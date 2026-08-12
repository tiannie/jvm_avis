package com.jvmavis.collector.model;

/**
 * Allocation attributed to one class.
 *
 * @param bytes JFR's extrapolated estimate from sampled allocations, not an exact total
 */
public record AllocationSite(
        String type,
        long bytes,
        long samples,
        double percent
) {
}
