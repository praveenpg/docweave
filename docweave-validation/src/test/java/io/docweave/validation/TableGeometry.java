package io.docweave.validation;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/**
 * Geometry-only table association used by the migrated cTDaR and detector scorers.
 *
 * <p>Annotations are evaluation inputs only. Association never consults topology or text, duplicate
 * predictions cannot claim one reference twice, and published spans are read without repair. The numeric
 * operations follow the retired Python helpers operation by operation so that IEEE results, and therefore
 * report bytes, are identical.
 */
final class TableGeometry {
    /** Prevents instantiation of this static utility class. */
    private TableGeometry() {
    }

    /**
     * Axis-aligned envelope in one image or page frame.
     *
     * @param left minimum x
     * @param top minimum y
     * @param right maximum x
     * @param bottom maximum y
     */
    record Box(double left, double top, double right, double bottom) {
        /**
         * Returns the coordinates as a Python-compatible float list.
         *
         * @return four coordinates
         */
        List<Object> values() {
            return List.of(left, top, right, bottom);
        }
    }

    /**
     * Positional span key of one anchor; equality is exact integer equality.
     *
     * @param row first row
     * @param column first column
     * @param rowSpan rows covered
     * @param columnSpan columns covered
     */
    record SpanKey(int row, int column, int rowSpan, int columnSpan) {}

    /**
     * Predicted or published table structure.
     *
     * @param profile capture profile directory name, or {@code null} for references
     * @param crop capture crop index
     * @param table capture table index
     * @param rows grid rows
     * @param columns grid columns
     * @param anchors distinct positional span keys
     * @param box envelope in the original source frame
     */
    record Table(String profile, int crop, int table, int rows, int columns, Set<SpanKey> anchors, Box box) {
        /**
         * Copies the anchor set.
         *
         * @param profile capture profile directory name, or {@code null} for references
         * @param crop capture crop index
         * @param table capture table index
         * @param rows grid rows
         * @param columns grid columns
         * @param anchors distinct positional span keys
         * @param box envelope in the original source frame
         */
        Table {
            anchors = Set.copyOf(anchors);
            Objects.requireNonNull(box, "box");
        }

        /**
         * Creates a reference-style table for tests and published labels.
         *
         * @param rows grid rows
         * @param columns grid columns
         * @param anchors distinct positional span keys
         * @param box envelope
         * @return table without capture identity
         */
        static Table of(final int rows, final int columns, final Set<SpanKey> anchors, final Box box) {
            return new Table(null, 0, 0, rows, columns, anchors, box);
        }

        /**
         * Returns a copy with a different envelope.
         *
         * @param replacement new envelope
         * @return relocated table
         */
        Table at(final Box replacement) {
            return new Table(profile, crop, table, rows, columns, anchors, replacement);
        }

        /**
         * Tests exact topology equality: dimensions and the complete anchor set.
         *
         * @param other compared table
         * @return whether both topologies are identical
         */
        boolean sameTopology(final Table other) {
            return rows == other.rows && columns == other.columns && anchors.equals(other.anchors);
        }

        /**
         * Counts shared anchors.
         *
         * @param other compared table
         * @return intersection size
         */
        int matchingAnchors(final Table other) {
            final var shared = new HashSet<>(anchors);
            shared.retainAll(other.anchors);
            return shared.size();
        }
    }

    /**
     * One-to-one association between a reference and a candidate.
     *
     * @param reference reference index
     * @param candidate candidate index
     */
    record Match(int reference, int candidate) {}

    /**
     * Envelope intersection over union, as {@code compare_tiled_regions.iou}.
     *
     * @param first first envelope
     * @param second second envelope
     * @return float ratio, or integer {@code 0} when the union is not positive (Python's literal)
     */
    static Number iou(final Box first, final Box second) {
        final double overlap = Math.max(0, Math.min(first.right, second.right) - Math.max(first.left, second.left))
                               * Math.max(0, Math.min(first.bottom, second.bottom) - Math.max(first.top, second.top));
        final double area = (first.right - first.left) * (first.bottom - first.top)
                            + (second.right - second.left) * (second.bottom - second.top);
        return area > overlap ? (Number) (overlap / (area - overlap)) : (Number) 0L;
    }

