package io.docweave.partition;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Call-confined resolution limits: byte ceiling, monotonic deadline and the creating thread's interruption.
 * A single shared daemon thread runs stop watchers registered through {@link #abortOnStop(Closeable)}; it is
 * created lazily on first use, so local-only processing starts no thread.
 */
final class DefaultResolutionContext implements ResolutionContext {
    /** Read chunk size for response bodies. */
    private static final int CHUNK = 64 * 1024;

    /** Initial buffer for bodies of unknown length. */
    private static final int INITIAL_UNKNOWN = 64 * 1024;

    /** Interval at which stop watchers poll the deadline and the owner's interruption. */
    private static final long WATCH_MILLIS = 50;

    /** Owner-only permissions for temporary files on POSIX filesystems. */
    private static final FileAttribute<?> OWNER_ONLY =
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));

    /** Byte ceiling. */
    private final int maxBytes;

    /** Deadline on the {@link System#nanoTime()} clock. */
    private final long deadlineNanos;

    /** Thread whose interruption cancels this resolution. */
    private final Thread owner;

    /**
     * Starts the deadline now.
     * @param maxBytes positive byte ceiling
     * @param timeout positive finite timeout
     * @throws NullPointerException if timeout is null
     * @throws IllegalArgumentException if a value is not positive
     */
    DefaultResolutionContext(final int maxBytes, final Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (maxBytes <= 0 || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Resolution needs a positive byte ceiling and timeout");
        }
        this.maxBytes = maxBytes;
        final long now = System.nanoTime();
        long nanos;
        try {
            nanos = timeout.toNanos();
        } catch (final ArithmeticException tooLong) {
            nanos = Long.MAX_VALUE / 4;
        }
        this.deadlineNanos = now + Math.min(nanos, Long.MAX_VALUE / 4);
        this.owner = Thread.currentThread();
    }

    @Override
    public int maxBytes() {
        return maxBytes;
    }

    @Override
    public Duration remaining() {
        return Duration.ofNanos(Math.max(0, deadlineNanos - System.nanoTime()));
    }

    @Override
    public void checkActive() throws IOException {
        if (Thread.currentThread().isInterrupted() || owner.isInterrupted()) {
            throw new InterruptedIOException("Remote resolution interrupted");
        }
        if (expired()) {
            throw deadline();
        }
    }

    @Override
    public AutoCloseable abortOnStop(final Closeable abort) {
        Objects.requireNonNull(abort, "abort");
        final var fired = new AtomicBoolean();
        final var task = new AtomicReference<ScheduledFuture<?>>();
        task.set(Watcher.EXECUTOR.scheduleWithFixedDelay(() -> {
            if ((owner.isInterrupted() || expired()) && fired.compareAndSet(false, true)) {
                // The abort runs on its own virtual thread: a transport close that blocks must never delay the
                // shared watcher, which serves the deadlines and interruptions of every other resolution.
                Thread.ofVirtual().name("docweave-remote-abort").start(() -> {
                    try {
                        abort.close();
                    } catch (final IOException | RuntimeException ignored) {
                        // Best effort: the resolving thread reports the stop itself.
                    }
                });
                final var self = task.get();
                if (self != null) {
                    self.cancel(false);
                }
            }
        }, WATCH_MILLIS, WATCH_MILLIS, TimeUnit.MILLISECONDS));
        return () -> {
            fired.set(true);
            task.get().cancel(false);
        };
    }

    @Override
    public ResolvedDocument receive(final InputStream body, final long declaredBytes, final SpoolPolicy spool,
            final String filename, final Function<Received, SourceProvenance> provenance) throws IOException {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(spool, "spool");
        Objects.requireNonNull(provenance, "provenance");
        checkActive();
        if (declaredBytes > maxBytes) {
            throw tooLarge();
        }
        final MessageDigest digest = sha256();
        try (AutoCloseable ignored = abortOnStop(body)) {
            if (spool.inMemory()) {
                final byte[] content = readToMemory(body, declaredBytes, digest);
                final var received = new Received(content.length, HexFormat.of().formatHex(digest.digest()));
                return DefaultResolvedDocument.owned(content, filename, provenance.apply(received));
            }
            return readToFile(body, declaredBytes, spool.directory(), digest, filename, provenance);
        } catch (final IOException failure) {
            throw stopped(failure);
        } catch (final RuntimeException failure) {
            throw failure;
        } catch (final Exception unexpected) {
            throw new IOException("Remote content could not be received", unexpected);
        }
    }

    /**
     * Reads a body into an exact-length array.
     * @param body response stream
     * @param declared declared length or negative
     * @param digest running digest
     * @return exact received bytes
     * @throws IOException on limit, mismatch, stop or read failure
     */
    private byte[] readToMemory(final InputStream body, final long declared, final MessageDigest digest)
            throws IOException {
        byte[] buffer = new byte[declared >= 0 ? (int) declared : Math.min(INITIAL_UNKNOWN, maxBytes)];
        int used = 0;
        final byte[] probe = new byte[1];
        while (true) {
            checkActive();
            if (used == buffer.length) {
                // Full: distinguish the end of the content from more data with a single-byte read.
                final int extra = body.read(probe);
                checkActive();
                if (extra < 0) {
                    break;
                }
                if (extra == 0) {
                    continue;
                }
                if (declared >= 0) {
                    throw incomplete("more bytes than the provider declared");
                }
                if (used == maxBytes) {
                    throw tooLarge();
                }
                buffer = Arrays.copyOf(buffer, (int) Math.min(maxBytes, Math.max(INITIAL_UNKNOWN, 2L * buffer.length)));
                buffer[used++] = probe[0];
                digest.update(probe, 0, 1);
                continue;
            }
            final int read = body.read(buffer, used, Math.min(CHUNK, buffer.length - used));
            checkActive();
            if (read < 0) {
                break;
            }
            digest.update(buffer, used, read);
            used += read;
        }
        if (declared >= 0 && used != declared) {
            throw incomplete("fewer bytes than the provider declared");
        }
        return used == buffer.length ? buffer : Arrays.copyOf(buffer, used);
    }

    /**
     * Streams a body into an owner-only temporary file, deleting it on failure.
     * @param body response stream
     * @param declared declared length or negative
     * @param directory temporary directory
     * @param digest running digest
     * @param filename logical filename
     * @param provenance provenance builder
     * @return an owned handle that deletes the file on close
     * @throws IOException on limit, mismatch, stop, read or write failure
     */
    private ResolvedDocument readToFile(final InputStream body, final long declared, final Path directory,
            final MessageDigest digest, final String filename, final Function<Received, SourceProvenance> provenance)
            throws IOException {
        final Path file = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
                                  ? Files.createTempFile(directory, "docweave-remote-", ".part", OWNER_ONLY)
                                  : Files.createTempFile(directory, "docweave-remote-", ".part");
        boolean kept = false;
        try {
            long used = 0;
            final byte[] chunk = new byte[CHUNK];
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                while (true) {
                    checkActive();
                    final int read = body.read(chunk);
                    checkActive();
                    if (read < 0) {
                        break;
                    }
                    used += read;
                    if (used > maxBytes) {
                        throw tooLarge();
                    }
                    if (declared >= 0 && used > declared) {
                        throw incomplete("more bytes than the provider declared");
                    }
                    digest.update(chunk, 0, read);
                    final var buffer = ByteBuffer.wrap(chunk, 0, read);
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                }
            }
            if (declared >= 0 && used != declared) {
                throw incomplete("fewer bytes than the provider declared");
            }
            final var received = new Received(used, HexFormat.of().formatHex(digest.digest()));
            final ResolvedDocument handle =
                    DefaultResolvedDocument.temporaryFile(file, filename, provenance.apply(received));
            kept = true;
            return handle;
        } finally {
            if (!kept) {
                Files.deleteIfExists(file);
            }
        }
    }

    /**
     * Maps a failure that followed a stop (deadline or interruption, which may have aborted the stream) to the stop
     * itself; other failures pass through.
     * @param failure observed failure
     * @return the exception to throw
     */
    private IOException stopped(final IOException failure) {
        if (failure instanceof PartitionException || failure instanceof RemoteSourceException
                || failure instanceof InterruptedIOException) {
            return failure;
        }
        if (owner.isInterrupted()) {
            final var interrupted = new InterruptedIOException("Remote resolution interrupted");
            interrupted.initCause(failure);
            return interrupted;
        }
        if (expired()) {
            final var late = deadline();
            late.addSuppressed(failure);
            return late;
        }
        return failure;
    }

    /**
     * Tells whether the deadline has passed.
     * @return true after the deadline
     */
    private boolean expired() {
        return System.nanoTime() - deadlineNanos >= 0;
    }

    /**
     * Creates the deadline failure.
     * @return typed failure
     */
    private static RemoteSourceException deadline() {
        return new RemoteSourceException(
                RemoteSourceException.Reason.DEADLINE_EXCEEDED, "Remote acquisition exceeded its deadline");
    }

    /**
     * Creates the size failure, using the same category as oversized local inputs.
     * @return typed failure
     */
    private PartitionException tooLarge() {
        return new PartitionException(
                PartitionException.Code.INPUT_TOO_LARGE, "Remote document exceeds maxInputBytes=" + maxBytes);
    }

    /**
     * Creates a length-mismatch failure.
     * @param detail what differed
     * @return typed failure
     */
    private static RemoteSourceException incomplete(final String detail) {
        return new RemoteSourceException(RemoteSourceException.Reason.INCOMPLETE, "Remote transfer returned " + detail);
    }

    /**
     * Returns a new SHA-256 digest.
     * @return digest
     */
    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", missing);
        }
    }

    /** Lazily created shared watcher thread. */
    private static final class Watcher {
        /** Single daemon scheduler shared by all contexts. */
        private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(task -> {
            final Thread thread = new Thread(task, "docweave-remote-watch");
            thread.setDaemon(true);
            return thread;
        });

        /** Prevents instantiation. */
        private Watcher() {
        }
    }
}
