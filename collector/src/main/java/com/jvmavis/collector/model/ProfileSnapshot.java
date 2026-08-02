package com.jvmavis.collector.model;

import java.util.List;

public record ProfileSnapshot(
        long timestampMs,
        long windowStartMs,
        long windowEndMs,
        long totalSamples,
        List<HotMethod> hotMethods,
        String dumpFile
) {
}
