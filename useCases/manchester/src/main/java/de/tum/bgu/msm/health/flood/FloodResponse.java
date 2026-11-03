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
 * The walking values are anchored on published stability limits and wading-speed experiments
 * (see {@link #forWalk()}); the cycling values are a geometric argument, because no published
 * depth-speed function for cycling through floodwater is known (see {@link #forBike()}). Both
 * are first estimates, to be revisited against the final runs. Caveats:
 * <ul>
 *   <li>The hazard criterion is depth <i>and velocity</i>. The exported depth series carries
 *       depth only, so the thresholds here are depth-only proxies. If the hydraulic model can
 *       also export velocity, extend {@link WaterDepthData} and use the full HR formula.</li>
 *   <li>Evidence for <i>cycling</i> in floodwater is much thinner than for walking. The bike
 *       values are an assumption that has to be stated, not hidden.</li>
 *   <li>One threshold per mode for everyone. Children and older adults lose stability at lower
 *       depths (Cox et al. 2010); an age-dependent threshold would need the person in
 *       {@link FloodAwareActiveTravelTime}, which it already receives.</li>
 * </ul>
 */
public final class FloodResponse {

    /** Depth below which the flood is ignored, in metres. */
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
     * Walking: no effect below 0.10 m, impassable from 0.50 m, half the dry speed just below it.
     * <ul>
     *   <li>0.10 m: the shallow class of Defra/EA FD2320, below which water is treated as
     *       passable (same as DEPTH_SHALLOW in the notebook).</li>
     *   <li>0.50 m: in slow-moving urban surface water (velocity near 0), FD2320's hazard rating
     *       reaches "danger for some" at about this depth, and it is the stability limit for
     *       children in Cox et al. (2010). Adults stay stable in deeper still water, but walking
     *       through water above the knee is not a route anyone chooses.</li>
     *   <li>0.50 speed factor: wading experiments (Bernardini et al. 2017) show walking speed
     *       falling roughly linearly with depth, to about half the dry speed near knee to thigh
     *       depth.</li>
     * </ul>
     * References not machine-verified; check the exact figures against the papers before
     * publication.
     */
    public static FloodResponse forWalk() {
        return new FloodResponse(0.10, 0.50, 0.50);
    }

    /**
     * Cycling: no effect below 0.05 m, impassable from 0.30 m, a fifth of the dry speed just
     * below it. Geometric, not empirical:
     * <ul>
     *   <li>0.05 m: wheel drag and spray slow a rider from a few centimetres; with a 170 mm crank
     *       and a bottom bracket about 0.27 m high, the pedal at the bottom of the stroke enters
     *       the water at about 0.10 m.</li>
     *   <li>0.30 m: the bottom bracket is under water and the rider has to dismount. Pushing a
     *       bike through water is then treated as impassable for the bike mode; mode choice can
     *       still move the trip to walking.</li>
     *   <li>0.20 speed factor: about 1.1 m/s, the speed of pushing a bike while wading, against
     *       a dry cycling speed of 5.5 m/s.</li>
     * </ul>
     */
    public static FloodResponse forBike() {
        return new FloodResponse(0.05, 0.30, 0.20);
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
