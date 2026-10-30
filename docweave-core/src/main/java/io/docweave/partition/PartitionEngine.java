package io.docweave.partition;

import io.docweave.DocWeave;

import java.io.IOException;

/**
 * Stateless native parser orchestration for {@link io.docweave.DocWeave}.
 * Prefer the facade in application code. Each call creates independent collectors/parsers,
 * allowing concurrent reuse. Installed format modules supply text and binary strategies.
 */
public interface PartitionEngine extends DocWeave {
    /**
     * Creates native source-loading/partitioning orchestration.
     * Prefer {@link DocWeave#create()} in application code.
     * @return a thread-safe native partition engine
     */
    static PartitionEngine create() {
        return new NativePartitionEngine(PartitionerRegistry.create());
    }

    /**
     * Creates native orchestration with injectable runtime dispatch.
     * @param registry thread-safe lookup whose strategies support concurrent calls
     * @return reusable partition engine
     * @throws NullPointerException if registry is null
     */
    static PartitionEngine create(final PartitionerRegistry registry) {
        return new NativePartitionEngine(registry);
    }

    /**
     * Starts explicit configuration of a native engine; prefer {@link DocWeave#builder()} in application code.
     * @return a new mutable builder
     */
    static DocWeave.Builder builder() {
        return new DefaultEngineBuilder();
    }

    /**
     * Loads, detects and partitions an input synchronously.
     * @param source local input
     * @param options immutable processing policy
     * @return immutable elements and diagnostics
     * @throws PartitionException for invalid encoding, unsupported content or exceeded limits
     * @throws java.io.InterruptedIOException at an interrupted processing checkpoint
     * @throws IOException if source I/O fails
     * @throws NullPointerException if either argument is null
     */
    PartitionResult partition(final DocumentSource source, final PartitionOptions options) throws IOException;
}
