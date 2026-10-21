package dev.docweave.batch;

import dev.docweave.chunking.ChunkOptions;
import dev.docweave.chunking.Chunker;
import dev.docweave.core.Element;
import dev.docweave.json.ElementJson;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Thread-safe batch chunking of saved UTF-8 compatibility element arrays.
 * Use {@link DocumentBatch} for raw native text-format documents. Each task reads then chunks synchronously;
 * the window bounds active files but returned results remain in memory.
 */
final class DefaultElementFileBatch implements ElementFileBatch {
    /** JSON element codec enforcing configured read limits. */
    private final ElementJson json;
    /** Stateless element chunker shared across batch tasks. */
    private final Chunker chunker = Chunker.create();

    /** Creates a reusable file-batch processor. */
    DefaultElementFileBatch() {
        this(dev.docweave.json.JsonReadOptions.defaults());
    }

    /**
     * Creates a reusable file-batch processor enforcing the given JSON read limits.
     * @param options nonnull JSON read limits
     */
    DefaultElementFileBatch(final dev.docweave.json.JsonReadOptions options) {
        json = ElementJson.create(options);
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
    public List<Result> chunk(final List<Path> inputs, final Chunker.Strategy strategy, final ChunkOptions options,
            final int maxInFlight) throws InterruptedException, ExecutionException {
        return BatchProcessor.create().mapOrdered(
                inputs, maxInFlight, path -> new Result(path, chunker.chunk(json.read(path), strategy, options)));
    }

    /**
     * Loads and chunks each saved element array under the given concurrency and deadline policy.
     * @param inputs ordered JSON file paths
     * @param strategy chunk grouping strategy
     * @param options immutable chunk policy
     * @param batchOptions concurrency and deadline policy
     * @return immutable per-file results in input order
     * @throws InterruptedException if the caller is interrupted while waiting
     * @throws ExecutionException if reading, decoding, chunking or a deadline fails
     */
    @Override
    public List<Result> chunk(final List<Path> inputs, final Chunker.Strategy strategy, final ChunkOptions options,
            final BatchOptions batchOptions) throws InterruptedException, ExecutionException {
        java.util.Objects.requireNonNull(strategy, "strategy");
        java.util.Objects.requireNonNull(options, "options");
        return BatchProcessor.create().mapOrdered(
                inputs, batchOptions, path -> new Result(path, chunker.chunk(json.read(path), strategy, options)));
    }

    /**
     * Loads and chunks each saved element array and delivers results in input order to {@code consumer}.
     * @param inputs ordered JSON file paths
     * @param strategy chunk grouping strategy
     * @param options immutable chunk policy
     * @param batchOptions concurrency and deadline policy
     * @param consumer callback receiving each completed file result in input order
     * @throws InterruptedException if the caller or consumer is interrupted
     * @throws ExecutionException if reading, decoding, chunking, delivery or a deadline fails
     */
    @Override
    public void chunkEach(final Iterable<Path> inputs, final Chunker.Strategy strategy, final ChunkOptions options,
            final BatchOptions batchOptions, final BatchProcessor.ResultConsumer<? super Result> consumer)
            throws InterruptedException, ExecutionException {
        java.util.Objects.requireNonNull(strategy, "strategy");
        java.util.Objects.requireNonNull(options, "options");
        BatchProcessor.create().forEachOrdered(inputs, batchOptions,
                path -> new Result(path, chunker.chunk(json.read(path), strategy, options)), consumer);
    }
}
