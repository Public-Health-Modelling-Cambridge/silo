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
import de.tum.bgu.msm.health.flood.FloodActiveModesModule;
import de.tum.bgu.msm.health.flood.WaterDepthData;
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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

import static org.matsim.core.config.groups.ScoringConfigGroup.ModeParams;

/**
 * @author qinzhang
 */
public final class MatsimTransportModelMCRHealth implements TransportModel {

    private static final Logger logger = LogManager.getLogger(MatsimTransportModelMCRHealth.class);

    // Subpopulations steer replanning and scoring. Person agents perform mode choice
    // (SubtourModeChoice) on top of re-routing / re-timing, so the modes compete within MATSim;
    // there is one person subpopulation per purpose group ("person_HBW", ...) because the mode
    // constants differ by group. "freight" (trucks + through traffic) keep their fixed mode and
    // may only re-route / re-time.
    private static final String SUBPOP_PERSON = "person";
    private static final String SUBPOP_FREIGHT = "freight";

    // Purpose groups of the mode-choice model. HBD = home-based discretionary (HBS, HBR, HBO).
    private static final List<String> PURPOSE_GROUPS = List.of("HBW", "HBE", "HBD", "HBA", "NHB");
    private static final String DEFAULT_PURPOSE_GROUP = "HBD";
    private static final List<String> PERSON_SUBPOPS = PURPOSE_GROUPS.stream()
            .map(group -> SUBPOP_PERSON + "_" + group).toList();

    // Car passenger, as exported by MITO (Mode.getMatsimMode(autoPassenger)) — must match exactly.
    private static final String CAR_PASSENGER = "car_passenger";
    private static final String NON_NETWORK_WALK = "non_network_walk";

    // --- Mode-choice parameters: MNL5_purposeASC, a 5-alternative multinomial logit (car
    // driver, car passenger, pt, walk, bike) with purpose-group constants, estimated with Apollo
    // on the TRADS survey (dare/modeChoice, mnl_modeChoice_5modes_apollo.R). MATSim's utility
    // is meant to equal the estimated one: the time and cost rates below stay as estimated and
    // only the constants (mode_constants.csv) are calibrated.
    // Caveat: the implied car value of time is high (about GBP 30/h against TAG's 5-12),
    // because the car cost (GBP 0.15/km) is collinear with car time. Keep a TAG-anchored
    // sensitivity run.
    private static final double PERFORMING = 11.019498;         // utils/h = -b_tt_driver * 60 (opportunity cost of time)
    private static final double MARG_UTIL_MONEY = 0.3665;       // = -b_cost, per GBP
    // marginalUtilityOfTraveling (utils/h) = (b_tt_mode - b_tt_driver) * 60
    private static final double TRAV_CAR = 0.;
    private static final double TRAV_CAR_PASSENGER = -3.446105;
    private static final double TRAV_PT = 8.794573;
    private static final double TRAV_WALK = 2.542577;
    private static final double TRAV_BIKE = 4.407420;
    // Waiting is not in the estimation data, so it is valued as pt time. In MATSim waitingPt
    // replaces the pt travel rate during the wait, on top of the opportunity cost; 0 would
    // value waiting as car time.
    private static final double WAITING_PT = TRAV_PT;
    private static final double PT_FARE_PER_BOARDING = -0.3665; // utils = b_cost * GBP 1
    private static final double MONETARY_RATE_CAR = -0.00015;   // GBP/m
    private static final double MONETARY_RATE_PT = -0.0001;     // GBP/m; car passenger, walk, bike pay nothing
    private static final double LATE_ARRIVAL = -3 * PERFORMING;

    // The all-modes run is split into two sequential Controler stages that write into ONE
    // output directory (.../allModes/), so ITERS/it.0 ... ITERS/it.200 form a single
    // continuous series and the iteration number alone says which regime an iteration
    // belongs to:
    //   STAGE_BASE      iterations 0..100   undisrupted network — agents reach equilibrium
    //   STAGE_DISRUPTED iterations 101..200 network change events active — agents re-adapt,
    //                                       warm-started from the stage-1 output plans so
    //                                       plan memory and scores carry over.
    // Stage 2 starts at 101, not 100, so it does not overwrite ITERS/it.100 — the converged
    // base iteration that the disrupted series is compared against.
    private static final int STAGE_BASE = 1;
    private static final int STAGE_DISRUPTED = 2;
    private static final int FIRST_ITERATION_BASE = 0;
    // Iteration counts come from the properties 'matsim.iterations.base' and
    // 'matsim.iterations.disrupted' (100 each by default, which gives the 0..100 / 101..200
    // split described above). About 20 disrupted iterations make a quick test: the first is
    // the pure shock (flooded network, base plans unchanged), rerouting relieves most of it
    // within ~15, and innovation switches off for the last 20%. Mode shares will have started
    // moving but not settled.
    private final int lastIterationBase;
    private final int firstIterationDisrupted;
    private final int lastIterationDisrupted;

