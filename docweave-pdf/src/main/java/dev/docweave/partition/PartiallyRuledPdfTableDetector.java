package dev.docweave.partition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Opt-in native strategy for a full-width top rule, matching segmented separator/lower rules,
 * up to eight header baselines and complete uniformly pitched numeric body rows.
 * Inferred header spans follow source run extents; no semantic merge or closed-grid claim is made.
 */
final class PartiallyRuledPdfTableDetector implements PdfNativeTableDetector {
    /** Existing narrow ASCII numeric syntax; no locale, footnote or missing-value repair. */
    private static final Pattern NUMBER = Pattern.compile("[+-]?[0-9]+(?:[.,][0-9]+)*%?");
    /** Existing native geometric tolerance in unscaled source units. */
    private static final float SNAP = 0.5f;
    /** Immutable caller table limits. */
    private final TableLimits limits;
    /** Displayed crop/rotation frame. */
    private final PdfPageGeometry geometry;

    /**
     * Captures immutable policies; all mutable state remains local to detection.
     * @param limits structured-table limits
     * @param geometry displayed source geometry
     */
    PartiallyRuledPdfTableDetector(final TableLimits limits, final PdfPageGeometry geometry) {
        this.limits = limits;
        this.geometry = geometry;
    }

    /**
     * One fixed-first-ordinate rule with original paint intervals, without synthetic joins.
     * @param y source ordinate
     * @param spans original sorted segments
     */
    private record Rule(float y, List<PdfPageGraphics.Segment> spans) {}

    /** {@inheritDoc} */
    @Override
    public List<PdfInferredTable> detect(final List<PdfPageText.Glyph> glyphs,
            final List<PdfPageGraphics.Segment> segments, final List<PdfPageGraphics.Image> images,
            final PdfTableWork work) throws IOException {
        final var rules = rules(segments, work);
        final var candidates = new ArrayList<PdfInferredTable>();
        for (int index = 0; index + 2 < rules.size(); index++) {
            work.step();
            final var top = rules.get(index);
            final var separator = rules.get(index + 1);
            final var bottom = rules.get(index + 2);
            final var xs = partitions(separator, work);
            final var lower = partitions(bottom, work);
            if (xs.size() < 3 || !xs.equals(lower) || top.spans().size() != 1 || separator.y() - top.y() < 12
                    || separator.y() - top.y() > 96 || bottom.y() - separator.y() < 16
                    || bottom.y() - separator.y() > 800) {
                continue;
            }
            final var upper = top.spans().getFirst();
            if (Math.abs(Math.min(upper.x1(), upper.x2()) - xs.getFirst()) > SNAP
                    || Math.abs(Math.max(upper.x1(), upper.x2()) - xs.getLast()) > SNAP) {
                continue;
            }
            final var frame = new PdfLayoutItem.Bounds(xs.getFirst(), top.y(), xs.getLast(), bottom.y());
            final var table = accept(glyphs, segments, images, frame, separator.y(), xs, work);
            if (table != null) {
                candidates.add(table);
            }
        }
        final boolean[] ambiguous = new boolean[candidates.size()];
        for (int first = 0; first < candidates.size(); first++) {
            for (int second = first + 1; second < candidates.size(); second++) {
                work.step();
                if (intersects(candidates.get(first).frame(), candidates.get(second).frame())) {
                    ambiguous[first] = true;
                    ambiguous[second] = true;
                }
            }
        }
        final var result = new ArrayList<PdfInferredTable>();
        for (int index = 0; index < candidates.size(); index++) {
            work.step();
            if (!ambiguous[index]) {
                result.add(candidates.get(index));
            }
        }
        return List.copyOf(result);
    }

    /**
     * Groups horizontal source intervals without joining distinct ordinates or overlapping paint.
     * @param segments original rules
     * @param work page counter
     * @return ascending fixed-baseline groups
     * @throws IOException if cancelled or over budget
     */
    private static List<Rule> rules(final List<PdfPageGraphics.Segment> segments, final PdfTableWork work)
            throws IOException {
        final var sorted = new ArrayList<PdfPageGraphics.Segment>();
        for (final var segment : segments) {
            work.step();
            if (Math.abs(segment.y2() - segment.y1()) < SNAP && Math.abs(segment.x2() - segment.x1()) >= 4) {
                sorted.add(segment);
            }
        }
        sorted.sort(Comparator.comparingDouble(PdfPageGraphics.Segment::y1));
        final var result = new ArrayList<Rule>();
        for (final var segment : sorted) {
            work.step();
            if (result.isEmpty() || segment.y1() - result.getLast().y() > SNAP) {
                result.add(new Rule(segment.y1(), new ArrayList<>()));
            }
            result.getLast().spans().add(segment);
        }
        for (final var rule : result) {
            rule.spans().sort(Comparator.comparingDouble(segment -> Math.min(segment.x1(), segment.x2())));
        }
        return result;
    }

