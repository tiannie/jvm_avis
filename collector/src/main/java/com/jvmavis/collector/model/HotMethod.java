package com.jvmavis.collector.model;

public record HotMethod(
        String method,
        long samples,
        double percent
) {
}
