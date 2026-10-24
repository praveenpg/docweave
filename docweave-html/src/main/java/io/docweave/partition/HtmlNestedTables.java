package io.docweave.partition;

import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Document-local HTML preservation: source ownership, bounded snapshots and explicit coordinate spaces. */
final class HtmlNestedTables {
    /** Shared per-document nested-table metadata budget tracker. */
    private final NestedTableBudget budget;
    /** Table dimension and cell limits enforced on nested tables. */
    private final TableLimits limits;

    /**
     * Logical cell placement within a normalized table grid.
     *
     * @param node HTML {@code <td>} or {@code <th>} element
     * @param row 0-based logical row index
     * @param column 0-based logical starting column index
     */
    private record Cell(Element node, int row, int column) {}

    /**
     * Normalized table grid mapping rows and DOM cell elements to logical coordinates.
     *
     * @param rows ordered rows of logical cells
     * @param cells identity map from cell DOM element to its logical {@link Cell} placement
     */
    private record Grid(List<List<Cell>> rows, Map<Element, Cell> cells) {}

    /**
     * Bounded snapshot of a nested table's flattened text, canonical HTML, links, and captions.
     *
     * @param text tab- and newline-delimited cell text
     * @param html compact canonical HTML table markup
     * @param links extracted hyperlink descriptors with child-local code-point offsets
     * @param captions non-empty direct {@code <caption>} texts
     */
    private record Snapshot(String text, String html, List<Map<String, Object>> links, List<String> captions) {}

    /**
     * Creates a nested-table metadata capturer with the given preservation options and table limits.
     *
     * @param options nested-table preservation options and budgets
     * @param limits table structural limits
     */
    HtmlNestedTables(final NestedTableOptions options, final TableLimits limits) {
        this.budget = new NestedTableBudget(options);
        this.limits = limits;
    }

    /**
     * Captures structured metadata descriptors for all descendant tables inside {@code root}.
     *
     * @param root outer HTML {@code <table>} element
     * @param rootText flattened plain text emitted for {@code root}
     * @return metadata map containing {@code nested_tables} and {@code nested_table_count}, or an empty map
     * @throws IOException if nested-table budgets are exceeded, structure is malformed, or the thread is interrupted
     */
    Map<String, Object> capture(final Element root, final String rootText) throws IOException {
        final var children = children(root);
        if (children.isEmpty()) {
            return Map.of();
        }
        final Set<Element> tracked = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        tracked.addAll(children);
        final var grids = new IdentityHashMap<Element, Grid>();
        final var rootGrid = grid(root);
        grids.put(root, rootGrid);
        final var ranges = ranges(rootGrid, tracked);
        final var indices = new IdentityHashMap<Element, Integer>();
        final var paths = new IdentityHashMap<Element, List<Map<String, Object>>>();
        paths.put(root, List.of());
        final var siblings = new IdentityHashMap<Element, Integer>();
        final var output = new NestedTableDescriptors(budget, rootText.codePointCount(0, rootText.length()));
        // Assemble nested-table descriptors in document preorder, resolving each child's parent cell coordinates and
        // code-point interval.
        for (final var child : children) {
            ElementCollector.checkInterrupted();
            final var parent = closestTable(child);
            final var owner = ownerCell(child, parent);
            var parentGrid = grids.get(parent);
            if (parentGrid == null) {
                parentGrid = grid(parent);
                grids.put(parent, parentGrid);
            }
            final var position = parentGrid.cells().get(owner);
            var interval = ranges.get(child);
            if (position == null || interval == null || !paths.containsKey(parent)) {
                throw malformed("Nested table has no supported containing cell");
            }
            // An empty child before its parent's first visible text belongs at that text boundary,
            // after the normalization separator that the parent itself excludes.
            if (parent != root && interval.start() == interval.end()) {
                final int parentStart = ranges.get(parent).start();
                if (interval.start() < parentStart) {
                    interval = new HtmlInlineText.Range(parentStart, parentStart);
                    ranges.put(child, interval);
                }
            }
            final int ordinal = siblings.getOrDefault(owner, 0);
            siblings.put(owner, ordinal + 1);
            final var path = new ArrayList<>(paths.get(parent));
            path.add(Map.of("row", position.row(), "column", position.column(), "table_index", ordinal));
            final var snapshot = snapshot(child);
            output.add(parent == root ? null : indices.get(parent), path, interval.start(), interval.end(),
                    snapshot.text(), snapshot.html(), snapshot.links(), snapshot.captions());
            indices.put(child, indices.size());
            paths.put(child, List.copyOf(path));
        }
        return output.metadata();
    }

