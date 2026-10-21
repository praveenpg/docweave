package dev.docweave.partition;

import org.apache.poi.xwpf.usermodel.*;
import org.w3c.dom.Node;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Stored logical-grid region matching, independent of semantic inheritance and text emission.
 * Computes shared horizontal/vertical merge footprints before extraction; gap columns are real grid
 * positions. Uses look 0000 when absent. Semantic bands require a size declared in the
 * effective table/style settings; an absent size disables that axis, matching the owned
 * Word horizontal-band observation. Explicit sizes and inherited settings remain effective.
 * Enabled edges reserve stripe positions only when a resolved paragraph branch exists
 * for that edge. This affects inferred stripes, not edge/corner membership or cached flags.
 * cnfStyle masks/attributes overlay geometric membership row, cell, then paragraph; tblLook still gates
 * families. Corners require both corresponding edge options. No visual/bidi layout is inferred.
 */
final class DocxTableRegions {
    /** Conditional formatting attribute names in 12-bit {@code cnfStyle} bitmask order. */
    private static final String[] ATTRS = {"firstRow", "lastRow", "firstColumn", "lastColumn", "oddVBand", "evenVBand",
            "oddHBand", "evenHBand", "firstRowFirstColumn", "firstRowLastColumn", "lastRowFirstColumn",
            "lastRowLastColumn"};

    /** Logical bounding box of a cell or merged cell region in the table grid. */
    private static final class Footprint {
        /** Inclusive 0-based bounding row and column coordinates of the cell footprint. */
        int top, bottom, left, right;
        /** Vertical merge state ({@code "none"}, {@code "restart"}, or {@code "continue"}). */
        String vertical;

        /**
         * Creates a cell footprint for the given starting position, horizontal span, and vertical merge mode.
         *
         * @param row 0-based row index
         * @param column 0-based starting column index
         * @param span horizontal column span
         * @param vertical vertical merge mode
         */
        Footprint(final int row, int column, final int span, final String vertical) {
            top = bottom = row;
            left = column;
            right = column + span - 1;
            this.vertical = vertical;
        }
    }

    /** Logical cell footprints indexed by physical row and physical cell ordinal. */
    private final List<List<Footprint>> positions = new ArrayList<>();
    /** Ordered table rows. */
    private final List<XWPFTableRow> rows;
    /** Ordered physical cells per row. */
    private final List<List<XWPFTableCell>> cells;
    /** Ordered {@code tblPr} settings nodes from the table and its style chain. */
    private final List<Node> settings;
    /** Region names that have resolved conditional paragraph style branches. */
    private final Set<String> paragraphRegions;
    /** Maximum logical grid width across all rows and declared grid columns. */
    private int width;

