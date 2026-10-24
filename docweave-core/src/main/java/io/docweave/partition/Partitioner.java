package io.docweave.partition;

import java.io.IOException;

/**
 * Runtime document-parser strategy selected after common source detection/decoding.
 * Implementations must be stateless or thread-safe: one strategy may process concurrent documents.
 * Keep document state inside {@link #partition(PartitionContext)}. Emit synchronously and do not
 * retain the context or submit emissions after returning. Shared IDs/limits remain outside the parser.
 * <pre>{@code
 * Partitioner custom = new Partitioner() {
 *     public DocumentFormat format() { return DocumentFormat.HTML; }
 *     public void partition(PartitionContext context) throws IOException {
 *         context.emit(ElementType.NarrativeText, "Custom extraction", Map.of());
 *     }
 * };
 * var registry = PartitionerRegistry.builder().register(custom).build();
 * DocWeave processor = DocWeave.create(registry);
 * }</pre>
 */
public interface Partitioner {
    /**
     * Declares this strategy's registry key.
     * @return nonnull format, stable throughout the instance lifetime
     */
    DocumentFormat format();
    /**
     * Extracts one text or binary document through a document-confined context.
     * @param context valid only for this synchronous invocation
     * @throws IOException if extraction, limits or interruption fails
     */
    void partition(final PartitionContext context) throws IOException;

    /**
     * Declares whether this strategy honors nested-table metadata preservation and its budgets.
     * The default is false, preserving compatibility with custom strategies. Advertising support
     * is a contract to implement the selected policy without silently reverting to flattening.
     * A strategy may restrict preservation to a documented extraction mode; unsupported mode/provider
     * combinations must fail explicitly before parsing or emitting content.
     * @return true only when PRESERVE_METADATA is implemented in a supported configuration
     */
    default boolean supportsNestedTablePreservation() {
        return false;
    }
}
