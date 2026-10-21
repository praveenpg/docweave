package dev.docweave.partition;

/**
 * Explicit render and raw-anchor limits for native text assigned to provider table geometry.
 * No OCR engine is invoked. These limits do not bound PDFBox intermediate allocations or provider deadlines.
 * @param cellTextJoin explicit provider-owned native cell text policy; defaults preserve space-separated lines
 * @param dpi render resolution,36 through600
 * @param maxRenderPixels maximum rendered pixels per native-text page
 * @param maxTableAnchors maximum aggregate raw provider anchors per page; native-first composition also
 *                        charges accepted native anchors to this ceiling
 */
public record PdfNativeLayoutOptions(int dpi, long maxRenderPixels, int maxTableAnchors, CellTextJoin cellTextJoin) {
    /** Explicit native structured cell text choices; no option changes geometry or OCR output. */
    public enum CellTextJoin {
        /** Preserves the historical space between every grouped native text run. */
        SPACE_SEPARATED,
        /** Joins an entire valid ISO date split after hyphens across two or three aligned upright native lines. */
        WRAPPED_ISO_DATE,
        /**
         * Also joins tagged word wraps whose single hyphen glyph has an unambiguous source ActualText
         * of U+00AD. Requires separate upright lines and letter adjacency; arbitrary alternatives and
         * untagged hard hyphens are preserved. Provider-only cells retain the ISO-date policy.
         */
        SOURCE_SOFT_HYPHENS_AND_WRAPPED_ISO_DATE,
        /**
         * Keeps distinct upright native baselines within each cell as LF-separated text and HTML
         * line breaks. Same-baseline separated runs retain spaces. Does not split rows or cells,
         * join dates/hyphens or change geometry; mixed/rotated provider text retains historical joining.
         */
        PRESERVE_LINE_BREAKS,
        /**
         * Groups complete nearby runs of at most eight smaller glyphs with one unambiguous body
         * baseline within an existing cell. Only upright native text on an unrotated displayed
         * frame is interpreted. Preserves source characters, bounds, topology and ambiguous runs;
         * does not join dates, remove hyphens or introduce mathematical markup.
         */
        INLINE_SCRIPT_RUNS,
        /**
         * Combines valid wrapped ISO dates with proven source word wraps. Physical compound hyphens remain
         * while only generated separators are omitted. Tagged cells additionally honor unambiguous U+00AD
         * hints, with source-proven discretionary removal taking precedence. Provider-only cells have no
         * tagged discretionary hints. Original text budgets, geometry and unsupported-frame fallback remain.
         */
        SOURCE_WORD_WRAPS_AND_ISO_DATES
    }

    /**
     * Preserves the original constructor and historical cell text behavior.
     * @param dpi render resolution,36 through600
     * @param maxRenderPixels rendered pixel ceiling
     * @param maxTableAnchors aggregate native/provider anchor ceiling
     */
    public PdfNativeLayoutOptions(final int dpi, final long maxRenderPixels, final int maxTableAnchors) {
        this(dpi, maxRenderPixels, maxTableAnchors, CellTextJoin.SPACE_SEPARATED);
    }

    /**
     * Copies this immutable policy with an explicit cell text strategy.
     * The date policy applies to provider-owned native cells and explicitly enabled source-tagged cells.
     * Accepted native heuristic tables, ordinary text, OCR and source geometry are unchanged.
     * PRESERVE_LINE_BREAKS retains upright source baselines within existing provider or tagged cells;
     * it does not reconstruct missing physical table rows or change model predictions.
     * INLINE_SCRIPT_RUNS optionally groups unambiguous short small-glyph runs inside those same
     * existing cells, retaining all source characters and geometry. Rotated frames retain historical grouping.
     * @param policy nonnull cell text choice
     * @return new immutable policy
     * @throws NullPointerException if policy is null
     */
    public PdfNativeLayoutOptions withCellTextJoin(final CellTextJoin policy) {
        return new PdfNativeLayoutOptions(dpi, maxRenderPixels, maxTableAnchors, policy);
    }

    /**
     * Validates immutable positive limits without rendering or executing providers.
     * @throws IllegalArgumentException if resolution or either budget is invalid
     * @throws NullPointerException if cellTextJoin is null
     */
    public PdfNativeLayoutOptions {
        java.util.Objects.requireNonNull(cellTextJoin, "cellTextJoin");
        if (dpi < 36 || dpi > 600 || maxRenderPixels < 1 || maxTableAnchors < 1) {
            throw new IllegalArgumentException("Invalid native PDF layout policy");
        }
    }

    /**
     * Returns bounded150DPI defaults with16M pixels and256 raw anchors.
     * @return immutable policy; larger table budgets require explicit selection
     */
    public static PdfNativeLayoutOptions defaults() {
        return new PdfNativeLayoutOptions(150, 16_000_000, 256);
    }

    /**
     * Adapts only raw geometry limits to the shared complete-topology validator; no recognition runs.
     * @return internal validation policy whose OCR settings are unused
     */
    PdfOcrOptions validation() {
        return new PdfOcrOptions(dpi, maxRenderPixels, 1, 1, PdfOcrOptions.Selection.TEXTLESS_PAGES, 1,
                PdfOcrOptions.TableDetection.PROVIDER_LAYOUT, maxTableAnchors);
    }
}
