package io.docweave.validation;

import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Hidden comparator. Stored candidate annotations are interpreted independently of Word measurements. */
final class DefaultWordEvidenceComparator implements WordEvidenceComparator {
    @Override
    public Comparison compare(final JsonNode manifest, final JsonNode candidate, final JsonNode observations,
            final String manifestHash) throws IOException {
        final var expected = manifestCases(manifest);
        final var measured = observationCases(manifest, observations, manifestHash);
        final var candidates = indexedCases(candidate, "candidate cases");
        require(candidates.keySet().equals(expected.keySet()), "Missing or unexpected candidate cases");
        final var comparisons = new ArrayList<CaseComparison>();
        for (final var entry : measured.entrySet()) {
            final String name = entry.getKey();
            final String hash = text(expected.get(name), "sha256");
            require(hash.equals(text(candidates.get(name), "sha256")), "Candidate case hash mismatch");
            final var nativeCells = new LinkedHashMap<Coordinate, Cell>();
            candidateCells(candidates.get(name)).forEach(cell -> nativeCells.put(cell.coordinate(), cell));
            final var differences = new ArrayList<Difference>();
            for (final var cell : array(entry.getValue(), "cells")) {
                final var coordinate = coordinate(cell);
                final var nativeCell = nativeCells.get(coordinate);
                require(text(cell, "text").equals(nativeCell.text()), "Word cell text/anchor mismatch");
                final int outline = integer(cell, "outline_level", 0, 9);
                final String label = text(cell, "list_string");
                if (outline != nativeCell.outlineLevel()) {
                    differences.add(new Difference(coordinate, "outline_level", nativeCell.outlineLevel(), outline));
                }
                if (!label.equals(nativeCell.listString())) {
                    differences.add(new Difference(coordinate, "list_string", nativeCell.listString(), label));
                }
            }
            comparisons.add(new CaseComparison(name, differences));
        }
        return new Comparison(text(observations, "word_version"), text(observations, "word_build"),
                text(observations, "platform"), comparisons);
    }

