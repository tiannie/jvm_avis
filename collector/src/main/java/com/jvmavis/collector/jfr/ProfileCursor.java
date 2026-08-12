package com.jvmavis.collector.jfr;

import java.time.Instant;

/**
 * Newest event already counted, tracked per event type.
 *
 * <p>A single shared watermark would be set by whichever type happens to run ahead, silently
 * discarding the others' events that land behind it. Each type therefore advances on its own, and
 * the stream is only asked to reach back as far as the furthest-behind one.
 *
 * <p>The watermark cannot dedupe perfectly: an event's decoded timestamp shifts by a few
 * milliseconds between reads of the same chunk, so events sitting on a dump boundary are counted
 * twice. Measured against the target's own recording this overcounts execution and allocation
 * samples by roughly 1%, which is well inside the sampling error those views already carry. Exactly
 * countable events must dedupe on their own identity instead — see {@code GcPauseSummary}.
 */
public record ProfileCursor(Instant execution, Instant allocation, Instant gcPause) {
    public static final ProfileCursor EMPTY = new ProfileCursor(null, null, null);

    /** Null means nothing has been read yet, so the first stream is deliberately unbounded. */
    public Instant earliest() {
        Instant earliest = null;
        for (Instant candidate : new Instant[]{execution, allocation, gcPause}) {
            if (candidate != null && (earliest == null || candidate.isBefore(earliest))) {
                earliest = candidate;
            }
        }
        return earliest;
    }

    /** Keeps the previous watermark for any type the dump held no events for. */
    public ProfileCursor advance(Instant newExecution, Instant newAllocation, Instant newGcPause) {
        return new ProfileCursor(
                newExecution == null ? execution : newExecution,
                newAllocation == null ? allocation : newAllocation,
                newGcPause == null ? gcPause : newGcPause);
    }
}