    /**
     * Accepts two to32 sequential source intervals with at most half-unit joins and widths at least12.
     * @param rule original source group
     * @param work shared counter
     * @return immutable boundary list, empty on ambiguity
     * @throws IOException if cancelled or over budget
     */
    private static List<Float> partitions(final Rule rule, final PdfTableWork work) throws IOException {
        if (rule.spans().size() < 2 || rule.spans().size() > 32) {
            return List.of();
        }
        final var xs = new ArrayList<Float>();
        float previous = Float.NaN;
        for (final var span : rule.spans()) {
            work.step();
            final float left = Math.min(span.x1(), span.x2());
            final float right = Math.max(span.x1(), span.x2());
            if (right - left < 12 || !Float.isNaN(previous) && Math.abs(left - previous) > SNAP) {
                return List.of();
            }
            if (xs.isEmpty()) {
                xs.add(left);
            }
            xs.add(right);
            previous = right;
        }
        return List.copyOf(xs);
    }

    /**
     * Validates every intersecting glyph and barrier before assigning complete body cells and header spans.
     * @param glyphs remaining native source
     * @param segments painted barriers
     * @param images raster barriers
     * @param frame supporting rule bounds
     * @param separator source header/body rule
     * @param xs supported columns
     * @param work shared page counter
     * @return accepted table or null, leaving rejected ownership untouched
     * @throws IOException if cancelled or table/work limits fail
     */
    private PdfInferredTable accept(final List<PdfPageText.Glyph> glyphs, final List<PdfPageGraphics.Segment> segments,
            final List<PdfPageGraphics.Image> images, final PdfLayoutItem.Bounds frame, final float separator,
            final List<Float> xs, final PdfTableWork work) throws IOException {
        for (final var image : images) {
            work.step();
            if (intersects(frame, new PdfLayoutItem.Bounds(image.x(), image.y(), image.x() + image.width(),
                                          image.y() + image.height()))) {
                return null;
            }
        }
        for (final var segment : segments) {
            work.step();
            final var box =
                    new PdfLayoutItem.Bounds(Math.min(segment.x1(), segment.x2()), Math.min(segment.y1(), segment.y2()),
                            Math.max(segment.x1(), segment.x2()), Math.max(segment.y1(), segment.y2()));
            if (!intersects(frame, box)) {
                continue;
            }
            final boolean horizontal =
                    Math.abs(segment.y2() - segment.y1()) < SNAP
                    && Math.min(Math.abs(segment.y1() - frame.top()),
                               Math.min(Math.abs(segment.y1() - separator), Math.abs(segment.y1() - frame.bottom())))
                               <= SNAP;
            boolean vertical = Math.abs(segment.x2() - segment.x1()) < SNAP && box.top() >= separator - SNAP
                               && box.bottom() <= frame.bottom() + SNAP;
            boolean boundary = false;
            for (final float x : xs) {
                work.step();
                boundary |= Math.abs(segment.x1() - x) <= SNAP;
            }
            vertical &= boundary;
            if (!horizontal && !vertical) {
                return null;
            }
        }
        final var members = new ArrayList<Integer>();
        for (int index = 0; index < glyphs.size(); index++) {
            work.step();
            final var glyph = glyphs.get(index);
            final var box = geometry.textBounds(new PdfLayoutItem.Bounds(glyph.x(), glyph.y() - glyph.height(),
                                                        glyph.x() + glyph.width(), glyph.y()),
                    glyph.direction());
            if (!intersects(frame, box)) {
                continue;
            }
            if (glyph.direction() != 0 || geometry.textRotation(glyph.direction()) != 0
                    || box.left() < frame.left() + SNAP || box.right() > frame.right() - SNAP
                    || box.top() < frame.top() + SNAP || box.bottom() > frame.bottom() - SNAP
                    || box.top() <= separator + SNAP && box.bottom() >= separator - SNAP) {
                return null;
            }
            members.add(index);
        }
        members.sort(Comparator.comparingDouble((Integer index) -> glyphs.get(index).y())
                        .thenComparingDouble(index -> glyphs.get(index).x()));
        final var baselines = new ArrayList<List<PdfPageText.Glyph>>();
        float baseline = Float.NaN;
        for (final int index : members) {
            work.step();
            final var glyph = glyphs.get(index);
            if (baselines.isEmpty() || Math.abs(glyph.y() - baseline) > 2) {
                baselines.add(new ArrayList<>());
                baseline = glyph.y();
            }
            baselines.getLast().add(glyph);
        }
        int headers = 0;
        while (headers < baselines.size() && baselines.get(headers).getFirst().y() < separator) {
            work.step();
            headers++;
        }
        if (headers < 2 || headers > 8 || baselines.size() - headers < 2) {
            return null;
        }
        final var rows = new ArrayList<List<PdfInferredTable.Cell>>();
        final float pitch = baselines.get(headers + 1).getFirst().y() - baselines.get(headers).getFirst().y();
        for (int row = 0; row < baselines.size(); row++) {
            work.step();
            final var source = baselines.get(row);
            final var first = source.getFirst();
            if (row >= headers
                    && (pitch < Math.max(8, first.height() * 1.2) || pitch > 40
                            || row > headers
                                       && Math.abs(first.y() - baselines.get(row - 1).getFirst().y() - pitch)
                                                  > Math.max(2, pitch * 0.15))) {
                return null;
            }
            final float top = row == 0         ? frame.top()
                              : row == headers ? separator
                                               : (baselines.get(row - 1).getFirst().y()
                                                         + source.stream()
                                                                 .map(glyph -> glyph.y() - glyph.height())
                                                                 .min(Float::compare)
                                                                 .orElseThrow())
                                                         / 2;
            final float bottom = row == headers - 1 ? separator
                                 : row == baselines.size() - 1
                                         ? frame.bottom()
                                         : (first.y()
                                                   + baselines.get(row + 1)
                                                           .stream()
                                                           .map(glyph -> glyph.y() - glyph.height())
                                                           .min(Float::compare)
                                                           .orElseThrow())
                                                   / 2;
            for (final var glyph : source) {
                work.step();
                if (glyph.y() - glyph.height() < top || glyph.y() > bottom || top >= bottom) {
                    return null;
                }
            }
            final var cells =
                    row < headers ? header(source, xs, top, bottom, work) : body(source, xs, top, bottom, work);
            if (cells == null) {
                return null;
            }
            rows.add(cells);
        }
        final var numeric = new ArrayList<Integer>();
        for (int column = 0; column < xs.size() - 1; column++) {
            boolean number = true;
            for (int row = headers; row < rows.size(); row++) {
                work.step();
                number &= NUMBER.matcher(rows.get(row).get(column).text()).matches();
            }
            if (number) {
                numeric.add(column);
            }
        }
        if (numeric.isEmpty()) {
            return null;
        }
        if (rows.size() > limits.maxRows() || xs.size() - 1 > limits.maxColumns()
                || (long) rows.size() * (xs.size() - 1) > limits.maxCells()) {
            throw new PartitionException(PartitionException.Code.TABLE_LIMIT, "PDF partial table exceeds table limits");
        }
        for (final var row : rows) {
            for (final var cell : row) {
                work.step();
                if (cell.text().codePointCount(0, cell.text().length()) > limits.maxCellCharacters()) {
                    throw new PartitionException(
                            PartitionException.Code.TABLE_LIMIT, "PDF partial cell exceeds maxCellCharacters");
                }
            }
        }
        return new PdfInferredTable(
                List.copyOf(rows), xs.size() - 1, headers, List.copyOf(members), List.copyOf(numeric), frame);
    }

