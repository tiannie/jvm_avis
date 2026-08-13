package com.jvmavis.collector.model;

import java.util.List;

/**
 * Everything extracted from one bounded read of a target's JFR recording.
 *
 * <p>Every view comes from the same dump, so each one costs parsing only — no additional traffic to
 * the target.
 *
 * <p>The views do not merge the same way. Samples, pauses and monitor events are occurrences and
 * add up across dumps; thread load and leak candidates are re-reported in full each time and must
 * take the newest reading; exception counts are a running total needing its endpoints. See
 * {@code ProfileMerger}.
 */
public record ProfileSnapshot(
        long timestampMs,
        long windowStartMs,
        long windowEndMs,
        long totalSamples,
        List<HotMethod> hotMethods,
        FlameNode flameGraph,
        long allocationSamples,
        long allocatedBytes,
        List<AllocationSite> topAllocations,
        /** Weighted by estimated bytes rather than sample count. */
        FlameNode allocationFlameGraph,
        GcPauseSummary gcPauses,
        List<ThreadCpuLoad> threadCpu,
        List<LeakCandidate> leakCandidates,
        List<MonitorEventSummary> monitorEvents,
        ExceptionRate exceptions,
        String dumpFile
) {
}
