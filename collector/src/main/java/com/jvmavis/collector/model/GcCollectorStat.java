package com.jvmavis.collector.model;

/**
 * Cumulative counters for one collector.
 *
 * <p>Summing every collector hides the split that usually matters: young collections are expected
 * and cheap, old ones are neither.
 */
public record GcCollectorStat(
        String name,
        long collections,
        long timeMs
) {
}
