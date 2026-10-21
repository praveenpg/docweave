package dev.docweave.layout;

import dev.docweave.partition.PartitionException;
import dev.docweave.partition.PdfTableLayoutProvider;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/**
 * Geometry-only TATR refinement and conservative complete-cell assembly, without OCR-driven row removal.
 * Inspired by Microsoft's MIT-licensed inference/postprocess rules; notice retained in this artifact.
 * Conflicting merges follow an explicit policy; nonrectangular merges and overlapping final tables are rejected.
 */
final class TableTopology {
    /** Hard slot bound before allocating an owner array, in addition to caller limits/work checks. */
    private static final int MAX_SLOTS = 2_000_000;
    /** Immutable call resource/model policy. */
    private final OnnxTableOptions options;
    /** Shared deadline/geometry counter across all candidate tables. */
    private final LayoutWork work;
    /** Projected atomic slots consumed by candidate assembly, including subsequently rejected candidates. */
    private long slots;

    /**
     * Creates one call-confined geometry decoder.
     * @param options immutable dimensions/resource policy
     * @param deadline existing total call budget
     */
    TableTopology(final OnnxTableOptions options, final LayoutDeadline deadline) {
        this(options, new LayoutWork(deadline));
    }

    /**
     * Shares one geometry budget across detection, multiple structure crops and final translation.
     * @param options remaining structured geometry policy
     * @param work parent call-confined geometry/cancellation budget
     */
    TableTopology(final OnnxTableOptions options, final LayoutWork work) {
        this.options = options;
        this.work = work;
    }

    /**
     * Reports prospective slots charged before assembly, including rejected candidates.
     * @return consumed atomic-slot allowance
     */
    long consumedSlots() {
        return slots;
    }

    /**
     * Refines thresholded objects and returns disjoint complete table anchors in source pixels.
     * @param objects finite clipped objects from the fixed-label tensor decoder
     * @return immutable complete nonambiguous layouts with explicit model/policy provenance
     * @throws IOException if cancellation, geometry work or structured-cell limits fail
     */
    List<PdfTableLayoutProvider.Table> tables(final List<LayoutObject> objects) throws IOException {
        final var candidates = new ArrayList<PdfTableLayoutProvider.Table>();
        for (final var table : suppress(objects.stream().filter(object -> object.label() == 0).toList(), .5)) {
            final var members = new ArrayList<LayoutObject>();
            for (final var object : objects) {
                work.step();
                if (fraction(object.box(), table.box()) >= .5) {
                    members.add(object);
                }
            }
            final var rows = suppress(members.stream().filter(object -> object.label() == 2).toList(), .5)
                                     .stream()
                                     .sorted(Comparator.comparingDouble(object -> centerY(object.box())))
                                     .toList();
            final var columns = suppress(members.stream().filter(object -> object.label() == 1).toList(), .25)
                                        .stream()
                                        .sorted(Comparator.comparingDouble(object -> centerX(object.box())))
                                        .toList();
            if (rows.isEmpty() || columns.isEmpty()) {
                continue;
            }
            final var limits = options.tableLimits();
            final long count = (long) rows.size() * columns.size();
            if (rows.size() > limits.maxRows() || columns.size() > limits.maxColumns() || count > MAX_SLOTS
                    || count > limits.maxCells() - slots) {
                throw new PartitionException(
                        PartitionException.Code.TABLE_LIMIT, "ONNX table exceeds geometry/cell limits");
            }
            slots += count;
            final var xs = boundaries(columns, true);
            final var ys = boundaries(rows, false);
            if (xs == null || ys == null) {
                continue;
            }
            final var result = assemble(xs, ys, members);
            if (result != null) {
                candidates.add(result);
            }
        }
        final var ambiguous = new boolean[candidates.size()];
        for (int first = 0; first < candidates.size(); first++) {
            for (int second = first + 1; second < candidates.size(); second++) {
                work.step();
                if (intersection(candidates.get(first).bounds(), candidates.get(second).bounds()) > 0) {
                    ambiguous[first] = true;
                    ambiguous[second] = true;
                }
            }
        }
        final var accepted = new ArrayList<PdfTableLayoutProvider.Table>();
        for (int index = 0; index < candidates.size(); index++) {
            if (!ambiguous[index]) {
                accepted.add(candidates.get(index));
            }
        }
        return List.copyOf(accepted);
    }

    /**
     * Suppresses lower-score objects by intersection over the lower-score object's area.
     * Stable encounter order resolves equal scores; no token content or case-specific adjustment is used.
     * @param objects same-class candidate rectangles
     * @param threshold fixed containment suppression fraction
     * @return score-ordered surviving objects
     * @throws IOException if work/deadline/cancellation prevents comparisons
     */
    private List<LayoutObject> suppress(final List<LayoutObject> objects, final double threshold) throws IOException {
        final var sorted = objects.stream().sorted(Comparator.comparingDouble(LayoutObject::score).reversed()).toList();
        final var accepted = new ArrayList<LayoutObject>();
        for (final var candidate : sorted) {
            boolean duplicate = false;
            for (final var previous : accepted) {
                work.step();
                if (fraction(candidate.box(), previous.box()) >= threshold) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                accepted.add(candidate);
            }
        }
        return List.copyOf(accepted);
    }

