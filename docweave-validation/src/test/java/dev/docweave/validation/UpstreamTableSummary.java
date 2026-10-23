package dev.docweave.validation;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Aggregates the fixed all-20 upstream table comparison reports without excluding misses or
 * multiplying duplicate matches.
 *
 * <p>Replaces {@code summarize_pdf_upstream.py}. The per-input reports are produced by the retained
 * Python reference launcher, which executes Unstructured; this aggregation only reads their text-free
 * counts. Complete-table credit requires exactly one candidate, every input stays in the denominator,
 * and duplicate candidates contribute only their best positional span count.
 */
final class UpstreamTableSummary implements BenchmarkScorer {
    /** Upstream comparison profiles in report order. */
    static final List<String> PROFILES = List.of("isolated_upstream_postprocessing",
            "shared_weights_upstream_recognition", "public_pdf_api_default_model_names_with_pinned_local_files");
    /** Source of this scorer, recorded as the harness identity. */
    static final Path SOURCE =
            Path.of("docweave-validation/src/test/java/dev/docweave/validation/UpstreamTableSummary.java");
    /** Fixed published denominator of all 20 inputs. */
    private static final long PUBLISHED_ANCHORS = 1380;
    /** Configuration fields that must agree across all comparison reports. */
    private static final List<String> CONFIGURATION = List.of("dependencies", "source_sha256", "harness_sha256",
            "capture_harness_sha256", "model_sha256", "settings", "tesseract_sha256", "tesseract_executable_sha256");

    /** Creates the scorer. */
    UpstreamTableSummary() {
    }

    @Override
    public String command() {
        return "pdf-upstream-summary";
    }

    /**
     * Aggregates one upstream profile over all inputs ({@code aggregate}).
     *
     * @param cases per-input comparison reports
     * @param profile profile name
     * @return input, table, single-candidate completeness and best-candidate span counts
     */
    static Map<String, Object> aggregate(final List<Map<String, Object>> cases, final String profile) {
        long nonemptyTables = 0;
        long inputsWithTable = 0;
        long multiple = 0;
        long topology = 0;
        long topologyAndText = 0;
        long best = 0;
        for (final var item : cases) {
            final Object values = PythonJson.member(item, profile);
            final List<Map<String, Object>> tables =
                    values instanceof List<?> list
                            ? list.stream().map(table -> PythonJson.object(table, profile)).toList()
                            : List.of(PythonJson.object(values, profile));
            final var nonempty =
                    tables.stream()
                            .filter(table
                                    -> PythonJson.numericCompare((Number) PythonJson.member(table, "anchors"), 0L) > 0)
                            .toList();
            nonemptyTables += nonempty.size();
            inputsWithTable += nonempty.isEmpty() ? 0 : 1;
            multiple += nonempty.size() > 1 ? 1 : 0;
            boolean single = tables.size() == 1 && nonempty.size() == 1;
            if (profile.equals(PROFILES.get(2))) {
                single = single && PythonJson.pyEquals(PythonJson.member(item, "public_detected_tables"), 1L);
            }
            if (single) {
                topology += flag(nonempty.getFirst(), "complete_topology");
                topologyAndText += flag(nonempty.getFirst(), "complete_topology_and_text");
            }
            long maximum = 0;
            boolean any = false;
            for (final var table : tables) {
                final long matches = ((Number) PythonJson.member(table, "positional_span_matches")).longValue();
                maximum = any ? Math.max(maximum, matches) : matches;
                any = true;
            }
            best += maximum;
        }
        final var result = new LinkedHashMap<String, Object>();
        result.put("inputs", (long) cases.size());
        result.put("nonempty_tables", nonemptyTables);
        result.put("inputs_with_table", inputsWithTable);
        result.put("inputs_with_multiple_tables", multiple);
        result.put("exactly_one_complete_topology", topology);
        result.put("exactly_one_complete_topology_and_text", topologyAndText);
        result.put("best_candidate_positional_span_matches", best);
        return result;
    }

