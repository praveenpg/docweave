package dev.docweave.validation;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Prepares prediction-only detector proposals and compares fixed Java structure captures.
 *
 * <p>Replaces {@code compare_detector_structures.py}. Annotations enter only {@code compare}, never
 * proposal preparation. Scores and boxes are exported unchanged; association is geometry only.
 */
final class DetectorStructureScorer implements BenchmarkScorer {
    /** Detector profiles in report order. */
    private static final List<String> PROFILES = List.of("tatr", "yolox");
    /** Aggregated metric fields. */
    private static final List<String> TOTALS = List.of(
            "candidate_tables", "matched_reference_tables_at_iou_0_5", "exact_topologies_one_to_one_at_iou_0_5");

    /** Creates the scorer. */
    DetectorStructureScorer() {
    }

    /**
     * Detector proposal exported for structure recognition.
     *
     * @param score unmodified detector score (Python integer or float)
     * @param box unmodified, unclipped box values (Python integers or floats)
     */
    record Proposal(Number score, List<Number> box) {
        /**
         * Copies the box.
         *
         * @param score unmodified detector score
         * @param box unmodified box values
         */
        Proposal {
            box = List.copyOf(box);
        }

        /**
         * Formats one TSV line as {@code '\t'.join(map(str, [score, *box]))}.
         *
         * @return line without terminator
         */
        String line() {
            final var values = new ArrayList<String>();
            values.add(PythonJson.repr(score));
            box.forEach(value -> values.add(PythonJson.repr(value)));
            return String.join("\t", values);
        }
    }

    @Override
    public String command() {
        return "detector-structures";
    }

    @Override
    public void run(final List<String> arguments, final PrintStream console) throws IOException {
        if (arguments.isEmpty()) {
            throw new IllegalArgumentException("Expected prepare or compare");
        }
        final var rest = arguments.subList(1, arguments.size());
        switch (arguments.getFirst()) {
        case "prepare" ->
            prepare(ScoringArguments.parse(rest, Set.of("captures", "summary", "output"), Set.of(), Set.of(), 0));
        case "compare" ->
            evaluate(ScoringArguments.parse(rest,
                             Set.of("capture21", "capture25", "cache", "manifest", "proposals", "output"), Set.of(),
                             Set.of(), 0),
                    console);
        default -> throw new IllegalArgumentException("Expected prepare or compare");
        }
    }

    /**
     * Maps detector table classes to proposals without changing scores or boxes ({@code proposals}).
     *
     * @param profile {@code tatr} or {@code yolox}
     * @param nativeRows captured native detections with string fields
     * @param reference upstream YOLOX objects from the region summary
     * @return table proposals in source order
     */
    static List<Proposal> proposals(
            final String profile, final List<Map<String, String>> nativeRows, final List<Object> reference) {
        return switch (profile) {
            case "tatr" ->
                nativeRows.stream()
                        .filter(row -> "0".equals(row.get("label")))
                        .map(row
                                -> new Proposal(TableGeometry.number(row, "score"),
                                        List.of(TableGeometry.number(row, "left"), TableGeometry.number(row, "top"),
                                                TableGeometry.number(row, "right"),
                                                TableGeometry.number(row, "bottom"))))
                        .toList();
            case "yolox" ->
                reference.stream()
                        .map(item -> PythonJson.object(item, "object"))
                        .filter(object -> PythonJson.pyEquals(PythonJson.member(object, "label"), 8L))
                        .map(object
                                -> new Proposal((Number) PythonJson.member(object, "score"),
                                        PythonJson.array(PythonJson.member(object, "box"), "box")
                                                .stream()
                                                .map(Number.class ::cast)
                                                .toList()))
                        .toList();
            default -> throw new IllegalArgumentException("Unknown detector profile");
        };
    }

    /**
     * Formats a proposal TSV exactly as the retired preparation step wrote it.
     *
     * @param proposals proposals in order
     * @return complete file text
     */
    static String proposalFile(final List<Proposal> proposals) {
        final var text = new StringBuilder("score\tleft\ttop\tright\tbottom\n");
        proposals.forEach(proposal -> text.append(proposal.line()).append('\n'));
        return text.toString();
    }

