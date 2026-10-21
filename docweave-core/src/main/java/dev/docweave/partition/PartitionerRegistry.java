package dev.docweave.partition;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Runtime strategy lookup keyed by document format.
 * Built-in registries are immutable and thread-safe. Strategies must separately satisfy the
 * {@link Partitioner} concurrency contract. Custom registries can select implementations dynamically,
 * but their capabilities and lookups must be consistent. Providers use the standard JDK service discovery mechanism.
 */
public interface PartitionerRegistry {
    /**
     * Discovers installed format modules through {@link java.util.ServiceLoader}.
     * @return immutable lookup; empty when no format modules are installed
     * @throws java.util.ServiceConfigurationError if a provider cannot be loaded
     * @throws IllegalStateException if installed providers duplicate or misdeclare a format
     */
    static PartitionerRegistry create() {
        return builder().build();
    }

    /**
     * Creates a registry containing only the supplied strategies.
     * @param partitioners strategies with distinct format keys
     * @return immutable registry; an empty list creates an empty registry
     * @throws IllegalArgumentException if format keys are duplicated
     * @throws NullPointerException if required values are null
     */
    static PartitionerRegistry of(final List<Partitioner> partitioners) {
        return new DefaultPartitionerRegistry(partitioners);
    }

    /**
     * Creates a builder prepopulated with discovered native document strategies.
     * @return caller-owned mutable builder
     * @throws java.util.ServiceConfigurationError if a provider cannot be loaded
     * @throws IllegalStateException if installed providers duplicate or misdeclare a format
     */
    static Builder builder() {
        return new DefaultPartitionerRegistryBuilder();
    }

    /**
     * Looks up a runtime strategy after source detection.
     * @param format nonnull selected format
     * @return strategy, or empty when unavailable
     * @throws NullPointerException if format is null
     */
    Optional<Partitioner> find(final DocumentFormat format);
    /**
     * Advertises registered formats.
     * @return immutable supported-format snapshot
     */
    Set<DocumentFormat> supportedFormats();
    /** Mutable, non-thread-safe configuration contract; built registries snapshot its state. */
    interface Builder {
        /**
         * Registers or replaces a format's strategy.
         * @param partitioner stateless/thread-safe strategy with a stable nonnull format
         * @return this builder
         * @throws NullPointerException if strategy or format is null
         */
        Builder register(final Partitioner partitioner);
        /**
         * Snapshots configured strategies.
         * @return immutable registry
         */
        PartitionerRegistry build();
    }
}
