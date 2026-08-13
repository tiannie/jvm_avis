package com.jvmavis.collector.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jvmavis.collector.config.CollectorConfig;
import com.jvmavis.collector.jmx.TargetMonitor;
import com.jvmavis.collector.model.TargetInfo;
import com.jvmavis.collector.store.MetricStore;
import com.jvmavis.collector.store.ProfileStore;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ApiServer {
    private static final Pattern TARGET_METRICS =
            Pattern.compile("^/api/targets/([^/]+)/metrics$");
    private static final Pattern TARGET_PROFILE =
            Pattern.compile("^/api/targets/([^/]+)/profile$");
    private static final Pattern TARGET_THREADS =
            Pattern.compile("^/api/targets/([^/]+)/threads$");
    private static final Pattern TARGET_ONE =
            Pattern.compile("^/api/targets/([^/]+)$");

    private final CollectorConfig config;
    private final TargetMonitor monitor;
    private final MetricStore metricStore;
    private final ProfileStore profileStore;
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;

    public ApiServer(
            CollectorConfig config,
            TargetMonitor monitor,
            MetricStore metricStore,
            ProfileStore profileStore) {
        this.config = config;
        this.monitor = monitor;
        this.metricStore = metricStore;
        this.profileStore = profileStore;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(config.bindHost(), config.bindPort()), 0);
        server.createContext("/", this::handle);
        server.setExecutor(null);
        server.start();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (path == null || path.isBlank()) {
                path = "/";
            }
            String method = exchange.getRequestMethod();

            if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
                writeResource(exchange, "web/index.html", "text/html; charset=utf-8");
                return;
            }
            if ("GET".equals(method) && path.startsWith("/static/")) {
                String resource = "web" + path.substring("/static".length());
                writeResource(exchange, resource, contentType(resource));
                return;
            }
            if ("GET".equals(method) && "/api/health".equals(path)) {
                writeJson(exchange, 200, Map.of("status", "ok"));
                return;
            }
            if ("GET".equals(method) && "/api/targets".equals(path)) {
                writeJson(exchange, 200, monitor.listTargets());
                return;
            }
            if ("POST".equals(method) && "/api/targets".equals(path)) {
                ObjectNode body = (ObjectNode) mapper.readTree(exchange.getRequestBody());
                String host = text(body, "host");
                int port = body.path("port").asInt(9010);
                String label = body.path("label").asText("");
                if (host == null || host.isBlank()) {
                    writeJson(exchange, 400, Map.of("error", "host is required"));
                    return;
                }
                TargetInfo info = monitor.addTarget(host.trim(), port, label);
                writeJson(exchange, 201, info);
                return;
            }

            Matcher one = TARGET_ONE.matcher(path);
            if (one.matches()) {
                String id = urlDecode(one.group(1));
                if ("DELETE".equals(method)) {
                    boolean removed = monitor.removeTarget(id);
                    writeJson(exchange, removed ? 200 : 404, Map.of("removed", removed));
                    return;
                }
                if ("GET".equals(method)) {
                    Optional<TargetInfo> info = monitor.getTarget(id);
                    if (info.isEmpty()) {
                        writeJson(exchange, 404, Map.of("error", "target not found"));
                        return;
                    }
                    writeJson(exchange, 200, info.get());
                    return;
                }
            }

            Matcher metrics = TARGET_METRICS.matcher(path);
            if ("GET".equals(method) && metrics.matches()) {
                String id = urlDecode(metrics.group(1));
                if (monitor.getTarget(id).isEmpty()) {
                    writeJson(exchange, 404, Map.of("error", "target not found"));
                    return;
                }
                Map<String, String> q = query(exchange);
                Long from = parseLong(q.get("from"));
                Long to = parseLong(q.get("to"));
                writeJson(exchange, 200, metricStore.query(id, from, to));
                return;
            }

            Matcher threads = TARGET_THREADS.matcher(path);
            if ("GET".equals(method) && threads.matches()) {
                String id = urlDecode(threads.group(1));
                if (monitor.getTarget(id).isEmpty()) {
                    writeJson(exchange, 404, Map.of("error", "target not found"));
                    return;
                }
                writeJson(
                        exchange,
                        200,
                        profileStore.threadSeries(id, config.profileWindowSeconds() * 1000L));
                return;
            }

            Matcher profile = TARGET_PROFILE.matcher(path);
            if ("GET".equals(method) && profile.matches()) {
                String id = urlDecode(profile.group(1));
                if (monitor.getTarget(id).isEmpty()) {
                    writeJson(exchange, 404, Map.of("error", "target not found"));
                    return;
                }
                var latest = profileStore.rolling(id, config.profileWindowSeconds() * 1000L);
                // Snapshots only change once per dump interval, but the UI polls far more often.
                // Flame graph trees dominate this response, so skip resending an unchanged one.
                Long since = parseLong(query(exchange).get("since"));
                if (since != null && latest.isPresent() && since == latest.get().timestampMs()) {
                    writeJson(exchange, 200, Map.of("unchanged", true, "timestampMs", since));
                    return;
                }
                if (latest.isEmpty()) {
                    ObjectNode empty = mapper.createObjectNode();
                    empty.putNull("timestampMs");
                    empty.put("totalSamples", 0);
                    empty.putArray("hotMethods");
                    empty.putNull("flameGraph");
                    empty.put("message", "No profile dump yet — wait for the next JFR interval");
                    writeJson(exchange, 200, empty);
                    return;
                }
                writeJson(exchange, 200, latest.get());
                return;
            }

            writeJson(exchange, 404, Map.of("error", "not found"));
        } catch (Exception e) {
            writeJson(exchange, 500, Map.of("error", e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    private void writeResource(HttpExchange exchange, String resource, String contentType) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                writeJson(exchange, 404, Map.of("error", "missing resource " + resource));
                return;
            }
            byte[] bytes = in.readAllBytes();
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private void writeJson(HttpExchange exchange, int status, Object body) throws IOException {
        // Metric series and flame trees are large; indentation is a sizeable share of the bytes.
        byte[] bytes = mapper.writeValueAsBytes(body);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String contentType(String resource) {
        if (resource.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (resource.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (resource.endsWith(".svg")) {
            return "image/svg+xml";
        }
        return "application/octet-stream";
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> out = new HashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String part : raw.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2) {
                out.put(urlDecode(kv[0]), urlDecode(kv[1]));
            }
        }
        return out;
    }

    private static String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Long.parseLong(value);
    }

    private static String text(ObjectNode body, String field) {
        return body.hasNonNull(field) ? body.get(field).asText() : null;
    }
}