    /**
     * Computes logical merge footprints and validates grid dimensions for conditional region matching.
     *
     * @param rows ordered table rows
     * @param cells ordered physical cells per row
     * @param settings ordered {@code tblPr} settings nodes
     * @param paragraphRegions region names with resolved paragraph style branches
     * @param limits table resource budgets
     * @param declaredColumns number of columns declared in {@code tblGrid}
     * @throws IOException if the thread is interrupted
     */
    DocxTableRegions(final List<XWPFTableRow> rows, final List<List<XWPFTableCell>> cells, final List<Node> settings,
            final Set<String> paragraphRegions, final TableLimits limits, final int declaredColumns)
            throws IOException {
        this.rows = rows;
        this.cells = cells;
        this.settings = settings;
        this.width = declaredColumns;
        this.paragraphRegions = Set.copyOf(paragraphRegions);
        if (width > limits.maxColumns()) {
            throw limit("Table region declared-column budget exceeded");
        }
        if (rows.size() > limits.maxRows()) {
            throw limit("Table region row budget exceeded");
        }
        var previous = new HashMap<Integer, Footprint>();
        long total = 0;
        // Precompute the logical bounding footprint (top/bottom/left/right) of every cell across horizontal and
        // vertical merges.
        for (int r = 0; r < rows.size(); r++) {
            ElementCollector.checkInterrupted();
            final Node pr = DocxBodyView.child(rows.get(r).getCtRow().getDomNode(), "trPr");
            int column = integer(DocxBodyView.child(pr, "gridBefore"), 0);
            final int after = integer(DocxBodyView.child(pr, "gridAfter"), 0);
            if (column + (long) after > limits.maxColumns()) {
                throw limit("Table region gap budget exceeded");
            }
            final var physical = new ArrayList<Footprint>();
            final var groups = new ArrayList<Footprint>();
            Footprint anchor = null;
            for (final var cell : cells.get(r)) {
                ElementCollector.checkInterrupted();
                final Node cellPr = DocxBodyView.child(cell.getCTTc().getDomNode(), "tcPr");
                final int span = integer(DocxBodyView.child(cellPr, "gridSpan"), 1);
                if (span < 1) {
                    throw malformed("Invalid table region span");
                }
                if (column + (long) span + after > limits.maxColumns()) {
                    throw limit("Table region column budget exceeded");
                }
                final String vertical = merge(DocxBodyView.child(cellPr, "vMerge"));
                final String horizontal = merge(DocxBodyView.child(cellPr, "hMerge"));
                Footprint value;
                if (horizontal.equals("continue")) {
                    if (anchor == null || !anchor.vertical.equals(vertical)) {
                        throw malformed("Invalid horizontal region merge");
                    }
                    value = anchor;
                    value.right += span;
                } else {
                    value = new Footprint(r, column, span, vertical);
                    groups.add(value);
                    anchor = horizontal.equals("restart") ? value : null;
                }
                physical.add(value);
                column += span;
            }
            // Link vertical merge continuations to the preceding row's footprint so all merged cells share one bounding
            // box.
            final var current = new HashMap<Integer, Footprint>();
            final var replacements = new java.util.IdentityHashMap<Footprint, Footprint>();
            for (var value : groups) {
                if (value.vertical.equals("continue")) {
                    final var owner = previous.get(value.left);
                    if (owner == null || owner.right != value.right) {
                        throw malformed("Invalid vertical region merge");
                    }
                    owner.bottom = r;
                    replacements.put(value, owner);
                    current.put(owner.left, owner);
                } else if (value.vertical.equals("restart")) {
                    current.put(value.left, value);
                }
            }
            physical.replaceAll(value -> replacements.getOrDefault(value, value));
            positions.add(List.copyOf(physical));
            previous = current;
            width = Math.max(width, column + after);
            total += column + (long) after;
            if (width > limits.maxColumns() || total > limits.maxCells()) {
                throw limit("Table region logical-cell budget exceeded");
            }
        }
    }

