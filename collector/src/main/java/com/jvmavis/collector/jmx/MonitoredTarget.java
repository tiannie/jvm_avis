package com.jvmavis.collector.jmx;

import com.jvmavis.collector.model.TargetInfo;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

final class MonitoredTarget {
    private final String id;
    private final String host;
    private final int port;
    private final String label;
    private final AtomicReference<String> status = new AtomicReference<>("NEW");
    private final AtomicReference<String> lastError = new AtomicReference<>(null);
    private final AtomicReference<Long> lastMetricAtMs = new AtomicReference<>(null);
    private final AtomicReference<Long> lastProfileAtMs = new AtomicReference<>(null);
    /** Newest sample already counted, in the target's clock. Bounds the next stream. */
    private final AtomicReference<Instant> profileCursor = new AtomicReference<>(null);
    private volatile JmxConnection connection;

    MonitoredTarget(String id, String host, int port, String label) {
        this.id = id;
        this.host = host;
        this.port = port;
        this.label = label == null || label.isBlank() ? host + ":" + port : label;
    }

    String id() {
        return id;
    }

    String host() {
        return host;
    }

    int port() {
        return port;
    }

    synchronized JmxConnection ensureConnected() throws Exception {
        if (connection != null) {
            try {
                connection.connection().getMBeanCount();
                return connection;
            } catch (Exception e) {
                connection.close();
                connection = null;
            }
        }
        connection = JmxConnection.connect(host, port);
        status.set("CONNECTED");
        lastError.set(null);
        return connection;
    }

    synchronized void close() {
        if (connection != null) {
            connection.close();
            connection = null;
        }
        status.set("CLOSED");
    }

    void markMetricOk(long ts) {
        lastMetricAtMs.set(ts);
        if (!"ERROR".equals(status.get())) {
            status.set("OK");
        }
    }

    Instant profileCursor() {
        return profileCursor.get();
    }

    /**
     * @param newestSample newest counted sample, or null when the dump held none. Leaving the
     *                     cursor put on an empty dump makes the next stream re-cover the gap.
     */
    void markProfileOk(long ts, Instant newestSample) {
        lastProfileAtMs.set(ts);
        if (newestSample != null) {
            profileCursor.set(newestSample);
        }
        status.set("OK");
        lastError.set(null);
    }

    void markError(String message) {
        status.set("ERROR");
        lastError.set(message);
    }

    TargetInfo toInfo() {
        return new TargetInfo(
                id,
                host,
                port,
                label,
                status.get(),
                lastError.get(),
                lastMetricAtMs.get(),
                lastProfileAtMs.get());
    }
}
