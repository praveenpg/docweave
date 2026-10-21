package dev.docweave.partition;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;

/**
 * Stateless native parser orchestration for {@link dev.docweave.DocWeave}.
 * Prefer the facade in application code. Each call creates independent collectors/parsers,
 * allowing concurrent reuse. Installed format modules supply text and binary strategies.
 */
final class NativePartitionEngine implements PartitionEngine {
    /** Registry of format-specific partitioner strategies used by this engine. */
    private final PartitionerRegistry registry;

    /**
     * Captures runtime strategies without retaining document-local parser state.
     *
     * @param registry non-null registry of format partitioners
     */
    NativePartitionEngine(final PartitionerRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Returns the set of document formats supported by the underlying partitioner registry.
     *
     * @return unmodifiable set of supported {@link DocumentFormat} values
     */
    public Set<DocumentFormat> supportedFormats() {
        return registry.supportedFormats();
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
    public PartitionResult partition(final DocumentSource source, final PartitionOptions options) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        ElementCollector.checkInterrupted();
        final long start = System.nanoTime();
        final var content = new SourceLoader().load(source, options);
        final var collector = new ElementCollector(content.filename(), content.format(), options.maxElements());
        final var warnings = new ArrayList<>(content.warnings());
        final var partitioner =
                registry.find(content.format())
                        .orElseThrow(()
                                             -> new PartitionException(PartitionException.Code.UNSUPPORTED_FORMAT,
                                                     "No partitioner registered for " + content.format()));
        NestedTableSupport.check(partitioner, options);
        final var context = new DefaultPartitionContext(content, options, collector, warnings);
        try {
            partitioner.partition(context);
        } finally {
            context.complete();
        }
        ElementCollector.checkInterrupted();
        return new PartitionResult(collector.elements(), content.format(), content.encoding(), content.bytes(),
                Duration.ofNanos(System.nanoTime() - start), warnings);
    }
}
