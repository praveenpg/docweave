package dev.docweave.validation;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recomputes every proposal metric of a retained upstream-YOLOX/TATR proposal report on the JVM.
 *
 * <p>{@code compare_scan_detectors.py} stays in Python because it executes the pinned Unstructured YOLOX
 * prediction code. Its geometry-only scoring was migrated here: this verifier reads the harness's raw
 * retained objects ({@code yolox_all_objects}), the Java-captured TATR detections and the original
 * labels, recomputes the one-to-one matches and aggregate counts, and refuses any disagreement. The
 * JVM computation is therefore the authority for every reported proposal metric.
 */
final class ScanDetectorVerification implements BenchmarkScorer {
    /** Proposal profiles in report order. */
    private static final List<String> PROFILES = List.of("tatr", "yolox");
    /** Aggregated proposal fields. */
    private static final List<String> FIELDS =
            List.of("table_proposals", "matched_reference_tables_at_iou_0_5", "unmatched_proposals");

    /** Creates the verifier. */
    ScanDetectorVerification() {
    }

    @Override
    public String command() {
        return "scan-detectors-verify";
    }

    /**
     * Returns upstream YOLOX table-class objects mapped back to original pixels.
     *
     * @param objects retained {@code yolox_all_objects}
     * @param frame captured source frame
     * @return source-frame proposal envelopes in model order
     */
    static List<TableGeometry.Box> yolox(final List<Object> objects, final Map<String, String> frame) {
        return objects.stream()
                .map(item -> PythonJson.object(item, "object"))
                .filter(object -> PythonJson.pyEquals(PythonJson.member(object, "label"), 8L))
                .map(object -> {
                    final var box = PythonJson.array(PythonJson.member(object, "box"), "box");
                    return TableGeometry.sourceBox(
                            new TableGeometry.Box(((Number) box.get(0)).doubleValue(),
                                    ((Number) box.get(1)).doubleValue(), ((Number) box.get(2)).doubleValue(),
                                    ((Number) box.get(3)).doubleValue()),
                            frame);
                })
                .toList();
    }

    /**
     * Returns native TATR table-class detections mapped back to original pixels.
     *
     * @param detections captured detection rows
     * @param frame captured source frame
     * @return source-frame proposal envelopes in capture order
     */
    static List<TableGeometry.Box> tatr(final List<Map<String, String>> detections, final Map<String, String> frame) {
        return detections.stream()
                .filter(row -> "0".equals(row.get("label")))
                .map(row -> TableGeometry.sourceBox(TableGeometry.rectangle(row), frame))
                .toList();
    }

    @Override
    public void run(final List<String> arguments, final PrintStream console) throws IOException {
        final var options = ScoringArguments.parse(
                arguments, Set.of("report", "capture21", "cache", "manifest", "output"), Set.of(), Set.of(), 0);
        final Path output = options.path("output");
        ScoringFiles.requireNew(output);
        final var manifest = CtdarEvidence.verifiedManifest(
                options.path("manifest"), options.path("cache"), "Changed source/annotation: ", true);
        final Path reportPath = options.path("report");
        final var report = PythonJson.object(PythonJson.parse(ScoringFiles.readText(reportPath)), "report");
        final var rows = PythonJson.array(PythonJson.member(report, "results"), "results");
        final var images = CtdarEvidence.images(manifest);
        if (rows.size() != images.size()) {
            throw new IllegalArgumentException("Report does not cover every pinned original");
        }
        final var recomputed = new ArrayList<Map<String, Object>>();
        long references = 0;
        long limits = 0;
        for (int index = 0; index < images.size(); index++) {
            final var input = images.get(index);
            final var row = PythonJson.object(rows.get(index), "result");
            if (!input.name().equals(row.get("input")) || !input.sha256().equals(row.get("source_sha256"))) {
                throw new IllegalArgumentException("Report input order or identity differs: " + input.name());
            }
            final Path capture = options.path("capture21").resolve(input.name());
            final var frame = CtdarEvidence.frame(capture);
            if (!input.sha256().equals(frame.get("sha256"))) {
                throw new IllegalArgumentException("Capture/source identity mismatch");
            }
            final var boxes = TableGeometry.referenceTables(options.path("cache").resolve(input.name() + ".xml"))
                                      .stream()
                                      .map(TableGeometry.Table::box)
                                      .toList();
            final String outcome = CtdarEvidence.requiredOutcome(capture);
            if (!outcome.equals(row.get("outcome"))
                    || !PythonJson.pyEquals(row.get("reference_tables"), (long) boxes.size())) {
                throw new IllegalArgumentException("Outcome or reference count differs: " + input.name());
            }
            references += boxes.size();
            final var metrics = new LinkedHashMap<String, Object>();
            if (!outcome.equals("OK")) {
                limits++;
                metrics.put("tatr", StructureMetrics.proposals(boxes, List.of()));
                metrics.put("yolox", StructureMetrics.proposals(boxes, List.of()));
            } else {
                if (!ScoringFiles.strip(ScoringFiles.readText(capture.resolve("render.sha256")))
                                .equals(row.get("render_sha256"))) {
                    throw new IllegalArgumentException("Render identity differs: " + input.name());
                }
                metrics.put("tatr", StructureMetrics.proposals(
                                            boxes, tatr(ScoringFiles.rows(capture.resolve("detections.tsv")), frame)));
                metrics.put(
                        "yolox", StructureMetrics.proposals(boxes,
                                         yolox(PythonJson.array(PythonJson.member(row, "yolox_all_objects"), "objects"),
                                                 frame)));
            }
            for (final String profile : PROFILES) {
                if (!PythonJson.pyEquals(metrics.get(profile), PythonJson.member(row, profile))) {
                    throw new IllegalArgumentException("Recomputed " + profile + " metric differs: " + input.name());
                }
            }
            recomputed.add(metrics);
        }
        final var aggregate = new LinkedHashMap<String, Object>();
        for (final String profile : PROFILES) {
            final var profiles =
                    recomputed.stream().map(metrics -> PythonJson.object(metrics.get(profile), profile)).toList();
            final var totals = new LinkedHashMap<String, Object>();
            FIELDS.forEach(field -> totals.put(field, StructureMetrics.sum(profiles, field)));
            aggregate.put(profile, totals);
        }
        if (!PythonJson.pyEquals(aggregate, PythonJson.member(report, "aggregate"))
                || !PythonJson.pyEquals(references, PythonJson.member(report, "reference_tables"))
                || !PythonJson.pyEquals(limits, PythonJson.member(report, "whole_source_limit_inputs"))
                || !PythonJson.pyEquals((long) images.size(), PythonJson.member(report, "inputs"))) {
            throw new IllegalArgumentException("Recomputed aggregate differs");
        }
        final var verification = new LinkedHashMap<String, Object>();
        verification.put("scope", "JVM recomputation of geometry-only proposal metrics; no model execution");
        verification.put("report_sha256", ScoringFiles.sha256(reportPath));
        verification.put("inputs_verified", (long) images.size());
        verification.put("reference_tables", references);
        verification.put("aggregate", aggregate);
        verification.put("all_metrics_identical", true);
        CtdarEvidence.write(output, verification, false);
        console.println(PythonJson.dumps(aggregate, false));
    }
}
