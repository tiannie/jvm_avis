package com.jvmavis.collector.config;

import java.nio.file.Path;
import java.util.Locale;

public record CollectorConfig(
        String bindHost,
        int bindPort,
        long metricIntervalMs,
        int jfrDumpIntervalSeconds,
        int metricRetentionSeconds,
        Path dumpDir,
        String initialTargetHost,
        int initialTargetPort
) {
    public static CollectorConfig fromEnvAndArgs(String[] args) {
        String bindHost = env("JVM_AVIS_HOST", "0.0.0.0");
        int bindPort = envInt("JVM_AVIS_PORT", 8080);
        long metricIntervalMs = envLong("JVM_AVIS_METRIC_INTERVAL_MS", 1000);
        int jfrDumpIntervalSeconds = envInt("JVM_AVIS_JFR_DUMP_INTERVAL_S", 15);
        int metricRetentionSeconds = envInt("JVM_AVIS_RETENTION_S", 3600);
        Path dumpDir = Path.of(env("JVM_AVIS_DUMP_DIR", "target/jfr-dumps"));

        String targetHost = env("JVM_AVIS_TARGET_HOST", null);
        int targetPort = envInt("JVM_AVIS_TARGET_PORT", 9010);

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--host" -> bindHost = args[++i];
                case "--port" -> bindPort = Integer.parseInt(args[++i]);
                case "--target" -> {
                    String[] parts = args[++i].split(":");
                    targetHost = parts[0];
                    if (parts.length > 1) {
                        targetPort = Integer.parseInt(parts[1]);
                    }
                }
                case "--metric-interval-ms" -> metricIntervalMs = Long.parseLong(args[++i]);
                case "--jfr-dump-interval-s" -> jfrDumpIntervalSeconds = Integer.parseInt(args[++i]);
                case "--dump-dir" -> dumpDir = Path.of(args[++i]);
                case "--help", "-h" -> {
                    printHelp();
                    System.exit(0);
                }
                default -> throw new IllegalArgumentException("Unknown arg: " + args[i]);
            }
        }

        return new CollectorConfig(
                bindHost,
                bindPort,
                metricIntervalMs,
                jfrDumpIntervalSeconds,
                metricRetentionSeconds,
                dumpDir,
                targetHost,
                targetPort);
    }

    private static void printHelp() {
        System.out.println("""
                jvm-avis collector

                  --host <addr>                 Bind host (default 0.0.0.0)
                  --port <port>                 Bind port (default 8080)
                  --target <host:port>          Auto-register JMX target
                  --metric-interval-ms <ms>     Metric scrape interval (default 1000)
                  --jfr-dump-interval-s <s>     JFR stream dump interval (default 15)
                  --dump-dir <path>             Local dir for temporary .jfr dumps
                """);
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static int envInt(String key, int defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value.trim());
    }

    private static long envLong(String key, long defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : Long.parseLong(value.trim());
    }

    public String recordingName() {
        return env("JVM_AVIS_RECORDING_NAME", "jvm-avis").toLowerCase(Locale.ROOT);
    }
}
