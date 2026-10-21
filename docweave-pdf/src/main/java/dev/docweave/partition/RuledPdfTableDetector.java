package dev.docweave.partition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/** Recognizes independent closed ruled components and rectangular merges; ambiguous topology fails closed. */
final class RuledPdfTableDetector implements PdfTableDetector {
    /** Half a PDF user-space unit for endpoint snapping, joins and coverage. */
    private static final float TOLERANCE = 0.5f;

    /** Axis-normalized source segment. */
    private record Rule(boolean horizontal, float position, float start, float end) {
        /**
         * Returns the left edge for sweep ordering.
         * @return minimum x
         */
        float left() {
            return horizontal ? start : position;
        }

        /**
         * Returns the right edge for sweep termination.
         * @return maximum x
         */
        float right() {
            return horizontal ? end : position;
        }
    }

    /** Structured-table budgets, checked before allocating topology matrices. */
    private final TableLimits limits;

    /**
     * Captures immutable table limits.
     * @param limits caller-selected structured-table policy
     */
    RuledPdfTableDetector(final TableLimits limits) {
        this.limits = limits;
    }

    /**
     * Separates connected source strokes, then rejects invalid or overlapping candidate bounds.
     * @param segments source geometry
     * @param work shared page work counter
     * @return non-overlapping validated grids
     * @throws IOException if cancelled or a work/table budget fails
     */
    @Override
    public List<PdfTableGrid> detect(final List<PdfPageGraphics.Segment> segments, final PdfTableWork work)
            throws IOException {
        final var rules = new ArrayList<Rule>();
        for (final var segment : segments) {
            work.step();
            final float dx = Math.abs(segment.x1() - segment.x2());
            final float dy = Math.abs(segment.y1() - segment.y2());
            if (dy < TOLERANCE && dx > 2) {
                rules.add(new Rule(true, segment.y1(), Math.min(segment.x1(), segment.x2()),
                        Math.max(segment.x1(), segment.x2())));
            } else if (dx < TOLERANCE && dy > 2) {
                rules.add(new Rule(false, segment.x1(), Math.min(segment.y1(), segment.y2()),
                        Math.max(segment.y1(), segment.y2())));
            }
        }
        rules.sort(Comparator.comparingDouble(Rule::left));
        final var groups = new Groups(rules.size());
        for (int first = 0; first < rules.size(); first++) {
            final var rule = rules.get(first);
            for (int second = first + 1; second < rules.size(); second++) {
                final var other = rules.get(second);
                if (other.left() > rule.right() + TOLERANCE) {
                    break;
                }
                work.step();
                if (touches(rule, other)) {
                    groups.join(first, second);
                }
            }
        }
        final var components = new LinkedHashMap<Integer, List<Rule>>();
        for (int index = 0; index < rules.size(); index++) {
            work.step();
            components.computeIfAbsent(groups.root(index), unused -> new ArrayList<>()).add(rules.get(index));
        }
        final var candidates = new ArrayList<PdfTableGrid>();
        for (final var component : components.values()) {
            final var grid = grid(component, work);
            if (grid != null) {
                candidates.add(grid);
            }
        }
        final var ambiguous = new boolean[candidates.size()];
        for (int first = 0; first < candidates.size(); first++) {
            for (int second = first + 1; second < candidates.size(); second++) {
                work.step();
                if (overlaps(candidates.get(first), candidates.get(second))) {
                    ambiguous[first] = true;
                    ambiguous[second] = true;
                }
            }
        }
        final var result = new ArrayList<PdfTableGrid>();
        for (int index = 0; index < candidates.size(); index++) {
            if (!ambiguous[index]) {
                result.add(candidates.get(index));
            }
        }
        result.sort(Comparator.comparingDouble((PdfTableGrid grid) -> grid.ys().getFirst())
                        .thenComparingDouble(grid -> grid.xs().getFirst()));
        return List.copyOf(result);
    }

