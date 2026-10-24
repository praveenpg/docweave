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
import java.util.function.Function;

/**
 * Validates public compact/standard layout captures against pinned sources and prototype geometry.
 *
 * <p>Replaces {@code compare_compact_layout.py}. The documented rounding and five-pixel inset apply only
 * to predicted rule centerlines, never to annotations, and a prediction too small to keep a positive
 * interior is rejected rather than repaired.
 */
final class CompactLayoutScorer implements BenchmarkScorer {
    /** Layout profiles in report order. */
    private static final List<String> PROFILES = List.of("standard", "compact");

    /** Creates the scorer. */
    CompactLayoutScorer() {
    }

    @Override
    public String command() {
        return "compact-layout";
    }

    /**
     * Applies the fixed inward rounding and inset to a predicted rule-centerline rectangle ({@code interior}).
     *
     * @param box predicted centerline rectangle
     * @return integer interior {@code (ceil(left)+5, ceil(top)+5, floor(right)-5, floor(bottom)-5)}
     * @throws IllegalArgumentException if the interior would not be positive
     */
    static long[] interior(final TableGeometry.Box box) {
        for (final double value : boxValues(box)) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Nonfinite predicted rectangle");
            }
        }
        final long[] result = {(long) Math.ceil(box.left()) + 5, (long) Math.ceil(box.top()) + 5,
                (long) Math.floor(box.right()) - 5, (long) Math.floor(box.bottom()) - 5};
        if (result[2] <= result[0] || result[3] <= result[1]) {
            throw new IllegalArgumentException("Nonpositive predicted interior");
        }
        return result;
    }

    /**
     * Requires identical topology and parent bounds, with exactly the documented cell inset.
     *
     * @param prototype historical prototype profile directory
     * @param actual new public-interface profile directory
     * @throws IOException if a capture file cannot be read
     */
    static void verifyGeometry(final Path prototype, final Path actual) throws IOException {
        final var before = ScoringFiles.rows(prototype.resolve("tables.tsv"));
        final var after = ScoringFiles.rows(actual.resolve("tables.tsv"));
        if (before.size() != after.size()) {
            throw new IllegalArgumentException("Changed table count");
        }
        final var oldCells = ScoringFiles.rows(prototype.resolve("anchors.tsv"));
        final var newCells = ScoringFiles.rows(actual.resolve("anchors.tsv"));
        for (int index = 0; index < before.size(); index++) {
            final var expected = before.get(index);
            final var observed = after.get(index);
            if (!Objects.equals(expected.get("rows"), observed.get("rows"))
                    || !Objects.equals(expected.get("columns"), observed.get("columns"))
                    || !sameValues(boxValues(TableGeometry.rectangle(expected)),
                            boxValues(TableGeometry.rectangle(observed)))) {
                throw new IllegalArgumentException("Changed parent grid geometry");
            }
            final var expectedCells = keyed(cells(oldCells, expected), box -> {
                final long[] inset = interior(box);
                return new double[] {inset[0], inset[1], inset[2], inset[3]};
            });
            final var observedCells = keyed(cells(newCells, observed), CompactLayoutScorer::boxValues);
            if (expectedCells.size() != observedCells.size()) {
                throw new IllegalArgumentException("Changed anchor/inset geometry");
            }
            for (int cell = 0; cell < expectedCells.size(); cell++) {
                if (!sameValues(expectedCells.get(cell), observedCells.get(cell))) {
                    throw new IllegalArgumentException("Changed anchor/inset geometry");
                }
            }
        }
    }

    /**
     * Selects anchors owned by one table using exact string identities.
     *
     * @param anchors all anchors
     * @param table owning table row
     * @return owned anchors
     */
    private static List<Map<String, String>> cells(
            final List<Map<String, String>> anchors, final Map<String, String> table) {
        return anchors.stream()
                .filter(cell
                        -> Objects.equals(cell.get("crop"), table.get("crop"))
                                   && Objects.equals(cell.get("table"), table.get("table")))
                .toList();
    }

    /**
     * Builds sorted {@code (row, column, row_span, column_span, *geometry)} keys.
     *
     * @param cells owned anchors
     * @param transform geometry projection
     * @return lexicographically sorted numeric tuples
     */
    private static List<double[]> keyed(
            final List<Map<String, String>> cells, final Function<TableGeometry.Box, double[]> transform) {
        final var result = new ArrayList<double[]>();
        for (final var cell : cells) {
            final double[] geometry = transform.apply(TableGeometry.rectangle(cell));
            final double[] key = new double[4 + geometry.length];
            key[0] = TableGeometry.integer(cell, "row");
            key[1] = TableGeometry.integer(cell, "column");
            key[2] = TableGeometry.integer(cell, "row_span");
            key[3] = TableGeometry.integer(cell, "column_span");
            System.arraycopy(geometry, 0, key, 4, geometry.length);
            result.add(key);
        }
        result.sort(LEXICOGRAPHIC);
        return result;
    }

    /** Python tuple ordering over numeric values, treating equal-valued zeros as equal. */
    private static final Comparator<double[]> LEXICOGRAPHIC = (first, second) -> {
        for (int index = 0; index < Math.min(first.length, second.length); index++) {
            if (first[index] < second[index]) {
                return -1;
            }
            if (first[index] > second[index]) {
                return 1;
            }
        }
        return Integer.compare(first.length, second.length);
    };

    /**
     * Returns box coordinates as an array.
     *
     * @param box envelope
     * @return left, top, right, bottom
     */
    private static double[] boxValues(final TableGeometry.Box box) {
        return new double[] {box.left(), box.top(), box.right(), box.bottom()};
    }

    /**
     * Compares numeric tuples with Python {@code ==}.
     *
     * @param first first tuple
     * @param second second tuple
     * @return whether every value is numerically equal
     */
    private static boolean sameValues(final double[] first, final double[] second) {
        if (first.length != second.length) {
            return false;
        }
        for (int index = 0; index < first.length; index++) {
            if (first[index] != second[index]) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void run(final List<String> arguments, final PrintStream console) throws IOException {
        final var options = ScoringArguments.parse(
                arguments, Set.of("capture21", "capture25", "cache", "manifest", "output"), Set.of(), Set.of(), 0);
        final Path output = options.path("output");
        ScoringFiles.requireNew(output);
        final Path root = Path.of("benchmarks/review");
        final var manifest = CtdarEvidence.verifiedManifest(
                options.path("manifest"), options.path("cache"), "Changed source/annotation", false);
        final Path capture21 = options.path("capture21");
        final int paired = CtdarEvidence.pairedFiles(
                capture21, options.path("capture25"), "Paired file sets differ", "Paired captures differ", false);
        final var results = new ArrayList<Map<String, Object>>();
        for (final var input : CtdarEvidence.images(manifest)) {
            final Path capture = capture21.resolve(input.name());
            final Path historical = root.resolve("pdf-whole-page-ruled-composition/java21").resolve(input.name());
            if (!Arrays.equals(Files.readAllBytes(capture.resolve("source.tsv")),
                        Files.readAllBytes(historical.resolve("source.tsv")))) {
                throw new IllegalArgumentException("Changed source frame");
            }
            final var frame = CtdarEvidence.frame(capture);
            if (!input.sha256().equals(frame.get("sha256"))) {
                throw new IllegalArgumentException("Source identity mismatch");
            }
            final var references = TableGeometry.referenceTables(options.path("cache").resolve(input.name() + ".xml"));
            final String outcome = CtdarEvidence.outcome(capture);
            final var record = new LinkedHashMap<String, Object>();
            record.put("input", input.name())
            ;
            record.put("reference_tables", (long) references.size())
            ;
            record.put("outcome", outcome)
            ;
            final var profiles = new LinkedHashMap<String, Object>();
            for (final String profile : PROFILES) {
                final Path directory = capture.resolve(profile);
                final String typed;
                final List<TableGeometry.Table> tables;
                if (outcome.equals("OK")) {
                    if (!Arrays.equals(Files.readAllBytes(capture.resolve("render.sha256")),
                                Files.readAllBytes(historical.resolve("render.sha256")))) {
                        throw new IllegalArgumentException("Changed raster");
                    }
                    final Path prototype = root.resolve("pdf-raster-rule-audit/java21")
                                                   .resolve(profile.equals("standard") ? "baseline" : "compact")
                                                   .resolve(input.name());
                    typed = CtdarEvidence.requiredOutcome(directory);
                    if (!typed.equals(CtdarEvidence.requiredOutcome(prototype))) {
                        throw new IllegalArgumentException("Changed prototype outcome");
                    }
                    if (typed.equals("OK")) {
                        verifyGeometry(prototype, directory);
                    }
                    tables = TableGeometry.predictions(directory, frame);
                } else {
                    typed = outcome;
                    tables = List.of();
                    if (!CtdarEvidence.requiredOutcome(historical).equals(outcome)) {
                        throw new IllegalArgumentException("Changed source limit");
                    }
                }
                final var metric = new LinkedHashMap<String, Object>();
                metric.put("outcome", typed);
                metric.putAll(StructureMetrics.structure(references, tables));
                profiles.put(profile, metric);
            }
            record.put("profiles", profiles)
            ;
            results.add(record);
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put("scope", "Actual public rule-only interface strategies; no model/OCR/held-out accuracy claim");
        report.put("paired_capture_files_identical", (long) paired);
        report.put("inputs", (long) results.size());
        report.put("reference_tables", StructureMetrics.sum(results, "reference_tables"));
        final var totals = new LinkedHashMap<String, Object>();
        for (final String profile : PROFILES) {
            totals.put(profile, DetectorStructureScorer.profileTotals(results, profile,
                                        List.of("candidate_tables", "matched_reference_tables_at_iou_0_5",
                                                "exact_topologies_one_to_one_at_iou_0_5")));
        }
        report.put("totals", totals);
        report.put("results", new ArrayList<Object>(results));
        CtdarEvidence.write(output, report, false);
        final var summary = new LinkedHashMap<>(report);
        summary.remove("results");
        console.println(PythonJson.dumps(summary, false));
    }
}
