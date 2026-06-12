/* *********************************************************************** *
 * project: org.matsim.*												   *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2008 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */
package de.tum.bgu.msm.health;

import de.tum.bgu.msm.container.DataContainer;
import de.tum.bgu.msm.data.Day;
import de.tum.bgu.msm.data.MitoGender;
import de.tum.bgu.msm.data.Mode;
import de.tum.bgu.msm.data.Purpose;
import de.tum.bgu.msm.data.travelTimes.SkimTravelTimes;
import de.tum.bgu.msm.data.travelTimes.TravelTimes;
import de.tum.bgu.msm.health.data.DataContainerHealth;
import de.tum.bgu.msm.matsim.MatsimData;
import de.tum.bgu.msm.matsim.MatsimScenarioAssembler;
import de.tum.bgu.msm.matsim.MatsimTravelTimesAndCosts;
import de.tum.bgu.msm.matsim.SiloMatsimUtils;
import de.tum.bgu.msm.models.transportModel.TransportModel;
import de.tum.bgu.msm.properties.Properties;
import de.tum.bgu.msm.properties.modules.TransportModelPropertiesModule;
import io.SpeedsReader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.contrib.dvrp.trafficmonitoring.TravelTimeUtils;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.*;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.ControlerDefaults;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.router.NetworkRoutingProvider;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.MainModeIdentifierImpl;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.MutableScenario;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleType;
import org.matsim.vehicles.VehicleUtils;
import org.matsim.vehicles.VehiclesFactory;
import org.matsim.pt.utils.CreateVehiclesForSchedule;
import ch.sbb.matsim.config.SwissRailRaptorConfigGroup;
import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorModule;
import routing.*;
import routing.components.Gradient;
import routing.components.JctStress;
import routing.components.LinkAmbience;
import routing.components.LinkStress;

import java.io.File;
import java.util.*;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

import static org.matsim.core.config.groups.ScoringConfigGroup.ModeParams;

/**
 * @author qinzhang
 */
public final class MatsimTransportModelMCRHealth implements TransportModel {

    private static final Logger logger = LogManager.getLogger(MatsimTransportModelMCRHealth.class);

    // Subpopulations steer replanning only (scoring falls back to the default parameter set):
    // "person" agents may switch mode, "freight" (trucks + through traffic) may only re-route.
    private static final String SUBPOP_PERSON = "person";
    private static final String SUBPOP_FREIGHT = "freight";

    private final Properties properties;
    private final Config initialMatsimConfig;

    private final MatsimData matsimData;
    private final MatsimTravelTimesAndCosts internalTravelTimes;

    private final DataContainer dataContainer;

    private MatsimScenarioAssembler scenarioAssembler;

    private List<Day> simulatedDays;

    protected final Random random;

    public MatsimTransportModelMCRHealth(DataContainer dataContainer, Config matsimConfig,
                                         Properties properties, MatsimScenarioAssembler scenarioAssembler,
                                         MatsimData matsimData, Random random) {
        this.dataContainer = Objects.requireNonNull(dataContainer);
        this.initialMatsimConfig = Objects.requireNonNull(matsimConfig,
                "No initial matsim config provided to SiloModel class!");
        logger.info("Copying initial config to output folder");
        File file = new File(properties.main.baseDirectory + "scenOutput/" + properties.main.scenarioName + "/matsim/initialConfig.xml");
        file.getParentFile().mkdirs();
        ConfigUtils.writeMinimalConfig(initialMatsimConfig, file.getAbsolutePath());

        final TravelTimes travelTimes = dataContainer.getTravelTimes();
        if (travelTimes instanceof MatsimTravelTimesAndCosts) {
            this.internalTravelTimes = (MatsimTravelTimesAndCosts) travelTimes;
        } else {
            this.internalTravelTimes = new MatsimTravelTimesAndCosts(matsimConfig);
        }
        this.matsimData = matsimData;
        this.scenarioAssembler = scenarioAssembler;
        this.properties = properties;
        this.simulatedDays = Arrays.asList(Day.thursday); // we only test a typical weekday
        this.random = random;
    }

    @Override
    public void setup() {
        internalTravelTimes.initialize(dataContainer.getGeoData(), matsimData);

        if (properties.transportModel.matsimInitialEventsFile == null) {
            //TODO: comment out for longitudinal simulation. need to make it more general
            runTransportModel(properties.main.startYear);
            logger.warn("This is a temporary fix, we don't want to run again the transport model in the longitudinal scenario");
        } else {
            String eventsFile = properties.main.baseDirectory + properties.transportModel.matsimInitialEventsFile;
            replayFromEvents(eventsFile);
        }
    }

    @Override
    public void prepareYear(int year) {
    }

    @Override
    public void endYear(int year) {
        if (properties.transportModel.transportModelYears.contains(year + 1)) {
            runTransportModel(year + 1);
        } else if (properties.healthData.exposureModelYears.contains(year)){
            runMitoModel(year);

            logger.warn( "Updating reginal travel times");
            final TravelTimes mainTravelTimes = dataContainer.getTravelTimes();

            if (mainTravelTimes instanceof SkimTravelTimes) {
                ((SkimTravelTimes) mainTravelTimes).updateRegionalTravelTimes(dataContainer.getGeoData().getRegions().values(),
                        dataContainer.getGeoData().getZones().values());
            }
            logger.warn( "finish update reginal travel times");
        }
    }



    @Override
    public void endSimulation() {
    }

    private void runMitoModel(int year) {
        logger.warn("Running MITO model only for year " + year + ".");
        ((MitoMatsimScenarioAssemblerMCR)scenarioAssembler).runMitoStandalone(year);
    }


    private void runTransportModel(int year) {
        logger.warn("Running MATSim transport model for year " + year + ".");
        Map<Day, Scenario> assembledMultiScenario;
        TravelTimes travelTimes = dataContainer.getTravelTimes();
        if (year == properties.main.baseYear &&
                properties.transportModel.transportModelIdentifier == TransportModelPropertiesModule.TransportModelIdentifier.MATSIM){
            //if using the SimpleCommuteModeChoiceScenarioAssembler, we need some intial travel times (this will use an unlodaded network)
            TravelTime myTravelTime = SiloMatsimUtils.getAnEmptyNetworkTravelTime();
            TravelDisutility myTravelDisutility = SiloMatsimUtils.getAnEmptyNetworkTravelDisutility();
            updateTravelTimes(myTravelTime, myTravelDisutility);
        }

        assembledMultiScenario = scenarioAssembler.assembleMultiScenarios(initialMatsimConfig, year, travelTimes);

        //run all modes (car, truck, pt, bike, walk) in one simulation so modes compete via mode choice
        runAllModesSimulation(year, assembledMultiScenario);

        //run car truck simulation
        //runCarTruckSimulation(year, assembledMultiScenario);

        //run bike ped simulation
        //runBikePedSimulation(year, assembledMultiScenario);
    }

