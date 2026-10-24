package io.docweave.validation;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Evaluates fixed whole-page rule/model composition captures against original cTDaR labels.
 *
 * <p>Replaces {@code compare_ruled_scans.py}. The historical model control is replayed by complete
 * numeric geometry that ignores capture-local crop/table identifiers but not geometry or failures.
 */
final class RuledScanScorer implements BenchmarkScorer {
    /** Composition profiles in report order. */
    private static final List<String> PROFILES = List.of("model", "rules", "composed");
    /** Fields that identify the original image and its physical PDF placement. */
    private static final List<String> PHYSICAL =
            List.of("sha256", "original_width", "original_height", "pdf_width", "pdf_height");

    /** Creates the scorer. */
    RuledScanScorer() {
    }

    /**
     * Capture geometry without capture-local identifiers.
     *
     * @param outcome stripped profile outcome
     * @param tables sorted table tuples {@code (rows, columns, left, top, right, bottom, cells)}; empty unless OK
     */
    record Canonical(String outcome, List<List<Object>> tables) {
        /**
         * Copies the tables.
         *
         * @param outcome stripped profile outcome
         * @param tables sorted table tuples
         */
        Canonical {
            tables = List.copyOf(tables);
        }

        /**
         * Compares with Python tuple equality (numeric values compare by value).
         *
         * @param other other canonical capture
         * @return whether Python would find both tuples equal
         */
        boolean same(final Canonical other) {
            return outcome.equals(other.outcome) && PythonJson.pyEquals(tables, other.tables);
        }
    }

    @Override
    public String command() {
        return "ruled-scans";
    }

    /**
     * Returns complete numeric table geometry without crop/table IDs or provenance ({@code canonical_tables}).
     *
     * @param directory profile directory
     * @return outcome and sorted tables, with no tables unless the outcome is OK
     * @throws IOException if a capture file cannot be read
     */
    static Canonical canonicalTables(final Path directory) throws IOException {
        final String outcome = CtdarEvidence.requiredOutcome(directory);
        if (!outcome.equals("OK")) {
            return new Canonical(outcome, List.of());
        }
        final var anchors = ScoringFiles.rows(directory.resolve("anchors.tsv"));
        final var tables = new ArrayList<List<Object>>();
        for (final var table : ScoringFiles.rows(directory.resolve("tables.tsv"))) {
            final var cells = new ArrayList<List<Object>>();
            for (final var anchor : anchors) {
                if (Objects.equals(anchor.get("crop"), table.get("crop"))
                        && Objects.equals(anchor.get("table"), table.get("table"))) {
                    final var cell = new ArrayList<Object>();
                    for (final String key : List.of("row", "column", "row_span", "column_span")) {
                        cell.add((long) TableGeometry.integer(anchor, key));
                    }
                    cell.addAll(TableGeometry.rectangle(anchor).values());
                    cells.add(cell);
                }
            }
            cells.sort(TUPLES);
            final var entry = new ArrayList<Object>();
            entry.add((long) TableGeometry.integer(table, "rows"));
            entry.add((long) TableGeometry.integer(table, "columns"));
            entry.addAll(TableGeometry.rectangle(table).values());
            entry.add(List.copyOf(cells));
            tables.add(entry);
        }
        tables.sort(TUPLES);
        return new Canonical(outcome, tables);
    }

    /** Python ordering of tuples whose members are numbers or nested tuples. */
    @SuppressWarnings("unchecked")
    private static final Comparator<List<Object>> TUPLES = new Comparator<>() {
        @Override
        public int compare(final List<Object> first, final List<Object> second) {
            for (int index = 0; index < Math.min(first.size(), second.size()); index++) {
                final Object left = first.get(index);
                final Object right = second.get(index);
                if (PythonJson.pyEquals(left, right)) {
                    continue;
                }
                if (left instanceof Number a && right instanceof Number b) {
                    return PythonJson.numericCompare(a, b);
                }
                return compare((List<Object>) left, (List<Object>) right);
            }
            return Integer.compare(first.size(), second.size());
        }
    };

