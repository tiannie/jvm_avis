package com.jvmavis.collector.model;

/**
 * CPU attributed to one thread, from {@code jdk.ThreadCPULoad}.
 *
 * <p>A gauge, not a running total: JFR re-reports every thread's current load in a batch each
 * chunk, so merged windows keep the newest reading rather than summing.
 */
public record ThreadCpuLoad(
        String thread,
        long javaThreadId,
        double userPercent,
        double systemPercent
) {
}
