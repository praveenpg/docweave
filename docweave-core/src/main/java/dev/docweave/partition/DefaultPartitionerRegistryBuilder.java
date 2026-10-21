package dev.docweave.partition;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Objects;

/** Caller-owned registry builder with explicit replace-on-register behavior. */
final class DefaultPartitionerRegistryBuilder implements PartitionerRegistry.Builder {
    /** Mutable map of format strategies accumulated by this builder. */
    private final EnumMap<DocumentFormat, Partitioner> strategies = new EnumMap<>(DocumentFormat.class);

    /**
     * Creates a registry builder pre-populated with all classpath {@link PartitionerProvider} services.
     */
    DefaultPartitionerRegistryBuilder() {
        for (final var provider : java.util.ServiceLoader.load(PartitionerProvider.class)) {
            final var strategy = Objects.requireNonNull(provider.create(), "provider strategy");
            if (strategy.format() != provider.format()) {
                throw new IllegalStateException("Provider format mismatch: " + provider.getClass().getName());
            }
            if (strategies.containsKey(strategy.format())) {
                throw new IllegalStateException("Duplicate installed format: " + strategy.format());
            }
            register(strategy);
        }
    }

    /**
     * Registers or replaces the partitioner strategy for its declared {@link Partitioner#format()}.
     *
     * @param partitioner non-null partitioner strategy to register
     * @return this builder
     */
    public PartitionerRegistry.Builder register(final Partitioner partitioner) {
        Objects.requireNonNull(partitioner, "partitioner");
        strategies.put(Objects.requireNonNull(partitioner.format(), "partitioner format"), partitioner);
        return this;
    }

    /**
     * Builds an immutable {@link PartitionerRegistry} snapshot from the currently registered strategies.
     *
     * @return immutable partitioner registry
     */
    public PartitionerRegistry build() {
        return new DefaultPartitionerRegistry(new ArrayList<>(strategies.values()));
    }
}
