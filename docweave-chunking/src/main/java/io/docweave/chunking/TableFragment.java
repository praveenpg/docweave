package io.docweave.chunking;

import org.jsoup.nodes.Entities;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Synchronized normalized text, compact HTML, and source-coordinate ownership for one fragment. */
final class TableFragment {
    /**
     * Fragment cell projection referencing its source cell and active code-point slice.
     *
     * @param source underlying source cell in the table model
     * @param row 0-based source row index where this cell fragment appears
     * @param rows effective vertical row span within the current table fragment
     * @param text cell text slice emitted in this fragment
     * @param start inclusive start code-point offset within {@code source.text()}
     * @param end exclusive end code-point offset within {@code source.text()}
     * @param spanContinuation {@code true} if this cell is a blank vertical span continuation from an earlier fragment
     */
    record Cell(
            HtmlTableModel.Cell source, int row, int rows, String text, int start, int end, boolean spanContinuation) {
        /**
         * Creates a cell projection covering either the full source cell text or an empty span continuation.
         *
         * @param source underlying source cell
         * @param row 0-based source row index
         * @param rows clipped vertical row span within the fragment
         * @param continuation {@code true} if the source cell started prior to this fragment
         * @return whole or continuation {@link Cell} projection
         */
        static Cell whole(final HtmlTableModel.Cell source, final int row, final int rows, final boolean continuation) {
            return new Cell(source, row, rows, continuation ? "" : source.text(), 0,
                    continuation ? 0 : UnicodeText.length(source.text()), continuation);
        }

        /**
         * Creates a single-row cell projection covering the half-open code-point interval {@code [from, to)}.
         *
         * @param from inclusive start code-point offset in the source cell text
         * @param to exclusive end code-point offset in the source cell text
         * @return sliced {@link Cell} projection
         */
        Cell slice(final int from, final int to) {
            return new Cell(source, row, 1, UnicodeText.slice(source.text(), from, to), from, to, spanContinuation);
        }
    }

    /**
     * Fragment row containing projected cells and header status.
     *
     * @param sourceIndex 0-based row index in the source table model
     * @param cells projected cells in this fragment row
     * @param header {@code true} if this row is a header row
     */
    record Row(int sourceIndex, List<Cell> cells, boolean header) {}

    /** Ordered rows in this fragment, including any prepended carried header rows. */
    final List<Row> rows;
    /** Serialized HTML markup inside the outer {@code <table>...</table>} tags. */
    final String innerHtml;
    /** Space-joined normalized cell text across all non-empty cells in this fragment. */
    final String text;
    /** Total Unicode code-point length of {@link #html()}. */
    final int htmlLength;
    /** Total Unicode code-point length of {@link #text}. */
    final int textLength;
    /** Number of prepended carried-over header rows at the start of {@link #rows}. */
    final int carriedHeaders;

    /**
     * Creates a table fragment without carried-over header rows.
     *
     * @param rows ordered fragment rows
     */
    TableFragment(final List<Row> rows) {
        this(rows, 0);
    }

    /**
     * Creates a table fragment with the specified rows and carried-over header row count.
     *
     * @param rows ordered fragment rows (including {@code carriedHeaders} leading rows)
     * @param carriedHeaders number of leading rows that are repeated headers
     */
    TableFragment(final List<Row> rows, final int carriedHeaders) {
        this.rows = List.copyOf(rows);
        this.carriedHeaders = carriedHeaders;
        final var html = new StringBuilder();
        final var texts = new ArrayList<String>();
        // Serialize carried header rows inside <thead> and body rows as <tr>/<td> with clipped spans and
        // Python-compatible HTML escaping.
        for (int i = 0; i < rows.size(); i++) {
            ChunkCancellation.check();
            if (i == 0 && carriedHeaders > 0) {
                html.append("<thead>");
            }
            if (i == carriedHeaders && carriedHeaders > 0) {
                html.append("</thead>");
            }
            html.append("<tr>");
            for (final var cell : rows.get(i).cells()) {
                ChunkCancellation.check();
                final String tag = i < carriedHeaders ? "th" : "td";
                html.append('<').append(tag);
                if (cell.source().columns() > 1) {
                    html.append(" colspan=\"").append(cell.source().columns()).append('"');
                }
                if (cell.rows() > 1) {
                    html.append(" rowspan=\"").append(cell.rows()).append('"');
                }
                if (cell.text().isEmpty()) {
                    html.append("/>");
                } else {
                    html.append('>').append(escape(cell.text())).append("</").append(tag).append('>');
                }
                if (!cell.text().isEmpty()) {
                    texts.add(cell.text());
                }
            }
            html.append("</tr>");
        }
        if (carriedHeaders == rows.size() && carriedHeaders > 0) {
            html.append("</thead>");
        }
        innerHtml = html.toString();
        text = String.join(" ", texts);
        htmlLength = 15 + UnicodeText.length(innerHtml);
        textLength = UnicodeText.length(text);
    }

