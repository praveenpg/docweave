package io.docweave.partition;

/**
 * Immutable structured-table budgets shared by Markdown and delimited-file strategies.
 * Rows include headers. Limits are enforced while consuming parsed rows, before Table emission;
 * they do not impose a heap limit on third-party parsing or an execution deadline.
 * @param maxRows positive record/row limit, default 100,000
 * @param maxColumns positive per-row cell limit, default 1,000
 * @param maxCells positive total cell limit, default 1,000,000
 * @param maxCellCharacters positive Unicode code-point limit per cell, default 1,000,000
 */
public record TableLimits(int maxRows, int maxColumns, int maxCells, int maxCellCharacters) {
    /**
     * Validates positive structured-table budgets.
     * @param maxRows positive row limit
     * @param maxColumns positive per-row cell limit
     * @param maxCells positive total cell limit
     * @param maxCellCharacters positive per-cell code-point limit
     * @throws IllegalArgumentException if a budget is not positive
     */
    public TableLimits {
        if (maxRows < 1 || maxColumns < 1 || maxCells < 1 || maxCellCharacters < 1) {
            throw new IllegalArgumentException("Table budgets must be positive");
        }
    }

    /**
     * Creates bounded defaults for one structured table.
     * @return positive row, column, total-cell and field budgets
     */
    public static TableLimits defaults() {
        return new TableLimits(100_000, 1_000, 1_000_000, 1_000_000);
    }
}
