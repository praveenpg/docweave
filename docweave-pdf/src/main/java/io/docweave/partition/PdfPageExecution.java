package io.docweave.partition;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.HashSet;
import java.util.Set;

/** Per-call cancellation bridge for common-pool page tasks; scheduling itself belongs to parallelStream. */
final class PdfPageExecution implements AutoCloseable {
    /** Calling thread receives the external job cancellation. */
    private final Thread owner = Thread.currentThread();
    /** Only this call's active page threads may be interrupted. */
    private final Set<Thread> active = new HashSet<>();
    /** Tracks interrupt flags issued by this scope, to avoid contaminating common-pool workers. */
    private final Set<Thread> interrupted = new HashSet<>();
    /** First processing failure; cancellation failures cannot replace it. */
    private Throwable failure;
    /** Whether a provider interrupted its own page thread before this scope stopped siblings. */
    private boolean interruptedPage;

    /** Creates a cancellation scope on the calling thread. */
    PdfPageExecution() {
    }

    /**
     * Waits for the common-pool stream while forwarding caller interruption to active pages.
     * The caller does not execute page callbacks, so stopping a sibling cannot interrupt unrelated caller work.
     * @param task common-pool stream task
     * @return buffered pages in input order
     * @throws IOException if waiting or the stream itself fails
     */
    java.util.List<PdfPageOutput> await(
            final java.util.concurrent.CompletableFuture<java.util.List<PdfPageOutput>> task) throws IOException {
        // CompletableFuture.get lets a common-pool caller execute its queued supplier inline.
        // Wait through a managed blocker instead: page callbacks must remain interruptible
        // independently of the caller, and the pool can compensate for a blocked worker.
        final var completed = new java.util.concurrent.CountDownLatch(1);
        task.whenComplete((result, error) -> completed.countDown());
        final var blocker = new java.util.concurrent.ForkJoinPool.ManagedBlocker() {
            @Override
            public boolean isReleasable() {
                return completed.getCount() == 0;
            }

            @Override
            public boolean block() throws InterruptedException {
                completed.await();
                return true;
            }
        };
        boolean restoreInterrupt = false;
        try {
            while (true) {
                try {
                    java.util.concurrent.ForkJoinPool.managedBlock(blocker);
                    return task.get();
                } catch (final InterruptedException cancelled) {
                    restoreInterrupt = true;
                    stop(new InterruptedIOException("PDF extraction interrupted"));
                } catch (final java.util.concurrent.ExecutionException failed) {
                    throw new IOException("Parallel PDF page processing failed", failed.getCause());
                }
            }
        } finally {
            if (restoreInterrupt) {
                owner.interrupt();
            }
        }
    }

    /**
     * Runs one page on the stream's chosen thread and retains a checked failure for the caller.
     * @param task isolated page operation
     * @return buffered page, or null after failure/cancellation
     */
    PdfPageOutput run(final Page task) {
        final var thread = Thread.currentThread();
        synchronized (this) {
            if (failure != null || owner.isInterrupted()) {
                if (failure == null) {
                    stop(new InterruptedIOException("PDF extraction interrupted"));
                }
                return null;
            }
            active.add(thread);
        }
        try {
            return task.run();
        } catch (final IOException | RuntimeException | Error error) {
            synchronized (this) {
                if (failure == null && thread.isInterrupted()) {
                    interruptedPage = true;
                }
                stop(error);
            }
            return null;
        } finally {
            synchronized (this) {
                active.remove(thread);
                interrupted.remove(thread);
                // A pool thread never keeps an interrupt: neither one this execution issued nor one a provider set on
                // its own thread before throwing (that one is already forwarded to the owner).
                if (thread != owner) {
                    Thread.interrupted();
                }
                notifyAll();
            }
        }
    }

    /**
     * Stops only registered threads while holding the same lock used for deregistration.
     * @param error first processing failure or caller cancellation
     */
    private synchronized void stop(final Throwable error) {
        if (failure == null) {
            failure = error;
        }
        for (final var thread : active) {
            if (thread != owner) {
                interrupted.add(thread);
                thread.interrupt();
            }
        }
    }

    /**
     * Restores the original checked/runtime failure after every stream task in the batch has returned.
     * @throws IOException if extraction failed or the caller was interrupted
     */
    synchronized void check() throws IOException {
        if (interruptedPage) {
            owner.interrupt();
        }
        if (failure instanceof final IOException io) {
            throw io;
        }
        if (failure instanceof final RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof final Error error) {
            throw error;
        }
        ElementCollector.checkInterrupted();
    }

    /** Stops and waits for this call's page tasks before releasing document input. */
    @Override
    public void close() {
        boolean restoreInterrupt = Thread.interrupted();
        synchronized (this) {
            if (!active.isEmpty()) {
                stop(new InterruptedIOException("PDF extraction stopped"));
            }
            while (!active.isEmpty()) {
                try {
                    wait();
                } catch (final InterruptedException cancelled) {
                    restoreInterrupt = true;
                }
            }
        }
        if (restoreInterrupt) {
            Thread.currentThread().interrupt();
        }
    }

    /** Checked page work adapted to a stream mapping function. */
    @FunctionalInterface
    interface Page {
        /**
         * Processes one isolated page.
         * @return page output to replay in order
         * @throws IOException if extraction fails
         */
        PdfPageOutput run() throws IOException;
    }
}
