package com.jvmavis.collector.model;

/**
 * One {@code jdk.GCPhasePause} event: a stop-the-world pause the application actually felt.
 *
 * @param gcId identifies the collection, and is stable across reads unlike the decoded timestamp
 */
public record GcPause(
        long gcId,
        long timestampMs,
        double durationMs,
        String name
) {
}