    /**
     * Escapes cell text using Python {@code html.escape}-compatible entity spellings.
     *
     * @param value plain cell text
     * @return HTML-escaped string
     */
    static String escape(final String value) {
        // Match Python html.escape spellings; escaped expansion participates in the hard budget.
        return Entities.escape(value).replace("\"", "&quot;").replace("'", "&#x27;");
    }

    /**
     * Returns the complete compact HTML table string for this fragment.
     *
     * @return compact {@code <table>...</table>} markup
     */
    String html() {
        return "<table>" + innerHtml + "</table>";
    }

    /**
     * Checks whether both the HTML markup and plain text of this fragment fit within {@code limit}.
     *
     * @param limit maximum allowed Unicode code points
     * @return {@code true} if both {@link #htmlLength} and {@link #textLength} are at most {@code limit}
     */
    boolean fits(final int limit) {
        return htmlLength <= limit && textLength <= limit;
    }

    /**
     * Builds a table fragment covering the half-open row interval {@code [start, end)} of {@code model}.
     *
     * @param model source HTML table model
     * @param start inclusive start row index
     * @param end exclusive end row index
     * @return constructed {@link TableFragment}
     */
    static TableFragment range(final HtmlTableModel model, final int start, final int end) {
        final var rows = new ArrayList<Row>();
        // Project cells into [start, end), clipping vertical spans to end and emitting blank continuation cells when a
        // span started before start.
        for (int r = start; r < end; r++) {
            ChunkCancellation.check();
            final var cells = new ArrayList<Cell>();
            for (final var cell : model.rows().get(r).cells()) {
                ChunkCancellation.check();
                if (cell.row() != r && r != start) {
                    continue;
                }
                final int span = Math.min(cell.row() + cell.rows(), end) - r;
                cells.add(Cell.whole(cell, r, span, cell.row() < start));
            }
            rows.add(new Row(r, List.copyOf(cells), model.rows().get(r).header()));
        }
        return new TableFragment(rows);
    }

    /**
     * Builds the table-chunk extension metadata describing row/column bounds, carried headers, and cell fragments.
     *
     * @return mutable metadata map for this table fragment
     */
    Map<String, Object> metadata() {
        final var result = new LinkedHashMap<String, Object>();
        final var sourceRows = rows.subList(carriedHeaders, rows.size());
        result.put("table_row_start", sourceRows.isEmpty() ? 0 : sourceRows.getFirst().sourceIndex());
        result.put("table_row_end", sourceRows.isEmpty() ? 0 : sourceRows.getLast().sourceIndex() + 1);
        result.put("table_row_count", rows.size());
        result.put("table_header_rows", (int) rows.stream().filter(Row::header).count());
        int leading = 0;
        for (final var row : rows) {
            if (!row.header()) {
                break;
            }
            leading++;
        }
        result.put("table_leading_header_rows", leading);
        // Compute logical column bounds and per-cell ownership descriptors across non-carried rows.
        int minColumn = Integer.MAX_VALUE, maxColumn = 0;
        final var fragments = new ArrayList<Map<String, Object>>();
        for (int i = carriedHeaders; i < rows.size(); i++) {
            for (final var cell : rows.get(i).cells()) {
                ChunkCancellation.check();
                final var source = cell.source();
                minColumn = Math.min(minColumn, source.column());
                maxColumn = Math.max(maxColumn, source.column() + source.columns());
                final var fragment = new LinkedHashMap<String, Object>();
                fragment.put("cell_index", source.index());
                fragment.put("row", source.row());
                fragment.put("column", source.column());
                fragment.put("rowspan", source.rows());
                fragment.put("colspan", source.columns());
                fragment.put("text_start", cell.start());
                fragment.put("text_end", cell.end());
                if (cell.spanContinuation()) {
                    fragment.put("is_span_continuation", true);
                }
                fragments.add(fragment);
            }
        }
        result.put("table_column_start", minColumn == Integer.MAX_VALUE ? 0 : minColumn);
        result.put("table_column_end", maxColumn);
        result.put("table_column_count", maxColumn - (minColumn == Integer.MAX_VALUE ? 0 : minColumn));
        result.put("table_cell_fragments", List.copyOf(fragments));
        result.put("num_carried_over_header_rows", carriedHeaders);
        result.put("text_as_html", html());
        return result;
    }
}
