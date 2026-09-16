package de.tum.bgu.msm.health.flood;

import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.vehicles.Vehicle;
import routing.ActiveConfigGroup;

import java.util.List;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * Flood-aware replacement for mito's {@code ActiveDisutility}.
 * <p>
 * It reproduces the JIBE cost exactly —
 * {@code linkTime * (1 + sum(weight_i * attribute_i))} — with two changes:
 * <ol>
 *   <li>The <b>real time</b> is passed to the travel time calculator. Mito's
 *       {@code ActiveDisutility} hardcodes {@code 0.}, which would evaluate every link as if it
 *       were dry at midnight and make flood-aware routing impossible. This class cannot simply
 *       subclass it, because {@code attributes} and {@code attributeCount} are package-private.</li>
 *   <li>An optional <b>hazard term</b> multiplies the cost by {@code (1 + hazardWeight * f)},
 *       where {@code f} is depth normalised over the passable range. This expresses reluctance
 *       to wade that is not captured by slowness alone. With {@code hazardWeight = 0} (the
 *       default) the cost is the JIBE cost evaluated at the right time, and nothing else.</li>
 * </ol>
 * Links that do not allow the mode return {@code NaN}, matching mito's convention. Links that
 * are merely <i>impassable because of the flood</i> instead return a large finite cost — see
 * {@link #IMPASSABLE_COST_FACTOR}.
 * <p>
 * The static link attributes (gradient, {@code LinkStress}, {@code LinkAmbience},
 * {@code JctStress}) are untouched: the attribute list is {@code ToDoubleFunction<Link>}, with
 * no time argument, so a time-varying quantity cannot live there.
 */
public final class FloodAwareActiveDisutility implements TravelDisutility {

    /**
     * Cost multiplier applied to a link that is impassable at the time of traversal.
     * <p>
     * Deliberately large but <b>finite</b>, rather than {@code NaN} or infinity. Excluding the
     * link outright would be correct wherever an alternative exists, but leaves an agent whose
     * origin or destination sits on a flooded link with no route at all — and with roughly
     * 4,200 impassable links in a dense urban network, some activity locations do. A finite
     * penalty degrades gracefully: alternatives always win by orders of magnitude, so nobody
     * crosses voluntarily, but a stranded agent can still reach its activity.
     */
    public static final double IMPASSABLE_COST_FACTOR = 1000.;

    private final ActiveConfigGroup configGroup;
    private final TravelTime timeCalculator;
    private final WaterDepthData depthData;
    private final FloodResponse response;
    private final double hazardWeight;

    private final List<ToDoubleFunction<Link>> attributes;
    private final int attributeCount;
    private final Function<Person, double[]> weightsFunction;
    private final String mode;

    public FloodAwareActiveDisutility(ActiveConfigGroup configGroup, TravelTime timeCalculator,
                                      WaterDepthData depthData, FloodResponse response, double hazardWeight) {
        this.configGroup = configGroup;
        this.timeCalculator = timeCalculator;
        this.depthData = depthData;
        this.response = response;
        this.hazardWeight = hazardWeight;
        this.attributes = configGroup.getAttributes();
        this.attributeCount = attributes.size();
        this.weightsFunction = configGroup.getWeights();
        this.mode = configGroup.getMode();
    }

    @Override
    public double getLinkTravelDisutility(Link link, double time, Person person, Vehicle vehicle) {
        if (!link.getAllowedModes().contains(mode)) {
            return Double.NaN;
        }

        double depth = depthData.getDepth(link, time);
        double linkTime = timeCalculator.getLinkTravelTime(link, time, person, vehicle);

        double[] weights = weightsFunction == null ? null : weightsFunction.apply(person);
        double streetEnvironmentAdjustment = 1.;
        if (weights != null) {
            if (weights.length != attributeCount) {
                throw new RuntimeException("Size of marginal weights array (" + weights.length
                        + ") does not match size of attributes list (" + attributeCount + ")");
            }
            for (int i = 0; i < attributeCount; i++) {
                if (weights[i] < 0) {
                    throw new RuntimeException("All active travel disutility weights must be positive!");
                }
                streetEnvironmentAdjustment += weights[i] * attributes.get(i).applyAsDouble(link);
            }
        }

        // Impassable: a large finite penalty rather than exclusion, so an agent whose origin or
        // destination lies on a flooded link can still be routed. Any dry alternative wins by
        // three orders of magnitude.
        if (response.isImpassable(depth)) {
            return linkTime * streetEnvironmentAdjustment * IMPASSABLE_COST_FACTOR;
        }

        double hazardAdjustment = hazardWeight == 0. || depth <= 0.
                ? 1.
                : 1. + hazardWeight * response.hazardFraction(depth);

        return linkTime * streetEnvironmentAdjustment * hazardAdjustment;
    }

    @Override
    public double getLinkMinimumTravelDisutility(Link link) {
        // Same lower bound as mito's ActiveDisutility: safe for landmark-based A*.
        return 0;
    }
}