    private void runBikePedSimulation(int year, Map<Day, Scenario> assembledMultiScenario) {
        for (Day day : simulatedDays) {
            Scenario assembledScenario = assembledMultiScenario.get(day);
            MainModeIdentifierImpl mainModeIdentifier = new MainModeIdentifierImpl();

            Population populationBikePed = PopulationUtils.createPopulation(ConfigUtils.createConfig());

            // Add bike, and pedestrian plans from MITO
            //TODO: do we need to scale it down?
            for (Person pp : assembledScenario.getPopulation().getPersons().values()) {
                String mode = mainModeIdentifier.identifyMainMode(TripStructureUtils.getLegs(pp.getSelectedPlan()));
                if("bike".equals(mode) || "walk".equals(mode)){
                    if (random.nextDouble() < properties.healthData.matsim_scale_factor_bikePed){
                        populationBikePed.addPerson(pp);
                    }
                }
            }

            logger.warn("Running MATSim transport model for " + day + " Bike&Ped scenario " + year + ".");
            //initial bike, ped simulation config
            Config bikePedConfig = ConfigUtils.loadConfig(initialMatsimConfig.getContext());
            bikePedConfig.addModule(new BicycleConfigGroup());
            bikePedConfig.addModule(new WalkConfigGroup());
            fillBikePedConfig(bikePedConfig, year, day);

            //initialize scenario
            MutableScenario matsimScenario = (MutableScenario) ScenarioUtils.loadScenario(bikePedConfig);
            matsimScenario.setPopulation(populationBikePed);
            logger.info("total population " + day + " | Bike Walk: " + populationBikePed.getPersons().size());

            // set vehicles
            EnumMap<Mode, EnumMap<MitoGender, Map<Integer,Double>>> allSpeeds = ((DataContainerHealth)dataContainer).getAvgSpeeds();
            VehiclesFactory fac = VehicleUtils.getFactory();
            for(MitoGender gender : MitoGender.values()) {
                for(int age = 0 ; age <= 100 ; age++) {
                    VehicleType walk = fac.createVehicleType(Id.create(TransportMode.walk + gender + age, VehicleType.class));
                    walk.setMaximumVelocity(allSpeeds.get(Mode.walk).get(gender).get(age));
                    walk.setNetworkMode(TransportMode.walk);
                    walk.setPcuEquivalents(0.);
                    matsimScenario.getVehicles().addVehicleType(walk);

                    VehicleType bicycle = fac.createVehicleType(Id.create(TransportMode.bike + gender + age, VehicleType.class));
                    bicycle.setMaximumVelocity(allSpeeds.get(Mode.bicycle).get(gender).get(age));
                    bicycle.setNetworkMode(TransportMode.bike);
                    bicycle.setPcuEquivalents(0.);
                    matsimScenario.getVehicles().addVehicleType(bicycle);
                }
            }

            // Create vehicle for each person (i.e., trip)
            for(Person person : matsimScenario.getPopulation().getPersons().values()) {
                MitoGender gender = (MitoGender) person.getAttributes().getAttribute("sex");
                int age = (int) person.getAttributes().getAttribute("age");
                String mode = (String) person.getAttributes().getAttribute("mode");
                Id<Vehicle> vehicleId = Id.createVehicleId(person.getId().toString());
                VehicleType vehicleType = matsimScenario.getVehicles().getVehicleTypes().get(Id.create(mode + gender + age, VehicleType.class));
                Vehicle veh = fac.createVehicle(vehicleId,vehicleType);
                Map<String,Id<Vehicle>> modeToVehicle = new HashMap<>();
                modeToVehicle.put(mode,vehicleId);
                VehicleUtils.insertVehicleIdsIntoPersonAttributes(person,modeToVehicle);
                matsimScenario.getVehicles().addVehicle(veh);
            }

            matsimScenario.getConfig().qsim().setVehiclesSource(QSimConfigGroup.VehiclesSource.fromVehiclesData);
            matsimScenario.getConfig().qsim().setVehicleBehavior(QSimConfigGroup.VehicleBehavior.teleport);
            matsimScenario.getConfig().qsim().setUsePersonIdForMissingVehicleId(true);

            // Create active mode networkk
            Network activeNetwork = extractModeSpecificNetwork(matsimScenario.getNetwork(),new HashSet<>(Arrays.asList(TransportMode.bike, TransportMode.walk)));
            matsimScenario.setNetwork(activeNetwork);

            //set up controler
            final Controler controlerBikePed = new Controler(matsimScenario);
            controlerBikePed.addOverridingModule(new WalkModule());
            controlerBikePed.addOverridingModule(new BicycleModule());


            controlerBikePed.run();
            logger.warn("Running MATSim transport model for " + day + " Bike&Ped scenario " + year + " finished.");
        }
    }

