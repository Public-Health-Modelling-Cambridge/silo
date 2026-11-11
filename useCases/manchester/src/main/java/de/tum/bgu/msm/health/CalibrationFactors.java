package de.tum.bgu.msm.health;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class CalibrationFactors {
    private static final Logger logger = LogManager.getLogger(CalibrationFactors.class);
    private static final double UNCALIBRATED_FACTOR = 1.0;
    private static final Map<String, Map<String, Double>> calibrationFactors = new HashMap<>();
    private static final Set<String> warnedScenarios = new HashSet<>();

    static {
        // Initialize scenarios
        String[] scenarios = {"base", "both", "green", "safeStreet", "goDutch"};
        String[] modes = {"Car", "Bike", "Walk"};

        // Populate the map
        for (String scenario : scenarios) {
            Map<String, Double> modeFactors = new HashMap<>();
            for (String mode : modes) {
                // Set base scenario values
                if (scenario.equals("base")) {
                    switch (mode) {
                        case "Bike":
                            modeFactors.put(mode, 2.4140595);
                            break;
                        case "Car":
                            modeFactors.put(mode, 1.4307229);
                            break;
                        case "Walk":
                            modeFactors.put(mode, 0.7442796);
                            break;
                    }
                } else if(scenario.equals("safeStreet")){
                    switch (mode) {
                        case "Bike":
                            modeFactors.put(mode, 1.5240304);
                            break;
                        case "Car":
                            modeFactors.put(mode, 2.1175477);
                            break;
                        case "Walk":
                            modeFactors.put(mode, 0.7359706);
                            break;
                    }
                } else if(scenario.equals("green")){
                    switch (mode) {
                        case "Bike":
                            modeFactors.put(mode, 2.4899862);
                            break;
                        case "Car":
                            modeFactors.put(mode, 2.0792794);
                            break;
                        case "Walk":
                            modeFactors.put(mode, 0.7222587);
                            break;
                    }
                } else if(scenario.equals("both")){
                    switch (mode) {
                        case "Bike":
                            modeFactors.put(mode, 1.605557743);
                            break;
                        case "Car":
                            modeFactors.put(mode, 2.060215446);
                            break;
                        case "Walk":
                            modeFactors.put(mode, 0.714050054);
                            break;
                    }
                } else if(scenario.equals("goDutch")){
                    switch (mode) {
                        case "Bike":
                            modeFactors.put(mode, 0.9795538);
                            break;
                        case "Car":
                            modeFactors.put(mode, 2.1643528);
                            break;
                        case "Walk":
                            modeFactors.put(mode, 0.8166055);
                            break;
                    }
                } else {
                    // Set other scenarios to 0
                    modeFactors.put(mode, 1.0);
                }
            }
            calibrationFactors.put(scenario, modeFactors);
        }
    }

    /**
     * Returns the injury calibration factor for a scenario and mode. A scenario without estimated factors
     * gets {@value #UNCALIBRATED_FACTOR} for every mode, with a warning logged once per scenario.
     *
     * @throws IllegalArgumentException if a known scenario has no factor for the mode
     */
    public double getCalibrationFactor(String scenario, String mode) {
        Map<String, Double> modeFactors = calibrationFactors.get(scenario);
        if (modeFactors == null) {
            warnUncalibrated(scenario);
            return UNCALIBRATED_FACTOR;
        }
        Double factor = modeFactors.get(mode);
        if (factor == null) {
            throw new IllegalArgumentException("No injury calibration factor for mode '" + mode
                    + "' in scenario '" + scenario + "'. Known modes: " + modeFactors.keySet());
        }
        return factor;
    }

    private static synchronized void warnUncalibrated(String scenario) {
        if (warnedScenarios.add(scenario)) {
            String line = "*".repeat(100);
            logger.warn(line);
            logger.warn("*** NEW SCENARIO '" + scenario + "': NO INJURY CALIBRATION FACTORS ESTIMATED");
            logger.warn("*** Known scenarios: " + calibrationFactors.keySet());
            logger.warn("*** Using calibration factor " + UNCALIBRATED_FACTOR + " for all modes (Car, Bike, Walk).");
            logger.warn("*** Injury and fatality numbers are UNCALIBRATED. Estimate calibration factors for this");
            logger.warn("*** scenario and add them to CalibrationFactors before using the results.");
            logger.warn(line);
        }
    }
}
