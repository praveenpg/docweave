package dev.docweave.layout;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import dev.docweave.partition.PartitionException;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Managed CPU session with shared bounded admission and cooperative native cancellation.
 * @param <P> model-specific prepared pixels
 */
final class ManagedTableSession<P> implements AutoCloseable {
    /** Immutable model-specific tensor and preprocessing strategy. */
    private final TableModel<P> model;
    /** Immutable graph/resource identity. */
    private final OnnxTableOptions options;
    /** Shared process environment; never closed by this provider. */
    private final OrtEnvironment environment;
    /** Provider-owned session, protected against concurrent closure. */
    private final OrtSession session;
    /** Limits admitted preprocessing and native calls together. */
    private final Semaphore admission;
    /** Active calls hold read ownership; native cleanup requires write ownership. */
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();
    /** One daemon timer for all admitted native calls. */
    private final ScheduledThreadPoolExecutor watchdog;
    /** Blocks new admissions as soon as closure starts. */
    private volatile boolean closing;
    /** Cleanup completion, guarded by the lifecycle write lock. */
    private boolean closed;

    /**
     * Verifies a bounded standalone graph and creates a schema-checked native session.
     * @param options validated graph and resource policy
     * @param model explicit internal tensor and preprocessing strategy
     * @throws IOException if identity, loading or schema validation fails
     */
    ManagedTableSession(final OnnxTableOptions options, final TableModel<P> model) throws IOException {
        this.options = options;
        this.model = Objects.requireNonNull(model, "model");
        final byte[] graph;
        try (final var input = Files.newInputStream(options.model())) {
            graph = input.readNBytes(options.maxModelBytes() + 1);
        }
        if (graph.length > options.maxModelBytes()) {
            throw failure("ONNX graph exceeds maxModelBytes", null);
        }
        try {
            final var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(graph));
            if (!hash.equals(options.sha256())) {
                throw failure("ONNX graph SHA256 mismatch", null);
            }
        } catch (final NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 unavailable", exception);
        }
        OrtSession created = null;
        try (final var configuration = new OrtSession.SessionOptions()) {
            configuration.setIntraOpNumThreads(options.nativeThreads());
            configuration.setInterOpNumThreads(1);
            environment = OrtEnvironment.getEnvironment();
            created = environment.createSession(graph, configuration);
            model.validate(created);
            session = created;
        } catch (final OrtException | LinkageError exception) {
            if (created != null) {
                try {
                    created.close();
                } catch (final OrtException cleanup) {
                    exception.addSuppressed(cleanup);
                }
            }
            throw failure("Cannot initialize compatible ONNX table session", exception);
        } catch (final IOException exception) {
            if (created != null) {
                try {
                    created.close();
                } catch (final OrtException cleanup) {
                    exception.addSuppressed(cleanup);
                }
            }
            throw exception;
        }
        admission = new Semaphore(options.maxConcurrentCalls(), true);
        watchdog = new ScheduledThreadPoolExecutor(1, task -> {
            final var thread = new Thread(task, "docweave-onnx-cancellation");
            thread.setDaemon(true);
            return thread;
        });
        watchdog.setRemoveOnCancelPolicy(true);
    }

    /**
     * Runs compatible inference and bounded postprocessing within one admission/lifecycle/deadline scope.
     * @param <T> call-confined postprocessing result type
     * @param image caller-owned source raster
     * @param deadline shared total pipeline budget, including nested child inference
     * @param finishing bounded postprocessing receiving copied source-frame objects
     * @return postprocessing result without retaining or modifying the borrowed raster
     * @throws IOException if lifecycle, cancellation, native execution or geometry validation fails
     */
    <T> T infer(final BufferedImage image, final LayoutDeadline deadline, final LayoutInference<T> finishing)
            throws IOException {
        return infer(image, deadline, new LayoutWork(deadline), finishing);
    }

    /**
     * Shares detector suppression, crop and topology work inside managed call ownership.
     * @param <T> call-confined postprocessing result
     * @param image borrowed unchanged source raster
     * @param deadline shared total pipeline deadline
     * @param work shared detector/crop/topology comparison budget
     * @param finishing bounded postprocessing within parent admission and lifecycle ownership
     * @return completed copied result
     * @throws IOException if inference, lifecycle or budgets fail
     */
    <T> T infer(final BufferedImage image, final LayoutDeadline deadline, final LayoutWork work,
            final LayoutInference<T> finishing) throws IOException {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(finishing, "finishing");
        boolean admitted = false;
        boolean locked = false;
        try {
            requireOpen();
            admitted = admission.tryAcquire(deadline.remaining(), TimeUnit.NANOSECONDS);
            deadline.remaining();
            if (!admitted) {
                throw failure("ONNX admission unavailable", null);
            }
            requireOpen();
            final var pixels = model.prepare(image, options, deadline);
            locked = lifecycle.readLock().tryLock(deadline.remaining(), TimeUnit.NANOSECONDS);
            deadline.remaining();
            if (!locked) {
                throw failure("ONNX lifecycle unavailable", null);
            }
            requireOpen();
            try (final var cancellation = new NativeCancellation(deadline)) {
                final var objects =
                        model.infer(environment, session, pixels, options, deadline, work, cancellation.run);
                final var output = finishing.finish(objects);
                deadline.remaining();
                return output;
            } catch (final OrtException exception) {
                deadline.remaining();
                throw failure("ONNX table inference failed", exception);
            }
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            final var interrupted = new InterruptedIOException("ONNX table inference interrupted");
            interrupted.initCause(exception);
            throw interrupted;
        } finally {
            if (locked) {
                lifecycle.readLock().unlock();
            }
            if (admitted) {
                admission.release();
            }
        }
    }

    /**
     * Rejects operations after closure starts.
     * @throws IOException if the provider is closing or closed
     */
    private void requireOpen() throws IOException {
        if (closing) {
            throw failure("ONNX table provider is closed", null);
        }
    }

    /**
     * Closes the owned session after active inference leaves; interrupted cleanup can be retried.
     * @throws IOException if interrupted or native cleanup fails
     */
    @Override
    public void close() throws IOException {
        closing = true;
        try {
            lifecycle.writeLock().lockInterruptibly();
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("ONNX table provider closure interrupted");
        }
        try {
            if (!closed) {
                try {
                    session.close();
                    closed = true;
                } catch (final OrtException exception) {
                    throw failure("ONNX session cleanup failed", exception);
                } finally {
                    watchdog.shutdownNow();
                }
            }
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    /**
     * Releases a successfully constructed but never-published session after a sibling graph fails.
     * No inference or external lifecycle call may have started. Direct native cleanup preserves an
     * already-interrupted constructor thread without interruptible lock acquisition leaking this session.
     * @throws IOException if native cleanup fails
     */
    void discardUnpublished() throws IOException {
        closing = true;
        try {
            session.close();
            closed = true;
        } catch (final OrtException exception) {
            throw failure("ONNX unpublished session cleanup failed", exception);
        } finally {
            watchdog.shutdownNow();
        }
    }

    /**
     * Constructs a typed provider failure without exposing source content.
     * @param message bounded diagnostic
     * @param cause optional native or platform cause
     * @return typed OCR failure
     */
    private static PartitionException failure(final String message, final Throwable cause) {
        return new PartitionException(PartitionException.Code.OCR_FAILURE, message, cause);
    }

    /** Serializes native termination against release of its per-call handle. */
    private final class NativeCancellation implements AutoCloseable {
        /** Per-call native cancellation handle. */
        private final OrtSession.RunOptions run = new OrtSession.RunOptions();
        /** Scheduled poll, cancelled before native handle release. */
        private final java.util.concurrent.ScheduledFuture<?> poll;
        /** Whether native options have been released, guarded by this instance. */
        private boolean released;

        /**
         * Schedules owner interruption/deadline polling while native inference is active.
         * @param deadline shared call deadline
         * @throws OrtException if native options cannot be allocated
         */
        private NativeCancellation(final LayoutDeadline deadline) throws OrtException {
            poll = watchdog.scheduleAtFixedRate(() -> terminate(deadline), 0, 10, TimeUnit.MILLISECONDS);
        }

        /**
         * Cooperatively terminates native inference when its owner cancels or times out.
         * @param deadline owning call budget
         */
        private synchronized void terminate(final LayoutDeadline deadline) {
            if (!released && (deadline.interrupted() || deadline.expired())) {
                try {
                    run.setTerminate(true);
                } catch (final OrtException ignored) {
                    // The owning call still checks its deadline and reports the typed cancellation.
                }
            }
        }

        /** Cancels polling before releasing the handle, excluding an in-flight poll. */
        @Override
        public synchronized void close() {
            poll.cancel(false);
            released = true;
            run.close();
        }
    }
}