    private void runCarTruckSimulation(int year, Map<Day, Scenario> assembledMultiScenario) {
        for (Day day : simulatedDays) {
            Scenario assembledScenario = assembledMultiScenario.get(day);
            MainModeIdentifierImpl mainModeIdentifier = new MainModeIdentifierImpl();

            Population populationCarTruck = PopulationUtils.createPopulation(ConfigUtils.createConfig());

            // Add truck plans from tfgm (static)
            String truckPlans = properties.main.baseDirectory + properties.healthData.truck_plan_file;
            PopulationUtils.readPopulation(populationCarTruck, truckPlans);

            double truckSample = 0.;
            if(day.equals(Day.thursday)) {
                truckSample = 1.278066;
            } else if (day.equals(Day.saturday)) {
                truckSample = 0.430817;
            } else if (day.equals(Day.sunday)) {
                truckSample = 0.178852;
            } else {
                throw new RuntimeException("Unrecognised day " + day);
            }

            logger.info(day + " truck sample: " + truckSample);
            if(truckSample < 1.) {
                PopulationUtils.sampleDown(populationCarTruck, truckSample);
            }

            logger.warn("MATSim truck population: " + day + "|" + year + "|" + populationCarTruck.getPersons().size());

            //Add through car plans from tfgm (static)
            Population populationThroughTraffic = PopulationUtils.createPopulation(ConfigUtils.createConfig());

            String throughPlans = properties.main.baseDirectory + properties.healthData.throughTraffic_plan_file;
            PopulationUtils.readPopulation(populationThroughTraffic, throughPlans);

            double throughCarSample = 0.;
            if(day.equals(Day.thursday)) {
                throughCarSample = 1.;
            } else if (day.equals(Day.saturday)) {
                throughCarSample = 0.79;
            } else if (day.equals(Day.sunday)) {
                throughCarSample = 0.46;
            } else {
                throw new RuntimeException("Unrecognised day " + day);
            }

            logger.info(day + " through traffic sample: " + throughCarSample);
            if(throughCarSample < 1.) {
                PopulationUtils.sampleDown(populationThroughTraffic, throughCarSample);
            }

            for (Person pp : populationThroughTraffic.getPersons().values()) {
                populationCarTruck.addPerson(pp);
            }

            logger.warn("MATSim truck/through population: " + day + "|" + year + "|" + populationCarTruck.getPersons().size());

            // Add car and pt plans from MITO. SwissRailRaptor will expand each "pt" leg into
            // walk-access → transit_walk/bus/tram → walk-egress against the schedule at routing time.
            for (Person pp : assembledScenario.getPopulation().getPersons().values()) {
                String mode = mainModeIdentifier.identifyMainMode(TripStructureUtils.getLegs(pp.getSelectedPlan()));
                if ("car".equals(mode) || "pt".equals(mode)) {
                        populationCarTruck.addPerson(pp);
                }
            }

            logger.warn("MATSim car/truck/through/pt population: " + day + "|" + year + "|" + populationCarTruck.getPersons().size());


            logger.warn("Running MATSim transport model for " + day + " car scenario " + year + ".");
            //initialize car truck config
            Config carTruckConfig = ConfigUtils.loadConfig(initialMatsimConfig.getContext());
            finalizeCarTruckConfig(carTruckConfig, year, day);

            //initialize scenario
            MutableScenario matsimScenario = (MutableScenario) ScenarioUtils.loadScenario(carTruckConfig);
            matsimScenario.setPopulation(populationCarTruck);

            // Fill any gaps between schedule and transit-vehicles file (safe no-op when
            // transit-vehicles.xml already covers all routes).
            new CreateVehiclesForSchedule(matsimScenario.getTransitSchedule(),
                                          matsimScenario.getTransitVehicles()).run();

            // Road-running PT (buses) must share road space with cars/trucks so congestion
            // propagates into bus in-vehicle times. We:
            //   1. Force networkMode = "car" so buses are simulated on the car network alongside
            //      cars/trucks and contend for the same link flow/storage capacity.
            //   2. Scale PCU by the car sample factor (e.g. bus PCU 2.5 -> 0.25 at a 10% sample)
            //      so each full-frequency bus occupies the correct fraction of the sampled
            //      capacity. (Consistent only while scale.factor == 1; otherwise scale by
            //      scale.factor * matsim_scale_factor_car to match flowCapFactor.)
            // Trams/rail (networkMode tram/rail) run on dedicated PT links and are left untouched.
            // allowedModes governs WHERE interaction happens, not whether a bus can move: QSim does
            // not re-check allowedModes along a vehicle's (schedule-defined) route, so buses still
            // traverse bus-only links fine. Links coded car+bus congest together; bus-only links
            // (bus lanes/busways) correctly see no car interaction.
            double carSampleFactor = properties.healthData.matsim_scale_factor_car;
            Set<String> dedicatedPtModes = Set.of("tram", "rail", "train", "subway", "ferry");
            for (VehicleType vt : matsimScenario.getTransitVehicles().getVehicleTypes().values()) {
                String netMode = vt.getNetworkMode();
                if (netMode != null && dedicatedPtModes.contains(netMode.toLowerCase())) {
                    logger.info("PT vehicle type " + vt.getId() + " kept on dedicated networkMode=" + netMode
                            + " (no PCU scaling, no car-queue interaction).");
                    continue;
                }
                vt.setNetworkMode(TransportMode.car);
                vt.setPcuEquivalents(vt.getPcuEquivalents() * carSampleFactor);
                logger.info("PT vehicle type " + vt.getId() + " (was networkMode=" + netMode
                        + ") moved to car queue with scaled PCU " + vt.getPcuEquivalents());
            }

            //set vehicle types — the base config's vehicles file (vehicletypes.xml) may already
            //define car/truck, which ScenarioUtils.loadScenario loads into the scenario. Only add
            //them when absent, otherwise addVehicleType throws "Vehicle type with id = car already
            //exists". When present, the file's definitions are authoritative (modeVehicleTypesFromVehiclesData).
            Id<VehicleType> carTypeId = Id.create(TransportMode.car, VehicleType.class);
            if (!matsimScenario.getVehicles().getVehicleTypes().containsKey(carTypeId)) {
                VehicleType car = VehicleUtils.getFactory().createVehicleType(carTypeId);
                car.setPcuEquivalents(1.);
                car.setLength(7.5);
                car.setNetworkMode(TransportMode.car);
                matsimScenario.getVehicles().addVehicleType(car);
            }

            Id<VehicleType> truckTypeId = Id.create(TransportMode.truck, VehicleType.class);
            if (!matsimScenario.getVehicles().getVehicleTypes().containsKey(truckTypeId)) {
                VehicleType truck = VehicleUtils.getFactory().createVehicleType(truckTypeId);
                truck.setPcuEquivalents(2.5);
                truck.setLength(15.);
                truck.setNetworkMode(TransportMode.truck);
                matsimScenario.getVehicles().addVehicleType(truck);
            }

            matsimScenario.getConfig().qsim().setVehiclesSource(QSimConfigGroup.VehiclesSource.modeVehicleTypesFromVehiclesData);

            //set up controler
            final Controler controlerCar = new Controler(matsimScenario);
            controlerCar.addOverridingModule(new SwissRailRaptorModule());
            controlerCar.run();
            logger.warn("Running MATSim transport model for " + day + " car scenario " + year + " finished.");

            // Get travel Times from MATSim - weekday
            if(day.equals(Day.thursday)){
                logger.warn("Using MATSim to compute travel times from zone to zone.");
                TravelTime travelTime = controlerCar.getLinkTravelTimes();
                TravelDisutility travelDisutility = controlerCar.getTravelDisutilityFactory().createTravelDisutility(travelTime);
                updateTravelTimes(travelTime, travelDisutility);
            }
        }
    }

