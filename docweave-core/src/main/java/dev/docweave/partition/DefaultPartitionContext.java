package dev.docweave.partition;

import dev.docweave.core.Element;
import dev.docweave.core.ElementType;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

/** Document-confined adapter preserving common invariants across native and custom strategies. */
final class DefaultPartitionContext implements PartitionContext {
    /** Loaded document content (decoded text or binary payload, filename, and detected format). */
    private final SourceLoader.Content content;
    /** Immutable partition options for the current document. */
    private final PartitionOptions options;
    /** Collector receiving emitted document elements. */
    private final ElementCollector collector;
    /** Mutable warning list accumulating non-fatal partition diagnostics. */
    private final List<PartitionResult.Warning> warnings;
    /** Whether this context is currently inside an active partition call. */
    private boolean active = true;

    /**
     * Creates a partition context for a single document invocation.
     *
     * @param content loaded document content and metadata
     * @param options immutable partition options
     * @param collector element collector enforcing output limits
     * @param warnings mutable warning list
     */
    DefaultPartitionContext(final SourceLoader.Content content, final PartitionOptions options,
            final ElementCollector collector, final List<PartitionResult.Warning> warnings) {
        this.content = content;
        this.options = options;
        this.collector = collector;
        this.warnings = warnings;
    }

    /**
     * Returns the decoded document text for text-based formats.
     *
     * @return decoded text string
     */
    public String text() {
        if (content.text() == null) {
            throw new IllegalStateException("Binary source has no decoded text");
        }
        return content.text();
    }

    /**
     * Returns a defensive copy of the binary document payload for archive/binary formats.
     *
     * @return cloned binary content bytes
     */
    public byte[] binaryContent() {
        if (content.binary() == null) {
            throw new IllegalStateException("No binary payload");
        }
        return content.binary().clone();
    }

    /**
     * Borrows immutable framework-owned binary bytes through an independent read-only cursor.
     * @return complete read-only snapshot view without another payload-array copy
     * @throws IllegalStateException if the source has no binary payload
     */
    @Override
    public ByteBuffer binaryBuffer() {
        if (content.binary() == null) {
            throw new IllegalStateException("No binary payload");
        }
        return ByteBuffer.wrap(content.binary()).asReadOnlyBuffer();
    }

    /**
     * Returns the optional source filename associated with the document.
     *
     * @return source filename, or {@code null} if unavailable
     */
    public String filename() {
        return content.filename();
    }

    /**
     * Returns the resolved document format for this partition invocation.
     *
     * @return resolved {@link DocumentFormat}
     */
    public DocumentFormat format() {
        return content.format();
    }

    /**
     * Returns the active partition options.
     *
     * @return immutable {@link PartitionOptions}
     */
    public PartitionOptions options() {
        return options;
    }

    /**
     * Returns the number of elements emitted so far in this context.
     *
     * @return current emitted element count
     */
    public int elementCount() {
        return collector.size();
    }

    /**
     * Returns the previously emitted element at the given 0-based index.
     *
     * @param index 0-based element index
     * @return emitted {@link Element} at {@code index}
     */
    public Element elementAt(final int index) {
        return collector.elementAt(index);
    }

    /**
     * Appends a new semantic element with context-supplied filename/filetype metadata.
     *
     * @param type semantic element category
     * @param text element text content
     * @param metadata additional element metadata (must not contain {@code filename} or {@code filetype})
     * @return newly created and recorded {@link Element}
     * @throws IOException if the element limit is exceeded or the thread is interrupted
     */
    public Element emit(final ElementType type, final String text, final Map<String, Object> metadata)
            throws IOException {
        ensureActive();
        if (metadata.containsKey("filename") || metadata.containsKey("filetype")) {
            throw new IllegalArgumentException("filename and filetype metadata are supplied by the partition context");
        }
        return collector.add(type, text, metadata);
    }

    /**
     * Records a non-fatal warning diagnostic for the current document.
     *
     * @param code structured warning category
     * @param message human-readable diagnostic message
     */
    public void warn(final PartitionResult.WarningCode code, final String message) {
        ensureActive();
        warnings.add(new PartitionResult.Warning(code, message));
    }

    /**
     * Marks this context as completed so subsequent mutations are rejected.
     */
    void complete() {
        active = false;
    }

    /**
     * Verifies that this context is still active.
     */
    private void ensureActive() {
        if (!active) {
            throw new IllegalStateException("Partition context is no longer active");
        }
    }
}
