package de.tum.bgu.msm.health;

import com.google.inject.Inject;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.scoring.ScoringFunction;
import org.matsim.core.scoring.ScoringFunctionFactory;
import org.matsim.core.scoring.SumScoringFunction;
import org.matsim.core.scoring.functions.CharyparNagelScoringFunctionFactory;
import org.matsim.core.scoring.functions.ScoringParametersForPerson;

/**
 * MATSim's default scoring plus {@link PtTripWalkScoring}, which re-scores the walk legs of
 * public transport trips at the pt time rate.
 *
 * The delegate is built from the scenario: the (Config, ScoringParametersForPerson, Network)
 * constructor of CharyparNagelScoringFunctionFactory is package-private in MATSim
 * 2025.0-PR3643. The scenario constructor creates the same per-subpopulation parameters that
 * are injected here by default, so both use the same values.
 */
final class PtTripScoringFunctionFactory implements ScoringFunctionFactory {

    private final CharyparNagelScoringFunctionFactory delegate;
    private final ScoringParametersForPerson params;

    @Inject
    PtTripScoringFunctionFactory(Scenario scenario, ScoringParametersForPerson params) {
        this.delegate = new CharyparNagelScoringFunctionFactory(scenario);
        this.params = params;
    }

    @Override
    public ScoringFunction createNewScoringFunction(Person person) {
        SumScoringFunction scoringFunction = (SumScoringFunction) delegate.createNewScoringFunction(person);
        scoringFunction.addScoringFunction(new PtTripWalkScoring(params.getScoringParameters(person)));
        return scoringFunction;
    }
}
