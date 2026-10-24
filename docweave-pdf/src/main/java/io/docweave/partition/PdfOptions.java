package io.docweave.partition;

/**
 * Immutable limits for the native PDF strategy. Limits bound extracted state, not PDFBox heap or execution time.
 * Images are referenced by default; opt-in PNG payloads are normalized decoded pixels, not original compressed streams.
 * Solid-color stencil payloads retain the occurrence's fill and transparent mask pixels. Pattern stencils
 * retain explicitly labelled decoded mask samples; payloads are not rendered page crops.
 * Image metadata also retains the ordered transformed source unit-square corners under
 * {@code pdf_image_coordinates}; the ordinary element coordinates remain an enclosing rectangle.
 * Corner positions describe source placement, not clipping, visible pixels or page compositing.
 * @param maxPages maximum pages per document
 * @param maxGlyphsPerPage maximum visited text positions on a page, including positions excluded by the rendering
 *         policy
 * @param maxOperatorsPerPage maximum content operators processed on a page, including forms
 * @param maxImagePixels maximum decoded PNG extent, including larger source-declared active mask dimensions
 * @param maxImageBytes cumulative normalized PNG byte budget per document
 * @param includeImageBytes whether Image metadata includes base64 PNG data
 * @param tableLimits limits for recognized tables
 * @param tableDetection explicit table recognition policy; defaults to ruled geometry only
 * @param textLayout explicit native text grouping/order policy; defaults to lines
 * @param includeTableCellCoordinates whether recognized tables include anchor-cell geometry metadata
 * @param tableContinuation optional conservative adjacent-page linking policy
 * @param unicodeNormalization explicit native per-glyph presentation-form expansion; RAW preserves decoded text
 * @param textRendering explicit native rendering-mode selection; ALL preserves invisible searchable text
 * @param figureCaptions optional conservative native caption classification; NONE preserves historical types
 * @param includeImageClipBounds whether Image metadata includes a conservative invocation clip-bounds
 *         classification under {@code pdf_image_clip_bounds}; defaults preserve historical image metadata
 * @param includeImageGroupContext whether Image metadata lists enclosing painted transparency-group invocation
 *         states under {@code pdf_image_group_context}; defaults preserve historical image metadata
 * @param curvedClipEnvelopes whether curved clip paths are interpreted by the rectangle enclosing their points
 *         and Bezier control points; defaults keep ignoring curved clips for every clip consumer
 * @param inlineScripts explicit grouping of small raised/lowered native glyph runs into their body line;
 *         defaults preserve historical baseline grouping
 * @param sideStreams explicit block grouping of a smaller-type side stream, such as an article sidebar, on pages
 *         without an accepted two-column gutter; defaults preserve historical interleaved grouping
 */
