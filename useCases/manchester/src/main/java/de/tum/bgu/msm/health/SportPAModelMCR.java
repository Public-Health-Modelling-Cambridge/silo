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
import java.util.stream.DoubleStream;
import java.util.stream.Stream;

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
        if ((properties.healthData.exposureModelYears.contains(year))) {
            updateSportPA();
        }
    }

    @Override
    public void endSimulation() {
    }

    public void updateSportPA() {
        Map<String, List<Double>> globalPADistribution = ((HealthDataContainerImpl) dataContainer).getHealthSurveyData();
        Random random = new Random();

        List<Double> globalValues = new ArrayList<>();
        for (List<Double> list : globalPADistribution.values()) {
            globalValues.addAll(list);
        }

        Map<Integer, List<Float>> totalPAList = new HashMap<>();

        // Now assign sport PA for each person in df_A (simulation persons)
        for (Person person : dataContainer.getHouseholdDataManager().getPersons()) {
            PersonHealthMCR personHealth = (PersonHealthMCR) person;
            int zoneId = dataContainer.getRealEstateDataManager()
                    .getDwelling(person.getHousehold().getDwellingId())
                    .getZoneId();
            ZoneMCR zone = (ZoneMCR) dataContainer.getGeoData().getZones().get(zoneId);

            String key = buildKey(person.getAge(), person.getGender(), zone.getImd10());

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
            //System.out.println(sportPA + " for key " + key);
            personHealth.setWeeklyMarginalMetHoursSport((float) sportPA);

            /*if (key.equals("16-24|FEMALE|1")){
                totalPAList.put(personHealth.getId(), Arrays.asList((float) personHealth.getWeeklyMarginalMetHours(Mode.walk),
                        (float) personHealth.getWeeklyMarginalMetHours(Mode.bicycle),
                        (float) personHealth.getWeeklyMarginalMetHoursSport()));
                //sportsPAList.put(personHealth.getId(), sportPA);
                System.out.println("---");
            }*/
        }

        /*List<Float> sortedStream = totalPAList.values().stream()
                .filter(list -> list != null && !list.isEmpty())
                .map(list -> list.get(0))
                .sorted()
                .toList();   // Java 16+; use collect(...) on older versions

        int n = sortedStream.size();

        double median_walk = (n % 2 == 0)
                ? (double) sortedStream.get(n / 2)
                : (sortedStream.get(n / 2 - 1) + sortedStream.get(n / 2)) / 2.0;


        sortedStream = totalPAList.values().stream()
                .filter(list -> list != null && !list.isEmpty())
                .map(list -> list.get(1))
                .sorted()
                .toList();   // Java 16+; use collect(...) on older versions

        n = sortedStream.size();

        double median_bike = (n % 2 == 0)
                ? (double) sortedStream.get(n / 2)
                : (sortedStream.get(n / 2 - 1) + sortedStream.get(n / 2)) / 2.0;

        sortedStream = totalPAList.values().stream()
                .filter(list -> list != null && !list.isEmpty())
                .map(list -> list.get(2))
                .sorted()
                .toList();   // Java 16+; use collect(...) on older versions

        n = sortedStream.size();

        double median_sports = (n % 2 == 0)
                ? (double) sortedStream.get(n / 2)
                : (sortedStream.get(n / 2 - 1) + sortedStream.get(n / 2)) / 2.0;

        sortedStream = totalPAList.values().stream()
                .filter(list -> list != null && !list.isEmpty())
                .map(list -> (list.get(0) + list.get(1) + list.get(2)))
                .sorted()
                .toList();   // Java 16+; use collect(...) on older versions

        n = sortedStream.size();

        double median_total_pa = (n % 2 == 0)
                ? (double) sortedStream.get(n / 2)
                : (sortedStream.get(n / 2 - 1) + sortedStream.get(n / 2)) / 2.0;

        System.out.println("16-24|FEMALE|1 with count " + n);
        System.out.println("---");
        System.out.println("median walk pa " + median_walk + "median bike pa " + median_bike + "median sports pa " + median_sports);
        System.out.println("median total pa " + median_total_pa);
        System.out.println("median total pa " + (median_walk + median_bike + median_sports));
        System.out.println("---");
        */
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
}
