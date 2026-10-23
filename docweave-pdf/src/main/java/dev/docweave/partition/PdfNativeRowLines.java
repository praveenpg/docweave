package dev.docweave.partition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits provider table rows whose cells each hold two aligned physical source lines, for
 * {@link PdfNativeLayoutOptions.RowLines#SPLIT_ALIGNED_TWO_LINE_ROWS}.
 *
 * <p>Only geometry is read: visible glyph centres decide cell membership exactly as provider cell text assignment
 * does, whitespace glyphs are ignored, and {@link PdfGlyphLines} decides physical lines. Text is never classified.
 * Every returned table must still pass the caller's topology validation.
 */
final class PdfNativeRowLines {
    /** Largest baseline difference, in displayed units, between corresponding lines of different cells. */
    private static final double BASELINE = 2;

    /** Utility type. */
    private PdfNativeRowLines() {
    }

    /**
     * Splits every eligible row of every table; ineligible tables are returned unchanged and by identity.
     *
     * @param tables validated provider population
     * @param glyphs unclaimed native glyphs
     * @param geometry displayed source frame
     * @param xs displayed units per rendered horizontal pixel
     * @param ys displayed units per rendered vertical pixel
     * @param work shared page work and cancellation budget
     * @return same-order population; a changed identity marks split rows
     * @throws IOException if cancelled or the work ceiling is exhausted
     */
    static List<PdfTableLayoutProvider.Table> split(final List<PdfTableLayoutProvider.Table> tables,
            final List<PdfPageText.Glyph> glyphs, final PdfPageGeometry geometry, final double xs, final double ys,
            final PdfTableWork work) throws IOException {
        final var result = new ArrayList<PdfTableLayoutProvider.Table>();
        for (final var table : tables) {
            var current = table;
            for (int row = table.rows() - 1; row >= 0; row--) {
                work.step();
                final double cut = cut(table, row, glyphs, geometry, xs, ys, work);
                if (!Double.isNaN(cut)) {
                    current = apply(current, row, cut, work);
                }
            }
            result.add(current);
        }
        return List.copyOf(result);
    }

    /**
     * Returns the pixel row at which a row splits, or NaN when the row is ineligible.
     *
     * @param table original table
     * @param row row index in the original table
     * @param glyphs unclaimed native glyphs
     * @param geometry displayed frame
     * @param xs horizontal scale
     * @param ys vertical scale
     * @param work shared budget
     * @return split position in rendered pixels, or NaN
     * @throws IOException if cancelled or over budget
     */
    private static double cut(final PdfTableLayoutProvider.Table table, final int row,
            final List<PdfPageText.Glyph> glyphs, final PdfPageGeometry geometry, final double xs, final double ys,
            final PdfTableWork work) throws IOException {
        final var cells = new ArrayList<PdfTableLayoutProvider.Anchor>();
        for (final var anchor : table.anchors()) {
            work.step();
            if (anchor.row() < row && anchor.row() + anchor.rowSpan() > row
                    || anchor.row() == row && anchor.rowSpan() != 1) {
                return Double.NaN;
            }
            if (anchor.row() == row) {
                cells.add(anchor);
            }
        }
        final var firsts = new ArrayList<PdfLayoutItem.Bounds>();
        final var seconds = new ArrayList<PdfLayoutItem.Bounds>();
        for (final var cell : cells) {
            final var box = cell.bounds();
            final var members = new ArrayList<PdfPageText.Glyph>();
            for (final var glyph : glyphs) {
                work.step();
                final var center = geometry.center(glyph);
                final double x = center.x / xs;
                final double y = center.y / ys;
                // Whitespace glyphs, such as a producer's empty-cell spacers, are structure rather than lines.
                if (!glyph.text().isBlank() && x >= box.left() && x < box.right() && y >= box.top()
                        && y < box.bottom()) {
                    if (geometry.textRotation(glyph.direction()) != 0) {
                        return Double.NaN;
                    }
                    members.add(glyph);
                }
            }
            if (members.isEmpty()) {
                continue;
            }
            final var lines = PdfGlyphLines.displayed(members, geometry);
            if (lines.size() != 2) {
                return Double.NaN;
            }
            firsts.add(lines.get(0).box());
            seconds.add(lines.get(1).box());
        }
        if (firsts.size() < 2 || spread(firsts) > BASELINE || spread(seconds) > BASELINE) {
            return Double.NaN;
        }
        final double firstBottom = firsts.stream().mapToDouble(PdfLayoutItem.Bounds::bottom).max().orElseThrow();
        final double secondTop = seconds.stream().mapToDouble(PdfLayoutItem.Bounds::top).min().orElseThrow();
        if (firstBottom >= secondTop) {
            return Double.NaN;
        }
        final double cut = (firstBottom + secondTop) / 2 / ys;
        for (final var cell : cells) {
            if (cut <= cell.bounds().top() || cut >= cell.bounds().bottom()) {
                return Double.NaN;
            }
        }
        return cut;
    }

    /**
     * Returns the range of line bottoms.
     *
     * @param boxes line boxes
     * @return largest minus smallest bottom
     */
    private static double spread(final List<PdfLayoutItem.Bounds> boxes) {
        final double low = boxes.stream().mapToDouble(PdfLayoutItem.Bounds::bottom).min().orElseThrow();
        final double high = boxes.stream().mapToDouble(PdfLayoutItem.Bounds::bottom).max().orElseThrow();
        return high - low;
    }

    /**
     * Splits one row of a table at a pixel position, shifting later rows and widening declared headers.
     *
     * @param table table whose rows above {@code row} are unchanged so far
     * @param row row to split
     * @param cut split position in rendered pixels
     * @param work shared budget
     * @return new table
     * @throws IOException if cancelled or over budget
     */
    private static PdfTableLayoutProvider.Table apply(final PdfTableLayoutProvider.Table table, final int row,
            final double cut, final PdfTableWork work) throws IOException {
        final var anchors = new ArrayList<PdfTableLayoutProvider.Anchor>();
        for (final var anchor : table.anchors()) {
            work.step();
            final var box = anchor.bounds();
            if (anchor.row() == row) {
                anchors.add(new PdfTableLayoutProvider.Anchor(row, anchor.column(), 1, anchor.columnSpan(),
                        new PdfTableLayoutProvider.Box(box.left(), box.top(), box.right(), cut)));
                anchors.add(new PdfTableLayoutProvider.Anchor(row + 1, anchor.column(), 1, anchor.columnSpan(),
                        new PdfTableLayoutProvider.Box(box.left(), cut, box.right(), box.bottom())));
            } else if (anchor.row() > row) {
                anchors.add(new PdfTableLayoutProvider.Anchor(
                        anchor.row() + 1, anchor.column(), anchor.rowSpan(), anchor.columnSpan(), box));
            } else {
                anchors.add(anchor);
            }
        }
        return new PdfTableLayoutProvider.Table(table.rows() + 1, table.columns(), table.bounds(), anchors,
                table.provenance(), table.headerRows() + (row < table.headerRows() ? 1 : 0));
    }
}
