package de.tum.bgu.msm.health;

import com.google.inject.Inject;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.router.util.TravelTime;
import org.matsim.vehicles.Vehicle;

import java.util.Objects;

public final class BicycleTravelTimePreComp implements TravelTime {
    private final Network network;
    private final double[] multiplierByLinkIndex;
    private final double[] freespeedByLinkIndex;
    private final double[] lengthByLinkIndex;
    private final double defaultMaxBicycleSpeedForRouting;

    /**
     * A small adapter that returns the pure multiplier (infrastructure * surface * gradient)
     * for a link. Implement this by reusing your existing computeInfrastructureFactor/computeSurfaceFactor/...
     */

    @Inject
    public BicycleTravelTimePreComp(Network network, BicycleLinkSpeedCalculatorPrecomp factorProvider) {
        this.network = Objects.requireNonNull(network);
        this.defaultMaxBicycleSpeedForRouting = factorProvider.getDefaultMaxBicycleSpeedForRouting();

        int size = Id.getNumberOfIds(Link.class);
        this.multiplierByLinkIndex = new double[size];
        this.freespeedByLinkIndex = new double[size];
        this.lengthByLinkIndex = new double[size];

        // Precompute once
        for (Link link : network.getLinks().values()) {
            int idx = link.getId().index();
            double multiplier = factorProvider.getMultiplier(link);
            multiplierByLinkIndex[idx] = multiplier;
            freespeedByLinkIndex[idx] = link.getFreespeed();
            lengthByLinkIndex[idx] = link.getLength();
        }
    }

    @Override
    public double getLinkTravelTime(Link link, double time, Person person, Vehicle vehicle) {
        int idx = link.getId().index();

        double multiplier = multiplierByLinkIndex[idx];
        double freespeed = freespeedByLinkIndex[idx];
        double length = lengthByLinkIndex[idx];

        double vehicleMax = (vehicle == null) ? defaultMaxBicycleSpeedForRouting : vehicle.getType().getMaximumVelocity();
        // Equivalent to original: speed = min(vehicleMax * multiplier, link.getFreespeed())
        double speed = vehicleMax * multiplier;
        if (speed > freespeed) speed = freespeed;

        // Safety: avoid divide-by-zero (fall back to a very large travel time)
        if (speed <= 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        return length / speed;
    }
}
