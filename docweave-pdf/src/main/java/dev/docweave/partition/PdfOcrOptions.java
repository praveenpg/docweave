package dev.docweave.partition;

/**
 * Opt-in scanned-page OCR budgets, independent of native image payload policies.
 * Rendering is limited before allocation and checked again against actual raster dimensions.
 * These limits do not bound PDFBox intermediate allocations or work inside an external provider.
 * @param dpi rendering resolution, from 36 through 600 dots per inch
 * @param maxRenderPixels maximum pixels in one rendered page or padded provider-cell crop
 * @param maxLines maximum aggregate returned lines per page, including cell calls and blank lines
 * @param maxCharacters maximum aggregate UTF-16 units per page, including inserted cell/table joining spaces
 * @param selection explicit native/OCR ownership policy
 * @param maxRegions maximum image placements considered on a mixed page, before rectangle deduplication
 * @param tableDetection opt-in rendered ruled-grid, actual-word or explicit-layout table recognition
 * @param maxTableCells maximum prospective ruled-cell calls, accepted aligned anchors or raw provider anchors per page
 * @param readingOrder explicit whole-page native/OCR emission order
 * @param cellContextPixels extra rendered pixels around provider-layout cell crops, from 0 through 16;
 *                          clamped to the provider table, default 0, unused by other table modes
 */
