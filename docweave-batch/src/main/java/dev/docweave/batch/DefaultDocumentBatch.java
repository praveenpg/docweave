package dev.docweave.batch;

import dev.docweave.DocWeave;
import dev.docweave.chunking.ChunkOptions;
import dev.docweave.chunking.Chunker;
import dev.docweave.core.Element;
import dev.docweave.partition.DocumentSource;
import dev.docweave.partition.PartitionOptions;
import dev.docweave.partition.PartitionResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Thread-safe raw-document partition/chunk batches on bounded virtual threads.
 * Files are read locally and processed synchronously within each task. PartitionOptions bounds
 * document resources; maxInFlight bounds concurrency. No output is written, and results retain
 * all partition elements and chunks in memory.
 * <pre>{@code
 * var results = DocumentBatch.create().partitionAndChunk(paths,
 *     PartitionOptions.defaults(), Chunker.Strategy.BY_TITLE,
 *     ChunkOptions.defaults(), 4);
 * }</pre>
 */
final class DefaultDocumentBatch implements DocumentBatch {
    /** Document partitioner used by each worker task. */
    private final DocWeave processor;
    /** Element chunker applied to each partitioned document. */
    private final Chunker chunker;

    /** Creates a reusable processor without shared document state. */
    DefaultDocumentBatch() {
        this(DocWeave.create(), Chunker.create());
    }

    /**
     * Creates a batch processor backed by the given partitioner and chunker.
     * @param processor nonnull document partitioner
     * @param chunker nonnull element chunker
     */
    DefaultDocumentBatch(final DocWeave processor, final Chunker chunker) {
        this.processor = java.util.Objects.requireNonNull(processor, "processor");
        this.chunker = java.util.Objects.requireNonNull(chunker, "chunker");
    }

    /**
     * Partitions/chunks each file with bounded outstanding tasks.
     * Failure detection follows input order. Cancellation is cooperative; this overload has no deadline.
     * A failed batch returns no partial result.
     * @param paths ordered local file paths
     * @param partitionOptions decoding/format/resource policies
     * @param strategy grouping strategy
     * @param chunkOptions immutable chunk policy
     * @param maxInFlight positive outstanding-document bound
     * @return immutable results in input order
     * @throws InterruptedException if the caller is interrupted while waiting
     * @throws ExecutionException if source loading, partitioning or chunking fails
     * @throws IllegalArgumentException if maxInFlight is not positive
     * @throws NullPointerException if paths or a path is null
     */
    public List<Result> partitionAndChunk(final List<Path> paths, final PartitionOptions partitionOptions,
            final Chunker.Strategy strategy, final ChunkOptions chunkOptions, final int maxInFlight)
            throws InterruptedException, ExecutionException {
        return BatchProcessor.create().mapOrdered(
                paths, maxInFlight, path -> createPartitionResult(partitionOptions, strategy, chunkOptions, path));
    }

    /**
     * Partitions and chunks each file under the given concurrency and deadline policy.
     * @param paths ordered local file paths
     * @param partitionOptions decoding, format and resource policies
     * @param strategy chunk grouping strategy
     * @param chunkOptions immutable chunk policy
     * @param batchOptions concurrency and deadline policy
     * @return immutable per-document results in input order
     * @throws InterruptedException if the caller is interrupted while waiting
     * @throws ExecutionException if partitioning, chunking or a deadline fails
     */
    @Override
    public List<Result> partitionAndChunk(final List<Path> paths, final PartitionOptions partitionOptions,
            final Chunker.Strategy strategy, final ChunkOptions chunkOptions, final BatchOptions batchOptions)
            throws InterruptedException, ExecutionException {
        java.util.Objects.requireNonNull(partitionOptions, "partitionOptions");
        java.util.Objects.requireNonNull(strategy, "strategy");
        java.util.Objects.requireNonNull(chunkOptions, "chunkOptions");
        return BatchProcessor.create().mapOrdered(
                paths, batchOptions, path -> createPartitionResult(partitionOptions, strategy, chunkOptions, path));
    }

    /**
     * Partitions and chunks each file and delivers results in input order to {@code consumer}.
     * @param paths ordered local file paths
     * @param partitionOptions decoding, format and resource policies
     * @param strategy chunk grouping strategy
     * @param chunkOptions immutable chunk policy
     * @param batchOptions concurrency and deadline policy
     * @param consumer callback receiving each completed document result in input order
     * @throws InterruptedException if the caller or consumer is interrupted
     * @throws ExecutionException if partitioning, chunking, delivery or a deadline fails
     */
    @Override
    public void partitionAndChunkEach(final Iterable<Path> paths, final PartitionOptions partitionOptions,
            final Chunker.Strategy strategy, final ChunkOptions chunkOptions, final BatchOptions batchOptions,
            final BatchProcessor.ResultConsumer<? super Result> consumer)
            throws InterruptedException, ExecutionException {
        java.util.Objects.requireNonNull(partitionOptions, "partitionOptions");
        java.util.Objects.requireNonNull(strategy, "strategy");
        java.util.Objects.requireNonNull(chunkOptions, "chunkOptions");
        BatchProcessor.create().forEachOrdered(paths, batchOptions,
                path -> createPartitionResult(partitionOptions, strategy, chunkOptions, path), consumer);
    }

    /**
     * Loads, partitions and chunks one file within its existing batch worker task.
     * @param partitionOptions decoding, format and resource policies
     * @param strategy chunk grouping strategy
     * @param chunkOptions immutable chunk policy
     * @param path file assigned to this worker
     * @return partition and chunks associated with the input path
     * @throws IOException if loading or partitioning the source fails
     */
    private Result createPartitionResult(final PartitionOptions partitionOptions, final Chunker.Strategy strategy,
            final ChunkOptions chunkOptions, final Path path) throws IOException {
        final var partition = processor.partition(DocumentSource.of(path), partitionOptions);
        return new Result(path, partition, chunker.chunk(partition.elements(), strategy, chunkOptions));
    }
}
