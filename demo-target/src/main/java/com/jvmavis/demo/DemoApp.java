package com.jvmavis.demo;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Small HotSpot workload with always-on bounded JFR expected via JVM flags.
 */
public final class DemoApp {
    private static final AtomicLong COUNTER = new AtomicLong();
    private static final List<byte[]> CHURN = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("DEMO_HTTP_PORT", "8081"));
        ExecutorService workers = Executors.newFixedThreadPool(4);
        workers.submit(DemoApp::cpuBurner);
        workers.submit(DemoApp::allocator);
        workers.submit(DemoApp::allocator);
        workers.submit(DemoApp::waiter);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", exchange -> write(exchange, 200, "ok\n"));
        server.createContext("/work", exchange -> {
            busyWork(5_000_000);
            write(exchange, 200, "worked counter=" + COUNTER.incrementAndGet() + "\n");
        });
        server.start();
        System.out.println("demo-target http://127.0.0.1:" + port + " (JMX expected on 9010 via launch flags)");
    }

    private static void cpuBurner() {
        while (true) {
            busyWork(2_000_000 + ThreadLocalRandom.current().nextInt(2_000_000));
            sleep(50);
        }
    }

    private static void allocator() {
        while (true) {
            byte[] chunk = new byte[32_768 + ThreadLocalRandom.current().nextInt(65_536)];
            ThreadLocalRandom.current().nextBytes(chunk);
            synchronized (CHURN) {
                CHURN.add(chunk);
                if (CHURN.size() > 200) {
                    CHURN.subList(0, 50).clear();
                }
            }
            COUNTER.incrementAndGet();
            sleep(20);
        }
    }

    private static void waiter() {
        while (true) {
            synchronized (CHURN) {
                try {
                    CHURN.wait(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static void busyWork(int n) {
        long x = ThreadLocalRandom.current().nextLong();
        for (int i = 0; i < n; i++) {
            x = x * 6364136223846793005L + 1442695040888963407L;
        }
        if (x == 0xdeadbeefL) {
            COUNTER.incrementAndGet();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void write(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private DemoApp() {
    }
}
