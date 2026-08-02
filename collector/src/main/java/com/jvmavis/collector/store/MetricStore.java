package com.jvmavis.collector.store;

import com.jvmavis.collector.model.MetricSample;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public final class MetricStore {
    private final long retentionMs;
    private final Map<String, ConcurrentLinkedDeque<MetricSample>> byTarget = new ConcurrentHashMap<>();

    public MetricStore(int retentionSeconds) {
        this.retentionMs = retentionSeconds * 1000L;
    }

    public void add(String targetId, MetricSample sample) {
        ConcurrentLinkedDeque<MetricSample> series =
                byTarget.computeIfAbsent(targetId, id -> new ConcurrentLinkedDeque<>());
        series.addLast(sample);
        long cutoff = sample.timestampMs() - retentionMs;
        while (true) {
            MetricSample head = series.peekFirst();
            if (head == null || head.timestampMs() >= cutoff) {
                break;
            }
            series.pollFirst();
        }
    }

    public List<MetricSample> query(String targetId, Long fromMs, Long toMs) {
        ConcurrentLinkedDeque<MetricSample> series = byTarget.get(targetId);
        if (series == null) {
            return List.of();
        }
        long from = fromMs == null ? 0L : fromMs;
        long to = toMs == null ? Long.MAX_VALUE : toMs;
        List<MetricSample> out = new ArrayList<>();
        for (MetricSample sample : series) {
            if (sample.timestampMs() >= from && sample.timestampMs() <= to) {
                out.add(sample);
            }
        }
        return out;
    }

    public void remove(String targetId) {
        byTarget.remove(targetId);
    }
}
