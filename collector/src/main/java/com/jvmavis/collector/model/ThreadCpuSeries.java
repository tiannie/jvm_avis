package com.jvmavis.collector.model;

import java.util.List;

/**
 * Per-thread CPU over time, one lane per thread.
 *
 * <p>Timestamps are shared by every lane so the payload stays compact and the client can render a
 * grid directly. A lane's arrays are index-aligned with {@code timestampsMs}, with null where that
 * dump carried no reading for the thread.
 *
 * <p>Resolution is one point per JFR dump, since that is how often the target re-reports thread
 * load. Sampling faster would mean querying {@code ThreadMXBean} per thread per scrape, which is
 * far more expensive on the target than reading a batch that JFR already produced.
 */
public record ThreadCpuSeries(
        long windowStartMs,
        long windowEndMs,
        double maxPercent,
        List<Long> timestampsMs,
        List<ThreadLane> lanes
) {
    public static final ThreadCpuSeries EMPTY =
            new ThreadCpuSeries(0, 0, 0, List.of(), List.of());

    public record ThreadLane(
            String thread,
            double peakPercent,
            List<Double> userPercent,
            List<Double> systemPercent
    ) {
    }
}
