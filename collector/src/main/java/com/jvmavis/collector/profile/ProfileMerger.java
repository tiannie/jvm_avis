package com.jvmavis.collector.profile;

import com.jvmavis.collector.model.AllocationSite;
import com.jvmavis.collector.model.ExceptionRate;
import com.jvmavis.collector.model.FlameNode;
import com.jvmavis.collector.model.GcPause;
import com.jvmavis.collector.model.GcPauseSummary;
import com.jvmavis.collector.model.HotMethod;
import com.jvmavis.collector.model.MonitorEventSummary;
import com.jvmavis.collector.model.ProfileSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Combines the incremental dumps taken since a target was registered into one view.
 *
 * <p>Each dump only carries samples newer than the previous one, so a single snapshot covers just
 * one dump interval. Merging restores the multi-minute profile the UI shows without re-reading the
 * target's recording.
 */
public final class ProfileMerger {
    private static final int TOP_N = 25;

    public static ProfileSnapshot merge(List<ProfileSnapshot> snapshots) {
        List<ProfileSnapshot> usable = new ArrayList<>();
        for (ProfileSnapshot snapshot : snapshots) {
            if (snapshot != null && hasData(snapshot)) {
                usable.add(snapshot);
            }
        }
        if (usable.isEmpty()) {
            return snapshots.isEmpty() ? null : snapshots.get(snapshots.size() - 1);
        }
        if (usable.size() == 1) {
            return usable.get(0);
        }

        MutableNode cpuRoot = new MutableNode("all");
        MutableNode allocRoot = new MutableNode("all");
        List<GcPause> pauses = new ArrayList<>();
        long total = 0;
        long allocSamples = 0;
        long allocBytes = 0;
        long windowStart = Long.MAX_VALUE;
        long windowEnd = Long.MIN_VALUE;
        long timestamp = Long.MIN_VALUE;
        String dumpFile = null;
        ProfileSnapshot newest = null;

        for (ProfileSnapshot snapshot : usable) {
            addTree(cpuRoot, snapshot.flameGraph());
            addTree(allocRoot, snapshot.allocationFlameGraph());
            total += snapshot.totalSamples();
            allocSamples += snapshot.allocationSamples();
            allocBytes += snapshot.allocatedBytes();
            if (snapshot.gcPauses() != null) {
                pauses.addAll(snapshot.gcPauses().pauses());
            }
            windowStart = Math.min(windowStart, snapshot.windowStartMs());
            windowEnd = Math.max(windowEnd, snapshot.windowEndMs());
            if (snapshot.timestampMs() >= timestamp) {
                timestamp = snapshot.timestampMs();
                dumpFile = snapshot.dumpFile();
                newest = snapshot;
            }
        }

        return new ProfileSnapshot(
                timestamp,
                windowStart,
                windowEnd,
                total,
                hotMethods(cpuRoot, total),
                cpuRoot.toFlameNode(),
                allocSamples,
                allocBytes,
                topAllocations(usable, allocBytes),
                allocRoot.toFlameNode(),
                GcPauseSummary.of(pauses),
                latestNonEmpty(usable, ProfileSnapshot::threadCpu),
                latestNonEmpty(usable, ProfileSnapshot::leakCandidates),
                mergeMonitorEvents(usable),
                mergeExceptions(usable),
                newest == null ? dumpFile : newest.dumpFile());
    }

    /**
     * Gauges and snapshots describe the target as it is now, so the newest reading replaces older
     * ones rather than adding to them. Empty lists are skipped: a dump that happened not to span a
     * batch boundary should not blank the panel.
     */
    private static <T> List<T> latestNonEmpty(
            List<ProfileSnapshot> snapshots, Function<ProfileSnapshot, List<T>> field) {
        List<T> best = List.of();
        long bestAt = Long.MIN_VALUE;
        for (ProfileSnapshot snapshot : snapshots) {
            List<T> value = field.apply(snapshot);
            if (value != null && !value.isEmpty() && snapshot.timestampMs() >= bestAt) {
                bestAt = snapshot.timestampMs();
                best = value;
            }
        }
        return best;
    }

    private static List<MonitorEventSummary> mergeMonitorEvents(List<ProfileSnapshot> snapshots) {
        Map<String, double[]> byKey = new LinkedHashMap<>();
        for (ProfileSnapshot snapshot : snapshots) {
            for (MonitorEventSummary event : snapshot.monitorEvents()) {
                byKey.compute(event.kind() + "\u0000" + event.monitorClass(), (k, v) -> {
                    double[] acc = v == null ? new double[3] : v;
                    acc[0] += event.events();
                    acc[1] += event.totalMs();
                    acc[2] = Math.max(acc[2], event.maxMs());
                    return acc;
                });
            }
        }
        List<MonitorEventSummary> out = new ArrayList<>();
        byKey.entrySet().stream()
                .sorted(Comparator.comparingDouble((Map.Entry<String, double[]> e) -> e.getValue()[1]).reversed())
                .limit(TOP_N)
                .forEach(e -> {
                    String[] parts = e.getKey().split("\u0000", 2);
                    out.add(new MonitorEventSummary(
                            parts[0],
                            parts.length > 1 ? parts[1] : "unknown",
                            (long) e.getValue()[0],
                            round2(e.getValue()[1]),
                            round2(e.getValue()[2])));
                });
        return out;
    }

