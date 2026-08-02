package com.jvmavis.collector.jmx;

import com.jvmavis.collector.model.MetricSample;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class MetricScraper {
    public MetricSample scrape(MBeanServerConnection mbsc) throws Exception {
        long now = System.currentTimeMillis();

        MemoryMXBean memory = ManagementFactory.newPlatformMXBeanProxy(
                mbsc, ManagementFactory.MEMORY_MXBEAN_NAME, MemoryMXBean.class);
        MemoryUsage heap = memory.getHeapMemoryUsage();
        MemoryUsage nonHeap = memory.getNonHeapMemoryUsage();

        ThreadMXBean threads = ManagementFactory.newPlatformMXBeanProxy(
                mbsc, ManagementFactory.THREAD_MXBEAN_NAME, ThreadMXBean.class);

        Double cpu = null;
        try {
            com.sun.management.OperatingSystemMXBean sunOs = ManagementFactory.newPlatformMXBeanProxy(
                    mbsc,
                    ManagementFactory.OPERATING_SYSTEM_MXBEAN_NAME,
                    com.sun.management.OperatingSystemMXBean.class);
            double load = sunOs.getProcessCpuLoad();
            if (load >= 0 && Double.isFinite(load)) {
                cpu = load;
            }
        } catch (Exception ignored) {
            // Some JREs expose only the standard OperatingSystemMXBean.
            OperatingSystemMXBean os = ManagementFactory.newPlatformMXBeanProxy(
                    mbsc, ManagementFactory.OPERATING_SYSTEM_MXBEAN_NAME, OperatingSystemMXBean.class);
            os.getAvailableProcessors();
        }

        long gcCount = 0;
        long gcTime = 0;
        Set<ObjectName> gcNames = mbsc.queryNames(new ObjectName("java.lang:type=GarbageCollector,*"), null);
        for (ObjectName gcName : gcNames) {
            GarbageCollectorMXBean gc = ManagementFactory.newPlatformMXBeanProxy(
                    mbsc, gcName.getCanonicalName(), GarbageCollectorMXBean.class);
            long count = gc.getCollectionCount();
            long time = gc.getCollectionTime();
            if (count >= 0) {
                gcCount += count;
            }
            if (time >= 0) {
                gcTime += time;
            }
        }

        Map<String, Integer> states = sampleThreadStates(threads);

        // Touch runtime bean so connection failures surface early on odd targets.
        RuntimeMXBean ignored = ManagementFactory.newPlatformMXBeanProxy(
                mbsc, ManagementFactory.RUNTIME_MXBEAN_NAME, RuntimeMXBean.class);
        ignored.getUptime();

        return new MetricSample(
                now,
                cpu,
                heap.getUsed(),
                heap.getCommitted(),
                heap.getMax(),
                nonHeap.getUsed(),
                gcCount,
                gcTime,
                threads.getThreadCount(),
                threads.getDaemonThreadCount(),
                threads.getPeakThreadCount(),
                states);
    }

    private static Map<String, Integer> sampleThreadStates(ThreadMXBean threads) {
        Map<Thread.State, Integer> counts = new EnumMap<>(Thread.State.class);
        for (Thread.State state : Thread.State.values()) {
            counts.put(state, 0);
        }
        long[] ids = threads.getAllThreadIds();
        // Cap dump size for very large thread pools.
        int limit = Math.min(ids.length, 500);
        long[] subset = new long[limit];
        System.arraycopy(ids, 0, subset, 0, limit);
        var infos = threads.getThreadInfo(subset);
        if (infos != null) {
            for (var info : infos) {
                if (info == null || info.getThreadState() == null) {
                    continue;
                }
                counts.merge(info.getThreadState(), 1, Integer::sum);
            }
        }
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<Thread.State, Integer> e : counts.entrySet()) {
            out.put(e.getKey().name(), e.getValue());
        }
        out.put("SAMPLED", limit);
        return out;
    }
}