    /**
     * Preflight counts/depth before allocating range capture maps, without retaining an unbounded traversal stack.
     *
     * @param root outer HTML {@code <table>} element
     * @return descendant {@code <table>} elements in document order
     * @throws IOException if nested-table count or depth budgets are exceeded, ownership is invalid, or interrupted
     */
    private List<Element> children(final Element root) throws IOException {
        final var result = new ArrayList<Element>();
        Node node = root.firstChild();
        // Traverse the DOM subtree iteratively using parent/sibling pointers to avoid an O(depth) stack allocation.
        while (node != null) {
            ElementCollector.checkInterrupted();
            if (node instanceof final Element element && element.normalName().equals("table")) {
                int depth = 0;
                for (Element parent = element; parent != root; parent = closestTable(parent)) {
                    if (parent == null) {
                        throw malformed("Invalid nested table ownership");
                    }
                    depth++;
                }
                budget.checkCount(result.size() + 1, depth);
                result.add(element);
            }
            if (node.firstChild() != null) {
                node = node.firstChild();
            } else {
                while (node != root && node.nextSibling() == null) {
                    node = node.parent();
                }
                node = node == root ? null : node.nextSibling();
            }
        }
        return result;
    }

    /**
     * Mirrors root text composition; ranges are captured during normalization, never found by substring search.
     *
     * @param grid logical cell grid of the root table
     * @param tracked descendant table elements whose code-point intervals should be recorded
     * @return identity map from tracked table element to its code-point {@link HtmlInlineText.Range} in the root text
     * @throws IOException if rendering fails or the thread is interrupted
     */
    private Map<Element, HtmlInlineText.Range> ranges(final Grid grid, final Set<Element> tracked) throws IOException {
        final var result = new IdentityHashMap<Element, HtmlInlineText.Range>();
        int points = 0;
        // Replay root table cell rendering with tab and newline offsets so each tracked descendant receives exact
        // root-relative offsets.
        for (final var row : grid.rows()) {
            if (row.isEmpty()) {
                continue;
            }
            if (points > 0) {
                points++;
            }
            boolean previousCell = false;
            for (final var cell : row) {
                ElementCollector.checkInterrupted();
                if (previousCell) {
                    points++;
                }
                final var rendered = render(cell.node(), tracked, Long.MAX_VALUE);
                for (final var entry : rendered.ranges().entrySet()) {
                    result.put(entry.getKey(), new HtmlInlineText.Range(points + entry.getValue().start(),
                                                       points + entry.getValue().end()));
                }
                final String value = rendered.text();
                points += value.codePointCount(0, value.length());
                previousCell = true;
            }
        }
        return result;
    }

    /**
     * Logical cell origins account for row/column spans without expanding the table grid.
     *
     * @param table HTML {@code <table>} element to index
     * @return normalized {@link Grid} with logical cell coordinates
     * @throws IOException if table limits are exceeded, spans overlap, or the thread is interrupted
     */
    private Grid grid(final Element table) throws IOException {
        final var rows = new ArrayList<List<Cell>>();
        final var cells = new IdentityHashMap<Element, Cell>();
        final var occupied = new java.util.HashMap<Integer, Integer>();
        // Assign logical (row, column) coordinates to each direct cell while tracking active rowspans per column.
        for (final var row : table.getElementsByTag("tr")) {
            ElementCollector.checkInterrupted();
            if (closestTable(row) != table) {
                continue;
            }
            if (rows.size() >= limits.maxRows()) {
                throw limit();
            }
            final int r = rows.size();
            int column = 0;
            final var values = new ArrayList<Cell>();
            for (final var node : row.children()) {
                if (!node.normalName().equals("td") && !node.normalName().equals("th")) {
                    continue;
                }
                while (occupied.getOrDefault(column, 0) > r) {
                    column++;
                }
                final int columns = span(node, "colspan"), height = span(node, "rowspan");
                if ((long) column + columns > limits.maxColumns() || (long) r + height > limits.maxRows()) {
                    throw limit();
                }
                for (int c = column; c < column + columns; c++) {
                    if (occupied.getOrDefault(c, 0) > r) {
                        throw malformed("Overlapping nested-table cell spans");
                    }
                    occupied.put(c, r + height);
                }
                final var cell = new Cell(node, r, column);
                values.add(cell);
                cells.put(node, cell);
                column += columns;
                if (cells.size() > limits.maxCells()) {
                    throw limit();
                }
            }
            rows.add(List.copyOf(values));
        }
        return new Grid(List.copyOf(rows), cells);
    }

