package io.docweave.chunking;

import io.docweave.batch.BatchOptions;
import io.docweave.batch.BatchProcessor;
import io.docweave.batch.BatchTimeoutException;
import io.docweave.core.Element;
import io.docweave.core.ElementType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.AbstractList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class ChunkCancellationTest {
    private Element element() {
        return new Element("a", ElementType.NarrativeText, "Some text.", Map.of());
    }

    private void interrupted(final Runnable operation) {
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, operation::run);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void bothStrategiesAndEmptyInputRespectExistingInterruption() {
        final var chunker = Chunker.create();
        for (final var strategy : Chunker.Strategy.values()) {
            interrupted(() -> chunker.chunk(List.of(element()), strategy, ChunkOptions.defaults()));
            interrupted(() -> chunker.chunk(List.of(), strategy, ChunkOptions.defaults()));
        }
        assertFalse(chunker.basic(List.of(element()), ChunkOptions.defaults()).isEmpty());
    }

    @Test
    void interruptionDuringInputTraversalStopsBeforeSuccess() {
        final var input = new AbstractList<Element>() {
            public int size() {
                return 2;
            }
            public Element get(final int index) {
                Thread.currentThread().interrupt();
                return element();
            }
        };
        try {
            assertThrows(CancellationException.class, () -> Chunker.create().basic(input, ChunkOptions.defaults()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void structuralAndLiteralSplittingHaveTheirOwnCheckpoints() {
        interrupted(() -> HtmlTableModel.parse("<table><tr><td>cell</td></tr></table>"));
        interrupted(()
                            -> new TextChunkSplitter(ChunkOptions.builder()
                                                             .maxCharacters(10)
                                                             .newAfterCharacters(10)
                                                             .combineTextUnderCharacters(0)
                                                             .build())
                                    .split("long text ".repeat(20)));
        interrupted(() -> MetadataConsolidator.consolidate(List.of(element())));
    }

    @Test
    void unicodeNormalizationAndTrimmingRespectInterruption() {
        interrupted(() -> UnicodeText.normalize(" \uD83D\uDE00 words ".repeat(1000)));
        interrupted(() -> UnicodeText.leftStrip(" ".repeat(5000) + "text"));
        interrupted(()
                            -> UnicodeText.rightStrip("text"
                                                      + " ".repeat(5000)));
        assertEquals("😀 words", UnicodeText.normalize("\u00a0😀\t words\u0085"));
    }

    @Test
    void batchTaskDeadlineInterruptsBuiltInChunking() throws Exception {
        final var entered = new CountDownLatch(1);
        final var stopped = new CountDownLatch(1);
        final var observed = new AtomicReference<Throwable>();
        final var input = new AbstractList<Element>() {
            public int size() {
                return 2;
            }
            public Element get(final int index) {
                entered.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (final InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
                return element();
            }
        };
        final var error = assertThrows(ExecutionException.class,
                ()
                        -> BatchProcessor.create().mapOrdered(List.of(input),
                                new BatchOptions(1, Duration.ofMillis(300), Duration.ZERO), elements -> {
                                    try {
                                        return Chunker.create().basic(elements, ChunkOptions.defaults());
                                    } catch (final CancellationException cancelled) {
                                        observed.set(cancelled);
                                        throw cancelled;
                                    } finally {
                                        stopped.countDown();
                                    }
                                }));
        assertInstanceOf(BatchTimeoutException.class, error.getCause());
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertTrue(stopped.await(2, TimeUnit.SECONDS));
        assertInstanceOf(CancellationException.class, observed.get());
    }

    @Test
    void workerCancellationCauseIsExposedByBatch() {
        final var error = assertThrows(
                ExecutionException.class, () -> BatchProcessor.create().mapOrdered(List.of(element()), 1, value -> {
                    Thread.currentThread().interrupt();
                    return Chunker.create().basic(List.of(value), ChunkOptions.defaults());
                }));
        assertInstanceOf(CancellationException.class, error.getCause());
    }
}