    /**
     * One integrated simulation for all modes, replacing the separate runCarTruckSimulation /
     * runBikePedSimulation pair so that car, pt, bike and walk compete against each other
     * (mode choice via ChangeTripMode replanning).
     *
     * Teleport variant for active modes: bike/walk are network-ROUTED with the JIBE travel
     * times and disutilities, but they are not QSim main modes — the QSim teleports them along
     * their routed path using the routed travel time. They therefore never interact with
     * cars/trucks/pt by construction, and add no meaningful QSim cost across the ~100
     * iterations that the motorized side needs to equilibrate. Buses share the car queue as
     * before; trams/rail stay on dedicated links.
     */
    private void runAllModesSimulation(int year, Map<Day, Scenario> assembledMultiScenario) {
        for (Day day : simulatedDays) {
            Scenario assembledScenario = assembledMultiScenario.get(day);

            Population population = PopulationUtils.createPopulation(ConfigUtils.createConfig());

            // Add truck plans from tfgm (static)
            String truckPlans = properties.main.baseDirectory + properties.healthData.truck_plan_file;
            PopulationUtils.readPopulation(population, truckPlans);

            double truckSample;
            if(day.equals(Day.thursday)) {
                truckSample = 1.278066;
            } else if (day.equals(Day.saturday)) {
                truckSample = 0.430817;
            } else if (day.equals(Day.sunday)) {
                truckSample = 0.178852;
            } else {
                throw new RuntimeException("Unrecognised day " + day);
            }

            logger.info(day + " truck sample: " + truckSample);
            if(truckSample < 1.) {
                PopulationUtils.sampleDown(population, truckSample);
            }

            logger.warn("MATSim truck population: " + day + "|" + year + "|" + population.getPersons().size());

            //Add through car plans from tfgm (static)
            Population populationThroughTraffic = PopulationUtils.createPopulation(ConfigUtils.createConfig());

            String throughPlans = properties.main.baseDirectory + properties.healthData.throughTraffic_plan_file;
            PopulationUtils.readPopulation(populationThroughTraffic, throughPlans);

            double throughCarSample;
            if(day.equals(Day.thursday)) {
                throughCarSample = 1.;
            } else if (day.equals(Day.saturday)) {
                throughCarSample = 0.79;
            } else if (day.equals(Day.sunday)) {
                throughCarSample = 0.46;
            } else {
                throw new RuntimeException("Unrecognised day " + day);
            }

            logger.info(day + " through traffic sample: " + throughCarSample);
            if(throughCarSample < 1.) {
                PopulationUtils.sampleDown(populationThroughTraffic, throughCarSample);
            }

            for (Person pp : populationThroughTraffic.getPersons().values()) {
                population.addPerson(pp);
            }

            // Everything loaded so far is fixed-mode background traffic
            for (Person pp : population.getPersons().values()) {
                PopulationUtils.putSubpopulation(pp, SUBPOP_FREIGHT);
            }

            logger.warn("MATSim truck/through population: " + day + "|" + year + "|" + population.getPersons().size());

            // Add ALL MITO persons — no mode filter: MITO's chosen mode is only the starting
            // point, agents may switch among car/pt/bike/walk during replanning.
            for (Person pp : assembledScenario.getPopulation().getPersons().values()) {
                PopulationUtils.putSubpopulation(pp, SUBPOP_PERSON);
                population.addPerson(pp);
            }

            logger.warn("MATSim all-modes population: " + day + "|" + year + "|" + population.getPersons().size());

            logger.warn("Running MATSim transport model for " + day + " all-modes scenario " + year + ".");
            Config allModesConfig = ConfigUtils.loadConfig(initialMatsimConfig.getContext());
            allModesConfig.addModule(new BicycleConfigGroup());
            allModesConfig.addModule(new WalkConfigGroup());
            fillAllModesConfig(allModesConfig, year, day);

            //initialize scenario
            MutableScenario matsimScenario = (MutableScenario) ScenarioUtils.loadScenario(allModesConfig);
            matsimScenario.setPopulation(population);

            // Fill any gaps between schedule and transit-vehicles file (safe no-op when
            // transit-vehicles.xml already covers all routes).
            new CreateVehiclesForSchedule(matsimScenario.getTransitSchedule(),
                                          matsimScenario.getTransitVehicles()).run();

            // Road-running PT rides in the car queue (networkMode=car) so buses EXPERIENCE car
            // congestion — but with PCU 0 they add NO load (no flow/storage consumption).
            // Rationale: link capacities were calibrated against observed (Google Maps) car
            // travel times with only cars+trucks simulated, so the congestion effect of real
            // buses is already baked into the effective link parameters. Explicit buses with
            // positive PCU would double-count that load (and at 10% storage capacity, gridlock
            // short urban links). One-way coupling — cars delay buses, buses don't delay cars —
            // matches the calibration assumption. Trams/rail stay on dedicated links untouched.
            Set<String> dedicatedPtModes = Set.of("tram", "rail", "train", "subway", "ferry");
            for (VehicleType vt : matsimScenario.getTransitVehicles().getVehicleTypes().values()) {
                String netMode = vt.getNetworkMode();
                if (netMode != null && dedicatedPtModes.contains(netMode.toLowerCase())) {
                    logger.info("PT vehicle type " + vt.getId() + " kept on dedicated networkMode=" + netMode
                            + " (no car-queue interaction).");
                    continue;
                }
                vt.setNetworkMode(TransportMode.car);
                vt.setPcuEquivalents(0.);
                logger.info("PT vehicle type " + vt.getId() + " (was networkMode=" + netMode
                        + ") moved to car queue with PCU 0 (experiences congestion, adds no load).");
            }

            // Mode-level vehicle types. With modeVehicleTypesFromVehiclesData the QSim builds
            // car/truck vehicles from these; PrepareForSim additionally needs a "bike" type to
            // provision vehicles for the bike network mode. Note: the routers receive a null
            // vehicle (plain NetworkRoutingModule), so routed bike/walk travel times use the
            // JIBE config-group default speeds (5.5 m/s bike, 1.67 m/s walk), not these types.
            // Per-person age/gender speeds (as in runBikePedSimulation) would require
            // fromVehiclesData plus one vehicle per person and mode, in the QSim.
            VehiclesFactory fac = VehicleUtils.getFactory();
            Id<VehicleType> carTypeId = Id.create(TransportMode.car, VehicleType.class);
            if (!matsimScenario.getVehicles().getVehicleTypes().containsKey(carTypeId)) {
                VehicleType car = fac.createVehicleType(carTypeId);
                car.setPcuEquivalents(1.);
                car.setLength(7.5);
                car.setNetworkMode(TransportMode.car);
                matsimScenario.getVehicles().addVehicleType(car);
            }

            Id<VehicleType> truckTypeId = Id.create(TransportMode.truck, VehicleType.class);
            if (!matsimScenario.getVehicles().getVehicleTypes().containsKey(truckTypeId)) {
                VehicleType truck = fac.createVehicleType(truckTypeId);
                truck.setPcuEquivalents(2.5);
                truck.setLength(15.);
                truck.setNetworkMode(TransportMode.truck);
                matsimScenario.getVehicles().addVehicleType(truck);
            }

            Id<VehicleType> bikeTypeId = Id.create(TransportMode.bike, VehicleType.class);
            if (!matsimScenario.getVehicles().getVehicleTypes().containsKey(bikeTypeId)) {
                VehicleType bicycle = fac.createVehicleType(bikeTypeId);
                bicycle.setMaximumVelocity(averageMaxSpeed(Mode.bicycle));
                bicycle.setNetworkMode(TransportMode.bike);
                bicycle.setPcuEquivalents(0.);
                matsimScenario.getVehicles().addVehicleType(bicycle);
            }

            matsimScenario.getConfig().qsim().setVehiclesSource(QSimConfigGroup.VehiclesSource.modeVehicleTypesFromVehiclesData);

            //set up controler
            final Controler controler = new Controler(matsimScenario);
            controler.addOverridingModule(new SwissRailRaptorModule());
            controler.addOverridingModule(new WalkModule());
            controler.addOverridingModule(new BicycleModule());
            // Walk is teleported at config level (raptor requirement, see fillAllModesConfig),
            // but direct walk legs still need JIBE network routing. Overriding the walk
            // routing-module binding re-attaches network routing — the network-vs-teleported
            // consistency check only inspects the config, not Guice bindings. The WalkModule
            // above provides the walk TravelTime and disutility this provider picks up.
            controler.addOverridingModule(new AbstractModule() {
                @Override
                public void install() {
                    addRoutingModuleBinding(TransportMode.walk).toProvider(new NetworkRoutingProvider(TransportMode.walk));
                }
            });
            controler.run();
            logger.warn("Running MATSim transport model for " + day + " all-modes scenario " + year + " finished.");

            // Get travel Times from MATSim - weekday
            if(day.equals(Day.thursday)){
                logger.warn("Using MATSim to compute travel times from zone to zone.");
                TravelTime travelTime = controler.getLinkTravelTimes();
                TravelDisutility travelDisutility = controler.getTravelDisutilityFactory().createTravelDisutility(travelTime);
                updateTravelTimes(travelTime, travelDisutility);
            }
        }
    }

