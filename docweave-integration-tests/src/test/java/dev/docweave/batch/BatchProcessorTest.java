package dev.docweave.batch;

import dev.docweave.chunking.ChunkOptions;
import dev.docweave.chunking.Chunker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class BatchProcessorTest {
    @Test
    void usesVirtualThreadsWithBoundedConcurrencyAndInputOrder() throws Exception {
        final var active = new AtomicInteger();
        final var peak = new AtomicInteger();
        final var inputs = IntStream.range(0, 30).boxed().toList();
        final var outputs = BatchProcessor.create().mapOrdered(inputs, 3, input -> {
            assertTrue(Thread.currentThread().isVirtual());
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                Thread.sleep(5);
                return input * 2;
            } finally {
                active.decrementAndGet();
            }
        });
        assertEquals(inputs.stream().map(i -> i * 2).toList(), outputs);
        assertTrue(peak.get() <= 3);
        assertEquals(0, active.get());
    }

    @Test
    void failureCancelsInFlightWork() throws Exception {
        final var started = new CountDownLatch(1);
        final var cancelled = new CountDownLatch(1);
        assertThrows(ExecutionException.class, () -> BatchProcessor.create().mapOrdered(List.of(0, 1), 2, input -> {
            if (input == 0) {
                started.await();
                throw new IllegalStateException("failure");
            }
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (final InterruptedException expected) {
                cancelled.countDown();
                throw expected;
            }
            return input;
        }));
        assertEquals(0, cancelled.getCount());
    }

    @Test
    void callerInterruptionCancelsRunningTask() throws Exception {
        final var started = new CountDownLatch(1);
        final var cancelled = new CountDownLatch(1);
        final var callerFinished = new CountDownLatch(1);
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var caller = executor.submit(() -> {
                try {
                    return BatchProcessor.create().mapOrdered(List.of(1), 1, input -> {
                        started.countDown();
                        try {
                            new CountDownLatch(1).await();
                        } catch (final InterruptedException expected) {
                            cancelled.countDown();
                            throw expected;
                        }
                        return input;
                    });
                } finally {
                    callerFinished.countDown();
                }
            });
            started.await();
            caller.cancel(true);
        }
        // A cancelled outer Future is not proof that the batch call itself has unwound.
        assertTrue(callerFinished.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, cancelled.getCount());
    }

    @Test
    void loadsAndChunksFiles(final @TempDir Path directory) throws Exception {
        final var input = directory.resolve("elements.json");
        Files.writeString(input, """
                [{"type":"Title","element_id":"one","text":"Heading","metadata":{}},
                 {"type":"NarrativeText","element_id":"two","text":"Body","metadata":{}}]
                """);
        final var results =
                ElementFileBatch.create().chunk(List.of(input), Chunker.Strategy.BASIC, ChunkOptions.defaults(), 2);
        assertEquals(input, results.getFirst().source());
        assertEquals("Heading\n\nBody", results.getFirst().chunks().getFirst().text());
    }

    @Test
    void rejectsInvalidLimits() {
        assertThrows(
                IllegalArgumentException.class, () -> BatchProcessor.create().mapOrdered(List.of(), 0, input -> input));
    }
}
