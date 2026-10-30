package io.docweave.partition;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generic acquisition rules of {@link ResolutionContext#receive}: actual byte limits without truncation, declared
 * length mismatches, digests, owner-only temporary files and their cleanup, deadlines and interruption.
 */
class ResolutionContextTest {
    /** Provenance builder used by these tests. */
    private static final Function<ResolutionContext.Received, SourceProvenance> PROVENANCE = received
            -> new SourceProvenance(
                    "test", "test://object", "v1", null, "doc.txt", received.bytes(), received.sha256());

    @TempDir Path directory;

    @Test
    void memoryReceiveKeepsExactBytesAndDigestForKnownAndUnknownLengths() throws Exception {
        final byte[] content = "hello remote world".getBytes(StandardCharsets.UTF_8);
        for (final long declared : new long[] {content.length, -1}) {
            final var context = ResolutionContext.create(1024, Duration.ofSeconds(5));
            try (var handle = context.receive(
                         new ByteArrayInputStream(content), declared, SpoolPolicy.memory(), "doc.txt", PROVENANCE)) {
                final var local = assertInstanceOf(DocumentSource.Bytes.class, handle.localSource());
                assertArrayEquals(content, local.content());
                assertEquals("doc.txt", handle.filename());
                assertEquals(content.length, handle.provenance().bytes());
                assertEquals(sha256(content), handle.provenance().sha256());
            }
        }
    }

    @Test
    void unknownLengthGrowsPastTheInitialBufferAndAcceptsExactlyTheLimit() throws Exception {
        final byte[] content = new byte[200_000];
        new java.util.Random(7).nextBytes(content);
        final var context = ResolutionContext.create(content.length, Duration.ofSeconds(5));
        try (var handle =
                        context.receive(new TrickleStream(content, 777), -1, SpoolPolicy.memory(), null, PROVENANCE)) {
            assertArrayEquals(content, ((DocumentSource.Bytes) handle.localSource()).content());
        }
    }

    @Test
    void sizeIsEnforcedOnReceivedBytesNotOnlyOnTheDeclaredLength() {
        final byte[] content = new byte[2049];
        // Declared too large: refused before reading.
        assertTooLarge(()
                               -> ResolutionContext.create(2048, Duration.ofSeconds(5))
                                       .receive(new ByteArrayInputStream(content), 2049, SpoolPolicy.memory(), null,
                                               PROVENANCE));
        // Unknown length: refused when the 2049th byte arrives; never truncated.
        assertTooLarge(()
                               -> ResolutionContext.create(2048, Duration.ofSeconds(5))
                                       .receive(new ByteArrayInputStream(content), -1, SpoolPolicy.memory(), null,
                                               PROVENANCE));
        assertTooLarge(()
                               -> ResolutionContext.create(2048, Duration.ofSeconds(5))
                                       .receive(new ByteArrayInputStream(content), -1,
                                               SpoolPolicy.temporaryFile(directory), null, PROVENANCE));
    }

    @Test
    void declaredLengthMismatchIsIncompleteInBothDirections() {
        final byte[] content = new byte[100];
        for (final SpoolPolicy spool : new SpoolPolicy[] {SpoolPolicy.memory(), SpoolPolicy.temporaryFile(directory)}) {
            final var shorter = assertThrows(RemoteSourceException.class,
                    ()
                            -> ResolutionContext.create(1000, Duration.ofSeconds(5))
                                    .receive(new ByteArrayInputStream(content), 150, spool, null, PROVENANCE));
            assertEquals(RemoteSourceException.Reason.INCOMPLETE, shorter.reason());
            final var longer = assertThrows(RemoteSourceException.class,
                    ()
                            -> ResolutionContext.create(1000, Duration.ofSeconds(5))
                                    .receive(new ByteArrayInputStream(content), 50, spool, null, PROVENANCE));
            assertEquals(RemoteSourceException.Reason.INCOMPLETE, longer.reason());
        }
        assertNoTemporaryFiles();
    }

    @Test
    void temporaryFileIsOwnerOnlyNamedRandomlyAndDeletedOnceOnClose() throws Exception {
        final byte[] content = "spooled".getBytes(StandardCharsets.UTF_8);
        final var context = ResolutionContext.create(1024, Duration.ofSeconds(5));
        final var handle = context.receive(new ByteArrayInputStream(content), content.length,
                SpoolPolicy.temporaryFile(directory), "report.pdf", PROVENANCE);
        final var file = assertInstanceOf(DocumentSource.File.class, handle.localSource()).path();
        assertTrue(Files.exists(file));
        assertFalse(file.getFileName().toString().contains("report"), "the logical name is not the temporary name");
        assertEquals("report.pdf", handle.filename());
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        }
        assertArrayEquals(content, Files.readAllBytes(file));
        handle.close();
        handle.close();
        assertFalse(Files.exists(file));
        assertThrows(IllegalStateException.class, handle::localSource);
    }

    @Test
    void failedTransferLeavesNoTemporaryFile() {
        final InputStream broken = new InputStream() {
            private int sent;

            @Override
            public int read() throws IOException {
                if (sent++ < 10) {
                    return 'x';
                }
                throw new IOException("connection reset");
            }
        };
        assertThrows(IOException.class,
                ()
                        -> ResolutionContext.create(1024, Duration.ofSeconds(5))
                                .receive(broken, -1, SpoolPolicy.temporaryFile(directory), null, PROVENANCE));
        assertNoTemporaryFiles();
    }

    @Test
    void deadlineAbortsABlockedReadAndCleansUp() throws Exception {
        final var stream = new BlockingStream();
        final var context = ResolutionContext.create(1024, Duration.ofMillis(200));
        final long start = System.nanoTime();
        final var failure = assertThrows(RemoteSourceException.class,
                () -> context.receive(stream, -1, SpoolPolicy.temporaryFile(directory), null, PROVENANCE));
        assertEquals(RemoteSourceException.Reason.DEADLINE_EXCEEDED, failure.reason());
        assertTrue(stream.closed.getCount() == 0, "the blocked read was aborted through close()");
        assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(3)) < 0);
        assertEquals(Duration.ZERO, context.remaining());
        assertNoTemporaryFiles();
    }

    @Test
    void interruptionAbortsABlockedReadAndPreservesTheFlag() throws Exception {
        final var stream = new BlockingStream();
        final var outcome = new AtomicReference<Throwable>();
        final var flag = new AtomicReference<Boolean>();
        final Thread worker = Thread.ofVirtual().start(() -> {
            try {
                ResolutionContext.create(1024, Duration.ofMinutes(1))
                        .receive(stream, -1, SpoolPolicy.memory(), null, PROVENANCE);
            } catch (final Throwable failure) {
                outcome.set(failure);
            }
            flag.set(Thread.currentThread().isInterrupted());
        });
        stream.started.await();
        worker.interrupt();
        worker.join(Duration.ofSeconds(5));
        assertInstanceOf(InterruptedIOException.class, outcome.get());
        assertTrue(flag.get(), "interrupt status preserved");
    }

    @Test
    void aBlockedAbortDoesNotDelayStopHandlingForOtherResolutions() throws Exception {
        final var release = new CountDownLatch(1);
        final var firstStarted = new CountDownLatch(1);
        final var secondAborted = new CountDownLatch(1);
        final var first = ResolutionContext.create(1024, Duration.ofMillis(20));
        final var second = ResolutionContext.create(1024, Duration.ofMillis(75));
        try (AutoCloseable blocked = first.abortOnStop(() -> {
            firstStarted.countDown();
            try {
                release.await();
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
                AutoCloseable independent = second.abortOnStop(secondAborted::countDown)) {
            assertTrue(firstStarted.await(5, java.util.concurrent.TimeUnit.SECONDS), "the first abort ran and blocks");
            assertTrue(secondAborted.await(1, java.util.concurrent.TimeUnit.SECONDS),
                    "the second deadline fired while the first abort was still blocked");
        } finally {
            release.countDown();
        }
    }

    @Test
    void checkActiveAndValidation() throws Exception {
        final var context = ResolutionContext.create(10, Duration.ofSeconds(5));
        context.checkActive();
        assertEquals(10, context.maxBytes());
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, context::checkActive);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertThrows(IllegalArgumentException.class, () -> ResolutionContext.create(0, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> ResolutionContext.create(1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> ResolutionContext.create(1, Duration.ofSeconds(-1)));
    }

    @Test
    void handlesAndValuesRedactAndValidate() throws Exception {
        final var remote = (DocumentSource.Remote) DocumentSource.remote("s3://bucket/secret-key?X-Amz-Signature=abc");
        assertFalse(remote.toString().contains("secret"));
        assertEquals("s3://bucket/secret-key?X-Amz-Signature=abc", remote.location());
        assertThrows(NullPointerException.class, () -> DocumentSource.remote(null));
        assertThrows(IllegalArgumentException.class, () -> ResolvedDocument.of(remote, null, provenance(), null));
        assertFalse(SpoolPolicy.temporaryFile(directory).toString().contains(directory.toString()));
        assertThrows(IllegalArgumentException.class, () -> new SourceProvenance("s3", "r", null, null, null, 1, "ABC"));
        assertThrows(IllegalArgumentException.class, () -> new SourceProvenance(" ", "r", null, null, null, 1, null));
        final var released = new int[1];
        final var wrapped = ResolvedDocument.of(DocumentSource.text("x"), "a.txt", provenance(), () -> released[0]++);
        wrapped.close();
        wrapped.close();
        assertEquals(1, released[0], "release runs exactly once");
        assertNull(new PartitionResult(
                           java.util.List.of(), DocumentFormat.TXT, "UTF-8", 0, Duration.ZERO, java.util.List.of())
                           .sourceProvenance(),
                "the pre-remote constructor reports no provenance");
    }

    /**
     * Creates a minimal provenance.
     * @return provenance
     */
    private static SourceProvenance provenance() {
        return new SourceProvenance("test", "test://x", null, null, null, 1, null);
    }

    /**
     * Asserts an INPUT_TOO_LARGE failure.
     * @param call call expected to fail
     */
    private static void assertTooLarge(final org.junit.jupiter.api.function.Executable call) {
        final var failure = assertThrows(PartitionException.class, call);
        assertEquals(PartitionException.Code.INPUT_TOO_LARGE, failure.code());
    }

    /** Asserts the spool directory is empty. */
    private void assertNoTemporaryFiles() {
        try (Stream<Path> files = Files.list(directory)) {
            assertEquals(0, files.count(), "no temporary file remains");
        } catch (final IOException failure) {
            throw new AssertionError(failure);
        }
    }

    /**
     * Computes a hex SHA-256.
     * @param content bytes
     * @return digest
     * @throws Exception never in practice
     */
    private static String sha256(final byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    /** Returns content in small reads. */
    private static final class TrickleStream extends InputStream {
        /** Content. */
        private final byte[] content;

        /** Largest read. */
        private final int step;

        /** Next index. */
        private int index;

        /**
         * Creates the stream.
         * @param content content
         * @param step largest read
         */
        TrickleStream(final byte[] content, final int step) {
            this.content = content;
            this.step = step;
        }

        @Override
        public int read() {
            return index < content.length ? content[index++] & 0xff : -1;
        }

        @Override
        public int read(final byte[] buffer, final int offset, final int length) {
            if (index >= content.length) {
                return -1;
            }
            final int count = Math.min(Math.min(length, step), content.length - index);
            System.arraycopy(content, index, buffer, offset, count);
            index += count;
            return count;
        }
    }

    /** Blocks every read until closed, like a stalled socket. */
    private static final class BlockingStream extends InputStream {
        /** Released once a read has started. */
        final CountDownLatch started = new CountDownLatch(1);

        /** Released when closed. */
        final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public int read() throws IOException {
            started.countDown();
            try {
                // Ignores interruption, as a blocking socket read does.
                while (!closed.await(1, java.util.concurrent.TimeUnit.SECONDS)) {
                    Thread.onSpinWait();
                }
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                while (closed.getCount() > 0) {
                    Thread.onSpinWait();
                }
            }
            throw new IOException("stream closed");
        }

        @Override
        public int read(final byte[] buffer, final int offset, final int length) throws IOException {
            return read();
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }
}
