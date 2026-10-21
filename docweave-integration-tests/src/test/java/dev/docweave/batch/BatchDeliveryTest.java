package dev.docweave.batch;

import dev.docweave.chunking.ChunkOptions;
import dev.docweave.chunking.Chunker;
import dev.docweave.partition.PartitionOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.Timeout(10)
class BatchDeliveryTest {
    @TempDir Path temp;

    @Test
    void lazyWindowOrderAndCallerDelivery() throws Exception {
        final var read = new AtomicInteger();
        final var delivered = new ArrayList<Integer>();
        final var caller = Thread.currentThread();
        final Iterable<Integer> source = () -> new Iterator<>() {
            public boolean hasNext() {
                return read.get() < 1000;
            }
            public Integer next() {
                return read.getAndIncrement();
            }
        };
        BatchProcessor.create().forEachOrdered(source, new BatchOptions(3),
                value
                -> {
                    assertTrue(Thread.currentThread().isVirtual());
                    return value;
                },
                value -> {
                    assertSame(caller, Thread.currentThread());
                    assertEquals(delivered.size(), value);
                    assertTrue(read.get() <= delivered.size() + 3);
                    delivered.add(value);
                });
        assertEquals(1000, delivered.size());
    }

    @Test
    void consumerFailureCancelsWorkersAndStopsReading() throws Exception {
        final var active = new CountDownLatch(1);
        final var stopped = new CountDownLatch(1);
        final var read = new AtomicInteger();
        final Iterable<Integer> source = () -> new Iterator<>() {
            public boolean hasNext() {
                return true;
            }
            public Integer next() {
                return read.getAndIncrement();
            }
        };
        final var original = new IllegalStateException("sink");
        final var error = assertThrows(ExecutionException.class,
                ()
                        -> BatchProcessor.create().forEachOrdered(source, new BatchOptions(2),
                                value
                                -> {
                                    if (value == 0) {
                                        assertTrue(active.await(5, TimeUnit.SECONDS));
                                        return value;
                                    }
                                    active.countDown();
                                    try {
                                        new CountDownLatch(1).await();
                                        return value;
                                    } finally {
                                        stopped.countDown();
                                    }
                                },
                                value -> { throw original; }));
        assertSame(original, error.getCause());
        assertEquals(2, read.get());
        assertTrue(stopped.await(5, TimeUnit.SECONDS));
    }

    @Test
    void lateFailureCanPreventEarlierDelivery() {
        final var started = new CountDownLatch(1);
        final var delivered = new ArrayList<Integer>();
        final var error = assertThrows(ExecutionException.class,
                ()
                        -> BatchProcessor.create().<Integer, Integer>forEachOrdered(
                                List.of(0, 1), new BatchOptions(2), value -> {
                                    if (value == 0) {
                                        started.countDown();
                                        new CountDownLatch(1).await();
                                    }
                                    assertTrue(started.await(5, TimeUnit.SECONDS));
                                    throw new IllegalArgumentException("worker");
                                }, delivered::add));
        assertInstanceOf(IllegalArgumentException.class, error.getCause());
        assertTrue(delivered.isEmpty());
    }

    @Test
    void batchDeadlineIncludesDelivery() {
        final var output = new ArrayList<Integer>();
        final var error = assertThrows(ExecutionException.class,
                ()
                        -> BatchProcessor.create().forEachOrdered(List.of(1, 2),
                                new BatchOptions(1, Duration.ZERO, Duration.ofMillis(100)), x -> x, value -> {
                                    output.add(value);
                                    Thread.sleep(250);
                                }));
        assertEquals(BatchTimeoutException.Scope.BATCH, ((BatchTimeoutException) error.getCause()).scope());
        assertEquals(List.of(1), output);
    }

    @Test
    void taskDeadlineRetainsPreviouslyDeliveredPrefix() {
        final var output = new ArrayList<Integer>();
        final var error = assertThrows(ExecutionException.class,
                ()
                        -> BatchProcessor.create().forEachOrdered(
                                List.of(0, 1), new BatchOptions(1, Duration.ofMillis(200), Duration.ZERO), value -> {
                                    if (value == 1) {
                                        new CountDownLatch(1).await();
                                    }
                                    return value;
                                }, output::add));
        final var timeout = (BatchTimeoutException) error.getCause();
        assertEquals(BatchTimeoutException.Scope.TASK, timeout.scope());
        assertEquals(1, timeout.inputIndex());
        assertEquals(List.of(0), output);
    }