    /**
     * Counts maximum-cardinality one-to-one matches at an IoU threshold.
     *
     * <p>Candidate edges are tried in descending IoU order with stable index tie-breaking, and augmenting
     * paths make the cardinality independent of reference order, as in {@code maximum_matches}.
     *
     * @param references reference envelopes
     * @param candidates candidate envelopes
     * @param threshold minimum IoU
     * @return matches sorted by reference then candidate
     */
    static List<Match> maximumMatches(final List<Box> references, final List<Box> candidates, final double threshold) {
        final var edges = new ArrayList<List<Integer>>();
        for (final var reference : references) {
            final var order = new ArrayList<Integer>();
            for (int index = 0; index < candidates.size(); index++) {
                order.add(index);
            }
            order.sort(Comparator
                            .comparing((Integer index)
                                               -> iou(reference, candidates.get(index)),
                                    PythonJson::numericCompare)
                            .reversed());
            edges.add(order.stream()
                            .filter(index -> iou(reference, candidates.get(index)).doubleValue() >= threshold)
                            .toList());
        }
        final var owners = new HashMap<Integer, Integer>();
        for (int reference = 0; reference < references.size(); reference++) {
            assign(reference, new HashSet<>(), edges, owners);
        }
        return owners.entrySet()
                .stream()
                .map(entry -> new Match(entry.getValue(), entry.getKey()))
                .sorted(Comparator.comparingInt(Match::reference).thenComparingInt(Match::candidate))
                .toList();
    }

    /**
     * Finds an augmenting path for one reference.
     *
     * @param reference reference index
     * @param seen candidates visited in this search
     * @param edges ordered admissible candidates per reference
     * @param owners current candidate owners
     * @return whether the reference was matched
     */
    private static boolean assign(final int reference, final Set<Integer> seen, final List<List<Integer>> edges,
            final Map<Integer, Integer> owners) {
        for (final int prediction : edges.get(reference)) {
            if (!seen.add(prediction)) {
                continue;
            }
            if (!owners.containsKey(prediction) || assign(owners.get(prediction), seen, edges, owners)) {
                owners.put(prediction, reference);
                return true;
            }
        }
        return false;
    }

    /**
     * Reads a source-frame rectangle from {@code left/top/right/bottom} TSV fields.
     *
     * @param row TSV row
     * @return float envelope
     */
    static Box rectangle(final Map<String, String> row) {
        return new Box(number(row, "left"), number(row, "top"), number(row, "right"), number(row, "bottom"));
    }

    /**
     * Parses one Python {@code float()} field.
     *
     * @param row TSV row
     * @param key field name
     * @return parsed value
     */
    static double number(final Map<String, String> row, final String key) {
        return ScoringFiles.pyFloat(Objects.requireNonNull(row.get(key), key));
    }

    /**
     * Parses one Python {@code int()} field.
     *
     * @param row TSV row
     * @param key field name
     * @return parsed value
     */
    static int integer(final Map<String, String> row, final String key) {
        return integer(Objects.requireNonNull(row.get(key), key));
    }

    /**
     * Parses a decimal integer with surrounding whitespace, as Python {@code int(str)} does for these files.
     *
     * @param text integer text
     * @return parsed value
     */
    static int integer(final String text) {
        return ScoringFiles.pyInt(text);
    }

    /**
     * Maps a box from the rendered PDF frame back to original image pixels.
     *
     * <p>The scale uses the physical PDF placement, never the floored integer raster canvas, matching
     * {@code source_box}.
     *
     * @param box rendered-frame envelope
     * @param frame captured {@code source.tsv} row
     * @return original-pixel envelope
     */
    static Box sourceBox(final Box box, final Map<String, String> frame) {
        final double dpi = number(frame, "render_dpi");
        final double xscale = integer(frame, "original_width") / (number(frame, "pdf_width") * dpi / 72);
        final double yscale = integer(frame, "original_height") / (number(frame, "pdf_height") * dpi / 72);
        return new Box(box.left * xscale, box.top * yscale, box.right * xscale, box.bottom * yscale);
    }

    /**
     * Loads complete predicted structures from one profile directory ({@code predictions}).
     *
     * @param directory profile capture directory
     * @param frame captured {@code source.tsv} row
     * @return tables in capture order, or empty for a typed non-OK outcome
     * @throws IOException if a capture file cannot be read
     */
    static List<Table> predictions(final Path directory, final Map<String, String> frame) throws IOException {
        if (!ScoringFiles.strip(ScoringFiles.readText(directory.resolve("outcome.txt"))).equals("OK")) {
            return List.of();
        }
        final var anchors = ScoringFiles.rows(directory.resolve("anchors.tsv"));
        final var result = new ArrayList<Table>();
        for (final var table : ScoringFiles.rows(directory.resolve("tables.tsv"))) {
            final var keys = new LinkedHashSet<SpanKey>();
            for (final var anchor : anchors) {
                if (Objects.equals(anchor.get("crop"), table.get("crop"))
                        && Objects.equals(anchor.get("table"), table.get("table"))) {
                    keys.add(new SpanKey(integer(anchor, "row"), integer(anchor, "column"), integer(anchor, "row_span"),
                            integer(anchor, "column_span")));
                }
            }
            result.add(new Table(directory.getFileName().toString(), integer(table, "crop"), integer(table, "table"),
                    integer(table, "rows"), integer(table, "columns"), keys, sourceBox(rectangle(table), frame)));
        }
        return result;
    }

