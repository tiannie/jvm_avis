package com.jvmavis.collector.jfr;

import com.jvmavis.collector.model.FlameNode;
import com.jvmavis.collector.model.HotMethod;
import com.jvmavis.collector.model.ProfileSnapshot;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class JfrHotMethodParser {
    private static final int TOP_N = 25;

    /**
     * @param after when non-null, events at or before this instant are ignored. A bounded stream is
     *              delivered at chunk granularity, so the file normally re-includes samples the
     *              previous dump already counted.
     */
    public ParsedProfile parse(Path jfrFile, long collectedAtMs, Instant after) throws Exception {
        Map<String, Long> counts = new HashMap<>();
        MutableFlameNode flameRoot = new MutableFlameNode("all");
        long total = 0;
        Instant windowStart = null;
        Instant windowEnd = null;

        try (RecordingFile recording = new RecordingFile(jfrFile)) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                String type = event.getEventType().getName();
                if (!isExecutionSample(type)) {
                    continue;
                }
                Instant ts = event.getStartTime();
                if (after != null && !ts.isAfter(after)) {
                    continue;
                }
                if (windowStart == null || ts.isBefore(windowStart)) {
                    windowStart = ts;
                }
                if (windowEnd == null || ts.isAfter(windowEnd)) {
                    windowEnd = ts;
                }
                List<String> stack = javaFramesRootToLeaf(event.getStackTrace());
                if (stack.isEmpty()) {
                    continue;
                }
                String leaf = stack.get(stack.size() - 1);
                counts.merge(leaf, 1L, Long::sum);
                addStack(flameRoot, stack);
                total++;
            }
        }

        final long sampleTotal = total;
        List<HotMethod> hot = new ArrayList<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .limit(TOP_N)
                .forEach(e -> {
                    double pct = sampleTotal == 0 ? 0.0 : (100.0 * e.getValue() / sampleTotal);
                    hot.add(new HotMethod(e.getKey(), e.getValue(), round2(pct)));
                });

        long startMs = windowStart == null ? collectedAtMs : windowStart.toEpochMilli();
        long endMs = windowEnd == null ? collectedAtMs : windowEnd.toEpochMilli();
        ProfileSnapshot snapshot = new ProfileSnapshot(
                collectedAtMs,
                startMs,
                endMs,
                total,
                hot,
                flameRoot.toFlameNode(),
                jfrFile.getFileName().toString());
        return new ParsedProfile(snapshot, windowEnd);
    }

    private static boolean isExecutionSample(String type) {
        // Prefer Java execution samples; native poll/accept noise dominates otherwise.
        return "jdk.ExecutionSample".equals(type)
                || "com.oracle.jdk.ExecutionSample".equals(type);
    }

    /** JFR frames are leaf→root; reverse to root→leaf for flame graphs. */
    private static List<String> javaFramesRootToLeaf(RecordedStackTrace stack) {
        if (stack == null) {
            return List.of();
        }
        List<RecordedFrame> frames = stack.getFrames();
        if (frames == null || frames.isEmpty()) {
            return List.of();
        }
        List<String> leafToRoot = new ArrayList<>();
        for (RecordedFrame frame : frames) {
            String name = frameName(frame);
            if (name != null) {
                leafToRoot.add(name);
            }
        }
        if (leafToRoot.isEmpty()) {
            return List.of();
        }
        Collections.reverse(leafToRoot);
        return leafToRoot;
    }

    private static String frameName(RecordedFrame frame) {
        if (frame == null || !frame.isJavaFrame()) {
            return null;
        }
        RecordedMethod method = frame.getMethod();
        if (method == null || method.getType() == null) {
            return null;
        }
        return method.getType().getName() + "." + method.getName();
    }

    private static void addStack(MutableFlameNode root, List<String> rootToLeaf) {
        MutableFlameNode node = root;
        node.value++;
        for (String frame : rootToLeaf) {
            node = node.child(frame);
            node.value++;
        }
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static final class MutableFlameNode {
        private final String name;
        private long value;
        private final Map<String, MutableFlameNode> children = new HashMap<>();

        private MutableFlameNode(String name) {
            this.name = name;
        }

        private MutableFlameNode child(String childName) {
            return children.computeIfAbsent(childName, MutableFlameNode::new);
        }

        private FlameNode toFlameNode() {
            List<FlameNode> kids = new ArrayList<>(children.size());
            for (MutableFlameNode child : children.values()) {
                kids.add(child.toFlameNode());
            }
            kids.sort(Comparator.comparingLong(FlameNode::value).reversed());
            return new FlameNode(name, value, kids);
        }
    }
}
