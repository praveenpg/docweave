package dev.docweave.batch;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Machine-readable elapsed-time failure, exposed as the cause of a batch ExecutionException.
 * Carries policy scope and an input index, never document text or a source path. Timeout signals
 * request interruption of outstanding work; an operation ignoring interruption may outlive the call.
 */
public final class BatchTimeoutException extends TimeoutException {
    /** Serialization version identifier. */
    private static final long serialVersionUID = 1L;

    /** Elapsed-time budget that produced the timeout signal. */
    public enum Scope {
        /** One task exceeded its budget, measured from worker entry. */ TASK,
        /** The invocation exceeded its overall post-snapshot budget. */ BATCH
    }

    /**
     * Serialized budget identity.
     * @serial nonnull task or batch scope
     */
    private final Scope scope;
    /**
     * Serialized source position without the source value.
     * @serial zero-based task index, or -1 for a batch timeout
     */
    private final int inputIndex;
    /**
     * Serialized configured elapsed-time budget.
     * @serial positive duration representable in nanoseconds
     */
    private final Duration timeout;

    /**
     * Describes a timeout without retaining the input or operation.
     * @param scope nonnull budget scope
     * @param inputIndex zero-based input index for TASK, or -1 for BATCH
     * @param timeout positive configured budget, representable in nanoseconds
     * @throws IllegalArgumentException if the index or duration is invalid
     * @throws NullPointerException if scope or timeout is null
     */
    public BatchTimeoutException(final Scope scope, final int inputIndex, final Duration timeout) {
        super(message(scope, inputIndex, timeout));
        this.scope = scope;
        this.inputIndex = inputIndex;
        this.timeout = timeout;
    }

    /**
     * Identifies the exceeded budget.
     * @return task or batch scope
     */
    public Scope scope() {
        return scope;
    }

    /**
     * Identifies the timed-out input without retaining it.
     * @return zero-based input position, or -1 for an overall timeout
     */
    public int inputIndex() {
        return inputIndex;
    }

    /**
     * Returns the configured duration, not a measured execution time.
     * @return positive budget
     */
    public Duration timeout() {
        return timeout;
    }

    /**
     * Validates constructor arguments and formats the sanitized exception message.
     * @param scope task or batch timeout scope
     * @param index zero-based input index for {@link Scope#TASK}, or {@code -1} for {@link Scope#BATCH}
     * @param timeout positive configured duration
     * @return formatted timeout message without source content
     */
    private static String message(final Scope scope, final int index, final Duration timeout) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(timeout, "timeout");
        if (scope == Scope.TASK ? index < 0 : index != -1) {
            throw new IllegalArgumentException("TASK requires a nonnegative index; BATCH requires -1");
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        try {
            timeout.toNanos();
        } catch (final ArithmeticException error) {
            throw new IllegalArgumentException("timeout must fit in nanoseconds", error);
        }
        return scope == Scope.TASK ? "Task at input index " + index + " exceeded " + timeout
                                   : "Batch exceeded " + timeout;
    }
}
