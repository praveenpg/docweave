package io.docweave.validation;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compares fixed whole/upper/lower detector-window captures with pinned cTDaR geometry labels.
 *
 * <p>Replaces {@code compare_tiled_regions.py}. The tile union is a diagnostic candidate pool, not a
 * fused production output. The highest-overlap candidate is selected by geometry for evaluation only.
 */
final class TiledRegionScorer implements BenchmarkScorer {
    /** Window profiles in report order. */
    private static final List<String> WINDOWS = List.of("whole", "upper", "lower");

    /** Creates the scorer. */
    TiledRegionScorer() {
    }

    @Override
    public String command() {
        return "tiled-regions";
    }

    @Override
    public void run(final List<String> arguments, final PrintStream console) throws IOException {
        final var options = ScoringArguments.parse(
                arguments, Set.of("capture21", "capture25", "cache", "manifest", "output"), Set.of(), Set.of(), 0);
        final var output = options.path("output");
        if (Files.exists(output)) {
            throw new IllegalArgumentException("Output must be new");
        }
        final var manifest = CtdarEvidence.verifiedManifest(
                options.path("manifest"), options.path("cache"), "Changed source/annotation: ", true);
        final var capture21 = options.path("capture21");
        final int paired = CtdarEvidence.pairedFiles(capture21, options.path("capture25"),
                "Paired captures have different files", "Paired capture differs: ", true);
        final var results = new ArrayList<Object>();
        final var totals = new LinkedHashMap<String, Object>();
        for (final String name : List.of("whole", "upper", "lower", "tile_pool")) {
            totals.put(name, 0L);
        }
        for (final var input : CtdarEvidence.images(manifest)) {
            final var source = capture21.resolve(input.name());
            final var frame = CtdarEvidence.frame(source);
            if (!input.sha256().equals(frame.get("sha256"))) {
                throw new IllegalArgumentException("Capture source mismatch");
            }
            final var references = TableGeometry.referenceTables(options.path("cache").resolve(input.name() + ".xml"));
            if (Files.exists(source.resolve("outcome.txt"))) {
                final var record = new LinkedHashMap<String, Object>();
                record.put("input", input.name())
                ;
                record.put("outcome", CtdarEvidence.requiredOutcome(source))
                ;
                record.put("reference_tables", (long) references.size())
                ;
                results.add(record);
                continue;
            }
            final var profiles = new LinkedHashMap<String, List<TableGeometry.Table>>();
            for (final String window : WINDOWS) {
                profiles.put(window, TableGeometry.predictions(source.resolve(window), frame));
            }
            final var pool = new ArrayList<>(profiles.get("upper"));
            pool.addAll(profiles.get("lower"));
            profiles.put("tile_pool", pool);
            final var comparisons = new ArrayList<Object>();
            for (final var reference : references) {
                final var comparison = new LinkedHashMap<String, Object>();
                for (final var profile : profiles.entrySet()) {
                    final var record = StructureMetrics.highestOverlap(reference, profile.getValue());
                    comparison.put(profile.getKey(), record);
                    totals.put(profile.getKey(),
                            (Long) totals.get(profile.getKey()) + ((Boolean) record.get("exact_topology") ? 1 : 0));
                }
                final var entry = new LinkedHashMap<String, Object>();
                entry.put("reference_rows", (long) reference.rows());
                entry.put("reference_columns", (long) reference.columns());
                entry.put("reference_anchors", (long) reference.anchors().size());
                entry.put("profiles", comparison);
                comparisons.add(entry);
            }
            final var record = new LinkedHashMap<String, Object>();
            record.put("input", input.name())
            ;
            record.put("outcome", "OK")
            ;
            final var frameValues = new LinkedHashMap<String, Object>();
            for (final var field : frame.entrySet()) {
                if (!field.getKey().equals("sha256")) {
                    frameValues.put(field.getKey(), ScoringFiles.pyFloat(field.getValue()));
                }
            }
            record.put("frame", frameValues)
            ;
            final var outcomes = new LinkedHashMap<String, Object>();
            for (final String window : WINDOWS) {
                outcomes.put(window, CtdarEvidence.requiredOutcome(source.resolve(window)));
            }
            record.put("profile_outcomes", outcomes)
            ;
            final var candidates = new LinkedHashMap<String, Object>();
            profiles.forEach((profile, tables) -> candidates.put(profile, (long) tables.size()));
            record.put("candidate_tables", candidates)
            ;
            final var seams = new LinkedHashMap<String, Object>();
            for (final String window : List.of("upper", "lower")) {
                seams.put(window, ScoringFiles.rows(source.resolve(window).resolve("detections.tsv"))
                                          .stream()
                                          .filter(row -> "true".equals(row.get("artificial_edge_contact")))
                                          .count());
            }
            record.put("seam_contact_detections", seams)
            ;
            record.put("references", comparisons)
            ;
            results.add(record);
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put("scope", "Diagnostic raw ONNX structure only; no OCR/rule composition/fusion or held-out score");
        report.put("inputs", (long) results.size());
        report.put("paired_capture_files_identical", (long) paired);
        report.put("typed_whole_source_limits",
                results.stream().filter(row -> !"OK".equals(((Map<?, ?>) row).get("outcome"))).count());
        report.put("exact_topologies_by_highest_envelope_overlap", totals);
        report.put("results", results);
        CtdarEvidence.write(output, report, false);
        final var summary = new LinkedHashMap<>(report);
        summary.remove("results");
        console.println(PythonJson.dumps(summary, false));
    }
}
