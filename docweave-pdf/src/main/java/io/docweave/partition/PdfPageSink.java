package io.docweave.partition;

import io.docweave.core.Element;
import io.docweave.core.ElementType;

import java.io.IOException;
import java.util.Map;

/** Destination of the ordered PDF page writer: a partition context, or a page-range collector. */
interface PdfPageSink {
    /**
     * Appends one element with source metadata and an ID.
     * @param type semantic category
     * @param text extracted text
     * @param metadata page-local metadata without source keys
     * @return emitted immutable element
     * @throws IOException if cancelled or emission fails
     */
    Element emit(ElementType type, String text, Map<String, Object> metadata) throws IOException;

    /**
     * Records one warning in order.
     * @param code category
     * @param message content-free diagnostic
     */
    void warn(PartitionResult.WarningCode code, String message);

    /**
     * Returns the number of elements emitted so far, for the historical buffered-page check.
     * @return element count
     */
    int elementCount();

    /**
     * Returns the document options.
     * @return options supplying maxElements
     */
    PartitionOptions options();

    /**
     * Receives the end of one physical page, after all of its elements and warnings.
     * @param page one-based physical page
     * @param boundary continuation evidence of the page's single candidate table, or null
     * @throws IOException if downstream assembly fails
     */
    default void pageFinished(final int page, final PdfTableBoundary boundary) throws IOException {
    }

    /**
     * Adapts a partition context; page ends are not observed.
     * @param context document sink
     * @return adapter
     */
    static PdfPageSink of(final PartitionContext context) {
        return new PdfPageSink() {
            @Override
            public Element emit(final ElementType type, final String text, final Map<String, Object> metadata)
                    throws IOException {
                return context.emit(type, text, metadata);
            }

            @Override
            public void warn(final PartitionResult.WarningCode code, final String message) {
                context.warn(code, message);
            }

            @Override
            public int elementCount() {
                return context.elementCount();
            }

            @Override
            public PartitionOptions options() {
                return context.options();
            }
        };
    }
}