    private void fillAllModesConfig(Config config, int year, Day day) {
        // Set basic setting
        config.qsim().setEndTime(24*60*60);
        config.global().setNumberOfThreads(16);
        config.qsim().setNumberOfThreads(16);
        config.routing().setRoutingRandomness(0.);

        // --- Public Transport --- (same setup as finalizeCarTruckConfig)
        config.network().setInputFile(properties.main.baseDirectory + properties.healthData.multimodalNetwork_file);
        config.transit().setTransitScheduleFile(properties.main.baseDirectory + properties.healthData.transitSchedule_file);
        config.transit().setVehiclesFile(properties.main.baseDirectory + properties.healthData.transitVehicles_file);
        config.transit().setUseTransit(true);
        // Transit vehicles run in the QSim so buses EXPERIENCE car congestion (flood
        // disruption propagates into pt in-vehicle times). They add no load themselves:
        // road-running pt types are set to PCU 0 in runAllModesSimulation, because the
        // bus impact on car traffic is already baked into the Google-Maps-calibrated
        // link capacities.
        config.transit().setUsingTransitInMobsim(true);
        config.transit().setTransitModes(Set.of("pt"));
        ConfigUtils.addOrGetModule(config, SwissRailRaptorConfigGroup.class);

        // Set scale factor. One QSim → one flowCapFactor: the car sample governs road
        // capacity. Bike/walk are teleported and consume no capacity, so the separate
        // bikePed scale factor no longer applies here.
        config.qsim().setFlowCapFactor(properties.main.scaleFactor * properties.transportModel.matsimScaleFactor);
        config.qsim().setStorageCapFactor(properties.main.scaleFactor * properties.transportModel.matsimScaleFactor);
        logger.info("Flow Cap Factor for all modes: " + config.qsim().getFlowCapFactor());
        logger.info("Storage Cap Factor for all modes: " + config.qsim().getStorageCapFactor());

        // Set output directory
        final String outputDirectoryRoot = properties.main.baseDirectory + "scenOutput/" + properties.main.scenarioName;
        String outputDirectory = outputDirectoryRoot + "/matsim/" + year + "/" + day + "/allModes/";
        config.controller().setRunId(String.valueOf(year));
        config.controller().setOutputDirectory(outputDirectory);
        config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);

        // Iterations: the motorized side needs ~100 iterations to equilibrate congestion and
        // mode shares; bike/walk converge immediately and ride along at negligible cost.
        // Innovation is switched off for the last 20% so final mode shares come from stable
        // selection rather than ongoing experimentation.
        //config.controller().setLastIteration(100);
        config.replanning().setFractionOfIterationsToDisableInnovation(0.8);
        config.controller().setWritePlansInterval(Math.max(config.controller().getLastIteration(), 1));
        config.controller().setWriteEventsInterval(Math.max(config.controller().getLastIteration(), 1));

        // Modes: only car/truck are QSim main modes (pt vehicles are driven by the transit
        // engine). bike is a network routing mode WITHOUT being a main mode — the QSim
        // teleports it along its routed path with the routed travel time. walk must STAY a
        // teleported mode in the config: SwissRailRaptor's TransitRouterConfig hard-requires
        // teleported walk params (beeline speed for pt access/egress/transfer walks) and
        // MATSim forbids a mode being both network and teleported. Direct walk legs are
        // nevertheless network-routed via the routing-module override in
        // runAllModesSimulation. To move bike/walk into the QSim later (e.g. for link-level
        // exposure events), make them main modes and switch to per-person vehicles + SeepageQ.
        config.qsim().setMainModes(List.of(TransportMode.car, TransportMode.truck));
        config.routing().setNetworkModes(List.of(TransportMode.car, TransportMode.truck, TransportMode.bike));
        config.routing().removeModeRoutingParams(TransportMode.bike);
        config.routing().setAccessEgressType(RoutingConfigGroup.AccessEgressType.none);

        // --- Scoring ---
        // All modes are scored in ONE utility scale; the mode constants below are the
        // calibration knobs. TODO: calibrate constants so the baseline (no-flood) run
        // reproduces MITO's mode shares before interpreting flood-scenario mode shifts.
        ModeParams ptParams = config.scoring().getOrCreateModeParams(TransportMode.pt);
        ptParams.setConstant(0.);
        ptParams.setMarginalUtilityOfTraveling(-6.0);
        ptParams.setMarginalUtilityOfDistance(0.);
        ptParams.setMonetaryDistanceRate(0.);

        ModeParams walkParams = config.scoring().getOrCreateModeParams(TransportMode.walk);
        walkParams.setConstant(0.);
        walkParams.setMarginalUtilityOfDistance(-0.0004);
        walkParams.setMarginalUtilityOfTraveling(-6.0);
        walkParams.setMonetaryDistanceRate(0.);

        ModeParams bicycleParams = config.scoring().getOrCreateModeParams(TransportMode.bike);
        bicycleParams.setConstant(0.);
        bicycleParams.setMarginalUtilityOfDistance(-0.0004);
        bicycleParams.setMarginalUtilityOfTraveling(-6.0);
        bicycleParams.setMonetaryDistanceRate(0.);

        // Raptor's transfer walks at the same stop area emit "non_network_walk"; without
        // its own ModeParams the scoring lookup NPEs. Mirror walk params.
        ModeParams nonNetworkWalk = new ModeParams("non_network_walk");
        nonNetworkWalk.setConstant(walkParams.getConstant());
        nonNetworkWalk.setMarginalUtilityOfTraveling(walkParams.getMarginalUtilityOfTraveling());
        nonNetworkWalk.setMarginalUtilityOfDistance(walkParams.getMarginalUtilityOfDistance());
        nonNetworkWalk.setMonetaryDistanceRate(walkParams.getMonetaryDistanceRate());
        config.scoring().addModeParams(nonNetworkWalk);

        ModeParams carParams = config.scoring().getOrCreateModeParams(TransportMode.car);
        ModeParams truckParams = new ModeParams(TransportMode.truck);
        truckParams.setConstant(carParams.getConstant());
        truckParams.setDailyMonetaryConstant(carParams.getDailyMonetaryConstant());
        truckParams.setMarginalUtilityOfDistance(carParams.getMarginalUtilityOfDistance());
        truckParams.setDailyUtilityConstant(carParams.getDailyUtilityConstant());
        truckParams.setMonetaryDistanceRate(carParams.getMonetaryDistanceRate());
        config.scoring().addModeParams(truckParams);

        // Activity params (guarded — the base config file may already define them)
        Map<String, Double> typicalDurations = Map.of(
                "home", 12. * 60 * 60,
                "work", 8. * 60 * 60,
                "education", 8. * 60 * 60,
                "shopping", 1. * 60 * 60,
                "recreation", 1. * 60 * 60,
                "other", 1. * 60 * 60,
                "airport", 1. * 60 * 60);
        for (Map.Entry<String, Double> e : typicalDurations.entrySet()) {
            if (config.scoring().getActivityParams(e.getKey()) == null) {
                config.scoring().addActivityParams(
                        new ScoringConfigGroup.ActivityParams(e.getKey()).setTypicalDuration(e.getValue()));
            }
        }

        // --- JIBE bike/walk routing inputs (as in fillBikePedConfig) ---
        // Flood hook: water-depth sensitivity for active modes goes here later — add a
        // l -> waterDepth(l) term with a calibrated weight so flooded links are avoided,
        // plus a depth-dependent speed in the link speed calculators for the time effect.

