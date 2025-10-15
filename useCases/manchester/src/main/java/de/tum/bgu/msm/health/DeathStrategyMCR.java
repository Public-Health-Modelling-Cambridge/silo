package de.tum.bgu.msm.health;

import de.tum.bgu.msm.data.ZoneMCR;
import de.tum.bgu.msm.data.person.Gender;
import de.tum.bgu.msm.data.person.Person;
import de.tum.bgu.msm.health.data.DataContainerHealth;
import de.tum.bgu.msm.health.data.PersonHealth;
import de.tum.bgu.msm.health.disease.Diseases;
import de.tum.bgu.msm.health.disease.HealthExposures;
import de.tum.bgu.msm.models.demography.death.DeathStrategy;
import de.tum.bgu.msm.properties.Properties;

import java.util.*;

public class DeathStrategyMCR implements DeathStrategy {

    private final HealthDataContainerImpl dataContainer;
    private final Boolean adjustByRelativeRisk;

    public DeathStrategyMCR(HealthDataContainerImpl dataContainer, Boolean adjustByRelativeRisk) {
        this.dataContainer = dataContainer;
        this.adjustByRelativeRisk = adjustByRelativeRisk;
    }

    @Override
    public double calculateDeathProbability(Person person, Random random) {
        final int personAge = Math.min(person.getAge(), 100);
        Gender personSex = person.getGender();

        if (personAge < 0){
            throw new RuntimeException("Undefined negative person age !" + personAge);
        }

        //cap age at 100, over 100 all cause mortality prob = 1
        if (personAge >= 100){
            return 1.;
        }

        // check killed by injury
        Set<Diseases> killedInAccident = Set.of(
                Diseases.dead_car,
                Diseases.dead_bike,
                Diseases.dead_walk
        );

        if (!Collections.disjoint(((PersonHealth) person).getCurrentDisease(), killedInAccident)) {
            return 1.;
        }

        int zoneId = dataContainer.getRealEstateDataManager().getDwelling(person.getHousehold().getDwellingId()).getZoneId();
        String location = ((ZoneMCR)dataContainer.getGeoData().getZones().get(zoneId)).getLsoaCode();
        String compositeKey = ((DataContainerHealth) dataContainer).createTransitionLookupIndex(Math.min(person.getAge(), 100), person.getGender(), location);

        double alpha = dataContainer.getHealthTransitionData().get(Diseases.all_cause_mortality).get(compositeKey);

        //calculation of probabilities for mortality with first adjustment using rates*rr exposures/PA and then
        // adjusting probabilities (previous rate converted to probability) to odds ratios and multiplied by teh
        // disease rr and back to prob. I understand this was not done this way before.
        //no rr adjustment for age under 18
        if(personAge < 18){
            return alpha;
        }

        if(adjustByRelativeRisk){
            for(HealthExposures healthExposures : ((PersonHealth)person).getRelativeRisksByDisease().keySet()){
                alpha *= ((PersonHealth)person).getRelativeRisksByDisease().get(healthExposures).get(Diseases.all_cause_mortality);
            }
        }


        // risk factors
        Set<Diseases> currentDiseases = new HashSet<>(((PersonHealth) person).getCurrentDisease());
        Set<Diseases> injuries = Set.of(
                Diseases.severely_injured_car,
                Diseases.severely_injured_bike,
                Diseases.severely_injured_walk
        );


        if (Collections.disjoint(currentDiseases, injuries)) {

            if (currentDiseases.size() == 1) {
                alpha *= 1.23;
            }
            if (currentDiseases.size() == 2) {
                alpha *= 1.62;
            }
            if (currentDiseases.size() == 3) {
                alpha *= 2.09;
            }
            if (currentDiseases.size() == 4) {
                alpha *= 2.77;
            }
            if (currentDiseases.size() == 5) {
                alpha *= 3.46;
            }
            if (currentDiseases.size() > 5) {
                alpha *= 5.14;
            }
        }

        // todo: what happens with people < 18
        if (!Collections.disjoint(currentDiseases, injuries)) {
            if (person.getGender().equals(Gender.MALE)) {
                alpha *= 1.71;
            } else {
                alpha *= 1.74;
            }
        }

        return  (1 - Math.exp(-alpha));

    }
}