package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.core.Element;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Generic remote-source contract with fake resolvers and the real installed parsers: opaque locations, explicit
 * configuration, cleanup on every path, limits, deadlines, cancellation, concurrency and local/remote parity.
 */
class RemoteSourceContractTest {
    @TempDir Path spoolDirectory;

    @Test
    void locationReachesTheResolverUnchangedAndOnlyOnce() throws Exception {
        final var seen = new CopyOnWriteArrayList<String>();
        final RemoteSourceResolver resolver = (source, context) -> {
            seen.add(source.location());
            return ResolvedDocument.of(DocumentSource.text("ok"), "a.txt", provenance(2), null);
        };
        final var processor = DocWeave.builder().remoteSources(resolver).build();
        final List<String> locations = List.of("reports/annual-report.pdf", "s3://bucket/key with spaces.txt",
                "  leading and trailing  ", "", "ünïcödé/路径.txt", "provider:ref?version=3#frag", "../../etc/passwd");
        for (final String location : locations) {
            processor.partition(DocumentSource.remote(location));
        }
        assertEquals(locations, seen, "core neither parses, trims nor normalizes locations");
    }

    @Test
    void remoteSourcesNeedAnExplicitResolverAndNothingIsResolvedImplicitly() {
        for (final DocWeave processor : List.of(DocWeave.create(), DocWeave.builder().build())) {
            final var failure = assertThrows(
                    IllegalArgumentException.class, () -> processor.partition(DocumentSource.remote("reports/a.pdf")));
            assertTrue(failure.getMessage().contains("RemoteSourceResolver"));
            assertFalse(failure.getMessage().contains("reports/a.pdf"), "the location is not echoed");
        }
    }

    @Test
    void invalidLocationsAreIllegalArgumentsFromTheResolverWhileAcquisitionFailuresStayTyped() {
        final RemoteSourceResolver resolver = (source, context) -> {
            if (!source.location().startsWith("ok/")) {
                throw new IllegalArgumentException("Unsupported location syntax");
            }
            throw new RemoteSourceException(RemoteSourceException.Reason.NOT_FOUND, "Object not found");
        };
        final var processor = DocWeave.builder().remoteSources(resolver).build();
        assertThrows(IllegalArgumentException.class, () -> processor.partition(DocumentSource.remote("bad")));
        final var missing = assertThrows(
                RemoteSourceException.class, () -> processor.partition(DocumentSource.remote("ok/missing.txt")));
        assertEquals(RemoteSourceException.Reason.NOT_FOUND, missing.reason());
    }

    @Test
    void localAndRemoteInputsWithTheSameBytesAndNameProduceIdenticalElementsAndIds() throws Exception {
        final Map<String, byte[]> inputs =
                Map.of("plain.txt", Files.readAllBytes(Path.of("benchmarks/fixtures/plain.txt")), "page.html",
                        "<h1>Title</h1><p>Body text.</p><ul><li>One</li></ul>".getBytes(StandardCharsets.UTF_8),
                        "structured.docx", Files.readAllBytes(Path.of("benchmarks/fixtures/structured.docx")),
                        "simple.pdf", pdf());
        for (final SpoolPolicy spool : List.of(SpoolPolicy.memory(), SpoolPolicy.temporaryFile(spoolDirectory))) {
            final RemoteSourceResolver resolver = (source, context)
                    -> context.receive(new ByteArrayInputStream(inputs.get(source.location())),
                            inputs.get(source.location()).length, spool, source.location(),
                            received
                            -> new SourceProvenance("fake", "fake://" + source.location(), "v7", null,
                                    source.location(), received.bytes(), received.sha256()));
            final var remoteProcessor = DocWeave.builder().remoteSources(resolver).build();
            for (final var input : inputs.entrySet()) {
                final var local = DocWeave.create().partition(DocumentSource.bytes(input.getValue(), input.getKey()));
                final var remote = remoteProcessor.partition(DocumentSource.remote(input.getKey()));
                assertEquals(local.elements(), remote.elements(), input.getKey() + " with " + spool);
                assertEquals(local.format(), remote.format());
                assertEquals(local.encoding(), remote.encoding());
                assertEquals(local.inputBytes(), remote.inputBytes());
                assertEquals(local.warnings(), remote.warnings());
                assertNull(local.sourceProvenance());
                final var provenance = remote.sourceProvenance();
                assertEquals("v7", provenance.version());
                assertEquals(input.getValue().length, provenance.bytes());
                assertEquals(input.getKey(), provenance.filename());
                assertFalse(remote.elements().isEmpty(), input.getKey());
                for (final Element element : remote.elements()) {
                    assertFalse(String.valueOf(element.metadata()).contains(spoolDirectory.toString()),
                            "temporary paths never reach element metadata");
                }
            }
        }
        assertNoSpoolFiles();
    }

