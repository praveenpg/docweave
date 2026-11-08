package io.docweave.partition;

import io.docweave.core.Element;
import io.docweave.core.ElementType;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The single PDF assembly path. Whole-document partitioning feeds it one range covering every page, page by page as
 * pages are replayed; {@link PdfRangeAssembler} feeds it stored range results in page order. It emits document
 * warnings once, then each page's elements and warnings in page order, assigns final IDs through its output, rewrites
 * table-continuation references from provisional to final IDs and links tables across range boundaries exactly as the
 * ordered page writer links them within a range. It holds only the continuation state of the previous page.
 */
final class PdfRangeAssembly {
    /** Final element and warning destination. */
    interface Output {
        /**
         * Emits one final element; the output assigns its final ID from the document-wide sequence.
         * @param type category
         * @param text text
         * @param metadata complete metadata, starting with the source filename (if any) and filetype
         * @return final element
         * @throws IOException if emission fails or is cancelled
         */
        Element emit(ElementType type, String text, Map<String, Object> metadata) throws IOException;

        /**
         * Emits one warning in document order.
         * @param warning warning
         * @throws IOException if emission fails
         */
        void warn(PartitionResult.Warning warning) throws IOException;

        /**
         * Tells whether the range elements were already emitted with their final IDs, so assembly only verifies that
         * no reference needs rewriting instead of emitting again. True only for one range that starts at page 1 and
         * whose collector emitted directly into the partition context.
         * @return whether elements are already final
         */
        default boolean emitted() {
            return false;
        }

        /**
         * Output of whole-document partitioning: the collector already emitted every element into the context, whose
         * sequence equals the range's, so elements are final; warnings go to the context in page order.
         * @param context whole-document context
         * @return output
         */
        static Output emitted(final PartitionContext context) {
            return new Output() {
                @Override
                public Element emit(final ElementType type, final String text, final Map<String, Object> metadata) {
                    throw new IllegalStateException("Whole-document elements are emitted by the collector");
                }

                @Override
                public void warn(final PartitionResult.Warning warning) {
                    context.warn(warning.code(), warning.message());
                }

                @Override
                public boolean emitted() {
                    return true;
                }
            };
        }
    }

    /** A violated assembly invariant: coverage, page order or an unresolvable reference. */
    static final class InvalidRange extends IllegalArgumentException {
        /**
         * Describes the violation.
         * @param message content-free description
         */
        InvalidRange(final String message) {
            super(message);
        }
    }

    /** Metadata key of table continuation links. */
    static final String CONTINUATION = "pdf_table_continuation";

    /** Destination. */
    private final Output output;
    /** Next page expected by coverage, one-based. */
    private int next = 1;
    /** Document page count, known after the first range begins. */
    private int documentPages = -1;
    /** Whether a range is open. */
    private boolean open;
    /** Whether the next page is the first page of the open range. */
    private boolean firstPage;
    /** Continuation candidate on the first page of the open range. */
    private PdfTableBoundary entry;
    /** Final continuation state of the previous range's last page, or null. */
    private Exit exit;
    /** Tables of the previous page of the open range, by provisional ID. */
    private Map<String, Chain> previousTables = Map.of();

    /**
     * Creates an assembly writing to an output.
     * @param output destination
     */
    PdfRangeAssembly(final Output output) {
        this.output = output;
    }

    /**
     * Opens the next range. Coverage must continue exactly where the previous range ended.
     * @param coverage covered pages
     * @param pages document page count
     * @param documentWarnings document warnings, emitted for the first range only
     * @param firstPageTable continuation evidence on the range's first page, or null
     * @throws IOException if warning emission fails
     * @throws IllegalArgumentException if coverage leaves a gap, overlaps or disagrees about the page count
     */
    void begin(final PdfPageRange coverage, final int pages, final List<PartitionResult.Warning> documentWarnings,
            final PdfTableBoundary firstPageTable) throws IOException {
        if (open) {
            throw new IllegalStateException("Previous PDF range is still open");
        }
        if (documentPages >= 0 && documentPages != pages || coverage.last() > pages) {
            throw new InvalidRange("PDF range results describe different page counts");
        }
        if (coverage.first() > next) {
            throw new InvalidRange(
                    "PDF range results leave pages " + next + "-" + (coverage.first() - 1) + " uncovered");
        }
        if (coverage.first() < next) {
            throw new InvalidRange("PDF range results overlap at page " + coverage.first());
        }
        if (documentPages < 0) {
            documentPages = pages;
            for (final var warning : documentWarnings) {
                output.warn(warning);
            }
        }
        open = true;
        firstPage = true;
        entry = firstPageTable;
        previousTables = Map.of();
    }

