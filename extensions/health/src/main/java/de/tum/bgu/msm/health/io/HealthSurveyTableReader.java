package de.tum.bgu.msm.health.io;

import de.tum.bgu.msm.health.data.DataContainerHealth;
import de.tum.bgu.msm.health.disease.Diseases;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

public class HealthSurveyTableReader {

    private final static Logger logger = LogManager.getLogger(HealthSurveyTableReader.class);
    private Diseases key;

    public Map<String, List<Double>> readData(DataContainerHealth dataContainer, String path) {
        logger.info("Reading health survey for England table from csv file");
        logger.info("Reading path " + path);

        Map<String, List<Double>> paDistributionByStrata = new HashMap<>();


        String recString = "";
        int recCount = 0;
           try (BufferedReader br = new BufferedReader(new FileReader(path))) {
                String headerLine = br.readLine(); // skip header row

                if (headerLine == null) {
                    throw new IllegalStateException("CSV file is empty: " + path);
                }

                // Parse header to find column indices dynamically
                String[] headers = headerLine.split(",");
                int ageGroupIdx = -1;
                int genderIdx   = -1;
                int imdIdx      = -1;
                int totalPaIdx  = -1;

                for (int i = 0; i < headers.length; i++) {
                    switch (headers[i].trim().toLowerCase()) {
                        case "age_group"  -> ageGroupIdx = i;
                        case "gender"     -> genderIdx   = i;
                        case "imd"        -> imdIdx       = i;
                        case "total_pa"   -> totalPaIdx   = i;
                    }
                }

                if (ageGroupIdx < 0 || genderIdx < 0 || imdIdx < 0 || totalPaIdx < 0) {
                    throw new IllegalStateException(
                            "CSV is missing one or more required columns: age_group, gender, imd, mmetHr_sport_manual"
                    );
                }

                String line;
                while ((line = br.readLine()) != null) {
                    if (line.isBlank()) continue;

                    recCount++;
                    recString = line;
                    //System.out.println("DEBUG Reading line #" + recCount + ": " + line);

                    String[] cols = line.split(",");

                    String ageGroup = cols[ageGroupIdx].trim();
                    String gender   = cols[genderIdx].trim();
                    String imd      = cols[imdIdx].trim();
                    double totalPa  = Double.parseDouble(cols[totalPaIdx].trim());
                    //System.out.println("DEBUG Parsed key: " + key + " | total_PA: " + totalPa);

                    String key = ageGroup + "|" + gender.toUpperCase() + "|" + imd;
                    //System.out.println("reading key: " + key + "with totalPA " + totalPa);
                    paDistributionByStrata
                            .computeIfAbsent(key, k -> new ArrayList<>())
                            .add(totalPa);
                }

        } catch (IOException e) {
               logger.fatal("IO Exception", e);
               e.printStackTrace();
        } catch (IllegalArgumentException e){
            logger.warn(e.getMessage());
        }
        logger.info("Finished reading health survey england from csv file.");

        return paDistributionByStrata;

    }
}
