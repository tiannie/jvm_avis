package com.jvmavis.collector.model;

/** Usage of a single memory pool, e.g. G1 Eden Space, G1 Old Gen, Metaspace. */
public record MemoryPoolUsage(
        String name,
        boolean heap,
        long usedBytes,
        long committedBytes,
        long maxBytes
) {
}
