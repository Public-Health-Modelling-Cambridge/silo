package de.tum.bgu.msm.health.flood;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;

/**
 * Time-varying flood depth per link, loaded once and shared by every component that needs it.
 * <p>
 * Input is {@code flood_link_depth_timeseries.csv.gz}, written by section 14 of
 * {@code flood_road_exposure.ipynb}:
 * <pre>
 * linkID,is_bridge,&lt;simSec_1&gt;,&lt;simSec_2&gt;,...,&lt;restoreSec&gt;
 * 1045out,0,0.000,0.120,...,0.000
 * </pre>
 * The column headers are MATSim simulation seconds, identical to the {@code startTime} values
 * in the car {@code networkChangeEvents} file, so both modes see the same flood at the same
 * instant. The final column is the restoration step (all zero).
 * <p>
 * Depth is a step function: the value at column <i>i</i> holds until column <i>i+1</i>. Before
 * the first timestamp and after the last, depth is zero.
 * <p>
 * Lookup sits in the router's inner loop, so depths are held in a flat array addressed by
 * {@link Id#index()} — the same trick mito's {@code ActiveDisutilityPrecalc} uses. Links that
 * are never wet carry no row at all.
 */
public final class WaterDepthData {

    private static final Logger logger = LogManager.getLogger(WaterDepthData.class);

    /** Simulation seconds, ascending. */
    private final double[] times;

    /** Row index per link index, or -1 when the link is never wet. */
    private final int[] rowByLinkIndex;

    /** Depths in metres, row-major: {@code depths[row * times.length + timeBin]}. */
    private final float[] depths;

    private final int wetLinkCount;

    private WaterDepthData(double[] times, int[] rowByLinkIndex, float[] depths, int wetLinkCount) {
        this.times = times;
        this.rowByLinkIndex = rowByLinkIndex;
        this.depths = depths;
        this.wetLinkCount = wetLinkCount;
    }

    /**
     * Depth in metres on this link at this simulation time; 0 when the link is dry, unknown to
     * the flood model, or the time falls outside the event window.
     */
    public double getDepth(Link link, double time) {
        int linkIndex = link.getId().index();
        if (linkIndex >= rowByLinkIndex.length) {
            return 0.;
        }
        int row = rowByLinkIndex[linkIndex];
        if (row < 0) {
            return 0.;
        }
        return depths[row * times.length + timeBin(time)];
    }

    /** Index of the last timestamp at or before {@code time}; 0 when before the event starts. */
    private int timeBin(double time) {
        if (time <= times[0]) {
            return 0;
        }
        if (time >= times[times.length - 1]) {
            return times.length - 1;
        }
        int i = Arrays.binarySearch(times, time);
        return i >= 0 ? i : -i - 2;   // insertion point - 1
    }

    public int getWetLinkCount() {
        return wetLinkCount;
    }

    public static WaterDepthData read(String file, Network network) {
        logger.info("Reading flood depth time series from " + file);
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(Path.of(file))), StandardCharsets.UTF_8))) {

            String[] header = in.readLine().split(",");
            // columns: linkID, is_bridge, then one per time step
            double[] times = new double[header.length - 2];
            for (int i = 0; i < times.length; i++) {
                times[i] = Double.parseDouble(header[i + 2].trim());
            }

            int[] rowByLinkIndex = new int[Id.getNumberOfIds(Link.class)];
            Arrays.fill(rowByLinkIndex, -1);

            // Two passes would need the file twice; grow instead and trim at the end. Only a
            // few per cent of links are ever wet, so sizing for the whole network would waste
            // most of the allocation.
            float[] depths = new float[16384 * times.length];
            int row = 0;
            int unknown = 0;
            int bridgesSkipped = 0;

            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] f = line.split(",");
                Id<Link> id = Id.createLinkId(f[0]);
                Link link = network.getLinks().get(id);
                if (link == null) {
                    unknown++;
                    continue;
                }
                if ("1".equals(f[1].trim())) {
                    // Elevated link: the raster sampled the ground beneath it. The notebook
                    // already zeroes these, so this is belt and braces.
                    bridgesSkipped++;
                    continue;
                }

                int offset = row * times.length;
                if (offset + times.length > depths.length) {
                    depths = Arrays.copyOf(depths, depths.length * 2);
                }
                for (int t = 0; t < times.length; t++) {
                    depths[offset + t] = Float.parseFloat(f[t + 2]);
                }
                int linkIndex = link.getId().index();
                if (linkIndex >= rowByLinkIndex.length) {
                    rowByLinkIndex = Arrays.copyOf(rowByLinkIndex, linkIndex + 1);
                }
                rowByLinkIndex[linkIndex] = row;
                row++;
            }

            depths = Arrays.copyOf(depths, row * times.length);

            logger.warn("Flood depth data: " + row + " wet links, " + times.length + " time steps ("
                    + (int) times[0] + "s to " + (int) times[times.length - 1] + "s).");
            if (unknown > 0) {
                logger.warn("  " + unknown + " link ids in the depth file are not in the network — ignored.");
            }
            if (bridgesSkipped > 0) {
                logger.info("  " + bridgesSkipped + " bridge/elevated links skipped.");
            }
            if (row == 0) {
                throw new RuntimeException("No usable rows in " + file + " — check the link id convention.");
            }
            return new WaterDepthData(times, rowByLinkIndex, depths, row);

        } catch (IOException e) {
            throw new RuntimeException("Could not read flood depth time series " + file, e);
        }
    }
}
