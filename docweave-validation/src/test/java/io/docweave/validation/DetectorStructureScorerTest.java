package io.docweave.validation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reference-free preparation and geometry-only association, migrated from {@code test_detector_structures.py}.
 */
class DetectorStructureScorerTest {
    /** Creates the controls. */
    DetectorStructureScorerTest() {
    }

    /**
     * Builds a minimal positional-span fixture.
     *
     * @param box envelope
     * @param anchors span keys
     * @return one-by-one table
     */
    private static TableGeometry.Table table(final TableGeometry.Box box, final Set<TableGeometry.SpanKey> anchors) {
        return new TableGeometry.Table("test", 0, 0, 1, 1, anchors, box);
    }

    /**
     * Builds a fixture with the single {@code (0, 0, 1, 1)} anchor.
     *
     * @param box envelope
     * @return one-by-one table
     */
    private static TableGeometry.Table table(final TableGeometry.Box box) {
        return table(box, Set.of(new TableGeometry.SpanKey(0, 0, 1, 1)));
    }

    @Test
    void extraDuplicateCannotMultiplyTopologyCredit() {
        final var reference = table(new TableGeometry.Box(0, 0, 10, 10));
        final var result = StructureMetrics.structure(List.of(reference), List.of(reference, reference));
        assertEquals(2L, result.get("candidate_tables"));
        assertEquals(1L, result.get("exact_topologies_one_to_one_at_iou_0_5"));
    }

    @Test
    void topologyCannotChooseAGeometricallyUnmatchedPrediction() {
        final var reference = table(new TableGeometry.Box(0, 0, 10, 10));
        final var wrong = table(new TableGeometry.Box(0, 0, 10, 10), Set.of(new TableGeometry.SpanKey(0, 0, 1, 2)));
        final var farExact = table(new TableGeometry.Box(20, 20, 30, 30));
        final var result = StructureMetrics.structure(List.of(reference), List.of(wrong, farExact));
        assertEquals(0L, result.get("exact_topologies_one_to_one_at_iou_0_5"));
        assertEquals(0L, PythonJson.object(PythonJson.array(result.get("matches"), "matches").getFirst(), "match")
                                 .get("candidate"));
        assertEquals(0L, StructureMetrics.structure(List.of(reference), List.of(farExact))
                                 .get("matched_reference_tables_at_iou_0_5"));
    }

    @Test
    void preparationPreservesThresholdedScoresAndUnclippedBoxes() {
        final var nativeRows = List.<Map<String, String>>of(
                Map.of("label", "0", "score", "0.51", "left", "0", "top", "1", "right", "9", "bottom", "10"),
                Map.of("label", "1", "score", "0.9", "left", "0", "top", "1", "right", "9", "bottom", "10"));
        final var first = new LinkedHashMap<String, Object>();
        first.put("label", 8L);
        first.put("score", 0.26);
        first.put("box", List.of(-2L, 1L, 12L, 10L));
        final var second = new LinkedHashMap<String, Object>();
        second.put("label", 9L);
        second.put("score", 0.9);
        second.put("box", List.of(0L, 1L, 9L, 10L));
        final List<Object> reference = List.of(first, second);
        assertEquals(List.of(new DetectorStructureScorer.Proposal(0.51, List.of(0.0, 1.0, 9.0, 10.0))),
                DetectorStructureScorer.proposals("tatr", nativeRows, reference));
        assertEquals(List.of(new DetectorStructureScorer.Proposal(0.26, List.of(-2L, 1L, 12L, 10L))),
                DetectorStructureScorer.proposals("yolox", nativeRows, reference));
        assertThrows(IllegalArgumentException.class,
                () -> DetectorStructureScorer.proposals("oracle", nativeRows, reference));
    }

    @Test
    void retainedProposalFilesAreReproducedFromRetainedRegionEvidence() throws IOException {
        final Path review = Path.of("benchmarks/review");
        final var summary = PythonJson.object(
                PythonJson.parse(ScoringFiles.readText(review.resolve("pdf-alternate-detectors/summary.json"))),
                "summary");
        final var pins = PythonJson.object(PythonJson
                                                   .object(PythonJson.parse(ScoringFiles.readText(review.resolve(
                                                                   "pdf-detector-structures/proposals/manifest.json"))),
                                                           "manifest")
                                                   .get("proposal_sha256"),
                "proposal_sha256");
        assertEquals(ScoringFiles.sha256(review.resolve("pdf-alternate-detectors/summary.json")),
                PythonJson
                        .object(PythonJson.parse(ScoringFiles.readText(
                                        review.resolve("pdf-detector-structures/proposals/manifest.json"))),
                                "manifest")
                        .get("region_summary_sha256"));
        int files = 0;
        for (final Object item : PythonJson.array(summary.get("results"), "results")) {
            final var row = PythonJson.object(item, "row");
            if (!"OK".equals(row.get("outcome"))) {
                continue;
            }
            final String name = (String) row.get("input");
            final var detections = ScoringFiles.rows(
                    review.resolve("pdf-alternate-detectors/java21").resolve(name).resolve("detections.tsv"));
            for (final String profile : List.of("tatr", "yolox")) {
                final String text = DetectorStructureScorer.proposalFile(DetectorStructureScorer.proposals(
                        profile, detections, PythonJson.array(row.get("yolox_all_objects"), "objects")));
                assertEquals(pins.get(name + "/" + profile + ".tsv"),
                        ScoringFiles.sha256(text.getBytes(StandardCharsets.UTF_8)), name + "/" + profile);
                files++;
            }
        }
        assertEquals(pins.size(), files);
    }
}
