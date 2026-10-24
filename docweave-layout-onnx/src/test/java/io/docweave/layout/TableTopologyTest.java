package io.docweave.layout;

import io.docweave.partition.PartitionException;
import io.docweave.partition.PdfTableLayoutProvider.Box;
import io.docweave.partition.TableLimits;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TableTopologyTest {
    private static OnnxTableOptions options() {
        return OnnxTableOptions.defaults(Path.of("unused-local-model"), "0".repeat(64));
    }

    private static LayoutObject object(
            final int label, final double l, final double t, final double r, final double b) {
        return new LayoutObject(label, .99, new Box(l, t, r, b));
    }

    private static ArrayList<LayoutObject> grid() {
        return new ArrayList<>(List.of(object(0, 0, 0, 100, 100), object(1, 0, 0, 50, 100), object(1, 50, 0, 100, 100),
                object(2, 0, 0, 100, 50), object(2, 0, 50, 100, 100)));
    }

    @Test
    void headerEvidenceDoesNotSplitAnAnchorCrossingTheHeaderBoundary() throws Exception {
        final var objects = grid();
        objects.add(object(5, 0, 0, 50, 100));
        objects.add(object(3, 0, 0, 100, 50));
        final var table =
                new TableTopology(options(), new LayoutDeadline(Duration.ofSeconds(1))).tables(objects).getFirst();
        assertEquals(1, table.headerRows());
        assertEquals(2, table.rows());
        assertEquals(3, table.anchors().size());
        assertEquals(2, table.anchors().getFirst().rowSpan());
    }

    @Test
    void declaredHeadersPreserveStackedRowsDataRowsAndAllAnchorGeometry() throws Exception {
        final var objects = grid();
        objects.removeIf(candidate -> candidate.label() == 2);
        objects.addAll(List.of(object(2, 0, 0, 100, 30), object(2, 0, 30, 100, 60), object(2, 0, 60, 100, 100)));
        final var plain =
                new TableTopology(options(), new LayoutDeadline(Duration.ofSeconds(1))).tables(objects).getFirst();
        objects.add(object(3, 0, 0, 100, 60));
        final var marked =
                new TableTopology(options(), new LayoutDeadline(Duration.ofSeconds(1))).tables(objects).getFirst();
        assertEquals(0, plain.headerRows());
        assertEquals(2, marked.headerRows());
        assertEquals(3, marked.rows(), "Two declared header levels must remain two atomic rows");
        assertEquals(plain.bounds(), marked.bounds());
        assertEquals(plain.anchors(), marked.anchors());
        assertEquals(plain.provenance(), marked.provenance());
        java.util.Collections.reverse(objects);
        assertEquals(marked,
                new TableTopology(options(), new LayoutDeadline(Duration.ofSeconds(1))).tables(objects).getFirst());
    }

    @Test
    void headerPrefixStopsAtFirstGapWithoutPromotingLaterHeaders() throws Exception {
        final var lowerOnly = grid();
        lowerOnly.add(object(3, 0, 50, 100, 100));
        assertEquals(0, new TableTopology(options(), new LayoutDeadline(Duration.ofSeconds(1)))
                                .tables(lowerOnly)
                                .getFirst()
                                .headerRows());
        final var gap = grid();
        gap.removeIf(candidate -> candidate.label() == 2);
        gap.addAll(List.of(object(2, 0, 0, 100, 30), object(2, 0, 30, 100, 60), object(2, 0, 60, 100, 100),
                object(3, 0, 0, 100, 30), object(3, 0, 60, 100, 100)));
        assertEquals(1, new TableTopology(options(), new LayoutDeadline(Duration.ofSeconds(1)))
                                .tables(gap)
                                .getFirst()
                                .headerRows());
    }

    @Test
    void headerHalfHeightThresholdAndDuplicateEvidenceHaveExplicitBoundaries() throws Exception {
        final var half = grid();
        half.add(object(3, 0, 0, 100, 25));
        half.add(object(3, 0, 0, 100, 25));
        assertEquals(1, new TableTopology(options(), new LayoutDeadline(Duration.ofSeconds(1)))
                                .tables(half)
                                .getFirst()
                                .headerRows());
        final var below = grid();
        below.add(object(3, 0, 0, 100, 24.999));
        assertEquals(0, new TableTopology(options(), new LayoutDeadline(Duration.ofSeconds(1)))
                                .tables(below)
                                .getFirst()
                                .headerRows());
        final var projected = grid();
        projected.add(object(4, 0, 0, 100, 50));
        assertEquals(0, new TableTopology(options(), new LayoutDeadline(Duration.ofSeconds(1)))
                                .tables(projected)
                                .getFirst()
                                .headerRows());
    }

    @Test
    void emptyCellsSurviveAndDuplicateSpansAreSuppressed() throws Exception {
        final var objects = grid();
        objects.add(object(5, 0, 0, 100, 50));
        objects.add(object(5, 0, 0, 100, 50));
        final var tables = new TableTopology(options(), new LayoutDeadline(Duration.ofSeconds(1))).tables(objects);
        assertEquals(1, tables.size());
        assertEquals(3, tables.getFirst().anchors().size());
        assertEquals(2, tables.getFirst().anchors().getFirst().columnSpan());
    }

    @Test
    void crossingSpansRejectWholeTableRatherThanInventTopology() throws Exception {
        final var objects = grid();
        objects.add(object(5, 0, 0, 100, 50));
        objects.add(object(5, 0, 0, 50, 100));
        assertTrue(new TableTopology(options(), new LayoutDeadline(Duration.ofSeconds(1))).tables(objects).isEmpty());
    }

    @Test
    void prospectiveSlotsRespectCallerCeilingEvenWhenMerged() {
        final var objects = grid();
        objects.add(object(5, 0, 0, 100, 100));
        final var capped = options().withTableLimits(new TableLimits(2, 2, 3, 100));
        final var failure = assertThrows(PartitionException.class,
                () -> new TableTopology(capped, new LayoutDeadline(Duration.ofSeconds(1))).tables(objects));
        assertEquals(PartitionException.Code.TABLE_LIMIT, failure.code());
    }

    @Test
    void absentRowsOrColumnsYieldNoClaim() throws Exception {
        assertTrue(new TableTopology(options(), new LayoutDeadline(Duration.ofSeconds(1)))
                        .tables(List.of(object(0, 0, 0, 100, 100)))
                        .isEmpty());
    }

    @Test
    void explicitHigherScorePolicyDiscardsWholeConflictingProposalWithoutPartialOwnership() throws Exception {
        final var objects = grid();
        objects.add(new LayoutObject(5, .7, new Box(0, 0, 100, 50)));
        objects.add(new LayoutObject(5, .9, new Box(50, 0, 100, 100)));
        final var selected = options().withMergeConflicts(OnnxTableOptions.MergeConflicts.KEEP_HIGHER_SCORE);
        final var tables = new TableTopology(selected, new LayoutDeadline(Duration.ofSeconds(1))).tables(objects);
        assertEquals(1, tables.size());
        final var anchors = tables.getFirst().anchors();
        assertEquals(3, anchors.size());
        assertEquals(1, anchors.get(0).rowSpan());
        assertEquals(1, anchors.get(0).columnSpan());
        assertEquals(0, anchors.get(0).column());
        assertEquals(2, anchors.get(1).rowSpan());
        assertEquals(1, anchors.get(1).column());
        assertEquals(1, anchors.get(2).row());
        assertEquals(0, anchors.get(2).column());
        assertTrue(tables.getFirst().provenance().endsWith(";merge_conflicts=keep_higher_score"));
        java.util.Collections.reverse(objects);
        assertEquals(tables, new TableTopology(selected, new LayoutDeadline(Duration.ofSeconds(1))).tables(objects));
        assertTrue(new TableTopology(options(), new LayoutDeadline(Duration.ofSeconds(1))).tables(objects).isEmpty());
    }

    @Test
    void equalScoreConflictsStillRejectInsteadOfUsingEncounterOrder() throws Exception {
        final var objects = grid();
        objects.add(object(5, 0, 0, 100, 50));
        objects.add(object(5, 50, 0, 100, 100));
        final var selected = options().withMergeConflicts(OnnxTableOptions.MergeConflicts.KEEP_HIGHER_SCORE);
        assertTrue(new TableTopology(selected, new LayoutDeadline(Duration.ofSeconds(1))).tables(objects).isEmpty());
        java.util.Collections.reverse(objects);
        assertTrue(new TableTopology(selected, new LayoutDeadline(Duration.ofSeconds(1))).tables(objects).isEmpty());
    }

    @Test
    void nonrectangularSlotSelectionStillRejectsWithExplicitPolicy() throws Exception {
        final var objects = grid();
        objects.add(object(5, 5, 5, 80, 80)); // Three slots exceed half overlap, forming an L.
        final var selected = options().withMergeConflicts(OnnxTableOptions.MergeConflicts.KEEP_HIGHER_SCORE);
        assertTrue(new TableTopology(selected, new LayoutDeadline(Duration.ofSeconds(1))).tables(objects).isEmpty());
    }

    @Test
    void duplicateEstimatesRemainOneMergeWithExplicitPolicy() throws Exception {
        final var objects = grid();
        objects.add(object(5, 0, 0, 100, 50));
        objects.add(object(4, 0, 0, 100, 50));
        final var selected = options().withMergeConflicts(OnnxTableOptions.MergeConflicts.KEEP_HIGHER_SCORE);
        assertEquals(3, new TableTopology(selected, new LayoutDeadline(Duration.ofSeconds(1)))
                                .tables(objects)
                                .getFirst()
                                .anchors()
                                .size());
    }

    @Test
    void newPolicyPreservesLimitsAndCallerInterruption() {
        final var selected = options()
                                     .withMergeConflicts(OnnxTableOptions.MergeConflicts.KEEP_HIGHER_SCORE)
                                     .withTableLimits(new TableLimits(2, 2, 3, 100));
        final var failure = assertThrows(PartitionException.class,
                () -> new TableTopology(selected, new LayoutDeadline(Duration.ofSeconds(1))).tables(grid()));
        assertEquals(PartitionException.Code.TABLE_LIMIT, failure.code());
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.io.InterruptedIOException.class,
                    () -> new TableTopology(selected, new LayoutDeadline(Duration.ofSeconds(1))).tables(grid()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void formerConstructorAndCopiesPreserveExplicitOrDefaultPolicy() {
        final var base = options();
        final var old = new OnnxTableOptions(base.model(), base.sha256(), base.timeout(), base.maxConcurrentCalls(),
                base.maxModelBytes(), base.maxInputPixels(), base.maxQueries(), base.tableLimits(), base.minimumScore(),
                base.nativeThreads());
        assertEquals(base, old);
        assertEquals(OnnxTableOptions.MergeConflicts.REJECT_TABLE, base.mergeConflicts());
        final var selected = base.withMergeConflicts(OnnxTableOptions.MergeConflicts.KEEP_HIGHER_SCORE);
        assertEquals(selected, selected.withTimeout(selected.timeout())
                                       .withConcurrency(selected.maxConcurrentCalls())
                                       .withTableLimits(selected.tableLimits()));
        assertThrows(NullPointerException.class, () -> base.withMergeConflicts(null));
    }
}
