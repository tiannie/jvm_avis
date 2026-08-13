package com.jvmavis.collector.model;

/**
 * Objects JFR sampled at allocation that are still reachable, grouped by where they came from.
 * Derived from {@code jdk.OldObjectSample}.
 *
 * <p>Survival alone is not a leak — a cache is supposed to survive. What points at a leak is a
 * growing sample count on one site paired with a large {@code maxAgeSeconds}. The retention path is
 * left out: JFR only walks it when the recording is dumped with {@code path-to-gc-roots=true},
 * which forces a full GC on the target.
 *
 * @param allocatedBy the deepest Java frame at allocation when one was recorded, otherwise the
 *                    allocating thread. {@code settings=default} does not attach stack traces to
 *                    these events, so in practice this is the thread name unless the target runs
 *                    {@code settings=profile}. That is still enough to place the allocation — a
 *                    pile of identical buffers on an RMI connection thread reads very differently
 *                    from the same objects on an application worker.
 */
public record LeakCandidate(
        String type,
        String allocatedBy,
        long samples,
        long maxAgeSeconds,
        long totalArrayElements
) {
}
