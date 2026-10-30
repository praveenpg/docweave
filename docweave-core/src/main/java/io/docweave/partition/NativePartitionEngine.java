package io.docweave.partition;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;

/**
 * Stateless native parser orchestration for {@link io.docweave.DocWeave}.
 * Prefer the facade in application code. Each call creates independent collectors/parsers,
 * allowing concurrent reuse. Installed format modules supply text and binary strategies.
 */
final class NativePartitionEngine implements PartitionEngine {
    /** Default finite acquisition timeout for remote sources. */
    static final Duration DEFAULT_REMOTE_TIMEOUT = Duration.ofMinutes(2);

    /** Registry of format-specific partitioner strategies used by this engine. */
    private final PartitionerRegistry registry;

    /** Explicitly configured remote resolver, or null for local-only processing. */
    private final RemoteSourceResolver resolver;

    /** Finite acquisition timeout for remote sources. */
    private final Duration remoteTimeout;

    /**
     * Captures runtime strategies without retaining document-local parser state; local inputs only.
     *
     * @param registry non-null registry of format partitioners
     */
    NativePartitionEngine(final PartitionerRegistry registry) {
        this(registry, null, DEFAULT_REMOTE_TIMEOUT);
    }

    /**
     * Captures runtime strategies and an optional remote resolver.
     *
     * @param registry non-null registry of format partitioners
     * @param resolver remote resolver, or null when Remote sources are not supported
     * @param remoteTimeout positive finite acquisition timeout
     */
    NativePartitionEngine(
            final PartitionerRegistry registry, final RemoteSourceResolver resolver, final Duration remoteTimeout) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.resolver = resolver;
        this.remoteTimeout = Objects.requireNonNull(remoteTimeout, "remoteTimeout");
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
        if (source instanceof final DocumentSource.Remote remote) {
            return partitionRemote(remote, options, start);
        }
        return partition(new SourceLoader().load(source, options), options, start, null);
    }

    /**
     * Resolves a remote source through the configured resolver, parses the owned local content and always closes it.
     *
     * @param remote remote source
     * @param options processing policy
     * @param start call start on the nano clock
     * @return result with safe provenance
     * @throws IOException on acquisition, loading, parsing or cleanup failure
     */
    private PartitionResult partitionRemote(
            final DocumentSource.Remote remote, final PartitionOptions options, final long start) throws IOException {
        if (resolver == null) {
            throw new IllegalArgumentException(
                    "Remote sources need a RemoteSourceResolver configured with DocWeave.builder().remoteSources(...)");
        }
        final var context = new DefaultResolutionContext(options.maxInputBytes(), remoteTimeout);
        final ResolvedDocument resolved = resolver.resolve(remote, context);
        if (resolved == null) {
            throw new IllegalStateException("RemoteSourceResolver returned no document");
        }
        try (resolved) {
            final var loader = new SourceLoader();
            final SourceLoader.Content content;
            if (resolved instanceof final DefaultResolvedDocument owned) {
                content = loader.load(owned, options);
            } else {
                final DocumentSource local = resolved.localSource();
                if (local == null || local instanceof DocumentSource.Remote) {
                    throw new IllegalStateException("RemoteSourceResolver must return a local source");
                }
                content = loader.loadResolved(local, resolved.filename(), options);
            }
            return partition(content, options, start, Objects.requireNonNull(resolved.provenance(), "provenance"));
        }
    }

    /**
     * Detects the parser and partitions loaded content.
     *
     * @param content loaded content
     * @param options processing policy
     * @param start call start on the nano clock
     * @param provenance remote provenance or null
     * @return immutable result
     * @throws IOException on parsing failure or interruption
     */
    private PartitionResult partition(final SourceLoader.Content content, final PartitionOptions options,
            final long start, final SourceProvenance provenance) throws IOException {
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
                Duration.ofNanos(System.nanoTime() - start), warnings, provenance);
    }
}
