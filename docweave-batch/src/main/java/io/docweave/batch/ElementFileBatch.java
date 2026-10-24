package io.docweave.batch;

import io.docweave.chunking.ChunkOptions;
import io.docweave.chunking.Chunker;
import io.docweave.core.Element;
import io.docweave.partition.PartitionOptions;
import io.docweave.partition.PartitionResult;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Thread-safe batch chunking of saved UTF-8 compatibility element arrays.
 * Use {@link DocumentBatch} for raw native text-format documents. Each task reads then chunks synchronously;
 * the window bounds active files but returned results remain in memory.
 */
public interface ElementFileBatch {
    /**
     * Creates the built-in thread-safe implementation behind this contract.
     * @return a reusable ElementFileBatch service
     */
    static ElementFileBatch create() {
        return new DefaultElementFileBatch();
    }

    /**
     * Creates a saved-element batch service with bounded JSON reads on every execution path.
     * @param readOptions nonnull saved-element JSON resource policy
     * @return reusable thread-safe batch service
     * @throws NullPointerException if readOptions is null
     */
    static ElementFileBatch create(final io.docweave.json.JsonReadOptions readOptions) {
        return new DefaultElementFileBatch(readOptions);
    }

    /**
     * Output associated with its source file.
     * @param source original JSON path
     * @param chunks immutable ordered chunks
     */
    record Result(Path source, List<Element> chunks) {
        /**
         * Snapshots a chunk list.
         * @param source original path
         * @param chunks chunks to snapshot
         * @throws NullPointerException if chunks or an entry is null
         */
        public Result {
            chunks = List.copyOf(chunks);
        }
    }

    /**
     * Loads/chunks each saved element array independently.
     * @param inputs ordered JSON paths
     * @param strategy grouping strategy
     * @param options immutable chunk policy
     * @param maxInFlight positive outstanding-file bound
     * @return immutable per-file results in input order
     * @throws InterruptedException if the caller is interrupted while waiting
     * @throws ExecutionException if reading, decoding or chunking fails
     * @throws IllegalArgumentException if maxInFlight is not positive
     * @throws NullPointerException if inputs or an input entry is null
     */
    List<Result> chunk(final List<Path> inputs, final Chunker.Strategy strategy, final ChunkOptions options,
            final int maxInFlight) throws InterruptedException, ExecutionException;

    /**
     * Reads, decodes and chunks saved arrays with a single budget per input and an optional batch
     * budget. Enabled deadlines observe failure independently of input order, interrupt outstanding
     * work and return no partial results. Timeout causes are BatchTimeoutException; operations that
     * ignore interruption may outlive the call. See BatchProcessor's timed contract for cleanup limits.
     * @param inputs ordered saved JSON paths
     * @param strategy chunking strategy
     * @param options immutable chunk policies
     * @param batchOptions nonnull concurrency/deadline policy
     * @return immutable per-file results in input order
     * @throws InterruptedException if the caller is interrupted
     * @throws ExecutionException if reading/chunking fails or a deadline expires
     * @throws NullPointerException if a required argument or input is null
     * @throws UnsupportedOperationException if a custom implementation does not support enabled deadlines
     */
    default List<Result> chunk(final List<Path> inputs, final Chunker.Strategy strategy, final ChunkOptions options,
            final BatchOptions batchOptions) throws InterruptedException, ExecutionException {
        java.util.Objects.requireNonNull(batchOptions, "batchOptions");
        if (batchOptions.hasDeadlines()) {
            throw new UnsupportedOperationException("This element batch does not implement deadlines");
        }
        return chunk(inputs, strategy, options, batchOptions.maxInFlight());
    }

    /**
     * Delivers results incrementally in input order without accumulating the batch.
     * See {@link BatchProcessor#forEachOrdered} for backpressure, partial delivery, deadline,
     * cancellation and source ownership rules. Each file still materializes its own elements/chunks.
     * Custom implementations must override this method to support incremental delivery.
     * @param inputs lazy saved JSON paths
     * @param strategy chunking strategy
     * @param options chunk policy
     * @param batchOptions concurrency and optional deadlines
     * @param consumer sequential receiver on the calling thread
     * @throws InterruptedException if the caller/consumer is interrupted
     * @throws ExecutionException if processing/delivery fails or a deadline expires
     * @throws NullPointerException if a required argument or input is null
     * @throws RuntimeException if input iteration fails
     * @throws UnsupportedOperationException if a custom implementation lacks incremental delivery
     */
    default void chunkEach(final Iterable<Path> inputs, final Chunker.Strategy strategy, final ChunkOptions options,
            final BatchOptions batchOptions, final BatchProcessor.ResultConsumer<? super Result> consumer)
            throws InterruptedException, ExecutionException {
        throw new UnsupportedOperationException("Incremental delivery is not implemented");
    }
}
