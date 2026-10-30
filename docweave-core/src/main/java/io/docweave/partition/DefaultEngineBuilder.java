package io.docweave.partition;

import io.docweave.DocWeave;

import java.time.Duration;
import java.util.Objects;

/**
 * Mutable configuration for {@link NativePartitionEngine}. The default registry is discovered only when
 * {@link #build()} runs without an explicit one; no resolver is ever created implicitly.
 */
final class DefaultEngineBuilder implements DocWeave.Builder {
    /** Longest accepted remote acquisition timeout. */
    private static final Duration MAX_REMOTE_TIMEOUT = Duration.ofDays(1);

    /** Explicit registry, or null for installed modules. */
    private PartitionerRegistry registry;

    /** Explicit remote resolver, or null. */
    private RemoteSourceResolver resolver;

    /** Remote acquisition timeout. */
    private Duration remoteTimeout = NativePartitionEngine.DEFAULT_REMOTE_TIMEOUT;

    /** Creates an empty builder. */
    DefaultEngineBuilder() {
    }

    @Override
    public DocWeave.Builder registry(final PartitionerRegistry value) {
        registry = Objects.requireNonNull(value, "registry");
        return this;
    }

    @Override
    public DocWeave.Builder remoteSources(final RemoteSourceResolver value) {
        resolver = Objects.requireNonNull(value, "resolver");
        return this;
    }

    @Override
    public DocWeave.Builder remoteTimeout(final Duration value) {
        Objects.requireNonNull(value, "remoteTimeout");
        if (value.isNegative() || value.isZero() || value.compareTo(MAX_REMOTE_TIMEOUT) > 0) {
            throw new IllegalArgumentException("remoteTimeout must be positive and at most one day");
        }
        remoteTimeout = value;
        return this;
    }

    @Override
    public DocWeave build() {
        return new NativePartitionEngine(
                registry != null ? registry : PartitionerRegistry.create(), resolver, remoteTimeout);
    }
}
