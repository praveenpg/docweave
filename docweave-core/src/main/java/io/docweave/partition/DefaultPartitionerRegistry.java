package io.docweave.partition;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable format-key snapshot; registered strategies are required to support concurrent calls. */
final class DefaultPartitionerRegistry implements PartitionerRegistry {
    /** Immutable mapping from supported document format to its registered partitioner strategy. */
    private final Map<DocumentFormat, Partitioner> strategies;

    /**
     * Creates an immutable registry from the provided list of partitioners, rejecting duplicate formats.
     *
     * @param partitioners partitioner strategies to register
     */
    DefaultPartitionerRegistry(final List<Partitioner> partitioners) {
        final var map = new EnumMap<DocumentFormat, Partitioner>(DocumentFormat.class);
        for (final var partitioner : List.copyOf(partitioners)) {
            final var format = Objects.requireNonNull(partitioner.format(), "partitioner format");
            if (map.putIfAbsent(format, partitioner) != null) {
                throw new IllegalArgumentException("Duplicate partitioner format: " + format);
            }
        }
        strategies = Map.copyOf(map);
    }

    /**
     * Looks up the registered partitioner strategy for {@code format}.
     *
     * @param format non-null document format to look up
     * @return matching {@link Partitioner}, or {@link Optional#empty()} if not registered
     */
    public Optional<Partitioner> find(final DocumentFormat format) {
        return Optional.ofNullable(strategies.get(Objects.requireNonNull(format)));
    }

    /**
     * Returns the immutable set of document formats supported by this registry.
     *
     * @return unmodifiable set of registered {@link DocumentFormat} values
     */
    public Set<DocumentFormat> supportedFormats() {
        return strategies.keySet();
    }
}
