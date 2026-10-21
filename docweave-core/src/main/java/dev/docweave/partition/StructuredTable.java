package dev.docweave.partition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Document-confined row accumulator retaining empty cells, text/HTML structure and code-point link offsets. */
final class StructuredTable {
    /**
     * Normalized table cell value and its cell-local hyperlink descriptors.
     *
     * @param text normalized cell text
     * @param links ordered cell-local hyperlink maps
     */
    record Cell(String text, List<Map<String, Object>> links) {
        /**
         * Creates a plain-text table cell without hyperlinks.
         *
         * @param text normalized cell text
         */
        Cell(final String text) {
            this(text, List.of());
        }
    }

    /** Structural row, column, cell, and per-cell character budgets. */
    private final TableLimits limits;
    /** Accumulated tab- and newline-separated plain table text. */
    private final StringBuilder plain = new StringBuilder();
    /** Accumulated compact HTML table markup. */
    private final StringBuilder html = new StringBuilder("<table>");
    /** Accumulated table-wide hyperlink descriptors with shifted code-point offsets. */
    private final List<Map<String, Object>> links = new ArrayList<>();
    /** Number of rows appended so far. */
    private int rows;
    /** Maximum column count observed across appended rows. */
    private int columns;
    /** Total number of cells appended across all rows. */
    private int cells;
    /** Number of header rows appended so far. */
    private int headerRows;
    /** Total Unicode code points appended to {@link #plain}. */
    private int codePoints;

    /**
     * Creates a structured table builder enforcing the given {@code limits}.
     *
     * @param limits structural table limits
     */
    StructuredTable(final TableLimits limits) {
        this.limits = limits;
    }

    /**
     * Verifies that appending a row with {@code fields} cells stays within row, column, and total-cell budgets.
     *
     * @param fields number of cells in the candidate row
     * @throws PartitionException if row, column, or total-cell limits would be exceeded
     */
    void checkRow(final int fields) throws PartitionException {
        if (rows >= limits.maxRows() || fields > limits.maxColumns() || cells + (long) fields > limits.maxCells()) {
            throw new PartitionException(
                    PartitionException.Code.TABLE_LIMIT, "Table exceeds row, column or total-cell budget");
        }
    }

    /**
     * Appends a row of cells to both the plain-text and compact HTML representations.
     *
     * @param values ordered cells in the row
     * @param header {@code true} if the row should be emitted with {@code <th>} cells
     * @throws IOException if table limits are exceeded or the thread is interrupted
     */
    void row(final List<Cell> values, final boolean header) throws IOException {
        ElementCollector.checkInterrupted();
        checkRow(values.size());
        if (rows > 0) {
            plain.append('\n');
            codePoints++;
        }
        html.append("<tr>");
        for (int i = 0; i < values.size(); i++) {
            ElementCollector.checkInterrupted();
            final var cell = values.get(i);
            final int count = cell.text().codePointCount(0, cell.text().length());
            if (count > limits.maxCellCharacters()) {
                throw new PartitionException(
                        PartitionException.Code.TABLE_LIMIT, "Table field exceeds its code-point budget");
            }
            if (i > 0) {
                plain.append('\t');
                codePoints++;
            }
            for (final var link : cell.links()) {
                final var shifted = new LinkedHashMap<>(link);
                shifted.put("start_index", codePoints + (Integer) link.get("start_index"));
                links.add(shifted);
            }
            plain.append(cell.text());
            codePoints += count;
            final String tag = header ? "th" : "td";
            html.append('<')
                    .append(tag)
                    .append('>')
                    .append(HtmlTextEscaper.escape(cell.text()))
                    .append("</")
                    .append(tag)
                    .append('>');
        }
        html.append("</tr>");
        rows++;
        cells += values.size();
        columns = Math.max(columns, values.size());
        if (header) {
            headerRows++;
        }
    }

    /**
     * Returns the number of rows appended to this table.
     *
     * @return row count
     */
    int rowCount() {
        return rows;
    }

    /**
     * Returns the tab- and newline-delimited plain text representation of this table.
     *
     * @return plain table text
     */
    String text() {
        return plain.toString();
    }

    /**
     * Builds the element metadata map containing {@code text_as_html}, table dimensions, and link arrays.
     *
     * @return mutable metadata map for the emitted table element
     */
    Map<String, Object> metadata() {
        final var metadata = new LinkedHashMap<String, Object>();
        metadata.put("text_as_html", html + "</table>");
        metadata.put("table_row_count", rows);
        metadata.put("table_column_count", columns);
        metadata.put("table_header_rows", headerRows);
        if (!links.isEmpty()) {
            metadata.put("links", List.copyOf(links));
            metadata.put("link_texts", links.stream().map(link -> link.get("text")).toList());
            metadata.put("link_urls", links.stream().map(link -> link.get("url")).toList());
            metadata.put("link_start_indexes", links.stream().map(link -> link.get("start_index")).toList());
        }
        return metadata;
    }
}
