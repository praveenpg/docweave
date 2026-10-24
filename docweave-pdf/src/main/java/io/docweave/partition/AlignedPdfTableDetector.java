package io.docweave.partition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Conservative opt-in recognition; numeric evidence and strict alignment narrow, but cannot eliminate, false
 * positives.
 */
final class AlignedPdfTableDetector implements PdfBorderlessTableDetector {
    /** ASCII numeric syntax only; no dates, currencies or locale/type inference. */
    private static final Pattern NUMBER = Pattern.compile("[+-]?[0-9]+(?:[.,][0-9]+)*%?");
    /** Financial tokens only; source punctuation is classified without parsing values or changing text. */
    private static final Pattern FINANCIAL = Pattern.compile(
            "(?:[$€£¥][ \u00a0\u202f]*)?(?:[+\\-−]?[0-9]+(?:[.,][0-9]+)*%?|\\([0-9]+(?:[.,][0-9]+)*%?\\))|\\([$€£¥][ \u00a0\u202f]*[0-9]+(?:[.,][0-9]+)*%?\\)");
    /** Explicitly selected immutable numeric token grammar. */
    private final Pattern numeric;
    /** Maximum cell length for this deliberately narrow aligned-text heuristic. */
    private static final int MAX_CELL_POINTS = 80;
    /** Shared structured-table budgets. */
    private final TableLimits limits;

    /**
     * Captures immutable limits.
     * @param limits resource policies checked after recognition, before output
     */
    AlignedPdfTableDetector(final TableLimits limits) {
        this(limits, NUMBER);
    }

    /**
     * Creates the opt-in financial grammar behind the existing detector strategy interface.
     * @param limits unchanged table/output budgets
     * @return source-preserving detector with accounting/currency token classification
     */
    static PdfBorderlessTableDetector financial(final TableLimits limits) {
        return new AlignedPdfTableDetector(limits, FINANCIAL);
    }

    /**
     * Captures a fixed grammar without changing alignment, ownership or resource policies.
     * @param limits unchanged table budgets
     * @param numeric fixed immutable token grammar
     */
    private AlignedPdfTableDetector(final TableLimits limits, final Pattern numeric) {
        this.limits = limits;
        this.numeric = numeric;
    }

    /**
     * Groups neighboring baselines into uniform-column blocks; no sliding search through rejected blocks.
     * @param runs source runs
     * @param segments source strokes
     * @param images raster occurrences
     * @param work shared page counter
     * @return accepted disjoint source-run groups
     * @throws IOException if cancelled or a table/work budget is exceeded
     */
    @Override
    public List<PdfAlignedTable> detect(final List<PdfTextRun> runs, final List<PdfPageGraphics.Segment> segments,
            final List<PdfPageGraphics.Image> images, final PdfTableWork work) throws IOException {
        final var order = new ArrayList<Integer>();
        for (int index = 0; index < runs.size(); index++) {
            work.step();
            if (runs.get(index).rotation() == 0) {
                order.add(index);
            }
        }
        order.sort(Comparator.comparingDouble((Integer index) -> runs.get(index).bottom())
                        .thenComparingDouble(index -> runs.get(index).left()));
        final var rows = new ArrayList<List<Integer>>();
        float baseline = Float.NaN;
        for (final int index : order) {
            work.step();
            if (rows.isEmpty() || Math.abs(runs.get(index).bottom() - baseline) > 2) {
                rows.add(new ArrayList<>());
                baseline = runs.get(index).bottom();
            }
            rows.getLast().add(index);
        }
        final var result = new ArrayList<PdfAlignedTable>();
        final var block = new ArrayList<List<Integer>>();
        for (final var row : rows) {
            row.sort(Comparator.comparingDouble(index -> runs.get(index).left()));
            if (!block.isEmpty() && separated(block.getLast(), row, runs)) {
                accept(block, runs, segments, images, result, work);
                block.clear();
            }
            block.add(row);
        }
        accept(block, runs, segments, images, result, work);
        return List.copyOf(result);
    }

    /**
     * Uses a large vertical gap or a single-run paragraph as a block boundary.
     * Ragged multi-column rows remain in the block and cause rejection rather than selective extraction.
     * @param previous previous baseline row
     * @param next next baseline row
     * @param runs source metrics
     * @return whether the rows belong to separate recognition blocks
     */
    private static boolean separated(
            final List<Integer> previous, final List<Integer> next, final List<PdfTextRun> runs) {
        return previous.size() < 2 || next.size() < 2
                || runs.get(next.getFirst()).bottom() - runs.get(previous.getFirst()).bottom() > 40;
    }

