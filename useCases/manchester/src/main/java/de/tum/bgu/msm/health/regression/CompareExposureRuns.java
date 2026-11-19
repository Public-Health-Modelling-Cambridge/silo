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
 * - distribution of the normalised exposures by age group: mean, p10, p50, p90 before/after
 * - trip files (healthIndicators_*.csv): per column how many trips changed
 * - disease states (pp_healthDiseaseTracker_*.csv): per year how many persons changed state, and persons per disease
 * - link casualty rates of the accident model (matsim/[year]/[day]/casualtyRates.csv): per accident type how many links
 *   changed and the total before/after, and the links with the largest change
 * - checks on the new run: persons below background concentration, decomposition by source not adding up to the total
 *
 * Usage: CompareExposureRuns baselineDir newDir [backgroundPm25 backgroundNo2]
 */
public class CompareExposureRuns {

    private static final double TOLERANCE = 1e-5;
    private static final int TOP_CHANGES = 10;
    private static final String[] POLLUTANTS = {"pm25", "no2"};
    private static final String[] SOURCES = {"travel", "activity", "home"};
    private static final String[] EXPOSURES = {"pm25", "no2", "noise_Lden", "ndvi"};
    // lower bounds of the age groups
    private static final int[] AGE_GROUPS = {0, 18, 30, 45, 65, 75};

    private final Path baselineDir;
    private final Path newDir;
    private final Map<String, Double> background = new HashMap<>();
    private final StringBuilder report = new StringBuilder();
    private Map<String, Double> normalisationK = new HashMap<>();
    // mean ventilation rate of a person at home all week (m3/h): 16 h at 0.61 x 0.672 + 1.125 x 0.328, 8 h at 0.27
    private static final double HOME_WEEK_VENTILATION = (16 * (0.61 * (1 - 0.328) + 1.125 * 0.328) + 8 * 0.27) / 24;

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
            normalisationK = readNormalisationK(newDir);
            report.append("== Persons (").append(newPersons.getFileName()).append(") ==\n");
            compareTables(baseline, current);
            checkNewRun(current);
            reportLargestChanges(baseline, current);
            reportByAgeGroup(baseline, current);
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

        Path baselineDiseases = findFile(baselineDir, "pp_healthDiseaseTracker_");
        Path newDiseases = findFile(newDir, "pp_healthDiseaseTracker_");
        report.append("\n== Disease states (pp_healthDiseaseTracker_*.csv) ==\n");
        if (newDiseases == null) {
            report.append("   none in new run\n");
        } else {
            reportDiseaseStates(baselineDiseases == null ? null : Table.read(baselineDiseases, "id"), Table.read(newDiseases, "id"));
        }

