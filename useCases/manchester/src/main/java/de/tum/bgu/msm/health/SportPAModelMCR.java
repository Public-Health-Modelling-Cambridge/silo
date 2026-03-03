package de.tum.bgu.msm.health;

import de.tum.bgu.msm.container.DataContainer;
import de.tum.bgu.msm.data.Mode;
import de.tum.bgu.msm.data.ZoneMCR;
import de.tum.bgu.msm.data.person.Gender;
import de.tum.bgu.msm.data.person.Person;
import de.tum.bgu.msm.health.io.SportPAmodelCoefficientReader;
import de.tum.bgu.msm.models.AbstractModel;
import de.tum.bgu.msm.models.ModelUpdateListener;
import de.tum.bgu.msm.properties.Properties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import java.util.*;

public class SportPAModelMCR extends AbstractModel implements ModelUpdateListener {
    private static final Logger logger = LogManager.getLogger(SportPAModelMCR.class);
    private Map<String,Map<String,Double>> coef = new HashMap<>();

    public SportPAModelMCR(DataContainer dataContainer, Properties properties, Random random) {
        super(dataContainer, properties, random);
        this.coef = new SportPAmodelCoefficientReader().readData(properties.healthData.sportPAmodel);
    }

    @Override
    public void setup() {
    }

    @Override
    public void prepareYear(int year) {

    }

    @Override
    public void endYear(int year) {
        logger.warn("Sport Physical Activity end year:" + year);
        if (year == properties.main.startYear || properties.healthData.exposureModelYears.contains(year)) {
            updateSportPA();
        }
    }

    @Override
    public void endSimulation() {
    }

    public void updateSportPA() {
        Map<String, List<Double>> paDistributionByStrata = new HashMap<>();
        /*
        // Pre-group df_B-style source data: here assumed to be derived from dataContainer
        Map<String, List<Double>> paDistributionByStrata = new HashMap<>();

        // Build a lookup of total_PA by (age_group | gender | imd) from the reference population (df_B)
        for (Person person : dataContainer.getHouseholdDataManager().getPersons()) {
            PersonHealthMCR ph = (PersonHealthMCR) person;
            int zoneId = dataContainer.getRealEstateDataManager()
                    .getDwelling(person.getHousehold().getDwellingId())
                    .getZoneId();
            ZoneMCR zone = (ZoneMCR) dataContainer.getGeoData().getZones().get(zoneId);

            String key = buildKey(person.getAge(), person.getGender(), zone.getImd10());
            paDistributionByStrata
                    .computeIfAbsent(key, k -> new ArrayList<>())
                    .add((double) (ph.getWeeklyMarginalMetHours(Mode.walk) + ph.getWeeklyMarginalMetHours(Mode.bicycle))
            );
        }*/

/*
        // Pre-group df_B-style source data: read directly from CSV file
        Map<String, List<Double>> paDistributionByStrata = new HashMap<>();

        String csvFilePath = "/media/ali/Expansion/backup_tabea/Ali/manchester/input/health/HSE/processed_hse.csv";

        try (BufferedReader br = new BufferedReader(new FileReader(csvFilePath))) {
            String headerLine = br.readLine(); // skip header row

            if (headerLine == null) {
                throw new IllegalStateException("CSV file is empty: " + csvFilePath);
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

                String key = ageGroup + "|" + gender + "|" + imd;
                paDistributionByStrata
                        .computeIfAbsent(key, k -> new ArrayList<>())
                        .add(totalPa);
            }

        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read reference population CSV: " + csvFilePath, e);
        }

        // Create a global distribution fallback
        List<Double> globalPADistribution = paDistributionByStrata.values().stream()
                .flatMap(List::stream)
                .toList();
*/


        Map<String, List<Double>> globalPADistribution = dataContainer.getHealthSurveyDataManager();
        Random random = new Random();

        List<Double> globalValues = new ArrayList<>();
        for (List<Double> list : globalPADistribution.values()) {
            globalValues.addAll(list);
        }

        // Now assign sport PA for each person in df_A (simulation persons)
        for (Person person : dataContainer.getHouseholdDataManager().getPersons()) {
            PersonHealthMCR personHealth = (PersonHealthMCR) person;
            int zoneId = dataContainer.getRealEstateDataManager()
                    .getDwelling(person.getHousehold().getDwellingId())
                    .getZoneId();
            ZoneMCR zone = (ZoneMCR) dataContainer.getGeoData().getZones().get(zoneId);

            String key = buildKey(person.getAge(), person.getGender(), zone.getImd10());
            //String key = ageGroup + "|" + gender + "|" + imd;

            List<Double> matches = globalPADistribution.get(key);

            double totalPA;
            if (matches == null || matches.isEmpty()) {
                System.err.println("Warning: no match for stratum " + key + " – using global distribution.");
                totalPA = sample(globalValues, random);
            } else {
                totalPA = sample(matches, random);
            }

            double travelPA = personHealth.getWeeklyMarginalMetHours(Mode.walk)
                    + personHealth.getWeeklyMarginalMetHours(Mode.bicycle);
            double sportPA = Math.max(totalPA - travelPA, 0.0);
            System.out.println(sportPA + " for key " + key);
            personHealth.setWeeklyMarginalMetHoursSport((float) sportPA);
        }

        System.out.println(" --------------- ");
        System.out.println("We are done");

    }

    private static String buildKey(int age, Gender gender, double imd) {
        String ageGroup;
        if (age < 16) ageGroup = "under16";
        else if (age < 25) ageGroup = "16-24";
        else if (age < 35) ageGroup = "25-34";
        else if (age < 45) ageGroup = "35-44";
        else if (age < 55) ageGroup = "45-54";
        else if (age < 65) ageGroup = "55-64";
        else if (age < 75) ageGroup = "65-74";
        else ageGroup = "75+";

        int imdBand = (int) Math.ceil(imd / 2.0);
        return ageGroup + "|" + gender + "|" + imdBand;
    }

    private static double sample(List<Double> values, Random random) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("Cannot sample from null or empty list.");
        }
        int idx = random.nextInt(values.size());  // 0 .. size-1
        return values.get(idx);
    }


    /*
    private static double sample(@MonotonicNonNull Map<String, List<Double>> values, Random random) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("Map of values cannot be null or empty.");
        }

        // Pick a random list
        var lists = new ArrayList<>(values.values());
        var chosenList = lists.get(random.nextInt(lists.size()));

        if (chosenList == null || chosenList.isEmpty()) {
            throw new IllegalArgumentException("Chosen list is null or empty.");
        }

        // Pick a random double from that list
        return chosenList.get(random.nextInt(chosenList.size()));
    }

     */


}
