package com.jvmavis.collector.jfr;

import com.jvmavis.collector.model.AllocationSite;
import com.jvmavis.collector.model.ExceptionRate;
import com.jvmavis.collector.model.FlameNode;
import com.jvmavis.collector.model.GcPause;
import com.jvmavis.collector.model.GcPauseSummary;
import com.jvmavis.collector.model.HotMethod;
import com.jvmavis.collector.model.LeakCandidate;
import com.jvmavis.collector.model.MonitorEventSummary;
import com.jvmavis.collector.model.ProfileSnapshot;
import com.jvmavis.collector.model.ThreadCpuLoad;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedObject;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads one dump into every view in a single pass.
 *
 * <p>Event types fall into three groups, and they are read differently. Occurrences
 * (execution/allocation samples, GC pauses, monitor events) are filtered against a watermark so
 * each is counted once. Gauges and snapshots (thread load, old objects) are re-reported in full
 * every chunk, so the watermark is bypassed and only the newest batch is kept. Running totals
 * (exception counts) need the first and last reading in range.
 */
public final class JfrDumpParser {
    private static final int TOP_N = 25;

    public ProfileSnapshot emptySnapshot(long collectedAtMs, String dumpFile) {
        return new ProfileSnapshot(
                collectedAtMs, collectedAtMs, collectedAtMs, 0, List.of(),
                new FlameNode("all", 0, List.of()), 0, 0, List.of(),
                new FlameNode("all", 0, List.of()), GcPauseSummary.of(List.of()),
                List.of(), List.of(), List.of(), ExceptionRate.EMPTY, dumpFile);
    }

    public ParsedProfile parse(Path jfrFile, long collectedAtMs, ProfileCursor cursor) throws Exception {
        Map<String, Long> leafCounts = new HashMap<>();
        MutableFlameNode cpuRoot = new MutableFlameNode("all");
        long cpuSamples = 0;

        Map<String, long[]> allocByType = new HashMap<>();
        MutableFlameNode allocRoot = new MutableFlameNode("all");
        long allocSamples = 0;
        long allocBytes = 0;

        List<GcPause> pauses = new ArrayList<>();
        Map<String, double[]> monitorByKey = new LinkedHashMap<>();
        LatestBatch<Long, ThreadCpuLoad> threadCpu = new LatestBatch<>();
        LatestBatch<String, long[]> oldObjects = new LatestBatch<>();
        Counter exceptions = new Counter();

        Instant newestExecution = null;
        Instant newestAllocation = null;
        Instant newestGcPause = null;
        Instant newestMonitor = null;
        Instant windowStart = null;
        Instant windowEnd = null;

        try (RecordingFile recording = new RecordingFile(jfrFile)) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                String type = event.getEventType().getName();
                Instant ts = event.getStartTime();

                if (isExecutionSample(type)) {
                    if (!isCounted(ts, cursor.execution())) {
                        continue;
                    }
                    List<String> stack = javaFramesRootToLeaf(event.getStackTrace());
                    if (!stack.isEmpty()) {
                        leafCounts.merge(stack.get(stack.size() - 1), 1L, Long::sum);
                        addStack(cpuRoot, stack, 1L);
                        cpuSamples++;
                    }
                    newestExecution = later(newestExecution, ts);
                } else if (isAllocationSample(type)) {
                    if (!isCounted(ts, cursor.allocation())) {
                        continue;
                    }
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
                } else if (isGcPause(type)) {
                    if (!isCounted(ts, cursor.gcPause())) {
                        continue;
                    }
                    pauses.add(gcPause(event, ts));
                    newestGcPause = later(newestGcPause, ts);
                } else if (isMonitorEvent(type)) {
                    if (!isCounted(ts, cursor.monitor())) {
                        continue;
                    }
                    accumulateMonitor(monitorByKey, event, type);
                    newestMonitor = later(newestMonitor, ts);
                } else if (isThreadCpuLoad(type)) {
                    // Gauge: no watermark, newest batch wins.
                    RecordedThread thread = event.getThread();
                    if (thread != null) {
                        threadCpu.put(ts, thread.getJavaThreadId(), new ThreadCpuLoad(
                                threadName(thread),
                                thread.getJavaThreadId(),
                                percent(event, "user"),
                                percent(event, "system")));
                    }
                } else if (isOldObjectSample(type)) {
                    // Snapshot: the whole live sample set is re-reported each chunk.
                    accumulateOldObject(oldObjects, event, ts);
                } else if (isExceptionStatistics(type)) {
                    if (event.hasField("throwables")) {
                        exceptions.observe(ts, event.getLong("throwables"));
                    }
                } else {
                    continue;
                }

                windowStart = earlier(windowStart, ts);
                windowEnd = later(windowEnd, ts);
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
                topThreads(threadCpu.values()),
                leakCandidates(oldObjects.entries()),
                monitorSummaries(monitorByKey),
                exceptions.toRate(),
                jfrFile.getFileName().toString());

