package io.docweave.partition;

import java.util.List;

/**
 * Page-confined validated grid and rectangular cell ownership. Arrays are internal and never exposed as metadata.
 * @param xs ascending column boundaries
 * @param ys ascending row boundaries
 * @param cells row-major anchor cells
 * @param owners atomic-slot to anchor-cell index mapping
 */
record PdfTableGrid(List<Float> xs, List<Float> ys, List<Cell> cells, int[] owners) {
    /**
     * One rectangular source-backed cell.
     * @param row zero-based anchor row
     * @param column zero-based anchor column
     * @param rowSpan occupied rows
     * @param columnSpan occupied columns
     */
    record Cell(int row, int column, int rowSpan, int columnSpan) {}
}
