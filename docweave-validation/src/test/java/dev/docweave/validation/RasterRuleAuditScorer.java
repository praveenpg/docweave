package dev.docweave.validation;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compares paired source-pinned raster-rule audit captures against evaluation-only cTDaR labels.
 *
 * <p>Replaces {@code compare_raster_rule_audit.py}. Both JVM audits must agree byte for byte, every
 * profile must share the archived source frame and raster, and association is geometry only.
 */
final class RasterRuleAuditScorer implements BenchmarkScorer {
    /** Typed outcomes counted per profile. */
    private static final List<String> OUTCOMES = List.of("OK", "OCR_LIMIT", "TABLE_LIMIT");

    /** Creates the scorer. */
    RasterRuleAuditScorer() {
    }

    @Override
    public String command() {
        return "raster-rule-audit";
    }

    @Override
    public void run(final List<String> arguments, final PrintStream console) throws IOException {
        final var options = ScoringArguments.parse(
                arguments, Set.of("java21", "java25", "manifest", "cache", "output"), Set.of(), Set.of(), 0);
        final Path output = options.path("output");
        ScoringFiles.requireNew(output);
        final var manifest = CtdarEvidence.verifiedManifest(
                options.path("manifest"), options.path("cache"), "Changed source/annotation", false);
        final Path java21 = options.path("java21");
        final Path java25 = options.path("java25");
        final Object identity = PythonJson.parse(ScoringFiles.readText(java21.resolve("profiles.json")));
        if (!PythonJson.pyEquals(identity, PythonJson.parse(ScoringFiles.readText(java25.resolve("profiles.json"))))) {
            throw new IllegalArgumentException("Changed prototype identities");
        }
        long pairedFiles = 0;
        for (final String profile : RasterRulePrototypeScorer.PROFILES.keySet()) {
            final Path left = java21.resolve(profile).resolve("capture");
            final Path right = java25.resolve(profile).resolve("capture");
            pairedFiles += CtdarEvidence.pairedFiles(
                    left, right, "Different paired capture file sets", "Different paired bytes", false);
            if (!Arrays.equals(Files.readAllBytes(left.resolve("controls.tsv")),
                        Files.readAllBytes(right.resolve("controls.tsv")))) {
                throw new IllegalArgumentException("Different authored control results");
            }
        }
        final var results = new ArrayList<Map<String, Object>>();
        for (final var input : CtdarEvidence.images(manifest)) {
            final Path baseline = java21.resolve("baseline/capture").resolve(input.name());
            final var frame = CtdarEvidence.frame(baseline);
            final Path original = Path.of("benchmarks/review/pdf-alternate-detectors/java21").resolve(input.name());
            requireSame(
                    baseline.resolve("source.tsv"), original.resolve("source.tsv"), "Changed archived source frame");
            if (Files.exists(baseline.resolve("render.sha256"))) {
                requireSame(baseline.resolve("render.sha256"), original.resolve("render.sha256"),
                        "Changed archived raster identity");
            }
            if (!input.sha256().equals(frame.get("sha256"))) {
                throw new IllegalArgumentException("Changed captured source");
            }
            final var references = TableGeometry.referenceTables(options.path("cache").resolve(input.name() + ".xml"));
            final var record = new LinkedHashMap<String, Object>();
            record.put("input", input.name())
            ;
            record.put("reference_tables", (long) references.size())
            ;
            record.put("domain", CtdarEvidence.domain(input.name()))
            ;
            final var profiles = new LinkedHashMap<String, Object>();
            for (final String profile : RasterRulePrototypeScorer.PROFILES.keySet()) {
                final Path capture = java21.resolve(profile).resolve("capture").resolve(input.name());
                requireSame(capture.resolve("source.tsv"), baseline.resolve("source.tsv"),
                        "Different source frame between profiles");
                if (Files.exists(capture.resolve("render.sha256"))) {
                    requireSame(capture.resolve("render.sha256"), baseline.resolve("render.sha256"),
                            "Different raster between profiles");
                }
                final var entry = new LinkedHashMap<String, Object>();
                entry.put("outcome", CtdarEvidence.requiredOutcome(capture));
                entry.putAll(StructureMetrics.structure(references, TableGeometry.predictions(capture, frame)));
                final Path stages = capture.resolve("stages.tsv");
                entry.put("stages", Files.exists(stages) ? new LinkedHashMap<String, Object>(stageRow(stages)) : null);
                profiles.put(profile, entry);
            }
            record.put("profiles", profiles)
            ;
            results.add(record);
        }
        final var totals = new LinkedHashMap<String, Object>();
        for (final String domain : List.of("all", "modern", "historical")) {
            final var selected =
                    results.stream().filter(row -> domain.equals("all") || domain.equals(row.get("domain"))).toList();
            final var total = new LinkedHashMap<String, Object>();
            total.put("inputs", (long) selected.size());
            total.put("reference_tables", StructureMetrics.sum(selected, "reference_tables"));
            for (final String profile : RasterRulePrototypeScorer.PROFILES.keySet()) {
                final var profileTotal = DetectorStructureScorer.profileTotals(selected, profile,
                        List.of("candidate_tables", "matched_reference_tables_at_iou_0_5",
                                "exact_topologies_one_to_one_at_iou_0_5"));
                final var outcomes = new LinkedHashMap<String, Object>();
                for (final String outcome : OUTCOMES) {
                    outcomes.put(outcome,
                            selected.stream()
                                    .filter(row
                                            -> outcome.equals(PythonJson
                                                            .object(PythonJson.object(row.get("profiles"), "profiles")
                                                                            .get(profile),
                                                                    profile)
                                                            .get("outcome")))
                                    .count());
                }
                profileTotal.put("outcomes", outcomes);
                total.put(profile, profileTotal);
            }
            totals.put(domain, total);
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put("scope", "Fixed150DPI raw rule prototypes only; no OCR/model composition/held-out accuracy claim");
        report.put("association", "One-to-one maximum-cardinality axis-aligned envelope IoU>=0.5, geometry only");
        report.put("prototype_identities", identity);
        report.put("paired_files_identical_per_jvm", pairedFiles);
        report.put("totals", totals);
        report.put("results", new ArrayList<Object>(results));
        CtdarEvidence.write(output, report, false);
        console.println(PythonJson.dumps(totals, false));
    }

    /**
     * Reads the first stage-counter row.
     *
     * @param stages stage TSV
     * @return first row
     * @throws IOException if the file cannot be read
     */
    private static Map<String, String> stageRow(final Path stages) throws IOException {
        final var rows = ScoringFiles.rows(stages);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Missing stage counters");
        }
        return rows.getFirst();
    }

    /**
     * Requires byte-identical files.
     *
     * @param first first file
     * @param second second file
     * @param message failure message
     * @throws IOException if a file cannot be read
     */
    private static void requireSame(final Path first, final Path second, final String message) throws IOException {
        if (!Arrays.equals(Files.readAllBytes(first), Files.readAllBytes(second))) {
            throw new IllegalArgumentException(message);
        }
    }
}
