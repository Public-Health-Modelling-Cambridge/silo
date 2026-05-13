package de.tum.bgu.msm.health;

import de.tum.bgu.msm.container.DataContainer;
import de.tum.bgu.msm.data.household.Household;
import de.tum.bgu.msm.data.household.HouseholdDataManager;
import de.tum.bgu.msm.data.household.HouseholdUtil;
import de.tum.bgu.msm.data.person.Person;
import de.tum.bgu.msm.data.person.PersonRole;
import de.tum.bgu.msm.events.impls.person.DeathEvent;
import de.tum.bgu.msm.health.data.DataContainerHealth;
import de.tum.bgu.msm.health.data.PersonHealth;
import de.tum.bgu.msm.health.disease.Diseases;
import de.tum.bgu.msm.models.AbstractModel;
import de.tum.bgu.msm.models.demography.death.DeathModel;
import de.tum.bgu.msm.models.demography.death.DeathStrategy;
import de.tum.bgu.msm.properties.Properties;
import de.tum.bgu.msm.utils.SiloUtil;

import java.io.IOException;
import java.util.*;
import java.util.logging.*;

/**
 * @author Greg Erhardt, Rolf Moeckel
 * Created on Dec 2, 2009
 * Revised on Jan 19, 2018
 */
public class DeathModelMCR extends AbstractModel implements DeathModel {

    private static int year = 0;
    private final DeathStrategy strategy;
    //private final Random random;

    public DeathModelMCR(DataContainer dataContainer, Properties properties, DeathStrategy strategy, Random rnd) {
        super(dataContainer, properties, rnd);
        this.strategy = strategy;
        //this.random = rnd;
    }

    @Override
    public Collection<DeathEvent> getEventsForCurrentYear(int year) {
        this.year = year;
        final List<DeathEvent> events = new ArrayList<>();
        for (Person person : dataContainer.getHouseholdDataManager().getPersons()) {
            events.add(new DeathEvent(person.getId()));
        }
        return events;
    }

    @Override
    public boolean handleEvent(DeathEvent event) {

        // simulate if person with ID perId dies in this simulation period
        HouseholdDataManager householdDataManager = dataContainer.getHouseholdDataManager();
        final Person person = householdDataManager.getPersonFromId(event.getPersonId());


        if (person != null) {
            //For model stability: one random number per person per disease. use survival equation
            float rand = ((PersonHealth) person).getRandomNumByDisease().get(Diseases.all_cause_mortality);
            double deathProb = strategy.calculateDeathProbability(person, random);
            float thisYearSurvivalRate = (float) ((1 - deathProb) * ((PersonHealth) person).getLastYearSurvivalRateByDisease().get(Diseases.all_cause_mortality));

            if (rand > thisYearSurvivalRate) {
                return die(person);
            }

            ((PersonHealth) person).getLastYearSurvivalRateByDisease().put(Diseases.all_cause_mortality, thisYearSurvivalRate);
        }
        return false;
    }

    @Override
    public void endYear(int year) {
    }

    @Override
    public void endSimulation() {

    }

    @Override
    public void setup() {
    }

    @Override
    public void prepareYear(int year) {

    }

    boolean die(Person person) {
        final HouseholdDataManager householdDataManager = dataContainer.getHouseholdDataManager();
        final Household hhOfPersonToDie = person.getHousehold();
        householdDataManager.saveHouseholdMemento(hhOfPersonToDie);
        updateHealthDiseaseTracker(person);

        if (person.getJobId() > 0) {
            dataContainer.getJobDataManager().quitJob(true, person);
        }

        if (person.getRole() == PersonRole.MARRIED) {
            Person widow = HouseholdUtil.findMostLikelyPartner(person, hhOfPersonToDie);
            widow.setRole(PersonRole.SINGLE);
        }
        householdDataManager.removePerson(person.getId());

        final boolean onlyChildrenLeft = HouseholdUtil.checkIfNoAdultsPresent(hhOfPersonToDie);
        if (onlyChildrenLeft) {
            for (Person pp : hhOfPersonToDie.getPersons().values()) {
                if (pp.getId() == SiloUtil.trackPp || hhOfPersonToDie.getId() == SiloUtil.trackHh) {
                    SiloUtil.trackWriter.println("Child " + pp.getId() + " was moved from household " + hhOfPersonToDie.getId() +
                            " to foster care as remaining child just before head of household (ID " +
                            person.getId() + ") passed away.");
                }
            }
            householdDataManager.removeHousehold(hhOfPersonToDie.getId());
        }

        if (person.getId() == SiloUtil.trackPp || hhOfPersonToDie.getId() == SiloUtil.trackHh) {
            SiloUtil.trackWriter.println("We regret to inform that person " + person.getId() + " from household " + hhOfPersonToDie.getId() +
                    " has passed away.");
        }

        return true;
    }

    private void updateHealthDiseaseTracker(Person person) {
        Map<Integer, List<String>> healthDiseaseTracker = ((PersonHealth) person).getHealthDiseaseTracker();
        int previousYear = Collections.max(healthDiseaseTracker.keySet());
        healthDiseaseTracker.put(previousYear + 1, Arrays.asList("dead"));
        ((DataContainerHealth) dataContainer).getHealthDiseaseTrackerRemovedPerson().put(person.getId(),healthDiseaseTracker);
    }
}
