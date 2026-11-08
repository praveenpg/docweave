package io.docweave.partition;

import io.docweave.core.Element;
import io.docweave.core.ElementType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Page-range sink of the ordered writer: builds elements exactly as the partition context does (filename and
 * filetype first, then page metadata) with provisional IDs from the range's own sequence, and hands each completed
 * page to a listener. It retains one page at a time.
 */
final class PdfRangeCollector implements PdfPageSink {
    /** Receives the document header and each completed page. */
    interface Listener {
        /**
         * Receives document-level facts before the first page.
         * @param documentPages physical page count of the whole document
         * @param documentWarnings document warnings in order
         * @throws IOException if downstream processing fails
         */
        void begin(int documentPages, List<PartitionResult.Warning> documentWarnings) throws IOException;

        /**
         * Receives one completed page.
         * @param page page elements, warnings and usage
         * @param boundary continuation evidence of the page's candidate table, or null
         * @throws IOException if downstream processing fails
         */
        void page(PdfRangeResult.Page page, PdfTableBoundary boundary) throws IOException;
    }

    /** Optional source filename. */
    private final String filename;
    /** Document options. */
    private final PartitionOptions options;
    /** Per-page usage recorder. */
    private final PdfBudgetUsage usage;
    /** Page consumer. */
    private final Listener listener;
    /** Context receiving elements directly with final IDs, or null to build provisional elements. */
    private final PartitionContext direct;
    /** Range-local ID generator. */
    private final ElementIdGenerator ids = new ElementIdGenerator();
    /** Range-local element sequence. */
    private int sequence;
    /** Current page's elements. */
    private List<Element> elements = new ArrayList<>();
    /** Current page's warnings. */
    private List<PartitionResult.Warning> warnings = new ArrayList<>();

    /**
     * Creates the collector of one range.
     * @param filename optional filename
     * @param options document options
     * @param usage usage recorder of the range's budget
     * @param listener page consumer
     */
    PdfRangeCollector(final String filename, final PartitionOptions options, final PdfBudgetUsage usage,
            final Listener listener) {
        this(filename, options, usage, listener, null);
    }

    /**
     * Creates the collector of one range, optionally emitting straight into a whole-document context. The context's
     * sequence then equals the range's, so the elements it returns already carry final IDs.
     * @param filename optional filename
     * @param options document options
     * @param usage usage recorder of the range's budget
     * @param listener page consumer
     * @param direct whole-document context, or null for provisional elements
     */
    PdfRangeCollector(final String filename, final PartitionOptions options, final PdfBudgetUsage usage,
            final Listener listener, final PartitionContext direct) {
        this.direct = direct;
        this.filename = filename;
        this.options = options;
        this.usage = usage;
        this.listener = listener;
    }

    @Override
    public Element emit(final ElementType type, final String text, final Map<String, Object> metadata)
            throws IOException {
        if (direct != null) {
            final var element = direct.emit(type, text, metadata);
            sequence++;
            elements.add(element);
            return element;
        }
        if (metadata.containsKey("filename") || metadata.containsKey("filetype")) {
            throw new IllegalArgumentException("filename and filetype metadata are supplied by the partition context");
        }
        ElementCollector.checkInterrupted();
        final var complete = new LinkedHashMap<String, Object>();
        if (filename != null) {
            complete.put("filename", filename);
        }
        complete.put("filetype", DocumentFormat.PDF.mimeType());
        complete.putAll(metadata);
        final var element = new Element(ids.create(filename, text, sequence), type, text, complete);
        sequence++;
        elements.add(element);
        return element;
    }

    @Override
    public void warn(final PartitionResult.WarningCode code, final String message) {
        warnings.add(new PartitionResult.Warning(code, message));
    }

    @Override
    public int elementCount() {
        return sequence;
    }

    @Override
    public PartitionOptions options() {
        return options;
    }

    @Override
    public void pageFinished(final int page, final PdfTableBoundary boundary) throws IOException {
        final var completed = new PdfRangeResult.Page(page, elements, warnings, usage.take(page));
        elements = new ArrayList<>();
        warnings = new ArrayList<>();
        listener.page(completed, boundary);
    }
}