    /**
     * Determines the active table-style conditional regions for a paragraph at the given physical row and cell.
     *
     * @param row 0-based physical row index
     * @param cell 0-based physical cell index within the row
     * @param paragraph paragraph inside the cell
     * @return immutable set of active conditional region names
     * @throws IOException if the thread is interrupted
     */
    Set<String> match(final int row, final int cell, final XWPFParagraph paragraph) throws IOException {
        ElementCollector.checkInterrupted();
        final var footprint = positions.get(row).get(cell);
        // Combine row-level tblPrEx overrides with table and style-chain tblPr settings.
        final var effective = new ArrayList<Node>();
        final Node exceptions = DocxBodyView.child(rows.get(row).getCtRow().getDomNode(), "tblPrEx");
        if (exceptions != null) {
            effective.add(exceptions);
        }
        effective.addAll(settings);
        final boolean firstRow = look(effective, "firstRow", 0x20, false),
                      lastRow = look(effective, "lastRow", 0x40, false);
        final boolean firstCol = look(effective, "firstColumn", 0x80, false),
                      lastCol = look(effective, "lastColumn", 0x100, false);
        final boolean horizontal = !look(effective, "noHBand", 0x200, false),
                      vertical = !look(effective, "noVBand", 0x400, false);
        final int rowBand = band(effective, "tblStyleRowBandSize"), colBand = band(effective, "tblStyleColBandSize");
        // Compute initial geometric edge, alternating band, and corner membership from the cell's logical footprint.
        final boolean[] flags = new boolean[12];
        flags[0] = footprint.top == 0;
        flags[1] = footprint.bottom == rows.size() - 1;
        flags[2] = footprint.left == 0;
        flags[3] = footprint.right == width - 1;
        final boolean reserveFirstRow = firstRow && paragraphRegions.contains("firstRow");
        final boolean reserveLastRow = lastRow && paragraphRegions.contains("lastRow");
        final boolean reserveFirstColumn = firstCol && paragraphRegions.contains("firstCol");
        final boolean reserveLastColumn = lastCol && paragraphRegions.contains("lastCol");
        int r = Math.max(0, footprint.top - (reserveFirstRow ? 1 : 0));
        final int c = Math.max(0, footprint.left - (reserveFirstColumn ? 1 : 0));
        final boolean interiorColumn = !(reserveFirstColumn && flags[2] || reserveLastColumn && flags[3]);
        final boolean interiorRow = !(reserveFirstRow && flags[0] || reserveLastRow && flags[1]);
        flags[4] = interiorColumn && colBand > 0 && (c / colBand) % 2 == 0;
        flags[5] = interiorColumn && colBand > 0 && !flags[4];
        flags[6] = interiorRow && rowBand > 0 && (r / rowBand) % 2 == 0;
        flags[7] = interiorRow && rowBand > 0 && !flags[6];
        flags[8] = flags[0] && flags[2];
        flags[9] = flags[0] && flags[3];
        flags[10] = flags[1] && flags[2];
        flags[11] = flags[1] && flags[3];
        // Overlay explicit cnfStyle overrides in row -> cell -> paragraph order and recompute non-explicit corners.
        final var explicit = new boolean[12];
        overlay(flags, explicit,
                DocxBodyView.child(DocxBodyView.child(rows.get(row).getCtRow().getDomNode(), "trPr"), "cnfStyle"));
        overlay(flags, explicit,
                DocxBodyView.child(
                        DocxBodyView.child(cells.get(row).get(cell).getCTTc().getDomNode(), "tcPr"), "cnfStyle"));
        overlay(flags, explicit,
                DocxBodyView.child(
                        paragraph.getCTP().getPPr() == null ? null : paragraph.getCTP().getPPr().getDomNode(),
                        "cnfStyle"));
        if (!explicit[8]) {
            flags[8] = flags[0] && flags[2];
        }
        if (!explicit[9]) {
            flags[9] = flags[0] && flags[3];
        }
        if (!explicit[10]) {
            flags[10] = flags[1] && flags[2];
        }
        if (!explicit[11]) {
            flags[11] = flags[1] && flags[3];
        }
        // Emit active region names gated by tblLook family flags and band sizes.
        final var result = new LinkedHashSet<String>();
        if (vertical && colBand > 0) {
            if (flags[4]) {
                result.add("band1Vert");
            }
            if (flags[5]) {
                result.add("band2Vert");
            }
        }
        if (horizontal && rowBand > 0) {
            if (flags[6]) {
                result.add("band1Horz");
            }
            if (flags[7]) {
                result.add("band2Horz");
            }
        }
        if (firstRow && flags[0]) {
            result.add("firstRow");
        }
        if (lastRow && flags[1]) {
            result.add("lastRow");
        }
        if (firstCol && flags[2]) {
            result.add("firstCol");
        }
        if (lastCol && flags[3]) {
            result.add("lastCol");
        }
        if (firstRow && firstCol && flags[8]) {
            result.add("nwCell");
        }
        if (firstRow && lastCol && flags[9]) {
            result.add("neCell");
        }
        if (lastRow && firstCol && flags[10]) {
            result.add("swCell");
        }
        if (lastRow && lastCol && flags[11]) {
            result.add("seCell");
        }
        return Set.copyOf(result);
    }

    /**
     * Evaluates a {@code tblLook} boolean attribute or hexadecimal mask bit across ordered settings nodes.
     *
     * @param settings ordered {@code tblPrEx} and {@code tblPr} nodes
     * @param attribute explicit attribute name on {@code tblLook}
     * @param bit hexadecimal bitmask flag when explicit attribute is absent
     * @param fallback default value when no {@code tblLook} setting is present
     * @return resolved boolean flag
     * @throws IOException if the mask is malformed
     */
    private static boolean look(final List<Node> settings, final String attribute, final int bit,
            final boolean fallback) throws IOException {
        for (final Node properties : settings) {
            final Node look = DocxBodyView.child(properties, "tblLook");
            if (look == null) {
                continue;
            }
            final String explicit = DocxBodyView.attribute(look, DocxBodyView.WORD, attribute);
            if (explicit != null) {
                return on(explicit);
            }
            final String mask = DocxBodyView.attribute(look, DocxBodyView.WORD, "val");
            if (mask != null) {
                if (!mask.matches("[0-9A-Fa-f]{4}")) {
                    throw malformed("Invalid table look mask");
                }
                return (Integer.parseInt(mask, 16) & bit) != 0;
            }
        }
        return fallback;
    }

