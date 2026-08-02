package com.jvmavis.collector.jmx;

import com.jvmavis.collector.config.CollectorConfig;
import com.jvmavis.collector.jfr.JfrHotMethodParser;
import com.jvmavis.collector.jfr.JfrStreamDumper;
import com.jvmavis.collector.model.MetricSample;
import com.jvmavis.collector.model.ProfileSnapshot;
import com.jvmavis.collector.model.TargetInfo;
import com.jvmavis.collector.store.MetricStore;
import com.jvmavis.collector.store.ProfileStore;

import java.nio.file.Files;
import java.nio.file.Path;
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
    private final MetricScraper scraper = new MetricScraper();
    private final JfrStreamDumper dumper;
    private final JfrHotMethodParser parser = new JfrHotMethodParser();
    private final Map<String, MonitoredTarget> targets = new ConcurrentHashMap<>();

    public TargetMonitor(CollectorConfig config, MetricStore metricStore, ProfileStore profileStore) {
        this.config = config;
        this.metricStore = metricStore;
        this.profileStore = profileStore;
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
                MetricSample sample = scraper.scrape(conn.connection());
                metricStore.add(target.id(), sample);
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
                dumper.dumpToFile(conn.connection(), dumpFile);
                ProfileSnapshot snapshot = parser.parse(dumpFile, System.currentTimeMillis());
                profileStore.add(target.id(), snapshot);
                target.markProfileOk(snapshot.timestampMs());
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                target.markError("JFR dump: " + msg);
                System.err.println("JFR dump failed for " + target.id() + ": " + msg);
            } finally {
                try {
                    Files.deleteIfExists(dumpFile);
                } catch (Exception ignored) {
                    // keep dump on delete failure for debugging
                }
            }
        }
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
