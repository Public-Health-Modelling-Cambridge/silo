package de.tum.bgu.msm.health;

import org.matsim.api.core.v01.network.Link;
import routing.components.JctStress;
import routing.components.LinkStress;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

public class OsmLink {

    int osmId;
    public String roadType;
    public boolean onwysmm;
    // NaN when no member link has a speed limit (e.g. vehicle connectors); treated as the reference category
    public double speedLimitMPH;
    // true if member links disagree on roadType, speedLimitMPH or onwysmm
    public boolean mixedAttributes;

    public boolean bikeAllowed;
    public boolean carAllowed;
    public boolean walkAllowed;

    public double lengthSum;
    public double width;
    public double bikeStress;
    public double bikeStressJct;
    public double walkStressJct;

    // Updated to arrays for hourly demands
    public double[] carHourlyDemand = new double[24];
    public double[] truckHourlyDemand = new double[24];
    public double[] pedHourlyDemand = new double[24];
    public double[] bikeHourlyDemand = new double[24];
    public double[] motorHourlyDemand = new double[24];

    public Set<Link> networkLinks;

    public OsmLink(int osmId, Set<Link> links) {
        this.osmId = osmId;
        this.networkLinks = links;
    }

    public void computeAttributes() {
        if (networkLinks.isEmpty()) {
            // Set default values if no links
            this.roadType = "residential";
            this.onwysmm = false;
            this.speedLimitMPH = Double.NaN;
            this.bikeAllowed = false;
            this.carAllowed = false;
            this.walkAllowed = false;
            this.lengthSum = 0.0;
            this.width = 0.0;
            this.bikeStress = 0.0;
            this.bikeStressJct = 0.0;
            this.walkStressJct = 0.0;
            Arrays.fill(carHourlyDemand, 0.0);
            Arrays.fill(truckHourlyDemand, 0.0);
            Arrays.fill(pedHourlyDemand, 0.0);
            Arrays.fill(bikeHourlyDemand, 0.0);
            Arrays.fill(motorHourlyDemand, 0.0);
            return;
        }

        // Street-level categorical attributes: length-weighted mode over the member edges, ignoring missing values
        Collection<Link> edges = uniqueEdges();
        this.mixedAttributes = false;

        String type = lengthWeightedMode(edges, l -> getStringAttribute(l, "type", null));
        this.roadType = type != null ? type : roadTypeFromFlags(edges);

        Boolean oneWay = lengthWeightedMode(edges, l -> l.getAttributes().getAttribute("onwysmm") instanceof Boolean ?
                (Boolean) l.getAttributes().getAttribute("onwysmm") : null);
        this.onwysmm = oneWay != null ? oneWay : false;

        Double speed = lengthWeightedMode(edges, l -> {
            double s = getDoubleAttribute(l, "speedLimitMPH", Double.NaN);
            return Double.isNaN(s) || s <= 0 ? null : s;
        });
        this.speedLimitMPH = speed != null ? speed : Double.NaN;

        int bikeAllowedInt = networkLinks.stream()
                .mapToInt(link -> link.getAllowedModes().contains("bike") ? 1 : 0)
                .max().orElse(0);
        this.bikeAllowed = bikeAllowedInt == 1;

        int carAllowedInt = networkLinks.stream()
                .mapToInt(link -> link.getAllowedModes().contains("car") ? 1 : 0)
                .max().orElse(0);
        this.carAllowed = carAllowedInt == 1;

        int walkAllowedInt = networkLinks.stream()
                .mapToInt(link -> link.getAllowedModes().contains("walk") ? 1 : 0)
                .max().orElse(0);
        this.walkAllowed = walkAllowedInt == 1;

        //double totalLength = networkLinks.stream().mapToDouble(Link::getLength).sum();
        //this.lengthSum = (!onwysmm) ? totalLength / 2.0 : totalLength;

        this.lengthSum = edges.stream().mapToDouble(Link::getLength).sum();

        Map<Double, Long> widthFreq = networkLinks.stream()
                .map(l -> getDoubleAttribute(l, "width", 0.0))
                .filter(w -> w != 0.0)
                .collect(Collectors.groupingBy(w -> w, Collectors.counting()));
        this.width = widthFreq.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(0.0);

        double weightedSum = 0.0;
        double totalWeight = 0.0;
        for (Link link : networkLinks) {
            double stress = LinkStress.getStress(link, "bike");
            double length = link.getLength();
            if (!Double.isNaN(stress) && !Double.isInfinite(stress)) {
                weightedSum += stress * length;
                totalWeight += length;
            }
        }
        this.bikeStress = totalWeight > 0 ? weightedSum / totalWeight : 0.0;

        this.bikeStressJct = networkLinks.stream()
                .mapToDouble(link -> {
                    double stress = JctStress.getStress(link, "bike");
                    return Double.isNaN(stress) || Double.isInfinite(stress) ? Double.NEGATIVE_INFINITY : stress;
                })
                .max()
                .orElse(0.0);

        this.walkStressJct = networkLinks.stream()
                .mapToDouble(link -> {
                    double stress = JctStress.getStress(link, "walk");
                    return Double.isNaN(stress) || Double.isInfinite(stress) ? Double.NEGATIVE_INFINITY : stress;
                })
                .max()
                .orElse(0.0);
    }

