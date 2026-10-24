package io.docweave.chunking;

import java.util.concurrent.CancellationException;

/** Package-local cooperative checkpoints; never clears the caller's interruption signal. */
final class ChunkCancellation {
    /** Prevents instantiation of this static utility class. */
    private ChunkCancellation() {
    }

    /**
     * Throws {@link CancellationException} if the current thread is interrupted, preserving the interrupt status.
     */
    static void check() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Chunk processing interrupted");
        }
    }
}