    /**
     * Summarizes existing Java measurements, keeping their distinct OCR/parser configuration explicit
     * ({@code candidate_summary}).
     *
     * @param report Java comparison measurement report
     * @return input, table and single-candidate completeness counts
     */
    static Map<String, Object> candidateSummary(final Map<String, Object> report) {
        final var entries = PythonJson.array(PythonJson.member(report, "results"), "results")
                                    .stream()
                                    .map(entry -> PythonJson.object(entry, "result"))
                                    .toList();
        long tables = 0;
        long inputsWithTable = 0;
        long topology = 0;
        long topologyAndText = 0;
        for (final var entry : entries) {
            final var emitted = emitted(entry);
            tables += emitted.size();
            inputsWithTable += emitted.isEmpty() ? 0 : 1;
            if (emitted.size() == 1) {
                final var table = emitted.getFirst();
                if (flag(table, "same_shape") == 1
                        && PythonJson.pyEquals(PythonJson.member(table, "positional_span_matches"),
                                PythonJson.member(entry, "published_anchors"))) {
                    topology++;
                }
                topologyAndText += flag(table, "complete_shape_text_match");
            }
        }
        final var result = new LinkedHashMap<String, Object>();
        result.put("inputs", (long) entries.size());
        result.put("nonempty_tables", tables);
        result.put("inputs_with_table", inputsWithTable);
        result.put("exactly_one_complete_topology", topology);
        result.put("exactly_one_complete_topology_and_text", topologyAndText);
        return result;
    }

    /**
     * Returns emitted tables of a Java measurement entry, treating an absent field as none.
     *
     * @param entry measurement entry
     * @return emitted table records
     */
    private static List<Map<String, Object>> emitted(final Map<String, Object> entry) {
        if (!entry.containsKey("emitted_tables")) {
            return List.of();
        }
        return PythonJson.array(entry.get("emitted_tables"), "emitted_tables")
                .stream()
                .map(table -> PythonJson.object(table, "table"))
                .toList();
    }

    /**
     * Reads a required boolean credit flag.
     *
     * @param table table record
     * @param key flag name
     * @return one when true, zero when false
     */
    private static long flag(final Map<String, Object> table, final String key) {
        if (!(PythonJson.member(table, key) instanceof Boolean value)) {
            throw new IllegalArgumentException("Expected boolean field: " + key);
        }
        return value ? 1 : 0;
    }