public record PdfOcrOptions(int dpi, long maxRenderPixels, int maxLines, int maxCharacters, Selection selection,
        int maxRegions, TableDetection tableDetection, int maxTableCells, ReadingOrder readingOrder,
        int cellContextPixels) {
    /**
     * Preserves historical anchor-only cell crops for existing ordering configurations.
     * @param dpi render resolution
     * @param maxRenderPixels raster budget
     * @param maxLines aggregate raw line budget
     * @param maxCharacters aggregate UTF-16 budget
     * @param selection page/region policy
     * @param maxRegions source-image placement budget
     * @param tableDetection scanned-table strategy
     * @param maxTableCells raw anchor/cell-call budget
     * @param readingOrder page emission policy
     */
    public PdfOcrOptions(final int dpi, final long maxRenderPixels, final int maxLines, final int maxCharacters,
            final Selection selection, final int maxRegions, final TableDetection tableDetection,
            final int maxTableCells, final ReadingOrder readingOrder) {
        this(dpi, maxRenderPixels, maxLines, maxCharacters, selection, maxRegions, tableDetection, maxTableCells,
                readingOrder, 0);
    }

    /** Explicit mixed-source ordering; geometry cannot determine logical article order. */
    public enum ReadingOrder {
        /** Retains historical native output followed by provider OCR output. */ SOURCE_ORDER,
        /** Sorts all page elements by displayed top then left, preserving encounter order for ties. */ GEOMETRIC
    }

    /**
     * Preserves historical emission order for existing scanned-table policies.
     * @param dpi render resolution
     * @param maxRenderPixels raster budget
     * @param maxLines aggregate line budget
     * @param maxCharacters aggregate character budget
     * @param selection page selection
     * @param maxRegions image-placement budget
     * @param tableDetection scanned-table policy
     * @param maxTableCells cell-call budget
     */
    public PdfOcrOptions(final int dpi, final long maxRenderPixels, final int maxLines, final int maxCharacters,
            final Selection selection, final int maxRegions, final TableDetection tableDetection,
            final int maxTableCells) {
        this(dpi, maxRenderPixels, maxLines, maxCharacters, selection, maxRegions, tableDetection, maxTableCells,
                ReadingOrder.SOURCE_ORDER);
    }

    /** Explicit page/region ownership; native text is retained except under ALL_PAGES. */
    public enum Selection {
        /** Recognize whole pages only when no nonblank native glyph exists; the default. */ TEXTLESS_PAGES,
        /** Also recognize masked raster-image areas on pages containing native text. */ IMAGE_REGIONS,
        /** Recognize every whole page, replacing native text/tables while retaining original source images. */
        ALL_PAGES
    }

    /** Optional raster table interpretation; ordinary OCR remains the default. */
    public enum TableDetection {
        /** Do not reinterpret OCR text as a structured table. */ NONE,
        /** Recognize closed axis-aligned dark grids with rectangular merges and bounded anchor-cell OCR. */ RULED,
        /** Infer uniform numeric tables from actual word boxes; requires PdfWordOcrProvider. */ ALIGNED_WORDS,
        /** Use explicit complete layout geometry and actual source words; both providers are required. */
        PROVIDER_LAYOUT,
        /** Refine explicit layout anchors with bounded TABLE_CELL OCR; PAGE words retain remainder ownership. */
        PROVIDER_LAYOUT_CELLS
    }

    /**
     * Preserves the original region-selection policy without scanned-table inference.
     * @param dpi rendering resolution
     * @param maxRenderPixels page raster pixel ceiling
     * @param maxLines returned line ceiling
     * @param maxCharacters returned UTF-16 ceiling
     * @param selection page/region policy
     * @param maxRegions mixed-page image-placement ceiling
     */
    public PdfOcrOptions(final int dpi, final long maxRenderPixels, final int maxLines, final int maxCharacters,
            final Selection selection, final int maxRegions) {
        this(dpi, maxRenderPixels, maxLines, maxCharacters, selection, maxRegions, TableDetection.NONE, 256);
    }

    /**
     * Preserves the original textless-page policy with a 256-placement mixed-page budget.
     * @param dpi rendering resolution
     * @param maxRenderPixels page raster pixel ceiling
     * @param maxLines returned line ceiling
     * @param maxCharacters returned UTF-16 ceiling
     */
    public PdfOcrOptions(final int dpi, final long maxRenderPixels, final int maxLines, final int maxCharacters) {
        this(dpi, maxRenderPixels, maxLines, maxCharacters, Selection.TEXTLESS_PAGES, 256);
    }

    /**
     * Validates positive resource limits, rendering resolution and bounded optional cell context.
     * @throws IllegalArgumentException if a limit, resolution or context is invalid
     * @throws NullPointerException if a selection, table or ordering policy is null
     */
    public PdfOcrOptions {
        java.util.Objects.requireNonNull(selection, "selection");
        java.util.Objects.requireNonNull(tableDetection, "tableDetection");
        java.util.Objects.requireNonNull(readingOrder, "readingOrder");
        if (dpi < 36 || dpi > 600 || maxRenderPixels < 1 || maxLines < 1 || maxCharacters < 1 || maxRegions < 1
                || maxTableCells < 1 || cellContextPixels < 0 || cellContextPixels > 16) {
            throw new IllegalArgumentException("Invalid PDF OCR limits or cell context");
        }
    }

    /**
     * Copies this policy with explicit page/region selection, preserving every budget.
     * @param selection nonnull ownership policy
     * @return immutable configured copy
     * @throws NullPointerException if selection is null
     */
    public PdfOcrOptions withSelection(final Selection selection) {
        return new PdfOcrOptions(dpi, maxRenderPixels, maxLines, maxCharacters, selection, maxRegions, tableDetection,
                maxTableCells, readingOrder, cellContextPixels);
    }

    /**
     * Copies this policy with explicit rendering resolution while retaining all budgets and ownership.
     * Higher resolution consumes more pixels and does not guarantee improved recognition.
     * @param dpi render resolution,36 through600 dots per inch
     * @return immutable configured copy
     * @throws IllegalArgumentException if dpi is outside36 through600
     */
    public PdfOcrOptions withDpi(final int dpi) {
        return new PdfOcrOptions(dpi, maxRenderPixels, maxLines, maxCharacters, selection, maxRegions, tableDetection,
                maxTableCells, readingOrder, cellContextPixels);
    }

    /**
     * Copies this policy with optional scanned-table recognition, preserving all other budgets and selection.
     * @param detection nonnull raster table policy
     * @return immutable configured copy
     * @throws NullPointerException if detection is null
     */
    public PdfOcrOptions withTableDetection(final TableDetection detection) {
        return new PdfOcrOptions(dpi, maxRenderPixels, maxLines, maxCharacters, selection, maxRegions, detection,
                maxTableCells, readingOrder, cellContextPixels);
    }

    /**
     * Copies this policy with explicit mixed native/OCR ordering, retaining all resource limits.
     * @param order nonnull page emission policy
     * @return immutable configured copy
     * @throws NullPointerException if order is null
     */
    public PdfOcrOptions withReadingOrder(final ReadingOrder order) {
        return new PdfOcrOptions(dpi, maxRenderPixels, maxLines, maxCharacters, selection, maxRegions, tableDetection,
                maxTableCells, order, cellContextPixels);
    }

    /**
     * Copies this policy with explicit source context for PROVIDER_LAYOUT_CELLS recognition.
     * Context extends each anchor crop in rendered input pixels, clamped to its provider table,
     * before existing outward rounding and white padding. Reported table/cell rectangles stay
     * unchanged. Added context can include neighboring cell content; this is an opt-in geometry
     * heuristic, not text repair or an accuracy guarantee. Expanded crops retain the existing
     * raster/work/output budgets. Other table modes ignore this setting.
     * @param pixels extra rendered pixels on each edge, from 0 through 16; 0 preserves historical crops
     * @return immutable configured copy retaining all other settings
     * @throws IllegalArgumentException if pixels is outside 0 through 16
     */
    public PdfOcrOptions withCellContextPixels(final int pixels) {
        return new PdfOcrOptions(dpi, maxRenderPixels, maxLines, maxCharacters, selection, maxRegions, tableDetection,
                maxTableCells, readingOrder, pixels);
    }

    /**
     * Returns 150 DPI, 16 million pixels, 10,000 lines and one million UTF-16 units per page.
     * @return immutable conservative policy
     */
    public static PdfOcrOptions defaults() {
        return new PdfOcrOptions(150, 16_000_000, 10_000, 1_000_000);
    }
}