    /**
     * Verifies captured identities and exports only detector predictions; never reads annotations.
     *
     * @param options parsed options
     * @throws IOException if reading or writing fails
     */
    private static void prepare(final ScoringArguments options) throws IOException {
        final Path output = options.path("output");
        ScoringFiles.requireNew(output);
        final Path summary = options.path("summary");
        final var report = PythonJson.object(PythonJson.parse(ScoringFiles.readText(summary)), "summary");
        Files.createDirectory(output);
        final var hashes = new LinkedHashMap<String, Object>();
        for (final Object item : PythonJson.array(PythonJson.member(report, "results"), "results")) {
            final var row = PythonJson.object(item, "result");
            final String name = (String) PythonJson.member(row, "input");
            if (!name.startsWith("cTDaR_") || !Path.of(name).getFileName().toString().equals(name)
                    || name.contains("/")) {
                throw new IllegalArgumentException("Unexpected input name");
            }
            final Path capture = options.path("captures").resolve(name);
            final var frame = CtdarEvidence.frame(capture);
            if (!PythonJson.member(row, "source_sha256").equals(frame.get("sha256"))) {
                throw new IllegalArgumentException("Changed capture source");
            }
            final String outcome = (String) PythonJson.member(row, "outcome");
            if (!CtdarEvidence.requiredOutcome(capture).equals(outcome)) {
                throw new IllegalArgumentException("Changed outcome");
            }
            if (!outcome.equals("OK")) {
                continue;
            }
            if (!ScoringFiles.sha256(capture.resolve("render.png")).equals(PythonJson.member(row, "render_sha256"))) {
                throw new IllegalArgumentException("Changed raster");
            }
            final var nativeRows = ScoringFiles.rows(capture.resolve("detections.tsv"));
            final Path directory = Files.createDirectory(output.resolve(name));
            for (final String profile : PROFILES) {
                final var objects = proposals(
                        profile, nativeRows, PythonJson.array(PythonJson.member(row, "yolox_all_objects"), "objects"));
                final Path path = directory.resolve(profile + ".tsv");
                Files.writeString(path, proposalFile(objects), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                hashes.put(name + "/" + profile + ".tsv", ScoringFiles.sha256(path));
            }
        }
        final var manifest = new LinkedHashMap<String, Object>();
        manifest.put("region_summary_sha256", ScoringFiles.sha256(summary));
        manifest.put("proposal_sha256", hashes);
        CtdarEvidence.write(output.resolve("manifest.json"), manifest, false);
    }

    /**
     * Verifies all pins and paired outputs before reading geometry labels for evaluation.
     *
     * @param options parsed options
     * @param console summary destination
     * @throws IOException if reading or writing fails
     */
    private static void evaluate(final ScoringArguments options, final PrintStream console) throws IOException {
        final Path output = options.path("output");
        ScoringFiles.requireNew(output);
        final var manifest = CtdarEvidence.verifiedManifest(
                options.path("manifest"), options.path("cache"), "Changed source or annotation", false);
        final Path capture21 = options.path("capture21");
        final int paired = CtdarEvidence.pairedFiles(
                capture21, options.path("capture25"), "Paired file sets differ", "Paired output differs", false);
        final Path proposalRoot = options.path("proposals");
        final var pins =
                PythonJson.object(PythonJson.member(PythonJson.object(PythonJson.parse(ScoringFiles.readText(
                                                                              proposalRoot.resolve("manifest.json"))),
                                                            "proposals"),
                                          "proposal_sha256"),
                        "proposal_sha256");
        for (final var pin : pins.entrySet()) {
            if (!ScoringFiles.sha256(proposalRoot.resolve(pin.getKey())).equals(pin.getValue())) {
                throw new IllegalArgumentException("Changed proposal input");
            }
        }
        final var results = new ArrayList<Map<String, Object>>();
        for (final var input : CtdarEvidence.images(manifest)) {
            final Path capture = capture21.resolve(input.name());
            final var frame = CtdarEvidence.frame(capture);
            if (!input.sha256().equals(frame.get("sha256"))) {
                throw new IllegalArgumentException("Capture source mismatch");
            }
            final var references = TableGeometry.referenceTables(options.path("cache").resolve(input.name() + ".xml"));
            final var result = new LinkedHashMap<String, Object>();
            result.put("input", input.name());
            result.put("reference_tables", (long) references.size());
            result.put("domain", CtdarEvidence.domain(input.name()));
            result.put("outcome", CtdarEvidence.outcome(capture));
            final var profiles = new LinkedHashMap<String, Object>();
            for (final String profile : PROFILES) {
                final Path directory = capture.resolve(profile);
                final List<TableGeometry.Table> tables;
                final String outcome;
                if (!"OK".equals(result.get("outcome"))) {
                    tables = List.of();
                    outcome = (String) result.get("outcome");
                } else {
                    final Object expected = PythonJson.member(pins, input.name() + "/" + profile + ".tsv");
                    if (!ScoringFiles.strip(ScoringFiles.readText(directory.resolve("proposals.sha256")))
                                    .equals(expected)) {
                        throw new IllegalArgumentException("Capture/proposal identity mismatch");
                    }
                    outcome = CtdarEvidence.requiredOutcome(directory);
                    tables = TableGeometry.predictions(directory, frame);
                }
                final var record = new LinkedHashMap<String, Object>();
                record.put("outcome", outcome)
                ;
                record.putAll(StructureMetrics.structure(references, tables))
                ;
                profiles.put(profile, record);
            }
            result.put("profiles", profiles);
            results.add(result);
        }
        final var totals = new LinkedHashMap<String, Object>();
        for (final String domain : List.of("all", "modern", "historical")) {
            final var selected =
                    results.stream().filter(row -> domain.equals("all") || domain.equals(row.get("domain"))).toList();
            final var total = new LinkedHashMap<String, Object>();
            total.put("inputs", (long) selected.size());
            total.put("reference_tables", StructureMetrics.sum(selected, "reference_tables"));
            for (final String profile : PROFILES) {
                total.put(profile, profileTotals(selected, profile, TOTALS));
            }
            totals.put(domain, total);
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put("scope", "Fixed raw structure diagnostic; no OCR/rule composition/held-out or end-to-end claim");
        report.put("association", "Maximum-cardinality one-to-one envelope IoU>=0.5, geometry only");
        report.put("paired_capture_files_identical", (long) paired);
        report.put("totals", totals);
        report.put("results", new ArrayList<Object>(results));
        CtdarEvidence.write(output, report, false);
        console.println(PythonJson.dumps(totals, false));
    }

    /**
     * Sums selected per-profile metric fields.
     *
     * @param selected per-input records
     * @param profile profile name
     * @param keys metric fields
     * @return totals in key order
     */
    static Map<String, Object> profileTotals(
            final List<Map<String, Object>> selected, final String profile, final List<String> keys) {
        final var profiles =
                selected.stream()
                        .map(row
                                -> PythonJson.object(
                                        PythonJson.object(PythonJson.member(row, "profiles"), "profiles").get(profile),
                                        profile))
                        .toList();
        final var result = new LinkedHashMap<String, Object>();
        keys.forEach(key -> result.put(key, StructureMetrics.sum(profiles, key)));
        return result;
    }
}
