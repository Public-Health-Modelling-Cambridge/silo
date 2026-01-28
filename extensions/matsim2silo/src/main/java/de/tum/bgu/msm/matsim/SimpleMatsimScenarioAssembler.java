package de.tum.bgu.msm.matsim;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.Coordinate;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationFactory;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.scenario.MutableScenario;
import org.matsim.core.scenario.ScenarioUtils;

import de.tum.bgu.msm.container.DataContainer;
import de.tum.bgu.msm.data.Day;
import de.tum.bgu.msm.data.MitoGender;
import de.tum.bgu.msm.data.Mode;
import de.tum.bgu.msm.data.Purpose;
import de.tum.bgu.msm.data.dwelling.Dwelling;
import de.tum.bgu.msm.data.household.Household;
import de.tum.bgu.msm.data.household.HouseholdUtil;
import de.tum.bgu.msm.data.job.Job;
import de.tum.bgu.msm.data.job.JobDataManager;
import de.tum.bgu.msm.data.person.Occupation;
import de.tum.bgu.msm.data.person.Person;
import de.tum.bgu.msm.data.travelTimes.TravelTimes;
import de.tum.bgu.msm.data.vehicle.VehicleType;
import de.tum.bgu.msm.properties.Properties;
import de.tum.bgu.msm.utils.SiloUtil;

public class SimpleMatsimScenarioAssembler implements MatsimScenarioAssembler {
    private final static Logger logger = LogManager.getLogger(SimpleMatsimScenarioAssembler.class);
    private final DataContainer dataContainer;
    private final Properties properties;
//    private final boolean newRandomSeed = false;
    private final Random random;

    public SimpleMatsimScenarioAssembler(DataContainer dataContainer, Properties properties) {
        this.dataContainer = dataContainer;
        this.properties = properties;

//        if ( newRandomSeed ){
//            this.random = MatsimRandom.getLocalInstance();
//        } else{
            this.random = SiloUtil.getRandomObject();
            logger.warn("using random number sequence from silo.  for fabiland, this made regression tests non-deterministic.  Here, it seems to work, " +
                                        "thus leaving it the way it is.  kai, jun'23");
//        }
    }