    /**
     * Aligns neighboring axis estimates at shared midpoint boundaries and rejects inverted/ambiguous centers.
     * @param axis center-sorted rows or columns
     * @param horizontal true for column x bounds, false for row y bounds
     * @return strict positive boundary sequence or null for ambiguous geometry
     * @throws IOException if bounded geometry work/cancellation fails
     */
    private double[] boundaries(final List<LayoutObject> axis, final boolean horizontal) throws IOException {
        final var result = new double[axis.size() + 1];
        result[0] = horizontal ? axis.getFirst().box().left() : axis.getFirst().box().top();
        result[axis.size()] = horizontal ? axis.getLast().box().right() : axis.getLast().box().bottom();
        for (int index = 1; index < axis.size(); index++) {
            work.step();
            final var previous = axis.get(index - 1).box();
            final var next = axis.get(index).box();
            result[index] = horizontal ? previous.right() + (next.left() - previous.right()) / 2
                                       : previous.bottom() + (next.top() - previous.bottom()) / 2;
        }
        for (int index = 0; index < axis.size(); index++) {
            work.step();
            final var box = axis.get(index).box();
            final double center = horizontal ? centerX(box) : centerY(box);
            if (result[index + 1] <= result[index] || center <= result[index] || center >= result[index + 1]) {
                return null;
            }
        }
        return result;
    }