    /**
     * Requires the same original image identity and placement; DPI and canvas size may differ.
     *
     * @param first first {@code source.tsv} row
     * @param second second {@code source.tsv} row
     * @return whether the physical source frame is identical
     */
    static boolean samePhysicalFrame(final Map<String, String> first, final Map<String, String> second) {
        return PHYSICAL.stream().allMatch(key -> Objects.equals(first.get(key), second.get(key)));
    }

    @Override
    public void run(final List<String> arguments, final PrintStream console) throws IOException {
        final var options = ScoringArguments.parse(arguments,
                Set.of("capture21", "capture25", "cache", "manifest", "baseline", "output"), Set.of("dpi"), Set.of(),
                0);
        final int dpi = Integer.parseInt(options.options().getOrDefault("dpi", "150"));
        if (dpi != 150 && dpi != 300) {
            throw new IllegalArgumentException("--dpi must be 150 or 300");
        }
        final Path output = options.path("output");
        ScoringFiles.requireNew(output);
        final var manifest = CtdarEvidence.verifiedManifest(
                options.path("manifest"), options.path("cache"), "Changed source/annotation", false);
        final Path baseline = options.path("baseline");
        final var pins = PythonJson.object(
                PythonJson.parse(ScoringFiles.readText(baseline.resolve("manifest.json"))), "baseline");
        if (!ScoringFiles.sha256(baseline.resolve("summary.json")).equals(PythonJson.member(pins, "summary_sha256"))) {
            throw new IllegalArgumentException("Changed baseline summary");
        }
        for (final var pin :
                PythonJson.object(PythonJson.member(pins, "capture_sha256"), "capture_sha256").entrySet()) {
            if (!ScoringFiles.sha256(baseline.resolve(pin.getKey())).equals(pin.getValue())) {
                throw new IllegalArgumentException("Changed baseline evidence");
            }
        }
        final Path capture21 = options.path("capture21");
        final int paired = CtdarEvidence.pairedFiles(
                capture21, options.path("capture25"), "Paired output file sets differ", "Paired output differs", false);
        final var images = CtdarEvidence.images(manifest);
        final var expected = new TreeSet<String>();
        images.forEach(input -> expected.add(input.name()));
        try (Stream<Path> entries = Files.list(capture21)) {
            final var observed = new TreeSet<String>();
            entries.forEach(path -> observed.add(path.getFileName().toString()));
            if (!observed.equals(expected)) {
                throw new IllegalArgumentException("Changed input set");
            }
        }
        final var results = new ArrayList<Map<String, Object>>();
        for (final var input : images) {
            final Path capture = capture21.resolve(input.name());
            final Path control = baseline.resolve("java21").resolve(input.name());
            final var frame = CtdarEvidence.frame(capture);
            final var controlFrame = CtdarEvidence.frame(control);
            if (!samePhysicalFrame(frame, controlFrame)) {
                throw new IllegalArgumentException("Changed original source placement");
            }
            if (TableGeometry.integer(frame, "render_dpi") != dpi) {
                throw new IllegalArgumentException("Changed fixed resolution profile");
            }
            if (!input.sha256().equals(frame.get("sha256"))) {
                throw new IllegalArgumentException("Source identity mismatch");
            }
            final String outcome = CtdarEvidence.outcome(capture);
            if (dpi == 150) {
                if (!Arrays.equals(Files.readAllBytes(capture.resolve("source.tsv")),
                            Files.readAllBytes(control.resolve("source.tsv")))) {
                    throw new IllegalArgumentException("Changed control source frame");
                }
                if (outcome.equals("OK")) {
                    if (!Arrays.equals(Files.readAllBytes(capture.resolve("render.sha256")),
                                Files.readAllBytes(control.resolve("render.sha256")))) {
                        throw new IllegalArgumentException("Changed rendered control raster");
                    }
                    if (!canonicalTables(capture.resolve("model")).same(canonicalTables(control.resolve("tatr")))) {
                        throw new IllegalArgumentException("Model control changed on " + input.name());
                    }
                } else if (!CtdarEvidence.requiredOutcome(control).equals(outcome)) {
                    throw new IllegalArgumentException("Changed control whole-source outcome");
                }
            }
            final var references = TableGeometry.referenceTables(options.path("cache").resolve(input.name() + ".xml"));
            final var record = new LinkedHashMap<String, Object>();
            record.put("input", input.name())
            ;
            record.put("reference_tables", (long) references.size())
            ;
            record.put("reference_anchor_keys", references.stream().mapToLong(table -> table.anchors().size()).sum())
            ;
            record.put("domain", CtdarEvidence.domain(input.name()))
            ;
            record.put("outcome", outcome)
            ;
            record.put("same_original_placement_verified", true)
            ;
            record.put("model_control_reproduced", dpi == 150 ? Boolean.TRUE : null)
            ;
            final var profiles = new LinkedHashMap<String, Object>();
            for (final String profile : PROFILES) {
                final Path directory = capture.resolve(profile);
                final String profileOutcome =
                        !outcome.equals("OK") ? outcome : CtdarEvidence.requiredOutcome(directory);
                final var tables = outcome.equals("OK") ? TableGeometry.predictions(directory, frame)
                                                        : List.<TableGeometry.Table>of();
                final var metric = StructureMetrics.structure(references, tables);
                metric.put("matching_anchor_keys",
                        StructureMetrics.sum(PythonJson.array(metric.get("matches"), "matches")
                                                     .stream()
                                                     .map(match -> PythonJson.object(match, "match"))
                                                     .toList(),
                                "matching_anchors"));
                final var provenance =
                        profileOutcome.equals("OK")
                                ? ScoringFiles.rows(directory.resolve("tables.tsv"))
                                          .stream()
                                          .map(row -> Objects.requireNonNull(row.get("provenance"), "provenance"))
                                          .toList()
                                : List.<String>of();
                final var entry = new LinkedHashMap<String, Object>();
                entry.put("outcome", profileOutcome);
                entry.putAll(metric);
                entry.put("ruled_tables",
                        provenance.stream().filter(value -> value.startsWith("raster_ruled_interiors_v1;")).count());
                entry.put("model_tables",
                        provenance.stream().filter(value -> value.startsWith("detector_sha256=")).count());
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
            total.put("reference_anchor_keys", StructureMetrics.sum(selected, "reference_anchor_keys"));
            for (final String profile : PROFILES) {
                final var profileTotal = DetectorStructureScorer.profileTotals(selected, profile,
                        List.of("candidate_tables", "matched_reference_tables_at_iou_0_5",
                                "exact_topologies_one_to_one_at_iou_0_5", "matching_anchor_keys", "ruled_tables",
                                "model_tables"));
                profileTotal.put("non_ok_inputs",
                        selected.stream()
                                .filter(row
                                        -> !"OK".equals(PythonJson
                                                        .object(PythonJson.object(row.get("profiles"), "profiles")
                                                                        .get(profile),
                                                                profile)
                                                        .get("outcome")))
                                .count());
                total.put(profile, profileTotal);
            }
            totals.put(domain, total);
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put(
                "scope", "Fixed whole-page public layout strategies only; no OCR/held-out/end-to-end accuracy claim");
        report.put("association", "Maximum-cardinality one-to-one envelope IoU>=0.5, geometry only");
        report.put("render_dpi", (long) dpi);
        report.put("control_dpi", 150L);
        report.put("paired_capture_files_identical", (long) paired);
        report.put("totals", totals);
        report.put("results", new ArrayList<Object>(results));
        CtdarEvidence.write(output, report, false);
        console.println(PythonJson.dumps(totals, false));
    }
}
