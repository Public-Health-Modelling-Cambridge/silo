package de.tum.bgu.msm;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.events.handler.PersonDepartureEventHandler;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Estimates the sampling (scale) factor used to build a MATSim run from MITO's trips.csv.
 *
 * Each MITO-derived MATSim agent is one trip (agent id = t.id, see MitoMatsimScenarioAssemblerMCR).
 * The runner collects the person ids that depart in the events file, matches them against the
 * trip ids, and reports matched agents / trips per departure day and per MITO mode. Agents that
 * do not match a trip id (trucks, through traffic, transit drivers) are counted separately and
 * ignored for the ratio.
 *
 * Usage: CheckMatsimScaleFactor <trips.csv> <output_events.xml.gz> [day, e.g. thursday]
 * Without a day, the day with the most matched agents is used (one MATSim run = one day).
 */
public class CheckMatsimScaleFactor {

    private final static Logger logger = LogManager.getLogger(CheckMatsimScaleFactor.class);

    private static final double[] CANDIDATES = {0.01, 0.05, 0.1, 0.2, 0.25, 0.5, 1.0};

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: CheckMatsimScaleFactor <trips.csv> <events.xml.gz> [day]");
        }
        String tripsFile = args[0];
        String eventsFile = args[1];
        String requestedDay = args.length > 2 ? args[2] : null;

        // trip id -> {day, mode}
        Map<String, String[]> trips = readTrips(tripsFile);
        logger.info("Read " + trips.size() + " trips from " + tripsFile);

        Set<String> agents = readDepartingAgents(eventsFile);
        logger.info("Found " + agents.size() + " departing agents in " + eventsFile);

        // day -> mode -> {trips, matched}
        Map<String, Map<String, int[]>> counts = new TreeMap<>();
        for (Map.Entry<String, String[]> trip : trips.entrySet()) {
            int[] c = counts.computeIfAbsent(trip.getValue()[0], d -> new TreeMap<>())
                    .computeIfAbsent(trip.getValue()[1], m -> new int[2]);
            c[0]++;
            if (agents.contains(trip.getKey())) {
                c[1]++;
            }
        }
        long unmatched = agents.stream().filter(a -> !trips.containsKey(a)).count();

        String day = requestedDay;
        if (day == null) {
            day = counts.entrySet().stream()
                    .max(Comparator.comparingInt(e -> e.getValue().values().stream().mapToInt(c -> c[1]).sum()))
                    .map(Map.Entry::getKey).orElseThrow();
        }
        if (!counts.containsKey(day)) {
            throw new IllegalArgumentException("Day " + day + " not found in trips.csv; days are " + counts.keySet());
        }

        StringBuilder report = new StringBuilder("\nMatched agents / trips by departure day:\n");
        for (Map.Entry<String, Map<String, int[]>> e : counts.entrySet()) {
            int t = e.getValue().values().stream().mapToInt(c -> c[0]).sum();
            int m = e.getValue().values().stream().mapToInt(c -> c[1]).sum();
            report.append(String.format("  %-10s %9d / %9d = %.4f%n", e.getKey(), m, t, (double) m / t));
        }
        report.append("Agents not matching any trip id (freight, through traffic, ...): ").append(unmatched).append("\n");

        report.append("\nDay used: ").append(day).append(" — by MITO mode:\n");
        int dayTrips = 0;
        int dayMatched = 0;
        for (Map.Entry<String, int[]> e : counts.get(day).entrySet()) {
            int[] c = e.getValue();
            dayTrips += c[0];
            dayMatched += c[1];
            report.append(String.format("  %-15s %9d / %9d = %.4f%n", e.getKey(), c[1], c[0], (double) c[1] / c[0]));
        }

        // If only some modes were simulated (e.g. car/pt only), the sampled modes carry the factor;
        // non-simulated modes sit at ~0. Use the modes with a non-trivial share.
        int sampledTrips = 0;
        int sampledMatched = 0;
        for (int[] c : counts.get(day).values()) {
            if (c[1] > 0.005 * c[0]) {
                sampledTrips += c[0];
                sampledMatched += c[1];
            }
        }
        double overall = (double) dayMatched / dayTrips;
        double sampled = sampledTrips > 0 ? (double) sampledMatched / sampledTrips : 0.;
        double closest = CANDIDATES[0];
        for (double candidate : CANDIDATES) {
            if (Math.abs(candidate - sampled) < Math.abs(closest - sampled)) {
                closest = candidate;
            }
        }
        double sd = Math.sqrt(closest * (1 - closest) / Math.max(sampledTrips, 1));

        report.append(String.format("%nOverall ratio (all modes): %.4f%n", overall));
        report.append(String.format("Ratio over simulated modes: %.4f (%d / %d)%n", sampled, sampledMatched, sampledTrips));
        report.append(String.format("Most likely scale factor: %.2f (%.0f%%); expected random spread at that factor +/- %.4f%n",
                closest, closest * 100, 2 * sd));
        if (Math.abs(sampled - closest) > Math.max(4 * sd, 0.01)) {
            report.append("WARNING: ratio is far from every candidate — check that the trips.csv and events file belong to the same run/year/day.\n");
        }
        logger.info(report.toString());
    }

    private static Map<String, String[]> readTrips(String tripsFile) throws IOException {
        Map<String, String[]> trips = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(Path.of(tripsFile))) {
            List<String> header = Arrays.asList(reader.readLine().trim().split(","));
            int posId = header.indexOf("t.id");
            int posMode = header.indexOf("mode");
            int posDay = header.indexOf("departure_day");
            if (posId < 0 || posMode < 0 || posDay < 0) {
                throw new RuntimeException("Expected columns t.id, mode, departure_day in " + tripsFile);
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] record = line.split(",");
                trips.put(record[posId].trim(), new String[]{record[posDay].trim(), record[posMode].trim()});
            }
        }
        return trips;
    }

    private static Set<String> readDepartingAgents(String eventsFile) {
        Set<String> agents = new HashSet<>();
        EventsManager eventsManager = EventsUtils.createEventsManager();
        eventsManager.addHandler((PersonDepartureEventHandler) (PersonDepartureEvent event) ->
                agents.add(event.getPersonId().toString()));
        eventsManager.initProcessing();
        new MatsimEventsReader(eventsManager).readFile(eventsFile);
        eventsManager.finishProcessing();
        return agents;
    }
}
