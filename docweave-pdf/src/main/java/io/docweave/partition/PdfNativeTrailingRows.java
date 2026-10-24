package io.docweave.partition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Conservative, explicit source inference below validated native-provider table bounds.
 * Membership is supported only by the table's own repeated whitespace cell lattice: producers that paint an
 * explicit space glyph for every empty or padding cell repeat those glyphs at fixed column positions on every
 * table row, including visually blank rows, and stop repeating them where the table ends. No vocabulary,
 * punctuation or numeric grammar is consulted, and no OCR or model rerun occurs.
 */
final class PdfNativeTrailingRows {
    /**
     * Maximum horizontal difference, in displayed units, for two whitespace glyphs to share a lattice position.
     * Producers repeat identical coordinates for one table column; this only absorbs floating-point transform
     * noise and is far below a single glyph width, so it is not a layout similarity threshold.
     */
    private static final double POSITION_TOLERANCE = 0.1;

    /**
     * Maximum baseline difference, in displayed units, for glyphs to share one source line. This matches the
     * common-baseline tolerance used by the leading-row inference.
     */
    private static final double BASELINE_TOLERANCE = 2;

    /** Minimum number of lattice positions; a single repeated space is too weak to identify table structure. */
    private static final int MIN_LATTICE_POSITIONS = 2;

    /** Utility type; candidates and source positions remain page-confined. */
    private PdfNativeTrailingRows() {
    }

    /**
     * Extends each eligible table downward, preserving original anchors, row indices and provenance.
     * Raw layouts must already pass topology validation; the caller revalidates the returned population.
     * Conflicting extensions retain the original table rather than stealing another table's text.
     * @param tables validated provider population, possibly already carrying an inferred leading row
     * @param glyphs exactly unclaimed native positions
     * @param geometry displayed source frame
     * @param xs displayed units per rendered horizontal pixel
     * @param ys displayed units per rendered vertical pixel
     * @param preferred previously accepted native table envelopes
     * @param work shared page geometry/cancellation budget
     * @return immutable same-order population; a changed table identity marks inferred rows
     * @throws IOException if cancelled or the shared work ceiling is exhausted
     */
    static List<PdfTableLayoutProvider.Table> extend(final List<PdfTableLayoutProvider.Table> tables,
            final List<PdfPageText.Glyph> glyphs, final PdfPageGeometry geometry, final double xs, final double ys,
            final List<PdfLayoutItem> preferred, final PdfTableWork work) throws IOException {
        final var result = new ArrayList<PdfTableLayoutProvider.Table>();
        for (final var table : tables) {
            work.step();
            final var candidate = candidate(table, glyphs, geometry, xs, ys, work);
            result.add(
                    candidate != table
                                    && PdfNativeLeadingRow.conflicts(candidate, table, tables, preferred, xs, ys, work)
                            ? table
                            : candidate);
        }
        return List.copyOf(result);
    }

    /**
     * Derives the lattice, scans source lines below the table and builds the inferred rows.
     * @param table validated provider table
     * @param glyphs unclaimed source glyphs
     * @param geometry shared source frame
     * @param xs horizontal source/pixel scale
     * @param ys vertical source/pixel scale
     * @param work shared bounded counter
     * @return original table when unsupported or ambiguous, otherwise the table with appended rows
     * @throws IOException if cancelled or the work budget is exceeded
     */
    private static PdfTableLayoutProvider.Table candidate(final PdfTableLayoutProvider.Table table,
            final List<PdfPageText.Glyph> glyphs, final PdfPageGeometry geometry, final double xs, final double ys,
            final PdfTableWork work) throws IOException {
        final var last = new ArrayList<PdfTableLayoutProvider.Anchor>();
        for (final var anchor : table.anchors()) {
            work.step();
            if (anchor.row() + anchor.rowSpan() == table.rows()) {
                last.add(anchor);
            }
        }
        last.sort(Comparator.comparingInt(PdfTableLayoutProvider.Anchor::column));
        if (table.columns() < 3 || last.size() != table.columns()
                || last.stream().anyMatch(a -> a.rowSpan() != 1 || a.columnSpan() != 1)) {
            return table;
        }
        final var lattice = lattice(table, glyphs, geometry, xs, ys, work);
        if (lattice.size() < MIN_LATTICE_POSITIONS) {
            return table;
        }
        final var lines = linesBelow(table, glyphs, geometry, xs, ys, work);
        final var visible = new ArrayList<List<PdfPageText.Glyph>>();
        for (final var line : lines) {
            work.step();
            if (!continues(line, lattice, table, geometry, xs, work)) {
                break;
            }
            if (line.stream().anyMatch(glyph -> !glyph.text().isBlank())) {
                visible.add(line);
            }
        }
        if (visible.isEmpty()) {
            return table;
        }
        final double pageBottom = geometry.height() / ys;
        double previousBottom = table.bounds().bottom();
        final var anchors = new ArrayList<>(table.anchors());
        for (int index = 0; index < visible.size(); index++) {
            final var row =
                    row(visible.get(index), last, table.rows() + index, previousBottom, pageBottom, xs, ys, work);
            if (row.isEmpty()) {
                return table;
            }
            anchors.addAll(row);
            previousBottom = row.getFirst().bounds().bottom();
        }
        return new PdfTableLayoutProvider.Table(table.rows() + visible.size(), table.columns(),
                new PdfTableLayoutProvider.Box(
                        table.bounds().left(), table.bounds().top(), table.bounds().right(), previousBottom),
                anchors, table.provenance(), table.headerRows());
    }