    @Test
    void resolvedHandleIsClosedExactlyOnceOnSuccessParseFailureLimitFailureAndCancellation() throws Exception {
        final var closes = new AtomicInteger();
        final AtomicReference<DocumentSource> content = new AtomicReference<>();
        final RemoteSourceResolver resolver = (source,
                context) -> ResolvedDocument.of(content.get(), "doc.txt", provenance(1), closes::incrementAndGet);
        final var processor = DocWeave.builder().remoteSources(resolver).build();

        content.set(DocumentSource.text("Hello"));
        processor.partition(DocumentSource.remote("x"));
        assertEquals(1, closes.getAndSet(0), "success");

        content.set(DocumentSource.bytes(new byte[] {(byte) 0xff, (byte) 0xfe, 0, 'a', (byte) 0xd8}, "doc.txt"));
        assertThrows(PartitionException.class,
                ()
                        -> processor.partition(DocumentSource.remote("x"),
                                PartitionOptions.builder().charset(StandardCharsets.UTF_8).build()));
        assertEquals(1, closes.getAndSet(0), "decoding failure");

        content.set(DocumentSource.text("a\n\nb\n\nc\n\nd"));
        final var limit = assertThrows(PartitionException.class,
                ()
                        -> processor.partition(
                                DocumentSource.remote("x"), PartitionOptions.builder().maxElements(1).build()));
        assertEquals(PartitionException.Code.ELEMENT_LIMIT, limit.code());
        assertEquals(1, closes.getAndSet(0), "element limit");

        content.set(DocumentSource.bytes(new byte[100], "doc.txt"));
        final var tooLarge = assertThrows(PartitionException.class,
                ()
                        -> processor.partition(
                                DocumentSource.remote("x"), PartitionOptions.builder().maxInputBytes(50).build()));
        assertEquals(PartitionException.Code.INPUT_TOO_LARGE, tooLarge.code(), "local limits stay as defense in depth");
        assertEquals(1, closes.getAndSet(0), "size limit after resolution");

        content.set(DocumentSource.text("Hello"));
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> processor.partition(DocumentSource.remote("x")));
        } finally {
            Thread.interrupted();
        }
        assertEquals(0, closes.get(), "an interrupted call stops before resolving");
    }

    @Test
    void cleanupFailuresAreVisibleAfterSuccessAndSuppressedOntoPrimaryFailures() {
        final AtomicReference<DocumentSource> content = new AtomicReference<>(DocumentSource.text("Hello"));
        final RemoteSourceResolver resolver =
                (source, context) -> ResolvedDocument.of(content.get(), "doc.txt", provenance(1), () -> {
            throw new IOException("cleanup failed");
        });
        final var processor = DocWeave.builder().remoteSources(resolver).build();
        final var afterSuccess = assertThrows(IOException.class, () -> processor.partition(DocumentSource.remote("x")));
        assertEquals("cleanup failed", afterSuccess.getMessage());

        content.set(DocumentSource.text("a\n\nb\n\nc"));
        final var primary = assertThrows(PartitionException.class,
                ()
                        -> processor.partition(
                                DocumentSource.remote("x"), PartitionOptions.builder().maxElements(1).build()));
        assertEquals(PartitionException.Code.ELEMENT_LIMIT, primary.code());
        assertEquals("cleanup failed", primary.getSuppressed()[0].getMessage());
    }

    @Test
    void resolverReceivesTheCallByteCeilingAndMustReturnALocalDocument() {
        final var ceiling = new AtomicInteger();
        final var processor = DocWeave.builder()
                                      .remoteSources((source, context) -> {
                                          ceiling.set(context.maxBytes());
                                          return null;
                                      })
                                      .build();
        assertThrows(IllegalStateException.class,
                ()
                        -> processor.partition(
                                DocumentSource.remote("x"), PartitionOptions.builder().maxInputBytes(12_345).build()));
        assertEquals(12_345, ceiling.get());
        final var remoteInRemote = DocWeave.builder()
                                           .remoteSources((source, context)
                                                                  -> ResolvedDocument.of(DocumentSource.remote("again"),
                                                                          null, provenance(0), null))
                                           .build();
        assertThrows(IllegalArgumentException.class,
                () -> remoteInRemote.partition(DocumentSource.remote("x")), "no recursive Remote-to-Remote resolution");
    }

    @Test
    void acquisitionDeadlineStopsAStalledTransferAndCleansUp() throws Exception {
        final var stalled = new StalledStream();
        final var processor =
                DocWeave.builder()
                        .remoteSources(
                                (source, context)
                                        -> context.receive(stalled, -1, SpoolPolicy.temporaryFile(spoolDirectory),
                                                "slow.txt", received -> provenance(received.bytes())))
                        .remoteTimeout(Duration.ofMillis(300))
                        .build();
        final long start = System.nanoTime();
        final var failure =
                assertThrows(RemoteSourceException.class, () -> processor.partition(DocumentSource.remote("slow")));
        assertEquals(RemoteSourceException.Reason.DEADLINE_EXCEEDED, failure.reason());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(5)) < 0);
        assertTrue(stalled.closed.getCount() == 0);
        assertNoSpoolFiles();
    }

    @Test
    void interruptingTheCallingThreadCancelsAcquisition() throws Exception {
        final var stalled = new StalledStream();
        final var processor =
                DocWeave.builder()
                        .remoteSources(
                                (source, context)
                                        -> context.receive(stalled, -1, SpoolPolicy.temporaryFile(spoolDirectory),
                                                "slow.txt", received -> provenance(received.bytes())))
                        .build();
        final var outcome = new AtomicReference<Throwable>();
        final Thread caller = Thread.ofVirtual().start(() -> {
            try {
                processor.partition(DocumentSource.remote("slow"));
            } catch (final Throwable failure) {
                outcome.set(failure);
            }
        });
        stalled.started.await();
        caller.interrupt();
        caller.join(Duration.ofSeconds(5));
        assertInstanceOf(InterruptedIOException.class, outcome.get());
        assertNoSpoolFiles();
    }

    @Test
    void oneResolverServesConcurrentCallsWithIndependentHandles() throws Exception {
        final var open = new ConcurrentHashMap<String, Boolean>();
        final var closed = new AtomicInteger();
        final RemoteSourceResolver resolver = (source, context) -> {
            final var handle = context.receive(
                    new ByteArrayInputStream(("Document " + source.location()).getBytes(StandardCharsets.UTF_8)), -1,
                    SpoolPolicy.temporaryFile(spoolDirectory), source.location() + ".txt",
                    received -> provenance(received.bytes()));
            open.put(source.location(), true);
            return new ForwardingDocument(handle, closed);
        };
        final var processor = DocWeave.builder().remoteSources(resolver).build();
        final List<Future<PartitionResult>> results = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            IntStream.range(0, 64).forEach(
                    i -> results.add(executor.submit(() -> processor.partition(DocumentSource.remote("n" + i)))));
            for (int i = 0; i < 64; i++) {
                final var elements = results.get(i).get().elements();
                assertEquals("Document n" + i, elements.get(0).text());
            }
        }
        assertEquals(64, open.size());
        assertEquals(64, closed.get());
        assertNoSpoolFiles();
    }

    @Test
    void builderValidatesTheTimeoutAndKeepsLocalBehavior() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> DocWeave.builder().remoteTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> DocWeave.builder().remoteTimeout(Duration.ofDays(2)));
        assertThrows(NullPointerException.class, () -> DocWeave.builder().remoteSources(null));
        final var local = DocWeave.builder().build().partition(DocumentSource.text("Same", "a.txt"));
        assertEquals(DocWeave.create().partition(DocumentSource.text("Same", "a.txt")).elements(), local.elements());
        final var registry = PartitionerRegistry.create();
        assertNotNull(DocWeave.builder().registry(registry).build());
        assertSame(DocumentSource.Remote.class, DocumentSource.remote("x").getClass());
    }

    /**
     * Creates a provenance value.
     * @param bytes received bytes
     * @return provenance
     */
    private static SourceProvenance provenance(final long bytes) {
        return new SourceProvenance("fake", "fake://object", null, null, null, bytes, null);
    }

    /**
     * Builds a one-page PDF with two text lines.
     * @return PDF bytes
     * @throws IOException if PDFBox fails
     */
    private static byte[] pdf() throws IOException {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            final var page = new PDPage();
            document.addPage(page);
            try (var stream = new PDPageContentStream(document, page)) {
                int baseline = 700;
                for (final String line : List.of("Annual report", "Revenue grew in every region.")) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(72, baseline);
                    stream.showText(line);
                    stream.endText();
                    baseline -= 20;
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    /** Asserts the spool directory is empty. */
    private void assertNoSpoolFiles() {
        try (Stream<Path> files = Files.list(spoolDirectory)) {
            assertEquals(0, files.count(), "no temporary file remains");
        } catch (final IOException failure) {
            throw new AssertionError(failure);
        }
    }

    /** Counts closes of a delegate handle. */
    private record ForwardingDocument(ResolvedDocument delegate, AtomicInteger closes) implements ResolvedDocument {
        @Override
        public DocumentSource localSource() {
            return delegate.localSource();
        }

        @Override
        public String filename() {
            return delegate.filename();
        }

        @Override
        public SourceProvenance provenance() {
            return delegate.provenance();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
            closes.incrementAndGet();
        }
    }

    /** Sends a few bytes, then blocks until closed, ignoring interruption like a stalled socket. */
    private static final class StalledStream extends InputStream {
        /** Released when the stall begins. */
        final CountDownLatch started = new CountDownLatch(1);

        /** Released when closed. */
        final CountDownLatch closed = new CountDownLatch(1);

        /** Bytes sent before stalling. */
        private int sent;

        @Override
        public int read() throws IOException {
            if (sent < 3) {
                sent++;
                return 'a';
            }
            started.countDown();
            while (closed.getCount() > 0) {
                try {
                    closed.await();
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    while (closed.getCount() > 0) {
                        Thread.onSpinWait();
                    }
                }
            }
            throw new IOException("socket closed");
        }

        @Override
        public int read(final byte[] buffer, final int offset, final int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            final int value = read();
            buffer[offset] = (byte) value;
            return 1;
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }
}
