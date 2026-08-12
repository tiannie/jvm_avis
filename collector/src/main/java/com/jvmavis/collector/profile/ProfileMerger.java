package com.jvmavis.collector.profile;

import com.jvmavis.collector.model.FlameNode;
import com.jvmavis.collector.model.HotMethod;
import com.jvmavis.collector.model.ProfileSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
            if (snapshot != null && snapshot.flameGraph() != null && snapshot.totalSamples() > 0) {
                usable.add(snapshot);
            }
        }
        if (usable.isEmpty()) {
            return snapshots.isEmpty() ? null : snapshots.get(snapshots.size() - 1);
        }
        if (usable.size() == 1) {
            return usable.get(0);
        }

        MutableNode root = new MutableNode(usable.get(0).flameGraph().name());
        long total = 0;
        long windowStart = Long.MAX_VALUE;
        long windowEnd = Long.MIN_VALUE;
        long timestamp = Long.MIN_VALUE;
        String dumpFile = null;

        for (ProfileSnapshot snapshot : usable) {
            addTree(root, snapshot.flameGraph());
            total += snapshot.totalSamples();
            windowStart = Math.min(windowStart, snapshot.windowStartMs());
            windowEnd = Math.max(windowEnd, snapshot.windowEndMs());
            if (snapshot.timestampMs() >= timestamp) {
                timestamp = snapshot.timestampMs();
                dumpFile = snapshot.dumpFile();
            }
        }

        return new ProfileSnapshot(
                timestamp,
                windowStart,
                windowEnd,
                total,
                hotMethods(root, total),
                root.toFlameNode(),
                dumpFile);
    }

    private static void addTree(MutableNode target, FlameNode source) {
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
