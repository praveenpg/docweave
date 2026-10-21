package dev.docweave.partition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** Conservative, explicit source inference above validated native-provider table bounds. */
final class PdfNativeLeadingRow {
    /** One complete ASCII numeric token; paired accounting parentheses retain their source characters. */
    private static final Pattern NUMBER =
            Pattern.compile("(?:[+-]?[0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?%?|\\([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?\\))");

    /** Utility type; candidates and source positions remain page-confined. */
    private PdfNativeLeadingRow() {
    }

    /**
     * Extends each eligible table once, preserving original anchors and source provenance.
     * Raw layouts must already pass topology validation; the caller revalidates the returned population.
     * Conflicting extensions retain the original table rather than stealing another table's text.
     * @param tables validated raw provider population
     * @param glyphs exactly unclaimed native positions
     * @param geometry displayed source frame
     * @param xs displayed units per rendered horizontal pixel
     * @param ys displayed units per rendered vertical pixel
     * @param preferred previously accepted native table envelopes
     * @param work shared page geometry/cancellation budget
     * @return immutable same-order population; changed table identity marks inferred rows
     * @throws IOException if cancelled or the shared work ceiling is exhausted
     */
    static List<PdfTableLayoutProvider.Table> extend(final List<PdfTableLayoutProvider.Table> tables,
            final List<PdfPageText.Glyph> glyphs, final PdfPageGeometry geometry, final double xs, final double ys,
            final List<PdfLayoutItem> preferred, final PdfTableWork work) throws IOException {
        final var result = new ArrayList<PdfTableLayoutProvider.Table>();
        for (final var table : tables) {
            work.step();
            final var candidate = candidate(table, glyphs, geometry, xs, ys, work);
            result.add(candidate != table && conflicts(candidate, table, tables, preferred, xs, ys, work) ? table
                                                                                                          : candidate);
        }
        return List.copyOf(result);
    }

