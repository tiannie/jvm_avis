package com.jvmavis.collector.model;

import java.util.List;

public record FlameNode(
        String name,
        long value,
        List<FlameNode> children
) {
}
