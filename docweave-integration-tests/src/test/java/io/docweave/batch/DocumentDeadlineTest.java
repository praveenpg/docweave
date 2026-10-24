package io.docweave.batch;

import io.docweave.DocWeave;
import io.docweave.chunking.ChunkOptions;
import io.docweave.chunking.Chunker;
import io.docweave.partition.DocumentFormat;
import io.docweave.partition.PartitionOptions;
import io.docweave.partition.PartitionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class DocumentDeadlineTest {
    private BatchOptions timed() {
        return new BatchOptions(2, Duration.ofSeconds(3), Duration.ofSeconds(5));
    }

    private PartitionResult emptyPartition() {
        return new PartitionResult(List.of(), DocumentFormat.TXT, "UTF-8", 0, Duration.ZERO, List.of());
    }

    @Test
    void nativeAndSavedElementBatchesExposeTheTimedPolicy(final @TempDir Path directory) throws Exception {
        final var txt = directory.resolve("first.txt");
        final var html = directory.resolve("second.html");
        Files.writeString(txt, "Heading\n\nBody.");
        Files.writeString(html, "<h1>Report</h1><p>Body.</p>");
        final var results = DocumentBatch.create().partitionAndChunk(List.of(txt, html), PartitionOptions.defaults(),
                Chunker.Strategy.BASIC, ChunkOptions.defaults(), timed());
        assertEquals(List.of(txt, html), results.stream().map(DocumentBatch.Result::source).toList());
        assertEquals(List.of(DocumentFormat.TXT, DocumentFormat.HTML),
                results.stream().map(r -> r.partition().format()).toList());
        final var saved = directory.resolve("elements.json");
        Files.writeString(
                saved, "[{\"type\":\"NarrativeText\",\"element_id\":\"a\",\"text\":\"Body.\",\"metadata\":{}}]");
        final var chunks = ElementFileBatch.create().chunk(
                List.of(saved), Chunker.Strategy.BASIC, ChunkOptions.defaults(), timed());
        assertEquals("Body.", chunks.getFirst().chunks().getFirst().text());
    }

    @Test
    void taskDeadlineIncludesInjectedPartitioningAndPropagatesCancellation() throws Exception {
        final var stopped = new CountDownLatch(1);
        final DocWeave processor = (source, options) -> {
            assertTrue(Thread.currentThread().isVirtual());
            try {
                new CountDownLatch(1).await();
            } catch (final InterruptedException error) {
                throw new InterruptedIOException("cancelled");
            } finally {
                stopped.countDown();
            }
            return emptyPartition();
        };
        final var failure = assertThrows(ExecutionException.class,
                ()
                        -> DocumentBatch.create(processor, Chunker.create())
                                .partitionAndChunk(List.of(Path.of("unused.txt")), PartitionOptions.defaults(),
                                        Chunker.Strategy.BASIC, ChunkOptions.defaults(),
                                        new BatchOptions(1, Duration.ofMillis(150), Duration.ZERO)));
        final var timeout = assertInstanceOf(BatchTimeoutException.class, failure.getCause());
        assertEquals(BatchTimeoutException.Scope.TASK, timeout.scope());
        assertEquals(0, timeout.inputIndex());
        assertTrue(stopped.await(2, TimeUnit.SECONDS));
    }

    @Test
    void sameTaskBudgetCoversChunkingAfterSuccessfulPartitioning() throws Exception {
        final var stopped = new CountDownLatch(1);
        final DocWeave processor = (source, options) -> emptyPartition();
        final Chunker chunker = (elements, strategy, options) -> {
            try {
                new CountDownLatch(1).await();
            } catch (final InterruptedException error) {
                throw new IllegalStateException("cancelled", error);
            } finally {
                stopped.countDown();
            }
            return List.of();
        };
        final var failure = assertThrows(ExecutionException.class,
                ()
                        -> DocumentBatch.create(processor, chunker)
                                .partitionAndChunk(List.of(Path.of("unused.txt")), PartitionOptions.defaults(),
                                        Chunker.Strategy.BASIC, ChunkOptions.defaults(),
                                        new BatchOptions(1, Duration.ofMillis(150), Duration.ZERO)));
        assertEquals(BatchTimeoutException.Scope.TASK,
                assertInstanceOf(BatchTimeoutException.class, failure.getCause()).scope());
        assertTrue(stopped.await(2, TimeUnit.SECONDS));
    }

    @Test
    void customLegacyDocumentAndElementServicesRejectUnimplementedDeadlines() throws Exception {
        final DocumentBatch documents = (paths, partition, strategy, chunks, window) -> List.of();
        final ElementFileBatch elements = (paths, strategy, chunks, window) -> List.of();
        assertEquals(List.of(), documents.partitionAndChunk(List.of(), PartitionOptions.defaults(),
                                        Chunker.Strategy.BASIC, ChunkOptions.defaults(), new BatchOptions(1)));
        assertEquals(List.of(),
                elements.chunk(List.of(), Chunker.Strategy.BASIC, ChunkOptions.defaults(), new BatchOptions(1)));
        assertThrows(UnsupportedOperationException.class,
                ()
                        -> documents.partitionAndChunk(List.of(), PartitionOptions.defaults(), Chunker.Strategy.BASIC,
                                ChunkOptions.defaults(), timed()));
        assertThrows(UnsupportedOperationException.class,
                () -> elements.chunk(List.of(), Chunker.Strategy.BASIC, ChunkOptions.defaults(), timed()));
    }
}
