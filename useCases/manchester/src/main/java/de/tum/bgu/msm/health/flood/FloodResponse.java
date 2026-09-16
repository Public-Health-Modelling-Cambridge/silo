package de.tum.bgu.msm.health.flood;

/**
 * Depth-response function for one active mode: how much floodwater slows a pedestrian or
 * cyclist, and at what depth the link becomes impassable.
 * <p>
 * This is the active-mode counterpart of the Pregnolato depth-disruption function used for cars
 * in {@code flood_road_exposure.ipynb}. It is deliberately a separate function: wading is not
 * driving, and the governing criterion in the literature is pedestrian <i>instability</i>
 * (Defra/EA FD2320 hazard rating {@code HR = d(v + 0.5) + DF}; Cox et al. 2010;
 * Martinez-Gomariz et al. 2016), not vehicle wading depth.
 * <p>
 * <b>The coefficients below are placeholders and must be replaced with calibrated values before
 * any published result.</b> They are shaped correctly — monotone, bounded, zero effect when dry
 * — but the specific numbers are not taken from a fitted study. Two caveats to resolve first:
 * <ul>
 *   <li>The hazard criterion is depth <i>and velocity</i>. The exported depth series carries
 *       depth only, so the thresholds here are depth-only proxies. If the hydraulic model can
 *       also export velocity, extend {@link WaterDepthData} and use the full HR formula.</li>
 *   <li>Evidence for <i>cycling</i> in floodwater is much thinner than for walking. Treating
 *       bikes with a pedestrian-derived curve is an assumption that has to be stated, not
 *       hidden.</li>
 * </ul>
 */
public final class FloodResponse {

    /**
     * Depth below which the flood is ignored, in metres. Matches DEPTH_SHALLOW in the notebook,
     * so the two pipelines agree on what counts as wet.
     */
    private final double negligibleDepth;

    /** Depth at or above which the link is impassable on foot or by bike, in metres. */
    private final double impassableDepth;

    /**
     * Speed retained at {@link #impassableDepth}, as a fraction of the dry speed. The speed
     * factor falls linearly from 1 at {@link #negligibleDepth} to this value, then the link is
     * treated as impassable.
     */
    private final double minSpeedFactor;

    public FloodResponse(double negligibleDepth, double impassableDepth, double minSpeedFactor) {
        if (impassableDepth <= negligibleDepth) {
            throw new IllegalArgumentException("impassableDepth must exceed negligibleDepth");
        }
        this.negligibleDepth = negligibleDepth;
        this.impassableDepth = impassableDepth;
        this.minSpeedFactor = minSpeedFactor;
    }

    /**
     * Walking. Impassability at 0.3 m follows the notebook's DEPTH_MODERATE; adult pedestrian
     * instability in the experimental literature sets in around knee depth, earlier for
     * children and older adults. The 0.25 floor means a pedestrian in 0.3 m of water moves at a
     * quarter of dry-weather speed.
     */
    public static FloodResponse forWalk() {
        return new FloodResponse(0.10, 0.30, 0.25);
    }

    /**
     * Cycling. Assumed impassable at the same depth as walking — a cyclist reaching standing
     * water dismounts and becomes a pedestrian — with a steeper speed penalty, since riding
     * through even shallow water is slower relative to dry cycling speed than wading is
     * relative to walking.
     */
    public static FloodResponse forBike() {
        return new FloodResponse(0.10, 0.30, 0.10);
    }

    /** True when the link cannot be used at this depth. */
    public boolean isImpassable(double depth) {
        return depth >= impassableDepth;
    }

    /**
     * Multiplier on the dry-weather speed, in (0, 1]. Returns 1 when dry. Never returns 0:
     * a zero speed would make travel time infinite and break the router, so impassable links
     * are excluded via {@link #isImpassable} instead.
     */
    public double speedFactor(double depth) {
        if (depth <= negligibleDepth) {
            return 1.;
        }
        if (depth >= impassableDepth) {
            return minSpeedFactor;
        }
        double t = (depth - negligibleDepth) / (impassableDepth - negligibleDepth);
        return 1. - t * (1. - minSpeedFactor);
    }

    /**
     * Depth normalised to [0, 1] over the passable range, for use as an extra disutility term
     * when flooded links should be avoided for reasons beyond slowness (risk, reluctance).
     */
    public double hazardFraction(double depth) {
        if (depth <= negligibleDepth) {
            return 0.;
        }
        return Math.min(1., (depth - negligibleDepth) / (impassableDepth - negligibleDepth));
    }
}