    /**
     * Infers nonoverlapping header spans from complete source-run extents; uncovered slots remain explicit blanks.
     * @param glyphs one source header baseline
     * @param xs supported columns
     * @param top inferred row top
     * @param bottom inferred row bottom
     * @param work shared counter
     * @return immutable anchors or null on overlap/overlong/nonletter ambiguity
     * @throws IOException if cancelled or over budget
     */
    private static List<PdfInferredTable.Cell> header(final List<PdfPageText.Glyph> glyphs, final List<Float> xs,
            final float top, final float bottom, final PdfTableWork work) throws IOException {
        final var lines = PdfGlyphLines.lines(glyphs);
        final var result = new ArrayList<PdfInferredTable.Cell>();
        int next = 0;
        boolean letters = false;
        for (final var line : lines) {
            work.step();
            final int first = column(line.box().left(), xs, work);
            final int last = column(line.box().right(), xs, work);
            if (first < next || last < first
                    || line.text().codePointCount(0, line.text().length()) > 80 * (last - first + 1)) {
                return null;
            }
            letters |= line.text().codePoints().anyMatch(Character::isLetter);
            while (next < first) {
                work.step();
                result.add(cell(next++, 1, "", xs, top, bottom));
            }
            result.add(cell(first, last - first + 1, line.text(), xs, top, bottom));
            next = last + 1;
        }
        while (next < xs.size() - 1) {
            work.step();
            result.add(cell(next++, 1, "", xs, top, bottom));
        }
        return letters ? List.copyOf(result) : null;
    }

