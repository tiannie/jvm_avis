package com.jvmavis.collector.model;

public record TargetInfo(
        String id,
        String host,
        int port,
        String label,
        String status,
        String lastError,
        Long lastMetricAtMs,
        Long lastProfileAtMs
) {
}
