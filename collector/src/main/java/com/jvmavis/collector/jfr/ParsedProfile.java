package com.jvmavis.collector.jfr;

import com.jvmavis.collector.model.ProfileSnapshot;

/**
 * Result of parsing one dump.
 *
 * @param snapshot the servable profile
 * @param cursor   watermarks to bound the next stream with. Kept at full instant precision rather
 *                 than read back off the snapshot's millisecond window, so the next dump cannot
 *                 re-count a sub-millisecond tail.
 */
public record ParsedProfile(ProfileSnapshot snapshot, ProfileCursor cursor) {
}
