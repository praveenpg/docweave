package io.docweave.chunking;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/** Immutable sparse cell ownership; parsing limits also bound materialized span geometry. */
final class HtmlTableModel {
    /** Maximum allowed Unicode code points in the input HTML table string. */
    static final int MAX_HTML_CHARACTERS = 16 * 1024 * 1024;
    /** Maximum allowed source rows in a parsed HTML table. */
    static final int MAX_ROWS = 100_000;
    /** Maximum allowed source cells and materialized logical grid positions. */
    static final int MAX_CELLS = 1_000_000;
    /** Maximum allowed logical columns in a parsed HTML table. */
    static final int MAX_COLUMNS = 10_000;

    /**
     * Source table cell with normalized text and resolved logical span geometry.
     *
     * @param index 0-based source cell ordinal in document order
     * @param row 0-based starting logical row index
     * @param column 0-based starting logical column index
     * @param columns positive horizontal column span
     * @param rows positive vertical row span
     * @param text normalized cell text
     */
    record Cell(int index, int row, int column, int columns, int rows, String text) {}

    /**
     * Logical table row containing visible source cells and header classification.
     *
     * @param index 0-based logical row index
     * @param cells ordered cells occupying or spanning across this row
     * @param header {@code true} if this row is classified as a table header row
     * @param width logical column width of this row
     */
    record Row(int index, List<Cell> cells, boolean header, int width) {}

    /** Ordered logical rows of the table. */
    private final List<Row> rows;
    /** Number of contiguous leading header rows at the top of the table. */
    private final int headers;
    /** Maximum logical column width across all rows. */
    private final int width;

    /**
     * Creates an immutable HTML table model from resolved rows and dimensions.
     *
     * @param rows ordered logical table rows
     * @param headers count of contiguous leading header rows
     * @param width maximum logical column width
     */
    private HtmlTableModel(final List<Row> rows, int headers, int width) {
        this.rows = List.copyOf(rows);
        this.headers = headers;
        this.width = width;
    }

    /**
     * Returns the ordered logical rows of this table.
     *
     * @return immutable list of table rows
     */
    List<Row> rows() {
        return rows;
    }

    /**
     * Returns the number of contiguous leading header rows.
     *
     * @return leading header row count
     */
    int headerRows() {
        return headers;
    }

    /**
     * Returns the maximum logical column width across all rows.
     *
     * @return logical table width in columns
     */
    int width() {
        return width;
    }

    /**
     * Returns a copy of this model with the leading header row count overridden by {@code count}.
     *
     * @param count non-negative number of leading rows to mark as headers
     * @return updated {@link HtmlTableModel}
     */
    HtmlTableModel withLeadingHeaders(final int count) {
        if (count < 0 || count > rows.size()) {
            throw invalid("Invalid table_leading_header_rows");
        }
        final var corrected =
                rows.stream()
                        .map(r -> new Row(r.index(), r.cells(), r.index() < count || r.header(), r.width()))
                        .toList();
        return new HtmlTableModel(corrected, count, width);
    }