public record PdfOptions(int maxPages, int maxGlyphsPerPage, int maxOperatorsPerPage, long maxImagePixels,
        int maxImageBytes, boolean includeImageBytes, TableLimits tableLimits, TableDetection tableDetection,
        TextLayout textLayout, boolean includeTableCellCoordinates, TableContinuation tableContinuation,
        UnicodeNormalization unicodeNormalization, TextRendering textRendering, FigureCaptions figureCaptions,
        boolean includeImageClipBounds, boolean includeImageGroupContext, boolean curvedClipEnvelopes,
        InlineScripts inlineScripts, SideStreams sideStreams) {
    /**
     * Explicit grouping of a horizontally separate side stream set in smaller type, such as the metadata sidebar
     * beside an article's first-page abstract. Applies only to paragraph-producing native layouts on pages where no
     * two-column gutter is accepted; line layouts and accepted column bands are unchanged.
     */
    public enum SideStreams {
        /** Keeps historical grouping: interleaved lines of two streams are grouped and ordered by position only. */
        PRESERVE,
        /**
         * Clusters ordinary upright lines by left edge (four-unit tolerance) and takes the cluster with the most lines
         * as the main stream. Another cluster's lines lying wholly outside the main stream's horizontal extent, at
         * least four units beyond it, form a side stream when there are at least three of them, their median line
         * height is below 0.9 of the main stream's, and their vertical extent spans at least three main-stream lines.
         * A cluster is rejected as row-bound when at least half of those lines share a baseline (within two units)
         * with a main-stream line, as statement labels do with their values; a prose sidebar runs on its own baseline
         * grid. Side-stream lines are grouped by the existing paragraph rule among themselves only, and two
         * consecutive side lines never join across an intervening item, such as an image, table or spanning text,
         * that horizontally overlaps them. Each group is emitted at its first line's position. Every other item keeps
         * its historical grouping and order, and a side line still ends any adjacent main-stream group. No text is
         * changed; enclosing element bounds change where lines join. This is a geometric heuristic for sidebars, not
         * semantic article order.
         */
        SMALLER_TYPE_BLOCKS,
        /**
         * Produces exactly the {@link #SMALLER_TYPE_BLOCKS} elements, then resolves same-row ties by moving whole
         * elements only in column-paragraph layouts, including the oriented column variant. Plain
         * {@link TextLayout#PARAGRAPHS} retains {@link #SMALLER_TYPE_BLOCKS} placement. One pass scans the page's
         * grouped output once, in order. A side-stream element moves back one position, past its immediate predecessor,
         * only when all of these hold: <ul> <li>the predecessor is ordinary upright native text: UncategorizedText that
         * is not a side-stream element, source paragraph, rotated text, table or image;</li> <li>the predecessor holds
         * one original line ({@code pdf_line_count} absent or 1), so a joined paragraph never moves even when its last
         * line shares the side element's row;</li> <li>the side element lies wholly left of it (side right &le;
         * predecessor left);</li> <li>the side element's top lies within the predecessor's vertical extent (predecessor
         * top &le; side top &lt; predecessor bottom).</li>
         * </ul>
         * Each side-stream element moves at most once and never past more than that one item. Elements are moved
         * intact: text, bounds, metadata, membership and the relative order of all other elements are those of
         * {@link #SMALLER_TYPE_BLOCKS}, and no element is split, joined, dropped or added. Accepted two-column bands
         * and line layouts are unchanged. The rule uses geometry only. It came from post-hoc development evidence
         * against a pinned upstream reading order and is not a semantic or accuracy guarantee.
         */
        SMALLER_TYPE_BLOCKS_ROW_TIES,
        /**
         * Produces {@link #SMALLER_TYPE_BLOCKS_ROW_TIES} output and additionally represents one case that whole
         * elements cannot: a side-stream block whose first line starts on the visual row of the last line of a
         * multi-line main paragraph (that line's top &le; side top &lt; that line's bottom, side wholly to its left).
         * The paragraph is then emitted as two explicit fragments, its earlier lines and its last line, with the side
         * block between them. A split is made only when the first fragment's text, one space and the last line's text
         * equal the unsplit paragraph text exactly, so a retained hyphen wrap is never split. Both fragments carry
         * {@code pdf_reading_order_fragment_policy=row_tie_fragments_v1}, {@code pdf_paragraph_fragment_group} (a
         * page-local ordinal), {@code pdf_paragraph_fragment_index} (1 or 2) and {@code pdf_paragraph_fragment_count}
         * (2). Joining each group's fragments with one space and the union of their bounds restores the
         * {@link #SMALLER_TYPE_BLOCKS_ROW_TIES} paragraph; every other element is identical to that policy's output.
         * Consumers that require whole paragraphs must rejoin fragments or use another policy. Column-paragraph
         * layouts only; plain {@link TextLayout#PARAGRAPHS} retains {@link #SMALLER_TYPE_BLOCKS} placement. Post-hoc
         * development evidence against a pinned upstream reading order, not a semantic or accuracy guarantee.
         */
        SMALLER_TYPE_BLOCKS_ROW_FRAGMENTS
    }

    /**
     * Explicit grouping of short small-glyph runs, such as affiliation or citation superscripts, in ordinary
     * native upright text. Source characters and glyph bounds are retained; regrouped elements enclose their
     * member glyphs, so their bounds may change. No semantic superscript/subscript markup is generated.
     */
    public enum InlineScripts {
        /** Keeps historical grouping: a raised or lowered run more than two units off a baseline is its own line. */
        PRESERVE,
        /**
         * Groups a complete run of at most eight nonblank upright glyphs, each at most three quarters of the page's
         * median ordinary-text height and joined by small advances, with the one nearby body line that supports it:
         * median-height body glyphs within a small horizontal gap, shifted more than two units but at most eighty
         * percent of their height. Competing baselines, overlapping positions or longer runs keep the historical
         * grouping. This is the bounded whole-run rule of the explicit native cell policy
         * {@link PdfNativeLayoutOptions.CellTextJoin#INLINE_SCRIPT_RUNS}, applied to ordinary upright text after
         * native table staging and only on pages whose upright source frame is displayed unrotated. It changes line
         * membership and therefore paragraph and order grouping derived from lines; it does not repair text or
         * establish semantic article order.
         */
        NEARBY_SMALL_GLYPHS
    }

    /** Optional source-geometry heuristics; no policy generates text or guarantees semantic caption correctness. */
    public enum FigureCaptions {
        /** Preserves native text categories without caption inference. */ NONE,
        /**
         * Classifies bounded upright native units starting with an explicit Fig/Figure number and period,
         * immediately below one unambiguous substantial raster image. Preserves text, bounds and order;
         * does not collect missing caption continuation lines, interpret Alt/ActualText or process OCR captions.
         */
        IMAGE_ADJACENT_LABELS,
        /**
         * Uses the same image geometry with tab or Unicode Space_Separator characters around label tokens.
         * Keeps EN/EM/nonbreaking spaces in source text; rejects line/paragraph separators and empty bodies.
         * The original ASCII-label policy remains unchanged.
         */
        IMAGE_ADJACENT_HORIZONTAL_LABELS,
        /**
         * Also accepts a bare numbered label followed by a capitalized title, or a colon-delimited title.
         * Lowercase unpunctuated prose references remain ordinary text. Uses the same bounded image geometry;
         * recognition is an English title-format heuristic, not a semantic or word-wrap reconstruction guarantee.
         */
        IMAGE_ADJACENT_NUMBERED_TITLES,
        /**
         * Also accepts horizontal space before label punctuation and either left- or right-edge image alignment.
         * Keeps the same12-point edge tolerance,24-point maximum gap and unique substantial-image relation.
         * This explicit geometric alternative preserves earlier policies, source strings and caption boundaries.
         */
        IMAGE_EDGE_ALIGNED_NUMBERED_TITLES
    }

    /** Native text selection by PDF text rendering mode, not a rendered-visibility guarantee. */
    public enum TextRendering {
        /** Preserves all decoded text, including invisible OCR/search layers. */ ALL,
        /** Keeps fill/stroke text modes, including painting-and-clipping; excludes modes3 and7. */ PAINTED_MODES,
        /**
         * Also excludes text when every enabled fill/stroke operation has an effective alpha of zero.
         * Positive alpha remains eligible. Does not evaluate clipping, soft masks, occlusion or optional content.
         */
        NONZERO_ALPHA_PAINTED_MODES,
        /**
         * Also excludes source positions inside an empty inherited form/page clipping area. Does not test
         * individual glyph containment, explicit path clips, partial visibility, soft masks or occlusion.
         */
        NONZERO_ALPHA_NONEMPTY_CLIP_PAINTED_MODES;

        /**
         * Identifies policies requiring at least one positive-alpha painting operation.
         * @return whether source alpha is part of the explicit selection policy
         */
        boolean nonzeroAlpha() {
            return this == NONZERO_ALPHA_PAINTED_MODES || this == NONZERO_ALPHA_NONEMPTY_CLIP_PAINTED_MODES;
        }

        /**
         * Supplies stable native provenance only for explicit filtering policies.
         * @return versioned policy label
         * @throws IllegalStateException if called for unfiltered historical output
         */
        String provenance() {
            return switch (this) {
                case ALL -> throw new IllegalStateException("Unfiltered native text has no filtering provenance");
                case PAINTED_MODES -> "painted_modes_v1";
                case NONZERO_ALPHA_PAINTED_MODES -> "nonzero_alpha_painted_modes_v1";
                case NONZERO_ALPHA_NONEMPTY_CLIP_PAINTED_MODES -> "nonzero_alpha_nonempty_clip_painted_modes_v1";
            };
        }
    }

    /** Explicit native Unicode policy; neither option repairs font maps, glyph positions or logical bidi order. */
    public enum UnicodeNormalization {
        /** Preserves the original PDFBox-decoded glyph strings without compatibility conversion. */ RAW,
        /** Expands only Alphabetic/Arabic presentation forms using per-codepoint NFKC; other characters stay raw. */
        PRESENTATION_FORMS
    }

    /** Explicit recognition policies; aligned text is an opt-in heuristic, not a rendered-layout model. */
    public enum TableDetection {
        /** Recognizes closed ruled grids and rectangular merges only. */ RULED_ONLY,
        /** Also recognizes aligned text with at least three uniform rows and a numeric data column. */
        RULED_AND_ALIGNED_TEXT,
        /** Also accepts a tightly spaced continuation in the first column of every numeric body row. */
        RULED_AND_MULTILINE_ALIGNED_TEXT,
        /** Normalizes eligible empty double-rule bands before closed-grid recognition; no aligned-text inference. */
        RULED_AND_DOUBLE_BORDERS,
        /** Also infers native text tables from repeated segmented horizontal rules and complete numeric rows. */
        RULED_AND_HORIZONTAL_TEXT,
        /**
         * Also infers partially ruled native tables with a full-width top rule, matching segmented
         * separator/lower rules, multilevel header spans and complete uniformly pitched numeric body rows.
         * Cell areas and header spans are heuristic inferences, not observed closed borders or semantic merges.
         */
        RULED_AND_PARTIAL_TEXT,
        /**
         * Retains partial-rule detection and also infers column-local body row spans from matching segmented
         * top/bottom rules and first-column separators at every row. Missing separators are an explicit
         * structural heuristic, not semantic merge truth; empty, crossing or ambiguous candidates fall back whole.
         */
        RULED_AND_SEGMENTED_ROWS,
        /**
         * Also infers upright native tables from repeated pale rectangular row bands and matching
         * numeric subtotal rules. Row/cell areas are explicit geometric inferences, not semantic labels.
         */
        RULED_AND_SHADED_ROWS,
        /**
         * Retains shaded-row inference and additionally omits unique shifted ASCII padding overlaid
         * on two adjacent overlapping digit advances. Real separated digits, ordinary spaces and
         * ambiguous geometry retain original text; this is an explicit geometric text projection.
         */
        RULED_AND_SHADED_ROWS_COMPACT_PADDING,
        /**
         * Retains compact numeric padding and groups complete short adjacent smaller glyph runs
         * in native body cells against one unambiguous body baseline. Original text and geometry remain.
         */
        RULED_AND_SHADED_ROWS_INLINE_SCRIPTS,
        /**
         * Also recognizes uniformly aligned native text with explicit currency prefixes, balanced accounting
         * parentheses or a Unicode minus in a numeric body column. Source strings remain unchanged; no locale,
         * missing-value, date or footnote inference is performed. This run-based policy is opt-in.
         */
        RULED_AND_FINANCIAL_ALIGNED_TEXT;

        /**
         * Identifies run-based aligned strategies, which cannot provide exact source glyph ownership.
         * @return whether table recognition operates on complete ordinary source runs
         */
        boolean alignedText() {
            return this == RULED_AND_ALIGNED_TEXT || this == RULED_AND_MULTILINE_ALIGNED_TEXT
                    || this == RULED_AND_FINANCIAL_ALIGNED_TEXT;
        }

        /**
         * Identifies policies that require original pale row paint.
         * @return whether original pale row paint is required by this explicit policy
         */
        boolean shadedRows() {
            return this == RULED_AND_SHADED_ROWS || this == RULED_AND_SHADED_ROWS_COMPACT_PADDING
                    || this == RULED_AND_SHADED_ROWS_INLINE_SCRIPTS;
        }
    }

    /** Explicit continuation inference; links never merge page-local tables or remove repeated headers. */
    public enum TableContinuation {
        /** Leaves page-local Tables independent, preserving historical output. */ NONE,
        /** Links isolated adjacent-page ruled tables with matching repeated unmerged headers and geometry. */
        REPEATED_HEADER,
        /**
         * Also links provider tables with one explicitly declared, unmerged header row and
         * consistent column boundaries. Unknown, stacked or merged headers are ineligible.
         * Native, word-OCR and cell-OCR families and differing provider provenance never cross-link.
         */
        REPEATED_HEADER_WITH_PROVIDERS,
        /**
         * Additionally permits two through four explicitly declared, unmerged provider header rows,
         * capped at32 header cells. Matching retains row/cell boundaries and recognition provenance;
         * no header roles are inferred and existing policies retain their single-row restriction.
         */
        REPEATED_HEADER_PREFIX_WITH_PROVIDERS,
        /**
         * Also permits merged anchors wholly inside a declared two-through-four-row provider
         * header prefix. At most32 logical header slots, exact anchor topology and consistent
         * atomic column boundaries are required. Text and page-local tables remain unchanged.
         */
        REPEATED_HEADER_LAYOUT_WITH_PROVIDERS;

        /**
         * Selects explicitly declared provider evidence without affecting native ruled-table matching.
         * @return whether this policy includes provider tables
         */
        boolean providerHeaders() {
            return this == REPEATED_HEADER_WITH_PROVIDERS || providerPrefixes();
        }

        /**
         * Selects bounded multi-row provider header evidence.
         * @return whether declared prefixes may contribute
         */
        boolean providerPrefixes() {
            return this == REPEATED_HEADER_PREFIX_WITH_PROVIDERS || providerLayouts();
        }

        /**
         * Selects exact declared merged-header topology matching.
         * @return whether merged prefix anchors may contribute
         */
        boolean providerLayouts() {
            return this == REPEATED_HEADER_LAYOUT_WITH_PROVIDERS;
        }
    }

    /** Native geometric text policies; none provide semantic or rendered-layout guarantees. */
    public enum TextLayout {
        /** Keeps line-level text in geometric top/left order. */ LINES,
        /** Also retains arbitrary-angle native lines with approximate projected bounds. */ ORIENTED_LINES,
        /** Joins closely spaced, similarly aligned ordinary lines; retains geometric order. */ PARAGRAPHS,
        /** Also attempts fixed-gutter, two-column left-to-right order within barrier-separated bands. */
        COLUMN_PARAGRAPHS,
        /**
         * Also omits generated separators after unambiguous letter-final physical hyphens within an upright
         * native paragraph. Reconstructs complete source lines before interpreting the boundary; retains every
         * source character and all geometry. This explicit compound-word assumption is not semantic dehyphenation.
         */
        COLUMN_PARAGRAPHS_COMPOUND_WRAPS,
        /**
         * Combines compound-aware upright column paragraphs with arbitrary-angle native line retention.
         * Projected angled lines remain separate structural barriers with approximate bounds; they never
         * become ordinary paragraph members. This does not imply semantic article order or text repair.
         */
        ORIENTED_COLUMN_PARAGRAPHS_COMPOUND_WRAPS;

        /**
         * Selects explicit arbitrary-angle retention independently of upright grouping.
         * @return whether projected native lines are supported
         */
        boolean oriented() {
            return this == ORIENTED_LINES || this == ORIENTED_COLUMN_PARAGRAPHS_COMPOUND_WRAPS;
        }

        /**
         * Selects the existing fixed-gutter column strategy.
         * @return whether ordinary upright source lines may form two columns
         */
        boolean columns() {
            return this == COLUMN_PARAGRAPHS || compoundWraps();
        }

        /**
         * Selects source-preserving physical-hyphen separator interpretation.
         * @return whether eligible ordinary paragraph boundaries may omit generated spaces
         */
        boolean compoundWraps() {
            return this == COLUMN_PARAGRAPHS_COMPOUND_WRAPS || this == ORIENTED_COLUMN_PARAGRAPHS_COMPOUND_WRAPS;
        }
    }

    /**
     * Validates positive budgets.
     * @param maxPages maximum page count
     * @param maxGlyphsPerPage maximum retained glyph positions per page
     * @param maxOperatorsPerPage maximum content operators per page
     * @param maxImagePixels maximum decoded image pixels
     * @param maxImageBytes cumulative PNG bytes
     * @param includeImageBytes whether to extract PNG payloads
     * @param tableLimits nonnull table budgets
     * @param tableDetection nonnull recognition policy
     * @param textLayout nonnull native text layout policy
     * @param includeTableCellCoordinates whether to describe every table anchor
     * @param tableContinuation nonnull page-linking policy
     * @param unicodeNormalization nonnull native Unicode policy
     * @param textRendering nonnull native text rendering-mode policy
     * @param figureCaptions nonnull explicit native caption policy
     * @param includeImageClipBounds whether to describe the invocation clip-bounds classification
     * @param includeImageGroupContext whether to list enclosing painted transparency-group invocation states
     * @param curvedClipEnvelopes whether curved clip paths contribute their enclosing control-point rectangle
     * @param inlineScripts nonnull explicit native small-glyph run grouping policy
     * @param sideStreams nonnull explicit side-stream grouping policy
     * @throws IllegalArgumentException if a budget is not positive
     * @throws NullPointerException if a required policy is null
     */
    public PdfOptions {
        if (maxPages < 1 || maxGlyphsPerPage < 1 || maxOperatorsPerPage < 1 || maxImagePixels < 1
                || maxImageBytes < 1) {
            throw new IllegalArgumentException("PDF budgets must be positive");
        }
        java.util.Objects.requireNonNull(tableLimits, "tableLimits");
        java.util.Objects.requireNonNull(tableDetection, "tableDetection");
        java.util.Objects.requireNonNull(textLayout, "textLayout");
        java.util.Objects.requireNonNull(tableContinuation, "tableContinuation");
        java.util.Objects.requireNonNull(unicodeNormalization, "unicodeNormalization");
        java.util.Objects.requireNonNull(textRendering, "textRendering");
        java.util.Objects.requireNonNull(figureCaptions, "figureCaptions");
        java.util.Objects.requireNonNull(inlineScripts, "inlineScripts");
        java.util.Objects.requireNonNull(sideStreams, "sideStreams");
    }

    /**
     * Preserves the pre-side-stream full constructor; interleaved streams keep historical grouping.
     * @param maxPages maximum page count
     * @param maxGlyphsPerPage visited source-position ceiling, including filtered positions
     * @param maxOperatorsPerPage content-operator ceiling
     * @param maxImagePixels decoded image pixel ceiling
     * @param maxImageBytes cumulative PNG byte ceiling
     * @param includeImageBytes whether to retain PNG payloads
     * @param tableLimits nonnull table budgets
     * @param tableDetection nonnull table policy
     * @param textLayout nonnull native grouping policy
     * @param includeTableCellCoordinates whether to describe table cells
     * @param tableContinuation nonnull linking policy
     * @param unicodeNormalization nonnull native Unicode policy
     * @param textRendering nonnull rendering-mode policy
     * @param figureCaptions nonnull explicit native caption policy
     * @param includeImageClipBounds whether to describe the invocation clip-bounds classification
     * @param includeImageGroupContext whether to list enclosing painted transparency-group invocation states
     * @param curvedClipEnvelopes whether curved clip paths contribute their enclosing control-point rectangle
     * @param inlineScripts nonnull explicit native small-glyph run grouping policy
     */
    public PdfOptions(final int maxPages, final int maxGlyphsPerPage, final int maxOperatorsPerPage,
            final long maxImagePixels, final int maxImageBytes, final boolean includeImageBytes,
            final TableLimits tableLimits, final TableDetection tableDetection, final TextLayout textLayout,
            final boolean includeTableCellCoordinates, final TableContinuation tableContinuation,
            final UnicodeNormalization unicodeNormalization, final TextRendering textRendering,
            final FigureCaptions figureCaptions, final boolean includeImageClipBounds,
            final boolean includeImageGroupContext, final boolean curvedClipEnvelopes,
            final InlineScripts inlineScripts) {
        this(maxPages, maxGlyphsPerPage, maxOperatorsPerPage, maxImagePixels, maxImageBytes, includeImageBytes,
                tableLimits, tableDetection, textLayout, includeTableCellCoordinates, tableContinuation,
                unicodeNormalization, textRendering, figureCaptions, includeImageClipBounds, includeImageGroupContext,
                curvedClipEnvelopes, inlineScripts, SideStreams.PRESERVE);
    }

    /**
     * Preserves the pre-inline-script full constructor; small-glyph runs keep historical baseline grouping.
     * @param maxPages maximum page count
     * @param maxGlyphsPerPage visited source-position ceiling, including filtered positions
     * @param maxOperatorsPerPage content-operator ceiling
     * @param maxImagePixels decoded image pixel ceiling
     * @param maxImageBytes cumulative PNG byte ceiling
     * @param includeImageBytes whether to retain PNG payloads
     * @param tableLimits nonnull table budgets
     * @param tableDetection nonnull table policy
     * @param textLayout nonnull native grouping policy
     * @param includeTableCellCoordinates whether to describe table cells
     * @param tableContinuation nonnull linking policy
     * @param unicodeNormalization nonnull native Unicode policy
     * @param textRendering nonnull rendering-mode policy
     * @param figureCaptions nonnull explicit native caption policy
     * @param includeImageClipBounds whether to describe the invocation clip-bounds classification
     * @param includeImageGroupContext whether to list enclosing painted transparency-group invocation states
     * @param curvedClipEnvelopes whether curved clip paths contribute their enclosing control-point rectangle
     */
    public PdfOptions(final int maxPages, final int maxGlyphsPerPage, final int maxOperatorsPerPage,
            final long maxImagePixels, final int maxImageBytes, final boolean includeImageBytes,
            final TableLimits tableLimits, final TableDetection tableDetection, final TextLayout textLayout,
            final boolean includeTableCellCoordinates, final TableContinuation tableContinuation,
            final UnicodeNormalization unicodeNormalization, final TextRendering textRendering,
            final FigureCaptions figureCaptions, final boolean includeImageClipBounds,
            final boolean includeImageGroupContext, final boolean curvedClipEnvelopes) {
        this(maxPages, maxGlyphsPerPage, maxOperatorsPerPage, maxImagePixels, maxImageBytes, includeImageBytes,
                tableLimits, tableDetection, textLayout, includeTableCellCoordinates, tableContinuation,
                unicodeNormalization, textRendering, figureCaptions, includeImageClipBounds, includeImageGroupContext,
                curvedClipEnvelopes, InlineScripts.PRESERVE);
    }

    /**
     * Preserves the pre-curved-clip full constructor; curved clip paths remain ignored.
     * @param maxPages maximum page count
     * @param maxGlyphsPerPage visited source-position ceiling, including filtered positions
     * @param maxOperatorsPerPage content-operator ceiling
     * @param maxImagePixels decoded image pixel ceiling
     * @param maxImageBytes cumulative PNG byte ceiling
     * @param includeImageBytes whether to retain PNG payloads
     * @param tableLimits nonnull table budgets
     * @param tableDetection nonnull table policy
     * @param textLayout nonnull native grouping policy
     * @param includeTableCellCoordinates whether to describe table cells
     * @param tableContinuation nonnull linking policy
     * @param unicodeNormalization nonnull native Unicode policy
     * @param textRendering nonnull rendering-mode policy
     * @param figureCaptions nonnull explicit native caption policy
     * @param includeImageClipBounds whether to describe the invocation clip-bounds classification
     * @param includeImageGroupContext whether to list enclosing painted transparency-group invocation states
     */
    public PdfOptions(final int maxPages, final int maxGlyphsPerPage, final int maxOperatorsPerPage,
            final long maxImagePixels, final int maxImageBytes, final boolean includeImageBytes,
            final TableLimits tableLimits, final TableDetection tableDetection, final TextLayout textLayout,
            final boolean includeTableCellCoordinates, final TableContinuation tableContinuation,
            final UnicodeNormalization unicodeNormalization, final TextRendering textRendering,
            final FigureCaptions figureCaptions, final boolean includeImageClipBounds,
            final boolean includeImageGroupContext) {
        this(maxPages, maxGlyphsPerPage, maxOperatorsPerPage, maxImagePixels, maxImageBytes, includeImageBytes,
                tableLimits, tableDetection, textLayout, includeTableCellCoordinates, tableContinuation,
                unicodeNormalization, textRendering, figureCaptions, includeImageClipBounds, includeImageGroupContext,
                false);
    }

    /**
     * Preserves the pre-group-context full constructor without enclosing transparency-group metadata.
     * @param maxPages maximum page count
     * @param maxGlyphsPerPage visited source-position ceiling, including filtered positions
     * @param maxOperatorsPerPage content-operator ceiling
     * @param maxImagePixels decoded image pixel ceiling
     * @param maxImageBytes cumulative PNG byte ceiling
     * @param includeImageBytes whether to retain PNG payloads
     * @param tableLimits nonnull table budgets
     * @param tableDetection nonnull table policy
     * @param textLayout nonnull native grouping policy
     * @param includeTableCellCoordinates whether to describe table cells
     * @param tableContinuation nonnull linking policy
     * @param unicodeNormalization nonnull native Unicode policy
     * @param textRendering nonnull rendering-mode policy
     * @param figureCaptions nonnull explicit native caption policy
     * @param includeImageClipBounds whether to describe the invocation clip-bounds classification
     */
    public PdfOptions(final int maxPages, final int maxGlyphsPerPage, final int maxOperatorsPerPage,
            final long maxImagePixels, final int maxImageBytes, final boolean includeImageBytes,
            final TableLimits tableLimits, final TableDetection tableDetection, final TextLayout textLayout,
            final boolean includeTableCellCoordinates, final TableContinuation tableContinuation,
            final UnicodeNormalization unicodeNormalization, final TextRendering textRendering,
            final FigureCaptions figureCaptions, final boolean includeImageClipBounds) {
        this(maxPages, maxGlyphsPerPage, maxOperatorsPerPage, maxImagePixels, maxImageBytes, includeImageBytes,
                tableLimits, tableDetection, textLayout, includeTableCellCoordinates, tableContinuation,
                unicodeNormalization, textRendering, figureCaptions, includeImageClipBounds, false);
    }

    /**
     * Preserves the pre-image-clip-bounds full constructor without the clip-bounds classification.
     * @param maxPages maximum page count
     * @param maxGlyphsPerPage visited source-position ceiling, including filtered positions
     * @param maxOperatorsPerPage content-operator ceiling
     * @param maxImagePixels decoded image pixel ceiling
     * @param maxImageBytes cumulative PNG byte ceiling
     * @param includeImageBytes whether to retain PNG payloads
     * @param tableLimits nonnull table budgets
     * @param tableDetection nonnull table policy
     * @param textLayout nonnull native grouping policy
     * @param includeTableCellCoordinates whether to describe table cells
     * @param tableContinuation nonnull linking policy
     * @param unicodeNormalization nonnull native Unicode policy
     * @param textRendering nonnull rendering-mode policy
     * @param figureCaptions nonnull explicit native caption policy
     */
    public PdfOptions(final int maxPages, final int maxGlyphsPerPage, final int maxOperatorsPerPage,
            final long maxImagePixels, final int maxImageBytes, final boolean includeImageBytes,
            final TableLimits tableLimits, final TableDetection tableDetection, final TextLayout textLayout,
            final boolean includeTableCellCoordinates, final TableContinuation tableContinuation,
            final UnicodeNormalization unicodeNormalization, final TextRendering textRendering,
            final FigureCaptions figureCaptions) {
        this(maxPages, maxGlyphsPerPage, maxOperatorsPerPage, maxImagePixels, maxImageBytes, includeImageBytes,
                tableLimits, tableDetection, textLayout, includeTableCellCoordinates, tableContinuation,
                unicodeNormalization, textRendering, figureCaptions, false);
    }

    /**
     * Preserves the rendering-policy constructor without optional caption classification.
     * @param maxPages maximum page count
     * @param maxGlyphsPerPage visited source-position ceiling
     * @param maxOperatorsPerPage content-operator ceiling
     * @param maxImagePixels decoded image pixel ceiling
     * @param maxImageBytes cumulative PNG byte ceiling
     * @param includeImageBytes whether to retain PNG payloads
     * @param tableLimits nonnull structured-table limits
     * @param tableDetection nonnull table policy
     * @param textLayout nonnull native grouping policy
     * @param includeTableCellCoordinates whether to describe table cells
     * @param tableContinuation nonnull linking policy
     * @param unicodeNormalization nonnull native Unicode policy
     * @param textRendering nonnull rendering-mode policy
     */
    public PdfOptions(final int maxPages, final int maxGlyphsPerPage, final int maxOperatorsPerPage,
            final long maxImagePixels, final int maxImageBytes, final boolean includeImageBytes,
            final TableLimits tableLimits, final TableDetection tableDetection, final TextLayout textLayout,
            final boolean includeTableCellCoordinates, final TableContinuation tableContinuation,
            final UnicodeNormalization unicodeNormalization, final TextRendering textRendering) {
        this(maxPages, maxGlyphsPerPage, maxOperatorsPerPage, maxImagePixels, maxImageBytes, includeImageBytes,
                tableLimits, tableDetection, textLayout, includeTableCellCoordinates, tableContinuation,
                unicodeNormalization, textRendering, FigureCaptions.NONE);
    }

    /**
     * Preserves the beta1 full constructor, including invisible searchable text by default.
     * @param maxPages maximum page count
     * @param maxGlyphsPerPage visited glyph-position ceiling, including filtered positions
     * @param maxOperatorsPerPage content-operator ceiling
     * @param maxImagePixels decoded image pixel ceiling
     * @param maxImageBytes cumulative PNG byte ceiling
     * @param includeImageBytes whether to include image payloads
     * @param tableLimits table budgets
     * @param tableDetection native table policy
     * @param textLayout native grouping policy
     * @param includeTableCellCoordinates whether to describe anchor geometry
     * @param tableContinuation page-linking policy
     * @param unicodeNormalization native Unicode policy
     */
    public PdfOptions(final int maxPages, final int maxGlyphsPerPage, final int maxOperatorsPerPage,
            final long maxImagePixels, final int maxImageBytes, final boolean includeImageBytes,
            final TableLimits tableLimits, final TableDetection tableDetection, final TextLayout textLayout,
            final boolean includeTableCellCoordinates, final TableContinuation tableContinuation,
            final UnicodeNormalization unicodeNormalization) {
        this(maxPages, maxGlyphsPerPage, maxOperatorsPerPage, maxImagePixels, maxImageBytes, includeImageBytes,
                tableLimits, tableDetection, textLayout, includeTableCellCoordinates, tableContinuation,
                unicodeNormalization, TextRendering.ALL);
    }

    /**
     * Preserves the previous full constructor with original decoded native Unicode.
     * @param maxPages maximum page count
     * @param maxGlyphsPerPage retained glyph ceiling
     * @param maxOperatorsPerPage content-operator ceiling
     * @param maxImagePixels decoded image pixel ceiling
     * @param maxImageBytes cumulative normalized PNG byte ceiling
     * @param includeImageBytes whether to include image payloads
     * @param tableLimits structured-table budgets
     * @param tableDetection explicit native table policy
     * @param textLayout explicit native grouping/order policy
     * @param includeTableCellCoordinates whether to describe anchor geometry
     * @param tableContinuation explicit adjacent-page linking policy
     */
    public PdfOptions(final int maxPages, final int maxGlyphsPerPage, final int maxOperatorsPerPage,
            final long maxImagePixels, final int maxImageBytes, final boolean includeImageBytes,
            final TableLimits tableLimits, final TableDetection tableDetection, final TextLayout textLayout,
            final boolean includeTableCellCoordinates, final TableContinuation tableContinuation) {
        this(maxPages, maxGlyphsPerPage, maxOperatorsPerPage, maxImagePixels, maxImageBytes, includeImageBytes,
                tableLimits, tableDetection, textLayout, includeTableCellCoordinates, tableContinuation,
                UnicodeNormalization.RAW);
    }

    /**
     * Preserves the cell-coordinate constructor without adjacent-page linking.
     * @param maxPages maximum page count
     * @param maxGlyphsPerPage maximum retained glyphs per page
     * @param maxOperatorsPerPage maximum content operators per page
     * @param maxImagePixels decoded pixel budget per image
     * @param maxImageBytes cumulative PNG byte budget
     * @param includeImageBytes whether to extract PNG payloads
     * @param tableLimits nonnull table budgets
     * @param tableDetection nonnull table recognition mode
     * @param textLayout nonnull native text grouping policy
     * @param includeTableCellCoordinates whether to emit cell geometry
     */
    public PdfOptions(final int maxPages, final int maxGlyphsPerPage, final int maxOperatorsPerPage,
            final long maxImagePixels, final int maxImageBytes, final boolean includeImageBytes,
            final TableLimits tableLimits, final TableDetection tableDetection, final TextLayout textLayout,
            final boolean includeTableCellCoordinates) {
        this(maxPages, maxGlyphsPerPage, maxOperatorsPerPage, maxImagePixels, maxImageBytes, includeImageBytes,
                tableLimits, tableDetection, textLayout, includeTableCellCoordinates, TableContinuation.NONE);
    }

    /**
     * Preserves the previous constructor without per-cell geometry allocation.
     * @param maxPages maximum page count
     * @param maxGlyphsPerPage maximum retained glyphs per page
     * @param maxOperatorsPerPage maximum content operators per page
     * @param maxImagePixels decoded pixel budget per image
     * @param maxImageBytes cumulative PNG byte budget
     * @param includeImageBytes whether to extract PNG payloads
     * @param tableLimits nonnull table budgets
     * @param tableDetection nonnull table recognition mode
     * @param textLayout nonnull native text grouping policy
     */
    public PdfOptions(final int maxPages, final int maxGlyphsPerPage, final int maxOperatorsPerPage,
            final long maxImagePixels, final int maxImageBytes, final boolean includeImageBytes,
            final TableLimits tableLimits, final TableDetection tableDetection, final TextLayout textLayout) {
        this(maxPages, maxGlyphsPerPage, maxOperatorsPerPage, maxImagePixels, maxImageBytes, includeImageBytes,
                tableLimits, tableDetection, textLayout, false);
    }

    /**
     * Retains the table-policy constructor with historical line-level output.
     * @param maxPages maximum page count
     * @param maxGlyphsPerPage maximum retained glyphs per page
     * @param maxOperatorsPerPage maximum content operators per page
     * @param maxImagePixels decoded pixel budget per image
     * @param maxImageBytes cumulative PNG byte budget
     * @param includeImageBytes whether to extract PNG payloads
     * @param tableLimits nonnull table budgets
     * @param tableDetection nonnull table recognition mode
     * @throws IllegalArgumentException if a budget is not positive
     * @throws NullPointerException if a required policy is null
     */
    public PdfOptions(final int maxPages, final int maxGlyphsPerPage, final int maxOperatorsPerPage,
            final long maxImagePixels, final int maxImageBytes, final boolean includeImageBytes,
            final TableLimits tableLimits, final TableDetection tableDetection) {
        this(maxPages, maxGlyphsPerPage, maxOperatorsPerPage, maxImagePixels, maxImageBytes, includeImageBytes,
                tableLimits, tableDetection, TextLayout.LINES);
    }

    /**
     * Preserves the original constructor with ruled-only recognition.
     * @param maxPages maximum page count
     * @param maxGlyphsPerPage maximum retained glyphs per page
     * @param maxOperatorsPerPage maximum content operators per page
     * @param maxImagePixels decoded pixel budget per image
     * @param maxImageBytes cumulative PNG byte budget
     * @param includeImageBytes whether to extract PNG payloads
     * @param tableLimits nonnull table budgets
     * @throws IllegalArgumentException if a budget is not positive
     * @throws NullPointerException if tableLimits is null
     */
    public PdfOptions(final int maxPages, final int maxGlyphsPerPage, final int maxOperatorsPerPage,
            final long maxImagePixels, final int maxImageBytes, final boolean includeImageBytes,
            final TableLimits tableLimits) {
        this(maxPages, maxGlyphsPerPage, maxOperatorsPerPage, maxImagePixels, maxImageBytes, includeImageBytes,
                tableLimits, TableDetection.RULED_ONLY);
    }

    /**
     * Copies this immutable policy with an explicit table detector selection.
     * @param detection nonnull recognition mode
     * @return a fresh policy retaining all resource and image settings
     * @throws NullPointerException if detection is null
     */
    public PdfOptions withTableDetection(final TableDetection detection) {
        return new PdfOptions(maxPages, maxGlyphsPerPage, maxOperatorsPerPage, maxImagePixels, maxImageBytes,
                includeImageBytes, tableLimits, detection, textLayout, includeTableCellCoordinates, tableContinuation,
                unicodeNormalization, textRendering, figureCaptions, includeImageClipBounds, includeImageGroupContext,
                curvedClipEnvelopes, inlineScripts, sideStreams);
    }

    /**
     * Copies this immutable policy with explicit native paragraph/column processing.
     * @param layout nonnull text grouping and order mode
     * @return fresh policy retaining image, table and resource settings
     * @throws NullPointerException if layout is null
     */
    public PdfOptions withTextLayout(final TextLayout layout) {
        return new PdfOptions(maxPages, maxGlyphsPerPage, maxOperatorsPerPage, maxImagePixels, maxImageBytes,
                includeImageBytes, tableLimits, tableDetection, layout, includeTableCellCoordinates, tableContinuation,
                unicodeNormalization, textRendering, figureCaptions, includeImageClipBounds, includeImageGroupContext,
                curvedClipEnvelopes, inlineScripts, sideStreams);
    }

    /**
     * Copies this policy with anchor-cell geometry for native and explicitly configured OCR tables.
     * Metadata describes displayed page bounds, not glyph outlines, OCR confidence or cropped image pixels.
     * Existing table/work limits bound generation; source-cell text is not duplicated in this extension.
     * @param include whether to emit the pdf_table_cells extension
     * @return immutable copy retaining every extraction, layout and resource setting
     */
    public PdfOptions withTableCellCoordinates(final boolean include) {
        return new PdfOptions(maxPages, maxGlyphsPerPage, maxOperatorsPerPage, maxImagePixels, maxImageBytes,
                includeImageBytes, tableLimits, tableDetection, textLayout, include, tableContinuation,
                unicodeNormalization, textRendering, figureCaptions, includeImageClipBounds, includeImageGroupContext,
                curvedClipEnvelopes, inlineScripts, sideStreams);
    }

    /**
     * Copies this policy with optional continuation inference. Each page's Table/text/HTML is retained.
     * This heuristic buffers one bounded page of pending output and stores a capped prior header signature.
     * @param continuation nonnull explicit inference policy
     * @return immutable copy retaining all extraction, geometry and resource settings
     * @throws NullPointerException if continuation is null
     */
    public PdfOptions withTableContinuation(final TableContinuation continuation) {
        return new PdfOptions(maxPages, maxGlyphsPerPage, maxOperatorsPerPage, maxImagePixels, maxImageBytes,
                includeImageBytes, tableLimits, tableDetection, textLayout, includeTableCellCoordinates, continuation,
                unicodeNormalization, textRendering, figureCaptions, includeImageClipBounds, includeImageGroupContext,
                curvedClipEnvelopes, inlineScripts, sideStreams);
    }

    /**
     * Copies the explicit native Unicode policy without changing source glyph geometry or OCR output.
     * Presentation expansion is capped at16million normalized UTF-16 units per page; source font-map
     * decoding can allocate before callbacks. This is not blanket NFKC, bidi reconstruction or font repair.
     * @param normalization nonnull native Unicode policy
     * @return immutable copy retaining all other policies and budgets
     * @throws NullPointerException if normalization is null
     */
    public PdfOptions withUnicodeNormalization(final UnicodeNormalization normalization) {
        return new PdfOptions(maxPages, maxGlyphsPerPage, maxOperatorsPerPage, maxImagePixels, maxImageBytes,
                includeImageBytes, tableLimits, tableDetection, textLayout, includeTableCellCoordinates,
                tableContinuation, normalization, textRendering, figureCaptions, includeImageClipBounds,
                includeImageGroupContext, curvedClipEnvelopes, inlineScripts, sideStreams);
    }

    /**
     * Selects native text by rendering mode before duplicate suppression, tables and OCR selection.
     * PAINTED_MODES removes modes3/7 even when they hold a useful searchable scan layer. It does
     * not evaluate clipping, alpha, occlusion, optional-content visibility or font painting accuracy.
     * NONZERO_ALPHA_PAINTED_MODES additionally requires positive alpha for at least one enabled
     * fill/stroke operation. This is a graphics-state filter, not full rendered visibility; soft masks,
     * clipping, occlusion and optional-content visibility remain outside this policy.
     * NONZERO_ALPHA_NONEMPTY_CLIP_PAINTED_MODES also excludes empty inherited form/page clipping
     * areas before duplicate suppression and marked-content ownership. It does not test individual glyph
     * containment, explicit path clips, partial visibility, soft masks or occlusion.
     * Filtered source positions still count against maxGlyphsPerPage. Source files are unchanged.
     * @param rendering nonnull explicit native policy
     * @return immutable copy retaining all other settings
     * @throws NullPointerException if rendering is null
     */
    public PdfOptions withTextRendering(final TextRendering rendering) {
        return new PdfOptions(maxPages, maxGlyphsPerPage, maxOperatorsPerPage, maxImagePixels, maxImageBytes,
                includeImageBytes, tableLimits, tableDetection, textLayout, includeTableCellCoordinates,
                tableContinuation, unicodeNormalization, rendering, figureCaptions, includeImageClipBounds,
                includeImageGroupContext, curvedClipEnvelopes, inlineScripts, sideStreams);
    }

    /**
     * Selects optional image-adjacent native caption classification without generating or rewriting source text.
     * Only bounded upright native text units with a strict Fig/Figure label and one eligible nearby raster
     * are considered. Ambiguous, rotated, vector-only and unlabelled captions retain ordinary text.
     * Existing OCR output, images, table ownership, order and resource limits remain unchanged.
     * @param policy nonnull explicit caption policy
     * @return immutable copy retaining every other setting
     * @throws NullPointerException if policy is null
     */
    public PdfOptions withFigureCaptions(final FigureCaptions policy) {
        return new PdfOptions(maxPages, maxGlyphsPerPage, maxOperatorsPerPage, maxImagePixels, maxImageBytes,
                includeImageBytes, tableLimits, tableDetection, textLayout, includeTableCellCoordinates,
                tableContinuation, unicodeNormalization, textRendering, policy, includeImageClipBounds,
                includeImageGroupContext, curvedClipEnvelopes, inlineScripts, sideStreams);
    }

    /**
     * Copies this policy with a conservative per-image invocation clip-bounds classification.
     * Reuses the same bounded, already-validated enclosing-rectangle geometry used for native rule filtering;
     * it is always a superset of the true clip and never a complete final-visibility oracle. Any ancestor
     * clip operator unsupported by this traversal is simply not intersected, so the reported rectangle may be
     * wider than the actual clip; it never proves nonempty image coverage, opacity or final page occlusion.
     * @param include whether to emit the pdf_image_clip_bounds extension
     * @return immutable copy retaining every other extraction, layout and resource setting
     */
    public PdfOptions withImageClipBounds(final boolean include) {
        return new PdfOptions(maxPages, maxGlyphsPerPage, maxOperatorsPerPage, maxImagePixels, maxImageBytes,
                includeImageBytes, tableLimits, tableDetection, textLayout, includeTableCellCoordinates,
                tableContinuation, unicodeNormalization, textRendering, figureCaptions, include,
                includeImageGroupContext, curvedClipEnvelopes, inlineScripts, sideStreams);
    }

    /**
     * Copies this policy with each image's enclosing painted transparency-group invocation states.
     * Entries are ordered outermost to innermost and snapshot the caller state at each group's Do operator:
     * nonstroking alpha, blend mode, soft-mask presence, alpha-source flag and the group's isolated/knockout
     * attributes (PDF default false when absent). Ordinary forms add no entry; soft-mask groups are not painted
     * groups. The existing current-stream {@code pdf_image_graphics_state} and PNG samples are unchanged.
     * Entries are source provenance only: their alpha constants are not an effective or final opacity.
     * @param include whether to emit the pdf_image_group_context extension
     * @return immutable copy retaining every other extraction, layout and resource setting
     */
    public PdfOptions withImageGroupContext(final boolean include) {
        return new PdfOptions(maxPages, maxGlyphsPerPage, maxOperatorsPerPage, maxImagePixels, maxImageBytes,
                includeImageBytes, tableLimits, tableDetection, textLayout, includeTableCellCoordinates,
                tableContinuation, unicodeNormalization, textRendering, figureCaptions, includeImageClipBounds, include,
                curvedClipEnvelopes, inlineScripts, sideStreams);
    }

    /**
     * Copies this policy with explicit conservative interpretation of curved clip paths.
     * A curved clip contributes the axis-aligned rectangle enclosing its path points and Bezier control points,
     * which contains the true clip region. The graphics traversal uses this tighter conservative clip for
     * native rule filtering and optional image clip bounds. The separate native-text visitor's explicit-path
     * filtering is unchanged. The rectangle is never the exact curve; overlap does not prove visibility.
     * @param include whether curved clips contribute their control-point rectangle
     * @return immutable copy retaining every other extraction, layout and resource setting
     */
    public PdfOptions withCurvedClipEnvelopes(final boolean include) {
        return new PdfOptions(maxPages, maxGlyphsPerPage, maxOperatorsPerPage, maxImagePixels, maxImageBytes,
                includeImageBytes, tableLimits, tableDetection, textLayout, includeTableCellCoordinates,
                tableContinuation, unicodeNormalization, textRendering, figureCaptions, includeImageClipBounds,
                includeImageGroupContext, include, inlineScripts, sideStreams);
    }

    /**
     * Copies this policy with explicit grouping of small native glyph runs into their nearby body line.
     * Applies to ordinary upright native text after table staging, before line, paragraph and column grouping;
     * native, tagged and provider table cells keep their own policies. Work is bounded by the shared native page
     * counter and cancellation checks, and each run is compared only with glyphs in its own baseline band;
     * exhausting that counter fails the page with the existing {@code TABLE_LIMIT} code rather than returning
     * partial grouping.
     * @param policy nonnull explicit grouping policy
     * @return immutable copy retaining every other extraction, layout and resource setting
     * @throws NullPointerException if policy is null
     */
    public PdfOptions withInlineScripts(final InlineScripts policy) {
        return new PdfOptions(maxPages, maxGlyphsPerPage, maxOperatorsPerPage, maxImagePixels, maxImageBytes,
                includeImageBytes, tableLimits, tableDetection, textLayout, includeTableCellCoordinates,
                tableContinuation, unicodeNormalization, textRendering, figureCaptions, includeImageClipBounds,
                includeImageGroupContext, curvedClipEnvelopes, policy, sideStreams);
    }

    /**
     * Copies this policy with explicit block grouping of a smaller-type side stream.
     * Applies to paragraph-producing native layouts on pages without an accepted two-column gutter. Detection and
     * grouping share the native layout page budget and its {@code LAYOUT_LIMIT} failure and check cancellation.
     * @param policy nonnull explicit side-stream policy
     * @return immutable copy retaining every other extraction, layout and resource setting
     * @throws NullPointerException if policy is null
     */
    public PdfOptions withSideStreams(final SideStreams policy) {
        return new PdfOptions(maxPages, maxGlyphsPerPage, maxOperatorsPerPage, maxImagePixels, maxImageBytes,
                includeImageBytes, tableLimits, tableDetection, textLayout, includeTableCellCoordinates,
                tableContinuation, unicodeNormalization, textRendering, figureCaptions, includeImageClipBounds,
                includeImageGroupContext, curvedClipEnvelopes, inlineScripts, policy);
    }

    /**
     * Returns bounded native extraction defaults.
     * @return 1000 pages, 250000 glyphs/page, 100000 operators/page, 16M pixels/image, 16MiB PNG/document, references
     *         only
     */
    public static PdfOptions defaults() {
        return new PdfOptions(1000, 250_000, 100_000, 16_000_000, 16 * 1024 * 1024, false, TableLimits.defaults());
    }
}
