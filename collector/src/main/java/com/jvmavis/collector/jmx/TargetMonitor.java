package com.jvmavis.collector.jmx;

import com.jvmavis.collector.config.CollectorConfig;
import com.jvmavis.collector.jfr.JfrDumpParser;
import com.jvmavis.collector.jfr.JfrStreamDumper;
import com.jvmavis.collector.jfr.ParsedProfile;
import com.jvmavis.collector.jfr.ProfileCursor;
import com.jvmavis.collector.model.MetricSample;
import com.jvmavis.collector.model.ProfileSnapshot;
import com.jvmavis.collector.model.TargetInfo;
import com.jvmavis.collector.store.MetricStore;
import com.jvmavis.collector.store.ProfileStore;
import com.jvmavis.collector.store.ThreadStateStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TargetMonitor {
    private final CollectorConfig config;
    private final MetricStore metricStore;
    private final ProfileStore profileStore;
    private final ThreadStateStore threadStateStore;
    private final MetricScraper scraper = new MetricScraper();
    private final JfrStreamDumper dumper;
    private final JfrDumpParser parser = new JfrDumpParser();
    private final Map<String, MonitoredTarget> targets = new ConcurrentHashMap<>();

    public TargetMonitor(
            CollectorConfig config,
            MetricStore metricStore,
            ProfileStore profileStore,
            ThreadStateStore threadStateStore) {
        this.config = config;
        this.metricStore = metricStore;
        this.profileStore = profileStore;
        this.threadStateStore = threadStateStore;
        this.dumper = new JfrStreamDumper(config.recordingName());
    }

    public TargetInfo addTarget(String host, int port, String label) {
        String id = shortId(host, port);
        MonitoredTarget existing = targets.get(id);
        if (existing != null) {
            return existing.toInfo();
        }
        MonitoredTarget target = new MonitoredTarget(id, host, port, label);
        targets.put(id, target);
        return target.toInfo();
    }

    public boolean removeTarget(String id) {
        MonitoredTarget removed = targets.remove(id);
        if (removed == null) {
            return false;
        }
        removed.close();
        metricStore.remove(id);
        profileStore.remove(id);
        threadStateStore.remove(id);
        return true;
    }

    public List<TargetInfo> listTargets() {
        List<TargetInfo> out = new ArrayList<>();
        for (MonitoredTarget target : targets.values()) {
            out.add(target.toInfo());
        }
        out.sort((a, b) -> a.id().compareToIgnoreCase(b.id()));
        return out;
    }

    public Optional<TargetInfo> getTarget(String id) {
        MonitoredTarget target = targets.get(id);
        return target == null ? Optional.empty() : Optional.of(target.toInfo());
    }

    public void sampleAllMetrics() {
        for (MonitoredTarget target : targets.values()) {
            try {
                JmxConnection conn = target.ensureConnected();
                MetricScraper.ScrapeResult result = scraper.scrape(conn.connection());
                MetricSample sample = result.sample();
                metricStore.add(target.id(), sample);
                threadStateStore.add(target.id(), sample.timestampMs(), result.threadStates());
                target.markMetricOk(sample.timestampMs());
            } catch (Exception e) {
                target.markError(e.getMessage());
            }
        }
    }

    public void dumpAllProfiles() {
        for (MonitoredTarget target : targets.values()) {
            Path dumpFile = config.dumpDir().resolve(
                    target.id() + "-" + System.currentTimeMillis() + ".jfr");
            try {
                JmxConnection conn = target.ensureConnected();
                ProfileCursor cursor = target.profileCursor();
                dumper.dumpToFile(conn.connection(), dumpFile, streamFrom(cursor));
                ParsedProfile parsed = parser.parse(dumpFile, System.currentTimeMillis(), cursor);
                ProfileSnapshot snapshot = parsed.snapshot();
                profileStore.add(target.id(), snapshot);
                target.markProfileOk(snapshot.timestampMs(), parsed.cursor());
            } catch (Throwable e) {
                // Throwable, not Exception: a dump large enough to exhaust the heap throws
                // OutOfMemoryError, which would otherwise kill the scheduler thread silently.
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                target.markError("JFR dump: " + msg);
                System.err.printf(
                        "JFR dump failed for %s (%s, dump %d bytes)%n",
                        target.id(), e.getClass().getName(), fileSize(dumpFile));
                e.printStackTrace();
            } finally {
                try {
                    Files.deleteIfExists(dumpFile);
                } catch (Exception ignored) {
                    // keep dump on delete failure for debugging
                }
            }
        }
    }

    /**
     * The very first dump has no watermark to work from. Reaching back over the recording's whole
     * {@code maxage} would populate the UI in one go, but it also transfers and parses the entire
     * ring — enough to exhaust the collector's heap on a busy target, and a failed dump leaves the
     * watermark unset so the next attempt is just as large. Starting one interval back instead
     * makes startup cost the same as steady state; the window fills in as dumps accumulate.
     *
     * <p>This instant is read by the target, so it assumes the two clocks are roughly aligned.
     * Skew only shifts the first window, which the watermark corrects from the next dump onwards.
     */
    private static long fileSize(Path file) {
        try {
            return Files.size(file);
        } catch (Exception e) {
            return -1;
        }
    }

    private Instant streamFrom(ProfileCursor cursor) {
        Instant watermark = cursor.earliest();
        return watermark != null
                ? watermark
                : Instant.now().minusSeconds(config.jfrDumpIntervalSeconds());
    }

    public void close() {
        for (MonitoredTarget target : targets.values()) {
            target.close();
        }
        targets.clear();
    }

    private static String shortId(String host, int port) {
        String raw = host + ":" + port;
        String uuid = UUID.nameUUIDFromBytes(raw.getBytes()).toString().substring(0, 8);
        return host.replace('.', '-') + "-" + port + "-" + uuid;
    }
}
