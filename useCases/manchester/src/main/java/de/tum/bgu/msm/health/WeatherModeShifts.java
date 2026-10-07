package de.tum.bgu.msm.health;

import de.tum.bgu.msm.data.Day;
import de.tum.bgu.msm.resources.Resources;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Weather-dependent shifts of the mode utilities used in MATSim scoring.
 *
 * Division of labour for weather: MITO decides how many trips are made (it cancels trips with
 * its purpose-only weather coefficients), MATSim decides by which mode. This class supplies the
 * second half: for the weather of the simulated day it returns, per mode, the change in utility
 * relative to car. The values are changes in log-odds against car estimated on the National
 * Travel Survey (extremeWeather project, analysis/mito/weather_tripgen_sensitivities.ipynb,
 * output weatherModeUtilityShifts.csv).
 *
 * The weather itself is read from the MITO properties, the same entries MITO uses, so that one
 * run has one weather:
 * <pre>
 * weather.mode.run          = true
 * weather.mode.shifts       = input/mito/tables/weatherModeUtilityShifts.csv
 * weather.rain.mm.thursday  = 15      # daily rainfall total, mm
 * weather.tasmax.c.thursday = 8       # daily maximum temperature, degC
 * </pre>
 * File columns: hazard (rain | temp), bin, lower, upper, then one column per MATSim mode. A row
 * applies when {@code lower <= value < upper}; the rain row and the temperature row add up.
 */
final class WeatherModeShifts {

    private static final Logger logger = LogManager.getLogger(WeatherModeShifts.class);

    static final String RUN = "weather.mode.run";
    static final String SHIFTS = "weather.mode.shifts";
    private static final String RAIN_MM = "weather.rain.mm";
    private static final String TASMAX_C = "weather.tasmax.c";

    private static final List<String> FIXED_COLUMNS = Arrays.asList("hazard", "bin", "lower", "upper");

    private WeatherModeShifts() {
    }

    /**
     * Utility shift by MATSim mode for the weather of the given day. Empty if the feature is
     * switched off, MITO's properties are not loaded, or no weather is given for the day.
     */
    static Map<String, Double> forDay(Day day) {
        Map<String, Double> shiftByMode = new LinkedHashMap<>();
        if (Resources.instance == null || !Resources.instance.getBoolean(RUN, false)) {
            return shiftByMode;
        }
        Double rain = getWeatherValue(RAIN_MM, day);
        Double tasmax = getWeatherValue(TASMAX_C, day);
        if (rain == null && tasmax == null) {
            return shiftByMode;
        }

        Path path = Resources.instance.getRelativePath(SHIFTS);
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.ISO_8859_1)) {
            List<String> header = Arrays.asList(reader.readLine().split(","));
            for (String column : FIXED_COLUMNS) {
                if (!header.contains(column)) {
                    throw new RuntimeException("Column '" + column + "' is missing in " + path);
                }
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                String[] record = line.split(",");
                String hazard = record[header.indexOf("hazard")];
                Double value;
                if ("rain".equals(hazard)) {
                    value = rain;
                } else if ("temp".equals(hazard)) {
                    value = tasmax;
                } else {
                    throw new RuntimeException("Unknown hazard '" + hazard + "' in " + path);
                }
                double lower = Double.parseDouble(record[header.indexOf("lower")]);
                double upper = Double.parseDouble(record[header.indexOf("upper")]);
                if (value == null || value < lower || value >= upper) {
                    continue;
                }
                for (int i = 0; i < header.size(); i++) {
                    if (!FIXED_COLUMNS.contains(header.get(i))) {
                        shiftByMode.merge(header.get(i), Double.parseDouble(record[i]), Double::sum);
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not read weather mode utility shifts from " + path, e);
        }
        logger.warn("Weather on " + day + " (rain " + rain + " mm, maximum temperature " + tasmax
                + " C): mode utility shifts relative to car " + shiftByMode);
        return shiftByMode;
    }

    private static Double getWeatherValue(String key, Day day) {
        String value = Resources.instance.getString(key + "." + day);
        if (value == null) {
            value = Resources.instance.getString(key);
        }
        return value == null || value.trim().isEmpty() ? null : Double.parseDouble(value.trim());
    }
}
