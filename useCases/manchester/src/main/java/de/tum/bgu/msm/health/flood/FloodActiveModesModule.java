package de.tum.bgu.msm.health.flood;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.bicycle.BicycleLinkSpeedCalculator;
import org.matsim.core.config.Config;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.router.costcalculators.TravelDisutilityFactory;
import org.matsim.core.router.util.TravelTime;
import routing.BicycleConfigGroup;
import routing.WalkConfigGroup;
import routing.travelTime.BicycleLinkSpeedCalculatorImpl;
import routing.travelTime.WalkLinkSpeedCalculator;
import routing.travelTime.WalkLinkSpeedCalculatorImpl;

/**
 * Makes walking and cycling respond to the flood, by overriding the travel time and travel
 * disutility that {@code WalkModule} and {@code BicycleModule} bind by default.
 * <p>
 * Add this <b>after</b> those two modules, as an overriding module, so these bindings win:
 * <pre>
 * controler.addOverridingModule(new WalkModule());
 * controler.addOverridingModule(new BicycleModule());
 * controler.addOverridingModule(new FloodActiveModesModule(depthData, hazardWeight));
 * </pre>
 * Nothing in mito or in the MATSim bicycle contrib is modified. The dry-weather speed logic
 * (gradient, surface, dismount) is reused as-is and the flood factor is layered on top.
 */
public final class FloodActiveModesModule extends AbstractModule {

    private static final Logger logger = LogManager.getLogger(FloodActiveModesModule.class);

    private final WaterDepthData depthData;
    private final double hazardWeight;

    /**
     * @param hazardWeight extra avoidance of flooded links beyond their slowness, as a
     *                     multiplier on the routing cost: {@code 1 + hazardWeight * depthFraction}.
     *                     Pass 0 to let the flood act through travel time alone — the
     *                     conservative default until the term is calibrated.
     */
    public FloodActiveModesModule(WaterDepthData depthData, double hazardWeight) {
        this.depthData = depthData;
        this.hazardWeight = hazardWeight;
    }

    @Override
    public void install() {
        Config config = getConfig();
        WalkConfigGroup walkConfig = (WalkConfigGroup) config.getModules().get(WalkConfigGroup.GROUP_NAME);
        BicycleConfigGroup bikeConfig = (BicycleConfigGroup) config.getModules().get(BicycleConfigGroup.GROUP_NAME);

        FloodResponse walkResponse = FloodResponse.forWalk();
        FloodResponse bikeResponse = FloodResponse.forBike();

        // --- walk -----------------------------------------------------------------------
        WalkLinkSpeedCalculator walkSpeed = new WalkLinkSpeedCalculatorImpl(config);
        TravelTime walkTravelTime = new FloodAwareActiveTravelTime(
                walkSpeed::getMaximumVelocityForLink, depthData, walkResponse);

        addTravelTimeBinding(walkConfig.getMode()).toInstance(walkTravelTime);
        addTravelDisutilityFactoryBinding(walkConfig.getMode()).toInstance(
                tt -> new FloodAwareActiveDisutility(walkConfig, tt, depthData, walkResponse, hazardWeight));

        // --- bike -----------------------------------------------------------------------
        BicycleLinkSpeedCalculator bikeSpeed = new BicycleLinkSpeedCalculatorImpl(config);
        TravelTime bikeTravelTime = new FloodAwareActiveTravelTime(
                bikeSpeed::getMaximumVelocityForLink, depthData, bikeResponse);

        addTravelTimeBinding(bikeConfig.getMode()).toInstance(bikeTravelTime);
        addTravelDisutilityFactoryBinding(bikeConfig.getMode()).toInstance(
                tt -> new FloodAwareActiveDisutility(bikeConfig, tt, depthData, bikeResponse, hazardWeight));

        logger.warn("Flood-aware active modes installed: " + depthData.getWetLinkCount()
                + " wet links, hazard weight " + hazardWeight + ".");
    }

    /**
     * Optional hard closure: removes walk and bike from the allowed modes of links that are
     * impassable at their peak depth. Call once on the scenario network, before the Controler
     * is built.
     * <p>
     * This is a <b>static</b> closure — a link shut at its peak is shut all day — whereas the
     * disutility check in {@link FloodAwareActiveDisutility} is time-dependent and generally
     * preferable. Use this only when a permanently severed footway is the intended
     * representation, e.g. for a sensitivity run.
     */
    public static void removeActiveModesFromImpassableLinks(Network network, WaterDepthData depthData,
                                                            double[] sampleTimes) {
        FloodResponse walkResponse = FloodResponse.forWalk();
        int closed = 0;
        for (var link : network.getLinks().values()) {
            double peak = 0.;
            for (double t : sampleTimes) {
                peak = Math.max(peak, depthData.getDepth(link, t));
            }
            if (walkResponse.isImpassable(peak)) {
                var modes = new java.util.HashSet<>(link.getAllowedModes());
                if (modes.remove(TransportMode.walk) | modes.remove(TransportMode.bike)) {
                    link.setAllowedModes(modes);
                    closed++;
                }
            }
        }
        logger.warn("Active modes removed from " + closed + " links impassable at peak depth.");
    }
}
