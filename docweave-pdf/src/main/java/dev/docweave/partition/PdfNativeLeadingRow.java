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

    /**
     * One leading generic currency glyph and its following gap, used only by the section-row numeric check.
     * A currency mark commonly sits on its own displayed run, separated from its digits by a column-wide gap;
     * this strips that mark before matching {@link #NUMBER}, without widening the shared numeric-row pattern.
     */
    private static final Pattern CURRENCY_PREFIX = Pattern.compile("^[$£€]\\s*");

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
     * @param sections whether an aligned colon-terminated section label is eligible
     * @param preferred previously accepted native table envelopes
     * @param work shared page geometry/cancellation budget
     * @return immutable same-order population; changed table identity marks inferred rows
     * @throws IOException if cancelled or the shared work ceiling is exhausted
     */
    static List<PdfTableLayoutProvider.Table> extend(final List<PdfTableLayoutProvider.Table> tables,
            final List<PdfPageText.Glyph> glyphs, final PdfPageGeometry geometry, final double xs, final double ys,
            final List<PdfLayoutItem> preferred, final boolean sections, final PdfTableWork work) throws IOException {
        final var result = new ArrayList<PdfTableLayoutProvider.Table>();
        for (final var table : tables) {
            work.step();
            final var candidate = candidate(table, glyphs, geometry, xs, ys, sections, work);
            result.add(candidate != table && conflicts(candidate, table, tables, preferred, xs, ys, work) ? table
                                                                                                          : candidate);
        }
        return List.copyOf(result);
    }

    /**
     * Retains the numeric-only internal entry point.
     * @param tables validated provider tables
     * @param glyphs unclaimed native glyphs
     * @param geometry displayed page frame
     * @param xs horizontal frame scale
     * @param ys vertical frame scale
     * @param preferred accepted native table envelopes
     * @param work shared bounded work counter
     * @return numeric-only extensions in provider order
     * @throws IOException if interrupted or the work ceiling is exceeded
     */
    static List<PdfTableLayoutProvider.Table> extend(final List<PdfTableLayoutProvider.Table> tables,
            final List<PdfPageText.Glyph> glyphs, final PdfPageGeometry geometry, final double xs, final double ys,
            final List<PdfLayoutItem> preferred, final PdfTableWork work) throws IOException {
        return extend(tables, glyphs, geometry, xs, ys, preferred, false, work);
    }

    /**
     * Tests exactly one first-row-height source band without changing original geometry.
     * @param table validated provider table
     * @param glyphs unclaimed source glyphs
     * @param geometry shared source frame
     * @param xs horizontal source/pixel scale
     * @param ys vertical source/pixel scale
     * @param sections whether section inference was explicitly selected
     * @param work shared bounded counter
     * @return original table when ambiguous, otherwise one inferred leading row
     * @throws IOException if cancelled or the work budget is exceeded
     */
    private static PdfTableLayoutProvider.Table candidate(final PdfTableLayoutProvider.Table table,
            final List<PdfPageText.Glyph> glyphs, final PdfPageGeometry geometry, final double xs, final double ys,
            final boolean sections, final PdfTableWork work) throws IOException {
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
            if (lines.size() > 1) {
                return table;
            }
            values.add(lines.isEmpty() ? "" : lines.getFirst().text());
        }
        final var sectionBox = new PdfTableLayoutProvider.Box(
                first.getFirst().bounds().left(), Math.max(0, top - height), first.getFirst().bounds().right(), top);
        final boolean section = sections && values.getFirst().endsWith(":")
                                && values.subList(1, values.size()).stream().allMatch(String::isEmpty)
                                && supportsSection(first, sectionBox, minBaseline, glyphs, geometry, xs, ys, work);
        if (!values.getFirst().codePoints().anyMatch(Character::isLetter)
                || !section && values.subList(1, values.size()).stream().anyMatch(v -> !NUMBER.matcher(v).matches())) {
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
     * Requires a matching left-aligned label above a complete numeric first body row.
     * A colon alone is insufficient: centered headings, empty bodies and incomplete numeric cells stay text.
     * A body cell may still legitimately span more than one displayed run, for example a leading currency
     * mark separated from its digits by a column-wide gap; its runs are joined in reading order before the
     * label/numeric check, matching how an established provider cell's own text is already assembled elsewhere.
     * Left alignment compares each line's true run origin, including a leading indentation space, not only its
     * first visible character; excluding that space would understate genuine alignment between a section label
     * and an indented body label sharing one left margin.
     * Source characters are never classified by financial vocabulary or reference strings.
     * @param first uniform first-row anchors
     * @param sectionBox displayed geometry of the proposed section label's column
     * @param sectionBaseline source baseline of the proposed label
     * @param glyphs unclaimed source positions
     * @param geometry displayed frame
     * @param xs horizontal frame scale
     * @param ys vertical frame scale
     * @param work shared work counter
     * @return whether the existing body supplies unambiguous alignment and numeric support
     * @throws IOException if interrupted or bounded work is exhausted
     */
    private static boolean supportsSection(final List<PdfTableLayoutProvider.Anchor> first,
            final PdfTableLayoutProvider.Box sectionBox, final double sectionBaseline,
            final List<PdfPageText.Glyph> glyphs, final PdfPageGeometry geometry, final double xs, final double ys,
            final PdfTableWork work) throws IOException {
        final var cells = new ArrayList<List<PdfPageText.Glyph>>();
        for (final var ignored : first) {
            cells.add(new ArrayList<>());
        }
        double minBaseline = Double.POSITIVE_INFINITY, maxBaseline = Double.NEGATIVE_INFINITY;
        for (final var glyph : glyphs) {
            work.step();
            if (glyph.text().isBlank()) {
                continue;
            }
            final var center = geometry.center(glyph);
            final double x = center.x / xs, y = center.y / ys;
            for (int column = 0; column < first.size(); column++) {
                work.step();
                final var box = first.get(column).bounds();
                if (x >= box.left() && x < box.right() && y >= box.top() && y < box.bottom()) {
                    if (geometry.textRotation(glyph.direction()) != 0 || glyph.x() / xs < box.left()
                            || (glyph.x() + glyph.width()) / xs > box.right()) {
                        return false;
                    }
                    cells.get(column).add(glyph);
                    minBaseline = Math.min(minBaseline, glyph.y());
                    maxBaseline = Math.max(maxBaseline, glyph.y());
                    break;
                }
            }
        }
        if (maxBaseline - minBaseline > 2) {
            return false;
        }
        for (int column = 0; column < cells.size(); column++) {
            final var cell = cells.get(column);
            for (final var ignored : cell) {
                work.step();
            }
            final var lines = PdfGlyphLines.displayed(cell, geometry);
            final String joined = String.join(" ", lines.stream().map(PdfLayoutItem::text).toList());
            if (lines.isEmpty()
                    || (column == 0 ? lines.size() != 1 || !joined.codePoints().anyMatch(Character::isLetter)
                                    : !NUMBER.matcher(CURRENCY_PREFIX.matcher(joined).replaceFirst("")).matches())) {
                return false;
            }
        }
        final double labelLeft = leftEdge(glyphs, sectionBox, sectionBaseline, geometry, xs, ys, work);
        final double bodyLeft = leftEdge(glyphs, first.getFirst().bounds(), minBaseline, geometry, xs, ys, work);
        return Math.abs(labelLeft - bodyLeft) <= 2;
    }

    /**
     * Finds the leftmost native glyph origin inside one displayed box, including whitespace glyphs.
     * Whitespace is part of a line's true run origin (for example a leading indentation space); excluding it
     * would compare an unindented candidate label against a body label's first visible, non-blank character
     * rather than its actual run start, understating genuine alignment.
     * @param glyphs source positions to search
     * @param box displayed containment box
     * @param baseline selected nonblank source baseline; unrelated whitespace is excluded
     * @param geometry displayed frame
     * @param xs horizontal frame scale
     * @param ys vertical frame scale
     * @param work shared work counter
     * @return leftmost native x, or positive infinity if no glyph with text falls inside the box
     * @throws IOException if interrupted
     */
    private static double leftEdge(final List<PdfPageText.Glyph> glyphs, final PdfTableLayoutProvider.Box box,
            final double baseline, final PdfPageGeometry geometry, final double xs, final double ys,
            final PdfTableWork work) throws IOException {
        double left = Double.POSITIVE_INFINITY;
        for (final var glyph : glyphs) {
            work.step();
            if (glyph.text().isEmpty() || geometry.textRotation(glyph.direction()) != 0
                    || Math.abs(glyph.y() - baseline) > 2) {
                continue;
            }
            final var center = geometry.center(glyph);
            final double x = center.x / xs, y = center.y / ys;
            if (x >= box.left() && x < box.right() && y >= box.top() && y < box.bottom()) {
                left = Math.min(left, glyph.x());
            }
        }
        return left;
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