    // Extra avoidance of flooded links by walk and bike, beyond their slowness: the routing
    // cost is multiplied by (1 + weight * depthFraction). 0 lets the flood act through travel
    // time alone — the conservative setting until the term is calibrated against evidence on
    // how far pedestrians actually detour around standing water.
    private static final double FLOOD_HAZARD_WEIGHT = 0.;

    // Stage 2 writes its own <runId>.scorestats.csv / modestats.csv into the shared output
    // directory, so stage 1's are copied aside as "<runId>.base_*" to keep the 0-100 history.
    // The plans are NOT copied: stage 2 warm-starts from stage 1's own iteration dump (see
    // basePlansFile), which MATSim writes and stage 2 never touches.
    private static final List<String> BASE_OUTPUTS_TO_PRESERVE =
            List.of("scorestats.csv", "modestats.csv");

    private final Properties properties;
    private final Config initialMatsimConfig;

    private final MatsimData matsimData;
    private final MatsimTravelTimesAndCosts internalTravelTimes;

    private final DataContainer dataContainer;

    private MatsimScenarioAssembler scenarioAssembler;

    private List<Day> simulatedDays;

    // Mode constants by purpose group and mode, read once from mode_constants.csv.
    private Map<String, Map<String, Double>> modeConstants;
    private final Set<String> unmappedPurposes = new HashSet<>();

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
        this.lastIterationBase = properties.healthData.iterationsBase;
        this.firstIterationDisrupted = lastIterationBase + 1;
        this.lastIterationDisrupted = lastIterationBase + properties.healthData.iterationsDisrupted;
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
        // runBikePedSimulation(year, assembledMultiScenario);
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
     * (mode choice via SubtourModeChoice replanning).
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

            // Add ALL MITO persons — no mode filter. Each agent STARTS from its MITO-chosen mode
            // (car/pt/bike/walk); SubtourModeChoice replanning then lets them switch modes.
            for (Person pp : assembledScenario.getPopulation().getPersons().values()) {
                PopulationUtils.putSubpopulation(pp, SUBPOP_PERSON + "_" + purposeGroup(pp));
                population.addPerson(pp);
            }

            logger.warn("MATSim all-modes population: " + day + "|" + year + "|" + population.getPersons().size());

            // --- Two-stage run, single output directory --------------------------------
            // Stage 1 lets agents reach equilibrium on the undisrupted network; stage 2 then
            // switches the network change events on and observes how they re-adapt. Both write
            // into .../allModes/, so ITERS/ holds one continuous 0..200 series. Stage 1 is
            // SKIPPED when a base run is already on disk — only the disrupted stage is rerun.
            String outputDirectory = allModesOutputDirectory(year, day);
            String basePlansFile = basePlansFile(outputDirectory, year);

            final Controler controler;
            if (!properties.healthData.runDisruptedStage) {
                // Base-only mode ('matsim.run.disrupted.stage = false'), for calibrating mode
                // shares: stage 1 is ALWAYS rerun (an
                // existing base run in this directory is overwritten), so each change to the
                // mode constants takes effect without deleting output by hand.
                logger.warn("Base-only mode: running MATSim stage 1 (iterations " + FIRST_ITERATION_BASE + "-"
                        + lastIterationBase + ", undisrupted network) for " + day + " all-modes scenario " + year
                        + ". Stage 2 is switched off; any existing output in " + outputDirectory + " is replaced.");
                controler = runAllModesStage(year, day, STAGE_BASE, population, null);
            } else {
                if (!new File(basePlansFile).exists()) {
                    logger.warn("Running MATSim stage 1 (iterations " + FIRST_ITERATION_BASE + "-" + lastIterationBase
                            + ", undisrupted network) for " + day + " all-modes scenario " + year + ".");
                    runAllModesStage(year, day, STAGE_BASE, population, null);
                } else {
                    logger.warn("Base run found (" + basePlansFile + ") — skipping iterations "
                            + FIRST_ITERATION_BASE + "-" + lastIterationBase + " and warm-starting stage 2 from it.");
                }
                preserveBaseOutputs(outputDirectory, year);

                logger.warn("Running MATSim stage 2 (iterations " + firstIterationDisrupted + "-" + lastIterationDisrupted
                        + ", network change events active) for " + day + " all-modes scenario " + year + ".");
                controler = runAllModesStage(year, day, STAGE_DISRUPTED, null, basePlansFile);
            }

