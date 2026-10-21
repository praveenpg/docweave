package dev.docweave.partition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Explicit horizontal-segment strategy with column-local row spans and transactional native ownership. */
final class SegmentedRowsPdfTableDetector implements PdfNativeTableDetector {
    /** Paint grouping tolerance in displayed unscaled source units. */
    private static final float SNAP = 0.5f;
    /** Caller-owned immutable structured budgets. */
    private final TableLimits limits;
    /** Original displayed crop frame. */
    private final PdfPageGeometry geometry;

    /**
     * Captures policies without retaining any document, source glyph or model state.
     * @param limits structured budgets
     * @param geometry original displayed frame
     */
    SegmentedRowsPdfTableDetector(final TableLimits limits, final PdfPageGeometry geometry) {
        this.limits = limits;
        this.geometry = geometry;
    }

    /**
     * One original horizontal paint interval.
     * @param left lower source abscissa
     * @param right upper source abscissa
     */
    private record Span(float left, float right) {}

    /**
     * A fixed-first-ordinate group of original intervals; gaps/overlaps are not silently repaired.
     * @param y displayed original ordinate
     * @param spans sorted original intervals
     */
    private record Rule(float y, List<Span> spans) {}

    /**
     * Detects maximal supported rule components and rejects conflicting envelopes before returning ownership.
     * @param glyphs original native glyphs left by earlier strategies
     * @param segments original painted rules and barriers
     * @param images original raster placements
     * @param work shared page counter, never reset by this strategy
     * @return complete supported candidates, leaving all rejected glyphs unclaimed
     * @throws IOException if cancelled or structured/work limits fail
     */
    @Override
    public List<PdfInferredTable> detect(final List<PdfPageText.Glyph> glyphs,
            final List<PdfPageGraphics.Segment> segments, final List<PdfPageGraphics.Image> images,
            final PdfTableWork work) throws IOException {
        final var rules = rules(segments, work);
        final var candidates = new ArrayList<PdfInferredTable>();
        for (int first = 0; first < rules.size(); first++) {
            work.step();
            final var xs = partitions(rules.get(first), work);
            if (xs.isEmpty()) {
                continue;
            }
            final var dividers = new ArrayList<boolean[]>();
            dividers.add(coverage(rules.get(first), xs, work));
            int last = first;
            boolean invalid = false;
            while (last + 1 < rules.size()) {
                work.step();
                final float gap = rules.get(last + 1).y() - rules.get(last).y();
                final var covered = coverage(rules.get(last + 1), xs, work);
                final var next = rules.get(last + 1);
                if (gap < 4 || gap > 100 || next.spans().getFirst().left() < xs.getFirst() - SNAP
                        || next.spans().getLast().right() > xs.getLast() + SNAP) {
                    break;
                }
                invalid |= covered == null || !covered[0];
                dividers.add(covered == null ? new boolean[xs.size() - 1] : covered);
                last++;
            }
            boolean partial = false;
            boolean interiorFull = false;
            for (int row = 1; row < dividers.size() - 1; row++) {
                work.step();
                final boolean full = all(dividers.get(row));
                partial |= !full;
                interiorFull |= full;
            }
            if (!invalid && last - first >= 3 && partial && interiorFull
                    && same(xs, partitions(rules.get(last), work))) {
                final var ys = new ArrayList<Float>();
                for (int row = first; row <= last; row++) {
                    work.step();
                    ys.add(rules.get(row).y());
                }
                final var table = assemble(glyphs, segments, images, xs, ys, dividers, work);
                if (table != null) {
                    candidates.add(table);
                }
            }
            // The maximal structural component is indivisible: never salvage its inner rows after rejection.
            first = last;
        }
        final var ambiguous = new boolean[candidates.size()];
        for (int first = 0; first < candidates.size(); first++) {
            for (int second = first + 1; second < candidates.size(); second++) {
                work.step();
                if (intersects(candidates.get(first).frame(), candidates.get(second).frame())) {
                    ambiguous[first] = true;
                    ambiguous[second] = true;
                }
            }
        }
        final var accepted = new ArrayList<PdfInferredTable>();
        for (int index = 0; index < candidates.size(); index++) {
            work.step();
            if (!ambiguous[index]) {
                accepted.add(candidates.get(index));
            }
        }
        return List.copyOf(accepted);
    }

