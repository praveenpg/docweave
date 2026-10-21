package dev.docweave.partition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** Narrow native-only strategy requiring repeated horizontal segmentation, numeric rows and lower support. */
final class HorizontalPdfTableDetector {
    /** Same deliberately limited ASCII syntax used by aligned-text recognition. */
    private static final Pattern NUMBER = Pattern.compile("[+-]?[0-9]+(?:[.,][0-9]+)*%?");
    /** Existing geometric comparison tolerance, in unscaled displayed units. */
    private static final float SNAP = 0.5f;
    /** Shared caller policy. */
    private final TableLimits limits;
    /** Displayed crop/rotation frame shared with native glyph extraction. */
    private final PdfPageGeometry geometry;

    /**
     * Captures immutable source policies.
     * @param limits structured-table budgets
     * @param geometry displayed frame
     */
    HorizontalPdfTableDetector(final TableLimits limits, final PdfPageGeometry geometry) {
        this.limits = limits;
        this.geometry = geometry;
    }

    /**
     * One approximate horizontal baseline with original intervals; source paint segmentation is retained.
     * @param y first source ordinate, avoiding transitive drift
     * @param spans original left/right extents
     */
    private record Rule(float y, List<float[]> spans) {}

    /**
     * Recognizes disjoint complete text tables, leaving all rejected glyphs available as ordinary text.
     * @param glyphs remaining native glyphs after ruled ownership
     * @param segments original painted rules
     * @param images raster barriers
     * @param work shared bounded page counter
     * @return immutable disjoint candidates
     * @throws IOException on cancellation or table/work exhaustion
     */
    List<PdfHorizontalTable> detect(final List<PdfPageText.Glyph> glyphs, final List<PdfPageGraphics.Segment> segments,
            final List<PdfPageGraphics.Image> images, final PdfTableWork work) throws IOException {
        final var rules = rules(segments, work);
        final var candidates = new ArrayList<PdfHorizontalTable>();
        for (int index = 0; index + 2 < rules.size(); index++) {
            work.step();
            final var top = rules.get(index);
            final var header = rules.get(index + 1);
            final var bottom = rules.get(index + 2);
            final var xs = partitions(top, work);
            final var other = partitions(header, work);
            if (xs.size() < 3 || xs.size() != other.size() || header.y() - top.y() < 8 || header.y() - top.y() > 40
                    || bottom.y() - header.y() < 16 || bottom.y() - header.y() > 800) {
                continue;
            }
            boolean same = true;
            for (int column = 0; column < xs.size(); column++) {
                work.step();
                same &= Math.abs(xs.get(column) - other.get(column)) <= 1;
            }
            if (!same || !lowerSupport(bottom, xs, work)) {
                continue;
            }
            final var frame = new PdfLayoutItem.Bounds(xs.getFirst(), top.y(), xs.getLast(), bottom.y());
            final var table = accept(glyphs, segments, images, frame, header.y(), xs, work);
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
        final var result = new ArrayList<PdfHorizontalTable>();
        for (int index = 0; index < candidates.size(); index++) {
            if (!ambiguous[index]) {
                result.add(candidates.get(index));
            }
        }
        return List.copyOf(result);
    }

    /**
     * Groups only near-horizontal nontrivial segments, without joining distinct rule ordinates.
     * @param segments painted source paths
     * @param work shared counter
     * @return ascending rule groups with left-sorted intervals
     * @throws IOException on cancellation or work exhaustion
     */
    private static List<Rule> rules(final List<PdfPageGraphics.Segment> segments, final PdfTableWork work)
            throws IOException {
        final var selected = new ArrayList<PdfPageGraphics.Segment>();
        for (final var segment : segments) {
            work.step();
            if (Math.abs(segment.y2() - segment.y1()) < SNAP && Math.abs(segment.x2() - segment.x1()) >= 4) {
                selected.add(segment);
            }
        }
        selected.sort(Comparator.comparingDouble(PdfPageGraphics.Segment::y1));
        final var rules = new ArrayList<Rule>();
        for (final var segment : selected) {
            work.step();
            if (rules.isEmpty() || segment.y1() - rules.getLast().y() > SNAP) {
                rules.add(new Rule(segment.y1(), new ArrayList<>()));
            }
            rules.getLast().spans().add(
                    new float[] {Math.min(segment.x1(), segment.x2()), Math.max(segment.x1(), segment.x2())});
        }
        for (final var rule : rules) {
            rule.spans().sort(Comparator.comparingDouble(span -> span[0]));
        }
        return rules;
    }

    /**
     * Requires 2–32 sequential segments with small joins and no duplicate/full-overlap alternatives.
     * @param rule source group
     * @param work shared counter
     * @return inferred column boundaries or an empty list for unsupported segmentation
     * @throws IOException on cancellation or work exhaustion
     */
    private static List<Float> partitions(final Rule rule, final PdfTableWork work) throws IOException {
        if (rule.spans().size() < 2 || rule.spans().size() > 32) {
            return List.of();
        }
        final var boundaries = new ArrayList<Float>();
        boundaries.add(rule.spans().getFirst()[0]);
        for (int index = 0; index < rule.spans().size(); index++) {
            work.step();
            final var span = rule.spans().get(index);
            if (span[1] - span[0] < 12) {
                return List.of();
            }
            if (index + 1 < rule.spans().size()) {
                final var next = rule.spans().get(index + 1);
                if (Math.abs(span[1] - next[0]) > 1.5f || next[0] <= span[0] + 12) {
                    return List.of();
                }
                boundaries.add(span[1] + (next[0] - span[1]) / 2);
            }
        }
        boundaries.add(rule.spans().getLast()[1]);
        return List.copyOf(boundaries);
    }

    /**
     * Requires matching lower extent and at least85% painted coverage; lower support does not invent cell borders.
     * @param rule first rule below the header separator
     * @param xs supported column boundaries
     * @param work shared counter
     * @return whether the lower rule supplies sufficient explicit support
     * @throws IOException on cancellation or work exhaustion
     */
    private static boolean lowerSupport(final Rule rule, final List<Float> xs, final PdfTableWork work)
            throws IOException {
        if (Math.abs(rule.spans().getFirst()[0] - xs.getFirst()) > 2
                || Math.abs(rule.spans().getLast()[1] - xs.getLast()) > 2) {
            return false;
        }
        float end = xs.getFirst();
        float covered = 0;
        for (final var span : rule.spans()) {
            work.step();
            final float start = Math.max(xs.getFirst(), span[0]);
            final float stop = Math.min(xs.getLast(), span[1]);
            if (stop > end) {
                covered += stop - Math.max(end, start);
                end = stop;
            }
        }
        return covered >= (xs.getLast() - xs.getFirst()) * 0.85f;
    }

    /**
     * Assigns every intersecting upright glyph to exactly one complete row/column cell, rejecting barriers.
     * @param glyphs original remaining glyphs
     * @param segments painted source geometry
     * @param images raster barriers
     * @param frame supporting envelope
     * @param separator header separator ordinate
     * @param xs repeated inferred columns
     * @param work shared counter
     * @return accepted candidate or null
     * @throws IOException on cancellation or table/work exhaustion
     */
    private PdfHorizontalTable accept(final List<PdfPageText.Glyph> glyphs,
            final List<PdfPageGraphics.Segment> segments, final List<PdfPageGraphics.Image> images,
            final PdfLayoutItem.Bounds frame, final float separator, final List<Float> xs, final PdfTableWork work)
            throws IOException {
        for (final var image : images) {
            work.step();
            final var box = new PdfLayoutItem.Bounds(
                    image.x(), image.y(), image.x() + image.width(), image.y() + image.height());
            final boolean encloses = box.left() <= frame.left() && box.top() <= frame.top()
                                     && box.right() >= frame.right() && box.bottom() >= frame.bottom();
            if (intersects(frame, box) && !encloses) {
                return null;
            }
        }
        for (final var segment : segments) {
            work.step();
            final var box =
                    new PdfLayoutItem.Bounds(Math.min(segment.x1(), segment.x2()), Math.min(segment.y1(), segment.y2()),
                            Math.max(segment.x1(), segment.x2()), Math.max(segment.y1(), segment.y2()));
            if (intersects(frame, box)
                    && (Math.abs(segment.y2() - segment.y1()) >= SNAP
                            || Math.min(Math.abs(segment.y1() - frame.top()),
                                       Math.min(Math.abs(segment.y1() - separator),
                                               Math.abs(segment.y1() - frame.bottom())))
                                       > SNAP)) {
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
            if (geometry.textRotation(glyph.direction()) != 0 || box.left() < frame.left() + SNAP
                    || box.right() > frame.right() - SNAP || box.top() < frame.top() + SNAP
                    || box.bottom() > frame.bottom() - SNAP
                    || box.top() <= separator + SNAP && box.bottom() >= separator - SNAP) {
                return null;
            }
            members.add(index);
        }
        members.sort(Comparator.comparingDouble((Integer index) -> glyphs.get(index).y())
                        .thenComparingDouble(index -> glyphs.get(index).x()));
        final var rows = new ArrayList<List<Integer>>();
        float baseline = Float.NaN;
        for (final int index : members) {
            work.step();
            final float next = glyphs.get(index).y();
            if (rows.isEmpty() || Math.abs(next - baseline) > 2) {
                rows.add(new ArrayList<>());
                baseline = next;
            }
            rows.getLast().add(index);
        }
        if (rows.size() < 3 || glyphs.get(rows.getFirst().getFirst()).y() >= separator
                || glyphs.get(rows.get(1).getFirst()).y() <= separator) {
            return null;
        }
        final int columns = xs.size() - 1;
        final float pitch = glyphs.get(rows.get(2).getFirst()).y() - glyphs.get(rows.get(1).getFirst()).y();
        final var cells = new ArrayList<List<PdfLayoutItem>>();
        for (int row = 0; row < rows.size(); row++) {
            work.step();
            final var first = glyphs.get(rows.get(row).getFirst());
            if (pitch < Math.max(8, first.height() * 1.2) || pitch > 40
                    || row > 1
                               && Math.abs(first.y() - glyphs.get(rows.get(row - 1).getFirst()).y() - pitch)
                                          > Math.max(2, pitch * 0.15)) {
                return null;
            }
            if (row == 1
                    && (first.y() - glyphs.get(rows.getFirst().getFirst()).y() < 8
                            || first.y() - glyphs.get(rows.getFirst().getFirst()).y() > Math.min(40, pitch * 2))) {
                return null;
            }
            final var assigned = new ArrayList<List<PdfPageText.Glyph>>();
            for (int column = 0; column < columns; column++) {
                assigned.add(new ArrayList<>());
            }
            for (final int index : rows.get(row)) {
                final var glyph = glyphs.get(index);
                final float center = glyph.x() + glyph.width() / 2;
                boolean placed = false;
                for (int column = 0; column < columns; column++) {
                    work.step();
                    if (center >= xs.get(column) && center < xs.get(column + 1)) {
                        if (glyph.x() < xs.get(column) + SNAP
                                || glyph.x() + glyph.width() > xs.get(column + 1) - SNAP) {
                            return null;
                        }
                        assigned.get(column).add(glyph);
                        placed = true;
                        break;
                    }
                }
                if (!placed) {
                    return null;
                }
            }
            final var cellRow = new ArrayList<PdfLayoutItem>();
            for (final var group : assigned) {
                work.step();
                final var lines = PdfGlyphLines.lines(group);
                if (lines.size() != 1) {
                    return null;
                }
                final var cell = lines.getFirst();
                if (cell.text().codePointCount(0, cell.text().length()) > 80
                        || row == 0 && cell.text().codePoints().noneMatch(Character::isLetter)) {
                    return null;
                }
                cellRow.add(cell);
            }
            cells.add(List.copyOf(cellRow));
        }
        final var numeric = new ArrayList<Integer>();
        for (int column = 0; column < columns; column++) {
            boolean number = true;
            for (int row = 1; row < cells.size(); row++) {
                work.step();
                number &= NUMBER.matcher(cells.get(row).get(column).text()).matches();
            }
            if (number) {
                numeric.add(column);
            }
        }
        if (numeric.isEmpty()) {
            return null;
        }
        if (cells.size() > limits.maxRows() || columns > limits.maxColumns()
                || (long) cells.size() * columns > limits.maxCells()) {
            throw new PartitionException(
                    PartitionException.Code.TABLE_LIMIT, "PDF horizontal table exceeds table limits");
        }
        for (final var row : cells) {
            for (final var cell : row) {
                work.step();
                if (cell.text().codePointCount(0, cell.text().length()) > limits.maxCellCharacters()) {
                    throw new PartitionException(
                            PartitionException.Code.TABLE_LIMIT, "PDF horizontal cell exceeds maxCellCharacters");
                }
            }
        }
        return new PdfHorizontalTable(List.copyOf(cells), List.copyOf(members), List.copyOf(numeric), frame);
    }

    /**
     * Tests inclusive rectangles, including zero-width rule bounds.
     * @param first first bounds
     * @param second second bounds
     * @return whether the rectangles intersect or touch
     */
    private static boolean intersects(final PdfLayoutItem.Bounds first, final PdfLayoutItem.Bounds second) {
        return first.left() <= second.right() && second.left() <= first.right() && first.top() <= second.bottom()
                && second.top() <= first.bottom();
    }
}