            logger.warn("Running MATSim transport model for " + day + " all-modes scenario " + year + " finished.");

            // Get travel Times from MATSim - weekday. Taken from the last stage that ran: the
            // disrupted network normally, the undisrupted one in base-only mode.
            if(day.equals(Day.thursday)){
                logger.warn("Using MATSim to compute travel times from zone to zone.");
                TravelTime travelTime = controler.getLinkTravelTimes();
                TravelDisutility travelDisutility = controler.getTravelDisutilityFactory().createTravelDisutility(travelTime);
                updateTravelTimes(travelTime, travelDisutility);
            }
        }
    }

    /**
     * Runs one stage of the all-modes simulation and returns the finished Controler.
     *
     * @param stage              {@link #STAGE_BASE} (undisrupted, iterations 0..100) or
     *                           {@link #STAGE_DISRUPTED} (network change events, 100..200).
     * @param population         population to simulate; pass null for a warm-started stage, where
     *                           the population comes from {@code warmStartPlansFile} instead.
     * @param warmStartPlansFile output plans of the preceding stage, or null to start from
     *                           {@code population}. Subpopulation attributes are persisted in the
     *                           plans file, so the per-subpopulation strategies still bind.
     */
    private Controler runAllModesStage(int year, Day day, int stage, Population population, String warmStartPlansFile) {

            Config allModesConfig = ConfigUtils.loadConfig(initialMatsimConfig.getContext());
            allModesConfig.addModule(new BicycleConfigGroup());
            allModesConfig.addModule(new WalkConfigGroup());
            fillAllModesConfig(allModesConfig, year, day, stage);

            if (warmStartPlansFile != null) {
                allModesConfig.plans().setInputFile(warmStartPlansFile);
            }

            //initialize scenario
            MutableScenario matsimScenario = (MutableScenario) ScenarioUtils.loadScenario(allModesConfig);
            if (warmStartPlansFile == null) {
                matsimScenario.setPopulation(population);
            }
            logger.warn("Stage " + stage + " population: " + day + "|" + year + "|"
                    + matsimScenario.getPopulation().getPersons().size());

            // Passengers may use every link a car may use, so the network router's mode-filtered
            // network for car_passenger equals the car network.
            for (Link link : matsimScenario.getNetwork().getLinks().values()) {
                Set<String> allowed = link.getAllowedModes();
                if (allowed.contains(TransportMode.car) && !allowed.contains(CAR_PASSENGER)) {
                    Set<String> modes = new HashSet<>(allowed);
                    modes.add(CAR_PASSENGER);
                    link.setAllowedModes(modes);
                }
            }

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

            // PrepareForSim needs a type for every network routing mode. The passenger rides in
            // a driver's car that is already simulated, so the type occupies no road capacity.
            Id<VehicleType> carPassengerTypeId = Id.create(CAR_PASSENGER, VehicleType.class);
            if (!matsimScenario.getVehicles().getVehicleTypes().containsKey(carPassengerTypeId)) {
                VehicleType carPassenger = fac.createVehicleType(carPassengerTypeId);
                carPassenger.setNetworkMode(CAR_PASSENGER);
                carPassenger.setPcuEquivalents(0.);
                matsimScenario.getVehicles().addVehicleType(carPassenger);
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

                    // Passengers ride in a driver's car: route on the car network with the congested
                    // (and, in stage 2, flooded) car travel times and the car disutility.
                    // networkTravelTime() is the key of the car travel time (named "car").
                    addTravelTimeBinding(CAR_PASSENGER).to(networkTravelTime());
                    addTravelDisutilityFactoryBinding(CAR_PASSENGER).to(carTravelDisutilityFactoryKey());

                    // Default scoring plus a correction that scores the walk legs of pt trips at
                    // the pt time rate, as in the estimation.
                    bindScoringFunctionFactory().to(PtTripScoringFunctionFactory.class);
                }
            });

            // Flood response for the active modes. Cars, trucks and road-running buses get the
            // flood natively, through the freespeed NetworkChangeEvents set up in
            // fillAllModesConfig. Walk and bike cannot: they are teleported and their speed
            // comes from mito's config-group calculators, which never consult the network. This
            // module rebinds their TravelTime and TravelDisutility to read the same hazard
            // field, so both sides respond to one flood. Stage 1 stays undisrupted.
            if (stage == STAGE_DISRUPTED) {
                WaterDepthData depthData = WaterDepthData.read(
                        properties.main.baseDirectory + properties.healthData.floodDepthTimeSeries_file,
                        matsimScenario.getNetwork());
                controler.addOverridingModule(new FloodActiveModesModule(depthData, FLOOD_HAZARD_WEIGHT));
            }

            controler.run();
            logger.warn("MATSim stage " + stage + " for " + day + " " + year + " finished.");

            return controler;
    }

    /**
     * The single output directory shared by both all-modes stages — unchanged from the
     * one-stage version, so ITERS/it.0 ... ITERS/it.200 all live side by side.
     */
    private String allModesOutputDirectory(int year, Day day) {
        final String outputDirectoryRoot = properties.main.baseDirectory + "scenOutput/" + properties.main.scenarioName;
        return outputDirectoryRoot + "/matsim/" + year + "/" + day + "/allModes/";
    }

    /**
     * Plans that stage 2 warm-starts from: stage 1's own dump of its final iteration, written
     * by MATSim as {@code ITERS/it.<n>/<runId>.<n>.plans.xml.gz}. Deliberately not a copy under
     * an invented name — stage 2 begins one iteration later and so
     * can never overwrite this file, which makes its presence an unambiguous marker that a base
     * run finished here. The top-level {@code <runId>.output_plans.xml.gz} could not serve
     * either purpose: both stages write it, so after a disrupted run it holds the last
     * disrupted iteration, indistinguishable from a base one.
     */
    private String basePlansFile(String outputDirectory, int year) {
        return outputDirectory + "ITERS/it." + lastIterationBase + "/"
                + year + "." + lastIterationBase + ".plans.xml.gz";
    }

    /**
     * Copies the stage-1 statistics to {@code <runId>.base_<name>} so stage 2, which writes its
     * own into the same directory, cannot clobber them. Runs whether stage 1 was just executed
     * or reused, and never overwrites an existing copy — after a stage-2 run the originals hold
     * iterations the disrupted iterations, and
     * re-copying them would destroy the preserved base history.
     * <p>
     * The per-iteration ITERS/ folders need no protection: stage 2 starts after the
     * last base iteration and so never writes into a stage-1 iteration folder.
     */
    private void preserveBaseOutputs(String outputDirectory, int year) {
        for (String name : BASE_OUTPUTS_TO_PRESERVE) {
            Path source = Path.of(outputDirectory + year + "." + name);
            if (!Files.exists(source)) {
                logger.warn("Base output " + source + " not found — not preserved.");
                continue;
            }
            Path target = Path.of(outputDirectory + year + ".base_" + name);
            if (Files.exists(target)) {
                logger.info("Base output " + target.getFileName() + " already preserved — keeping it.");
                continue;
            }
            try {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                logger.info("Preserved base output " + source.getFileName() + " as " + target.getFileName() + ".");
            } catch (IOException e) {
                throw new RuntimeException("Could not preserve base output " + source + " as " + target, e);
            }
        }
    }

    private void fillAllModesConfig(Config config, int year, Day day, int stage) {
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

        // Set output directory. Both stages share it, so only stage 1 may clear it; stage 2
        // writes its iterations into the same ITERS/ alongside the base ones.
        config.controller().setRunId(String.valueOf(year));
        config.controller().setOutputDirectory(allModesOutputDirectory(year, day));

        // Iterations: the motorized side needs ~100 iterations to equilibrate congestion and
        // mode shares; bike/walk converge immediately and ride along at negligible cost.
        // Innovation is switched off for the last 20% of EACH stage (MATSim computes the
        // cut-off as firstIteration + fraction * (lastIteration - firstIteration)), so stage 1
        // settles by iteration 80 and stage 2 by iteration 180 — ~80 iterations to adapt to the
        // disruption, then 20 of stable selection.
        if (stage == STAGE_BASE) {
            config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
            config.controller().setFirstIteration(FIRST_ITERATION_BASE);
            config.controller().setLastIteration(lastIterationBase);
        } else {
            config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles);
            config.controller().setFirstIteration(firstIterationDisrupted);
            config.controller().setLastIteration(lastIterationDisrupted);

            // Network change events. The time-variant network must be requested BEFORE
            // ScenarioUtils.loadScenario, otherwise plain LinkImpls are built and the change
            // events have no effect at all.
            String changeEventsFile = properties.main.baseDirectory + properties.healthData.networkChangeEvents_file;
            if (!new File(changeEventsFile).exists()) {
                throw new RuntimeException("Network change events file not found: " + changeEventsFile
                        + " (set 'network.change.events.file' in the properties file).");
            }
            config.network().setTimeVariantNetwork(true);
            config.network().setChangeEventsInputFile(changeEventsFile);
            logger.warn("Stage " + stage + ": network change events ACTIVE from " + changeEventsFile);
        }
        config.replanning().setFractionOfIterationsToDisableInnovation(0.8);
        config.controller().setWritePlansInterval(Math.max(config.controller().getLastIteration(), 1));
        config.controller().setWriteEventsInterval(Math.max(config.controller().getLastIteration(), 1));

        // Modes: only car/truck are QSim main modes (pt vehicles are driven by the transit
        // engine). bike and car_passenger are network routing modes WITHOUT being main modes —
        // the QSim teleports them along their routed path with the routed travel time (for
        // car_passenger that is the congested car travel time). walk must STAY a
        // teleported mode in the config: SwissRailRaptor's TransitRouterConfig hard-requires
        // teleported walk params (beeline speed for pt access/egress/transfer walks) and
        // MATSim forbids a mode being both network and teleported. Direct walk legs are
        // nevertheless network-routed via the routing-module override in
        // runAllModesSimulation. To move bike/walk into the QSim later (e.g. for link-level
        // exposure events), make them main modes and switch to per-person vehicles + SeepageQ.
        config.qsim().setMainModes(List.of(TransportMode.car, TransportMode.truck));
        config.routing().setNetworkModes(List.of(TransportMode.car, TransportMode.truck, TransportMode.bike, CAR_PASSENGER));
        config.routing().removeModeRoutingParams(TransportMode.bike);
        // A mode cannot be both network and teleported.
        config.routing().removeModeRoutingParams(CAR_PASSENGER);
        config.routing().setAccessEgressType(RoutingConfigGroup.AccessEgressType.none);

        // --- Scoring ---
        // All modes are scored in ONE utility scale, the estimated MNL5_purposeASC model (see
        // the constants at the top of the class). One parameter set per person purpose group,
        // differing only in the mode constants, which are the calibration knobs
        // (mode_constants.csv). The default set serves freight (fixed mode, so its constants
        // are irrelevant) and SwissRailRaptor's routing, which reads the default set and
        // therefore needs the same time and waiting rates.
        Map<String, Map<String, Double>> constantsByGroup = getModeConstants();
        Map<String, Double> weatherShift = WeatherModeShifts.forDay(day);
        // Activity types defined in the base config file live in the unnamed set, which is
        // about to be replaced; remember them so that every set keeps them (the freight plans
        // may use activity types beyond the MITO ones).
        List<ScoringConfigGroup.ActivityParams> baseActivities = new ArrayList<>(config.scoring().getActivityParams());

        // ORDER MATTERS. In MATSim 2025.0, adding a set for a subpopulation that has none yet
        // REMOVES the unnamed (null-key) set: the "does one exist already?" lookup falls back to
        // the unnamed set and then replaces it. So the named sets are created first and the
        // unnamed one last (adding it removes nothing). All three kinds are needed:
        //   "default"  required by the consistency check as soon as there are several sets;
        //   "freight"  so trucks / through traffic never depend on the fallback;
        //   unnamed    what config.scoring() itself delegates to (raptor) and the fallback.
        List<String> namedSets = new ArrayList<>(List.of(ScoringConfigGroup.DEFAULT_SUBPOPULATION, SUBPOP_FREIGHT));
        namedSets.addAll(PERSON_SUBPOPS);
        namedSets.add(null);    // the unnamed set, last
        for (String subpopulation : namedSets) {
            ScoringConfigGroup.ScoringParameterSet set = config.scoring().getOrCreateScoringParameters(subpopulation);
            String group = subpopulation != null && subpopulation.startsWith(SUBPOP_PERSON + "_")
                    ? subpopulation.substring(SUBPOP_PERSON.length() + 1) : DEFAULT_PURPOSE_GROUP;
            fillScoringSet(set, constantsByGroup.get(group), weatherShift);
            for (ScoringConfigGroup.ActivityParams base : baseActivities) {
                if (set.getActivityParams(base.getActivityType()) == null) {
                    ScoringConfigGroup.ActivityParams copy = new ScoringConfigGroup.ActivityParams(base.getActivityType());
                    base.getParams().forEach(copy::addParam);
                    set.addActivityParams(copy);
                }
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
        // Persons perform mode choice (SubtourModeChoice) so car/pt/walk/bike compete, plus
        // ReRoute (route shifts) and TimeAllocationMutator (departure-time adjustments);
        // ChangeExpBeta is the plan selector that lets the equilibrium settle. Freight keeps its
        // fixed mode (no SubtourModeChoice) and may only re-route / re-time.
        config.replanning().setMaxAgentPlanMemorySize(5);
        config.replanning().clearStrategySettings();

        // Departure-time mutation range (±) for TimeAllocationMutator, in seconds.
        config.timeAllocationMutator().setMutationRange(1800.);
        config.timeAllocationMutator().setAffectingDuration(false);

        // Mode choice (persons only): SubtourModeChoice swaps the mode of whole home-based
        // subtours among the five competing modes, the same choice set as MITO. car and bike are
        // chain-based (must return to where they were left) so an agent cannot abandon a vehicle
        // mid-chain; car_passenger is not. Car availability is restricted per agent via the
        // "carAvail" person attribute set in MitoMatsimScenarioAssemblerMCR (licence and
        // household car ownership). It only restricts "car": car_passenger stays open to
        // everyone, so people without car access can get a lift.
        config.subtourModeChoice().setModes(new String[]{
                TransportMode.car, CAR_PASSENGER, TransportMode.pt, TransportMode.walk, TransportMode.bike});
        config.subtourModeChoice().setChainBasedModes(new String[]{
                TransportMode.car, TransportMode.bike});
        config.subtourModeChoice().setConsiderCarAvailability(true);

        // Persons: mode choice + route + time, identical for every purpose group. The
        // SubtourModeChoice weight is the proportion of persons attempting a mode switch each
        // iteration (0.15 is the conventional starting value); lower it once mode shares are
        // calibrated. Note: non-home-based trips are open plans, which SubtourModeChoice cannot
        // change, so they keep their MITO mode and the NHB constants matter only for completeness.
        Map<String, Double> personStrategies = new LinkedHashMap<>();
        personStrategies.put("ChangeExpBeta", 0.7);
        personStrategies.put("SubtourModeChoice", 0.15);
        personStrategies.put("ReRoute", 0.15);
        personStrategies.put("TimeAllocationMutator", 0.15);
        for (String subpopulation : PERSON_SUBPOPS) {
            for (Map.Entry<String, Double> strategy : personStrategies.entrySet()) {
                ReplanningConfigGroup.StrategySettings strategySettings = new ReplanningConfigGroup.StrategySettings();
                strategySettings.setStrategyName(strategy.getKey());
                strategySettings.setWeight(strategy.getValue());
                strategySettings.setSubpopulation(subpopulation);
                config.replanning().addStrategySettings(strategySettings);
            }
        }

        // Freight: fixed mode, route + time only
        {
            ReplanningConfigGroup.StrategySettings strategySettings = new ReplanningConfigGroup.StrategySettings();
            strategySettings.setStrategyName("ChangeExpBeta");
            strategySettings.setWeight(0.7);
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
        {
            ReplanningConfigGroup.StrategySettings strategySettings = new ReplanningConfigGroup.StrategySettings();
            strategySettings.setStrategyName("TimeAllocationMutator");
            strategySettings.setWeight(0.15);
            strategySettings.setSubpopulation(SUBPOP_FREIGHT);
            config.replanning().addStrategySettings(strategySettings);
        }
    }

    /**
     * Fills one scoring parameter set with the estimated mode-choice model: common time, cost
     * and waiting rates, the given mode constants, and the weather shift of the day.
     *
     * @param constants    constant by mode (car_passenger, pt, walk, bike) for one purpose group;
     *                     car is the reference and fixed at 0
     * @param weatherShift utility shift by mode for the weather of the day (may be empty)
     */
    private static void fillScoringSet(ScoringConfigGroup.ScoringParameterSet set, Map<String, Double> constants,
                                       Map<String, Double> weatherShift) {
        set.setPerforming_utils_hr(PERFORMING);
        set.setMarginalUtilityOfMoney(MARG_UTIL_MONEY);
        set.setMarginalUtlOfWaitingPt_utils_hr(WAITING_PT);
        set.setLateArrival_utils_hr(LATE_ARRIVAL);
        // Each transfer is another boarding and pays the fare again; the first boarding's fare is
        // in the pt constant below.
        set.setUtilityOfLineSwitch(PT_FARE_PER_BOARDING);

        ModeParams carParams = set.getOrCreateModeParams(TransportMode.car);
        carParams.setConstant(0.);
        carParams.setMarginalUtilityOfTraveling(TRAV_CAR);
        carParams.setMarginalUtilityOfDistance(0.);
        carParams.setMonetaryDistanceRate(MONETARY_RATE_CAR);

        // Car passenger: its own, stronger time effect than the driver's (as estimated) and no
        // monetary cost (the driver pays).
        ModeParams carPassengerParams = set.getOrCreateModeParams(CAR_PASSENGER);
        carPassengerParams.setConstant(constants.get(CAR_PASSENGER));
        carPassengerParams.setMarginalUtilityOfTraveling(TRAV_CAR_PASSENGER);
        carPassengerParams.setMarginalUtilityOfDistance(0.);
        carPassengerParams.setMonetaryDistanceRate(0.);

        // The fare of the first boarding is a cost, not calibration, so it is added here and
        // kept out of the CSV: calibration edits only the CSV value.
        ModeParams ptParams = set.getOrCreateModeParams(TransportMode.pt);
        ptParams.setConstant(constants.get(TransportMode.pt) + PT_FARE_PER_BOARDING);
        ptParams.setMarginalUtilityOfTraveling(TRAV_PT);
        ptParams.setMarginalUtilityOfDistance(0.);
        ptParams.setMonetaryDistanceRate(MONETARY_RATE_PT);

        ModeParams walkParams = set.getOrCreateModeParams(TransportMode.walk);
        walkParams.setConstant(constants.get(TransportMode.walk));
        walkParams.setMarginalUtilityOfTraveling(TRAV_WALK);
        walkParams.setMarginalUtilityOfDistance(0.);
        walkParams.setMonetaryDistanceRate(0.);

        ModeParams bicycleParams = set.getOrCreateModeParams(TransportMode.bike);
        bicycleParams.setConstant(constants.get(TransportMode.bike));
        bicycleParams.setMarginalUtilityOfTraveling(TRAV_BIKE);
        bicycleParams.setMarginalUtilityOfDistance(0.);
        bicycleParams.setMonetaryDistanceRate(0.);

        // Raptor's transfer walks at the same stop area emit "non_network_walk"; it mirrors walk.
        // Inside a pt trip both are re-scored at the pt rate by PtTripWalkScoring.
        ModeParams nonNetworkWalk = set.getOrCreateModeParams(NON_NETWORK_WALK);
        nonNetworkWalk.setConstant(walkParams.getConstant());
        nonNetworkWalk.setMarginalUtilityOfTraveling(walkParams.getMarginalUtilityOfTraveling());
        nonNetworkWalk.setMarginalUtilityOfDistance(walkParams.getMarginalUtilityOfDistance());
        nonNetworkWalk.setMonetaryDistanceRate(walkParams.getMonetaryDistanceRate());

        // Weather: shift of each mode's utility relative to car for the weather of this day, in
        // both stages (the weather belongs to the day, the flood only to stage 2). It is a
        // DAILY constant, charged once per agent that uses the mode — an agent is one trip
        // (out and back), so a per-leg constant would penalise a round trip twice. A pt trip
        // also contains walk access/egress legs and therefore picks up the walk shift; the pt
        // shift is reduced by it so that a pt user ends up with exactly the pt value.
        double walkShift = weatherShift.getOrDefault(TransportMode.walk, 0.);
        walkParams.setDailyUtilityConstant(walkShift);
        bicycleParams.setDailyUtilityConstant(weatherShift.getOrDefault(TransportMode.bike, 0.));
        ptParams.setDailyUtilityConstant(weatherShift.getOrDefault(TransportMode.pt, 0.) - walkShift);

        ModeParams truckParams = set.getOrCreateModeParams(TransportMode.truck);
        truckParams.setConstant(carParams.getConstant());
        truckParams.setDailyMonetaryConstant(carParams.getDailyMonetaryConstant());
        truckParams.setMarginalUtilityOfDistance(carParams.getMarginalUtilityOfDistance());
        truckParams.setDailyUtilityConstant(carParams.getDailyUtilityConstant());
        truckParams.setMonetaryDistanceRate(carParams.getMonetaryDistanceRate());

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
            if (set.getActivityParams(e.getKey()) == null) {
                set.addActivityParams(
                        new ScoringConfigGroup.ActivityParams(e.getKey()).setTypicalDuration(e.getValue()));
            }
        }
        // Stage activities ("pt interaction", ...) are not scored. A per-subpopulation set may
        // not inherit them from the default set, so each set gets its own.
        for (String mode : List.of(TransportMode.car, CAR_PASSENGER, TransportMode.pt, TransportMode.walk,
                TransportMode.bike, TransportMode.truck, NON_NETWORK_WALK)) {
            String stageActivity = ScoringConfigGroup.createStageActivityType(mode);
            if (set.getActivityParams(stageActivity) == null) {
                set.addActivityParams(
                        new ScoringConfigGroup.ActivityParams(stageActivity).setScoringThisActivityAtAll(false));
            }
        }
    }

    /**
     * Mode constants by purpose group and mode, from the CSV named by
     * 'matsim.mode.constants.file' (header group,mode,constant; car is 0 and not listed).
     * Read once; fails if any group/mode combination is missing.
     */
    private Map<String, Map<String, Double>> getModeConstants() {
        if (modeConstants != null) {
            return modeConstants;
        }
        Path path = Path.of(properties.main.baseDirectory + properties.healthData.modeConstantsFile);
        Map<String, Map<String, Double>> constants = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            List<String> header = Arrays.asList(reader.readLine().trim().split(","));
            int groupIndex = header.indexOf("group");
            int modeIndex = header.indexOf("mode");
            int constantIndex = header.indexOf("constant");
            if (groupIndex < 0 || modeIndex < 0 || constantIndex < 0) {
                throw new RuntimeException("Expected header group,mode,constant in " + path);
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                String[] record = line.trim().split(",");
                constants.computeIfAbsent(record[groupIndex].trim(), g -> new HashMap<>())
                        .put(record[modeIndex].trim(), Double.parseDouble(record[constantIndex].trim()));
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not read mode constants from " + path
                    + " (set 'matsim.mode.constants.file' in the properties file).", e);
        }
        for (String group : PURPOSE_GROUPS) {
            for (String mode : List.of(CAR_PASSENGER, TransportMode.pt, TransportMode.walk, TransportMode.bike)) {
                if (!constants.containsKey(group) || !constants.get(group).containsKey(mode)) {
                    throw new RuntimeException("Mode constant missing for group " + group + ", mode " + mode
                            + " in " + path);
                }
            }
        }
        logger.warn("Mode constants by purpose group from " + path + ": " + constants);
        modeConstants = constants;
        return modeConstants;
    }

    /**
     * Purpose group of a MITO agent, from its "purpose" attribute (a MITO Purpose in memory, a
     * string after a warm start). Unknown purposes are logged once and use the HBD constants.
     */
    private String purposeGroup(Person person) {
        Object purpose = person.getAttributes().getAttribute("purpose");
        String name = purpose == null ? "null" : purpose.toString();
        switch (name) {
            case "HBW":
                return "HBW";
            case "HBE":
                return "HBE";
            case "HBS":
            case "HBR":
            case "HBO":
                return "HBD";
            case "HBA":
                return "HBA";
            case "NHBW":
            case "NHBO":
                return "NHB";
            default:
                if (unmappedPurposes.add(name)) {
                    logger.warn("No purpose group for purpose '" + name + "'; using " + DEFAULT_PURPOSE_GROUP + ".");
                }
                return DEFAULT_PURPOSE_GROUP;
        }
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
