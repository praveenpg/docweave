package dev.docweave.partition;

import dev.docweave.core.Element;

import java.io.IOException;
import java.util.List;

/**
 * Explicit post-extraction recognition of repeated PDF running headers and footers. The conservative
 * built-in policy requires identical whitespace-normalized text on at least three distinct pages and
 * sixty percent of the supplied physical page count, with consistent normalized margin positions.
 * It preserves text, IDs, geometry and order and never removes source elements. This is a geometric
 * heuristic, not semantic classification or a calibrated probability. Instances are thread-safe.
 * Matching collapses Unicode whitespace, including nonbreaking and narrow spaces, only in comparison keys;
 * original text, punctuation, case, numbers and non-whitespace characters remain unchanged.
 */
public interface PdfMarginClassifier {
    /**
     * Creates the stateless built-in repeated-margin classifier.
     * @return service with a hidden implementation
     */
    static PdfMarginClassifier create() {
        return create(PdfMarginOptions.defaults());
    }

    /**
     * Creates an explicit repeated-margin classifier with separate bands and first-page body handling.
     * Absolute support, physical-page coverage, geometry consistency, source preservation and later-page
     * body vetoes remain unchanged. Configured classifications record their policy in metadata.
     * @param options nonnull immutable margin policy
     * @return service with a hidden stateless implementation
     * @throws NullPointerException if options is null
     */
    static PdfMarginClassifier create(final PdfMarginOptions options) {
        return new DefaultPdfMarginClassifier(java.util.Objects.requireNonNull(options, "options"));
    }

    /**
     * Tags eligible ordinary text as Header or Footer after complete-document extraction. Candidates
     * by default must fit wholly inside the outer eight percent of page height, be at most four percent tall,
     * contain letters and be no longer than160 normalized UTF-16 units (640 before whitespace reduction). Matching
     * positions must agree within two percent on both axes. Duplicate same-text candidates on one page, body
     * repetitions, rotated text, tables/images, changing page numbers and missing geometry are conservatively left
     * unchanged. Explicit options may adjust the bands and permit body matches only on physical page1.
     * Absent rotation or any JSON-compatible numeric zero is upright, independent of its Java number type;
     * nonzero decimal angles remain excluded even when conversion to double would underflow to zero.
     * Each tag stores policy, original type, matching page count and total physical page count in
     * pdf_margin_classification. Existing Header/Footer elements are preserved, making reuse idempotent.
     *
     * @param elements complete ordered output for one PDF, at most100,000 source elements
     * @param pageCount actual physical page count, including blank pages, from1 through10,000; callers
     *                  must not infer this from nonempty pages or supply a mixed-document element list
     * @return immutable list in input order; untagged elements retain their original instances
     * @throws IOException if interrupted or the element budget is exceeded
     * @throws IllegalArgumentException if page count, source page references or candidate geometry is invalid
     * @throws NullPointerException if elements or an entry is null
     */
    List<Element> classify(List<Element> elements, int pageCount) throws IOException;
}