        return new ParsedProfile(
                snapshot,
                cursor.advance(newestExecution, newestAllocation, newestGcPause, newestMonitor, windowEnd));
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

    private static boolean isMonitorEvent(String type) {
        return "jdk.JavaMonitorEnter".equals(type) || "jdk.JavaMonitorWait".equals(type);
    }

    private static boolean isThreadCpuLoad(String type) {
        return "jdk.ThreadCPULoad".equals(type);
    }

    private static boolean isOldObjectSample(String type) {
        return "jdk.OldObjectSample".equals(type);
    }

    private static boolean isExceptionStatistics(String type) {
        return "jdk.ExceptionStatistics".equals(type);
    }

    private static GcPause gcPause(RecordedEvent event, Instant ts) {
        double ms = event.getDuration().toNanos() / 1_000_000.0;
        String name = event.hasField("name") ? event.getString("name") : "GC Pause";
        long gcId = event.hasField("gcId") ? event.getLong("gcId") : -1;
        return new GcPause(gcId, ts.toEpochMilli(), Math.round(ms * 1000.0) / 1000.0, name);
    }

    private static void accumulateMonitor(
            Map<String, double[]> byKey, RecordedEvent event, String type) {
        String kind = "jdk.JavaMonitorEnter".equals(type)
                ? MonitorEventSummary.BLOCKED
                : MonitorEventSummary.WAITING;
        String monitorClass = "unknown";
        if (event.hasField("monitorClass")) {
            RecordedClass cls = event.getClass("monitorClass");
            if (cls != null) {
                monitorClass = readableClassName(cls.getName());
            }
        }
        double ms = event.getDuration().toNanos() / 1_000_000.0;
        byKey.compute(kind + "\u0000" + monitorClass, (k, v) -> {
            double[] acc = v == null ? new double[3] : v;
            acc[0] += 1;
            acc[1] += ms;
            acc[2] = Math.max(acc[2], ms);
            return acc;
        });
    }

    private static void accumulateOldObject(
            LatestBatch<String, long[]> batch, RecordedEvent event, Instant ts) {
        String type = "unknown";
        try {
            RecordedObject object = event.getValue("object");
            if (object != null && object.hasField("type")) {
                RecordedClass cls = object.getClass("type");
                if (cls != null) {
                    type = readableClassName(cls.getName());
                }
            }
        } catch (RuntimeException ignored) {
            // Old-object struct layout varies between JDKs; the sample is still worth counting.
        }

        List<String> stack = javaFramesRootToLeaf(event.getStackTrace());
        String site;
        if (!stack.isEmpty()) {
            site = stack.get(stack.size() - 1);
        } else {
            RecordedThread thread = event.getThread();
            site = thread == null ? "unknown" : "thread " + threadName(thread);
        }
        long ageSeconds = objectAgeSeconds(event, ts);
        long elements = event.hasField("arrayElements") ? Math.max(0, event.getLong("arrayElements")) : 0;

        String key = type + "\u0000" + site;
        long[] previous = batch.peek(ts, key);
        long[] acc = previous == null ? new long[3] : previous;
        acc[0] += 1;
        acc[1] = Math.max(acc[1], ageSeconds);
        acc[2] += elements;
        batch.put(ts, key, acc);
    }

