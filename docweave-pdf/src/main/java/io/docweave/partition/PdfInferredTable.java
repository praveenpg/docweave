package io.docweave.partition;

import java.util.List;

/**
 * Document-confined inferred table with source glyph ownership and rectangular header anchors.
 * @param rows immutable row-major anchors, including empty uncovered header positions
 * @param columns number of supported columns
 * @param headerRows number of inferred header baselines
 * @param members original glyph indices owned once
 * @param numericColumns complete numeric body columns
 * @param frame source supporting envelope
 * @param omittedPaddingGlyphs blank positions omitted only from explicitly selected text projection
 */
record PdfInferredTable(List<List<Cell>> rows, int columns, int headerRows, List<Integer> members,
        List<Integer> numericColumns, PdfLayoutItem.Bounds frame, int omittedPaddingGlyphs) {
    /**
     * Preserves historical inferred-table construction without numeric padding projection.
     * @param rows immutable source-supported cells
     * @param columns supported column count
     * @param headerRows inferred header rows
     * @param members original owned indices
     * @param numericColumns supported numeric columns
     * @param frame original supporting envelope
     */
    PdfInferredTable(final List<List<Cell>> rows, final int columns, final int headerRows, final List<Integer> members,
            final List<Integer> numericColumns, final PdfLayoutItem.Bounds frame) {
        this(rows, columns, headerRows, members, numericColumns, frame, 0);
    }

    /**
     * One inferred rectangular anchor; a span is never described as an observed painted merge.
     * @param column zero-based origin
     * @param columnSpan positive inferred width in columns
     * @param rowSpan positive inferred height in source-supported rows
     * @param text source line text, empty only for an uncovered header position
     * @param bounds inferred cell area in displayed source units
     */
    record Cell(int column, int columnSpan, int rowSpan, String text, PdfLayoutItem.Bounds bounds) {
        /**
         * Preserves the existing single-row inferred-cell construction.
         * @param column zero-based origin
         * @param columnSpan positive column extent
         * @param text retained source text
         * @param bounds source-supported inferred area
         */
        Cell(final int column, final int columnSpan, final String text, final PdfLayoutItem.Bounds bounds) {
            this(column, columnSpan, 1, text, bounds);
        }
    }
}