    /**
     * Requires one nonblank bounded line in every body position, with no glyph crossing a column boundary.
     * @param glyphs source baseline
     * @param xs supported columns
     * @param top inferred row top
     * @param bottom inferred row bottom
     * @param work shared counter
     * @return complete cells or null on missing/crossing/ambiguous text
     * @throws IOException if cancelled or over budget
     */
    private static List<PdfInferredTable.Cell> body(final List<PdfPageText.Glyph> glyphs, final List<Float> xs,
            final float top, final float bottom, final PdfTableWork work) throws IOException {
        final var groups = new ArrayList<List<PdfPageText.Glyph>>();
        for (int column = 0; column < xs.size() - 1; column++) {
            groups.add(new ArrayList<>());
        }
        for (final var glyph : glyphs) {
            work.step();
            final int index = column(glyph.x(), xs, work);
            if (index < 0 || glyph.x() < xs.get(index) + SNAP || glyph.x() + glyph.width() > xs.get(index + 1) - SNAP) {
                return null;
            }
            groups.get(index).add(glyph);
        }
        final var result = new ArrayList<PdfInferredTable.Cell>();
        for (int column = 0; column < groups.size(); column++) {
            work.step();
            final var lines = PdfGlyphLines.lines(groups.get(column));
            if (lines.size() != 1 || lines.getFirst().text().codePointCount(0, lines.getFirst().text().length()) > 80) {
                return null;
            }
            result.add(cell(column, 1, lines.getFirst().text(), xs, top, bottom));
        }
        return List.copyOf(result);
    }

    /**
     * Locates a source x coordinate without moving or clipping it to a supported boundary.
     * @param x source coordinate
     * @param xs supported column boundaries
     * @param work page counter
     * @return zero-based column or minus one outside the frame
     * @throws IOException if cancelled or over budget
     */
    private static int column(final float x, final List<Float> xs, final PdfTableWork work) throws IOException {
        for (int column = 0; column + 1 < xs.size(); column++) {
            work.step();
            if (x >= xs.get(column) && x < xs.get(column + 1)) {
                return column;
            }
        }
        return -1;
    }

    /**
     * Creates one inferred-area anchor without modifying source text.
     * @param column origin column
     * @param span width in columns
     * @param text source text or explicit header blank
     * @param xs supported columns
     * @param top inferred row top
     * @param bottom inferred row bottom
     * @return immutable cell
     */
    private static PdfInferredTable.Cell cell(final int column, final int span, final String text, final List<Float> xs,
            final float top, final float bottom) {
        return new PdfInferredTable.Cell(
                column, span, text, new PdfLayoutItem.Bounds(xs.get(column), top, xs.get(column + span), bottom));
    }

    /**
     * Tests inclusive box intersection, including zero-width painted rules.
     * @param first candidate bounds
     * @param second barrier bounds
     * @return whether bounds touch or overlap
     */
    private static boolean intersects(final PdfLayoutItem.Bounds first, final PdfLayoutItem.Bounds second) {
        return first.left() <= second.right() && second.left() <= first.right() && first.top() <= second.bottom()
                && second.top() <= first.bottom();
    }
}
