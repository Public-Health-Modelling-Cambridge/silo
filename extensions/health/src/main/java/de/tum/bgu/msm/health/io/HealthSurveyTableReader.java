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
                            "CSV is missing one or more required columns: age_group, gender, imd, total_pa"
                    );
                }

                String line;
                while ((line = br.readLine()) != null) {
                    if (line.isBlank()) continue;

                    String[] cols = line.split(",");

                    String ageGroup = cols[ageGroupIdx].trim();
                    String gender   = cols[genderIdx].trim();
                    String imd      = cols[imdIdx].trim();
                    double totalPa  = Double.parseDouble(cols[totalPaIdx].trim());

                    String key = ageGroup + "|" + gender.toUpperCase() + "|" + imd;
                    System.out.println("reading key: " + key + "with totalPA " + totalPa);
                    paDistributionByStrata
                            .computeIfAbsent(key, k -> new ArrayList<>())
                            .add(totalPa);
                }



            /*

            BufferedReader in = new BufferedReader(new FileReader(path));
            recString = in.readLine();

            // read header
            String[] header = recString.split(",");

            //id gender age_group time_totalpa imd total_PA
            int posID = SiloUtil.findPositionInArray("id", header);
            int posGender = SiloUtil.findPositionInArray("gender", header);
            int posAgeGroup = SiloUtil.findPositionInArray("age_group", header);
            int posTotalPATime = SiloUtil.findPositionInArray("time_totalpa", header);
            int posIMD = SiloUtil.findPositionInArray("imd", header);
            int posTotalPA = SiloUtil.findPositionInArray("total_PA", header);

            // read line
            while ((recString = in.readLine()) != null) {
                recCount++;
                String[] lineElements = recString.split(",");
                int id = Integer.parseInt(lineElements[posID]);
                String gender = lineElements[posGender];
                int imd = Integer.parseInt(lineElements[posIMD]);
                String ageGroup = lineElements[posAgeGroup];
                double totalPA = Double.parseDouble(lineElements[posTotalPA]);

                //String compositeKey = dataContainer.createHealthSurveyIndex(ageGroup, gender,imd);

                //healthDiseaseData.computeIfAbsent(id, k -> new HashMap<>()).put(compositeKey, totalPA);

                String compositeKey = ageGroup + "|" + gender + "|" + imd;
                healthSurveyData
                        .computeIfAbsent(key, new Function<Diseases, Map<String, Double>>() {
                            @Override
                            public Map<String, Double> apply(Diseases compositeKey) {
                                Map<String, Double> stringDoubleMap = new Map<>();
                                return
                                        stringDoubleMap;
                            }
                        })
                        .add(totalPA);

            }

        */


        } catch (IOException e) {
            logger.fatal("IO Exception caught reading health survey england data file: " + path);
            logger.fatal("recCount = " + recCount + ", recString = <" + recString + ">");
        } catch (IllegalArgumentException e){
            logger.warn(e.getMessage());
        }
        logger.info("Finished reading health survey england from csv file.");

        return paDistributionByStrata;

        /*
           // Create a global distribution fallback
            return paDistributionByStrata.values().stream()
                    .flatMap(List::stream)
                    .toList();

         */
    }
}