    /**
     * Parses an HTML table fragment into a validated logical table model.
     *
     * @param html HTML string containing a single outer {@code <table>}
     * @return parsed {@link HtmlTableModel}
     */
    static HtmlTableModel parse(final String html) {
        if (html.length() > 2L * MAX_HTML_CHARACTERS || UnicodeText.length(html) > MAX_HTML_CHARACTERS) {
            throw invalid("Table HTML exceeds the 16 Mi code-point input budget");
        }
        ChunkCancellation.check();
        final var document = Jsoup.parseBodyFragment(html);
        // Verify that the parsed table DOM subtree does not exceed 256 nesting levels.
        record Depth(Node node, int depth) {}
        final var pending = new ArrayDeque<Depth>();
        pending.push(new Depth(document.body(), 0));
        while (!pending.isEmpty()) {
            ChunkCancellation.check();
            final var next = pending.pop();
            if (next.depth() > 256) {
                throw invalid("Table HTML nesting exceeds 256");
            }
            for (final Node child : next.node().childNodes()) {
                pending.push(new Depth(child, next.depth() + 1));
            }
        }
        final List<Element> tables =
                document.select("table")
                        .stream()
                        .filter(t -> t.parents().stream().noneMatch(p -> p.normalName().equals("table")))
                        .toList();
        if (tables.size() != 1) {
            throw invalid("text_as_html must contain exactly one outer table");
        }
        final var table = tables.getFirst();
        if (table.getElementsByTag("table").size() > 1) {
            throw invalid("Nested HTML tables must be partitioned or flattened before structural chunking");
        }
        if (table.children().stream().anyMatch(c -> c.normalName().equals("caption") && !c.text().isBlank())) {
            throw invalid("Table captions must be separate elements before structural chunking");
        }
        // Collect direct <tr> rows and rows nested inside <thead>, <tbody>, or <tfoot> in document order.
        final var sourceRows = new ArrayList<Element>();
        for (final var child : table.children()) {
            ChunkCancellation.check();
            if (child.normalName().equals("tr")) {
                sourceRows.add(child);
            } else if (List.of("thead", "tbody", "tfoot").contains(child.normalName())) {
                for (final var row : child.children()) {
                    if (row.normalName().equals("tr")) {
                        sourceRows.add(row);
                    }
                }
            }
        }
        if (sourceRows.size() > MAX_ROWS) {
            throw invalid("Table exceeds row budget");
        }
        // Record the last row index of each parent section so HTML5 rowspan="0" spans to the end of its section.
        final var groupLast = new IdentityHashMap<Element, Integer>();
        for (int i = 0; i < sourceRows.size(); i++) {
            groupLast.put(sourceRows.get(i).parent(), i);
        }
        final Cell[] active = new Cell[MAX_COLUMNS];
        final var rows = new ArrayList<Row>();
        int cells = 0, width = 0, headers = 0, activeWidth = 0;
        long logicalPositions = 0;
        // Resolve logical cell coordinates and active rowspans row by row while enforcing cell, column, and
        // grid-position budgets.
        for (int r = 0; r < sourceRows.size(); r++) {
            ChunkCancellation.check();
            final var row = sourceRows.get(r);
            int remainingWidth = 0;
            // Expire active vertical spans that ended at or before the current row index.
            for (int c = 0; c < activeWidth; c++) {
                ChunkCancellation.check();
                if (active[c] != null && active[c].row() + active[c].rows() <= r) {
                    active[c] = null;
                }
                if (active[c] != null) {
                    remainingWidth = c + 1;
                }
            }
            activeWidth = remainingWidth;
            int column = 0;
            boolean hasHeader = row.parent().normalName().equals("thead");
            boolean allHeaderOrBlank = true;
            for (final var cell : row.children()) {
                ChunkCancellation.check();
                if (!cell.normalName().equals("td") && !cell.normalName().equals("th")) {
                    continue;
                }
                if (++cells > MAX_CELLS) {
                    throw invalid("Table exceeds cell budget");
                }
                while (column < MAX_COLUMNS && active[column] != null
                        && active[column].row() + active[column].rows() > r) {
                    column++;
                }
                int colspan = span(cell, "colspan");
                if (colspan < 1) {
                    colspan = 1;
                }
                if (column + (long) colspan > MAX_COLUMNS) {
                    throw invalid("Table exceeds logical-column budget");
                }
                int rowspan = span(cell, "rowspan");
                rowspan = rowspan == 0 ? groupLast.get(row.parent()) - r + 1
                                       : Math.min(Math.max(1, rowspan), sourceRows.size() - r);
                final String text = UnicodeText.normalize(cell.text());
                final boolean th = cell.normalName().equals("th");
                hasHeader |= th;
                allHeaderOrBlank &= th || text.isEmpty();
                final var value = new Cell(cells - 1, r, column, colspan, rowspan, text);
                for (int c = column; c < column + colspan; c++) {
                    ChunkCancellation.check();
                    if (active[c] != null && active[c].row() + active[c].rows() > r) {
                        throw invalid("Table cells overlap an active rowspan");
                    }
                    active[c] = value;
                }
                column += colspan;
                activeWidth = Math.max(activeWidth, column);
            }
            // Collect unique cells covering this row in column order by advancing across each cell's colspan.
            final var visible = new ArrayList<Cell>();
            int rowWidth = column;
            for (int c = 0; c < activeWidth;) {
                ChunkCancellation.check();
                final Cell cell = active[c];
                if (cell == null || cell.row() + cell.rows() <= r) {
                    c++;
                    continue;
                }
                visible.add(cell);
                rowWidth = Math.max(rowWidth, cell.column() + cell.columns());
                c += cell.columns();
            }
            logicalPositions += rowWidth;
            if (logicalPositions > MAX_CELLS) {
                throw invalid("Table exceeds materialized logical-position budget");
            }
            final boolean header = row.parent().normalName().equals("thead") || hasHeader && allHeaderOrBlank;
            if (r == headers && header) {
                headers++;
            }
            rows.add(new Row(r, List.copyOf(visible), header, rowWidth));
            width = Math.max(width, rowWidth);
        }
        return new HtmlTableModel(rows, headers, width);
    }

    /**
     * Parses a non-negative integer span attribute from {@code cell}, defaulting to {@code 1} when absent.
     *
     * @param cell HTML table cell element
     * @param name span attribute name ({@code colspan} or {@code rowspan})
     * @return non-negative parsed span value
     */
    private static int span(final Element cell, final String name) {
        if (!cell.hasAttr(name)) {
            return 1;
        }
        try {
            return Math.max(0, Integer.parseInt(cell.attr(name).strip()));
        } catch (final NumberFormatException error) {
            throw invalid("Invalid table span");
        }
    }

    /**
     * Creates an {@link IllegalArgumentException} for invalid table markup or exceeded structural budgets.
     *
     * @param message diagnostic error message
     * @return illegal argument exception
     */
    private static IllegalArgumentException invalid(final String message) {
        return new IllegalArgumentException(message);
    }
}