    /**
     * Intersects the whitespace glyph positions of every nonempty provider row.
     * Each row band uses that row's single-row anchors; rows with no such anchors or no glyphs do not constrain
     * the lattice, while any nonempty row lacking a position removes it.
     * @param table validated provider table
     * @param glyphs unclaimed source positions
     * @param geometry displayed frame
     * @param xs horizontal frame scale
     * @param ys vertical frame scale
     * @param work shared work counter
     * @return displayed x centers repeated by every nonempty row; empty when no lattice exists
     * @throws IOException if interrupted or bounded work is exhausted
     */
    private static List<Double> lattice(final PdfTableLayoutProvider.Table table, final List<PdfPageText.Glyph> glyphs,
            final PdfPageGeometry geometry, final double xs, final double ys, final PdfTableWork work)
            throws IOException {
        List<Double> lattice = null;
        for (int row = 0; row < table.rows(); row++) {
            double top = Double.POSITIVE_INFINITY;
            double bottom = Double.NEGATIVE_INFINITY;
            for (final var anchor : table.anchors()) {
                work.step();
                if (anchor.row() == row && anchor.rowSpan() == 1) {
                    top = Math.min(top, anchor.bounds().top());
                    bottom = Math.max(bottom, anchor.bounds().bottom());
                }
            }
            if (top >= bottom) {
                continue;
            }
            final var spaces = new ArrayList<Double>();
            boolean occupied = false;
            for (final var glyph : glyphs) {
                work.step();
                final var center = geometry.center(glyph);
                final double x = center.x / xs;
                final double y = center.y / ys;
                if (x < table.bounds().left() || x >= table.bounds().right() || y < top || y >= bottom) {
                    continue;
                }
                occupied = true;
                if (whitespace(glyph) && geometry.textRotation(glyph.direction()) == 0) {
                    spaces.add((double) center.x);
                }
            }
            if (!occupied) {
                continue;
            }
            if (lattice == null) {
                lattice = distinct(spaces, work);
            } else {
                final var retained = new ArrayList<Double>();
                for (final double position : lattice) {
                    if (repeats(spaces, position, work)) {
                        retained.add(position);
                    }
                }
                lattice = retained;
            }
            if (lattice.size() < MIN_LATTICE_POSITIONS) {
                return List.of();
            }
        }
        return lattice == null ? List.of() : List.copyOf(lattice);
    }

    /**
     * Groups glyphs whose centers lie below the table into source lines, in increasing baseline order.
     * Every glyph below the table is considered, including those outside its horizontal extent, so that a
     * wider paragraph is recognized and stops the scan rather than being partially absorbed.
     * @param table validated provider table
     * @param glyphs unclaimed source positions
     * @param geometry displayed frame
     * @param xs horizontal frame scale
     * @param ys vertical frame scale
     * @param work shared work counter
     * @return source lines, each a nonempty list of glyphs sharing one baseline
     * @throws IOException if interrupted or bounded work is exhausted
     */
    private static List<List<PdfPageText.Glyph>> linesBelow(final PdfTableLayoutProvider.Table table,
            final List<PdfPageText.Glyph> glyphs, final PdfPageGeometry geometry, final double xs, final double ys,
            final PdfTableWork work) throws IOException {
        final var below = new ArrayList<PdfPageText.Glyph>();
        for (final var glyph : glyphs) {
            work.step();
            if (!glyph.text().isEmpty() && geometry.center(glyph).y / ys >= table.bounds().bottom()) {
                below.add(glyph);
            }
        }
        below.sort(Comparator.comparingDouble(PdfPageText.Glyph::y));
        final var lines = new ArrayList<List<PdfPageText.Glyph>>();
        List<PdfPageText.Glyph> line = null;
        double start = 0;
        for (final var glyph : below) {
            work.step();
            if (line == null || glyph.y() - start > BASELINE_TOLERANCE) {
                line = new ArrayList<>();
                lines.add(line);
                start = glyph.y();
            }
            line.add(glyph);
        }
        return lines;
    }

