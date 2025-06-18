package de.tum.bgu.msm.health;

import cern.colt.map.tfloat.OpenIntFloatHashMap;
import de.tum.bgu.msm.health.data.LinkInfo;
import de.tum.bgu.msm.health.injury.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.utils.objectattributes.attributable.Attributes;

import java.util.*;
import java.util.stream.Collectors;

public class CasualtyRateCalculationMCR {
    private static final Logger log = LogManager.getLogger(CasualtyRateCalculationMCR.class);
    private final double SCALEFACTOR;
    private final AnalysisEventHandler analzyer;
    private final AccidentsContext accidentsContext;
    private final Map<String, Double> binaryLogitCoef;
    private final AccidentType accidentType;
    private final AccidentSeverity accidentSeverity;
    private final Scenario scenario;
    private final Map<Integer, List<Link>> linksByOsmId = new HashMap<>();
    private final Set<OsmLink> osmLinkDataset = new HashSet<>();
    private final Random random = new Random(42);

    public CasualtyRateCalculationMCR(double scaleFactor, AccidentsContext accidentsContext, AnalysisEventHandler analzyer, AccidentType accidentType, AccidentSeverity accidentSeverity, String basePath, Scenario scenario) {
        this.SCALEFACTOR = scaleFactor;
        this.accidentsContext = accidentsContext;
        this.analzyer = analzyer;
        this.accidentType = accidentType;
        this.accidentSeverity = accidentSeverity;
        this.binaryLogitCoef = new AccidentRateModelCoefficientReader(accidentType, accidentSeverity, basePath + "binaryModel.csv").readData();
        this.scenario = scenario;
    }

    public void run(Collection<? extends Link> links) {
        createOsmLinkDataset(links);

        for (OsmLink osmLink : osmLinkDataset) {
            computeLinkCasualtyFrequency(osmLink);
            assignRiskToLinks(osmLink);
            assignCasualtyBinaryToLinks(osmLink);
        }
    }

    private void createOsmLinkDataset(Collection<? extends Link> links) {
        for (Link link : links) {
            Integer osmId = (Integer) link.getAttributes().getAttribute("osmId");
            if (osmId == null) continue;
            linksByOsmId.computeIfAbsent(osmId, k -> new ArrayList<>()).add(link);
        }

        for (Map.Entry<Integer, List<Link>> entry : linksByOsmId.entrySet()) {
            int osmId = entry.getKey();
            List<Link> groupLinks = entry.getValue();
            if (groupLinks.isEmpty()) continue;

            Link first = groupLinks.get(0);
            String roadType = getStringAttribute(first.getAttributes(), "type", "residential");
            String highway = getStringAttribute(first.getAttributes(), "highway", "unclassified");
            String onwysmm = getStringAttribute(first.getAttributes(), "onwysmm", "Two Way");
            double speedLimit = getDoubleAttribute(first.getAttributes(), "speedLimitMPH", 0.0);

            OsmLink osmLink = new OsmLink(osmId, roadType, highway, onwysmm, speedLimit, new HashSet<>(groupLinks));
            osmLinkDataset.add(osmLink);
        }
    }

    private void computeLinkCasualtyFrequency(OsmLink osmLink) {
        Set<Link> links = osmLink.getNetworkLinks();
        OpenIntFloatHashMap hourlyCasualtyRate = new OpenIntFloatHashMap();

        for (int hour = 0; hour < 24; hour++) {
            double probCrash = getProbabilityCrashBinaryLogit(osmLink, hour);
            probCrash = probCrash/ 5.0;
            hourlyCasualtyRate.put(hour, (float) probCrash);
        }

        // create a hash map storing the probCrash per osmID, mode, hour

        accidentsContext.getOsmId2info().get(osmLink.getOsmId())
                .getSevereFatalCasualityExposureByAccidentTypeByTime()
                .put(accidentType, hourlyCasualtyRate);
    }