    /**
     * Tests collinear joins or perpendicular crossings within the snapping tolerance.
     * @param first source rule
     * @param second source rule
     * @return whether the strokes belong to one connected component
     */
    private static boolean touches(final Rule first, final Rule second) {
        if (first.horizontal() == second.horizontal()) {
            return Math.abs(first.position() - second.position()) <= TOLERANCE
                    && Math.max(first.start(), second.start()) <= Math.min(first.end(), second.end()) + TOLERANCE;
        }
        final var horizontal = first.horizontal() ? first : second;
        final var vertical = first.horizontal() ? second : first;
        return vertical.position() >= horizontal.start() - TOLERANCE
                && vertical.position() <= horizontal.end() + TOLERANCE
                && horizontal.position() >= vertical.start() - TOLERANCE
                && horizontal.position() <= vertical.end() + TOLERANCE;
    }

    /**
     * Rejects nested or overlapping table bounds so a glyph can never be owned by two recognized tables.
     * @param first candidate grid
     * @param second candidate grid
     * @return whether bounding interiors overlap
     */
    private static boolean overlaps(final PdfTableGrid first, final PdfTableGrid second) {
        return Math.max(first.xs().getFirst(), second.xs().getFirst())
                < Math.min(first.xs().getLast(), second.xs().getLast())
                && Math.max(first.ys().getFirst(), second.ys().getFirst())
                           < Math.min(first.ys().getLast(), second.ys().getLast());
    }