    /**
     * Builds child-local flat text/HTML with each output string bounded before appending.
     *
     * @param table nested HTML {@code <table>} element to snapshot
     * @return bounded {@link Snapshot} of the nested table
     * @throws IOException if the metadata budget is exceeded or the thread is interrupted
     */
    private Snapshot snapshot(final Element table) throws IOException {
        final var grid = grid(table);
        final long remaining = budget.remainingCodePoints();
        final var html = new BoundedHtml(remaining);
        final var text = new StringBuilder();
        final var links = new ArrayList<Map<String, Object>>();
        int points = 0;
        long linkPayload = 0;
        html.append("<table>");
        // Render each row and cell into both flat tab/newline text and compact canonical HTML within the remaining
        // budget.
        for (final var row : grid.rows()) {
            if (row.isEmpty()) {
                continue;
            }
            html.append("<tr>");
            boolean previousCell = false;
            if (!text.isEmpty()) {
                text.append('\n');
                points++;
            }
            for (final var cell : row) {
                ElementCollector.checkInterrupted();
                if (previousCell) {
                    text.append('\t');
                    points++;
                }
                final var rendered = render(cell.node(), Set.of(), remaining - points);
                final String value = rendered.text();
                final int count = value.codePointCount(0, value.length());
                if ((long) points + count > remaining) {
                    throw limit();
                }
                for (final var link : rendered.links()) {
                    final String label = (String) link.get("text"), url = (String) link.get("url");
                    final long amount =
                            (long) label.codePointCount(0, label.length()) + url.codePointCount(0, url.length());
                    if (amount > remaining - linkPayload) {
                        throw limit();
                    }
                    linkPayload += amount;
                    final var adjusted = new LinkedHashMap<>(link);
                    adjusted.put("start_index", points + (Integer) link.get("start_index"));
                    links.add(adjusted);
                }
                text.append(value);
                points += count;
                final String tag = cell.node().normalName();
                html.append("<" + tag);
                for (final String name : List.of("colspan", "rowspan")) {
                    if (positiveSpan(cell.node(), name)) {
                        html.append(" " + name + "=\"");
                        html.append(cell.node().attr(name));
                        html.append("\"");
                    }
                }
                html.append(">");
                html.text(value);
                html.append("</" + tag + ">");
                previousCell = true;
            }
            html.append("</tr>");
        }
        html.append("</table>");
        // Extract direct <caption> children of the nested table while accounting for their code-point budget.
        final var captions = new ArrayList<String>();
        long captionPoints = 0;
        for (final var child : table.children()) {
            if (child.normalName().equals("caption")) {
                final String value = render(child, Set.of(), remaining - captionPoints).text();
                captionPoints += value.codePointCount(0, value.length());
                if (!value.isEmpty()) {
                    captions.add(value);
                }
            }
        }
        return new Snapshot(text.toString(), html.value(), List.copyOf(links), List.copyOf(captions));
    }

    /**
     * Renders the child nodes of {@code cell} into normalized inline text with optional range tracking.
     *
     * @param cell table cell or caption element to render
     * @param tracked descendant elements whose code-point intervals should be captured
     * @param maxPoints maximum allowed code points
     * @return rendered {@link HtmlInlineText}
     * @throws IOException if the code-point budget is exceeded or the thread is interrupted
     */
    private HtmlInlineText render(final Element cell, final Set<Element> tracked, final long maxPoints)
            throws IOException {
        final var value = new HtmlInlineText(tracked, maxPoints);
        for (final var child : cell.childNodes()) {
            value.append(child);
        }
        return value;
    }

    /**
     * Finds the nearest ancestor {@code <table>} element enclosing {@code node}.
     *
     * @param node DOM element to inspect
     * @return nearest enclosing {@code <table>} element, or {@code null} if none exists
     */
    private static Element closestTable(final Element node) {
        for (Element parent = node.parent(); parent != null; parent = parent.parent()) {
            if (parent.normalName().equals("table")) {
                return parent;
            }
        }
        return null;
    }