    private static long objectAgeSeconds(RecordedEvent event, Instant ts) {
        if (event.hasField("objectAge")) {
            try {
                return Math.max(0, event.getDuration("objectAge").toSeconds());
            } catch (RuntimeException ignored) {
                // fall through to allocationTime
            }
        }
        if (event.hasField("allocationTime")) {
            try {
                Instant allocated = event.getInstant("allocationTime");
                if (allocated != null) {
                    return Math.max(0, ts.getEpochSecond() - allocated.getEpochSecond());
                }
            } catch (RuntimeException ignored) {
                // no age available
            }
        }
        return 0;
    }

    private static double percent(RecordedEvent event, String field) {
        if (!event.hasField(field)) {
            return 0.0;
        }
        // JFR reports load as a fraction of one CPU.
        double value = event.getDouble(field) * 100.0;
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static String threadName(RecordedThread thread) {
        String name = thread.getJavaName();
        return name == null || name.isBlank() ? thread.getOSName() : name;
    }

    private static List<ThreadCpuLoad> topThreads(List<ThreadCpuLoad> threads) {
        List<ThreadCpuLoad> out = new ArrayList<>(threads);
        out.sort(Comparator.comparingDouble(
                (ThreadCpuLoad t) -> t.userPercent() + t.systemPercent()).reversed());
        return out.size() > TOP_N ? new ArrayList<>(out.subList(0, TOP_N)) : out;
    }

    private static List<LeakCandidate> leakCandidates(Map<String, long[]> byKey) {
        List<LeakCandidate> out = new ArrayList<>();
        byKey.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[0]).reversed())
                .limit(TOP_N)
                .forEach(e -> {
                    String[] parts = e.getKey().split("\u0000", 2);
                    out.add(new LeakCandidate(
                            parts[0],
                            parts.length > 1 ? parts[1] : "unknown",
                            e.getValue()[0],
                            e.getValue()[1],
                            e.getValue()[2]));
                });
        return out;
    }

    private static List<MonitorEventSummary> monitorSummaries(Map<String, double[]> byKey) {
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

    /**
     * Keeps only entries from the newest timestamp seen. JFR re-reports gauges and old-object
     * samples in full at every chunk boundary, so a bounded dump can contain several batches and
     * only the last one describes the target's current state.
     */
    private static final class LatestBatch<K, V> {
        private final Map<K, V> current = new LinkedHashMap<>();
        private Instant batchAt;

        private void put(Instant ts, K key, V value) {
            if (rollover(ts)) {
                current.put(key, value);
            }
        }

        private V peek(Instant ts, K key) {
            return rollover(ts) ? current.get(key) : null;
        }

        private boolean rollover(Instant ts) {
            if (batchAt == null || ts.isAfter(batchAt)) {
                batchAt = ts;
                current.clear();
                return true;
            }
            return ts.equals(batchAt);
        }

        private List<V> values() {
            return new ArrayList<>(current.values());
        }

        private Map<K, V> entries() {
            return new LinkedHashMap<>(current);
        }
    }

    /** Tracks the first and last reading of a monotonically increasing JVM counter. */
    private static final class Counter {
        private long firstValue;
        private long firstAtMs;
        private long lastValue;
        private long lastAtMs;

        private void observe(Instant ts, long value) {
            long atMs = ts.toEpochMilli();
            if (firstAtMs == 0 || atMs < firstAtMs) {
                firstAtMs = atMs;
                firstValue = value;
            }
            if (atMs >= lastAtMs) {
                lastAtMs = atMs;
                lastValue = value;
            }
        }

        private ExceptionRate toRate() {
            return firstAtMs == 0
                    ? ExceptionRate.EMPTY
                    : ExceptionRate.of(firstValue, firstAtMs, lastValue, lastAtMs);
        }
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