    /**
     * Merges only complete noncrossing rectangular groups selected by more-than-half atomic-cell overlap.
     * Duplicate merge estimates are ignored. Nonrectangular selections always reject the whole table;
     * distinct overlapping rectangles follow the explicit conflict policy, with equal-score ties rejected.
     * @param xs strict column boundaries
     * @param ys strict row boundaries
     * @param objects source table members
     * @return complete immutable layout or null if span predictions are ambiguous
     * @throws IOException if work/deadline limits prevent assembly
     */
    private PdfTableLayoutProvider.Table assemble(
            final double[] xs, final double[] ys, final List<LayoutObject> objects) throws IOException {
        final int rows = ys.length - 1;
        final int columns = xs.length - 1;
        final var owners = new int[rows * columns];
        Arrays.fill(owners, -1);
        final var merges = new ArrayList<Merge>();
        final var mergeScores = new ArrayList<Double>();
        final var unique = new HashSet<Merge>();
        for (final var object : objects.stream()
                        .filter(candidate -> candidate.label() == 4 || candidate.label() == 5)
                        .sorted(Comparator.comparingDouble(LayoutObject::score).reversed())
                        .toList()) {
            int firstRow = rows;
            int lastRow = -1;
            int firstColumn = columns;
            int lastColumn = -1;
            int selected = 0;
            for (int row = 0; row < rows; row++) {
                for (int column = 0; column < columns; column++) {
                    work.step();
                    final var cell = box(xs, ys, row, column, 1, 1);
                    if (fraction(cell, object.box()) > .5) {
                        firstRow = Math.min(firstRow, row);
                        lastRow = Math.max(lastRow, row);
                        firstColumn = Math.min(firstColumn, column);
                        lastColumn = Math.max(lastColumn, column);
                        selected++;
                    }
                }
            }
            if (selected < 2) {
                continue;
            }
            final var merge = new Merge(firstRow, firstColumn, lastRow - firstRow + 1, lastColumn - firstColumn + 1);
            if ((long) merge.rows() * merge.columns() != selected) {
                return null;
            }
            if (!unique.add(merge)) {
                continue;
            }
            if (options.mergeConflicts() == OnnxTableOptions.MergeConflicts.KEEP_HIGHER_SCORE) {
                boolean conflict = false;
                // Preflight ownership before claiming any slot: a later collision must not leave a partial merge.
                // Every comparison is charged, including proposals that are subsequently discarded.
                for (int row = firstRow; row <= lastRow; row++) {
                    for (int column = firstColumn; column <= lastColumn; column++) {
                        work.step();
                        final int owner = owners[row * columns + column];
                        if (owner >= 0) {
                            if (Double.compare(mergeScores.get(owner), object.score()) == 0) {
                                return null;
                            }
                            conflict = true;
                        }
                    }
                }
                if (conflict) {
                    continue;
                }
            }
            for (int row = firstRow; row <= lastRow; row++) {
                for (int column = firstColumn; column <= lastColumn; column++) {
                    work.step();
                    if (owners[row * columns + column] != -1) {
                        return null;
                    }
                    owners[row * columns + column] = merges.size();
                }
            }
            merges.add(merge);
            mergeScores.add(object.score());
        }
        final var anchors = new ArrayList<PdfTableLayoutProvider.Anchor>();
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                work.step();
                final int owner = owners[row * columns + column];
                if (owner == -1) {
                    anchors.add(new PdfTableLayoutProvider.Anchor(row, column, 1, 1, box(xs, ys, row, column, 1, 1)));
                } else {
                    final var merge = merges.get(owner);
                    if (row == merge.row() && column == merge.column()) {
                        anchors.add(new PdfTableLayoutProvider.Anchor(row, column, merge.rows(), merge.columns(),
                                box(xs, ys, row, column, merge.rows(), merge.columns())));
                    }
                }
            }
        }
        return new PdfTableLayoutProvider.Table(rows, columns,
                new PdfTableLayoutProvider.Box(xs[0], ys[0], xs[columns], ys[rows]), anchors,
                "onnx_tatr_geometry_v1;sha256=" + options.sha256()
                        + ";max_hw800_bilinear;score=" + options.minimumScore()
                        + (options.mergeConflicts() == OnnxTableOptions.MergeConflicts.KEEP_HIGHER_SCORE
                                        ? ";merge_conflicts=keep_higher_score"
                                        : ""),
                headerRows(ys, objects));
    }

    /**
     * Retains only a model-declared contiguous top prefix, without inventing missing header rows.
     * A row qualifies when one class3 object covers at least half its aligned vertical extent.
     * The first unqualified row stops the prefix, so later projected/ordinary rows are not promoted.
     * No tokens, strings, source labels, scores-as-confidence or row/anchor merges are involved.
     * @param ys unchanged atomic row edges
     * @param objects already validated table-member predictions
     * @return number of declared header rows from the top, zero if unknown or absent
     * @throws IOException if shared geometry work or cancellation prevents classification
     */
    private int headerRows(final double[] ys, final List<LayoutObject> objects) throws IOException {
        final var headers = objects.stream().filter(object -> object.label() == 3).toList();
        if (headers.isEmpty()) {
            return 0;
        }
        int prefix = 0;
        for (int row = 0; row < ys.length - 1; row++) {
            boolean declared = false;
            for (final var header : headers) {
                work.step();
                final double overlap =
                        Math.min(ys[row + 1], header.box().bottom()) - Math.max(ys[row], header.box().top());
                if (overlap >= (ys[row + 1] - ys[row]) * 0.5) {
                    declared = true;
                    break;
                }
            }
            if (!declared) {
                break;
            }
            prefix++;
        }
        return prefix;
    }

    /**
     * Returns a positive source rectangle for one validated grid anchor.
     * @param xs column boundaries
     * @param ys row boundaries
     * @param row first row
     * @param column first column
     * @param rows occupied rows
     * @param columns occupied columns
     * @return exact shared-boundary rectangle
     */
    private static PdfTableLayoutProvider.Box box(
            final double[] xs, final double[] ys, final int row, final int column, final int rows, final int columns) {
        return new PdfTableLayoutProvider.Box(xs[column], ys[row], xs[column + columns], ys[row + rows]);
    }

    /**
     * Computes intersection over the first rectangle's area for geometry-only selection/NMS.
     * @param first candidate/atomic-cell rectangle
     * @param second containing or suppressing rectangle
     * @return finite overlap fraction from zero through one
     */
    private static double fraction(final PdfTableLayoutProvider.Box first, final PdfTableLayoutProvider.Box second) {
        return intersection(first, second) / ((first.right() - first.left()) * (first.bottom() - first.top()));
    }

    /**
     * Computes positive intersection area, allowing disjoint rectangles to return zero.
     * @param first first source rectangle
     * @param second second source rectangle
     * @return nonnegative intersection area
     */
    private static double intersection(
            final PdfTableLayoutProvider.Box first, final PdfTableLayoutProvider.Box second) {
        return Math.max(0, Math.min(first.right(), second.right()) - Math.max(first.left(), second.left()))
                * Math.max(0, Math.min(first.bottom(), second.bottom()) - Math.max(first.top(), second.top()));
    }

    /**
     * Returns the horizontal center without adding potentially large edges first.
     * @param box positive source rectangle
     * @return center x
     */
    private static double centerX(final PdfTableLayoutProvider.Box box) {
        return box.left() + (box.right() - box.left()) / 2;
    }

    /**
     * Returns the vertical center without adding potentially large edges first.
     * @param box positive source rectangle
     * @return center y
     */
    private static double centerY(final PdfTableLayoutProvider.Box box) {
        return box.top() + (box.bottom() - box.top()) / 2;
    }

    /**
     * One unique rectangular merge in atomic row/column indices.
     * @param row first row
     * @param column first column
     * @param rows occupied rows
     * @param columns occupied columns
     */
    private record Merge(int row, int column, int rows, int columns) {}
}
