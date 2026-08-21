package de.tum.bgu.msm.health;

import java.util.HashMap;
import java.util.Map;

public class CalibrationFactors {
    private static final Map<String, Map<String, Double>> calibrationFactors = new HashMap<>();

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

    // Method to get calibration factor by scenario and mode
    public double getCalibrationFactor(String scenario, String mode) {
        return calibrationFactors.getOrDefault(scenario, new HashMap<>()).getOrDefault(mode, 0.0);
    }
}
