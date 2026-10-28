package de.tum.bgu.msm.health.regression;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.Node;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.MultimodalNetworkCleaner;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Creates a small copy of the Manchester input data for regression tests of the health exposure model
 * (RunExposureHealthOffline), so that a test run takes minutes instead of hours.
 *
 * The test area is a circle around (centerX, centerY):
 * - households: a fixed random sample (seed) of households living in the area,
 *   plus households visited by the sampled persons' trips
 * - trips: trips of the sampled households with origin and destination in the area
 * - network: nodes within radius + buffer, cleaned per mode so that routing stays connected
 * - link/location concentration files (incl. bus): only rows of links in the cut network and of receiver points
 *   used by the kept households and trips
 * - MATSim outputs per day (car and bike/ped events, vehicles, noise): only events/rows of links in the cut network
 *   and of the kept receiver points
 * - accident model network (network_[scenario].xml): the cut network, as for the reference scenario it is network.xml
 * All other files are hard-linked (>= 10 MB, never edited) or copied from the source directory,
 * so properties and config files can be edited in the test directory without touching the source.
 *
 * Usage: CreateRegressionTestArea sourceDir targetDir [centerX centerY radius buffer households seed]
 */
public class CreateRegressionTestArea {

    private static final Logger logger = LogManager.getLogger(CreateRegressionTestArea.class);

    private static final String SCENARIO = "base_debug";
    private static final int YEAR = 2021;
    private static final String[] CONCENTRATION_DAYS = {"thursday", "saturday", "sunday"};
    private static final String[] NETWORK_MODES = {"car", "truck", "bike", "walk"};
    private static final long HARDLINK_MIN_SIZE = 10L * 1024 * 1024;

    private static final String ZONES = "input/zoneSystem.csv";
    private static final String HOUSEHOLDS = "microData/hh_" + YEAR + ".csv";
    private static final String PERSONS = "microData/pp_" + YEAR + ".csv";
    private static final String JOBS = "microData/jj_" + YEAR + ".csv";
    private static final String NETWORK = "input/mito/trafficAssignment/network.xml";
    // network read by AccidentRateModelOsmMCR; the accident model writes risks to every one of its links, so it has to be the same cut network
    private static final String ACCIDENT_NETWORK = "input/mito/trafficAssignment/network_" + SCENARIO + ".xml";
    private static final String BUS_LINK_CONCENTRATION = "input/mito/trafficAssignment/ptSimulation/linkConcentration_bus.csv";
    private static final String BUS_LOCATION_CONCENTRATION = "input/mito/trafficAssignment/ptSimulation/locationConcentration_bus.csv";
    private static final String SCENARIO_OUTPUT = "scenOutput/" + SCENARIO + "/";
    private static final String TRIPS = SCENARIO_OUTPUT + YEAR + "/microData/trips.csv";

    private final Path source;
    private final Path target;
    private final double centerX;
    private final double centerY;
    private final double radius;
    private final double buffer;
    private final int sampleSize;
    private final long seed;

    private final Set<Integer> areaZones = new HashSet<>();
    private final Map<Integer, Integer> dwellingByHousehold = new HashMap<>();
    private final Set<Integer> sampledHouseholds = new TreeSet<>();
    private final Set<Integer> keptHouseholds = new TreeSet<>();
    private final Set<Integer> destinationJobs = new HashSet<>();
    private final Set<String> receiverPoints = new HashSet<>();
    private final Set<String> links = new HashSet<>();
    private final Map<String, Long> counts = new LinkedHashMap<>();

