package dev.docweave.ocr;

import dev.docweave.partition.PartitionException;

import java.io.InterruptedIOException;
import java.time.Duration;

/** Call-local monotonic deadline, shared by admission, encoding, process I/O and parsing. */
final class OcrDeadline {
    /** Monotonic expiry; subtraction remains valid for the validated at-most-one-day timeout. */
    private final long expires;

    /**
     * Starts a deadline.
     * @param timeout validated positive bounded duration
     */
    OcrDeadline(final Duration timeout) {
        expires = System.nanoTime() + timeout.toNanos();
    }

    /**
     * Checks interruption without clearing the flag and returns remaining waiting time.
     * @return positive nanoseconds remaining
     * @throws InterruptedIOException if interrupted
     * @throws PartitionException if the deadline expired
     */
    long remaining() throws InterruptedIOException, PartitionException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Tesseract recognition interrupted");
        }
        final long remaining = expires - System.nanoTime();
        if (remaining <= 0) {
            throw new PartitionException(PartitionException.Code.OCR_TIMEOUT, "Tesseract recognition timed out");
        }
        return remaining;
    }
}