    /**
     * Reads published cTDaR cell spans and table envelopes without repairing labels ({@code reference_tables}).
     *
     * @param path original XML annotation
     * @return tables in document order
     * @throws IOException if the file cannot be read or parsed
     */
    static List<Table> referenceTables(final Path path) throws IOException {
        final byte[] data = Files.readAllBytes(path);
        final String upper = new String(data, StandardCharsets.ISO_8859_1).toUpperCase(Locale.ROOT);
        if (upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY")) {
            throw new IllegalArgumentException("Unexpected XML declaration");
        }
        final Element root;
        try {
            final var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);
            root = factory.newDocumentBuilder().parse(new ByteArrayInputStream(data)).getDocumentElement();
        } catch (final ParserConfigurationException | SAXException failure) {
            throw new IOException("Invalid annotation XML: " + path.getFileName(), failure);
        }
        final var result = new ArrayList<Table>();
        for (final Element table : children(root, "table")) {
            final var keys = new LinkedHashSet<SpanKey>();
            int rowCount = 0;
            int columnCount = 0;
            for (final Element cell : children(table, "cell")) {
                final int row = integer(attribute(cell, "start-row"));
                final int column = integer(attribute(cell, "start-col"));
                final int endRow = cell.hasAttribute("end-row") ? integer(cell.getAttribute("end-row")) : row;
                final int endColumn = cell.hasAttribute("end-col") ? integer(cell.getAttribute("end-col")) : column;
                if (endRow < row || endColumn < column) {
                    throw new IllegalArgumentException("Invalid published span");
                }
                keys.add(new SpanKey(row, column, endRow - row + 1, endColumn - column + 1));
                rowCount = Math.max(rowCount, endRow + 1);
                columnCount = Math.max(columnCount, endColumn + 1);
            }
            final var coordinates = children(table, "Coords");
            if (coordinates.isEmpty()) {
                throw new IllegalArgumentException("Missing table coordinates");
            }
            double left = Double.POSITIVE_INFINITY;
            double top = Double.POSITIVE_INFINITY;
            double right = Double.NEGATIVE_INFINITY;
            double bottom = Double.NEGATIVE_INFINITY;
            final var pairs = ScoringFiles.splitWhitespace(attribute(coordinates.getFirst(), "points"));
            if (pairs.isEmpty()) {
                throw new IllegalArgumentException("Empty table coordinates");
            }
            for (final String pair : pairs) {
                final String[] values = pair.split(",", -1);
                if (values.length < 2) {
                    throw new IllegalArgumentException("Invalid coordinate pair");
                }
                final double x = ScoringFiles.pyFloat(values[0]);
                final double y = ScoringFiles.pyFloat(values[1]);
                for (int index = 2; index < values.length; index++) {
                    ScoringFiles.pyFloat(values[index]);
                }
                left = Math.min(left, x);
                top = Math.min(top, y);
                right = Math.max(right, x);
                bottom = Math.max(bottom, y);
            }
            result.add(Table.of(rowCount, columnCount, keys, new Box(left, top, right, bottom)));
        }
        return result;
    }

    /**
     * Returns direct children with a local name and no namespace, as ElementTree {@code findall(name)}.
     *
     * @param parent parent element
     * @param name unqualified tag
     * @return matching elements in document order
     */
    private static List<Element> children(final Element parent, final String name) {
        final var result = new ArrayList<Element>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && element.getNamespaceURI() == null
                    && name.equals(element.getLocalName())) {
                result.add(element);
            }
        }
        return result;
    }

    /**
     * Reads a required attribute, failing like a Python {@code KeyError}.
     *
     * @param element element
     * @param name attribute name
     * @return attribute value
     */
    private static String attribute(final Element element, final String name) {
        if (!element.hasAttribute(name)) {
            throw new IllegalArgumentException("Missing attribute: " + name);
        }
        return element.getAttribute(name);
    }
}