    // One link per original network edge (out/rtn share an edgeID); links without a valid edgeID are kept individually.
    // Links are visited in ID order so the result does not depend on set iteration order.
    private Collection<Link> uniqueEdges() {
        Map<String, Link> edges = new LinkedHashMap<>();
        networkLinks.stream()
                .sorted(Comparator.comparing(l -> l.getId().toString()))
                .forEach(l -> {
                    double edgeId = getDoubleAttribute(l, "edgeID", -1.0);
                    String key = edgeId >= 0 ? "edge_" + (long) edgeId : "link_" + l.getId();
                    edges.putIfAbsent(key, l);
                });
        return edges.values();
    }

    // Value covering the largest length; null values (missing attribute) are ignored.
    // Ties are broken by the larger value so the result is deterministic.
    private <T extends Comparable<T>> T lengthWeightedMode(Collection<Link> edges, Function<Link, T> getter) {
        Map<T, Double> lengthByValue = new HashMap<>();
        for (Link link : edges) {
            T value = getter.apply(link);
            if (value != null) {
                lengthByValue.merge(value, link.getLength(), Double::sum);
            }
        }
        if (lengthByValue.size() > 1) {
            this.mixedAttributes = true;
        }
        return lengthByValue.entrySet().stream()
                .max(Map.Entry.<T, Double>comparingByValue().thenComparing(Map.Entry.<T, Double>comparingByKey()))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    // Fallback when no member link has a "type" (e.g. vehicle connectors), based on the road class flags
    private String roadTypeFromFlags(Collection<Link> edges) {
        if (edges.stream().anyMatch(l -> getBooleanAttribute(l, "motorway", false))) return "motorway";
        if (edges.stream().anyMatch(l -> getBooleanAttribute(l, "trunk", false))) return "trunk";
        if (edges.stream().anyMatch(l -> getBooleanAttribute(l, "primary", false))) return "primary";
        return "none";
    }

    private double getDoubleAttribute(Link l, String key, double defaultVal) {
        Object attr = l.getAttributes().getAttribute(key);
        return attr instanceof Number ? ((Number) attr).doubleValue() : defaultVal;
    }

    private String getStringAttribute(Link l, String key, String defaultVal) {
        Object attr = l.getAttributes().getAttribute(key);
        return attr instanceof String ? (String) attr : defaultVal;
    }

    private boolean getBooleanAttribute(Link l, String key, boolean defaultVal) {
        Object attr = l.getAttributes().getAttribute(key);
        return attr instanceof Boolean ? (Boolean) attr : defaultVal;
    }

    public Set<Link> getNetworkLinks() {
        return new HashSet<>(networkLinks);
    }
}