package dev.docweave.partition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

/**
 * Explicit narrow extension of aligned recognition for one wrapped first-column line per body row.
 * Complete proximity blocks are considered; rejected blocks are never searched for a wrapped subtable.
 */
final class MultilinePdfTableDetector implements PdfBorderlessTableDetector {
    /** Reused numeric, alignment, field and resource policy. */
    private final AlignedPdfTableDetector aligned;

    /**
     * Captures the existing immutable limits through the single-line strategy.
     * @param limits table resource limits
     */
    MultilinePdfTableDetector(final TableLimits limits) {
        aligned = new AlignedPdfTableDetector(limits);
    }

    /**
     * Retains historical candidates and adds disjoint, fully validated wrapped candidates.
     * @param runs page source runs
     * @param segments stroke barriers
     * @param images raster barriers
     * @param work shared bounded work counter
     * @return immutable candidates with original source ownership
     * @throws IOException if cancelled or resource limits are exceeded
     */
    @Override
    public List<PdfAlignedTable> detect(final List<PdfTextRun> runs, final List<PdfPageGraphics.Segment> segments,
            final List<PdfPageGraphics.Image> images, final PdfTableWork work) throws IOException {
        final var result = new ArrayList<>(aligned.detect(runs, segments, images, work));
        final var order = new ArrayList<Integer>();
        for (int index = 0; index < runs.size(); index++) {
            work.step();
            order.add(index);
        }
        order.sort(Comparator.comparingDouble((Integer index) -> runs.get(index).bottom())
                        .thenComparingDouble(index -> runs.get(index).left()));
        final var block = new ArrayList<List<Integer>>();
        float baseline = Float.NaN;
        for (final int index : order) {
            work.step();
            final float next = runs.get(index).bottom();
            if (!block.isEmpty() && next - baseline > 40) {
                accept(block, runs, segments, images, result, work);
                block.clear();
            }
            if (block.isEmpty() || Math.abs(next - baseline) > 2) {
                block.add(new ArrayList<>());
                baseline = next;
            }
            block.getLast().add(index);
        }
        accept(block, runs, segments, images, result, work);
        return List.copyOf(result);
    }