        // BIKE ATTRIBUTES
        List<ToDoubleFunction<Link>> bikeAttributes = new ArrayList<>();
        bikeAttributes.add(l -> Math.max(Math.min(Gradient.getGradient(l),0.5),0.));
        bikeAttributes.add(l -> LinkStress.getStress(l,TransportMode.bike));

        // Bike weights
        Function<Person,double[]> bikeWeights = p -> {
            switch((Purpose) p.getAttributes().getAttribute("purpose")) {
                case HBW -> {
                    if(p.getAttributes().getAttribute("sex").equals(MitoGender.FEMALE)) {
                        return new double[] {35.9032908,2.3084587 + 2.7762033};
                    } else {
                        return new double[] {35.9032908,2.3084587};
                    }
                }
                case HBE -> {
                    return new double[] {0,4.3075357};
                }
                case HBS, HBR, HBO -> {
                    if((int) p.getAttributes().getAttribute("age") < 15) {
                        return new double[] {57.0135325,1.2411983 + 6.4243251};
                    } else {
                        return new double[] {57.0135325,1.2411983};
                    }
                }
                default -> {
                    return null;
                }
            }
        };

        // Bicycle config group
        BicycleConfigGroup bicycle = (BicycleConfigGroup) config.getModules().get(BicycleConfigGroup.GROUP_NAME);
        bicycle.setAttributes(bikeAttributes);
        bicycle.setWeights(bikeWeights);

        // WALK ATTRIBUTES
        List<ToDoubleFunction<Link>> walkAttributes = new ArrayList<>();
        walkAttributes.add(l -> Math.max(0.,0.81 - LinkAmbience.getVgviFactor(l)));
        walkAttributes.add(l -> Math.min(1.,((double) l.getAttributes().getAttribute("speedLimitMPH")) / 50.));
        walkAttributes.add(l -> JctStress.getStressProp(l,TransportMode.walk));

        // Walk weights
        Function<Person,double[]> walkWeights = p -> {
            switch ((Purpose) p.getAttributes().getAttribute("purpose")) {
                case HBW -> {
                    return new double[]{0.3307472, 0, 4.9887390};
                }
                case HBE -> {
                    return new double[]{0, 0, 1.0037846};
                }
                case HBS, HBR, HBO -> {
                    if ((int) p.getAttributes().getAttribute("age") < 15) {
                        return new double[]{0.7789561, 0.4479527 + 2.0418898, 5.8219067};
                    } else if ((int) p.getAttributes().getAttribute("age") >= 65) {
                        return new double[]{0.7789561, 0.4479527 + 0.3715017, 5.8219067};
                    } else {
                        return new double[]{0.7789561, 0.4479527, 5.8219067};
                    }
                }
                case HBA -> {
                    return new double[]{0.6908324, 0, 0};
                }
                case NHBO -> {
                    return new double[]{0, 3.4485883, 0};
                }
                default -> {
                    return null;
                }
            }
        };

        // Walk config group
        WalkConfigGroup walkConfigGroup = (WalkConfigGroup) config.getModules().get(WalkConfigGroup.GROUP_NAME);
        walkConfigGroup.setAttributes(walkAttributes);
        walkConfigGroup.setWeights(walkWeights);

        // --- Replanning: strategies per subpopulation ---
        config.replanning().setMaxAgentPlanMemorySize(5);
        config.replanning().clearStrategySettings();

        // Persons: mode choice across car/pt/bike/walk. ChangeTripMode flips ALL legs of a
        // plan to one randomly drawn mode and re-routes — the right innovator here because
        // each MITO-assembled MATSim person carries exactly one trip (home-based trips as
        // closed home→activity→home tours), so tours stay mode-consistent and open NHB
        // plans are covered too (SubtourModeChoice would silently skip those).
        {
            ReplanningConfigGroup.StrategySettings strategySettings = new ReplanningConfigGroup.StrategySettings();
            strategySettings.setStrategyName("ChangeExpBeta");
            strategySettings.setWeight(0.7);
            strategySettings.setSubpopulation(SUBPOP_PERSON);
            config.replanning().addStrategySettings(strategySettings);
        }
        {
            ReplanningConfigGroup.StrategySettings strategySettings = new ReplanningConfigGroup.StrategySettings();
            strategySettings.setStrategyName("ChangeTripMode");
            strategySettings.setWeight(0.15);
            strategySettings.setSubpopulation(SUBPOP_PERSON);
            config.replanning().addStrategySettings(strategySettings);
        }
        {
            ReplanningConfigGroup.StrategySettings strategySettings = new ReplanningConfigGroup.StrategySettings();
            strategySettings.setStrategyName("ReRoute");
            strategySettings.setWeight(0.15);
            strategySettings.setSubpopulation(SUBPOP_PERSON);
            config.replanning().addStrategySettings(strategySettings);
        }

        // Freight: fixed mode, may only re-route
        {
            ReplanningConfigGroup.StrategySettings strategySettings = new ReplanningConfigGroup.StrategySettings();
            strategySettings.setStrategyName("ChangeExpBeta");
            strategySettings.setWeight(0.85);
            strategySettings.setSubpopulation(SUBPOP_FREIGHT);
            config.replanning().addStrategySettings(strategySettings);
        }
        {
            ReplanningConfigGroup.StrategySettings strategySettings = new ReplanningConfigGroup.StrategySettings();
            strategySettings.setStrategyName("ReRoute");
            strategySettings.setWeight(0.15);
            strategySettings.setSubpopulation(SUBPOP_FREIGHT);
            config.replanning().addStrategySettings(strategySettings);
        }