    /**
     * Groups finite positive horizontal paint without coalescing overlapping intervals.
     * @param segments original source segments
     * @param work shared page counter
     * @return ascending fixed-ordinate groups
     * @throws IOException if cancelled or work is exhausted
     */
    private static List<Rule> rules(final List<PdfPageGraphics.Segment> segments, final PdfTableWork work)
            throws IOException {
        final var sorted = new ArrayList<PdfPageGraphics.Segment>();
        for (final var segment : segments) {
            work.step();
            if (Float.isFinite(segment.x1()) && Float.isFinite(segment.x2()) && Float.isFinite(segment.y1())
                    && Float.isFinite(segment.y2()) && Math.abs(segment.y2() - segment.y1()) < SNAP
                    && Math.abs(segment.x2() - segment.x1()) >= 4) {
                sorted.add(segment);
            }
        }
        sorted.sort(Comparator.comparingDouble(PdfPageGraphics.Segment::y1));
        final var groups = new ArrayList<Rule>();
        for (final var segment : sorted) {
            work.step();
            if (groups.isEmpty() || segment.y1() - groups.getLast().y() > SNAP) {
                groups.add(new Rule(segment.y1(), new ArrayList<>()));
            }
            groups.getLast().spans().add(
                    new Span(Math.min(segment.x1(), segment.x2()), Math.max(segment.x1(), segment.x2())));
        }
        for (final var group : groups) {
            group.spans().sort(Comparator.comparingDouble(Span::left));
        }
        return groups;
    }

    /**
     * Reads two through32 explicit abutting source partitions, each at least12 units wide.
     * @param rule original top/bottom rule
     * @param work shared counter
     * @return supported axes, or empty on gaps/overlaps/unsupported widths
     * @throws IOException if cancelled or over budget
     */
    private static List<Float> partitions(final Rule rule, final PdfTableWork work) throws IOException {
        if (rule.spans().size() < 2 || rule.spans().size() > 32) {
            return List.of();
        }
        final var xs = new ArrayList<Float>();
        for (final var span : rule.spans()) {
            work.step();
            if (span.right() - span.left() < 12 || !xs.isEmpty() && Math.abs(xs.getLast() - span.left()) > SNAP) {
                return List.of();
            }
            if (xs.isEmpty()) {
                xs.add(span.left());
            }
            xs.add(span.right());
        }
        return List.copyOf(xs);
    }

    /**
     * Requires every interval endpoint to match an observed column axis and every covered column to be unique.
     * @param rule original paint at one row boundary
     * @param xs observed top-rule column axes
     * @param work shared counter
     * @return covered columns, or null without paint repairs
     * @throws IOException if cancelled or over budget
     */
    private static boolean[] coverage(final Rule rule, final List<Float> xs, final PdfTableWork work)
            throws IOException {
        final var covered = new boolean[xs.size() - 1];
        for (final var span : rule.spans()) {
            final int left = axis(span.left(), xs, work);
            final int right = axis(span.right(), xs, work);
            if (left < 0 || right <= left) {
                return null;
            }
            for (int column = left; column < right; column++) {
                work.step();
                if (covered[column]) {
                    return null;
                }
                covered[column] = true;
            }
        }
        return covered;
    }