        report.append("\n== Casualty rates (casualtyRates.csv) ==\n");
        List<Path> newCasualtyFiles = findFiles(newDir, "casualtyRates");
        if (newCasualtyFiles.isEmpty()) report.append("   none in new run\n");
        for (Path newCasualties : newCasualtyFiles) {
            // one file per day with the same name: match by the path within the run
            Path relative = newDir.relativize(newCasualties);
            Path baselineCasualties = baselineDir.resolve(relative);
            report.append("-- ").append(relative).append('\n');
            if (!Files.exists(baselineCasualties)) {
                report.append("   not in baseline\n");
                continue;
            }
            compareCasualties(CasualtyRates.read(baselineCasualties), CasualtyRates.read(newCasualties));
        }
        return report.toString();
    }

    // per year: persons whose state changed, and persons with each disease (states are lists separated by "|")
    private void reportDiseaseStates(Table baseline, Table current) {
        if (baseline == null) {
            report.append("   not in baseline\n");
        } else {
            Set<String> common = new TreeSet<>(baseline.rows.keySet());
            common.retainAll(current.rows.keySet());
            report.append(String.format("   rows: baseline %d, new %d, only in baseline %d, only in new %d%n",
                    baseline.rows.size(), current.rows.size(), baseline.rows.size() - common.size(), current.rows.size() - common.size()));
        }
        for (String year : current.columns.subList(1, current.columns.size())) {
            boolean inBaseline = baseline != null && baseline.columns.contains(year);
            Map<String, Integer> countBaseline = inBaseline ? countDiseases(baseline, year) : new TreeMap<>();
            Map<String, Integer> countNew = countDiseases(current, year);
            int changed = 0;
            if (inBaseline) {
                for (String id : current.rows.keySet()) {
                    if (baseline.rows.containsKey(id) && !diseases(baseline.get(id, year)).equals(diseases(current.get(id, year)))) changed++;
                }
            }
            report.append(String.format("%n   %s: %s%n   %-24s %10s %10s %10s%n", year,
                    inBaseline ? "persons changed " + changed : "not in baseline", "state", "baseline", "new", "change"));
            Set<String> states = new TreeSet<>(countBaseline.keySet());
            states.addAll(countNew.keySet());
            for (String state : states) {
                int x = countBaseline.getOrDefault(state, 0);
                int y = countNew.getOrDefault(state, 0);
                report.append(String.format("   %-24s %10s %10d %10s%n", state, inBaseline ? String.valueOf(x) : "-", y, inBaseline ? String.format("%+d", y - x) : "-"));
            }
        }
    }

    private static Map<String, Integer> countDiseases(Table table, String year) {
        Map<String, Integer> counts = new TreeMap<>();
        for (String id : table.rows.keySet()) {
            for (String disease : diseases(table.get(id, year))) {
                counts.merge(disease, 1, Integer::sum);
            }
        }
        return counts;
    }

    private static Set<String> diseases(String state) {
        return state == null || state.isEmpty() ? Set.of("null") : new TreeSet<>(Arrays.asList(state.split("\\|")));
    }

    private void compareCasualties(CasualtyRates baseline, CasualtyRates current) {
        Set<String> keys = new TreeSet<>(baseline.rates.keySet());
        keys.addAll(current.rates.keySet());
        long onlyBaseline = keys.stream().filter(key -> !current.rates.containsKey(key)).count();
        long onlyNew = keys.stream().filter(key -> !baseline.rates.containsKey(key)).count();
        report.append(String.format("   rows: baseline %d, new %d, only in baseline %d, only in new %d%n",
                baseline.rates.size(), current.rates.size(), onlyBaseline, onlyNew));

        // per accident type: rows changed, sum baseline, sum new
        Map<String, double[]> byType = new TreeMap<>();
        Set<String> linksChanged = new HashSet<>();
        Set<String> osmIdsChanged = new HashSet<>();
        List<String> changes = new ArrayList<>();
        for (String key : keys) {
            String linkId = key.substring(0, key.indexOf('|'));
            String type = key.substring(key.indexOf('|') + 1);
            double x = baseline.rates.getOrDefault(key, 0.);
            double y = current.rates.getOrDefault(key, 0.);
            double[] sums = byType.computeIfAbsent(type, t -> new double[3]);
            sums[1] += x;
            sums[2] += y;
            // rates are small (~1e-5 per link), so the tolerance is relative
            if (Math.abs(x - y) > TOLERANCE * Math.max(Math.abs(x), Math.abs(y))) {
                sums[0]++;
                linksChanged.add(linkId);
                osmIdsChanged.add(baseline.osmIds.getOrDefault(linkId, current.osmIds.get(linkId)));
                changes.add(key);
            }
        }
        report.append(String.format("   links changed: %d, OSM IDs: %d%n", linksChanged.size(), osmIdsChanged.size()));
        report.append(String.format("   %-14s %9s %14s %14s %9s%n", "accidentType", "changed", "sum baseline", "sum new", "change"));
        double totalBaseline = 0, totalNew = 0;
        for (Map.Entry<String, double[]> entry : byType.entrySet()) {
            double[] sums = entry.getValue();
            totalBaseline += sums[1];
            totalNew += sums[2];
            report.append(String.format("   %-14s %9d %14.6g %14.6g %8.3f%%%n", entry.getKey(), (long) sums[0], sums[1], sums[2], percentChange(sums[1], sums[2])));
        }
        report.append(String.format("   %-14s %9s %14.6g %14.6g %8.3f%%%n", "total", "", totalBaseline, totalNew, percentChange(totalBaseline, totalNew)));

        if (changes.isEmpty()) return;
        changes.sort(Comparator.comparingDouble((String key) ->
                -Math.abs(current.rates.getOrDefault(key, 0.) - baseline.rates.getOrDefault(key, 0.))));
        report.append(String.format("   largest changes:%n   %-14s %-12s %-12s %12s %12s %9s%n", "link", "type", "osmId", "baseline", "new", "change"));
        for (String key : changes.subList(0, Math.min(TOP_CHANGES, changes.size()))) {
            String linkId = key.substring(0, key.indexOf('|'));
            double x = baseline.rates.getOrDefault(key, 0.);
            double y = current.rates.getOrDefault(key, 0.);
            report.append(String.format("   %-14s %-12s %-12s %12.4g %12.4g %8.2f%%%n", linkId, key.substring(key.indexOf('|') + 1),
                    baseline.osmIds.getOrDefault(linkId, current.osmIds.get(linkId)), x, y, percentChange(x, y)));
        }
    }

    private static double percentChange(double before, double after) {
        return before == 0 ? (after == 0 ? 0 : Double.POSITIVE_INFINITY) : 100 * (after - before) / before;
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
            String homeConcentrationColumn = "exposure_home_concentration_" + pollutant;
            boolean hasHomeConcentration = current.columns.contains(homeConcentrationColumn);
            int belowBackground = 0, decompositionMismatch = 0, notAvailable = 0;
            // population means over persons with a home benchmark: normalised exposure vs. home concentration
            double sumTotal = 0, sumHome = 0;
            int withHome = 0;
            // persons at home all week (no travel or activity exposure): normalised exposure / home concentration
            double sumRatioHomeOnly = 0;
            int homeOnly = 0;
            for (String id : current.rows.keySet()) {
                Double total = parse(current.get(id, totalColumn));
                if (total == null) {
                    notAvailable++;
                    continue;
                }
                if (background.containsKey(pollutant) && total < background.get(pollutant) - TOLERANCE) {
                    belowBackground++;
                }
                Double home = hasHomeConcentration ? parse(current.get(id, homeConcentrationColumn)) : null;
                if (home != null && home > 0) {
                    sumTotal += total;
                    sumHome += home;
                    withHome++;
                    Double travel = parse(current.get(id, totalColumn + "_travel"));
                    Double activity = parse(current.get(id, totalColumn + "_activity"));
                    if (travel != null && activity != null && travel == 0 && activity == 0) {
                        sumRatioHomeOnly += total / home;
                        homeOnly++;
                    }
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
            // below background is not an error: lower ventilation or a cleaner home location give legitimately lower values
            report.append(String.format("   %-5s below background (%s, information only): %d | travel+activity+home != total: %d | NA: %d%n", pollutant,
                    background.containsKey(pollutant) ? String.valueOf(background.get(pollutant)) : "background not given",
                    belowBackground, decompositionMismatch, notAvailable));
            if (withHome > 0) {
                Double k = normalisationK.get(pollutant);
                report.append(String.format("   %-5s K = %s | mean normalised = %.4f, mean home concentration = %.4f (%d persons)%n",
                        pollutant, k == null ? "not found" : String.format("%.6f", k), sumTotal / withHome, sumHome / withHome, withHome));
                if (homeOnly > 0) {
                    // at home all week, the normalised exposure is K x the mean home ventilation rate x the home
                    // concentration; with a constant concentration: (16 x 0.779 + 8 x 0.27) / 24 = 0.609 m3/h
                    report.append(String.format("   %-5s at home all week: normalised / home concentration = %.4f (%d persons), K x 0.609 = %s%n",
                            pollutant, sumRatioHomeOnly / homeOnly, homeOnly, k == null ? "-" : String.format("%.4f", k * HOME_WEEK_VENTILATION)));
                }
            }
        }
    }

    // normalisation factor K by pollutant label (pm25, no2), from airPollutionNormalisationK.csv of the new run
    private Map<String, Double> readNormalisationK(Path dir) throws IOException {
        Map<String, Double> k = new HashMap<>();
        Path file = findFile(dir, "airPollutionNormalisationK");
        if (file == null) {
            return k;
        }
        List<String> lines = Files.readAllLines(file);
        List<String> header = Arrays.asList(lines.get(0).split(","));
        int posPollutant = header.indexOf("pollutant"), posK = header.indexOf("k");
        for (String line : lines.subList(1, lines.size())) {
            String[] values = line.split(",");
            k.put(values[posPollutant].replace(".", ""), Double.parseDouble(values[posK]));
        }
        return k;
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

    private void reportByAgeGroup(Table baseline, Table current) {
        if (!current.columns.contains("age")) return;
        for (String exposure : EXPOSURES) {
            String column = "exposure_normalised_" + exposure;
            if (!baseline.columns.contains(column) || !current.columns.contains(column)) continue;
            List<List<Double>> before = new ArrayList<>();
            List<List<Double>> after = new ArrayList<>();
            int[] changed = new int[AGE_GROUPS.length];
            for (int i = 0; i < AGE_GROUPS.length; i++) {
                before.add(new ArrayList<>());
                after.add(new ArrayList<>());
            }
            for (String id : current.rows.keySet()) {
                if (!baseline.rows.containsKey(id)) continue;
                Double age = parse(current.get(id, "age"));
                Double x = parse(baseline.get(id, column));
                Double y = parse(current.get(id, column));
                if (age == null || x == null || y == null) continue;
                int group = ageGroup(age.intValue());
                before.get(group).add(x);
                after.get(group).add(y);
                if (Math.abs(x - y) > TOLERANCE * Math.max(1, Math.abs(x))) changed[group]++;
            }
            report.append(String.format("%n   %s by age group (baseline -> new):%n   %-7s %8s %8s %19s %19s %19s %19s%n",
                    column, "age", "persons", "changed", "mean", "p10", "p50", "p90"));
            for (int i = 0; i < AGE_GROUPS.length; i++) {
                List<Double> x = before.get(i);
                List<Double> y = after.get(i);
                if (x.isEmpty()) continue;
                Collections.sort(x);
                Collections.sort(y);
                report.append(String.format("   %-7s %8d %8d %19s %19s %19s %19s%n", ageGroupLabel(i), x.size(), changed[i],
                        pair(mean(x), mean(y)), pair(quantile(x, 0.1), quantile(y, 0.1)),
                        pair(quantile(x, 0.5), quantile(y, 0.5)), pair(quantile(x, 0.9), quantile(y, 0.9))));
            }
        }
    }

    private static int ageGroup(int age) {
        int group = 0;
        while (group + 1 < AGE_GROUPS.length && age >= AGE_GROUPS[group + 1]) group++;
        return group;
    }

    private static String ageGroupLabel(int group) {
        return group + 1 < AGE_GROUPS.length ? AGE_GROUPS[group] + "-" + (AGE_GROUPS[group + 1] - 1) : AGE_GROUPS[group] + "+";
    }

    private static String pair(double before, double after) {
        return String.format("%.3f -> %.3f", before, after);
    }

    private static double mean(List<Double> sorted) {
        return sorted.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    private static double quantile(List<Double> sorted, double q) {
        double pos = q * (sorted.size() - 1);
        int lower = (int) Math.floor(pos);
        int upper = Math.min(lower + 1, sorted.size() - 1);
        return sorted.get(lower) + (pos - lower) * (sorted.get(upper) - sorted.get(lower));
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

    // casualtyRates.csv: osmId,linkId,accidentType,casualty; one row per link and accident type
    private static class CasualtyRates {
        private final Map<String, Double> rates = new HashMap<>();
        private final Map<String, String> osmIds = new HashMap<>();

        static CasualtyRates read(Path file) throws IOException {
            // not a Table: linkId repeats for each accident type
            CasualtyRates casualties = new CasualtyRates();
            try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                List<String> columns = Arrays.asList(in.readLine().split(",", -1));
                int osmId = columns.indexOf("osmId");
                int linkId = columns.indexOf("linkId");
                int type = columns.indexOf("accidentType");
                int casualty = columns.indexOf("casualty");
                if (osmId < 0 || linkId < 0 || type < 0 || casualty < 0) {
                    throw new IllegalStateException("Expected columns osmId, linkId, accidentType, casualty in " + file);
                }
                String line;
                while ((line = in.readLine()) != null) {
                    String[] row = line.split(",", -1);
                    casualties.rates.put(row[linkId] + "|" + row[type], Double.parseDouble(row[casualty]));
                    casualties.osmIds.put(row[linkId], row[osmId]);
                }
            }
            return casualties;
        }
    }
}
