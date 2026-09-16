package de.tum.bgu.msm.health.flood;

import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.router.util.TravelTime;
import org.matsim.vehicles.Vehicle;

/**
 * Active-mode travel time that slows down with floodwater.
 * <p>
 * Wraps mito's dry-weather speed — {@code WalkLinkSpeedCalculatorImpl} (gradient) or
 * {@code BicycleLinkSpeedCalculatorImpl} (gradient, surface, dismount) — and multiplies it by
 * the depth-response factor for the depth on that link <i>at that time</i>.
 * <p>
 * Why this sits at the {@link TravelTime} level rather than in a link speed calculator: the
 * {@code LinkSpeedCalculator} interfaces expose {@code getMaximumVelocityForLink(Link, Vehicle)}
 * with <b>no time argument</b>, and {@code BicycleLinkSpeedCalculator} comes from the MATSim
 * bicycle contrib, so the signature cannot be widened without forking it. {@link TravelTime}
 * does receive the time. Binding here therefore makes active modes flood-aware with no change
 * to mito or to MATSim.
 * <p>
 * The QSim link speed calculators stay flood-blind, which is harmless while bike and walk are
 * teleported rather than QSim main modes. If they are ever moved into the mobsim, that gap has
 * to be closed too.
 */
public final class FloodAwareActiveTravelTime implements TravelTime {

    /** Dry-weather speed on a link, as provided by mito's existing calculators. */
    @FunctionalInterface
    public interface DrySpeed {
        double getMaximumVelocityForLink(Link link, Vehicle vehicle);
    }

    private final DrySpeed drySpeed;
    private final WaterDepthData depthData;
    private final FloodResponse response;

    public FloodAwareActiveTravelTime(DrySpeed drySpeed, WaterDepthData depthData, FloodResponse response) {
        this.drySpeed = drySpeed;
        this.depthData = depthData;
        this.response = response;
    }

    @Override
    public double getLinkTravelTime(Link link, double time, Person person, Vehicle vehicle) {
        double speed = drySpeed.getMaximumVelocityForLink(link, vehicle);
        double depth = depthData.getDepth(link, time);
        if (depth > 0.) {
            speed *= response.speedFactor(depth);
        }
        return link.getLength() / speed;
    }
}