    /**
     * Locates one source endpoint without changing the original axes.
     * @param value source endpoint
     * @param axes observed column axes
     * @param work shared counter
     * @return matching index or minus one
     * @throws IOException if cancelled or over budget
     */
    private static int axis(final float value, final List<Float> axes, final PdfTableWork work) throws IOException {
        for (int index = 0; index < axes.size(); index++) {
            work.step();
            if (Math.abs(value - axes.get(index)) <= SNAP) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Assembles complete rectangular column-local spans, validating all source text before any claim is committed.
     * @param glyphs original still-unclaimed native positions
     * @param segments original barriers
     * @param images original raster placements
     * @param xs observed segmented column axes
     * @param ys observed row-rule ordinates
     * @param dividers exact per-column paint coverage at every row boundary
     * @param work shared counter
     * @return whole supported candidate or null with no committed ownership
     * @throws IOException if cancelled or caller limits fail
     */
    private PdfInferredTable assemble(final List<PdfPageText.Glyph> glyphs,
            final List<PdfPageGraphics.Segment> segments, final List<PdfPageGraphics.Image> images,
            final List<Float> xs, final List<Float> ys, final List<boolean[]> dividers, final PdfTableWork work)
            throws IOException {
        final int rows = ys.size() - 1;
        final int columns = xs.size() - 1;
        final var frame = new PdfLayoutItem.Bounds(xs.getFirst(), ys.getFirst(), xs.getLast(), ys.getLast());
        if (frame.left() < 0 || frame.top() < 0 || frame.right() > geometry.width()
                || frame.bottom() > geometry.height()) {
            return null;
        }
        if (rows > limits.maxRows() || columns > limits.maxColumns() || (long) rows * columns > limits.maxCells()) {
            throw new PartitionException(
                    PartitionException.Code.TABLE_LIMIT, "PDF segmented table exceeds table limits");
        }
        final var precedingColumns = new boolean[columns];
        for (final var glyph : glyphs) {
            work.step();
            if (glyph.text().isBlank()) {
                continue;
            }
            final var center = geometry.center(glyph);
            // A nearby unruled continuation must not turn a missing bottom rule into a valid table prefix.
            if (center.x >= xs.getFirst() && center.x < xs.get(1) && center.y >= frame.bottom()
                    && center.y < frame.bottom() + ys.getLast() - ys.get(ys.size() - 2)) {
                return null;
            }
            if (center.y < frame.top() && center.y >= frame.top() - ys.get(1) + ys.getFirst()) {
                for (int column = 0; column < columns; column++) {
                    work.step();
                    precedingColumns[column] |= center.x >= xs.get(column) && center.x < xs.get(column + 1);
                }
            }
        }
        if (all(precedingColumns)) {
            return null;
        }
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
            if (intersects(frame, box) && Math.abs(segment.y2() - segment.y1()) >= SNAP) {
                return null;
            }
        }
        final var result = new ArrayList<List<PdfInferredTable.Cell>>();
        for (int row = 0; row < rows; row++) {
            result.add(new ArrayList<>());
        }
        final var members = new ArrayList<Integer>();
        final var claimed = new boolean[glyphs.size()];
        for (int column = 0; column < columns; column++) {
            for (int row = 0; row < rows;) {
                work.step();
                int end = row + 1;
                while (end < rows && !dividers.get(end)[column]) {
                    work.step();
                    end++;
                }
                final var box = new PdfLayoutItem.Bounds(xs.get(column), ys.get(row), xs.get(column + 1), ys.get(end));
                final var positions = new ArrayList<PdfPageText.Glyph>();
                for (int index = 0; index < glyphs.size(); index++) {
                    work.step();
                    final var glyph = glyphs.get(index);
                    final var center = geometry.center(glyph);
                    if (center.x >= box.left() && center.x < box.right() && center.y >= box.top()
                            && center.y < box.bottom()) {
                        final var bounds =
                                geometry.textBounds(new PdfLayoutItem.Bounds(glyph.x(), glyph.y() - glyph.height(),
                                                            glyph.x() + glyph.width(), glyph.y()),
                                        glyph.direction());
                        if (claimed[index] || glyph.direction() != 0 || geometry.textRotation(glyph.direction()) != 0
                                || bounds.left() < box.left() - SNAP || bounds.right() > box.right() + SNAP
                                || bounds.top() < box.top() - SNAP || bounds.bottom() > box.bottom() + SNAP) {
                            return null;
                        }
                        claimed[index] = true;
                        members.add(index);
                        positions.add(glyph);
                    }
                }
                final String text =
                        String.join(" ", PdfGlyphLines.lines(positions).stream().map(PdfLayoutItem::text).toList());
                if (text.isBlank()) {
                    return null;
                }
                if (text.codePointCount(0, text.length()) > limits.maxCellCharacters()) {
                    throw new PartitionException(
                            PartitionException.Code.TABLE_LIMIT, "PDF segmented cell exceeds maxCellCharacters");
                }
                result.get(row).add(new PdfInferredTable.Cell(column, 1, end - row, text, box));
                row = end;
            }
        }
        for (int index = 0; index < glyphs.size(); index++) {
            work.step();
            final var glyph = glyphs.get(index);
            final var bounds = geometry.textBounds(new PdfLayoutItem.Bounds(glyph.x(), glyph.y() - glyph.height(),
                                                           glyph.x() + glyph.width(), glyph.y()),
                    glyph.direction());
            if (!claimed[index] && intersects(frame, bounds) && !glyph.text().isBlank()) {
                return null;
            }
        }
        final var immutable =
                result.stream()
                        .map(row
                                -> row.stream().sorted(Comparator.comparingInt(PdfInferredTable.Cell::column)).toList())
                        .toList();
        return new PdfInferredTable(immutable, columns, 0, List.copyOf(members), List.of(), frame);
    }

    /**
     * Compares observed top/bottom axes without rewriting either source group.
     * @param first original upper axes
     * @param second original lower axes
     * @return whether sizes and paired ordinates agree within source tolerance
     */
    private static boolean same(final List<Float> first, final List<Float> second) {
        if (first.size() != second.size()) {
            return false;
        }
        for (int index = 0; index < first.size(); index++) {
            if (Math.abs(first.get(index) - second.get(index)) > SNAP) {
                return false;
            }
        }
        return true;
    }

    /**
     * Tests whether a complete row divider is painted.
     * @param values original per-column coverage
     * @return whether every column has source paint
     */
    private static boolean all(final boolean[] values) {
        for (final boolean value : values) {
            if (!value) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks positive interior overlap; touching outer edges do not claim source content.
     * @param first first displayed envelope
     * @param second second displayed envelope
     * @return whether interiors overlap on both axes
     */
    private static boolean intersects(final PdfLayoutItem.Bounds first, final PdfLayoutItem.Bounds second) {
        return first.left() < second.right() && first.right() > second.left() && first.top() < second.bottom()
                && first.bottom() > second.top();
    }
}