    private double getProbabilityCrashBinaryLogit(OsmLink osmLink, int hour) {
        Set<Link> links = osmLink.getNetworkLinks();
        double utility = binaryLogitCoef.getOrDefault("(Intercept)", 0.0);

        double truckHourlyDemand = links.stream().mapToDouble(l -> analzyer.getDemand(l.getId(), "truck", hour)).average().orElse(0.0) * SCALEFACTOR;
        double pedHourlyDemand = links.stream().mapToDouble(l -> analzyer.getDemand(l.getId(), "walk", hour)).average().orElse(0.0);
        double carHourlyDemand = links.stream().mapToDouble(l -> analzyer.getDemand(l.getId(), "car", hour)).average().orElse(0.0) * SCALEFACTOR;
        double bikeHourlyDemand = links.stream().mapToDouble(l -> analzyer.getDemand(l.getId(), "bike", hour)).average().orElse(0.0);
        double motorHourlyDemand = carHourlyDemand + truckHourlyDemand;

        if (pedHourlyDemand == 0 && accidentType == AccidentType.PED) return 0;
        if (carHourlyDemand == 0 && accidentType.name().contains("CAR")) return 0;
        if (bikeHourlyDemand == 0 && accidentType.name().contains("BIKE")) return 0;

        utility += binaryLogitCoef.getOrDefault("log1p(truck_flow)", 0.0) * Math.log1p(truckHourlyDemand);
        utility += binaryLogitCoef.getOrDefault("log1p(ped_flow)", 0.0) * Math.log1p(pedHourlyDemand);
        utility += binaryLogitCoef.getOrDefault("log1p(car_flow)", 0.0) * Math.log1p(carHourlyDemand);
        utility += binaryLogitCoef.getOrDefault("log(bike_flow + 0.1)", 0.0) * Math.log(bikeHourlyDemand + 0.1);
        utility += binaryLogitCoef.getOrDefault("motor_flow", 0.0) * motorHourlyDemand;
        utility += binaryLogitCoef.getOrDefault("log1p(motor_flow)", 0.0) * Math.log1p(motorHourlyDemand);

        utility += binaryLogitCoef.getOrDefault("log(length_sum)", 0.0) * Math.log(osmLink.lengthSum);
        utility += binaryLogitCoef.getOrDefault("bikeStress", 0.0) * osmLink.bikeStress;
        utility += binaryLogitCoef.getOrDefault("bikeStressJct", 0.0) * osmLink.bikeStressJct;
        utility += binaryLogitCoef.getOrDefault("walkStressJct", 0.0) * osmLink.walkStressJct;
        utility += binaryLogitCoef.getOrDefault("width", 0.0) * osmLink.width;

        double speedLimit = osmLink.speedLimitMPH;
        if (speedLimit < 20) utility += binaryLogitCoef.getOrDefault("speed_limit<20 MPH", 0.0);
        else if (speedLimit < 30) utility += binaryLogitCoef.getOrDefault("speed_limit20 - 29 MPH", 0.0);

        String roadType = osmLink.roadType != null ? osmLink.roadType : "residential";
        if (roadType.matches("primary|primary_link|trunk|trunk_link"))
            utility += binaryLogitCoef.getOrDefault("roadPrimary/Trunk", 0.0);
        else if (!roadType.contains("motorway")) {
            if (speedLimit < 20) utility += binaryLogitCoef.getOrDefault("road<20MPH", 0.0);
            else if (speedLimit < 30) utility += binaryLogitCoef.getOrDefault("road20 - 29 MPH", 0.0);
        }

        return Math.exp(utility) / (1.0 + Math.exp(utility));
    }

    private void assignRiskToLinks(OsmLink osmLink) {
        Set<Link> links = osmLink.getNetworkLinks();
        OpenIntFloatHashMap osmRisk = accidentsContext.getOsmId2info().get(osmLink.getOsmId())
                .getSevereFatalCasualityExposureByAccidentTypeByTime()
                .get(accidentType);

        double totalLength = links.stream().mapToDouble(Link::getLength).sum();

        for (Link link : links) {
            double linkLength = link.getLength();
            OpenIntFloatHashMap weightedRisk = new OpenIntFloatHashMap();
            for (int hour = 0; hour < 24; hour++) {
                float osmRate = osmRisk.get(hour);
                float linkRisk = (float) (osmRate * (linkLength / totalLength));
                weightedRisk.put(hour, linkRisk);
            }
            accidentsContext.getLinkId2info().get(link.getId())
                    .getSevereFatalCasualityExposureByAccidentTypeByTime()
                    .put(accidentType, weightedRisk);
        }
    }

    private void assignCasualtyBinaryToLinks(OsmLink osmLink) {
        Set<Link> links = osmLink.getNetworkLinks();
        for (Link link : links) {
            OpenIntFloatHashMap risk = accidentsContext.getLinkId2info().get(link.getId())
                    .getSevereFatalCasualityExposureByAccidentTypeByTime()
                    .get(accidentType);

            Map<Integer, Integer> binaryCasualties = new HashMap<>();
            for (int hour = 0; hour < 24; hour++) {
                float riskValue = risk.get(hour);
                binaryCasualties.put(hour, random.nextDouble() < riskValue ? 1 : 0);
            }

            accidentsContext.getLinkId2info().get(link.getId())
                    .getSevereFatalCasualityBinaryByAccidentTypeByTime()
                    .put(accidentType, binaryCasualties);
        }
    }

    private String getStringAttribute(Attributes attributes, String key, String defaultValue) {
        Object value = attributes.getAttribute(key);
        return value instanceof String ? (String) value : defaultValue;
    }

    private double getDoubleAttribute(Attributes attributes, String key, double defaultValue) {
        Object value = attributes.getAttribute(key);
        return value instanceof Number ? ((Number) value).doubleValue() : defaultValue;
    }
}
