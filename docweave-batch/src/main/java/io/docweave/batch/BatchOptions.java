package io.docweave.batch;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable concurrency and elapsed-time policy for one synchronous batch invocation.
 * Zero durations disable their deadline. Task time starts on entry to the worker, including all
 * operation stages; time before that worker starts does not consume its task budget. Batch time
 * starts after validation/input snapshotting, before task submission, and includes result collection.
 * Timers use monotonic elapsed time, not wall-clock dates. They request cooperative interruption,
 * not forced thread termination; no real-time scheduling guarantee is made.
 * @param maxInFlight positive bound on submitted, not-yet-collected tasks
 * @param taskTimeout nonnegative per-task duration, zero disables; must fit in nanoseconds
 * @param batchTimeout nonnegative overall duration, zero disables; must fit in nanoseconds
 */
public record BatchOptions(int maxInFlight, Duration taskTimeout, Duration batchTimeout) {
    /**
     * Validates concurrency and duration ranges without starting threads.
     * @param maxInFlight positive outstanding-task bound
     * @param taskTimeout nonnull nonnegative per-task duration
     * @param batchTimeout nonnull nonnegative batch duration
     * @throws IllegalArgumentException if a limit is invalid or a duration cannot fit in nanoseconds
     * @throws NullPointerException if a duration is null
     */
    public BatchOptions {
        if (maxInFlight < 1) {
            throw new IllegalArgumentException("maxInFlight must be positive");
        }
        validate(taskTimeout, "taskTimeout");
        validate(batchTimeout, "batchTimeout");
    }

    /**
     * Constructs a concurrency-only policy with both deadlines disabled.
     * @param maxInFlight positive outstanding-task bound
     * @throws IllegalArgumentException if the bound is not positive
     */
    public BatchOptions(final int maxInFlight) {
        this(maxInFlight, Duration.ZERO, Duration.ZERO);
    }

    /**
     * Reports whether either elapsed-time budget is enabled.
     * @return true if task or batch timeout is positive
     */
    public boolean hasDeadlines() {
        return !taskTimeout.isZero() || !batchTimeout.isZero();
    }

    /**
     * Validates that a timeout duration is nonnull, nonnegative and representable in nanoseconds.
     * @param duration duration value to validate
     * @param name option name for diagnostic messages
     */
    private static void validate(final Duration duration, final String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be nonnegative");
        }
        try {
            duration.toNanos();
        } catch (final ArithmeticException error) {
            throw new IllegalArgumentException(name + " must fit in nanoseconds", error);
        }
    }
}