    @Override
    public Scenario assembleScenario(Config matsimConfig, int year, TravelTimes travelTimes) {
        logger.info("Starting creating (simple home-work-home) MATSim scenario.");

//        if ( newRandomSeed ){
//            random.setSeed( 4711 );
//            // (note that we WANT this with the same random seed for every year when matsim is called.  Could, however, be made dependent on the silo
//            // seed, so that with a change of the silo seed it also changes the random seed here.  kai' jun'23)
//        }

        double populationScalingFactor = properties.transportModel.matsimScaleFactor;
        SiloMatsimUtils.checkSiloPropertiesAndMatsimConfigConsistency(matsimConfig, properties);

        Scenario scenario = ScenarioUtils.loadScenario(matsimConfig);
        Population matsimPopulation = scenario.getPopulation();
        PopulationFactory pf = matsimPopulation.getFactory();

        Collection<Person> siloPersons = dataContainer.getHouseholdDataManager().getPersons();
        JobDataManager jobDataManager = dataContainer.getJobDataManager();

        for (Person siloPerson : siloPersons) {
//            if (SiloUtil.getRandomNumberAsDouble() > populationScalingFactor) {
            if ( random.nextDouble() > populationScalingFactor ) {
                // e.g. if scalingFactor = 0.01, there will be a 1% chance that the loop is not
                // continued in the next step, i.e. that the person is added to the population
                continue;
            }

            if (siloPerson.getOccupation() != Occupation.EMPLOYED) { // i.e. person does not work
                continue;
            }

            int siloWorkplaceId = siloPerson.getJobId();
            if (siloWorkplaceId == -2) { // i.e. person has workplace outside study area
                continue;
            }

            Household household = siloPerson.getHousehold();

            int numberOfWorkers = HouseholdUtil.getNumberOfWorkers(household);
            int numberOfAutos =  (int) household.getVehicles().stream().filter(vv -> vv.getType().equals(VehicleType.CAR)).count();
            if (numberOfWorkers == 0) {
                throw new RuntimeException("If there are no workers in the household, the loop must already"
                        + " have been continued by finding that the given person is not employed!");
            }
            if ((double) numberOfAutos/numberOfWorkers < 1.) {
                if (SiloUtil.getRandomNumberAsDouble() > (double) numberOfAutos/numberOfWorkers) {
                    continue;
                }
            }

            Dwelling dwelling = dataContainer.getRealEstateDataManager().getDwelling(household.getDwellingId());
            Coordinate dwellingCoordinate;
            if (dwelling != null && dwelling.getCoordinate() != null) {
                dwellingCoordinate = dwelling.getCoordinate();
            } else {
                // TODO This step should not be done (again) if a random coordinate for the same dwelling has been chosen before, dz 10/20
                dwellingCoordinate = dataContainer.getGeoData().getZones().get(dwelling.getZoneId()).getRandomCoordinate(SiloUtil.getRandomObject());
            }
            Coord dwellingCoord = new Coord(dwellingCoordinate.x, dwellingCoordinate.y);

            Job job = jobDataManager.getJobFromId(siloWorkplaceId);
            Coordinate jobCoordinate;
            if (job != null && job.getCoordinate() != null) {
                jobCoordinate = job.getCoordinate();
            } else {
                // TODO This step should not be done (again) if a random coordinate for the same job has been chosen before, dz 10/20
                jobCoordinate = dataContainer.getGeoData().getZones().get(job.getZoneId()).getRandomCoordinate(SiloUtil.getRandomObject());
            }
            Coord jobCoord = new Coord(jobCoordinate.x, jobCoordinate.y);

            org.matsim.api.core.v01.population.Person matsimPerson = pf.createPerson(Id.createPersonId(siloPerson.getId()));
            matsimPopulation.addPerson(matsimPerson);

            Plan matsimPlan = pf.createPlan();
            matsimPerson.addPlan(matsimPlan);

            Activity homeActivityMorning = pf.createActivityFromCoord("home", dwellingCoord);
            homeActivityMorning.setEndTime(6 * 3600 + 3 * SiloUtil.getRandomNumberAsDouble() * 3600); // TODO Potentially change later
            matsimPlan.addActivity(homeActivityMorning);
            matsimPlan.addLeg(pf.createLeg(TransportMode.car)); // TODO Potentially change later

            Activity workActivity = pf.createActivityFromCoord("work", jobCoord);
            workActivity.setEndTime(15 * 3600 + 3 * SiloUtil.getRandomNumberAsDouble() * 3600); // TODO Potentially change later
            matsimPlan.addActivity(workActivity);
            matsimPlan.addLeg(pf.createLeg(TransportMode.car)); // TODO Potentially change later

            Activity homeActvitiyEvening = pf.createActivityFromCoord("home", dwellingCoord);
            matsimPlan.addActivity(homeActvitiyEvening);
        }
        logger.info("Finished creating MATSim scenario.");
        return scenario;
    }