    @Test
    void inputFailurePreservesDeliveredPrefixAndEmptyIsReusable() throws Exception {
        final var processor = BatchProcessor.create();
        final var output = new ArrayList<Integer>();
        final Iterable<Integer> source = () -> new Iterator<>() {
            int count;
            public boolean hasNext() {
                if (count > 0) {
                    throw new IllegalStateException("input");
                }
                return true;
            }
            public Integer next() {
                return count++;
            }
        };
        assertThrows(IllegalStateException.class,
                () -> processor.forEachOrdered(source, new BatchOptions(1), x -> x, output::add));
        assertEquals(List.of(0), output);
        processor.forEachOrdered(List.<Integer>of(), new BatchOptions(2), x -> x, output::add);
        processor.forEachOrdered(List.of(2), new BatchOptions(2), x -> x, output::add);
        assertEquals(List.of(0, 2), output);
    }

    @Test
    void consumerInterruptionAndNullContracts() {
        final var processor = BatchProcessor.create();
        assertThrows(InterruptedException.class,
                () -> processor.forEachOrdered(List.of(1), new BatchOptions(1), x -> x, x -> {
                    throw new InterruptedException();
                }));
        assertThrows(NullPointerException.class,
                () -> processor.forEachOrdered(List.of(1), new BatchOptions(1), x -> x, null));
        final var error = assertThrows(ExecutionException.class,
                () -> processor.forEachOrdered(List.of(1), new BatchOptions(1), x -> null, x -> {}));
        assertInstanceOf(NullPointerException.class, error.getCause());
    }

    @Test
    void callerInterruptionCancelsOutstandingWorker() throws Exception {
        final var started = new CountDownLatch(1);
        final var stopped = new CountDownLatch(1);
        final var outcome = new java.util.concurrent.CompletableFuture<Throwable>();
        final var caller = Thread.ofVirtual().start(() -> {
            try {
                BatchProcessor.create().forEachOrdered(List.of(1), new BatchOptions(1),
                        x
                        -> {
                            started.countDown();
                            try {
                                new CountDownLatch(1).await();
                                return x;
                            } finally {
                                stopped.countDown();
                            }
                        },
                        x -> {});
                outcome.complete(new AssertionError("Expected interruption"));
            } catch (final Throwable error) {
                outcome.complete(error);
            }
        });
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            caller.interrupt();
            assertInstanceOf(InterruptedException.class, outcome.get(5, TimeUnit.SECONDS));
            assertTrue(stopped.await(5, TimeUnit.SECONDS));
        } finally {
            caller.interrupt();
        }
    }

    @Test
    void legacyCustomServicesExplicitlyRejectStreaming() {
        final DocumentBatch documents = (paths, partition, strategy, chunks, window) -> List.of();
        final ElementFileBatch elements = (paths, strategy, chunks, window) -> List.of();
        assertThrows(UnsupportedOperationException.class,
                ()
                        -> documents.partitionAndChunkEach(List.of(), PartitionOptions.defaults(),
                                Chunker.Strategy.BASIC, ChunkOptions.defaults(), new BatchOptions(1), x -> {}));
        assertThrows(UnsupportedOperationException.class,
                ()
                        -> elements.chunkEach(List.of(), Chunker.Strategy.BASIC, ChunkOptions.defaults(),
                                new BatchOptions(1), x -> {}));
    }

    @Test
    void rawAndSavedDocumentsDeliverIncrementally() throws Exception {
        final var text = temp.resolve("input.txt");
        Files.writeString(text, "Hello streaming batch.");
        final var raw = new ArrayList<DocumentBatch.Result>();
        DocumentBatch.create().partitionAndChunkEach(List.of(text), PartitionOptions.defaults(), Chunker.Strategy.BASIC,
                ChunkOptions.defaults(), new BatchOptions(2), raw::add);
        assertEquals(1, raw.size());
        assertFalse(raw.getFirst().chunks().isEmpty());
        final var json = temp.resolve("input.json");
        Files.writeString(json,
                "[{\"type\":\"NarrativeText\",\"element_id\":\"saved\",\"text\":\"Saved text\",\"metadata\":{}}]");
        final var saved = new ArrayList<ElementFileBatch.Result>();
        ElementFileBatch.create().chunkEach(
                List.of(json), Chunker.Strategy.BASIC, ChunkOptions.defaults(), new BatchOptions(1), saved::add);
        assertEquals("Saved text", saved.getFirst().chunks().getFirst().text());
    }
}
