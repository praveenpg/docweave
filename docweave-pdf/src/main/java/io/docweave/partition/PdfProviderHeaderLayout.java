package io.docweave.partition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/** Capped exact-topology matching for explicitly declared provider header layouts. */
final class PdfProviderHeaderLayout {
    /** Prevents instances of the private evidence algorithm. */
    private PdfProviderHeaderLayout() {
    }

    /**
     * Validates every atomic column edge and retains merged header topology independently of text.
     * Header spans must stay inside the declared prefix; every logical slot must be occupied once.
     * Existing prefix normalization, distinct-column and text budgets apply to expanded slots.
     * @param geometry original displayed page frame
     * @param xScale source-to-page horizontal ratio
     * @param yScale source-to-page vertical ratio
     * @param table validated complete provider topology
     * @param anchors row-major authoritative anchors
     * @param values source cell values corresponding to anchors
     * @param source recognition family, isolated from other routes
     * @param work shared geometry and interruption budget
     * @return bounded signature, or null for unsupported or ambiguous evidence
     * @throws IOException if interrupted or the shared work budget is exhausted
     */
    static PdfTableSignature signature(final PdfPageGeometry geometry, final double xScale, final double yScale,
            final PdfTableLayoutProvider.Table table, final List<PdfTableLayoutProvider.Anchor> anchors,
            final List<String> values, final String source, final PdfTableWork work) throws IOException {
        final int rows = table.headerRows();
        final int columns = table.columns();
        if (rows < 2 || rows > 4 || columns < 2 || columns > 32 || (long) rows * columns > 32 || table.rows() <= rows
                || values.size() != anchors.size()) {
            return null;
        }
        final var boundaries = new Double[columns + 1];
        final var slots = new String[rows][columns];
        final var topology = new StringBuilder();
        boolean body = false;
        for (int index = 0; index < anchors.size(); index++) {
            work.step();
            final var anchor = anchors.get(index);
            final int endColumn = anchor.column() + anchor.columnSpan();
            if (anchor.column() < 0 || anchor.columnSpan() < 1 || endColumn > columns
                    || !observe(boundaries, anchor.column(), anchor.bounds().left() * xScale)
                    || !observe(boundaries, endColumn, anchor.bounds().right() * xScale)) {
                return null;
            }
            if (anchor.row() >= rows) {
                body |= !values.get(index).isBlank();
                continue;
            }
            if (anchor.row() < 0 || anchor.rowSpan() < 1 || anchor.row() + anchor.rowSpan() > rows
                    || values.get(index).length() > 512) {
                return null;
            }
            topology.append(anchor.row())
                    .append(',')
                    .append(anchor.column())
                    .append(',')
                    .append(anchor.rowSpan())
                    .append(',')
                    .append(anchor.columnSpan())
                    .append(';');
            for (int row = anchor.row(); row < anchor.row() + anchor.rowSpan(); row++) {
                for (int column = anchor.column(); column < endColumn; column++) {
                    work.step();
                    if (slots[row][column] != null) {
                        return null;
                    }
                    slots[row][column] = values.get(index);
                }
            }
        }
        if (!body) {
            return null;
        }
        for (int column = 0; column <= columns; column++) {
            if (boundaries[column] == null || !Double.isFinite(boundaries[column])
                    || column > 0 && boundaries[column] <= boundaries[column - 1]) {
                return null;
            }
        }
        final var labels = new ArrayList<List<String>>();
        for (final var row : slots) {
            if (Arrays.stream(row).anyMatch(java.util.Objects::isNull)) {
                return null;
            }
            labels.add(List.of(row));
        }
        final var transforms = new LinkedHashMap<String, Object>();
        geometry.describe(transforms);
        return PdfTableSignature.createPrefix(geometry.width(), geometry.height(), transforms,
                source + ":" + table.provenance() + ":header_layout=" + topology, List.of(boundaries), labels,
                table.bounds().top() * yScale, table.bounds().bottom() * yScale);
    }

    /**
     * Requires all anchors sharing an atomic edge to agree exactly in the displayed frame.
     * @param boundaries bounded observed column-edge array
     * @param index logical edge index
     * @param value observed source edge transformed to page units
     * @return whether the edge is new or exactly agrees with earlier evidence
     */
    private static boolean observe(final Double[] boundaries, final int index, final double value) {
        if (boundaries[index] != null && boundaries[index] != value) {
            return false;
        }
        boundaries[index] = value;
        return true;
    }
}
