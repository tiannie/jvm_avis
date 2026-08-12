package com.jvmavis.collector.model;

import java.util.List;

/**
 * Everything extracted from one bounded read of a target's JFR recording.
 *
 * <p>CPU, allocation and GC-pause views all come from the same dump, so the extra views cost
 * parsing only — no additional traffic to the target.
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
        String dumpFile
) {
}
