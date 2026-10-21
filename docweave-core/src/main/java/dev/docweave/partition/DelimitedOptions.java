package dev.docweave.partition;

/**
 * Immutable CSV/TSV table policies. Delimiters are selected by DocumentFormat; double quotes delimit
 * fields and doubled quotes escape a quote. Values stay strings, including leading zeros and whitespace.
 * Empty physical lines are ignored; quoted newlines and empty/trailing cells are retained.
 * Limits include the header row and are checked as each parsed record is consumed, before table emission.
 * @param header whether the first record becomes table headers, default FIRST_ROW
 * @param strictColumns whether all records must match the first record's width, default true
 * @param tableLimits immutable row, column, total-cell and field budgets
 */
public record DelimitedOptions(Header header, boolean strictColumns, TableLimits tableLimits) {
    /** Explicit header policy; names are never inferred from data types. */
    public enum Header {
        /** First record is rendered with header cells, including blank/duplicate labels. */ FIRST_ROW,
        /** All records are data cells; no synthetic header is inserted. */ NONE
    }

    /**
     * Validates positive budgets.
     * @param header nonnull explicit header mode
     * @param strictColumns requires a consistent record width
     * @param tableLimits nonnull structured-table budgets
     * @throws NullPointerException if a required policy is null
     */
    public DelimitedOptions {
        java.util.Objects.requireNonNull(header, "header");
        java.util.Objects.requireNonNull(tableLimits, "tableLimits");
    }

    /**
     * Creates bounded table defaults.
     * @return first-row headers, strict widths and bounded record/cell budgets
     */
    public static DelimitedOptions defaults() {
        return new DelimitedOptions(Header.FIRST_ROW, true, TableLimits.defaults());
    }
}
