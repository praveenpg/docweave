package io.docweave.layout;

import io.docweave.partition.PartitionException;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;

/** Monotonic call budget shared by admission, preprocessing, native cancellation and topology work. */
final class LayoutDeadline {
    /** Creating/calling thread, polled by native cancellation watchdog. */
    private final Thread owner = Thread.currentThread();
    /** Monotonic start, independent of wall-clock changes. */
    private final long started = System.nanoTime();
    /** Validated positive budget below the nanoTime wrap comparison range. */
    private final long budget;

    /**
     * Captures a validated at-most-one-day call budget.
     * @param timeout immutable positive duration
     */
    LayoutDeadline(final Duration timeout) {
        budget = timeout.toNanos();
    }

    /**
     * Reports caller interruption without clearing the flag.
     * @return whether the owning thread has been interrupted
     */
    boolean interrupted() {
        return owner.isInterrupted();
    }

    /**
     * Reports deadline expiry for a native watchdog without throwing on its own thread.
     * @return whether the monotonic budget is consumed
     */
    boolean expired() {
        return System.nanoTime() - started >= budget;
    }

    /**
     * Checks owner interruption/deadline and returns remaining admission time.
     * @return positive remaining nanoseconds
     * @throws IOException if interruption or timeout prevents further processing
     */
    long remaining() throws IOException {
        if (interrupted()) {
            throw new InterruptedIOException("ONNX table inference interrupted");
        }
        final long left = budget - (System.nanoTime() - started);
        if (left <= 0) {
            throw new PartitionException(PartitionException.Code.OCR_TIMEOUT, "ONNX table inference timed out");
        }
        return left;
    }
}
