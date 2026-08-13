package com.jvmavis.collector.model;

import java.util.List;

/**
 * Each thread's state over time, one lane per thread.
 *
 * <p>Bucketed rather than one cell per scrape: JMX samples every second, which over a
 * multi-minute window is far more cells than a row can show. Within a bucket the most
 * consequential state wins rather than the most frequent — a thread that blocked briefly is worth
 * seeing, and averaging would hide exactly the events being looked for.
 */
public record ThreadStateSeries(
        long windowStartMs,
        long windowEndMs,
        long bucketMs,
        List<Long> timestampsMs,
        List<ThreadStateLane> lanes
) {
    public static final ThreadStateSeries EMPTY =
            new ThreadStateSeries(0, 0, 0, List.of(), List.of());

    /**
     * @param states index-aligned with {@code timestampsMs}; null where the thread did not exist
     * @param blockedBuckets how many buckets caught this thread BLOCKED, used to sort lanes
     */
    public record ThreadStateLane(
            String thread,
            long blockedBuckets,
            List<String> states
    ) {
    }
}