    /**
     * Finds the containing {@code <td>} or {@code <th>} cell within {@code parentTable} that owns {@code child}.
     *
     * @param child nested {@code <table>} element
     * @param parentTable immediate parent {@code <table>} element
     * @return owning {@code <td>} or {@code <th>} cell element
     * @throws IOException if {@code child} is not enclosed by a cell inside {@code parentTable}
     */
    private Element ownerCell(final Element child, final Element parentTable) throws IOException {
        for (Element parent = child.parent(); parent != null && parent != parentTable; parent = parent.parent()) {
            if (parent.normalName().equals("td") || parent.normalName().equals("th")) {
                return parent;
            }
        }
        throw malformed("Nested table has no containing cell");
    }

    /**
     * Parses a positive integer span attribute ({@code colspan} or {@code rowspan}), defaulting to {@code 1}.
     *
     * @param cell table cell element
     * @param name span attribute name
     * @return parsed positive span value, or {@code 1} if absent or invalid
     */
    private int span(final Element cell, final String name) {
        final String attribute = cell.attr(name);
        if (attribute.isEmpty()) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(attribute));
        } catch (final NumberFormatException ignored) {
            return 1;
        }
    }

    /**
     * Checks whether {@code cell} declares a strictly positive integer value for the attribute {@code name}.
     *
     * @param cell table cell element
     * @param name span attribute name
     * @return {@code true} if the attribute parses as an integer greater than zero
     */
    private boolean positiveSpan(final Element cell, final String name) {
        final String attribute = cell.attr(name);
        if (attribute.isEmpty()) {
            return false;
        }
        try {
            return Integer.parseInt(attribute) > 0;
        } catch (final NumberFormatException ignored) {
            return false;
        }
    }

    /**
     * Creates a {@link PartitionException} indicating that a nested-table preservation budget was exceeded.
     *
     * @return table-limit partition exception
     */
    private static PartitionException limit() {
        return new PartitionException(PartitionException.Code.TABLE_LIMIT, "Nested-table preservation budget exceeded");
    }

    /**
     * Creates a {@link PartitionException} indicating malformed nested-table structure.
     *
     * @param message diagnostic detail message
     * @return malformed-document partition exception
     */
    private static PartitionException malformed(final String message) {
        return new PartitionException(PartitionException.Code.MALFORMED_DOCUMENT, message);
    }

    /** Child HTML serializer caps escaped output before copying a whole string. */
    private static final class BoundedHtml {
        /** Maximum allowed Unicode code points in the serialized HTML string. */
        private final long max;
        /** Underlying character buffer accumulating serialized HTML. */
        private final StringBuilder value = new StringBuilder();
        /** Current number of Unicode code points appended to {@link #value}. */
        private long points;

        /**
         * Creates a bounded HTML buffer with the specified maximum code-point capacity.
         *
         * @param max maximum allowed Unicode code points
         */
        BoundedHtml(final long max) {
            this.max = max;
        }

        /**
         * Appends literal markup text while enforcing the code-point budget.
         *
         * @param text literal markup string to append
         * @throws IOException if appending {@code text} would exceed {@link #max}
         */
        void append(final String text) throws IOException {
            final int count = text.codePointCount(0, text.length());
            if (count > max - points) {
                throw limit();
            }
            value.append(text);
            points += count;
        }

        /**
         * Escapes and appends character data while enforcing the code-point budget and checking for interruption.
         *
         * @param text plain cell text to HTML-escape and append
         * @throws IOException if the escaped output exceeds {@link #max} or the thread is interrupted
         */
        void text(final String text) throws IOException {
            for (int offset = 0; offset < text.length();) {
                if ((offset & 1023) == 0) {
                    ElementCollector.checkInterrupted();
                }
                final int begin = offset;
                final int point = text.codePointAt(offset);
                offset += Character.charCount(point);
                switch (point) {
                case '&' -> append("&amp;");
                case '<' -> append("&lt;");
                case '>' -> append("&gt;");
                case '"' -> append("&quot;");
                case '\'' -> append("&apos;");
                case 0xa0 -> append("&nbsp;");
                default -> {
                    if (point < 32 && point != 9 && point != 10 && point != 13 || point >= 0xd800 && point <= 0xdfff) {
                        append("&#x" + Integer.toHexString(point) + ";");
                    } else {
                        if (points >= max) {
                            throw limit();
                        }
                        value.append(text, begin, offset);
                        points++;
                    }
                }
                }
            }
        }

        /**
         * Returns the accumulated HTML string.
         *
         * @return serialized HTML string
         */
        String value() {
            return value.toString();
        }
    }
}