    /**
     * Collapses only a complete header followed by at least two complete anchor/continuation pairs.
     * Synthetic recognition metrics retain anchor baselines; output always uses the original union bounds.
     * @param rows baseline groups in the entire proximity block
     * @param runs original runs
     * @param segments source strokes
     * @param images source raster occurrences
     * @param output accepted disjoint tables
     * @param work shared work counter
     * @throws IOException if cancelled or a table/work limit fails
     */
    private void accept(final List<List<Integer>> rows, final List<PdfTextRun> runs,
            final List<PdfPageGraphics.Segment> segments, final List<PdfPageGraphics.Image> images,
            final List<PdfAlignedTable> output, final PdfTableWork work) throws IOException {
        if (rows.size() < 5 || rows.size() % 2 == 0 || rows.getFirst().size() < 2) {
            return;
        }
        for (final var row : rows) {
            work.step();
            row.sort(Comparator.comparingDouble(index -> runs.get(index).left()));
        }
        final int columns = rows.getFirst().size();
        final var synthetic = new ArrayList<PdfTextRun>();
        final var originals = new ArrayList<Integer>();
        final var continuations = new HashMap<Integer, Integer>();
        final var members = new java.util.HashSet<Integer>();
        float wrapPitch = Float.NaN;
        float left = Float.POSITIVE_INFINITY;
        float top = Float.POSITIVE_INFINITY;
        float right = Float.NEGATIVE_INFINITY;
        float bottom = Float.NEGATIVE_INFINITY;
        for (int row = 0; row < rows.size(); row += row == 0 ? 1 : 2) {
            work.step();
            final var anchors = rows.get(row);
            if (anchors.size() != columns) {
                return;
            }
            PdfTextRun extra = null;
            if (row > 0) {
                final var wrapped = rows.get(row + 1);
                if (wrapped.size() != 1) {
                    return;
                }
                final int index = wrapped.getFirst();
                extra = runs.get(index);
                final var anchor = runs.get(anchors.getFirst());
                final float gap = extra.bottom() - anchor.bottom();
                // A distinct short line, clearly closer to its own row than the next anchor would be.
                final float pitch =
                        runs.get(rows.get(1).getFirst()).bottom() - runs.get(rows.getFirst().getFirst()).bottom();
                if (extra.rotation() != 0 || Math.abs(extra.left() - anchor.left()) > 2 || extra.top() < anchor.bottom()
                        || gap < 6 || gap > 14 || pitch < gap * 2.5
                        || extra.right() > runs.get(anchors.get(1)).left() - 12
                        || extra.text().codePoints().noneMatch(Character::isLetter)
                        || !Float.isNaN(wrapPitch) && Math.abs(gap - wrapPitch) > 1) {
                    return;
                }
                wrapPitch = gap;
                continuations.put(anchors.getFirst(), index);
                members.add(index);
                left = Math.min(left, extra.left());
                top = Math.min(top, extra.top());
                right = Math.max(right, extra.right());
                bottom = Math.max(bottom, extra.bottom());
            }
            for (int column = 0; column < columns; column++) {
                work.step();
                final int index = anchors.get(column);
                final var run = runs.get(index);
                if (run.rotation() != 0) {
                    return;
                }
                originals.add(index);
                members.add(index);
                synthetic.add(extra != null && column == 0
                                      ? new PdfTextRun(run.text() + " " + extra.text(), run.left(), run.top(),
                                                Math.max(run.right(), extra.right()), run.bottom())
                                      : run);
                left = Math.min(left, run.left());
                top = Math.min(top, run.top());
                right = Math.max(right, run.right());
                bottom = Math.max(bottom, run.bottom());
            }
        }
        // Check the full union, including the last continuation below the final recognition baseline.
        for (final var stroke : segments) {
            work.step();
            if (intersects(left, top, right, bottom, Math.min(stroke.x1(), stroke.x2()),
                        Math.min(stroke.y1(), stroke.y2()), Math.max(stroke.x1(), stroke.x2()),
                        Math.max(stroke.y1(), stroke.y2()))) {
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
        for (int index = 0; index < runs.size(); index++) {
            work.step();
            final var run = runs.get(index);
            if (!members.contains(index)
                    && intersects(left, top, right, bottom, run.left(), run.top(), run.right(), run.bottom())) {
                return;
            }
        }
        for (final var table : output) {
            for (final var row : table.rows()) {
                for (final int index : row) {
                    work.step();
                    if (members.contains(index)) {
                        return;
                    }
                }
            }
        }
        for (final var candidate : aligned.detect(synthetic, List.of(), List.of(), work)) {
            // A candidate must own every synthetic cell, not merely a valid subset of the block.
            if (candidate.rows().size() * columns != synthetic.size()) {
                return;
            }
            output.add(new PdfAlignedTable(
                    candidate.rows().stream().map(row -> row.stream().map(originals::get).toList()).toList(),
                    candidate.numericColumns(), java.util.Map.copyOf(continuations)));
        }
    }

    /**
     * Tests inclusive bounds so even zero-width strokes act as barriers.
     * @param left first left
     * @param top first top
     * @param right first right
     * @param bottom first bottom
     * @param otherLeft second left
     * @param otherTop second top
     * @param otherRight second right
     * @param otherBottom second bottom
     * @return whether the boxes meet
     */
    private static boolean intersects(final float left, final float top, final float right, final float bottom,
            final float otherLeft, final float otherTop, final float otherRight, final float otherBottom) {
        return left <= otherRight && otherLeft <= right && top <= otherBottom && otherTop <= bottom;
    }
}