    /**
     * Tests exactly one first-row-height source band without changing original geometry.
     * @param table validated provider table
     * @param glyphs unclaimed source glyphs
     * @param geometry shared source frame
     * @param xs horizontal source/pixel scale
     * @param ys vertical source/pixel scale
     * @param work shared bounded counter
     * @return original table when ambiguous, otherwise one inferred leading row
     * @throws IOException if cancelled or the work budget is exceeded
     */
    private static PdfTableLayoutProvider.Table candidate(final PdfTableLayoutProvider.Table table,
            final List<PdfPageText.Glyph> glyphs, final PdfPageGeometry geometry, final double xs, final double ys,
            final PdfTableWork work) throws IOException {
        final var first = new ArrayList<PdfTableLayoutProvider.Anchor>();
        for (final var anchor : table.anchors()) {
            work.step();
            if (anchor.row() == 0) {
                first.add(anchor);
            }
        }
        first.sort(Comparator.comparingInt(PdfTableLayoutProvider.Anchor::column));
        if (table.headerRows() != 0 || table.columns() < 3 || first.size() != table.columns()
                || first.stream().anyMatch(a -> a.rowSpan() != 1 || a.columnSpan() != 1)) {
            return table;
        }
        final double top = table.bounds().top();
        final double height = first.getFirst().bounds().bottom() - top;
        if (first.stream().anyMatch(
                    a -> a.bounds().top() != top || a.bounds().bottom() != first.getFirst().bounds().bottom())) {
            return table;
        }
        final var members = new ArrayList<List<PdfPageText.Glyph>>();
        for (int col = 0; col < table.columns(); col++) {
            members.add(new ArrayList<>());
        }
        double minBaseline = Double.POSITIVE_INFINITY, maxBaseline = Double.NEGATIVE_INFINITY;
        double minTop = top;
        int count = 0;
        for (final var glyph : glyphs) {
            work.step();
            if (glyph.text().isBlank()) {
                continue;
            }
            final var center = geometry.center(glyph);
            final double x = center.x / xs, y = center.y / ys;
            if (x < table.bounds().left() || x >= table.bounds().right() || y < Math.max(0, top - height) || y >= top) {
                continue;
            }
            if (geometry.textRotation(glyph.direction()) != 0) {
                return table;
            }
            int column = -1;
            for (int col = 0; col < first.size(); col++) {
                work.step();
                final var box = first.get(col).bounds();
                if (x >= box.left() && x < box.right()) {
                    column = col;
                    break;
                }
            }
            if (column < 0 || glyph.x() / xs < first.get(column).bounds().left()
                    || (glyph.x() + glyph.width()) / xs > first.get(column).bounds().right() || glyph.y() / ys > top
                    || glyph.y() - glyph.height() < 0) {
                return table;
            }
            members.get(column).add(glyph);
            count++;
            minBaseline = Math.min(minBaseline, glyph.y());
            maxBaseline = Math.max(maxBaseline, glyph.y());
            minTop = Math.min(minTop, (glyph.y() - glyph.height()) / ys);
        }
        if (count == 0) {
            return table;
        }
        if (maxBaseline - minBaseline > 2) {
            return table;
        }
        final var values = new ArrayList<String>();
        for (final var cell : members) {
            work.step();
            for (final var ignored : cell) {
                work.step();
            }
            final var lines = PdfGlyphLines.displayed(cell, geometry);
            if (lines.size() != 1) {
                return table;
            }
            values.add(lines.getFirst().text());
        }
        if (!values.getFirst().codePoints().anyMatch(Character::isLetter)
                || values.subList(1, values.size()).stream().anyMatch(v -> !NUMBER.matcher(v).matches())) {
            return table;
        }
        final double newTop = Math.max(0, minTop - 1);
        final var anchors = new ArrayList<PdfTableLayoutProvider.Anchor>();
        for (final var cell : first) {
            work.step();
            final var box = cell.bounds();
            anchors.add(new PdfTableLayoutProvider.Anchor(
                    0, cell.column(), 1, 1, new PdfTableLayoutProvider.Box(box.left(), newTop, box.right(), top)));
        }
        for (final var cell : table.anchors()) {
            work.step();
            anchors.add(new PdfTableLayoutProvider.Anchor(
                    cell.row() + 1, cell.column(), cell.rowSpan(), cell.columnSpan(), cell.bounds()));
        }
        return new PdfTableLayoutProvider.Table(table.rows() + 1, table.columns(),
                new PdfTableLayoutProvider.Box(
                        table.bounds().left(), newTop, table.bounds().right(), table.bounds().bottom()),
                anchors, table.provenance());
    }

    /**
     * Rejects added geometry intersecting any other accepted or proposed table.
     * @param candidate expanded table
     * @param original unchanged raw table identity
     * @param tables complete raw provider population
     * @param preferred accepted native envelopes
     * @param xs horizontal frame scale
     * @param ys vertical frame scale
     * @param work shared bounded counter
     * @return whether the added envelope would conflict
     * @throws IOException if cancelled or the work ceiling is exceeded
     */
    private static boolean conflicts(final PdfTableLayoutProvider.Table candidate,
            final PdfTableLayoutProvider.Table original, final List<PdfTableLayoutProvider.Table> tables,
            final List<PdfLayoutItem> preferred, final double xs, final double ys, final PdfTableWork work)
            throws IOException {
        for (final var other : tables) {
            work.step();
            if (other != original && PdfTableLayouts.overlaps(candidate.bounds(), other.bounds())) {
                return true;
            }
        }
        final var bounds = candidate.bounds();
        for (final var other : preferred) {
            work.step();
            final var box = other.box();
            if (bounds.left() * xs < box.right() && bounds.right() * xs > box.left() && bounds.top() * ys < box.bottom()
                    && bounds.bottom() * ys > box.top()) {
                return true;
            }
        }
        return false;
    }
}
