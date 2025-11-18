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
                            modeFactors.put(mode, 2.301601915);
                            break;
                        case "Car":
                            modeFactors.put(mode, 2.01235657546337);
                            break;
                        case "Walk":
                            modeFactors.put(mode, 0.741037452);
                            break;
                    }
                } else if(scenario.equals("safeStreet")){
                    switch (mode) {
                        case "Bike":
                            modeFactors.put(mode, 1.583259223);
                            break;
                        case "Car":
                            modeFactors.put(mode, 2.056126119);
                            break;
                        case "Walk":
                            modeFactors.put(mode, 0.734002674);
                            break;
                    }
                } else if(scenario.equals("green")){
                    switch (mode) {
                        case "Bike":
                            modeFactors.put(mode, 2.373450201);
                            break;
                        case "Car":
                            modeFactors.put(mode, 2.019559765);
                            break;
                        case "Walk":
                            modeFactors.put(mode, 0.720561993);
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
                            modeFactors.put(mode, 1.0508308);
                            break;
                        case "Car":
                            modeFactors.put(mode, 2.1272710);
                            break;
                        case "Walk":
                            modeFactors.put(mode, 0.8126129);
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
