package com.jvmavis.collector.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Recent per-thread states, kept apart from {@link MetricStore}.
 *
 * <p>Thread names on every one-second sample would multiply the metrics series that the UI already
 * polls whole, so this is stored separately and held only for the profile window rather than the
 * full metric retention.
 */
public final class ThreadStateStore {
    private final long retentionMs;
    private final Map<String, ConcurrentLinkedDeque<Entry>> byTarget = new ConcurrentHashMap<>();

    public record Entry(long timestampMs, Map<String, String> states) {
    }

    public ThreadStateStore(int retentionSeconds) {
        this.retentionMs = retentionSeconds * 1000L;
    }

    public void add(String targetId, long timestampMs, Map<String, String> states) {
        if (states == null || states.isEmpty()) {
            return;
        }
        ConcurrentLinkedDeque<Entry> series =
                byTarget.computeIfAbsent(targetId, id -> new ConcurrentLinkedDeque<>());
        series.addLast(new Entry(timestampMs, Map.copyOf(states)));
        long cutoff = timestampMs - retentionMs;
        while (true) {
            Entry head = series.peekFirst();
            if (head == null || head.timestampMs() >= cutoff) {
                break;
            }
            series.pollFirst();
        }
    }

    public List<Entry> recent(String targetId) {
        ConcurrentLinkedDeque<Entry> series = byTarget.get(targetId);
        return series == null ? List.of() : new ArrayList<>(series);
    }

    public void remove(String targetId) {
        byTarget.remove(targetId);
    }
}
