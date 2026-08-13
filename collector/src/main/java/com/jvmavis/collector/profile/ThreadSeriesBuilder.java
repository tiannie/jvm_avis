package com.jvmavis.collector.profile;

import com.jvmavis.collector.model.ProfileSnapshot;
import com.jvmavis.collector.model.ThreadCpuLoad;
import com.jvmavis.collector.model.ThreadCpuSeries;
import com.jvmavis.collector.model.ThreadCpuSeries.ThreadLane;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the per-dump thread-load readings into one lane per thread.
 *
 * <p>{@code ProfileMerger} keeps only the newest reading because a gauge cannot be summed, which
 * answers "what is running now" but discards the shape over time. The stored snapshots still hold
 * every reading, so the series is rebuilt from those rather than from anything extra on the wire.
 */
public final class ThreadSeriesBuilder {
    private static final int MAX_LANES = 24;

    public static ThreadCpuSeries build(List<ProfileSnapshot> snapshots) {
        List<ProfileSnapshot> withReadings = new ArrayList<>();
        List<ThreadCpuLoad> previous = null;
        for (ProfileSnapshot snapshot : snapshots) {
            List<ThreadCpuLoad> reading = snapshot.threadCpu();
            if (reading == null || reading.isEmpty()) {
                continue;
            }
            // Dumps can outpace the target's chunk rotation and re-see the same batch; plotting it
            // twice would invent a data point that never happened.
            if (reading.equals(previous)) {
                continue;
            }
            previous = reading;
            withReadings.add(snapshot);
        }
        if (withReadings.isEmpty()) {
            return ThreadCpuSeries.EMPTY;
        }

        List<Long> timestamps = new ArrayList<>(withReadings.size());
        for (ProfileSnapshot snapshot : withReadings) {
            timestamps.add(snapshot.timestampMs());
        }

        Map<String, Double[]> user = new LinkedHashMap<>();
        Map<String, Double[]> system = new LinkedHashMap<>();
        Map<String, Double> peak = new LinkedHashMap<>();
        int slots = withReadings.size();

        for (int i = 0; i < slots; i++) {
            for (ThreadCpuLoad load : withReadings.get(i).threadCpu()) {
                String name = load.thread();
                user.computeIfAbsent(name, k -> new Double[slots])[i] = load.userPercent();
                system.computeIfAbsent(name, k -> new Double[slots])[i] = load.systemPercent();
                double total = load.userPercent() + load.systemPercent();
                peak.merge(name, total, Math::max);
            }
        }

        List<ThreadLane> lanes = new ArrayList<>();
        peak.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(MAX_LANES)
                // Arrays.asList, not List.of: a slot is null when that dump had no reading for the
                // thread, and the client needs the gap rather than a fabricated zero.
                .forEach(e -> lanes.add(new ThreadLane(
                        e.getKey(),
                        round2(e.getValue()),
                        Arrays.asList(user.get(e.getKey())),
                        Arrays.asList(system.get(e.getKey())))));

        double max = lanes.stream().mapToDouble(ThreadLane::peakPercent).max().orElse(0);
        return new ThreadCpuSeries(
                timestamps.get(0),
                timestamps.get(timestamps.size() - 1),
                max,
                timestamps,
                lanes);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private ThreadSeriesBuilder() {
    }
}