    /**
     * Assembles one page of the open range.
     * @param page next page in order
     * @throws IOException if emission fails or is cancelled
     * @throws IllegalArgumentException if the page is out of order or holds unresolvable references
     */
    void page(final PdfRangeResult.Page page) throws IOException {
        if (!open || page.pageNumber() != next) {
            throw new InvalidRange("PDF range page " + page.pageNumber() + " is out of order");
        }
        final boolean entryLinked = firstPage && entry != null && exit != null && exit.page() + 1 == page.pageNumber()
                                    && exit.exitClear() && entry.entryClear()
                                    && entry.evidence().signature().follows(exit.signature());
        final var currentTables = new HashMap<String, Chain>();
        for (final var element : page.elements()) {
            ElementCollector.checkInterrupted();
            var metadata = element.metadata();
            Chain link = null;
            boolean crossing = false;
            if (metadata.get(CONTINUATION) instanceof final Map<?, ?> reference) {
                final var previous = previousTables.get(reference.get("previous_element_id"));
                if (previous == null || !previous.provisionalRoot().equals(reference.get("root_element_id"))) {
                    throw new InvalidRange(
                            "PDF range page " + page.pageNumber() + " has an unresolved table continuation reference");
                }
                link = previous;
            } else if (metadata.containsKey(CONTINUATION)) {
                throw new InvalidRange("Malformed table continuation on PDF page " + page.pageNumber());
            } else if (entryLinked && element.elementId().equals(entry.elementId())) {
                link = exit.chain();
                crossing = true;
            }
            if (link != null) {
                final var linked = new LinkedHashMap<>(metadata);
                linked.put(CONTINUATION, PdfPageOutput.continuation(link.rootId(), link.rootPage(), link.finalId(),
                                                 link.page(), link.part() + 1));
                metadata = linked;
            }
            final Element emitted;
            if (output.emitted()) {
                // Final already: a single range from page 1 needs no rewriting, which is verified here.
                if (crossing || metadata != element.metadata() && !metadata.equals(element.metadata())) {
                    throw new InvalidRange("PDF page " + page.pageNumber() + " needs a reference rewrite");
                }
                emitted = element;
            } else {
                emitted = output.emit(element.type(), element.text(), metadata);
            }
            if (element.type() == ElementType.Table) {
                currentTables.put(element.elementId(),
                        link == null
                                ? new Chain(emitted.elementId(), emitted.elementId(), page.pageNumber(), 0,
                                          page.pageNumber(), element.elementId())
                                : new Chain(emitted.elementId(), link.rootId(), link.rootPage(), link.part() + 1,
                                          page.pageNumber(), crossing ? element.elementId() : link.provisionalRoot()));
            }
        }
        for (final var warning : page.warnings()) {
            output.warn(warning);
        }
        previousTables = currentTables;
        firstPage = false;
        next++;
    }

    /**
     * Closes the open range, keeping only the continuation state of its last page.
     * @param coverage covered pages
     * @param lastPageTable continuation evidence on the range's last page, or null
     * @throws IllegalArgumentException if pages are missing or the evidence names no table of the last page
     */
    void end(final PdfPageRange coverage, final PdfTableBoundary lastPageTable) {
        if (!open || next != coverage.last() + 1) {
            throw new InvalidRange("PDF range is missing pages");
        }
        if (lastPageTable == null) {
            exit = null;
        } else {
            final var chain = previousTables.get(lastPageTable.elementId());
            if (chain == null || lastPageTable.pageNumber() != coverage.last()) {
                throw new InvalidRange("PDF range boundary evidence names no table of its last page");
            }
            exit = new Exit(
                    chain, lastPageTable.evidence().signature(), lastPageTable.exitClear(), lastPageTable.pageNumber());
        }
        previousTables = Map.of();
        entry = null;
        open = false;
    }

    /**
     * Checks that the ranges covered the whole document.
     * @throws IllegalArgumentException if pages remain uncovered
     */
    void finish() {
        if (open) {
            throw new IllegalStateException("PDF range is still open");
        }
        if (documentPages < 0) {
            throw new InvalidRange("No PDF range results to assemble");
        }
        if (next != documentPages + 1) {
            throw new InvalidRange("PDF range results leave pages " + next + "-" + documentPages + " uncovered");
        }
    }

    /**
     * Final continuation state of one table.
     * @param finalId final table ID
     * @param rootId final ID of the chain's first table
     * @param rootPage page of the chain's first table
     * @param part zero for a root, increasing along the chain
     * @param page page of this table
     * @param provisionalRoot provisional ID, within the table's own range, of the root its range recorded
     */
    private record Chain(String finalId, String rootId, int rootPage, int part, int page, String provisionalRoot) {
        /**
         * Validates references.
         * @param finalId final ID
         * @param rootId root final ID
         * @param rootPage root page
         * @param part part index
         * @param page table page
         * @param provisionalRoot range-local root ID
         */
        private Chain {
            Objects.requireNonNull(finalId);
            Objects.requireNonNull(rootId);
            Objects.requireNonNull(provisionalRoot);
        }
    }

    /**
     * Continuation state carried from a range's last page to the next range's first page.
     * @param chain final state of the last page's candidate table
     * @param signature its header evidence
     * @param exitClear whether text leaves its lower edge clear
     * @param page its page
     */
    private record Exit(Chain chain, PdfTableSignature signature, boolean exitClear, int page) {}
}
