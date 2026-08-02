package com.jvmavis.collector.model;

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
        int threadCount,
        int daemonThreadCount,
        int peakThreadCount,
        Map<String, Integer> threadStates
) {
}