    /**
     * Tests whether one source line continues the table: upright, horizontally contained and repeating
     * every lattice position.
     * @param line glyphs sharing one baseline
     * @param lattice table whitespace positions
     * @param table validated provider table
     * @param geometry displayed frame
     * @param xs horizontal frame scale
     * @param work shared work counter
     * @return whether the line belongs to the same whitespace cell lattice
     * @throws IOException if interrupted or bounded work is exhausted
     */
    private static boolean continues(final List<PdfPageText.Glyph> line, final List<Double> lattice,
            final PdfTableLayoutProvider.Table table, final PdfPageGeometry geometry, final double xs,
            final PdfTableWork work) throws IOException {
        final var spaces = new ArrayList<Double>();
        for (final var glyph : line) {
            work.step();
            final double x = geometry.center(glyph).x;
            if (geometry.textRotation(glyph.direction()) != 0 || x / xs < table.bounds().left()
                    || x / xs >= table.bounds().right()) {
                return false;
            }
            if (whitespace(glyph)) {
                spaces.add(x);
            }
        }
        for (final double position : lattice) {
            if (!repeats(spaces, position, work)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Assigns one visible source line to the last provider row's columns and creates its anchors.
     * Each visible native run, as grouped by {@link PdfGlyphLines#lines(List)}, must fit inside a single column,
     * so a label crossing a column boundary retains the provider table instead of being split between cells.
     * @param line glyphs sharing one baseline, at least one visible
     * @param columns last-row unmerged anchors in column order
     * @param row logical row index of the new row
     * @param previousBottom bottom edge of the preceding row, in pixels
     * @param pageBottom displayed page height, in pixels
     * @param xs horizontal frame scale
     * @param ys vertical frame scale
     * @param work shared work counter
     * @return one anchor per column, or empty when a visible run does not fit one column
     * @throws IOException if interrupted or bounded work is exhausted
     */
    private static List<PdfTableLayoutProvider.Anchor> row(final List<PdfPageText.Glyph> line,
            final List<PdfTableLayoutProvider.Anchor> columns, final int row, final double previousBottom,
            final double pageBottom, final double xs, final double ys, final PdfTableWork work) throws IOException {
        final var visible = new ArrayList<PdfPageText.Glyph>();
        for (final var glyph : line) {
            work.step();
            if (!glyph.text().isBlank()) {
                visible.add(glyph);
            }
        }
        double top = Double.POSITIVE_INFINITY;
        double baseline = Double.NEGATIVE_INFINITY;
        for (final var run : PdfGlyphLines.lines(visible)) {
            final double left = run.box().left() / xs;
            final double right = run.box().right() / xs;
            boolean fits = false;
            for (final var column : columns) {
                work.step();
                if (left >= column.bounds().left() && right <= column.bounds().right()) {
                    fits = true;
                    break;
                }
            }
            if (!fits) {
                return List.of();
            }
            top = Math.min(top, run.box().top() / ys);
            baseline = Math.max(baseline, run.box().bottom() / ys);
        }
        final double rowTop = Math.max(previousBottom, top - 1);
        final double rowBottom = Math.min(pageBottom, baseline + 1);
        if (rowBottom <= rowTop) {
            return List.of();
        }
        final var anchors = new ArrayList<PdfTableLayoutProvider.Anchor>();
        for (final var column : columns) {
            work.step();
            anchors.add(new PdfTableLayoutProvider.Anchor(row, column.column(), 1, 1,
                    new PdfTableLayoutProvider.Box(
                            column.bounds().left(), rowTop, column.bounds().right(), rowBottom)));
        }
        return anchors;
    }

    /**
     * Identifies a painted whitespace glyph, as opposed to an absent glyph or a visible character.
     * @param glyph source position
     * @return whether the glyph carries only whitespace text
     */
    private static boolean whitespace(final PdfPageText.Glyph glyph) {
        return !glyph.text().isEmpty() && glyph.text().isBlank();
    }

    /**
     * Collapses positions repeated within one row to distinct lattice positions.
     * @param positions displayed x centers
     * @param work shared work counter
     * @return positions with near-duplicates removed, in first-seen order
     * @throws IOException if interrupted or bounded work is exhausted
     */
    private static List<Double> distinct(final List<Double> positions, final PdfTableWork work) throws IOException {
        final var result = new ArrayList<Double>();
        for (final double position : positions) {
            if (!repeats(result, position, work)) {
                result.add(position);
            }
        }
        return result;
    }

    /**
     * Tests whether a set of whitespace positions contains one lattice position.
     * @param positions displayed x centers on one row or line
     * @param position lattice position
     * @param work shared work counter
     * @return whether a position lies within the producer coordinate tolerance
     * @throws IOException if interrupted or bounded work is exhausted
     */
    private static boolean repeats(final List<Double> positions, final double position, final PdfTableWork work)
            throws IOException {
        for (final double candidate : positions) {
            work.step();
            if (Math.abs(candidate - position) <= POSITION_TOLERANCE) {
                return true;
            }
        }
        return false;
    }
}
