package dev.docweave.chunking;

import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Public-service controls for source identity, bounded model work and deterministic failure behavior. */
class ContextualChunkerTest {
    /** Creates independent provider/lifecycle controls. */
    ContextualChunkerTest() {
    }

    /**
     * Source code/table payloads and provenance stay separate from generated text and provider requests.
     * @throws Exception if enrichment fails
     */
    @Test
    void originalsAndUnicodeArePreservedWithExplicitEmbeddingText() throws Exception {
        final var original = new Element("source-id", ElementType.TableChunk, "A\t😀\nB\t2",
                Map.of("text_as_html", "<table></table>", "orig_elements", "original-payload", "coordinates",
                        Map.of("system", "owned")));
        final var calls = new AtomicInteger();
        final var service = ContextualChunker.create(request -> {
            assertTrue(Thread.currentThread().isVirtual());
            assertEquals("Company report 😀", request.documentText());
            assertEquals(original.text(), request.chunkText());
            assertEquals(0, request.chunkIndex());
            assertEquals(1, request.chunkCount());
            calls.incrementAndGet();
            return new ChunkContextProvider.Context("Report context 😀", "caller-provider", "local-model-rev1");
        });
        assertEquals(0, calls.get());
        final var result = service.enrich("Company report 😀", List.of(original), ContextualChunkOptions.defaults());
        assertEquals(1, calls.get());
        assertSame(original, result.getFirst().original());
        assertEquals(ContextualChunk.Outcome.ENRICHED, result.getFirst().outcome());
        assertEquals("Report context 😀\n\nA\t😀\nB\t2", result.getFirst().embeddingText());
        assertEquals("original-payload", original.metadata().get("orig_elements"));
        assertThrows(UnsupportedOperationException.class, () -> result.clear());
    }

    /**
     * Every input is checked before any model work; supplementary characters count once.
     * @throws Exception if enrichment fails
     */
    @Test
    void completePopulationIsPreflightedWithoutTruncationOrProviderSideEffects() throws Exception {
        final var calls = new AtomicInteger();
        final var service = ContextualChunker.create(request -> {
            calls.incrementAndGet();
            return new ChunkContextProvider.Context("😀😀", "provider", "model");
        });
        final var limits = new ContextualChunkOptions(
                2, 2, 2, 2, 1, Duration.ofSeconds(3), ContextualChunkOptions.FailurePolicy.FAIL_DOCUMENT);
        assertThrows(IllegalArgumentException.class, () -> service.enrich("abc", List.of(chunk("a")), limits));
        assertThrows(
                IllegalArgumentException.class, () -> service.enrich("ok", List.of(chunk("a"), chunk("abc")), limits));
        assertThrows(IllegalArgumentException.class,
                () -> service.enrich("ok", List.of(chunk("a"), chunk("b"), chunk("c")), limits));
        assertThrows(NullPointerException.class, () -> service.enrich("ok", Arrays.asList(chunk("a"), null), limits));
        assertEquals(0, calls.get());
        assertEquals(List.of(), service.enrich("ok", List.of(), limits));
        assertEquals(0, calls.get());
        assertEquals("😀😀\n\n😀😀", service.enrich("😀😀", List.of(chunk("😀😀")), limits).getFirst().embeddingText());
    }

    /**
     * Explicit original-only fallback records provider/invalid-context outcomes without error text leakage.
     * @throws Exception if enrichment fails
     */
    @Test
    void fallbackKeepsFailedChunksAndDefaultFailureRetainsCause() throws Exception {
        final var originalFailure = new IOException("caller-private failure");
        final ChunkContextProvider provider = request -> {
            return switch (request.chunkIndex()) {
                case 0 -> new ChunkContextProvider.Context("ok", "provider", "model");
                case 1 -> throw originalFailure;
                case 2 -> null;
                case 3 -> new ChunkContextProvider.Context("oversize", "provider", "model");
                default -> throw new IllegalStateException("caller-private unchecked failure");
            };
        };
        final var limits = new ContextualChunkOptions(
                20, 20, 2, 5, 2, Duration.ofSeconds(3), ContextualChunkOptions.FailurePolicy.KEEP_ORIGINAL);
        final var source = List.of(chunk("0"), chunk("1"), chunk("2"), chunk("3"), chunk("4"));
        final var result = ContextualChunker.create(provider).enrich("document", source, limits);
        assertEquals(List.of(ContextualChunk.Outcome.ENRICHED, ContextualChunk.Outcome.PROVIDER_FAILED,
                             ContextualChunk.Outcome.INVALID_CONTEXT, ContextualChunk.Outcome.INVALID_CONTEXT,
                             ContextualChunk.Outcome.PROVIDER_FAILED),
                result.stream().map(ContextualChunk::outcome).toList());
        for (int index = 1; index < result.size(); index++) {
            assertSame(source.get(index), result.get(index).original());
            assertEquals(Optional.empty(), result.get(index).context());
            assertEquals(source.get(index).text(), result.get(index).embeddingText());
        }
        final var failure = assertThrows(IOException.class,
                ()
                        -> ContextualChunker.create(request -> { throw originalFailure; })
                                .enrich("document", source, ContextualChunkOptions.defaults()));
        assertSame(originalFailure, failure.getCause());
        assertFalse(failure.getMessage().contains("caller-private"));
    }