    /**
     * Reads the effective row or column band size from ordered table settings nodes.
     *
     * @param settings ordered {@code tblPrEx} and {@code tblPr} nodes
     * @param name band size element name ({@code "tblStyleRowBandSize"} or {@code "tblStyleColBandSize"})
     * @return effective band size, or {@code 0} if absent
     * @throws IOException if the band size is negative or malformed
     */
    private static int band(final List<Node> settings, final String name) throws IOException {
        for (final Node properties : settings) {
            Node value = DocxBodyView.child(properties, name);
            if (value != null) {
                return integer(value, 1);
            }
        }
        return 0;
    }

    /**
     * Overlays explicit {@code cnfStyle} bitmask and named boolean attributes onto the region flags array.
     *
     * @param flags mutable 12-element region membership array
     * @param explicit mutable 12-element array tracking which flags were explicitly overridden
     * @param cnf {@code cnfStyle} DOM node, or {@code null}
     * @throws IOException if the mask or boolean flag is malformed
     */
    private static void overlay(final boolean[] flags, final boolean[] explicit, final Node cnf) throws IOException {
        if (cnf == null) {
            return;
        }
        final String mask = DocxBodyView.attribute(cnf, DocxBodyView.WORD, "val");
        if (mask != null) {
            if (!mask.matches("[01]{12}")) {
                throw malformed("Invalid conditional table-style mask");
            }
            for (int i = 0; i < flags.length; i++) {
                flags[i] = mask.charAt(i) == '1';
                explicit[i] = true;
            }
        }
        for (int i = 0; i < ATTRS.length; i++) {
            String value = DocxBodyView.attribute(cnf, DocxBodyView.WORD, ATTRS[i]);
            if (value != null) {
                flags[i] = on(value);
                explicit[i] = true;
            }
        }
    }

    /**
     * Parses a strict WordprocessingML boolean attribute value.
     *
     * @param value attribute string value
     * @return parsed boolean value
     * @throws IOException if the value is not a recognized boolean literal
     */
    private static boolean on(String value) throws IOException {
        return switch (value) {
            case "true", "on", "1" -> true;
            case "false", "off", "0" -> false;
            default -> throw malformed("Invalid table-style boolean flag");
        };
    }

    /**
     * Parses a non-negative integer from a WordprocessingML {@code w:val} attribute.
     *
     * @param node DOM node to inspect, or {@code null}
     * @param fallback value returned when the node or {@code w:val} attribute is absent
     * @return non-negative integer value
     * @throws IOException if the attribute value is negative or not an exact integer
     */
    private static int integer(final Node node, final int fallback) throws IOException {
        if (node == null) {
            return fallback;
        }
        String value = DocxBodyView.attribute(node, DocxBodyView.WORD, "val");
        if (value == null) {
            return fallback;
        }
        try {
            final int number = new BigInteger(value).intValueExact();
            if (number < 0) {
                throw malformed("Negative table region size");
            }
            return number;
        } catch (final NumberFormatException | ArithmeticException error) {
            throw malformed("Invalid table region size");
        }
    }

    /**
     * Parses a horizontal or vertical merge mode from a {@code vMerge} or {@code hMerge} DOM node.
     *
     * @param node merge DOM node, or {@code null}
     * @return {@code "none"}, {@code "restart"}, or {@code "continue"}
     * @throws IOException if the merge attribute value is invalid
     */
    private static String merge(final Node node) throws IOException {
        if (node == null) {
            return "none";
        }
        String value = DocxBodyView.attribute(node, DocxBodyView.WORD, "val");
        if (value == null || value.equals("continue")) {
            return "continue";
        }
        if (value.equals("restart")) {
            return value;
        }
        throw malformed("Invalid table region merge");
    }

    /**
     * Creates a table resource budget exception with the given message.
     *
     * @param message diagnostic error message
     * @return partition exception with {@link PartitionException.Code#TABLE_LIMIT}
     */
    private static PartitionException limit(final String message) {
        return new PartitionException(PartitionException.Code.TABLE_LIMIT, message);
    }

    /**
     * Creates a malformed table structure exception with the given message.
     *
     * @param message diagnostic error message
     * @return partition exception with {@link PartitionException.Code#MALFORMED_DOCUMENT}
     */
    private static PartitionException malformed(final String message) {
        return new PartitionException(PartitionException.Code.MALFORMED_DOCUMENT, message);
    }
}