    @Override
    public List<Cell> candidateCells(final JsonNode candidateCase) throws IOException {
        final String name = filename(candidateCase, "file", ".docx");
        final String stem = name.substring(0, name.length() - 5);
        final var tables = array(candidateCase, "elements")
                                   .stream()
                                   .filter(element
                                           -> element.has("type") && element.get("type").isString()
                                                      && element.get("type").asString().equals("Table"))
                                   .toList();
        require(tables.size() == 1, "Controlled candidate requires exactly one Table");
        final var table = tables.getFirst();
        final String value = text(table, "text");
        final var metadata = table.get("metadata");
        require(metadata != null && metadata.isObject(), "Candidate table metadata required");
        final var styles = ranges(metadata, "docx_table_style_paragraphs", value);
        final var lists = ranges(metadata, "docx_list_items", value);
        final var result = new ArrayList<Cell>();
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                final String anchor = stem + "-r" + row + "c" + column;
                final int chars = value.indexOf(anchor);
                require(chars >= 0 && value.indexOf(anchor, chars + 1) < 0,
                        "Missing or ambiguous candidate cell anchor");
                final int start = value.codePointCount(0, chars);
                final int end = start + anchor.codePointCount(0, anchor.length());
                int outline = 9;
                String label = "";
                for (final var style : styles) {
                    if (covers(style, start, end) && style.has("outline_level")) {
                        outline = integer(style, "outline_level", 0, 9);
                        break;
                    }
                }
                for (final var item : lists) {
                    if (covers(item, start, end)) {
                        label = item.has("docx_list_label") ? text(item, "docx_list_label") : "";
                        break;
                    }
                }
                result.add(new Cell(new Coordinate(row, column), anchor, outline, label));
            }
        }
        return List.copyOf(result);
    }

    /** Validates every case and its controlled grid even when candidate comparison is intentionally excluded. */
    static Map<String, JsonNode> observationCases(
            final JsonNode manifest, final JsonNode observations, final String manifestHash) throws IOException {
        require(manifestHash.equals(text(observations, "manifest_sha256")), "Word observation manifest hash mismatch");
        for (final String field : List.of("word_version", "word_build", "platform")) {
            require(!DocxOwnership.normalize(text(observations, field)).isEmpty(),
                    "Word version/build/platform required");
        }
        final var expected = manifestCases(manifest);
        final var actual = indexedCases(observations, "Word observation cases");
        require(actual.keySet().equals(expected.keySet()), "Missing or unexpected Word observation cases");
        for (final var entry : actual.entrySet()) {
            require(text(entry.getValue(), "sha256").equals(text(expected.get(entry.getKey()), "sha256")),
                    "Word observation case hash mismatch");
            final var cells = array(entry.getValue(), "cells");
            final var coordinates = new HashSet<Coordinate>();
            require(cells.size() == 9, "Missing or duplicate Word cells");
            for (final var cell : cells) {
                require(coordinates.add(coordinate(cell)), "Duplicate Word cells");
                integer(cell, "outline_level", 0, 9);
                text(cell, "list_string");
                final var coordinate = coordinate(cell);
                final String name = entry.getKey();
                final String anchor =
                        name.substring(0, name.length() - 5) + "-r" + coordinate.row() + "c" + coordinate.column();
                require(text(cell, "text").equals(anchor), "Word cell text/anchor mismatch");
            }
        }
        return actual;
    }

    static Map<String, JsonNode> manifestCases(final JsonNode manifest) throws IOException {
        final var cases = indexedCases(manifest, "manifest cases");
        require(!cases.isEmpty(), "Empty Word evidence manifest");
        for (final var entry : cases.values()) {
            integer(entry, "rows", 3, 3);
            integer(entry, "columns", 3, 3);
            require(text(entry, "sha256").matches("[a-f0-9]{64}"), "Invalid case SHA-256");
        }
        return cases;
    }

    private static Map<String, JsonNode> indexedCases(final JsonNode root, final String description)
            throws IOException {
        final var result = new LinkedHashMap<String, JsonNode>();
        for (final var entry : array(root, "cases")) {
            final String name = filename(entry, "file", ".docx");
            require(result.putIfAbsent(name, entry) == null, "Missing or duplicate " + description);
        }
        return result;
    }

    static String filename(final JsonNode node, final String field, final String suffix) throws IOException {
        final String name = text(node, field);
        require(!name.contains("/") && !name.contains("\\") && !name.contains(":") && !name.contains("\0")
                        && !name.equals(".") && !name.equals("..") && name.endsWith(suffix)
                        && name.length() > suffix.length(),
                "Unsupported filename");
        return name;
    }

    static List<JsonNode> array(final JsonNode node, final String field) throws IOException {
        require(node != null && node.has(field) && node.get(field).isArray(), "Required array: " + field);
        final var result = new ArrayList<JsonNode>();
        node.get(field).forEach(result::add);
        return result;
    }

    static String text(final JsonNode node, final String field) throws IOException {
        require(node != null && node.has(field) && node.get(field).isString(), "Required string: " + field);
        return node.get(field).asString();
    }

    private static Coordinate coordinate(final JsonNode cell) throws IOException {
        return new Coordinate(integer(cell, "row", 0, 2), integer(cell, "column", 0, 2));
    }

    private static int integer(final JsonNode node, final String field, final int minimum, final int maximum)
            throws IOException {
        require(node != null && node.has(field) && node.get(field).isIntegralNumber()
                        && node.get(field).canConvertToInt(),
                "Required integer: " + field);
        final int value = node.get(field).asInt();
        require(value >= minimum && value <= maximum, "Invalid integer range: " + field);
        return value;
    }

    private static List<JsonNode> ranges(final JsonNode metadata, final String field, final String value)
            throws IOException {
        if (!metadata.has(field)) {
            return List.of();
        }
        final var ranges = array(metadata, field);
        final int points = value.codePointCount(0, value.length());
        for (final var item : ranges) {
            final int start = integer(item, "start_index", 0, points);
            final int end = integer(item, "end_index", 0, points);
            require(start <= end, "Invalid candidate annotation range");
        }
        return ranges;
    }

    private static boolean covers(final JsonNode range, final int start, final int end) {
        return range.get("start_index").asInt() <= start && range.get("end_index").asInt() >= end;
    }

    static void require(final boolean condition, final String message) throws IOException {
        if (!condition) {
            throw new IOException(message);
        }
    }
}