    /**
     * Only the declared window executes concurrently and results retain source encounter order.
     * @throws Exception if controlled worker scheduling fails
     */
    @Test
    void virtualThreadWindowIsBoundedAndOutputOrderIsStable() throws Exception {
        final var entered = new CountDownLatch(2);
        final var release = new CountDownLatch(1);
        final var calls = new AtomicInteger();
        final var active = new AtomicInteger();
        final var peak = new AtomicInteger();
        final var service = ContextualChunker.create(request -> {
            assertTrue(Thread.currentThread().isVirtual());
            calls.incrementAndGet();
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            entered.countDown();
            try {
                if (!release.await(3, TimeUnit.SECONDS)) {
                    throw new IOException("Test release deadline exceeded");
                }
                return new ChunkContextProvider.Context("Context " + request.chunkIndex(), "provider", "model");
            } catch (final InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Test worker interrupted");
            } finally {
                active.decrementAndGet();
            }
        });
        final var source = new ArrayList<Element>();
        for (int index = 0; index < 10; index++) {
            source.add(chunk(Integer.toString(index)));
        }
        final var limits = new ContextualChunkOptions(
                20, 20, 20, 10, 2, Duration.ofSeconds(5), ContextualChunkOptions.FailurePolicy.FAIL_DOCUMENT);
        try (final var caller = Executors.newVirtualThreadPerTaskExecutor()) {
            final var result = caller.submit(() -> service.enrich("document", source, limits));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertEquals(2, calls.get());
                assertEquals(2, peak.get());
            } finally {
                release.countDown();
            }
            final var completed = result.get(3, TimeUnit.SECONDS);
            assertTrue(peak.get() <= 2);
            assertEquals(10, calls.get());
            for (int index = 0; index < completed.size(); index++) {
                assertSame(source.get(index), completed.get(index).original());
                assertEquals("Context " + index, completed.get(index).context().orElseThrow().text());
            }
        }
    }

    /**
     * An uncooperative provider cannot make executor shutdown wait past the public deadline.
     * @throws Exception if controlled timeout scheduling fails
     */
    @Test
    void deadlineCancelsWithoutWaitingForAnUncooperativeProvider() throws Exception {
        final var entered = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var exited = new CountDownLatch(1);
        final var interrupted = new CountDownLatch(1);
        final var service = ContextualChunker.create(request -> {
            entered.countDown();
            try {
                while (release.getCount() != 0) {
                    try {
                        release.await();
                    } catch (final InterruptedException failure) {
                        interrupted.countDown();
                        // Deliberately uncooperative provider: the test always releases it in finally.
                    }
                }
                return new ChunkContextProvider.Context("late", "provider", "model");
            } finally {
                exited.countDown();
            }
        });
        final var limits = new ContextualChunkOptions(
                20, 20, 20, 2, 1, Duration.ofSeconds(1), ContextualChunkOptions.FailurePolicy.KEEP_ORIGINAL);
        try (final var caller = Executors.newVirtualThreadPerTaskExecutor()) {
            final var result = caller.submit(() -> service.enrich("document", List.of(chunk("one")), limits));
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertInstanceOf(TimeoutException.class,
                        assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS)).getCause());
                assertTrue(interrupted.await(2, TimeUnit.SECONDS));
                assertEquals(1, exited.getCount());
            } finally {
                release.countDown();
            }
            assertTrue(exited.await(2, TimeUnit.SECONDS));
        }
    }

    /**
     * Caller and provider cancellation never turn into original-only success or clear caller interruption.
     * @throws Exception if enrichment fails unexpectedly
     */
    @Test
    void cancellationIsExplicitAndCallerInterruptionIsPreserved() throws Exception {
        final var calls = new AtomicInteger();
        final var service = ContextualChunker.create(request -> {
            calls.incrementAndGet();
            return new ChunkContextProvider.Context("context", "provider", "model");
        });
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class,
                    () -> service.enrich("document", List.of(chunk("one")), ContextualChunkOptions.defaults()));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, calls.get());
        } finally {
            Thread.interrupted();
        }
        for (final boolean checked : List.of(false, true)) {
            final var cancelled = ContextualChunker.create(request -> {
                if (checked) {
                    throw new InterruptedIOException("Provider cancelled");
                }
                throw new CancellationException("Provider cancelled");
            });
            assertThrows(CancellationException.class,
                    () -> cancelled.enrich("document", List.of(chunk("one")), ContextualChunkOptions.defaults()));
        }
    }

    /**
     * Caller interruption during generation cancels the running work and preserves the caller flag.
     * @throws Exception if controlled caller/worker scheduling fails
     */
    @Test
    void interruptionWhileWaitingCancelsInFlightCalls() throws Exception {
        final var entered = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var workerCancelled = new CountDownLatch(1);
        final var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        final var retainedInterrupt = new java.util.concurrent.atomic.AtomicBoolean();
        final var service = ContextualChunker.create(request -> {
            entered.countDown();
            try {
                release.await();
                return new ChunkContextProvider.Context("context", "provider", "model");
            } catch (final InterruptedException interrupted) {
                workerCancelled.countDown();
                throw new InterruptedIOException("Worker interrupted");
            }
        });
        final var caller = Thread.startVirtualThread(() -> {
            try {
                service.enrich("document", List.of(chunk("one")), ContextualChunkOptions.defaults());
                failure.set(new AssertionError("Cancellation returned a result"));
            } catch (final Throwable interrupted) {
                failure.set(interrupted);
                retainedInterrupt.set(Thread.currentThread().isInterrupted());
            }
        });
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(2000);
            assertFalse(caller.isAlive());
            assertInstanceOf(CancellationException.class, failure.get());
            assertTrue(retainedInterrupt.get());
            assertTrue(workerCancelled.await(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            caller.interrupt();
            caller.join(2000);
        }
    }

    /**
     * Default failure cancels its peer and never schedules the next chunk or retries generation.
     * @throws Exception if controlled worker scheduling fails
     */
    @Test
    void documentFailureCancelsPeerWithoutSchedulingLaterChunks() throws Exception {
        final var peerEntered = new CountDownLatch(1);
        final var peerCancelled = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var calls = new AtomicInteger();
        final var service = ContextualChunker.create(request -> {
            calls.incrementAndGet();
            try {
                if (request.chunkIndex() == 0) {
                    if (!peerEntered.await(2, TimeUnit.SECONDS)) {
                        throw new IOException("Peer did not start");
                    }
                    throw new IOException("Owned generation failure");
                }
                peerEntered.countDown();
                release.await();
                return new ChunkContextProvider.Context("context", "provider", "model");
            } catch (final InterruptedException interrupted) {
                peerCancelled.countDown();
                throw new InterruptedIOException("Peer interrupted");
            }
        });
        final var limits = new ContextualChunkOptions(
                20, 20, 20, 3, 2, Duration.ofSeconds(5), ContextualChunkOptions.FailurePolicy.FAIL_DOCUMENT);
        try {
            assertThrows(IOException.class,
                    () -> service.enrich("document", List.of(chunk("0"), chunk("1"), chunk("2")), limits));
            assertTrue(peerCancelled.await(2, TimeUnit.SECONDS));
            assertEquals(2, calls.get());
        } finally {
            release.countDown();
        }
    }

    /**
     * Supplies immutable chunks with deterministic source identities.
     * @param text original text
     * @return original composite chunk
     */
    private static Element chunk(final String text) {
        return new Element("source-" + text, ElementType.CompositeElement, text, Map.of());
    }
}