    @Override
    public Map<Day, Scenario> assembleMultiScenarios(Config initialMatsimConfig, int year, TravelTimes travelTimes) {
        Map<Day, Scenario> scenarios = new HashMap<>();

        // Check for precomputed MITO trips.csv in scenOutput/<scenario>/<year>/microData/trips.csv
        String tripsPath = properties.main.baseDirectory + "scenOutput/" + properties.main.scenarioName + "/" + year + "/microData/trips.csv";
        File tripsFile = new File(tripsPath);
        if (tripsFile.exists()) {
            Map<Integer, TripRecord> mitoTripsAll = readTrips(tripsPath);
            
            // Group persons by day (following MITO_MATSIM pattern)
            logger.info("  Receiving demand from trips CSV");
            Map<Day, Population> populationByDay = new HashMap<>();
            PopulationFactory pf = PopulationUtils.getFactory();

            for (TripRecord t : mitoTripsAll.values()) {
                if (t.getDepartureDay() == null || t.getTripOrigin() == null || t.getTripDestination() == null) continue;

                Day day = t.getDepartureDay();
                if (populationByDay.get(day) == null) {
                    populationByDay.put(day, PopulationUtils.createPopulation(ConfigUtils.createConfig()));
                }

                String personId = "trip_" + t.getTripId();
                org.matsim.api.core.v01.population.Person matsimPerson = pf.createPerson(Id.createPersonId(personId));
                
                // Set person attributes required by runBikePedSimulation
                if (t.getPersonId() > 0) {
                    Person siloPerson = dataContainer.getHouseholdDataManager().getPersonFromId(t.getPersonId());
                    if (siloPerson != null) {
                        matsimPerson.getAttributes().putAttribute("age", siloPerson.getAge());
                        matsimPerson.getAttributes().putAttribute("sex", MitoGender.valueOf(siloPerson.getGender().toString()));
                    }
                }
                if (t.purpose != null) {
                    try {
                        matsimPerson.getAttributes().putAttribute("purpose", Purpose.valueOf(t.purpose));
                    } catch (IllegalArgumentException e) {
                        logger.warn("Unknown purpose: " + t.purpose);
                    }
                }
                
                populationByDay.get(day).addPerson(matsimPerson);

                Plan plan = pf.createPlan();
                matsimPerson.addPlan(plan);

                Activity from = pf.createActivityFromCoord("home", t.getTripOrigin());
                from.setEndTime(t.getDepartureTimeInMinutes() * 60);
                plan.addActivity(from);

                // map Mode to MATSim mode string
                String mode;
                try {
                    Mode mm = t.getTripMode();
                    String mn = mm.name().toLowerCase();
                    if (mn.contains("auto")) {
                        mode = TransportMode.car;
                    } else if (mn.contains("bike")) {
                        mode = TransportMode.bike;
                    } else if (mn.contains("walk")) {
                        mode = TransportMode.walk;
                    } else if (mn.contains("pt") || mn.contains("transit")) {
                        mode = TransportMode.pt;
                    } else {
                        mode = mm.name().toLowerCase();
                    }
                } catch (Exception e) {
                    mode = TransportMode.car;
                }
                
                // Store mode in person attributes for runBikePedSimulation
                matsimPerson.getAttributes().putAttribute("mode", mode);

                plan.addLeg(pf.createLeg(mode));

                Activity to = pf.createActivityFromCoord("work", t.getTripDestination());
                if (t.getDepartureReturnInMinutes() > 0) {
                    to.setEndTime(t.getDepartureReturnInMinutes() * 60);
                }
                plan.addActivity(to);
            }

            // Create scenarios per day with config settings (following MITO_MATSIM pattern)
            for (Day day : Day.values()) {
                Population population = populationByDay.get(day);
                if (population == null) continue; // skip days with no trips
                
                Config config = ConfigUtils.loadConfig(initialMatsimConfig.getContext());
                setDemandSpecificConfigSettings(config);
                MutableScenario scenario = (MutableScenario) ScenarioUtils.loadScenario(config);
                scenario.setPopulation(population);
                scenarios.put(day, scenario);
            }

            return scenarios;
        }

        // Fallback: create one scenario per day using simple home-work-home sampler
        for (Day day : Day.values()) {
            Config cfg = ConfigUtils.loadConfig(initialMatsimConfig.getContext());
            Scenario scenario = assembleScenario(cfg, year, travelTimes);
            scenarios.put(day, scenario);
        }

        return scenarios;
    }

