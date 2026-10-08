package de.tum.bgu.msm.health;

import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scoring.SumScoringFunction;
import org.matsim.core.scoring.functions.ModeUtilityParameters;
import org.matsim.core.scoring.functions.ScoringParameters;

import java.util.List;

/**
 * Scores a public transport trip as one unit, the way the mode-choice model was estimated.
 *
 * In the TRADS estimation (MNL5_purposeASC) the pt travel time is in-vehicle time plus the
 * access, egress and transfer walks, all valued at the pt time coefficient, and a pt trip has
 * one pt constant. MATSim's leg scoring instead scores those walk legs as walk: walk time rate
 * plus one walk constant per leg. For every walk leg inside a pt trip this class adds the
 * difference, so the leg ends up scored at the pt time rate with no walk constant.
 *
 * Not touched here:
 * <ul>
 *     <li>the pt constant — CharyparNagelLegScoring already applies it once per trip (it
 *     subtracts it again for every pt leg after the first), and charges utilityOfLineSwitch
 *     for every boarding after the first, so transfers need no compensation;</li>
 *     <li>dailyUtilityConstant — the weather shift deliberately reaches pt users through the
 *     walk daily constant (see fillScoringSet in MatsimTransportModelMCRHealth).</li>
 * </ul>
 */
final class PtTripWalkScoring implements SumScoringFunction.TripScoring {

    private static final String NON_NETWORK_WALK = "non_network_walk";

    private final ScoringParameters params;
    private double score;

    PtTripWalkScoring(ScoringParameters params) {
        this.params = params;
    }

    @Override
    public void handleTrip(TripStructureUtils.Trip trip) {
        List<Leg> legs = trip.getLegsOnly();
        boolean isPtTrip = false;
        for (Leg leg : legs) {
            if (TransportMode.pt.equals(leg.getMode())) {
                isPtTrip = true;
                break;
            }
        }
        if (!isPtTrip) {
            return;
        }
        ModeUtilityParameters pt = params.modeParams.get(TransportMode.pt);
        for (Leg leg : legs) {
            String mode = leg.getMode();
            if (!TransportMode.walk.equals(mode) && !NON_NETWORK_WALK.equals(mode)) {
                continue;
            }
            if (leg.getTravelTime().isUndefined()) {
                continue;
            }
            ModeUtilityParameters walkLike = params.modeParams.get(mode);
            if (walkLike == null) {
                // same fallback as CharyparNagelLegScoring
                walkLike = params.modeParams.get(TransportMode.walk);
            }
            score += (pt.marginalUtilityOfTraveling_s - walkLike.marginalUtilityOfTraveling_s)
                    * leg.getTravelTime().seconds() - walkLike.constant;
        }
    }

    @Override
    public void finish() {
    }

    @Override
    public double getScore() {
        return score;
    }
}
