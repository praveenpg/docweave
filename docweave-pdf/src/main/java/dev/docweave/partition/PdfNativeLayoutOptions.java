package dev.docweave.partition;

/**
 * Explicit render and raw-anchor limits for native text assigned to provider table geometry.
 * No OCR engine is invoked. These limits do not bound PDFBox intermediate allocations or provider deadlines.
 * @param cellTextJoin explicit provider-owned native cell text policy; defaults preserve space-separated lines
 * @param leadingRow optional source-based leading row inference; defaults preserve provider bounds
 * @param trailingRows optional source-based trailing row inference; defaults preserve provider bounds
 * @param dpi render resolution,36 through600
 * @param maxRenderPixels maximum rendered pixels per native-text page
 * @param maxTableAnchors maximum aggregate raw provider anchors per page; native-first composition also
 *                        charges accepted native anchors to this ceiling; inferred leading- and trailing-row
 *                        anchors also count
 */
public record PdfNativeLayoutOptions(int dpi, long maxRenderPixels, int maxTableAnchors, CellTextJoin cellTextJoin,
        LeadingRow leadingRow, TrailingRows trailingRows) {
    /** Explicit source inference below an otherwise validated provider table. */
    public enum TrailingRows {
        /** Preserves the provider's original row count, bounds and anchors. */
        PROVIDER_ONLY,
        /**
         * Appends complete source lines below an upright provider table while they repeat the table's own
         * whitespace cell lattice. Some producers, for example HTML-to-PDF converters, paint an explicit
         * space glyph for each empty or padding cell at fixed column positions, including in visually blank
         * rows. The lattice is the set of whitespace glyph positions that every nonempty provider row
         * repeats within one tenth of a displayed unit; at least two positions are required. Lines below
         * the table are read in baseline order and accepted only while each one repeats every lattice
         * position, keeps all of its glyphs upright and within the table's horizontal extent, and fits each
         * visible glyph inside one of the last provider row's unmerged columns. Repeating blank lattice lines
         * are crossed but never become rows; each visible line becomes one inferred row. A footnote, separate
         * note or aligned number without the lattice stops the scan, and a conflicting, rotated, misfitting
         * or merged-column extension retains the provider table. Text is never classified by vocabulary,
         * punctuation or number grammar. This is source-structure inference, not semantic proof of
         * membership, and it adds nothing when a producer paints no whitespace cells. Default bounds are
         * unchanged.
         */
        SOURCE_CELL_SPACER_ROWS
    }

    /**
     * Preserves the former five-argument constructor without inferring trailing geometry.
     * @param dpi render resolution,36 through600
     * @param maxRenderPixels rendered pixel ceiling
     * @param maxTableAnchors aggregate native/provider anchor ceiling
     * @param cellTextJoin native cell text choice
     * @param leadingRow leading-row inference choice
     */
    public PdfNativeLayoutOptions(final int dpi, final long maxRenderPixels, final int maxTableAnchors,
            final CellTextJoin cellTextJoin, final LeadingRow leadingRow) {
        this(dpi, maxRenderPixels, maxTableAnchors, cellTextJoin, leadingRow, TrailingRows.PROVIDER_ONLY);
    }

    /** Explicit source inference outside an otherwise validated provider table. */
    public enum LeadingRow {
        /** Preserves the provider's original row count, bounds and anchors. */
        PROVIDER_ONLY,
        /**
         * Infers at most one complete numeric body row immediately above an upright provider table.
         * Requires at least three unmerged first-row columns, no declared headers, a letter-bearing
         * label and one numeric run per remaining column on a common source baseline. Every glyph
         * must fit the existing column; ambiguous, rotated, overlapping or incomplete rows are retained
         * as ordinary text. The search band is one original first-row height. No OCR or model rerun occurs.
         * Added geometry is inferred, not claimed by the provider. Normal table/work limits still apply.
         */
        SOURCE_NUMERIC_ROW,
        /**
         * Also accepts a single colon-terminated section label above a complete numeric first body row.
         * Other leading cells must be empty, and the label must align within two displayed units of
         * the first body label, including source indentation spaces on the selected baseline.
         * Supporting numeric cells may contain one leading dollar, pound or euro mark separated
         * horizontally from digits; multiple body baselines remain unsupported.
         * The numeric-row topology, source band, ownership and budget safeguards remain in force. This is conservative
         * source inference, not semantic proof of membership; unrelated aligned colon headings can still be ambiguous.
         * Default provider bounds are unchanged.
         */
        SOURCE_NUMERIC_OR_SECTION_ROW
    }

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
        this(dpi, maxRenderPixels, maxTableAnchors, CellTextJoin.SPACE_SEPARATED, LeadingRow.PROVIDER_ONLY);
    }

    /**
     * Preserves the former four-argument constructor without inferring additional geometry.
     * @param dpi render resolution,36 through600
     * @param maxRenderPixels rendered pixel ceiling
     * @param maxTableAnchors aggregate native/provider anchor ceiling
     * @param cellTextJoin native cell text choice
     */
    public PdfNativeLayoutOptions(
            final int dpi, final long maxRenderPixels, final int maxTableAnchors, final CellTextJoin cellTextJoin) {
        this(dpi, maxRenderPixels, maxTableAnchors, cellTextJoin, LeadingRow.PROVIDER_ONLY);
    }

    /**
     * Copies this policy with explicit leading-row inference for provider-owned native tables only.
     * Existing anchors and their text remain unchanged; accepted inference adds one row, shifts logical
     * row indices and expands the table envelope. Metadata identifies the inferred boundary policy.
     * Source-tagged tables, native heuristic tables and OCR output are unaffected.
     * @param policy nonnull leading-row choice
     * @return new immutable policy retaining render limits and cell text selection
     * @throws NullPointerException if policy is null
     */
    public PdfNativeLayoutOptions withLeadingRow(final LeadingRow policy) {
        return new PdfNativeLayoutOptions(dpi, maxRenderPixels, maxTableAnchors, cellTextJoin, policy, trailingRows);
    }

    /**
     * Copies this policy with explicit trailing-row inference for provider-owned native tables only.
     * Existing anchors, row indices and their text remain unchanged; accepted inference appends rows and
     * expands the table envelope downward. Metadata records the trailing boundary policy and added row count.
     * Leading-row inference, when also selected, runs first. Source-tagged tables, native heuristic tables and
     * OCR output are unaffected.
     * @param policy nonnull trailing-row choice
     * @return new immutable policy retaining render limits, cell text and leading-row selection
     * @throws NullPointerException if policy is null
     */
    public PdfNativeLayoutOptions withTrailingRows(final TrailingRows policy) {
        return new PdfNativeLayoutOptions(dpi, maxRenderPixels, maxTableAnchors, cellTextJoin, leadingRow, policy);
    }

    /**
     * Copies this immutable policy with an explicit cell text strategy.
     * The date policy applies to provider-owned native cells and explicitly enabled source-tagged cells.
     * This text selection leaves native heuristic tables, ordinary text, OCR and source geometry unchanged.
     * Any independently selected leading-row inference remains enabled.
     * PRESERVE_LINE_BREAKS retains upright source baselines within existing provider or tagged cells;
     * it does not reconstruct missing physical table rows or change model predictions.
     * INLINE_SCRIPT_RUNS optionally groups unambiguous short small-glyph runs inside those same
     * existing cells, retaining all source characters and geometry. Rotated frames retain historical grouping.
     * @param policy nonnull cell text choice
     * @return new immutable policy
     * @throws NullPointerException if policy is null
     */
    public PdfNativeLayoutOptions withCellTextJoin(final CellTextJoin policy) {
        return new PdfNativeLayoutOptions(dpi, maxRenderPixels, maxTableAnchors, policy, leadingRow, trailingRows);
    }

    /**
     * Validates immutable positive limits without rendering or executing providers.
     * @throws IllegalArgumentException if resolution or either budget is invalid
     * @throws NullPointerException if any policy is null
     */
    public PdfNativeLayoutOptions {
        java.util.Objects.requireNonNull(cellTextJoin, "cellTextJoin");
        java.util.Objects.requireNonNull(leadingRow, "leadingRow");
        java.util.Objects.requireNonNull(trailingRows, "trailingRows");
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
