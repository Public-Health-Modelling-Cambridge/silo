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


        /*calculate odds: odd_1 = transition_raw/(1 - transition_raw). transition_raw = 1 - exp-(transition_raw-this is the data)
        multiply by relative risks: odd_2 = odd_1 * rr
        translate to probability = probability = odd_2/(1+odd_2)

         */


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



        //alpha = alpha / (1 - alpha);

        // risk factors
        Set<Diseases> currentDiseases = new HashSet<>(((PersonHealth) person).getCurrentDisease());
        Set<Diseases> cancers = Set.of(
                Diseases.breast_cancer,
                Diseases.endometrial_cancer,
                Diseases.colon_cancer,
                Diseases.bladder_cancer,
                //Diseases.esophageal_cancer,
                //Diseases.gastric_cardia_cancer,
                Diseases.head_neck_cancer,
                //Diseases.liver_cancer,
                Diseases.lung_cancer,
                Diseases.rectum_cancer
        );
        Set<Diseases> injuries = Set.of(
                Diseases.severely_injured_car,
                Diseases.severely_injured_bike,
                Diseases.severely_injured_walk
        );


        /*
        // Risk factors
        // todo: apply only for people between 40 and 80
        if(personAge >  39 & personAge < 81 ){
            if (currentDiseases.contains(Diseases.all_cause_dementia)) {
                alpha *= 8.42;
            }
            if (currentDiseases.contains(Diseases.parkinson)) {
                alpha *= 4.6;
            }
            if (currentDiseases.contains(Diseases.copd)) {
                alpha *= 2.58;
            }
            if (currentDiseases.contains(Diseases.stroke)) {
                alpha *= 1.85;
            }
            if (!Collections.disjoint(currentDiseases, cancers)) {
                alpha *= 1.99;
            }
            if (currentDiseases.contains(Diseases.diabetes)) {
                alpha *= 1.93;
            }
            if (currentDiseases.contains(Diseases.coronary_heart_disease)) {
                alpha *= 1.72;
            }
            if (currentDiseases.contains(Diseases.depression)) {
                alpha *= 1.4;
            }
        }

         */

        if(currentDiseases.size() == 1){
            alpha *= 1.23;
        }
        if(currentDiseases.size() == 2){
            alpha *= 1.62;
        }
        if(currentDiseases.size() == 3){
            alpha *= 2.09;
        }
        if(currentDiseases.size() == 4){
            alpha *= 2.77;
        }
        if(currentDiseases.size() == 5){
            alpha *= 3.46;
        }
        if(currentDiseases.size() > 5){
            alpha *= 5.14;
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

        //return alpha/(1+alpha);
    }
}