package io.docweave.partition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Immutable coordinate writer shared by native rule/text bounds and rendered OCR anchors. */
final class PdfTableCells {
    /** Displayed, crop-relative, unscaled source page dimensions. */
    private final PdfPageGeometry geometry;
    /** Source x units to displayed user-space units; one for native, actual page/raster ratio for OCR. */
    private final double xScale;
    /** Source y units to displayed user-space units. */
    private final double yScale;
    /** Explicit support for bounded unmerged provider header prefixes; false on historical writers. */
    private final boolean headerPrefixes;
    /** Explicit support for merged anchors within a bounded declared header prefix. */
    private final boolean headerLayouts;

    /**
     * Captures an already normalized frame without another crop/rotation/UserUnit transform.
     * @param geometry displayed PDF page geometry
     * @param xScale positive source-to-page horizontal ratio
     * @param yScale positive source-to-page vertical ratio
     */
    PdfTableCells(final PdfPageGeometry geometry, final double xScale, final double yScale) {
        this(geometry, xScale, yScale, false);
    }

    /**
     * Captures the same coordinate frame with an independent explicit provider-prefix evidence policy.
     * @param geometry displayed PDF page geometry
     * @param xScale source-to-page horizontal ratio
     * @param yScale source-to-page vertical ratio
     * @param headerPrefixes whether bounded declared provider header prefixes may be compared
     */
    PdfTableCells(
            final PdfPageGeometry geometry, final double xScale, final double yScale, final boolean headerPrefixes) {
        this(geometry, xScale, yScale, headerPrefixes, false);
    }

    /**
     * Captures explicit prefix and merged-layout policies in the same source coordinate frame.
     * @param geometry displayed PDF page geometry
     * @param xScale source-to-page horizontal ratio
     * @param yScale source-to-page vertical ratio
     * @param headerPrefixes whether declared unmerged prefixes may be compared
     * @param headerLayouts whether exact merged prefix topology may be compared
     */
    PdfTableCells(final PdfPageGeometry geometry, final double xScale, final double yScale,
            final boolean headerPrefixes, final boolean headerLayouts) {
        this.headerLayouts = headerLayouts;
        this.headerPrefixes = headerPrefixes;
        this.geometry = geometry;
        this.xScale = xScale;
        this.yScale = yScale;
    }

    /**
     * Describes each rectangular anchor once, including empty cells and full merged extents.
     * @param grid validated displayed-native or rendered-pixel grid
     * @param work shared table work/cancellation budget
     * @return versioned JSON-compatible geometry extension; no text copies or covered-slot placeholders
     * @throws IOException if cancelled or over the geometry budget
     */
    Map<String, Object> grid(final PdfTableGrid grid, final PdfTableWork work) throws IOException {
        final var cells = new ArrayList<Map<String, Object>>();
        for (final var anchor : grid.cells()) {
            cells.add(cell(anchor.row(), anchor.column(), anchor.rowSpan(), anchor.columnSpan(),
                    grid.xs().get(anchor.column()), grid.ys().get(anchor.row()),
                    grid.xs().get(anchor.column() + anchor.columnSpan()),
                    grid.ys().get(anchor.row() + anchor.rowSpan()), work));
        }
        return describe("rule_centerlines", grid.ys().size() - 1, grid.xs().size() - 1, cells);
    }

    /**
     * Creates capped matching evidence without serializing cell arrays or changing extraction output.
     * @param grid validated topology
     * @param header first-row anchor text, in displayed column order
     * @param body whether a nonblank body anchor exists
     * @param source native_rules or ocr_rules; source families do not cross-link
     * @param work shared table geometry/cancellation budget
     * @return immutable evidence, or null for unsupported/ambiguous header layouts
     * @throws IOException if interrupted or over shared work limits
     */
    PdfTableSignature signature(final PdfTableGrid grid, final List<String> header, final boolean body,
            final String source, final PdfTableWork work) throws IOException {
        if (!body || !PdfTableSignature.supports(grid)) {
            return null;
        }
        final var columns = new ArrayList<Double>();
        for (final float boundary : grid.xs()) {
            work.step();
            columns.add(boundary * xScale);
        }
        final var transforms = new java.util.LinkedHashMap<String, Object>();
        geometry.describe(transforms);
        return PdfTableSignature.create(geometry.width(), geometry.height(), transforms, source, columns, header,
                grid.ys().getFirst() * yScale, grid.ys().getLast() * yScale);
    }