    public static void main(String[] args) throws IOException {
        if (args.length != 2 && args.length != 8) {
            throw new IllegalArgumentException("Usage: CreateRegressionTestArea sourceDir targetDir [centerX centerY radius buffer households seed]");
        }
        // default: Manchester city centre, 3 km radius, 2 km network buffer, 2000 households
        String[] p = args.length == 8 ? args : new String[]{args[0], args[1], "384000", "398000", "3000", "2000", "2000", "1"};
        new CreateRegressionTestArea(Paths.get(p[0]), Paths.get(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                Double.parseDouble(p[4]), Double.parseDouble(p[5]), Integer.parseInt(p[6]), Long.parseLong(p[7])).run();
    }

    public CreateRegressionTestArea(Path source, Path target, double centerX, double centerY, double radius, double buffer, int sampleSize, long seed) {
        this.source = source;
        this.target = target;
        this.centerX = centerX;
        this.centerY = centerY;
        this.radius = radius;
        this.buffer = buffer;
        this.sampleSize = sampleSize;
        this.seed = seed;
    }

    public void run() throws IOException {
        if (Files.exists(target)) {
            throw new IllegalStateException("Target directory " + target + " already exists. Delete it first to recreate the test area.");
        }
        mirrorUnchangedFiles();
        readAreaZones();
        sampleHouseholds();
        cutTrips();
        cutHouseholdsAndPersons();
        resolveJobReceiverPoints();
        cutNetwork();
        for (String day : CONCENTRATION_DAYS) {
            filterById(SCENARIO_OUTPUT + "linkConcentration_" + day + ".csv", "linkId", links);
            filterById(SCENARIO_OUTPUT + "locationConcentration_" + day + ".csv", "id", receiverPoints);
        }
        filterById(BUS_LINK_CONCENTRATION, "linkId", links);
        filterById(BUS_LOCATION_CONCENTRATION, "id", receiverPoints);
        for (String day : CONCENTRATION_DAYS) {
            cutMatsimOutputs(day);
        }
        writeDescription();
        logger.info("Regression test area created in " + target);
    }

    private void mirrorUnchangedFiles() throws IOException {
        Set<Path> cutFiles = new HashSet<>();
        for (String file : new String[]{HOUSEHOLDS, PERSONS, NETWORK, BUS_LINK_CONCENTRATION, BUS_LOCATION_CONCENTRATION}) {
            cutFiles.add(source.resolve(file).normalize());
        }
        Path scenarioOutputs = source.resolve("scenOutput").normalize();
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (dir.normalize().equals(scenarioOutputs)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (cutFiles.contains(file.normalize())) {
                    return FileVisitResult.CONTINUE;
                }
                Path copy = target.resolve(source.relativize(file));
                if (attrs.size() >= HARDLINK_MIN_SIZE) {
                    Files.createLink(copy, file);
                } else {
                    Files.copy(file, copy);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        Files.createDirectories(target.resolve(TRIPS).getParent());
        logger.info("Mirrored unchanged input files");
    }

    private void readAreaZones() throws IOException {
        try (BufferedReader in = reader(ZONES)) {
            Header header = new Header(in.readLine());
            String line;
            while ((line = in.readLine()) != null) {
                String[] row = line.split(",");
                double dx = Double.parseDouble(row[header.pos("popCentroid_x")]) - centerX;
                double dy = Double.parseDouble(row[header.pos("popCentroid_y")]) - centerY;
                if (dx * dx + dy * dy <= radius * radius) {
                    areaZones.add(Integer.parseInt(row[header.pos("oaID")]));
                }
            }
        }
        counts.put("zones in area", (long) areaZones.size());
    }

    private void sampleHouseholds() throws IOException {
        List<Integer> areaHouseholds = new ArrayList<>();
        try (BufferedReader in = reader(HOUSEHOLDS)) {
            Header header = new Header(in.readLine());
            String line;
            while ((line = in.readLine()) != null) {
                String[] row = line.split(",");
                int id = Integer.parseInt(row[header.pos("id")]);
                dwellingByHousehold.put(id, Integer.parseInt(row[header.pos("dwelling")]));
                if (areaZones.contains(Integer.parseInt(row[header.pos("zone")]))) {
                    areaHouseholds.add(id);
                }
            }
        }
        Collections.sort(areaHouseholds);
        Collections.shuffle(areaHouseholds, new Random(seed));
        sampledHouseholds.addAll(areaHouseholds.subList(0, Math.min(sampleSize, areaHouseholds.size())));
        keptHouseholds.addAll(sampledHouseholds);
        counts.put("households in area", (long) areaHouseholds.size());
        counts.put("sampled households", (long) sampledHouseholds.size());
    }

    private void cutTrips() throws IOException {
        long kept = 0;
        try (BufferedReader in = reader(TRIPS); BufferedWriter out = writer(TRIPS)) {
            String headerLine = in.readLine();
            Header header = new Header(headerLine);
            out.write(headerLine);
            out.newLine();
            String line;
            while ((line = in.readLine()) != null) {
                String[] row = line.split(",");
                if (!sampledHouseholds.contains(Integer.parseInt(row[header.pos("hh.id")]))
                        || !areaZones.contains(Integer.parseInt(row[header.pos("originZone")]))
                        || !areaZones.contains(Integer.parseInt(row[header.pos("destinationZone")]))) {
                    continue;
                }
                addDestinationReceiverPoint(row[header.pos("destinationType")], Integer.parseInt(row[header.pos("destinationId")]));
                out.write(line);
                out.newLine();
                kept++;
            }
        }
        counts.put("trips", kept);
    }

    // same receiver point ids as HealthExposureModelMCR.getReceiverPointId
    private void addDestinationReceiverPoint(String type, int id) {
        switch (type) {
            case "household":
                keptHouseholds.add(id);
                receiverPoints.add("dd" + dwellingByHousehold.get(id));
                break;
            case "vacantDwelling":
                receiverPoints.add("dd" + id);
                break;
            case "job":
                destinationJobs.add(id);
                break;
            case "poi":
                receiverPoints.add("poi" + id);
                break;
            case "zoneCentroid":
                receiverPoints.add("zone" + id);
                break;
            case "school":
                receiverPoints.add("ss" + id);
                break;
            default:
                logger.warn("Unknown destination type " + type);
        }
    }

    private void cutHouseholdsAndPersons() throws IOException {
        counts.put("households (sampled + visited)", (long) filterRows(HOUSEHOLDS, "id", keptHouseholds));
        counts.put("persons", (long) filterRows(PERSONS, "hhid", keptHouseholds));
        for (int household : keptHouseholds) {
            receiverPoints.add("dd" + dwellingByHousehold.get(household));
        }
    }

    private void resolveJobReceiverPoints() throws IOException {
        try (BufferedReader in = reader(JOBS)) {
            Header header = new Header(in.readLine());
            String line;
            while ((line = in.readLine()) != null) {
                String[] row = line.split(",");
                if (!destinationJobs.contains(Integer.parseInt(row[header.pos("id")]))) {
                    continue;
                }
                String microlocationType = row[header.pos("microlocationType")];
                String building = row[header.pos("microBuildingID")];
                if ("poi".equals(microlocationType)) {
                    receiverPoints.add("poi" + building);
                } else if ("zoneCentroid".equals(microlocationType)) {
                    receiverPoints.add("zone" + building);
                }
            }
        }
        counts.put("receiver points", (long) receiverPoints.size());
    }

    private void cutNetwork() {
        Network network = NetworkUtils.readNetwork(source.resolve(NETWORK).toString());
        double maxDistance = radius + buffer;
        List<Id<Node>> outside = network.getNodes().values().stream()
                .filter(node -> Math.hypot(node.getCoord().getX() - centerX, node.getCoord().getY() - centerY) > maxDistance)
                .map(Node::getId)
                .collect(Collectors.toList());
        outside.forEach(network::removeNode);
        // cutting can disconnect parts of the network, keep only the largest connected part per mode
        for (String mode : NETWORK_MODES) {
            new MultimodalNetworkCleaner(network).run(Set.of(mode));
        }
        List<Id<Node>> isolated = network.getNodes().values().stream()
                .filter(node -> node.getInLinks().isEmpty() && node.getOutLinks().isEmpty())
                .map(Node::getId)
                .collect(Collectors.toList());
        isolated.forEach(network::removeNode);
        NetworkUtils.writeNetwork(network, target.resolve(NETWORK).toString());
        NetworkUtils.writeNetwork(network, target.resolve(ACCIDENT_NETWORK).toString());
        for (Id<Link> link : network.getLinks().keySet()) {
            links.add(link.toString());
        }
        counts.put("network nodes", (long) network.getNodes().size());
        counts.put("network links", (long) links.size());
    }

    // MATSim outputs read per day: car events (travel times for car routing, accident model), bike/ped events and
    // vehicles (accident model), noise emissions/immissions (exposure model)
    private void cutMatsimOutputs(String day) throws IOException {
        String dayOutput = SCENARIO_OUTPUT + "matsim/" + YEAR + "/" + day + "/";
        String car = dayOutput + "car/";
        Files.createDirectories(target.resolve(car + "noise-analysis/emissions"));
        Files.createDirectories(target.resolve(car + "noise-analysis/immissions"));
        Files.createDirectories(target.resolve(dayOutput + "bikePed"));

        for (String mode : new String[]{"car", "bikePed"}) {
            filterEvents(dayOutput + mode + "/" + YEAR + ".output_events.xml.gz");
            String vehicles = dayOutput + mode + "/" + YEAR + ".output_vehicles.xml.gz";
            Files.copy(source.resolve(vehicles), target.resolve(vehicles));
        }

        for (int hour = 1; hour <= 24; hour++) {
            String time = (hour * 3600.) + ".csv";
            filterNoise(car + "noise-analysis/emissions/emission_" + time, links);
            filterNoise(car + "noise-analysis/immissions/immission_" + time, receiverPoints);
        }
    }

    private void filterEvents(String events) throws IOException {
        long kept = 0;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new java.util.zip.GZIPInputStream(Files.newInputStream(source.resolve(events)), 1 << 16), StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(new java.util.zip.GZIPOutputStream(Files.newOutputStream(target.resolve(events)), 1 << 16), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                // one event per line; drop events on links outside the cut network, keep everything else
                int pos = line.indexOf(" link=\"");
                if (pos >= 0 && !links.contains(line.substring(pos + 7, line.indexOf('"', pos + 7)))) {
                    continue;
                }
                out.write(line);
                out.newLine();
                kept++;
            }
        }
        counts.put("lines " + events, kept);
    }

    // noise files are separated by ";" with the link or receiver point id in the first column
    private void filterNoise(String file, Set<String> ids) throws IOException {
        try (BufferedReader in = reader(file); BufferedWriter out = writer(file)) {
            out.write(in.readLine());
            out.newLine();
            String line;
            while ((line = in.readLine()) != null) {
                if (ids.contains(line.substring(0, line.indexOf(';')))) {
                    out.write(line);
                    out.newLine();
                }
            }
        }
    }

    private void filterById(String file, String idColumn, Set<String> ids) throws IOException {
        long kept = 0;
        try (BufferedReader in = reader(file); BufferedWriter out = writer(file)) {
            String headerLine = in.readLine();
            int pos = new Header(headerLine).pos(idColumn);
            out.write(headerLine);
            out.newLine();
            String line;
            while ((line = in.readLine()) != null) {
                // the id is the first column in all concentration files, avoid splitting every row
                String id = pos == 0 ? line.substring(0, line.indexOf(',')) : line.split(",")[pos];
                if (ids.contains(id)) {
                    out.write(line);
                    out.newLine();
                    kept++;
                }
            }
        }
        counts.put("rows " + file, kept);
    }

    private int filterRows(String file, String idColumn, Set<Integer> ids) throws IOException {
        int kept = 0;
        try (BufferedReader in = reader(file); BufferedWriter out = writer(file)) {
            String headerLine = in.readLine();
            int pos = new Header(headerLine).pos(idColumn);
            out.write(headerLine);
            out.newLine();
            String line;
            while ((line = in.readLine()) != null) {
                if (ids.contains(Integer.parseInt(line.split(",")[pos]))) {
                    out.write(line);
                    out.newLine();
                    kept++;
                }
            }
        }
        return kept;
    }

    private void writeDescription() throws IOException {
        try (BufferedWriter out = writer("regressionTestArea.txt")) {
            out.write("Regression test area for RunExposureHealthOffline, created " + LocalDateTime.now() + " by CreateRegressionTestArea\n");
            out.write("source: " + source + "\n");
            out.write(String.format("center: %.0f, %.0f | radius: %.0f m | network buffer: %.0f m | households: %d | seed: %d%n",
                    centerX, centerY, radius, buffer, sampleSize, seed));
            for (Map.Entry<String, Long> count : counts.entrySet()) {
                out.write(count.getKey() + ": " + count.getValue() + "\n");
            }
        }
        counts.forEach((key, value) -> logger.info(key + ": " + value));
    }

    private BufferedReader reader(String file) throws IOException {
        return Files.newBufferedReader(source.resolve(file), StandardCharsets.UTF_8);
    }

    private BufferedWriter writer(String file) throws IOException {
        return Files.newBufferedWriter(target.resolve(file), StandardCharsets.UTF_8);
    }

    private static class Header {
        private final List<String> columns;

        Header(String line) {
            columns = Arrays.asList(line.split(","));
        }

        int pos(String column) {
            int pos = columns.indexOf(column);
            if (pos < 0) {
                throw new IllegalStateException("Column " + column + " not found in " + columns);
            }
            return pos;
        }
    }
}
