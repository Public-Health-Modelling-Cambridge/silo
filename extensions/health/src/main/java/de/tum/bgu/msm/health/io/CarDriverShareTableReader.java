package de.tum.bgu.msm.health.io;

import de.tum.bgu.msm.data.person.Gender;
import de.tum.bgu.msm.health.disease.Diseases;
import de.tum.bgu.msm.utils.SiloUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

public class CarDriverShareTableReader {
    private final static Logger logger = LogManager.getLogger(InjuryRRTableReader.class);

    public static class DataEntry {
        public final double shareDriver;

        public DataEntry(double shareDriver) {
            this.shareDriver = shareDriver;
        }
    }

    public Map<String, EnumMap<Gender, CarDriverShareTableReader.DataEntry>> readData(String fileName) {
        logger.info("Reading proportions of car drivers by age groups and gender for the injury model");

        Map<String, EnumMap<Gender, CarDriverShareTableReader.DataEntry>> dataMap = new HashMap<>();

        String recString = "";
        int recCount = 0;
        try {
            BufferedReader in = new BufferedReader(new FileReader(fileName));
            recString = in.readLine();

            // read header
            String[] header = recString.split(",");
            int posGender = SiloUtil.findPositionInArray("gender", header);
            int posAge = SiloUtil.findPositionInArray("age_group", header);
            int posDriverProb = SiloUtil.findPositionInArray("driver_prob", header);

            // read line
            while ((recString = in.readLine()) != null) {
                recCount++;
                String[] lineElements = recString.split(",");
                // String[] lineElements = recString.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
                for (int i = 0; i < lineElements.length; i++) {
                    lineElements[i] = lineElements[i].trim().replaceAll("^\"|\"$", "");
                }

                String genderStr = lineElements[posGender];
                Gender gender = "Male".equals(genderStr) ? Gender.MALE : Gender.FEMALE;
                String age = lineElements[posAge];
                Double driverProb = Double.parseDouble(lineElements[posDriverProb]);

                // Initialize nested maps if missing
                dataMap.putIfAbsent(age, new EnumMap<>(Gender.class));
                dataMap.get(age).put(gender, new CarDriverShareTableReader.DataEntry(driverProb));

            }
        } catch (IOException e) {
            logger.fatal("IO Exception caught reading car driver share data file: " + fileName);
            logger.fatal("recCount = " + recCount + ", recString = <" + recString + ">");
        }
        logger.info("Finished reading " + recCount + " prevalence data.");

        return dataMap;
    }
}
