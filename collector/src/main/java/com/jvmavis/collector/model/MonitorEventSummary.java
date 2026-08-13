package com.jvmavis.collector.model;

/**
 * Time spent on one monitor, aggregated per class.
 *
 * <p>{@code kind} separates the two cases, which mean opposite things: BLOCKED is
 * {@code jdk.JavaMonitorEnter}, a thread stalled behind a lock someone else holds, while WAITING is
 * {@code jdk.JavaMonitorWait}, a thread that deliberately called {@code Object.wait}. Only the
 * former is contention.
 *
 * <p>Under {@code settings=default} the JVM only records blocking longer than 20ms, so a target
 * with plenty of short lock convoys can legitimately report nothing here. Lowering that needs
 * {@code settings=profile} on the target.
 */
public record MonitorEventSummary(
        String kind,
        String monitorClass,
        long events,
        double totalMs,
        double maxMs
) {
    public static final String BLOCKED = "BLOCKED";
    public static final String WAITING = "WAITING";
}
