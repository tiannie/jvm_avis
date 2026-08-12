package com.jvmavis.collector.jfr;

import com.jvmavis.collector.model.ProfileSnapshot;

import java.time.Instant;

/**
 * Result of parsing one dump.
 *
 * @param snapshot     the servable profile
 * @param newestSample exact timestamp of the newest counted sample, or null when the dump held
 *                     none. Kept at full precision rather than reading it back off the snapshot's
 *                     millisecond window, so the next dump cannot re-count a sub-millisecond tail.
 */
public record ParsedProfile(ProfileSnapshot snapshot, Instant newestSample) {
}
