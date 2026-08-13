package com.jvmavis.collector.profile;

import com.jvmavis.collector.model.ThreadStateSeries;
import com.jvmavis.collector.model.ThreadStateSeries.ThreadStateLane;
import com.jvmavis.collector.store.ThreadStateStore.Entry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Buckets one-second thread-state samples into lanes the UI can draw as a row per thread. */
public final class ThreadStateSeriesBuilder {
    private static final int MAX_LANES = 24;
    private static final int TARGET_BUCKETS = 60;

    /**
     * Ordered by how much a reader needs to see it. A thread that blocked at any point in a bucket
     * shows as BLOCKED even if it spent most of the bucket running, because contention is the thing
     * being looked for and a modal average would erase it.
     */
    private static final List<String> PRECEDENCE =
            List.of("BLOCKED", "RUNNABLE", "TIMED_WAITING", "WAITING", "NEW", "TERMINATED");

    public static ThreadStateSeries build(List<Entry> entries) {
        if (entries.isEmpty()) {
            return ThreadStateSeries.EMPTY;
        }
        long start = entries.get(0).timestampMs();
        long end = entries.get(entries.size() - 1).timestampMs();
        long span = Math.max(1, end - start);
        long bucketMs = Math.max(1000, span / TARGET_BUCKETS);
        int buckets = (int) (span / bucketMs) + 1;

        Map<String, String[]> byThread = new LinkedHashMap<>();
        Map<String, Long> blocked = new LinkedHashMap<>();

        for (Entry entry : entries) {
            int slot = (int) Math.min(buckets - 1, (entry.timestampMs() - start) / bucketMs);
            for (Map.Entry<String, String> e : entry.states().entrySet()) {
                String[] lane = byThread.computeIfAbsent(e.getKey(), k -> new String[buckets]);
                lane[slot] = moreSignificant(lane[slot], e.getValue());
            }
        }
        for (Map.Entry<String, String[]> lane : byThread.entrySet()) {
            long count = Arrays.stream(lane.getValue()).filter("BLOCKED"::equals).count();
            blocked.put(lane.getKey(), count);
        }

        List<Long> timestamps = new ArrayList<>(buckets);
        for (int i = 0; i < buckets; i++) {
            timestamps.add(start + i * bucketMs);
        }

        List<ThreadStateLane> lanes = new ArrayList<>();
        byThread.keySet().stream()
                // Blocking first, then threads that actually change state. A pool worker flipping
                // between RUNNABLE and TIMED_WAITING says something; Finalizer parked in WAITING
                // forever does not, and sorting by name alone buries the workers under
                // infrastructure threads. Name breaks ties so lanes stay put between polls.
                .sorted(Comparator
                        .comparingLong((String name) -> -blocked.getOrDefault(name, 0L))
                        .thenComparing(name -> -distinctStates(byThread.get(name)))
                        .thenComparing(name -> -activeBuckets(byThread.get(name)))
                        .thenComparing(Comparator.naturalOrder()))
                .limit(MAX_LANES)
                .forEach(name -> lanes.add(new ThreadStateLane(
                        name, blocked.getOrDefault(name, 0L), Arrays.asList(byThread.get(name)))));

        return new ThreadStateSeries(start, end, bucketMs, timestamps, lanes);
    }

    private static long distinctStates(String[] lane) {
        return Arrays.stream(lane).filter(java.util.Objects::nonNull).distinct().count();
    }

    private static long activeBuckets(String[] lane) {
        return Arrays.stream(lane).filter("RUNNABLE"::equals).count();
    }

    private static String moreSignificant(String current, String candidate) {
        if (current == null) {
            return candidate;
        }
        int a = PRECEDENCE.indexOf(current);
        int b = PRECEDENCE.indexOf(candidate);
        if (a < 0) {
            return candidate;
        }
        return b >= 0 && b < a ? candidate : current;
    }

    private ThreadStateSeriesBuilder() {
    }
}
