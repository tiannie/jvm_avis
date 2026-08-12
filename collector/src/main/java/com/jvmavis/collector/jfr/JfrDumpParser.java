package com.jvmavis.collector.jfr;

import com.jvmavis.collector.model.AllocationSite;
import com.jvmavis.collector.model.FlameNode;
import com.jvmavis.collector.model.GcPause;
import com.jvmavis.collector.model.GcPauseSummary;
import com.jvmavis.collector.model.HotMethod;
import com.jvmavis.collector.model.ProfileSnapshot;
import jdk.jfr.consumer.RecordedClass;
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

/**
 * Reads one dump into the CPU, allocation and GC-pause views in a single pass.
 */
public final class JfrDumpParser {
    private static final int TOP_N = 25;

    public ProfileSnapshot emptySnapshot(long collectedAtMs, String dumpFile) {
        return new ProfileSnapshot(
                collectedAtMs, collectedAtMs, collectedAtMs, 0, List.of(),
                new FlameNode("all", 0, List.of()), 0, 0, List.of(),
                new FlameNode("all", 0, List.of()), GcPauseSummary.of(List.of()), dumpFile);
    }

    /**
     * @param cursor per-type watermarks; events at or before a type's watermark are skipped. The
     *               stream is delivered at chunk granularity, so a dump normally re-includes events
     *               the previous one already counted.
     */
    public ParsedProfile parse(Path jfrFile, long collectedAtMs, ProfileCursor cursor) throws Exception {
        Map<String, Long> leafCounts = new HashMap<>();
        MutableFlameNode cpuRoot = new MutableFlameNode("all");
        long cpuSamples = 0;

        Map<String, long[]> allocByType = new HashMap<>();
        MutableFlameNode allocRoot = new MutableFlameNode("all");
        long allocSamples = 0;
        long allocBytes = 0;

        List<GcPause> pauses = new ArrayList<>();

        Instant newestExecution = null;
        Instant newestAllocation = null;
        Instant newestGcPause = null;
        Instant windowStart = null;
        Instant windowEnd = null;

        try (RecordingFile recording = new RecordingFile(jfrFile)) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                String type = event.getEventType().getName();
                Instant ts = event.getStartTime();

                if (isExecutionSample(type)) {
                    if (isCounted(ts, cursor.execution())) {
                        List<String> stack = javaFramesRootToLeaf(event.getStackTrace());
                        if (!stack.isEmpty()) {
                            leafCounts.merge(stack.get(stack.size() - 1), 1L, Long::sum);
                            addStack(cpuRoot, stack, 1L);
                            cpuSamples++;
                        }
                        newestExecution = later(newestExecution, ts);
                        windowStart = earlier(windowStart, ts);
                        windowEnd = later(windowEnd, ts);
                    }
                } else if (isAllocationSample(type)) {
                    if (isCounted(ts, cursor.allocation())) {
                        long weight = event.hasField("weight") ? event.getLong("weight") : 0L;
                        allocByType.compute(allocationType(event), (k, v) -> {
                            long[] acc = v == null ? new long[2] : v;
                            acc[0] += weight;
                            acc[1]++;
                            return acc;
                        });
                        List<String> stack = javaFramesRootToLeaf(event.getStackTrace());
                        if (!stack.isEmpty() && weight > 0) {
                            addStack(allocRoot, stack, weight);
                        }
                        allocSamples++;
                        allocBytes += weight;
                        newestAllocation = later(newestAllocation, ts);
                        windowStart = earlier(windowStart, ts);
                        windowEnd = later(windowEnd, ts);
                    }
                } else if (isGcPause(type)) {
                    if (isCounted(ts, cursor.gcPause())) {
                        double ms = event.getDuration().toNanos() / 1_000_000.0;
                        String name = event.hasField("name") ? event.getString("name") : "GC Pause";
                        long gcId = event.hasField("gcId") ? event.getLong("gcId") : -1;
                        pauses.add(new GcPause(
                                gcId, ts.toEpochMilli(), Math.round(ms * 1000.0) / 1000.0, name));
                        newestGcPause = later(newestGcPause, ts);
                        windowStart = earlier(windowStart, ts);
                        windowEnd = later(windowEnd, ts);
                    }
                }
            }
        }

        long startMs = windowStart == null ? collectedAtMs : windowStart.toEpochMilli();
        long endMs = windowEnd == null ? collectedAtMs : windowEnd.toEpochMilli();

        ProfileSnapshot snapshot = new ProfileSnapshot(
                collectedAtMs,
                startMs,
                endMs,
                cpuSamples,
                hotMethods(leafCounts, cpuSamples),
                cpuRoot.toFlameNode(),
                allocSamples,
                allocBytes,
                topAllocations(allocByType, allocBytes),
                allocRoot.toFlameNode(),
                GcPauseSummary.of(pauses),
                jfrFile.getFileName().toString());

        return new ParsedProfile(
                snapshot,
                cursor.advance(newestExecution, newestAllocation, newestGcPause, windowEnd));
    }

    private static boolean isCounted(Instant ts, Instant watermark) {
        return watermark == null || ts.isAfter(watermark);
    }

    private static boolean isExecutionSample(String type) {
        // Prefer Java execution samples; native poll/accept noise dominates otherwise.
        return "jdk.ExecutionSample".equals(type)
                || "com.oracle.jdk.ExecutionSample".equals(type);
    }

    /** JDK 16+ emits this under the default settings; the older TLAB events are not used. */
    private static boolean isAllocationSample(String type) {
        return "jdk.ObjectAllocationSample".equals(type);
    }

    /** Only the top-level pause; the Level1/Level2 sub-phases nest inside it. */
    private static boolean isGcPause(String type) {
        return "jdk.GCPhasePause".equals(type);
    }

    private static String allocationType(RecordedEvent event) {
        if (!event.hasField("objectClass")) {
            return "unknown";
        }
        RecordedClass type = event.getClass("objectClass");
        return type == null ? "unknown" : readableClassName(type.getName());
    }

    /** Arrays arrive as JVM descriptors ({@code [B}, {@code [Ljava.lang.String;}). */
    private static String readableClassName(String name) {
        if (name == null || name.isEmpty() || name.charAt(0) != '[') {
            return name;
        }
        int dimensions = 0;
        while (dimensions < name.length() && name.charAt(dimensions) == '[') {
            dimensions++;
        }
        String element = name.substring(dimensions);
        String base = switch (element.isEmpty() ? ' ' : element.charAt(0)) {
            case 'B' -> "byte";
            case 'C' -> "char";
            case 'D' -> "double";
            case 'F' -> "float";
            case 'I' -> "int";
            case 'J' -> "long";
            case 'S' -> "short";
            case 'Z' -> "boolean";
            case 'L' -> element.substring(1, element.endsWith(";") ? element.length() - 1 : element.length());
            default -> element;
        };
        return base + "[]".repeat(dimensions);
    }

    private static List<HotMethod> hotMethods(Map<String, Long> counts, long total) {
        List<HotMethod> out = new ArrayList<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .limit(TOP_N)
                .forEach(e -> {
                    double pct = total == 0 ? 0.0 : (100.0 * e.getValue() / total);
                    out.add(new HotMethod(e.getKey(), e.getValue(), round2(pct)));
                });
        return out;
    }

    private static List<AllocationSite> topAllocations(Map<String, long[]> byType, long totalBytes) {
        List<AllocationSite> out = new ArrayList<>();
        byType.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[0]).reversed())
                .limit(TOP_N)
                .forEach(e -> {
                    double pct = totalBytes == 0 ? 0.0 : (100.0 * e.getValue()[0] / totalBytes);
                    out.add(new AllocationSite(e.getKey(), e.getValue()[0], e.getValue()[1], round2(pct)));
                });
        return out;
    }

    private static Instant later(Instant current, Instant candidate) {
        return current == null || candidate.isAfter(current) ? candidate : current;
    }

    private static Instant earlier(Instant current, Instant candidate) {
        return current == null || candidate.isBefore(current) ? candidate : current;
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

    private static void addStack(MutableFlameNode root, List<String> rootToLeaf, long weight) {
        MutableFlameNode node = root;
        node.value += weight;
        for (String frame : rootToLeaf) {
            node = node.child(frame);
            node.value += weight;
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
