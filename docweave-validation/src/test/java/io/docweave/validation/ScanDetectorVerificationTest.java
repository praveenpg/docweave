package io.docweave.validation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Geometry-only proposal comparisons, migrated from {@code test_scan_detectors.py}; no inference runs.
 */
class ScanDetectorVerificationTest {
    /** Creates the controls. */
    ScanDetectorVerificationTest() {
    }

    @Test
    void duplicatePredictionsCannotCountAReferenceTwice() {
        final var box = new TableGeometry.Box(0, 0, 10, 10);
        final var result = StructureMetrics.proposals(List.of(box), List.of(box, box));
        assertEquals(1L, result.get("matched_reference_tables_at_iou_0_5"));
        assertEquals(1L, result.get("unmatched_proposals"));
    }

    @Test
    void augmentingPathAvoidsOrderDependentUnderCount() {
        final var references = List.of(new TableGeometry.Box(0, 0, 10, 10), new TableGeometry.Box(0, 0, 5, 10));
        final var candidates = List.of(new TableGeometry.Box(0, 0, 8, 10), new TableGeometry.Box(5, 0, 10, 10));
        assertEquals(2, TableGeometry.maximumMatches(references, candidates, 0.5).size());
        assertEquals(2, TableGeometry.maximumMatches(references.reversed(), candidates, 0.5).size());
    }

    @Test
    void emptyAndCanvasFloorDoNotInventMatchesOrRescaling() {
        assertEquals(
                List.of(), TableGeometry.maximumMatches(List.of(new TableGeometry.Box(0, 0, 10, 10)), List.of(), 0.5));
        final var frame = Map.of("original_width", "100", "original_height", "100", "width", "99", "height", "99",
                "pdf_width", "48", "pdf_height", "48", "render_dpi", "150");
        assertEquals(new TableGeometry.Box(0, 0, 90, 90),
                TableGeometry.sourceBox(new TableGeometry.Box(0, 0, 90, 90), frame));
    }
}
