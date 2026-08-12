package com.jvmavis.collector.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pause-time distribution over a window.
 *
 * <p>Cumulative GC time cannot distinguish one long stall from many short ones, which is the
 * difference that matters for latency, so the individual pauses are kept alongside the percentiles.
 */
public record GcPauseSummary(
        long count,
        double totalMs,
        double maxMs,
        double p50Ms,
        double p95Ms,
        double p99Ms,
        List<GcPause> pauses
) {
    /** Caps the serialised pause list; percentiles are still computed over every pause. */
    private static final int MAX_RETAINED = 500;

    public static GcPauseSummary of(List<GcPause> pauses) {
        if (pauses == null || pauses.isEmpty()) {
            return new GcPauseSummary(0, 0, 0, 0, 0, 0, List.of());
        }
        List<GcPause> unique = deduplicate(pauses);

        List<Double> sorted = new ArrayList<>(unique.size());
        double total = 0;
        for (GcPause pause : unique) {
            sorted.add(pause.durationMs());
            total += pause.durationMs();
        }
        sorted.sort(Comparator.naturalOrder());

        List<GcPause> retained = new ArrayList<>(unique);
        retained.sort(Comparator.comparingLong(GcPause::timestampMs));
        if (retained.size() > MAX_RETAINED) {
            retained = new ArrayList<>(retained.subList(retained.size() - MAX_RETAINED, retained.size()));
        }

        return new GcPauseSummary(
                unique.size(),
                round2(total),
                round2(sorted.get(sorted.size() - 1)),
                round2(percentile(sorted, 50)),
                round2(percentile(sorted, 95)),
                round2(percentile(sorted, 99)),
                List.copyOf(retained));
    }

    /**
     * A dump is delivered at chunk granularity, so a pause near a boundary is read again by the
     * next dump. Its decoded timestamp shifts by a few milliseconds between reads, which defeats
     * the timestamp watermark, so identity comes from the collection id instead.
     */
    private static List<GcPause> deduplicate(List<GcPause> pauses) {
        Map<String, GcPause> byId = new LinkedHashMap<>();
        List<GcPause> unidentified = new ArrayList<>();
        for (GcPause pause : pauses) {
            if (pause.gcId() < 0) {
                unidentified.add(pause);
            } else {
                byId.putIfAbsent(pause.gcId() + "/" + pause.name(), pause);
            }
        }
        List<GcPause> out = new ArrayList<>(byId.values());
        out.addAll(unidentified);
        return out;
    }

    private static double percentile(List<Double> sorted, int p) {
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
