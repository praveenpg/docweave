package io.docweave.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Association and coordinate-frame boundaries, migrated from {@code test_tiled_regions.py}. */
class TiledRegionScorerTest {
    @TempDir Path temporary;

    /** Creates the guards. */
    TiledRegionScorerTest() {
    }

    /** One-cell reference used by the association controls. */
    private static final TableGeometry.Table REFERENCE = TableGeometry.Table.of(
            1, 1, Set.of(new TableGeometry.SpanKey(0, 0, 1, 1)), new TableGeometry.Box(0, 0, 10, 10));

    @Test
    void identicalSpanKeysWithoutSpatialOverlapAreNotAMatch() {
        final var candidate = new TableGeometry.Table(
                "whole", 0, 0, 1, 1, REFERENCE.anchors(), new TableGeometry.Box(20, 20, 30, 30));
        final var result = StructureMetrics.highestOverlap(REFERENCE, List.of(candidate));
        assertEquals(false, result.get("exact_topology"));
        assertEquals(0L, result.get("matching_anchors"));
        assertEquals(true, result.get("no_positive_overlap"));
    }

    @Test
    void spatialAssociationDoesNotSelectAnOracleTopology() {
        final var wrong = new TableGeometry.Table("whole", 0, 0, 2, 1,
                Set.of(new TableGeometry.SpanKey(0, 0, 1, 1), new TableGeometry.SpanKey(1, 0, 1, 1)), REFERENCE.box());
        final var oracle =
                new TableGeometry.Table("upper", 0, 0, 1, 1, REFERENCE.anchors(), new TableGeometry.Box(0, 0, 2, 2));
        final var result = StructureMetrics.highestOverlap(REFERENCE, List.of(oracle, wrong));
        assertEquals("whole", result.get("profile"));
        assertEquals(false, result.get("exact_topology"));
        assertEquals(1.0, ((Number) result.get("best_iou")).doubleValue());
    }

    @Test
    void rasterCanvasFloorDoesNotRescaleSourceGeometry() throws IOException {
        final Path path = temporary;
        Files.writeString(path.resolve("outcome.txt"), "OK\n");
        Files.writeString(path.resolve("tables.tsv"),
                "crop\ttable\trows\tcolumns\tleft\ttop\tright\tbottom\n0\t0\t1\t1\t0\t0\t90\t90\n");
        Files.writeString(
                path.resolve("anchors.tsv"), "crop\ttable\trow\tcolumn\trow_span\tcolumn_span\n0\t0\t0\t0\t1\t1\n");
        final var frame = Map.of("original_width", "100", "original_height", "100", "width", "99", "height", "99",
                "pdf_width", "48", "pdf_height", "48", "render_dpi", "150");
        assertEquals(new TableGeometry.Box(0, 0, 90, 90), TableGeometry.predictions(path, frame).getFirst().box());
        Files.writeString(path.resolve("outcome.txt"), "OCR_LIMIT\n");
        assertEquals(List.of(), TableGeometry.predictions(path, frame));
    }
}
