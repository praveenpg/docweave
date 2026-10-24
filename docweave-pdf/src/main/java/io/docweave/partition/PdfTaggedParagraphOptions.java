package io.docweave.partition;

import java.util.Objects;

/**
 * Explicit source-paragraph grouping limits, independent of table and native glyph limits.
 * Accepted paragraphs require complete single-page page-stream MCID ownership and one upright text column.
 * Source tags may be wrong; grouping does not establish general semantic page order or script correctness.
 * @param maxParagraphsPerPage maximum prospective supported source plans on one page, including later rejections
 * @param maxParagraphCharacters maximum original space-joined UTF16 units per paragraph/page fragment before shortening
 * @param wordWrap explicit preservation or source-evidenced discretionary line joining
 * @param pageBoundary whether cross-page source paragraphs fall back or form independently validated page fragments
 * @param inlineScripts explicit grouping of small raised/lowered source-owned glyphs
 */
public record PdfTaggedParagraphOptions(int maxParagraphsPerPage, int maxParagraphCharacters, WordWrap wordWrap,
        PageBoundary pageBoundary, InlineScripts inlineScripts) {
    /** Explicit geometric interpretation of small glyphs inside a completely source-owned paragraph. */
    public enum InlineScripts {
        /** Preserves historical baseline grouping and unsupported-paragraph fallback. */ PRESERVE,
        /**
         * Assigns smaller glyphs to one uniquely nearby body baseline when horizontally adjacent.
         * Retains all characters and original coordinates; does not infer mathematical semantics.
         * Ambiguous baseline assignments retain historical grouping.
         */
        NEARBY_SMALL_GLYPHS
    }

    /** Explicit handling of a source paragraph whose ordered references cross original pages. */
    public enum PageBoundary {
        /** Keeps whole cross-page paragraphs in ordinary geometric fallback. */ SINGLE_PAGE,
        /** Groups each consecutive-page fragment independently; no text is joined across page boundaries. */
        PAGE_FRAGMENTS
    }

    /** Explicit word-wrap treatment; no policy uses a dictionary, OCR or external reference text. */
    public enum WordWrap {
        /** Preserves physical hard hyphens and spaces between native runs. */ PRESERVE,
        /** Joins only unambiguous source U+00AD hints owning one retained line-final hyphen glyph. */
        SOURCE_SOFT_HYPHENS,
        /**
         * Also omits generated separators after letter-final physical hyphens at nearby native line wraps.
         * Requires unique native endpoints, a letter starting the next line and no explicit edge whitespace.
         * Physical hyphens remain; this opt-in compound-word interpretation is not semantic word detection.
         * Does not join across pages, paragraphs, columns or unsupported source ownership.
         */
        SOURCE_LINE_WRAPS
    }

    /**
     * Validates positive caller bounds and explicit text treatment without reading a document.
     * @throws NullPointerException if a policy is null
     * @throws IllegalArgumentException if a limit is not positive
     */
    public PdfTaggedParagraphOptions {
        Objects.requireNonNull(wordWrap, "wordWrap");
        Objects.requireNonNull(pageBoundary, "pageBoundary");
        Objects.requireNonNull(inlineScripts, "inlineScripts");
        if (maxParagraphsPerPage < 1 || maxParagraphCharacters < 1) {
            throw new IllegalArgumentException("Tagged paragraph limits must be positive");
        }
    }

    /**
     * Preserves the original four-argument constructor and historical inline grouping.
     * @param maxParagraphsPerPage maximum prospective source plans on a page
     * @param maxParagraphCharacters maximum original characters in one accepted region
     * @param wordWrap explicit word-wrap policy
     * @param pageBoundary explicit page-fragment policy
     */
    public PdfTaggedParagraphOptions(final int maxParagraphsPerPage, final int maxParagraphCharacters,
            final WordWrap wordWrap, final PageBoundary pageBoundary) {
        this(maxParagraphsPerPage, maxParagraphCharacters, wordWrap, pageBoundary, InlineScripts.PRESERVE);
    }

    /**
     * Copies this policy with explicit source-owned inline glyph grouping.
     * @param policy nonnull geometric interpretation
     * @return immutable copy preserving bounds, word wraps and page handling
     * @throws NullPointerException if policy is null
     */
    public PdfTaggedParagraphOptions withInlineScripts(final InlineScripts policy) {
        return new PdfTaggedParagraphOptions(
                maxParagraphsPerPage, maxParagraphCharacters, wordWrap, pageBoundary, policy);
    }

    /**
     * Preserves the original constructor descriptor and single-page behavior.
     * @param maxParagraphsPerPage maximum prospective source plans on a page
     * @param maxParagraphCharacters maximum original characters in one accepted region
     * @param wordWrap explicit word-wrap policy
     */
    public PdfTaggedParagraphOptions(
            final int maxParagraphsPerPage, final int maxParagraphCharacters, final WordWrap wordWrap) {
        this(maxParagraphsPerPage, maxParagraphCharacters, wordWrap, PageBoundary.SINGLE_PAGE);
    }

    /**
     * Copies this policy with explicit source-supported word-wrap treatment.
     * @param policy nonnull caller choice
     * @return immutable policy retaining all resource limits
     * @throws NullPointerException if policy is null
     */
    public PdfTaggedParagraphOptions withWordWrap(final WordWrap policy) {
        return new PdfTaggedParagraphOptions(
                maxParagraphsPerPage, maxParagraphCharacters, policy, pageBoundary, inlineScripts);
    }

    /**
     * Copies this policy with explicit cross-page handling; character limits apply to each original page fragment.
     * Fragment indices/counts describe source plans, including fragments later rejected by native ownership checks.
     * @param policy nonnull page-boundary policy
     * @return immutable copy retaining original bounds and word-wrap treatment
     * @throws NullPointerException if policy is null
     */
    public PdfTaggedParagraphOptions withPageBoundary(final PageBoundary policy) {
        return new PdfTaggedParagraphOptions(
                maxParagraphsPerPage, maxParagraphCharacters, wordWrap, policy, inlineScripts);
    }

    /**
     * Returns1024 prospective paragraphs per page and one million original UTF16 units per paragraph.
     * Physical hyphens are preserved unless the caller explicitly selects source discretionary joining.
     * @return immutable validated defaults
     */
    public static PdfTaggedParagraphOptions defaults() {
        return new PdfTaggedParagraphOptions(1024, 1_000_000, WordWrap.PRESERVE);
    }
}
