package de.tum.bgu.msm.health.injury;

import org.matsim.api.core.v01.network.Link;
import routing.components.JctStress;
import routing.components.LinkStress;

import java.util.*;
import java.util.stream.Collectors;

public class OsmLink {

    int osmId;
    public String roadType;
    public String highway;
    public String onwysmm;
    public double speedLimitMPH;

    public boolean bikeAllowed;
    public boolean carAllowed;
    public boolean walkAllowed;

    public double lengthSum;
    public double width;
    public double bikeStress;
    public double bikeStressJct;
    public double walkStressJct;

    public Set<Link> networkLinks;

    public OsmLink(int osmId, String roadType, String highway, String onwysmm, double speedLimitMPH, Set<Link> links) {
        this.osmId = osmId;
        this.highway = highway;
        this.onwysmm = onwysmm;
        this.speedLimitMPH = speedLimitMPH;
        this.networkLinks = links;

        computeAttributes();
    }

    private void computeAttributes() {

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

        double totalLength = networkLinks.stream().mapToDouble(Link::getLength).sum();
        this.lengthSum = onwysmm.startsWith("Two Way") ? totalLength : totalLength / 2.0;

        this.roadType = networkLinks.stream()
                .map(link -> getStringAttribute(link, "type", "residential"))
                .filter(Objects::nonNull)
                .findFirst().orElse("residential");

        Map<Double, Long> widthFreq = networkLinks.stream()
                .map(l -> (Double) l.getAttributes().getAttribute("width"))
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(w -> w, Collectors.counting()));
        this.width = widthFreq.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(0.0);

        double weightedSum = 0.0;
        double totalWeight = 0.0;
        for (Link link : networkLinks) {
            double stress = LinkStress.getStress(link, "bike");
            double length = link.getLength();
            if (!Double.isNaN(stress)) {
                weightedSum += stress * length;
                totalWeight += length;
            }
        }
        this.bikeStress = totalWeight > 0 ? weightedSum / totalWeight : 0.0;

        this.bikeStressJct = networkLinks.stream()
                .mapToDouble(link -> {
                    double stress = JctStress.getStress(link, "bike");
                    return Double.isNaN(stress) ? 0.0 : stress;
                })
                .max().orElse(0.0);

        this.walkStressJct = networkLinks.stream()
                .mapToDouble(link -> {
                    double stress = JctStress.getStress(link, "walk");
                    return Double.isNaN(stress) ? 0.0 : stress;
                })
                .max().orElse(0.0);
    }

    private double getDoubleAttribute(Link l, String key, double defaultVal) {
        Object attr = l.getAttributes().getAttribute(key);
        return attr instanceof Number ? ((Number) attr).doubleValue() : defaultVal;
    }

    private String getStringAttribute(Link l, String key, String defaultVal) {
        Object attr = l.getAttributes().getAttribute(key);
        return attr instanceof String ? (String) attr : defaultVal;
    }

    //public Set<Link> getNetworkLinks() {}
}
