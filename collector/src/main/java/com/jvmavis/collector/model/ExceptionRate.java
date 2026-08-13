package com.jvmavis.collector.model;

/**
 * Throw rate derived from {@code jdk.ExceptionStatistics}.
 *
 * <p>The JVM reports a total that only ever climbs, so a rate needs two readings. The endpoints are
 * kept alongside the derived figures because merging windows means taking the outermost pair, not
 * adding rates together.
 */
public record ExceptionRate(
        long firstCount,
        long firstAtMs,
        long lastCount,
        long lastAtMs,
        long thrownInWindow,
        double perSecond
) {
    public static final ExceptionRate EMPTY = new ExceptionRate(0, 0, 0, 0, 0, 0);

    public static ExceptionRate of(long firstCount, long firstAtMs, long lastCount, long lastAtMs) {
        long thrown = Math.max(0, lastCount - firstCount);
        long spanMs = lastAtMs - firstAtMs;
        double perSecond = spanMs > 0 ? (thrown * 1000.0 / spanMs) : 0.0;
        return new ExceptionRate(
                firstCount,
                firstAtMs,
                lastCount,
                lastAtMs,
                thrown,
                Math.round(perSecond * 100.0) / 100.0);
    }
}
