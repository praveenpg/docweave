package io.docweave.partition;

import java.util.List;

/**
 * Page-confined borderless candidate with explicit source-run ownership and deterministic evidence.
 * @param rows row-major source-run indices, one anchor run per cell
 * @param continuations additional source-run indices keyed by anchor, in reading order
 * @param numericColumns zero-based columns with numeric values in every body row
 */
record PdfAlignedTable(
        List<List<Integer>> rows, List<Integer> numericColumns, java.util.Map<Integer, Integer> continuations) {
    /**
     * Creates the historical one-run-per-cell candidate.
     * @param rows immutable source rows
     * @param numericColumns immutable numeric column evidence
     */
    PdfAlignedTable(final List<List<Integer>> rows, final List<Integer> numericColumns) {
        this(rows, numericColumns, java.util.Map.of());
    }
}
