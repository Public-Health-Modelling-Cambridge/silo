package de.tum.bgu.msm.health.noise;

public class NoiseMetrics {

    private static final int PENALTY_DAY = 0;
    private static final int PENALTY_EVENING = 5;
    private static final int PENALTY_NIGHT = 10;

    public static double getHourlyNoiseLevel(int hour, double noiseRp) {

        if(hour >= 19  && hour < 23 ){
            return Math.pow(10, (noiseRp + PENALTY_EVENING) / 10);
        }else if (hour < 7  || hour >= 23 ){
            return Math.pow(10, (noiseRp + PENALTY_NIGHT) / 10);
        }else{
            return Math.pow(10, (noiseRp + PENALTY_DAY) / 10);
        }
    }

    // sound energy without the Lden penalty, for Lnight
    public static double getHourlyNoiseEnergy(double noiseRp) {
        return Math.pow(10, noiseRp / 10);
    }

    // lower limits of the exposure-response curves: the quadratic curves rise again below their minimum (HA: Lden 45.6 dB,
    // HSD: Lnight 37 dB) and were not fitted for these levels, so the curve is held flat at its value at the limit
    private static final double MIN_LDEN_HIGH_ANNOYED = 45.;
    private static final double MIN_LNIGHT_HIGH_SLEEP_DISTURBANCE = 40.;

    public static double getHighAnnoyedPercentage(float Lden) {
        //noise %HA high annoyed (Guski et al., 2017)
        double level = Math.max(Lden, MIN_LDEN_HIGH_ANNOYED);
        return (78.9270 - 3.1162 * level + 0.0342 * level * level);
    }

    public static double getHighSleepDisturbancePercentage(float Lnight) {

        //%HSD high sleep disturbance (Basner & McGuire, 2018)
        double level = Math.max(Lnight, MIN_LNIGHT_HIGH_SLEEP_DISTURBANCE);
        return (19.4312 - 0.9336 * level + 0.0126 * level * level);
    }
}
