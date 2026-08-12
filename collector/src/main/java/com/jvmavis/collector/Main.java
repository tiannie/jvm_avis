package com.jvmavis.collector;

import com.jvmavis.collector.api.ApiServer;
import com.jvmavis.collector.config.CollectorConfig;
import com.jvmavis.collector.jmx.TargetMonitor;
import com.jvmavis.collector.store.MetricStore;
import com.jvmavis.collector.store.ProfileStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class Main {
    public static void main(String[] args) throws Exception {
        CollectorConfig config = CollectorConfig.fromEnvAndArgs(args);
        Files.createDirectories(config.dumpDir());

        MetricStore metricStore = new MetricStore(config.metricRetentionSeconds());
        ProfileStore profileStore = new ProfileStore();
        TargetMonitor monitor = new TargetMonitor(config, metricStore, profileStore);

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "jvm-avis-scheduler");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(
                () -> safeRun("metric-sample", monitor::sampleAllMetrics),
                0,
                config.metricIntervalMs(),
                TimeUnit.MILLISECONDS);

        scheduler.scheduleAtFixedRate(
                () -> safeRun("jfr-dump", monitor::dumpAllProfiles),
                config.jfrDumpIntervalSeconds(),
                config.jfrDumpIntervalSeconds(),
                TimeUnit.SECONDS);

        ApiServer api = new ApiServer(config, monitor, metricStore, profileStore);
        api.start();

        if (config.initialTargetHost() != null) {
            monitor.addTarget(config.initialTargetHost(), config.initialTargetPort(), "default");
            System.out.printf(
                    "Auto-registered target %s:%d%n",
                    config.initialTargetHost(),
                    config.initialTargetPort());
        }

        System.out.printf(
                "jvm-avis collector listening on http://%s:%d%n",
                config.bindHost(),
                config.bindPort());
        System.out.printf(
                "metric interval=%dms jfr dump interval=%ds profile window=%ds retention=%ds dumpDir=%s%n",
                config.metricIntervalMs(),
                config.jfrDumpIntervalSeconds(),
                config.profileWindowSeconds(),
                config.metricRetentionSeconds(),
                config.dumpDir().toAbsolutePath());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scheduler.shutdownNow();
            monitor.close();
            api.stop();
        }));

        Thread.currentThread().join();
    }

    private static void safeRun(String name, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            System.err.println(name + " failed: " + e.getMessage());
        }
    }

    private Main() {
    }
}
