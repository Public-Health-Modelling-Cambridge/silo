package de.tum.bgu.msm.health.regression;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Compares the outputs of two runs of RunExposureHealthOffline (e.g. before and after a fix):
 * - person exposure file (pp_exposure_*.csv): per column how many persons changed, mean before/after, largest change,
 *   and the persons with the largest change in normalised PM2.5 and NO2
 * - trip files (healthIndicators_*.csv): per column how many trips changed
 * - checks on the new run: persons below background concentration, decomposition by source not adding up to the total
 *
 * Usage: CompareExposureRuns baselineDir newDir [backgroundPm25 backgroundNo2]
 */
public class CompareExposureRuns {

    private static final double TOLERANCE = 1e-5;
    private static final int TOP_CHANGES = 10;
    private static final String[] POLLUTANTS = {"pm25", "no2"};
    private static final String[] SOURCES = {"travel", "activity", "home"};

    private final Path baselineDir;
    private final Path newDir;
    private final Map<String, Double> background = new HashMap<>();
    private final StringBuilder report = new StringBuilder();

    public static void main(String[] args) throws IOException {
        if (args.length != 2 && args.length != 4) {
            throw new IllegalArgumentException("Usage: CompareExposureRuns baselineDir newDir [backgroundPm25 backgroundNo2]");
        }
        CompareExposureRuns comparison = new CompareExposureRuns(Paths.get(args[0]), Paths.get(args[1]));
        if (args.length == 4) {
            comparison.setBackground(Double.parseDouble(args[2]), Double.parseDouble(args[3]));
        }
        System.out.println(comparison.compare());
    }

    public CompareExposureRuns(Path baselineDir, Path newDir) {
        this.baselineDir = baselineDir;
        this.newDir = newDir;
    }

    public void setBackground(double pm25, double no2) {
        background.put("pm25", pm25);
        background.put("no2", no2);
    }

    public String compare() throws IOException {
        report.append("Baseline: ").append(baselineDir).append('\n');
        report.append("New:      ").append(newDir).append("\n\n");

        Path baselinePersons = findFile(baselineDir, "pp_exposure_");
        Path newPersons = findFile(newDir, "pp_exposure_");
        if (baselinePersons == null || newPersons == null) {
            report.append("Person exposure file pp_exposure_*.csv missing in ").append(baselinePersons == null ? baselineDir : newDir).append('\n');
        } else {
            Table baseline = Table.read(baselinePersons, "id");
            Table current = Table.read(newPersons, "id");
            report.append("== Persons (").append(newPersons.getFileName()).append(") ==\n");
            compareTables(baseline, current);
            checkNewRun(current);
            reportLargestChanges(baseline, current);
        }

        report.append("\n== Trips (healthIndicators_*.csv) ==\n");
        for (Path newTrips : findFiles(newDir, "healthIndicators_")) {
            Path baselineTrips = findFile(baselineDir, newTrips.getFileName().toString());
            report.append("-- ").append(newTrips.getFileName()).append('\n');
            if (baselineTrips == null) {
                report.append("   not in baseline\n");
                continue;
            }
            compareTables(Table.read(baselineTrips, "t.id"), Table.read(newTrips, "t.id"));
        }
        return report.toString();
    }

    private void compareTables(Table baseline, Table current) {
        Set<String> common = new TreeSet<>(baseline.rows.keySet());
        common.retainAll(current.rows.keySet());
        report.append(String.format("   rows: baseline %d, new %d, only in baseline %d, only in new %d%n",
                baseline.rows.size(), current.rows.size(), baseline.rows.size() - common.size(), current.rows.size() - common.size()));

        List<String> unchanged = new ArrayList<>();
        report.append(String.format("   %-42s %9s %8s %14s %14s %14s%n", "column", "changed", "NA diff", "mean baseline", "mean new", "max |change|"));
        for (String column : current.columns) {
            if (!baseline.columns.contains(column)) {
                report.append(String.format("   %-42s new column%n", column));
                continue;
            }
            int changed = 0, naChanged = 0, nBaseline = 0, nNew = 0;
            double sumBaseline = 0, sumNew = 0, maxChange = 0;
            boolean numeric = false;
            for (String id : common) {
                String a = baseline.get(id, column);
                String b = current.get(id, column);
                Double x = parse(a);
                Double y = parse(b);
                if (x == null && y == null) {
                    if (!Objects.equals(a, b)) changed++;
                    continue;
                }
                numeric = true;
                if (x == null || y == null) {
                    naChanged++;
                    continue;
                }
                sumBaseline += x; nBaseline++;
                sumNew += y; nNew++;
                double change = Math.abs(x - y);
                if (change > TOLERANCE * Math.max(1, Math.abs(x))) {
                    changed++;
                    maxChange = Math.max(maxChange, change);
                }
            }
            if (changed == 0 && naChanged == 0) {
                unchanged.add(column);
            } else if (numeric) {
                report.append(String.format("   %-42s %9d %8d %14.6g %14.6g %14.6g%n", column, changed, naChanged,
                        sumBaseline / Math.max(1, nBaseline), sumNew / Math.max(1, nNew), maxChange));
            } else {
                report.append(String.format("   %-42s %9d (text)%n", column, changed));
            }
        }
        for (String column : baseline.columns) {
            if (!current.columns.contains(column)) {
                report.append(String.format("   %-42s removed column%n", column));
            }
        }
        report.append("   unchanged: ").append(unchanged.isEmpty() ? "-" : String.join(", ", unchanged)).append('\n');
    }