    /**
     * Builds private matching evidence from declared provider headers, never inferred text roles.
     * Historical writers admit one row; explicit prefix writers admit bounded unmerged rows,
     * and the separate layout writer admits exact declared merged prefix topology.
     * Every anchor must agree exactly with the header's column boundaries, including body spans.
     * Unsupported layouts retain their extraction output but supply no continuation evidence.
     * @param table validated complete provider topology
     * @param anchors row-major anchors corresponding one-to-one to values
     * @param values authoritative native or OCR cell text
     * @param source recognition family, kept distinct from ruled tables and other text routes
     * @param work shared geometry and cancellation budget
     * @return capped signature, or null for unsupported or blank header/body evidence
     * @throws IOException if interrupted or the shared geometry budget is exhausted
     */
    PdfTableSignature providerSignature(final PdfTableLayoutProvider.Table table,
            final List<PdfTableLayoutProvider.Anchor> anchors, final List<String> values, final String source,
            final PdfTableWork work) throws IOException {
        if (headerLayouts && table.headerRows() > 1) {
            return PdfProviderHeaderLayout.signature(geometry, xScale, yScale, table, anchors, values, source, work);
        }
        if (headerPrefixes && table.headerRows() > 1) {
            return prefixSignature(table, anchors, values, source, work);
        }
        final int columns = table.columns();
        if (table.headerRows() != 1 || table.rows() < 2 || columns < 2 || columns > 32) {
            return null;
        }
        final var boundaries = new ArrayList<Double>();
        final var labels = new ArrayList<String>();
        for (int column = 0; column < columns; column++) {
            work.step();
            final var anchor = anchors.get(column);
            if (anchor.row() != 0 || anchor.column() != column || anchor.rowSpan() != 1 || anchor.columnSpan() != 1
                    || column > 0 && anchor.bounds().left() * xScale != boundaries.getLast()) {
                return null;
            }
            if (column == 0) {
                boundaries.add(anchor.bounds().left() * xScale);
            }
            boundaries.add(anchor.bounds().right() * xScale);
            labels.add(values.get(column));
        }
        boolean body = false;
        for (int index = columns; index < anchors.size(); index++) {
            work.step();
            final var anchor = anchors.get(index);
            if (anchor.bounds().left() * xScale != boundaries.get(anchor.column())
                    || anchor.bounds().right() * xScale != boundaries.get(anchor.column() + anchor.columnSpan())) {
                return null;
            }
            body |= !values.get(index).isBlank();
        }
        if (!body) {
            return null;
        }
        final var transforms = new java.util.LinkedHashMap<String, Object>();
        geometry.describe(transforms);
        return PdfTableSignature.create(geometry.width(), geometry.height(), transforms,
                source + ":" + table.provenance(), boundaries, labels, table.bounds().top() * yScale,
                table.bounds().bottom() * yScale);
    }

