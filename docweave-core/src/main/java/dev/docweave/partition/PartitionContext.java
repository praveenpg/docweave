package dev.docweave.partition;

import dev.docweave.core.Element;
import dev.docweave.core.ElementType;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;

/**
 * Document-local input and emission contract for a parser strategy.
 * Mutable and confined to one synchronous call, not thread-safe. Emissions centralize IDs, source
 * metadata, output limits and interruption checks. Native contexts reject writes after completion.
 */
public interface PartitionContext {
    /**
     * Returns decoded input.
     * @return source text, possibly empty
     * @throws IllegalStateException if the source is a binary document
     */
    String text();

    /**
     * Returns a defensive copy of a binary document payload.
     * Text strategies should use {@link #text()} instead. The default supports text-only custom contexts.
     * @return binary source bytes
     * @throws IllegalStateException if this context has no binary payload
     */
    default byte[] binaryContent() {
        throw new IllegalStateException("No binary payload");
    }

    /**
     * Returns a read-only view of the binary source snapshot with an independent position and limit.
     * The view starts at position0 and spans the complete payload. It exposes no writable backing array;
     * changing its cursor does not affect a later view. The framework implementation avoids copying its
     * already-owned bytes. The default preserves compatibility with custom contexts by wrapping their
     * defensive {@link #binaryContent()} copy. Whole input bytes still materialize under existing limits.
     * @return independent read-only binary buffer
     * @throws IllegalStateException if this context has no binary payload
     */
    default ByteBuffer binaryBuffer() {
        return ByteBuffer.wrap(binaryContent()).asReadOnlyBuffer();
    }

    /**
     * Returns the metadata name.
     * @return filename, or null when absent
     */
    String filename();
    /**
     * Returns the selected format.
     * @return detected or explicit format
     */
    DocumentFormat format();
    /**
     * Returns immutable document policies.
     * @return processing options
     */
    PartitionOptions options();
    /**
     * Measures output already emitted.
     * @return nonnegative element count
     */
    int elementCount();
    /**
     * Looks up emitted output for hierarchy references.
     * @param index zero-based output index
     * @return previously emitted immutable element
     * @throws IndexOutOfBoundsException if the index is not in the emitted range
     */
    Element elementAt(final int index);
    /**
     * Appends one semantic unit with central IDs and filename/filetype metadata.
     * Do not override filename/filetype in strategy metadata. Output order is emission order.
     * @param type nonnull supported category
     * @param text nonnull extracted text
     * @param metadata JSON-compatible strategy metadata, deeply snapshotted
     * @return immutable emitted element with centrally generated ID
     * @throws PartitionException if the element budget is exceeded
     * @throws java.io.InterruptedIOException if the caller is interrupted
     * @throws IOException if emission fails
     * @throws IllegalArgumentException if metadata is invalid or overrides reserved source keys
     * @throws IllegalStateException if processing has completed
     * @throws NullPointerException if required arguments are null
     */
    Element emit(final ElementType type, final String text, final Map<String, Object> metadata) throws IOException;
    /**
     * Adds a nonfatal diagnostic to the result.
     * @param code warning category
     * @param message diagnostic without document content
     * @throws NullPointerException if either argument is null
     * @throws IllegalStateException if processing has completed
     */
    void warn(final PartitionResult.WarningCode code, final String message);
}
