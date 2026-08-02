package com.jvmavis.collector.jfr;

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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class JfrHotMethodParser {
    private static final int TOP_N = 25;

    public ProfileSnapshot parse(Path jfrFile, long collectedAtMs) throws Exception {
        Map<String, Long> counts = new HashMap<>();
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
                if (windowStart == null || ts.isBefore(windowStart)) {
                    windowStart = ts;
                }
                if (windowEnd == null || ts.isAfter(windowEnd)) {
                    windowEnd = ts;
                }
                String method = topFrame(event.getStackTrace());
                if (method == null) {
                    continue;
                }
                counts.merge(method, 1L, Long::sum);
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
        return new ProfileSnapshot(
                collectedAtMs,
                startMs,
                endMs,
                total,
                hot,
                jfrFile.getFileName().toString());
    }

    private static boolean isExecutionSample(String type) {
        // Prefer Java execution samples; native poll/accept noise dominates otherwise.
        return "jdk.ExecutionSample".equals(type)
                || "com.oracle.jdk.ExecutionSample".equals(type);
    }

    private static String topFrame(RecordedStackTrace stack) {
        if (stack == null) {
            return null;
        }
        List<RecordedFrame> frames = stack.getFrames();
        if (frames == null || frames.isEmpty()) {
            return null;
        }
        for (RecordedFrame frame : frames) {
            if (frame == null || !frame.isJavaFrame()) {
                continue;
            }
            RecordedMethod method = frame.getMethod();
            if (method == null || method.getType() == null) {
                continue;
            }
            String typeName = method.getType().getName();
            String methodName = method.getName();
            return typeName + "." + methodName;
        }
        return null;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