        config.changeMode().setModes(new String[]{TransportMode.car, TransportMode.pt, TransportMode.bike, TransportMode.walk});
        // TODO MITO persons carry no car-availability attribute yet; until one is added,
        // every agent may choose car.
        config.changeMode().setIgnoreCarAvailability(true);
    }

    /**
     * Population-average maximum speed over all genders and ages, used for the mode-level
     * bike/walk vehicle types of the all-modes run.
     */
    private double averageMaxSpeed(Mode mode) {
        return ((DataContainerHealth) dataContainer).getAvgSpeeds().get(mode).values().stream()
                .flatMap(byAge -> byAge.values().stream())
                .mapToDouble(Double::doubleValue)
                .average()
                .orElseThrow(() -> new RuntimeException("No average speeds available for mode " + mode));
    }

    private void fillBikePedConfig(Config bikePedConfig, int year, Day day) {
        // set input file and basic controler settings
        final String outputDirectoryRoot = properties.main.baseDirectory + "scenOutput/" + properties.main.scenarioName;
        String outputDirectory = outputDirectoryRoot + "/matsim/" + year + "/" + day + "/bikePed/";
        bikePedConfig.controller().setOutputDirectory(outputDirectory);
        bikePedConfig.controller().setLastIteration(1);
        bikePedConfig.controller().setRunId(String.valueOf(year));
        bikePedConfig.controller().setWritePlansInterval(Math.max(bikePedConfig.controller().getLastIteration(), 1));
        bikePedConfig.controller().setWriteEventsInterval(Math.max(bikePedConfig.controller().getLastIteration(), 1));
        bikePedConfig.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);

        // set qsim - passingQ
        bikePedConfig.qsim().setFlowCapFactor(properties.main.scaleFactor * properties.healthData.matsim_scale_factor_bikePed);
        bikePedConfig.qsim().setStorageCapFactor(properties.main.scaleFactor * properties.healthData.matsim_scale_factor_bikePed);
        logger.info("Flow Cap Factor for bikePed: " + bikePedConfig.qsim().getFlowCapFactor());
        logger.info("Storage Cap Factor for bikePed: " + bikePedConfig.qsim().getStorageCapFactor());
        bikePedConfig.qsim().setEndTime(24*60*60);
        bikePedConfig.qsim().setLinkDynamics(QSimConfigGroup.LinkDynamics.PassingQ);


        // set routing modes
        List<String> mainModeList = new ArrayList<>();
        mainModeList.add(TransportMode.bike);
        mainModeList.add(TransportMode.walk);
        bikePedConfig.qsim().setMainModes(mainModeList);
        bikePedConfig.routing().setNetworkModes(mainModeList);
        bikePedConfig.routing().removeModeRoutingParams("bike");
        bikePedConfig.routing().removeModeRoutingParams("walk");
        bikePedConfig.routing().removeModeRoutingParams("pt");


        // BIKE ATTRIBUTES
        List<ToDoubleFunction<Link>> bikeAttributes = new ArrayList<>();
        bikeAttributes.add(l -> Math.max(Math.min(Gradient.getGradient(l),0.5),0.));
        bikeAttributes.add(l -> LinkStress.getStress(l,TransportMode.bike));

        // Bike weights
        Function<Person,double[]> bikeWeights = p -> {
            switch((Purpose) p.getAttributes().getAttribute("purpose")) {
                case HBW -> {
                    if(p.getAttributes().getAttribute("sex").equals(MitoGender.FEMALE)) {
                        return new double[] {35.9032908,2.3084587 + 2.7762033};
                    } else {
                        return new double[] {35.9032908,2.3084587};
                    }
                }
                case HBE -> {
                    return new double[] {0,4.3075357};
                }
                case HBS, HBR, HBO -> {
                    if((int) p.getAttributes().getAttribute("age") < 15) {
                        return new double[] {57.0135325,1.2411983 + 6.4243251};
                    } else {
                        return new double[] {57.0135325,1.2411983};
                    }
                }
                default -> {
                    return null;
                }
            }
        };

        // Bicycle config group
        BicycleConfigGroup bicycle = (BicycleConfigGroup) bikePedConfig.getModules().get(BicycleConfigGroup.GROUP_NAME);
        bicycle.setAttributes(bikeAttributes);
        bicycle.setWeights(bikeWeights);

        // WALK ATTRIBUTES
        List<ToDoubleFunction<Link>> walkAttributes = new ArrayList<>();
        walkAttributes.add(l -> Math.max(0.,0.81 - LinkAmbience.getVgviFactor(l)));
        walkAttributes.add(l -> Math.min(1.,((double) l.getAttributes().getAttribute("speedLimitMPH")) / 50.));
        walkAttributes.add(l -> JctStress.getStressProp(l,TransportMode.walk));

        // Walk weights
        Function<Person,double[]> walkWeights = p -> {
            switch ((Purpose) p.getAttributes().getAttribute("purpose")) {
                case HBW -> {
                    return new double[]{0.3307472, 0, 4.9887390};
                }
                case HBE -> {
                    return new double[]{0, 0, 1.0037846};
                }
                case HBS, HBR, HBO -> {
                    if ((int) p.getAttributes().getAttribute("age") < 15) {
                        return new double[]{0.7789561, 0.4479527 + 2.0418898, 5.8219067};
                    } else if ((int) p.getAttributes().getAttribute("age") >= 65) {
                        return new double[]{0.7789561, 0.4479527 + 0.3715017, 5.8219067};
                    } else {
                        return new double[]{0.7789561, 0.4479527, 5.8219067};
                    }
                }
                case HBA -> {
                    return new double[]{0.6908324, 0, 0};
                }
                case NHBO -> {
                    return new double[]{0, 3.4485883, 0};
                }
                default -> {
                    return null;
                }
            }
        };

        // Walk config group
        WalkConfigGroup walkConfigGroup = (WalkConfigGroup) bikePedConfig.getModules().get(WalkConfigGroup.GROUP_NAME);
        walkConfigGroup.setAttributes(walkAttributes);
        walkConfigGroup.setWeights(walkWeights);

        // set scoring parameters
        ModeParams bicycleParams = new ModeParams(TransportMode.bike);
        bicycleParams.setConstant(0. );
        bicycleParams.setMarginalUtilityOfDistance(-0.0004 );
        bicycleParams.setMarginalUtilityOfTraveling(-6.0 );
        bicycleParams.setMonetaryDistanceRate(0. );
        bikePedConfig.scoring().addModeParams(bicycleParams);

        ModeParams walkParams = new ModeParams(TransportMode.walk);
        walkParams.setConstant(0. );
        walkParams.setMarginalUtilityOfDistance(-0.0004 );
        walkParams.setMarginalUtilityOfTraveling(-6.0 );
        walkParams.setMonetaryDistanceRate(0. );
        bikePedConfig.scoring().addModeParams(walkParams);

        ScoringConfigGroup.ActivityParams homeActivity = new ScoringConfigGroup.ActivityParams("home").setTypicalDuration(12 * 60 * 60);
        bikePedConfig.scoring().addActivityParams(homeActivity);

        ScoringConfigGroup.ActivityParams workActivity = new ScoringConfigGroup.ActivityParams("work").setTypicalDuration(8 * 60 * 60);
        bikePedConfig.scoring().addActivityParams(workActivity);

        ScoringConfigGroup.ActivityParams educationActivity = new ScoringConfigGroup.ActivityParams("education").setTypicalDuration(8 * 60 * 60);
        bikePedConfig.scoring().addActivityParams(educationActivity);

        ScoringConfigGroup.ActivityParams shoppingActivity = new ScoringConfigGroup.ActivityParams("shopping").setTypicalDuration(1 * 60 * 60);
        bikePedConfig.scoring().addActivityParams(shoppingActivity);

        ScoringConfigGroup.ActivityParams recreationActivity = new ScoringConfigGroup.ActivityParams("recreation").setTypicalDuration(1 * 60 * 60);
        bikePedConfig.scoring().addActivityParams(recreationActivity);

        ScoringConfigGroup.ActivityParams otherActivity = new ScoringConfigGroup.ActivityParams("other").setTypicalDuration(1 * 60 * 60);
        bikePedConfig.scoring().addActivityParams(otherActivity);

        ScoringConfigGroup.ActivityParams airportActivity = new ScoringConfigGroup.ActivityParams("airport").setTypicalDuration(1 * 60 * 60);
        bikePedConfig.scoring().addActivityParams(airportActivity);

        //Set strategy
        bikePedConfig.replanning().setMaxAgentPlanMemorySize(5);
        {
            ReplanningConfigGroup.StrategySettings strategySettings = new ReplanningConfigGroup.StrategySettings();
            strategySettings.setStrategyName("ChangeExpBeta");
            strategySettings.setWeight(0.8);
            bikePedConfig.replanning().addStrategySettings(strategySettings);
        }

        {
            ReplanningConfigGroup.StrategySettings strategySettings = new ReplanningConfigGroup.StrategySettings();
            strategySettings.setStrategyName("ReRoute");
            strategySettings.setWeight(0.2);
            bikePedConfig.replanning().addStrategySettings(strategySettings);
        }


        bikePedConfig.transit().setUsingTransitInMobsim(false);
        bikePedConfig.controller().setRoutingAlgorithmType(ControllerConfigGroup.RoutingAlgorithmType.Dijkstra);

    }


    private void finalizeCarTruckConfig(Config config, int year, Day day) {
        // Set basic setting
        config.qsim().setEndTime(24*60*60);
        config.global().setNumberOfThreads(16);
        config.qsim().setNumberOfThreads(16);
        config.routing().setRoutingRandomness(0.);

        // --- Public Transport ---
        // Multimodal network carries car/truck/bike/walk + bus/tram/rail. Buses share road
        // links with cars/trucks; trams/rail stay on dedicated pt links.
        config.network().setInputFile(properties.main.baseDirectory + properties.healthData.multimodalNetwork_file);
        config.transit().setTransitScheduleFile(properties.main.baseDirectory + properties.healthData.transitSchedule_file);
        config.transit().setVehiclesFile(properties.main.baseDirectory + properties.healthData.transitVehicles_file);
        config.transit().setUseTransit(true);
        // The base config sets usingTransitInMobsim=false (transit teleported). We MUST re-enable
        // it here, otherwise transit vehicles are never placed in the QSim: buses would not enter
        // the car queue and would never pick up congestion delay, defeating the road-PT interaction.
        config.transit().setUsingTransitInMobsim(true);
        config.transit().setTransitModes(Set.of("pt"));

        // Swiss Rail Raptor — faster PT router; access/egress kept teleported (no
        // intermodal access/egress configured), per PT_INTEGRATION_PROPOSAL §4.
        ConfigUtils.addOrGetModule(config, SwissRailRaptorConfigGroup.class);

        // pt scoring params — required so MITO-emitted "pt" legs can be scored.
        ModeParams ptParams = config.scoring().getOrCreateModeParams(TransportMode.pt);
        ptParams.setConstant(0.);
        ptParams.setMarginalUtilityOfTraveling(-6.0);
        ptParams.setMarginalUtilityOfDistance(0.);
        ptParams.setMonetaryDistanceRate(0.);

        // Raptor's transfer walks at the same stop area emit "non_network_walk"; without
        // its own ModeParams the scoring lookup NPEs. Mirror walk params.
        ModeParams walkParams = config.scoring().getOrCreateModeParams(TransportMode.walk);
        ModeParams nonNetworkWalk = new ModeParams("non_network_walk");
        nonNetworkWalk.setConstant(walkParams.getConstant());
        nonNetworkWalk.setMarginalUtilityOfTraveling(walkParams.getMarginalUtilityOfTraveling());
        nonNetworkWalk.setMarginalUtilityOfDistance(walkParams.getMarginalUtilityOfDistance());
        nonNetworkWalk.setMonetaryDistanceRate(walkParams.getMonetaryDistanceRate());
        config.scoring().addModeParams(nonNetworkWalk);

        // Set scale factor
        config.qsim().setFlowCapFactor(properties.main.scaleFactor * properties.healthData.matsim_scale_factor_car);
        config.qsim().setStorageCapFactor(properties.main.scaleFactor * properties.healthData.matsim_scale_factor_car);
        logger.info("Flow Cap Factor for car/truck: " + config.qsim().getFlowCapFactor());
        logger.info("Storage Cap Factor for car/truck: " + config.qsim().getStorageCapFactor());

        // Set output directory
        final String outputDirectoryRoot = properties.main.baseDirectory + "scenOutput/" + properties.main.scenarioName;
        String outputDirectory = outputDirectoryRoot + "/matsim/" + year + "/" + day + "/car/";
        config.controller().setRunId(String.valueOf(year));
        config.controller().setOutputDirectory(outputDirectory);
        config.controller().setWritePlansInterval(Math.max(config.controller().getLastIteration(), 1));
        config.controller().setWriteEventsInterval(Math.max(config.controller().getLastIteration(), 1));
        config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);



        //set mode params
        List<String> mainModeList = new ArrayList<>();
        mainModeList.add("car");
        mainModeList.add("truck");
        config.qsim().setMainModes(mainModeList);
        config.routing().setNetworkModes(mainModeList);
        config.routing().setAccessEgressType(RoutingConfigGroup.AccessEgressType.none);

        ModeParams carParams = config.scoring().getOrCreateModeParams(TransportMode.car);
        ModeParams truckParams = new ModeParams(TransportMode.truck);
        truckParams.setConstant(carParams.getConstant());
        truckParams.setDailyMonetaryConstant(carParams.getDailyMonetaryConstant());
        truckParams.setMarginalUtilityOfDistance(carParams.getMarginalUtilityOfDistance());
        truckParams.setDailyUtilityConstant(carParams.getDailyUtilityConstant());
        truckParams.setMonetaryDistanceRate(carParams.getMonetaryDistanceRate());
        config.scoring().addModeParams(truckParams);

    }

    /**
     * @param eventsFile
     */
    private void replayFromEvents(String eventsFile) {
        initialMatsimConfig.routing().setRoutingRandomness(0.);
        Scenario scenario = ScenarioUtils.loadScenario(initialMatsimConfig);
        TravelTime travelTime = TravelTimeUtils.createTravelTimesFromEvents(scenario.getNetwork(),scenario.getConfig(),eventsFile);
        TravelDisutility travelDisutility = ControlerDefaults.createDefaultTravelDisutilityFactory(scenario).createTravelDisutility(travelTime);
        updateTravelTimes(travelTime, travelDisutility);
    }

    private void updateTravelTimes(TravelTime travelTime, TravelDisutility disutility) {
        matsimData.update(disutility, travelTime);
        matsimData.getConfig().routing().setRoutingRandomness(0.);
        internalTravelTimes.update(matsimData);
        final TravelTimes mainTravelTimes = dataContainer.getTravelTimes();

        if (mainTravelTimes != this.internalTravelTimes && mainTravelTimes instanceof SkimTravelTimes) {
            ((SkimTravelTimes) mainTravelTimes).updateSkimMatrix(internalTravelTimes.getPeakSkim(TransportMode.car), TransportMode.car);
            if ((properties.transportModel.transportModelIdentifier == TransportModelPropertiesModule.TransportModelIdentifier.MATSIM)) {
                ((SkimTravelTimes) mainTravelTimes).updateSkimMatrix(internalTravelTimes.getPeakSkim(TransportMode.pt), TransportMode.pt);
            }
            ((SkimTravelTimes) mainTravelTimes).updateRegionalTravelTimes(dataContainer.getGeoData().getRegions().values(),
                    dataContainer.getGeoData().getZones().values());
        }
    }
    
    public static Network extractModeSpecificNetwork(Network fullNetwork, Set<String> transportModes) {
        Network modeSpecificNetwork = NetworkUtils.createNetwork();
        new TransportModeNetworkFilter(fullNetwork).filter(modeSpecificNetwork, transportModes);
        NetworkUtils.runNetworkCleaner(modeSpecificNetwork);
        return modeSpecificNetwork;
    }

}
