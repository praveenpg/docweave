package io.docweave.partition;

import io.docweave.core.Element;

import java.io.IOException;
import java.util.List;

/**
 * Combines {@link PdfRangeResult}s that together cover one PDF into the document's final output, identical to
 * partitioning the whole document with the same strategy and options. Whole-document partitioning uses the same
 * assembly internally, as one range covering every page.
 *
 * <p>Results must be supplied in page order and must cover pages 1 to {@link PdfRangeResult#documentPages()} exactly
 * once, without gaps or overlaps; they must agree on the result format, library version (this library's), source
 * SHA-256 and size, filename, options digest, page count, document warnings and ID-reference fields. Anything else is
 * rejected with {@link IllegalArgumentException} before or while assembling.</p>
 *
 * <p>Assembly emits the document warnings once, then, page by page, the page's elements and warnings. Each element
 * receives its final ID from the document-wide sequence, with the same formula as whole-document partitioning, and
 * every ID reference ({@link PdfRangeResult#ID_REFERENCE_FIELDS}) is rewritten to final IDs. Table continuation
 * chains are resolved in page order as the whole-document path resolves them, including chains that cross range
 * boundaries or span whole ranges. Budgets are not re-checked: they were charged while the ranges were extracted.
 * Post-extraction steps such as {@link PdfMarginClassifier} and {@link PdfParagraphAssembler} are not part of
 * partitioning, so assembly does not run them either; apply them to the assembled elements exactly as to a
 * whole-document result.</p>
 *
 * <p>The streaming form ({@link #open(ElementSink)}) holds one range result at a time plus the continuation state of
 * the previous page; {@link #assemble(List)} collects the elements into a list. Instances are stateless and
 * thread-safe; a session is confined to one thread.</p>
 */
public interface PdfRangeAssembler {
    /**
     * Creates the built-in assembler.
     * @return stateless thread-safe assembler with a hidden implementation
     */
    static PdfRangeAssembler create() {
        return new DefaultPdfRangeAssembler();
    }

    /**
     * Assembles complete coverage into a partition result. Its elements and warnings equal those of whole-document
     * partitioning; {@code inputBytes} is the source size and {@code elapsed} the assembly time.
     * @param ranges nonnull results covering the document, in page order
     * @return immutable PDF result
     * @throws IOException if interrupted
     * @throws NullPointerException if the list or an entry is null
     * @throws IllegalArgumentException if the results are empty, incomplete, overlapping or inconsistent
     */
    PartitionResult assemble(List<PdfRangeResult> ranges) throws IOException;

    /**
     * Opens a streaming assembly that passes final elements to a sink as each range is accepted.
     * @param sink nonnull consumer of final elements, in document order
     * @return single-use session
     * @throws NullPointerException if sink is null
     */
    Session open(ElementSink sink);

    /** Consumer of final elements. */
    @FunctionalInterface
    interface ElementSink {
        /**
         * Receives the next final element.
         * @param element element with its final ID
         * @throws IOException if the consumer fails; assembly stops
         */
        void accept(Element element) throws IOException;
    }

    /**
     * One streaming assembly. After any failure, or after {@link #finish()}, the session rejects further use.
     */
    interface Session {
        /**
         * Assembles the next range result, emitting its elements to the sink before returning.
         * @param range nonnull next result in page order
         * @throws IOException if the sink fails or the call is interrupted
         * @throws NullPointerException if range is null
         * @throws IllegalArgumentException if the result does not continue the coverage or does not match earlier
         *         results
         * @throws IllegalStateException if the session already finished or failed
         */
        void accept(PdfRangeResult range) throws IOException;

        /**
         * Completes the assembly after the last range.
         * @return the document's warnings, in order
         * @throws IllegalArgumentException if no result was accepted or pages remain uncovered
         * @throws IllegalStateException if the session already finished or failed
         */
        List<PartitionResult.Warning> finish();
    }
}