    /**
     * Builds bounded row-aware evidence only from a complete explicitly declared unmerged header prefix.
     * Each body anchor must retain exact header column boundaries; spans may not cross into the prefix.
     * @param table validated complete provider topology
     * @param anchors complete row-major provider anchors
     * @param values authoritative source or OCR values in anchor order
     * @param source recognition family, isolated from other routes and provider provenance
     * @param work shared source geometry/cancellation budget
     * @return signature or null for unsupported, blank, oversized or misaligned evidence
     * @throws IOException if shared work or cancellation limits fail
     */
    private PdfTableSignature prefixSignature(final PdfTableLayoutProvider.Table table,
            final List<PdfTableLayoutProvider.Anchor> anchors, final List<String> values, final String source,
            final PdfTableWork work) throws IOException {
        final int rows = table.headerRows();
        final int columns = table.columns();
        if (rows < 2 || rows > 4 || columns < 2 || columns > 32 || (long) rows * columns > 32 || table.rows() <= rows
                || anchors.size() < rows * columns || values.size() != anchors.size()) {
            return null;
        }
        final var boundaries = new ArrayList<Double>();
        final var labels = new ArrayList<List<String>>();
        for (int row = 0; row < rows; row++) {
            final var header = new ArrayList<String>();
            for (int column = 0; column < columns; column++) {
                work.step();
                final int index = row * columns + column;
                final var anchor = anchors.get(index);
                if (anchor.row() != row || anchor.column() != column || anchor.rowSpan() != 1
                        || anchor.columnSpan() != 1) {
                    return null;
                }
                final double left = anchor.bounds().left() * xScale;
                final double right = anchor.bounds().right() * xScale;
                if (row == 0) {
                    if (column == 0) {
                        boundaries.add(left);
                    } else if (left != boundaries.getLast()) {
                        return null;
                    }
                    boundaries.add(right);
                } else if (left != boundaries.get(column) || right != boundaries.get(column + 1)) {
                    return null;
                }
                header.add(values.get(index));
            }
            labels.add(List.copyOf(header));
        }
        boolean body = false;
        for (int index = rows * columns; index < anchors.size(); index++) {
            work.step();
            final var anchor = anchors.get(index);
            if (anchor.row() < rows || anchor.bounds().left() * xScale != boundaries.get(anchor.column())
                    || anchor.bounds().right() * xScale != boundaries.get(anchor.column() + anchor.columnSpan())) {
                return null;
            }
            body |= !values.get(index).isBlank();
        }
        if (!body) {
            return null;
        }
        final var transforms = new java.util.LinkedHashMap<String, Object>();
        geometry.describe(transforms);
        return PdfTableSignature.createPrefix(geometry.width(), geometry.height(), transforms,
                source + ":" + table.provenance(), boundaries, labels, table.bounds().top() * yScale,
                table.bounds().bottom() * yScale);
    }

    /**
     * Converts one approximate enclosing rectangle to the same frame as the parent Table coordinates.
     * Points are ordered top-left, top-right, bottom-right, bottom-left. No clipping or glyph-outline
     * fidelity is implied. OCR values describe the full rule-defined cell, not its inset/padded crop.
     * @param row zero-based anchor row in displayed topology
     * @param column zero-based anchor column
     * @param rowSpan positive occupied atomic rows
     * @param columnSpan positive occupied atomic columns
     * @param left source left edge
     * @param top source top edge
     * @param right source right edge
     * @param bottom source bottom edge
     * @param work shared table work/cancellation counter
     * @return JSON-compatible anchor description in unscaled displayed PDFUserSpace
     * @throws IOException if cancelled or over the shared work ceiling
     */
    Map<String, Object> cell(final int row, final int column, final int rowSpan, final int columnSpan,
            final double left, final double top, final double right, final double bottom, final PdfTableWork work)
            throws IOException {
        work.step();
        final double x1 = left * xScale;
        final double x2 = right * xScale;
        final double y1 = top * yScale;
        final double y2 = bottom * yScale;
        return Map.of("row_index", row, "column_index", column, "row_span", rowSpan, "column_span", columnSpan,
                "coordinates",
                Map.of("system", "PDFUserSpace", "layout_width", (double) geometry.width(), "layout_height",
                        (double) geometry.height(), "points",
                        List.of(List.of(x1, y1), List.of(x2, y1), List.of(x2, y2), List.of(x1, y2))));
    }

    /**
     * Wraps row-major anchors with explicit topology dimensions and the source geometry interpretation.
     * @param method rule_centerlines, text_bounds, inferred_cell_bounds or provider_rectangles; interpretation remains
     *         explicit
     * @param rows atomic row count
     * @param columns atomic column count
     * @param cells row-major anchors, without recognized text duplication
     * @return immutable envelope; Element emission also takes a deep immutable snapshot
     */
    static Map<String, Object> describe(
            final String method, final int rows, final int columns, final List<Map<String, Object>> cells) {
        return Map.of("version", 1, "geometry", method, "row_count", rows, "column_count", columns, "cells",
                List.copyOf(cells));
    }
}
