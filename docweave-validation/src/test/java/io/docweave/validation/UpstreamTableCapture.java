package io.docweave.validation;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded JVM conversion of upstream pixel frames/HTML; never adjusts predicted text or reference labels. */
final class UpstreamTableCapture {
    /** Prevents utility construction. */
    private UpstreamTableCapture() {
    }

    /**
     * Keeps every Table occurrence on the selected page, including missing or invalid HTML structures.
     * Failed whole-document captures must contain no partial elements. Raster width/height, rather than
     * nominal DPI, define the independent x/y scaling into original displayed PDF units.
     * @param capture unchanged complete raw upstream result
     * @param page original one-based page
     * @param width displayed original PDF width
     * @param height displayed original PDF height
     * @param mapper local representation converter
     * @return common selected-page table metadata without text normalization or geometric fitting
     */
    static JsonNode page(
            final JsonNode capture, final int page, final double width, final double height, final JsonMapper mapper) {
        require(page > 0 && Double.isFinite(width) && width > 0 && Double.isFinite(height) && height > 0);
        require(capture.get("outcome").isString() && capture.get("elements").isArray());
        final String outcome = capture.get("outcome").asString();
        require(outcome.equals("SUCCESS") || capture.get("elements").isEmpty());
        final var tables = new ArrayList<Map<String, Object>>();
        for (final var element : capture.get("elements")) {
            if (!element.get("type").asString().equals("Table")) {
                continue;
            }
            final var metadata = element.get("metadata");
            if (metadata.get("page_number").asInt() != page) {
                continue;
            }
            final var coordinates = metadata.get("coordinates");
            require(coordinates != null && coordinates.get("system").asString().equals("PixelSpace"));
            final double rasterWidth = coordinates.get("layout_width").asDouble();
            final double rasterHeight = coordinates.get("layout_height").asDouble();
            require(Double.isFinite(rasterWidth) && rasterWidth > 0 && Double.isFinite(rasterHeight)
                    && rasterHeight > 0);
            final var points = coordinates.get("points");
            require(points.isArray() && points.size() == 4);
            double left = Double.POSITIVE_INFINITY;
            double top = Double.POSITIVE_INFINITY;
            double right = Double.NEGATIVE_INFINITY;
            double bottom = Double.NEGATIVE_INFINITY;
            for (final var point : points) {
                require(point.isArray() && point.size() == 2);
                final double x = point.get(0).asDouble() * width / rasterWidth;
                final double y = point.get(1).asDouble() * height / rasterHeight;
                require(Double.isFinite(x) && Double.isFinite(y));
                left = Math.min(left, x);
                top = Math.min(top, y);
                right = Math.max(right, x);
                bottom = Math.max(bottom, y);
            }
            require(left < right && top < bottom);
            final var table = new LinkedHashMap<String, Object>();
            table.put("page_number", page);
            table.put("coordinates", Map.of("system", "PDFUserSpace", "points",
                                             List.of(List.of(left, top), List.of(left, bottom), List.of(right, bottom),
                                                     List.of(right, top))));
            table.put("pdf_table_method", "upstream_hi_res_html");
            final var html = metadata.get("text_as_html");
            try {
                table.putAll(structure(html != null && html.isString() ? html.asString() : ""));
            } catch (final IllegalArgumentException malformed) {
                // Invalid predicted topology remains a detected table with no valid keys, not a dropped observation.
                table.put("text_as_html", "<table/>");
                table.put("pdf_table_cells", Map.of("row_count", 0, "column_count", 0, "cells", List.of()));
                table.put("upstream_structure_valid", false);
            }
            tables.add(table);
        }
        return mapper.valueToTree(Map.of("outcome", outcome, "tables", tables));
    }

    /**
     * Derives only explicitly declared HTML anchors/spans and preserves each original cell's characters.
     * Empty/missing structure stays invalid; duplicate/overlapping/out-of-row spans never become fabricated cells.
     * @param html predicted table HTML, not reference markup
     * @return canonical XML HTML and matching anchor metadata
     */
    static Map<String, Object> structure(final String html) {
        require(html.length() <= 1_000_000);
        final var parsed = Jsoup.parse(html);
        require(parsed.select("table").size() == 1);
        final var source = parsed.selectFirst("table");
        final var rows = source.select("tr");
        require(!rows.isEmpty() && rows.size() <= 1024);
        final var output = new Document("");
        output.outputSettings()
                .prettyPrint(false)
                .syntax(Document.OutputSettings.Syntax.xml)
                .escapeMode(org.jsoup.nodes.Entities.EscapeMode.xhtml);
        final var table = output.appendElement("table");
        final var occupied = new HashSet<Long>();
        final var anchors = new ArrayList<Map<String, Integer>>();
        int columns = 0;
        for (int row = 0; row < rows.size(); row++) {
            final var line = table.appendElement("tr");
            int column = 0;
            for (final var cell : rows.get(row).children()) {
                require(cell.tagName().equals("td") || cell.tagName().equals("th"));
                while (occupied.contains(slot(row, column))) {
                    column++;
                }
                final int rowSpan = span(cell.attr("rowspan"));
                final int columnSpan = span(cell.attr("colspan"));
                require(row + rowSpan <= rows.size() && column + columnSpan <= 1024);
                for (int r = row; r < row + rowSpan; r++) {
                    for (int c = column; c < column + columnSpan; c++) {
                        require(occupied.size() < 1_000_000 && occupied.add(slot(r, c)));
                    }
                }
                anchors.add(Map.of(
                        "row_index", row, "column_index", column, "row_span", rowSpan, "column_span", columnSpan));
                line.appendChild(cell.clone());
                column += columnSpan;
                columns = Math.max(columns, column);
            }
        }
        require(!anchors.isEmpty());
        return Map.of("text_as_html", table.outerHtml(), "pdf_table_cells",
                Map.of("row_count", rows.size(), "column_count", columns, "cells", anchors), "upstream_structure_valid",
                true);
    }

    /**
     * Parses a bounded positive span without HTML's implicit zero/end-of-section semantics.
     * @param value original attribute, empty when absent
     * @return one for absent attributes or the explicit positive value
     */
    private static int span(final String value) {
        if (value.isEmpty()) {
            return 1;
        }
        require(value.matches("[0-9]{1,4}"));
        final int span = Integer.parseInt(value);
        require(span > 0 && span <= 1024);
        return span;
    }

    /**
     * Packs a bounded row/column coordinate without collisions.
     * @param row zero-based row
     * @param column zero-based column
     * @return occupancy key
     */
    private static long slot(final int row, final int column) {
        return ((long) row << 32) | column;
    }

    /**
     * Rejects unsupported capture frames or invalid predicted structure without printing source text.
     * @param condition required representation invariant
     */
    private static void require(final boolean condition) {
        if (!condition) {
            throw new IllegalArgumentException("Invalid upstream table capture representation");
        }
    }
}