    private Map<Integer, TripRecord> readTrips(String path) {
        Map<Integer, TripRecord> trips = new HashMap<>();
        String recString = "";
        int recCount = 0;
        try (BufferedReader in = new BufferedReader(new FileReader(path))) {
            recString = in.readLine();
            if (recString == null) return trips;
            String[] header = recString.split(",");
            int posId = SiloUtil.findPositionInArray("t.id", header);
            int posPurpose = SiloUtil.findPositionInArray("t.purpose", header);
            int posPersonId = SiloUtil.findPositionInArray("p.ID", header);
            int posOriginX = SiloUtil.findPositionInArray("originX", header);
            int posOriginY = SiloUtil.findPositionInArray("originY", header);
            int posDestinationX = SiloUtil.findPositionInArray("destinationX", header);
            int posDestinationY = SiloUtil.findPositionInArray("destinationY", header);
            int posMode = SiloUtil.findPositionInArray("mode", header);
            int posDepartureTime = SiloUtil.findPositionInArray("departure_time", header);
            int posDepartureTimeReturn = SiloUtil.findPositionInArray("departure_time_return", header);
            int posDepartureDay = SiloUtil.findPositionInArray("departure_day", header);

            while ((recString = in.readLine()) != null) {
                recCount++;
                String[] e = recString.split(",");
                try {
                    int id = Integer.parseInt(e[posId]);
                    TripRecord tr = new TripRecord(id);
                    if (posPurpose >= 0) tr.purpose = e[posPurpose];
                    if (posPersonId >= 0) tr.personId = Integer.parseInt(e[posPersonId]);
                    if (posOriginX >= 0 && posOriginY >= 0 && !e[posOriginX].equals("null") && !e[posOriginY].equals("null")) {
                        tr.origin = new Coord(Double.parseDouble(e[posOriginX]), Double.parseDouble(e[posOriginY]));
                    }
                    if (posDestinationX >= 0 && posDestinationY >= 0 && !e[posDestinationX].equals("null") && !e[posDestinationY].equals("null")) {
                        tr.destination = new Coord(Double.parseDouble(e[posDestinationX]), Double.parseDouble(e[posDestinationY]));
                    }
                    if (posMode >= 0) tr.mode = Mode.valueOf(e[posMode]);
                    if (posDepartureTime >= 0) tr.departure = Integer.parseInt(e[posDepartureTime]);
                    if (posDepartureTimeReturn >= 0) {
                        String rt = e[posDepartureTimeReturn];
                        if (!rt.equals("NA")) tr.returnDeparture = Integer.parseInt(rt);
                    }
                    if (posDepartureDay >= 0) tr.day = Day.valueOf(e[posDepartureDay]);
                    trips.put(id, tr);
                } catch (Exception ex) {
                    logger.warn("Skipping malformed trip line: " + recString);
                }
            }
        } catch (IOException ex) {
            logger.error("Error reading trips CSV: " + path, ex);
        }
        logger.info("Finished reading " + recCount + " mito trips.");
        return trips;
    }

    private static class TripRecord {
        final int id;
        int personId = -1;
        String purpose;
        Coord origin;
        Coord destination;
        Mode mode;
        int departure = 0;
        int returnDeparture = -1;
        Day day = null;

        TripRecord(int id) { this.id = id; }

        int getTripId() { return id; }
        int getPersonId() { return personId; }
        Day getDepartureDay() { return day; }
        Coord getTripOrigin() { return origin; }
        Coord getTripDestination() { return destination; }
        Mode getTripMode() { return mode; }
        int getDepartureTimeInMinutes() { return departure; }
        int getDepartureReturnInMinutes() { return returnDeparture; }
    }

    private void setDemandSpecificConfigSettings(Config config) {
        config.qsim().setFlowCapFactor(properties.main.scaleFactor);
        config.qsim().setStorageCapFactor(properties.main.scaleFactor);

        logger.info("Flow Cap Factor: " + config.qsim().getFlowCapFactor());
        logger.info("Storage Cap Factor: " + config.qsim().getStorageCapFactor());

        ScoringConfigGroup.ActivityParams homeActivity = new ScoringConfigGroup.ActivityParams("home").setTypicalDuration(12 * 60 * 60);
        config.scoring().addActivityParams(homeActivity);

        ScoringConfigGroup.ActivityParams workActivity = new ScoringConfigGroup.ActivityParams("work").setTypicalDuration(8 * 60 * 60);
        config.scoring().addActivityParams(workActivity);

        ScoringConfigGroup.ActivityParams educationActivity = new ScoringConfigGroup.ActivityParams("education").setTypicalDuration(8 * 60 * 60);
        config.scoring().addActivityParams(educationActivity);

        ScoringConfigGroup.ActivityParams shoppingActivity = new ScoringConfigGroup.ActivityParams("shopping").setTypicalDuration(1 * 60 * 60);
        config.scoring().addActivityParams(shoppingActivity);

        ScoringConfigGroup.ActivityParams recreationActivity = new ScoringConfigGroup.ActivityParams("recreation").setTypicalDuration(1 * 60 * 60);
        config.scoring().addActivityParams(recreationActivity);

        ScoringConfigGroup.ActivityParams otherActivity = new ScoringConfigGroup.ActivityParams("other").setTypicalDuration(1 * 60 * 60);
        config.scoring().addActivityParams(otherActivity);

        ScoringConfigGroup.ActivityParams airportActivity = new ScoringConfigGroup.ActivityParams("airport").setTypicalDuration(1 * 60 * 60);
        config.scoring().addActivityParams(airportActivity);
    }
}