    /** A running total spans the window from its outermost readings, so rates are not added. */
    private static ExceptionRate mergeExceptions(List<ProfileSnapshot> snapshots) {
        long firstCount = 0;
        long firstAtMs = 0;
        long lastCount = 0;
        long lastAtMs = 0;
        for (ProfileSnapshot snapshot : snapshots) {
            ExceptionRate rate = snapshot.exceptions();
            if (rate == null || rate.firstAtMs() == 0) {
                continue;
            }
            if (firstAtMs == 0 || rate.firstAtMs() < firstAtMs) {
                firstAtMs = rate.firstAtMs();
                firstCount = rate.firstCount();
            }
            if (rate.lastAtMs() >= lastAtMs) {
                lastAtMs = rate.lastAtMs();
                lastCount = rate.lastCount();
            }
        }
        return firstAtMs == 0 ? ExceptionRate.EMPTY
                : ExceptionRate.of(firstCount, firstAtMs, lastCount, lastAtMs);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static boolean hasData(ProfileSnapshot snapshot) {
        return snapshot.totalSamples() > 0
                || snapshot.allocationSamples() > 0
                || (snapshot.gcPauses() != null && snapshot.gcPauses().count() > 0)
                || !snapshot.threadCpu().isEmpty()
                || !snapshot.leakCandidates().isEmpty()
                || !snapshot.monitorEvents().isEmpty();
    }

    /**
     * Per-dump lists are already capped at the top N, so a class can be missing from some of them.
     * Its bytes still land in the merged total, which is the honest reading: the table shows the
     * biggest allocators, not a complete breakdown.
     */
    private static List<AllocationSite> topAllocations(List<ProfileSnapshot> snapshots, long totalBytes) {
        Map<String, long[]> byType = new HashMap<>();
        for (ProfileSnapshot snapshot : snapshots) {
            for (AllocationSite site : snapshot.topAllocations()) {
                byType.compute(site.type(), (k, v) -> {
                    long[] acc = v == null ? new long[2] : v;
                    acc[0] += site.bytes();
                    acc[1] += site.samples();
                    return acc;
                });
            }
        }
        List<AllocationSite> out = new ArrayList<>();
        byType.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[0]).reversed())
                .limit(TOP_N)
                .forEach(e -> {
                    double pct = totalBytes == 0 ? 0.0 : (100.0 * e.getValue()[0] / totalBytes);
                    out.add(new AllocationSite(
                            e.getKey(), e.getValue()[0], e.getValue()[1], Math.round(pct * 100.0) / 100.0));
                });
        return out;
    }

    private static void addTree(MutableNode target, FlameNode source) {
        if (source == null) {
            return;
        }
        target.value += source.value();
        List<FlameNode> children = source.children();
        if (children == null) {
            return;
        }
        for (FlameNode child : children) {
            addTree(target.child(child.name()), child);
        }
    }

    /**
     * A frame's own sample count is its subtree total minus its children's, which is exactly the
     * number of samples that ended there — the same figure the single-dump parser counts.
     */
    private static List<HotMethod> hotMethods(MutableNode root, long total) {
        Map<String, Long> self = new HashMap<>();
        for (MutableNode child : root.children.values()) {
            collectSelf(child, self);
        }
        List<HotMethod> out = new ArrayList<>();
        self.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .limit(TOP_N)
                .forEach(e -> {
                    double pct = total == 0 ? 0.0 : (100.0 * e.getValue() / total);
                    out.add(new HotMethod(e.getKey(), e.getValue(), Math.round(pct * 100.0) / 100.0));
                });
        return out;
    }

    private static void collectSelf(MutableNode node, Map<String, Long> self) {
        long childTotal = 0;
        for (MutableNode child : node.children.values()) {
            childTotal += child.value;
            collectSelf(child, self);
        }
        long own = node.value - childTotal;
        if (own > 0) {
            self.merge(node.name, own, Long::sum);
        }
    }

    private static final class MutableNode {
        private final String name;
        private long value;
        private final Map<String, MutableNode> children = new HashMap<>();

        private MutableNode(String name) {
            this.name = name;
        }

        private MutableNode child(String childName) {
            return children.computeIfAbsent(childName, MutableNode::new);
        }

        private FlameNode toFlameNode() {
            List<FlameNode> kids = new ArrayList<>(children.size());
            for (MutableNode child : children.values()) {
                kids.add(child.toFlameNode());
            }
            kids.sort(Comparator.comparingLong(FlameNode::value).reversed());
            return new FlameNode(name, value, kids);
        }
    }

    private ProfileMerger() {
    }
}
