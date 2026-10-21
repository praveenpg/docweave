package dev.docweave.batch;

import dev.docweave.DocWeave;
import dev.docweave.chunking.ChunkOptions;
import dev.docweave.chunking.Chunker;
import dev.docweave.core.Element;
import dev.docweave.partition.PartitionOptions;
import dev.docweave.partition.PartitionResult;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Thread-safe raw-document partition/chunk batches on bounded virtual threads.
 * Files are read locally and processed synchronously within each task. PartitionOptions bounds
 * document resources; maxInFlight bounds concurrency. BatchOptions adds optional elapsed budgets.
 * No output is written, and results retain
 * all partition elements and chunks in memory.
 * <pre>{@code
 * var results = DocumentBatch.create().partitionAndChunk(paths,
 *     PartitionOptions.defaults(), Chunker.Strategy.BY_TITLE,
 *     ChunkOptions.defaults(), 4);
 * }</pre>
 */
public interface DocumentBatch {
    /**
     * Creates the built-in thread-safe implementation behind this contract.
     * @return a reusable DocumentBatch service
     */
    static DocumentBatch create() {
        return new DefaultDocumentBatch();
    }

    /**
     * Creates a batch service with injectable processor and chunking contracts.
     * Use a registry-configured DocWeave processor to apply custom strategies in virtual-thread batches.
     * @param processor thread-safe processor, possibly using custom runtime partitioners
     * @param chunker thread-safe chunker
     * @return reusable batch service
     * @throws NullPointerException if either dependency is null
     */
    static DocumentBatch create(final DocWeave processor, final Chunker chunker) {
        return new DefaultDocumentBatch(processor, chunker);
    }

    /**
     * Diagnostics and chunks associated with one source.
     * @param source original local path
     * @param partition successful partition result
     * @param chunks immutable ordered chunks
     */
    record Result(Path source, PartitionResult partition, List<Element> chunks) {
        /**
         * Snapshots a chunk list.
         * @param source original path
         * @param partition successful partition result
         * @param chunks chunks to snapshot
         * @throws NullPointerException if chunks or a chunk is null
         */
        public Result {
            chunks = List.copyOf(chunks);
        }
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
    List<Result> partitionAndChunk(final List<Path> paths, final PartitionOptions partitionOptions,
            final Chunker.Strategy strategy, final ChunkOptions chunkOptions, final int maxInFlight)
            throws InterruptedException, ExecutionException;

    /**
     * Partitions and chunks with optional elapsed-time budgets. A task's budget covers local source
     * loading, decoding, partitioning and chunking together. Batch time excludes validation/input
     * snapshotting. Successful results retain input order; enabled deadlines observe failures
     * independently. Timeout causes expose scope/input index via BatchTimeoutException. Outstanding
     * work is interrupted without indefinite cleanup waits; custom processors/chunkers must cooperate
     * with interruption and may outlive the call if they ignore it. No partial results are returned.
     * @param paths ordered local input paths
     * @param partitionOptions immutable source/format policies
     * @param strategy chunking strategy
     * @param chunkOptions immutable chunk policies
     * @param batchOptions nonnull concurrency/deadline policy
     * @return immutable results in input order
     * @throws InterruptedException if the caller is interrupted
     * @throws ExecutionException if processing fails or a deadline expires
     * @throws NullPointerException if a required argument or path is null
     * @throws UnsupportedOperationException if a custom implementation does not support enabled deadlines
     */
    default List<Result> partitionAndChunk(final List<Path> paths, final PartitionOptions partitionOptions,
            final Chunker.Strategy strategy, final ChunkOptions chunkOptions, final BatchOptions batchOptions)
            throws InterruptedException, ExecutionException {
        java.util.Objects.requireNonNull(batchOptions, "batchOptions");
        if (batchOptions.hasDeadlines()) {
            throw new UnsupportedOperationException("This document batch does not implement deadlines");
        }
        return partitionAndChunk(paths, partitionOptions, strategy, chunkOptions, batchOptions.maxInFlight());
    }

    /**
     * Delivers results incrementally in input order without accumulating the batch.
     * See {@link BatchProcessor#forEachOrdered} for backpressure, partial delivery, deadline,
     * cancellation and source ownership rules. Each file still materializes its own elements/chunks.
     * Custom implementations must override this method to support incremental delivery.
     * @param paths lazy local input paths
     * @param partitionOptions source and format policies
     * @param strategy chunking strategy
     * @param chunkOptions chunk policy
     * @param batchOptions concurrency and optional deadlines
     * @param consumer sequential receiver on the calling thread
     * @throws InterruptedException if the caller/consumer is interrupted
     * @throws ExecutionException if processing/delivery fails or a deadline expires
     * @throws NullPointerException if a required argument or input is null
     * @throws RuntimeException if input iteration fails
     * @throws UnsupportedOperationException if a custom implementation lacks incremental delivery
     */
    default void partitionAndChunkEach(final Iterable<Path> paths, final PartitionOptions partitionOptions,
            final Chunker.Strategy strategy, final ChunkOptions chunkOptions, final BatchOptions batchOptions,
            final BatchProcessor.ResultConsumer<? super Result> consumer)
            throws InterruptedException, ExecutionException {
        throw new UnsupportedOperationException("Incremental delivery is not implemented");
    }
}
