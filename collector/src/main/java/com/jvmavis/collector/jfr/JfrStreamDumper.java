package com.jvmavis.collector.jfr;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import javax.management.openmbean.TabularData;
import javax.management.openmbean.TabularDataSupport;
import javax.management.openmbean.TabularType;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Pulls a bounded window from an always-on remote Flight Recorder via FlightRecorderMXBean streams.
 */
public final class JfrStreamDumper {
    private static final ObjectName FLIGHT_RECORDER;
    private static final TabularType STRING_MAP_TYPE;

    static {
        try {
            FLIGHT_RECORDER = new ObjectName("jdk.management.jfr:type=FlightRecorder");
            STRING_MAP_TYPE = stringMapType();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final String preferredRecordingName;

    public JfrStreamDumper(String preferredRecordingName) {
        this.preferredRecordingName = preferredRecordingName == null
                ? "jvm-avis"
                : preferredRecordingName.toLowerCase(Locale.ROOT);
    }

    public Path dumpToFile(MBeanServerConnection mbsc, Path outputFile) throws Exception {
        if (!mbsc.isRegistered(FLIGHT_RECORDER)) {
            throw new IllegalStateException(
                    "FlightRecorderMXBean not registered — target JRE may lack JFR or management modules");
        }

        long recordingId = findRunningRecordingId(mbsc);
        Long streamId = null;
        Long cloneId = null;
        try {
            // Clone a stopped snapshot so openStream has a finite, consistent window.
            cloneId = (Long) mbsc.invoke(
                    FLIGHT_RECORDER,
                    "cloneRecording",
                    new Object[]{recordingId, true},
                    new String[]{"long", "boolean"});

            Map<String, String> options = new HashMap<>();
            options.put("blockSize", "1048576");
            streamId = (Long) mbsc.invoke(
                    FLIGHT_RECORDER,
                    "openStream",
                    new Object[]{cloneId, toTabularData(options)},
                    new String[]{"long", "javax.management.openmbean.TabularData"});

            Files.createDirectories(outputFile.getParent());
            try (OutputStream out = Files.newOutputStream(outputFile)) {
                while (true) {
                    byte[] chunk = (byte[]) mbsc.invoke(
                            FLIGHT_RECORDER,
                            "readStream",
                            new Object[]{streamId},
                            new String[]{"long"});
                    if (chunk == null) {
                        break;
                    }
                    out.write(chunk);
                }
            }
            return outputFile;
        } finally {
            if (streamId != null) {
                safeInvoke(mbsc, "closeStream", new Object[]{streamId}, new String[]{"long"});
            }
            if (cloneId != null) {
                safeInvoke(mbsc, "closeRecording", new Object[]{cloneId}, new String[]{"long"});
            }
        }
    }

    private long findRunningRecordingId(MBeanServerConnection mbsc) throws Exception {
        CompositeData[] recordings = toCompositeArray(mbsc.getAttribute(FLIGHT_RECORDER, "Recordings"));
        if (recordings.length == 0) {
            throw new IllegalStateException(
                    "No JFR recordings found. Start the target with -XX:StartFlightRecording=name=jvm-avis,...");
        }

        Long preferred = null;
        Long anyRunning = null;
        for (CompositeData rec : recordings) {
            long id = ((Number) rec.get("id")).longValue();
            String name = String.valueOf(rec.get("name")).toLowerCase(Locale.ROOT);
            String state = String.valueOf(rec.get("state")).toUpperCase(Locale.ROOT);
            boolean running = state.contains("RUNNING");
            if (running) {
                anyRunning = id;
                if (name.contains(preferredRecordingName)) {
                    preferred = id;
                }
            }
        }
        if (preferred != null) {
            return preferred;
        }
        if (anyRunning != null) {
            return anyRunning;
        }
        return ((Number) recordings[recordings.length - 1].get("id")).longValue();
    }

    private static CompositeData[] toCompositeArray(Object raw) {
        if (raw == null) {
            return new CompositeData[0];
        }
        if (raw instanceof CompositeData[] arr) {
            return arr;
        }
        if (raw instanceof Object[] arr) {
            CompositeData[] out = new CompositeData[arr.length];
            for (int i = 0; i < arr.length; i++) {
                out[i] = (CompositeData) arr[i];
            }
            return out;
        }
        if (raw instanceof java.util.List<?> list) {
            return list.stream().map(CompositeData.class::cast).toArray(CompositeData[]::new);
        }
        throw new IllegalStateException("Unexpected Recordings attribute type: " + raw.getClass().getName());
    }

    private static TabularData toTabularData(Map<String, String> map) throws Exception {
        TabularDataSupport table = new TabularDataSupport(STRING_MAP_TYPE);
        CompositeType rowType = STRING_MAP_TYPE.getRowType();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            table.put(new CompositeDataSupport(
                    rowType,
                    new String[]{"key", "value"},
                    new Object[]{entry.getKey(), entry.getValue()}));
        }
        return table;
    }

    private static TabularType stringMapType() throws Exception {
        String typeName = "java.util.Map<java.lang.String, java.lang.String>";
        String[] itemNames = {"key", "value"};
        OpenType<?>[] itemTypes = {SimpleType.STRING, SimpleType.STRING};
        CompositeType rowType = new CompositeType(typeName, typeName, itemNames, itemNames, itemTypes);
        return new TabularType(typeName, typeName, rowType, new String[]{"key"});
    }

    private static void safeInvoke(
            MBeanServerConnection mbsc, String op, Object[] args, String[] sig) {
        try {
            mbsc.invoke(FLIGHT_RECORDER, op, args, sig);
        } catch (Exception ignored) {
            // best effort cleanup
        }
    }
}
