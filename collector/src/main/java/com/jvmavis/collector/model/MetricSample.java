package com.jvmavis.collector.model;

import java.util.List;
import java.util.Map;

public record MetricSample(
        long timestampMs,
        Double processCpuLoad,
        long heapUsedBytes,
        long heapCommittedBytes,
        long heapMaxBytes,
        long nonHeapUsedBytes,
        long gcCollectionCount,
        long gcCollectionTimeMs,
        List<GcCollectorStat> gcCollectors,
        List<MemoryPoolUsage> memoryPools,
        int threadCount,
        int daemonThreadCount,
        int peakThreadCount,
        Map<String, Integer> threadStates
) {
}
