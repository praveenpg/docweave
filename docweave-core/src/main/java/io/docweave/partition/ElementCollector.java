package io.docweave.partition;

import io.docweave.core.Element;
import io.docweave.core.ElementType;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Document-local output collector enforcing element limits and cooperative interruption.
 * IDs use the upstream filename/text/page/sequence hash shape, with page absent for native text formats.
 * Equal IDs across parsers are only expected when their text and segmentation are identical.
 */
final class ElementCollector {
    /** Optional source filename propagated into emitted element metadata and ID hashing. */
    private final String filename;
    /** Resolved document format supplying the {@code filetype} MIME metadata. */
    private final DocumentFormat format;
    /** Maximum allowed number of emitted elements for the current document. */
    private final int maxElements;
    /** Ordered list of elements emitted during the current partition invocation. */
    private final List<Element> elements = new ArrayList<>();
    /** Deterministic element ID generator scoped to the current document. */
    private final ElementIdGenerator ids = new ElementIdGenerator();

    /**
     * Creates an element collector for a single document partition invocation.
     *
     * @param filename optional source filename, or {@code null}
     * @param format resolved document format
     * @param maxElements maximum allowed emitted element count
     */
    ElementCollector(final String filename, final DocumentFormat format, final int maxElements) {
        this.filename = filename;
        this.format = format;
        this.maxElements = maxElements;
    }

    /**
     * Creates, records, and returns a new semantic element with deterministic ID and standard metadata.
     *
     * @param type semantic element category
     * @param text normalized element text
     * @param extra format-specific metadata entries
     * @return newly created {@link Element}
     * @throws java.io.IOException if {@link #maxElements} is exceeded or the thread is interrupted
     */
    Element add(final ElementType type, final String text, final Map<String, Object> extra) throws java.io.IOException {
        checkInterrupted();
        if (elements.size() >= maxElements) {
            throw new PartitionException(
                    PartitionException.Code.ELEMENT_LIMIT, "Document exceeds maxElements=" + maxElements);
        }
        final var metadata = new LinkedHashMap<String, Object>();
        if (filename != null) {
            metadata.put("filename", filename);
        }
        metadata.put("filetype", format.mimeType());
        metadata.putAll(extra);
        final String id = ids.create(filename, text, elements.size());
        final var element = new Element(id, type, text, metadata);
        elements.add(element);
        return element;
    }

    /**
     * Returns an immutable snapshot of all collected elements in emission order.
     *
     * @return immutable list of emitted elements
     */
    List<Element> elements() {
        return List.copyOf(elements);
    }

    /**
     * Returns the number of elements collected so far.
     *
     * @return emitted element count
     */
    int size() {
        return elements.size();
    }

    /**
     * Returns the collected element at the specified 0-based index.
     *
     * @param index 0-based element ordinal
     * @return element at {@code index}
     */
    Element elementAt(final int index) {
        return elements.get(index);
    }

    /**
     * Throws {@link InterruptedIOException} if the current thread has been interrupted.
     *
     * @throws InterruptedIOException if the current thread's interrupted status is set
     */
    static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Document processing interrupted");
        }
    }
}
