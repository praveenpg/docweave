package io.docweave.partition;

import java.util.Objects;

/**
 * Immutable, content-free snapshot of one partition call's progress.
 *
 * <p>Events describe work units, not elapsed or remaining time: OCR, layout and table work can vary greatly between
 * units, so a unit percentage is not a time estimate. Events never carry document text, filenames, metadata or
 * element values.
 *
 * <p>The built-in engine reports phases in this order, skipping none on success:
 * {@link Phase#PREPARING}, {@link Phase#EXTRACTING}, {@link Phase#ASSEMBLING} and {@link Phase#DONE}. A custom
 * {@link io.docweave.DocWeave} implementation that does not override the listener overload reports only PREPARING
 * and DONE. During
 * {@code EXTRACTING} a strategy with a reliable unit count, such as PDF pages, reports a stable {@link #total()} and
 * a monotonically increasing {@link #completed()} that never exceeds it. Other strategies report an indeterminate
 * total. {@code ASSEMBLING} and {@code DONE} carry the last unit counters forward, so a fully extracted document can
 * show {@code completed == total} while still assembling; only {@code DONE} means the partition call succeeded.
 * A determinate total can be zero (for example a document with no pages), giving {@code 0/0}: check for a zero
 * total before computing a fraction.
 *
 * <p>Counters are {@code long} so this value type can describe large unit counts; the built-in
 * {@link PartitionProgressReporter} accepts {@code int} totals because its units (such as pages) are indexed by
 * {@code int}.
 *
 * @param phase nonnull current phase
 * @param unit work-unit name such as {@code "pages"}, or null when the phase has no measured units
 * @param completed nonnegative completed units; zero without a unit
 * @param total stable unit total, or {@link #UNKNOWN_TOTAL} when no reliable total exists
 */
public record PartitionProgress(Phase phase, String unit, long completed, long total) {
    /** Sentinel total for indeterminate progress. */
    public static final long UNKNOWN_TOTAL = -1;

    /**
     * Validates one progress snapshot.
     *
     * @param phase nonnull current phase
     * @param unit null, or a nonblank unit name
     * @param completed nonnegative completed units, not above a determinate total
     * @param total {@link #UNKNOWN_TOTAL} or a nonnegative total
     * @throws NullPointerException if phase is null
     * @throws IllegalArgumentException if a value is outside the documented ranges or counters lack a unit
     */
    public PartitionProgress {
        Objects.requireNonNull(phase, "phase");
        if (unit != null && unit.isBlank()) {
            throw new IllegalArgumentException("unit must be null or nonblank");
        }
        if (completed < 0 || total < UNKNOWN_TOTAL || total != UNKNOWN_TOTAL && completed > total) {
            throw new IllegalArgumentException("completed must be within [0, total]");
        }
        if (unit == null && (completed != 0 || total != UNKNOWN_TOTAL)) {
            throw new IllegalArgumentException("unit counters require a unit name");
        }
    }

    /**
     * Creates an indeterminate snapshot without unit counters.
     *
     * @param phase nonnull phase
     * @return snapshot with no unit and an unknown total
     * @throws NullPointerException if phase is null
     */
    public static PartitionProgress indeterminate(final Phase phase) {
        return new PartitionProgress(phase, null, 0, UNKNOWN_TOTAL);
    }

    /**
     * Reports whether this snapshot has a reliable unit total.
     *
     * @return true when {@link #total()} is not {@link #UNKNOWN_TOTAL}
     */
    public boolean determinate() {
        return total != UNKNOWN_TOTAL;
    }

    /** Coarse stage of one partition call, reported in declaration order. */
    public enum Phase {
        /** Source acquisition, loading, format detection and strategy selection. */
        PREPARING,
        /** Format-specific extraction; may carry unit counters such as completed PDF pages. */
        EXTRACTING,
        /** Final result assembly after every extraction unit finished; the call has not succeeded yet. */
        ASSEMBLING,
        /** The result was assembled successfully; reported once, immediately before the call returns. */
        DONE
    }
}