    @Override
    public void run(final List<String> arguments, final PrintStream console) throws IOException {
        final var options = ScoringArguments.parse(
                arguments, Set.of("reports", "candidate", "detector-baseline", "output"), Set.of(), Set.of(), 0);
        final Path output = options.path("output");
        ScoringFiles.requireNew(output);
        final var manifest = PythonJson.object(
                PythonJson.parse(ScoringFiles.readText(Path.of("benchmarks/review/pdf-pubtabnet/manifest.json"))),
                "manifest");
        final var expected = new TreeSet<String>();
        for (final Object item : PythonJson.array(PythonJson.member(manifest, "inputs"), "inputs")) {
            final String filename = (String) PythonJson.member(PythonJson.object(item, "input"), "filename");
            if (filename.endsWith(".png")) {
                expected.add(filename);
            }
        }
        final List<Path> files;
        try (Stream<Path> entries = Files.list(options.path("reports"))) {
            files = entries.filter(path -> path.getFileName().toString().endsWith("-comparison.json"))
                            .sorted((first, second) -> PythonJson.codePointCompare(first.toString(), second.toString()))
                            .toList();
        }
        final var cases = new ArrayList<Map<String, Object>>();
        for (final Path path : files) {
            cases.add(PythonJson.object(PythonJson.parse(ScoringFiles.readText(path)), "case"));
        }
        final var inputs = new HashSet<Object>();
        cases.forEach(item -> inputs.add(PythonJson.member(item, "input")));
        if (cases.size() != 20 || inputs.size() != 20 || !inputs.equals(new HashSet<Object>(expected))) {
            throw new IllegalArgumentException("Require all20 unique pinned inputs, with no missing/duplicate cases");
        }
        for (final var item : cases) {
            if (!PythonJson.pyEquals(PythonJson.member(item, "source_commit"), PythonJson.member(manifest, "commit"))) {
                throw new IllegalArgumentException("Mixed source revisions");
            }
        }
        for (final String field : CONFIGURATION) {
            for (final var item : cases) {
                if (!PythonJson.pyEquals(PythonJson.member(item, field), PythonJson.member(cases.getFirst(), field))) {
                    throw new IllegalArgumentException("Mixed comparison configuration: " + field);
                }
            }
        }
        final var summaries = new LinkedHashMap<String, Object>();
        PROFILES.forEach(profile -> summaries.put(profile, aggregate(cases, profile)));
        final var measurements = new LinkedHashMap<String, Object>();
        for (final var named : List.of(Map.entry("docweave_explicit_structure_cell_ocr", "candidate"),
                     Map.entry("docweave_historical_detector_and_structure", "detector-baseline"))) {
            final String argument = options.text(named.getValue());
            final var data = PythonJson.object(PythonJson.parse(ScoringFiles.readText(Path.of(argument))), "data");
            final var results = PythonJson.array(PythonJson.member(data, "results"), "results")
                                        .stream()
                                        .map(entry -> PythonJson.object(entry, "result"))
                                        .toList();
            final var names = new HashSet<Object>();
            results.forEach(entry -> names.add(PythonJson.member(entry, "input")));
            if (results.size() != 20 || !names.equals(new HashSet<Object>(expected))) {
                throw new IllegalArgumentException("Incomplete Java comparison measurements");
            }
            final var byName = new LinkedHashMap<Object, Map<String, Object>>();
            results.forEach(entry -> byName.put(PythonJson.member(entry, "input"), entry));
            for (final var item : cases) {
                final var candidate = byName.get(PythonJson.member(item, "input"));
                final var published = PythonJson.object(PythonJson.member(item, "published"), "published");
                for (final String field : List.of("rows", "columns", "anchors")) {
                    if (!PythonJson.pyEquals(PythonJson.member(published, field),
                                PythonJson.member(candidate, "published_" + field))) {
                        throw new IllegalArgumentException("Python/Java reference shape disagreement");
                    }
                }
            }
            summaries.put(named.getKey(), candidateSummary(data));
            measurements.put(ScoringFiles.pythonPath(argument), ScoringFiles.sha256(Path.of(argument)));
        }
        long totalAnchors = 0;
        for (final var item : cases) {
            totalAnchors += ((Number) PythonJson.member(
                                     PythonJson.object(PythonJson.member(item, "published"), "published"), "anchors"))
                                    .longValue();
        }
        if (totalAnchors != PUBLISHED_ANCHORS) {
            throw new IllegalArgumentException("Published denominator changed");
        }
        final var reports = new LinkedHashMap<String, Object>();
        for (final Path path : files) {
            reports.put(path.getFileName().toString(), ScoringFiles.sha256(path));
        }
        final var result = new LinkedHashMap<String, Object>();
        result.put("scope", "All20 original scientific table crops embedded whole in PDFs; upstream train examples, "
                                    + "not held-out/general PDF accuracy");
        result.put("published_anchors", totalAnchors);
        result.put("summaries", summaries);
        result.put("cases", new ArrayList<Object>(cases));
        result.put("harness_sha256", ScoringFiles.sha256(SOURCE));
        result.put("source_reports_sha256", reports);
        result.put("java_measurement_sha256", measurements);
        CtdarEvidence.write(output, result, true);
        console.println(PythonJson.dumps(summaries, false));
    }
}