    private void checkNewRun(Table current) {
        report.append("\n   checks on new run:\n");
        for (String pollutant : POLLUTANTS) {
            String totalColumn = "exposure_normalised_" + pollutant;
            if (!current.columns.contains(totalColumn)) continue;
            int belowBackground = 0, decompositionMismatch = 0, notAvailable = 0;
            for (String id : current.rows.keySet()) {
                Double total = parse(current.get(id, totalColumn));
                if (total == null) {
                    notAvailable++;
                    continue;
                }
                if (background.containsKey(pollutant) && total < background.get(pollutant) - TOLERANCE) {
                    belowBackground++;
                }
                double sum = 0;
                boolean complete = true;
                for (String source : SOURCES) {
                    String column = totalColumn + "_" + source;
                    Double value = current.columns.contains(column) ? parse(current.get(id, column)) : null;
                    if (value == null) complete = false;
                    else sum += value;
                }
                if (complete && Math.abs(sum - total) > 1e-3 * Math.max(1, Math.abs(total))) {
                    decompositionMismatch++;
                }
            }
            report.append(String.format("   %-5s below background (%s): %d | travel+activity+home != total: %d | NA: %d%n", pollutant,
                    background.containsKey(pollutant) ? String.valueOf(background.get(pollutant)) : "background not given",
                    belowBackground, decompositionMismatch, notAvailable));
        }
    }

    private void reportLargestChanges(Table baseline, Table current) {
        for (String pollutant : POLLUTANTS) {
            String column = "exposure_normalised_" + pollutant;
            if (!baseline.columns.contains(column) || !current.columns.contains(column)) continue;
            List<double[]> changes = new ArrayList<>();
            for (String id : current.rows.keySet()) {
                if (!baseline.rows.containsKey(id)) continue;
                Double x = parse(baseline.get(id, column));
                Double y = parse(current.get(id, column));
                if (x != null && y != null && Math.abs(x - y) > TOLERANCE * Math.max(1, Math.abs(x))) {
                    changes.add(new double[]{Double.parseDouble(id), x, y});
                }
            }
            changes.sort((a, b) -> Double.compare(Math.abs(b[2] - b[1]), Math.abs(a[2] - a[1])));
            report.append(String.format("%n   largest changes in %s:%n   %12s %12s %12s %12s%n", column, "person", "baseline", "new", "change"));
            for (double[] change : changes.subList(0, Math.min(TOP_CHANGES, changes.size()))) {
                report.append(String.format("   %12d %12.4f %12.4f %+12.4f%n", (long) change[0], change[1], change[2], change[2] - change[1]));
            }
            if (changes.isEmpty()) report.append("   none\n");
        }
    }

    private static Double parse(String value) {
        if (value == null || value.isEmpty() || value.equals("NA") || value.equals("null")) return null;
        try {
            double parsed = Double.parseDouble(value);
            return Double.isNaN(parsed) ? null : parsed;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Path findFile(Path dir, String prefix) throws IOException {
        List<Path> files = findFiles(dir, prefix);
        return files.isEmpty() ? null : files.get(0);
    }

    private static List<Path> findFiles(Path dir, String prefix) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(file -> file.getFileName().toString().startsWith(prefix) && file.toString().endsWith(".csv"))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    private static class Table {
        private final List<String> columns;
        private final Map<String, String[]> rows = new LinkedHashMap<>();

        private Table(List<String> columns) {
            this.columns = columns;
        }

        static Table read(Path file, String idColumn) throws IOException {
            try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                Table table = new Table(Arrays.asList(in.readLine().split(",", -1)));
                int idPos = table.columns.indexOf(idColumn);
                if (idPos < 0) {
                    throw new IllegalStateException("Column " + idColumn + " not found in " + file);
                }
                String line;
                while ((line = in.readLine()) != null) {
                    String[] row = line.split(",", -1);
                    table.rows.put(row[idPos], row);
                }
                return table;
            }
        }

        String get(String id, String column) {
            String[] row = rows.get(id);
            int pos = columns.indexOf(column);
            return row == null || pos < 0 || pos >= row.length ? null : row[pos];
        }
    }
}