    /**
     * Applies strict pitch, alignment, numeric and geometric-barrier checks before retaining ownership.
     * @param rows complete proximity block
     * @param runs source runs
     * @param segments stroked ambiguity barriers
     * @param images raster ambiguity barriers
     * @param output accepted candidates
     * @param work shared counter
     * @throws IOException if cancelled or resource limits fail
     */
    private void accept(final List<List<Integer>> rows, final List<PdfTextRun> runs,
            final List<PdfPageGraphics.Segment> segments, final List<PdfPageGraphics.Image> images,
            final List<PdfAlignedTable> output, final PdfTableWork work) throws IOException {
        if (rows.size() < 3 || rows.getFirst().size() < 2) {
            return;
        }
        final int columns = rows.getFirst().size();
        final float pitch = runs.get(rows.get(1).getFirst()).bottom() - runs.get(rows.getFirst().getFirst()).bottom();
        float left = Float.POSITIVE_INFINITY;
        float top = Float.POSITIVE_INFINITY;
        float right = Float.NEGATIVE_INFINITY;
        float bottom = Float.NEGATIVE_INFINITY;
        for (int row = 0; row < rows.size(); row++) {
            work.step();
            if (rows.get(row).size() != columns) {
                return;
            }
            final float height = runs.get(rows.get(row).getFirst()).bottom() - runs.get(rows.get(row).getFirst()).top();
            if (pitch < Math.max(4, height * 1.2) || pitch > Math.min(40, Math.max(18, height * 5))) {
                return;
            }
            if (row > 0
                    && Math.abs(runs.get(rows.get(row).getFirst()).bottom()
                                - runs.get(rows.get(row - 1).getFirst()).bottom() - pitch)
                               > Math.max(2, pitch * 0.15)) {
                return;
            }
            for (final int index : rows.get(row)) {
                work.step();
                final var run = runs.get(index);
                if (run.text().codePointCount(0, run.text().length()) > MAX_CELL_POINTS) {
                    return;
                }
                left = Math.min(left, run.left());
                top = Math.min(top, run.top());
                right = Math.max(right, run.right());
                bottom = Math.max(bottom, run.bottom());
            }
        }
        final var numericColumns = new ArrayList<Integer>();
        float previousRight = Float.NEGATIVE_INFINITY;
        for (int column = 0; column < columns; column++) {
            float minLeft = Float.POSITIVE_INFINITY;
            float maxLeft = Float.NEGATIVE_INFINITY;
            float minRight = Float.POSITIVE_INFINITY;
            float maxRight = Float.NEGATIVE_INFINITY;
            boolean numericBody = true;
            for (int row = 0; row < rows.size(); row++) {
                work.step();
                final var run = runs.get(rows.get(row).get(column));
                minLeft = Math.min(minLeft, run.left());
                maxLeft = Math.max(maxLeft, run.left());
                minRight = Math.min(minRight, run.right());
                maxRight = Math.max(maxRight, run.right());
                if (row > 0 && !numeric.matcher(run.text()).matches()) {
                    numericBody = false;
                }
            }
            if (maxLeft - minLeft > 2 && maxRight - minRight > 2 || minLeft - previousRight < 12) {
                return;
            }
            previousRight = maxRight;
            if (numericBody) {
                numericColumns.add(column);
            }
        }
        if (numericColumns.isEmpty()) {
            return;
        }
        for (final var segment : segments) {
            work.step();
            if (intersects(left, top, right, bottom, Math.min(segment.x1(), segment.x2()),
                        Math.min(segment.y1(), segment.y2()), Math.max(segment.x1(), segment.x2()),
                        Math.max(segment.y1(), segment.y2()))) {
                return;
            }
        }
        for (final var image : images) {
            work.step();
            if (intersects(left, top, right, bottom, image.x(), image.y(), image.x() + image.width(),
                        image.y() + image.height())) {
                return;
            }
        }
        // Reject any unrelated run inside the candidate bounds; ownership is never inferred around intervening text.
        final var members = new java.util.HashSet<Integer>();
        rows.forEach(members::addAll);
        for (int index = 0; index < runs.size(); index++) {
            work.step();
            final var run = runs.get(index);
            if (!members.contains(index)
                    && intersects(left, top, right, bottom, run.left(), run.top(), run.right(), run.bottom())) {
                return;
            }
        }
        if (rows.size() > limits.maxRows() || columns > limits.maxColumns()
                || (long) rows.size() * columns > limits.maxCells()) {
            throw new PartitionException(PartitionException.Code.TABLE_LIMIT, "PDF aligned table exceeds table limits");
        }
        for (final var row : rows) {
            for (final int index : row) {
                work.step();
                final String value = runs.get(index).text();
                if (value.codePointCount(0, value.length()) > limits.maxCellCharacters()) {
                    throw new PartitionException(
                            PartitionException.Code.TABLE_LIMIT, "PDF aligned cell exceeds maxCellCharacters");
                }
            }
        }
        output.add(new PdfAlignedTable(rows.stream().map(List::copyOf).toList(), List.copyOf(numericColumns)));
    }

    /**
     * Tests inclusive box intersection, including strokes with zero width or height.
     * @param left first left
     * @param top first top
     * @param right first right
     * @param bottom first bottom
     * @param otherLeft second left
     * @param otherTop second top
     * @param otherRight second right
     * @param otherBottom second bottom
     * @return whether the boxes meet or overlap
     */
    private static boolean intersects(final float left, final float top, final float right, final float bottom,
            final float otherLeft, final float otherTop, final float otherRight, final float otherBottom) {
        return left <= otherRight && otherLeft <= right && top <= otherBottom && otherTop <= bottom;
    }
}