    /**
     * Builds edge coverage and validates every merged region as a border-free rectangle with a closed perimeter.
     * @param rules connected source component
     * @param work shared page counter
     * @return recognized grid or null for partial/nonrectangular topology
     * @throws IOException if cancelled or a table/work limit fails
     */
    private PdfTableGrid grid(final List<Rule> rules, final PdfTableWork work) throws IOException {
        final var horizontal = rules.stream().filter(Rule::horizontal).toList();
        final var vertical = rules.stream().filter(rule -> !rule.horizontal()).toList();
        final var xs = unique(vertical.stream().map(Rule::position).toList());
        final var ys = unique(horizontal.stream().map(Rule::position).toList());
        if (xs.size() < 3 || ys.size() < 3) {
            return null;
        }
        final int rows = ys.size() - 1;
        final int columns = xs.size() - 1;
        // Test outer borders before interpreting missing interior edges as intentional merges.
        if (coverage(horizontal, ys.getFirst(), xs.getFirst(), xs.getLast(), work) != 1
                || coverage(horizontal, ys.getLast(), xs.getFirst(), xs.getLast(), work) != 1
                || coverage(vertical, xs.getFirst(), ys.getFirst(), ys.getLast(), work) != 1
                || coverage(vertical, xs.getLast(), ys.getFirst(), ys.getLast(), work) != 1) {
            return null;
        }
        // Open components are ordinary source geometry, regardless of their prospective slot count.
        // Perimeter probing uses the shared work budget and allocates no topology matrix.
        if (rows > limits.maxRows() || columns > limits.maxColumns() || (long) rows * columns > limits.maxCells()) {
            throw new PartitionException(PartitionException.Code.TABLE_LIMIT, "PDF grid exceeds table limits");
        }
        final boolean[][] h = new boolean[rows + 1][columns];
        final boolean[][] v = new boolean[rows][columns + 1];
        final var groups = new Groups(rows * columns);
        for (int row = 0; row <= rows; row++) {
            for (int column = 0; column < columns; column++) {
                final int state = coverage(horizontal, ys.get(row), xs.get(column), xs.get(column + 1), work);
                if (state < 0) {
                    return null;
                }
                h[row][column] = state == 1;
                if (state == 0 && row > 0 && row < rows) {
                    groups.join((row - 1) * columns + column, row * columns + column);
                }
            }
        }
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column <= columns; column++) {
                final int state = coverage(vertical, xs.get(column), ys.get(row), ys.get(row + 1), work);
                if (state < 0) {
                    return null;
                }
                v[row][column] = state == 1;
                if (state == 0 && column > 0 && column < columns) {
                    groups.join(row * columns + column - 1, row * columns + column);
                }
            }
        }
        final var regions = new LinkedHashMap<Integer, List<Integer>>();
        for (int slot = 0; slot < rows * columns; slot++) {
            work.step();
            regions.computeIfAbsent(groups.root(slot), unused -> new ArrayList<>()).add(slot);
        }
        final var cells = new ArrayList<PdfTableGrid.Cell>();
        final int[] owners = new int[rows * columns];
        for (final var slots : regions.values()) {
            int top = rows;
            int bottom = -1;
            int left = columns;
            int right = -1;
            for (final int slot : slots) {
                work.step();
                top = Math.min(top, slot / columns);
                bottom = Math.max(bottom, slot / columns);
                left = Math.min(left, slot % columns);
                right = Math.max(right, slot % columns);
            }
            if ((long) (bottom - top + 1) * (right - left + 1) != slots.size()) {
                return null;
            }
            for (int row = top; row <= bottom; row++) {
                for (int column = left; column <= right; column++) {
                    work.step();
                    if (h[row][column] != (row == top) || h[row + 1][column] != (row == bottom)
                            || v[row][column] != (column == left) || v[row][column + 1] != (column == right)) {
                        return null;
                    }
                }
            }
            for (final int slot : slots) {
                owners[slot] = cells.size();
            }
            cells.add(new PdfTableGrid.Cell(top, left, bottom - top + 1, right - left + 1));
        }
        return new PdfTableGrid(xs, ys, List.copyOf(cells), owners);
    }

    /**
     * Measures union coverage of collinear segments over one atomic edge. Tiny gaps snap; partial strokes reject.
     * @param rules axis-compatible component rules
     * @param position edge axis coordinate
     * @param start interval start
     * @param end interval end
     * @param work shared page counter
     * @return 1 for full coverage, 0 for absent, -1 for partial
     * @throws IOException if interrupted or over budget
     */
    private static int coverage(final List<Rule> rules, final float position, final float start, final float end,
            final PdfTableWork work) throws IOException {
        final var matching = new ArrayList<Rule>();
        for (final var rule : rules) {
            work.step();
            if (Math.abs(rule.position() - position) <= TOLERANCE && rule.end() > start + TOLERANCE
                    && rule.start() < end - TOLERANCE) {
                matching.add(rule);
            }
        }
        if (matching.isEmpty()) {
            return 0;
        }
        matching.sort(Comparator.comparingDouble(Rule::start));
        float covered = start;
        for (final var rule : matching) {
            if (rule.start() > covered + TOLERANCE) {
                return -1;
            }
            covered = Math.max(covered, rule.end());
        }
        return covered >= end - TOLERANCE ? 1 : -1;
    }

    /**
     * Coalesces nearly identical axis coordinates without transitive drift.
     * @param positions raw axis positions
     * @return ascending snapped coordinates
     */
    private static List<Float> unique(final List<Float> positions) {
        final var sorted = new ArrayList<>(positions);
        sorted.sort(Float::compare);
        final var result = new ArrayList<Float>();
        for (final float position : sorted) {
            if (result.isEmpty() || position - result.getLast() > TOLERANCE) {
                result.add(position);
            }
        }
        return List.copyOf(result);
    }

    /** Path-compressed union/find shared by stroke components and atomic-cell connectivity. */
    private static final class Groups {
        /** Parent index per node. */
        private final int[] parents;
        /** Component sizes for balanced joins. */
        private final int[] sizes;

        /**
         * Creates independent singleton groups.
         * @param count number of nodes
         */
        Groups(final int count) {
            parents = new int[count];
            sizes = new int[count];
            for (int index = 0; index < count; index++) {
                parents[index] = index;
                sizes[index] = 1;
            }
        }

        /**
         * Finds a representative while compressing the path.
         * @param index node index
         * @return component root
         */
        int root(final int index) {
            int current = index;
            while (parents[current] != current) {
                parents[current] = parents[parents[current]];
                current = parents[current];
            }
            return current;
        }

        /**
         * Joins two components, attaching the smaller to the larger.
         * @param first first node
         * @param second second node
         */
        void join(final int first, final int second) {
            int left = root(first);
            int right = root(second);
            if (left == right) {
                return;
            }
            if (sizes[left] < sizes[right]) {
                final int swap = left;
                left = right;
                right = swap;
            }
            parents[right] = left;
            sizes[left] += sizes[right];
        }
    }
}
