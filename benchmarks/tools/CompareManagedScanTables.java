package dev.docweave.validation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Complete paired scan population comparison using geometry-only one-to-one table association. */
public final class CompareManagedScanTables {
    /** Prevents utility construction. */
    private CompareManagedScanTables() {
    }

    /**
     * Verifies all nine original cases and unchanged annotations before deterministic spatial association.
     * Matches maximize cardinality at IoU>=0.5; descending-IoU/stable-reference traversal resolves choices.
     * Dimensions and anchor labels are evaluated only after matching and never influence association.
     * @param args study manifest, fresh TATR report, fresh YOLOX report and NEW summary; or --self-test
     * @throws Exception if protocols, complete populations, controls or output requirements fail
     */
    public static void main(final String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--self-test")) {
            selfTest();
            System.out.println("One-to-one scan association controls passed");
            return;
        }
        require(args.length == 4 && !Files.exists(Path.of(args[3])),
                "Expected manifest, paired reports and NEW summary");
        selfTest();
        final var mapper = JsonMapper.builder().build();
        final var protocol = mapper.readTree(Files.readAllBytes(Path.of(args[0])));
        for (final var pin : protocol.get("identities").properties()) {
            require(hash(Path.of(pin.getKey())).equals(pin.getValue().asString()), "Evaluated protocol/source changed");
        }
        final var source = mapper.readTree(Files.readAllBytes(Path.of(protocol.get("source_manifest").asString())));
        final var cases = new ArrayList<String>();
        for (final var input : source.get("inputs")) {
            final String name = input.get("filename").asString();
            if (name.endsWith(".jpg")) {
                cases.add(name.substring(0, name.length() - 4));
            }
        }
        require(cases.size() == protocol.get("inputs").asInt(), "Original population changed");
        final var before = mapper.readTree(Files.readAllBytes(Path.of(args[1])));
        final var after = mapper.readTree(Files.readAllBytes(Path.of(args[2])));
        final var old = mapper.readTree(
                Files.readAllBytes(Path.of("benchmarks/review/pdf-physical-scans/extended-java21.json")));
        require(before.get("results").equals(old.get("results")), "Historical TATR control changed");
        for (final var report : List.of(before, after)) {
            require(report.get("results").size() == cases.size(), "Missing source cases");
            require(report.get("model_sha256").asString().equals(protocol.get("eng_sha256").asString()),
                    "OCR model changed");
            require(report.get("executable_sha256").asString().equals(protocol.get("executable_sha256").asString()),
                    "OCR executable changed");
            require(report.get("render_dpi").asInt() == protocol.get("render_dpi").asInt(),
                    "Render resolution changed");
            require(report.get("coverage").asString().equals(protocol.get("coverage").asString()), "Coverage changed");
            require(report.get("table_detection").asString().equals(protocol.get("table_detection").asString()),
                    "OCR route changed");
        }
        final var results = new ArrayList<Map<String, Object>>();
        final var totals = new ArrayList<Map<String, Object>>();
        for (int profile = 0; profile < 2; profile++) {
            final String family = profile == 0 ? "TATR" : "YOLOX";
            final var report = profile == 0 ? before : after;
            int referenceCount = 0;
            int emittedCount = 0;
            int associatedCount = 0;
            int dimensions = 0;
            int exact = 0;
            int failures = 0;
            for (int index = 0; index < cases.size(); index++) {
                final var entry = report.get("results").get(index);
                final var control = before.get("results").get(index);
                require(entry.get("input").asString().equals(cases.get(index)), "Source sequence changed");
                require(entry.get("published_structure").equals(control.get("published_structure")),
                        "Annotations changed");
                require(entry.get("orientation").equals(control.get("orientation")), "Orientation decisions changed");
                require(entry.get("layout_graph_sha256").asString().equals(protocol.get("structure_sha256").asString()),
                        "Structure graph changed");
                require(entry.get("detection_graph_sha256")
                                .asString()
                                .equals(protocol.get("profiles").get(profile).get("detector_sha256").asString()),
                        "Detector graph changed");
                if (profile == 1) {
                    require(entry.get("detection_family").asString().equals("YOLOX"),
                            "Missing explicit detector family");
                }
                final int references = entry.get("published_structure").size();
                final var prediction = entry.get("emitted_tables");
                final int predictions = prediction == null ? 0 : prediction.size();
                final var ious = new double[predictions][references];
                for (int table = 0; table < predictions; table++) {
                    final var comparisons = prediction.get(table).get("reference_comparisons");
                    require(comparisons.size() == references, "Incomplete comparison matrix");
                    for (int reference = 0; reference < references; reference++) {
                        final var comparison = comparisons.get(reference);
                        require(comparison.get("reference_index").asInt() == reference, "Reference sequence changed");
                        final double iou = comparison.get("table_envelope_iou").asDouble();
                        require(Double.isFinite(iou) && iou >= 0 && iou <= 1, "Invalid spatial overlap");
                        ious[table][reference] = iou;
                    }
                }
                final var owners = associate(ious, references);
                final var matches = new ArrayList<Map<String, Object>>();
                int exactHere = 0;
                int dimensionsHere = 0;
                for (int reference = 0; reference < owners.length; reference++) {
                    if (owners[reference] >= 0) {
                        final var comparison =
                                prediction.get(owners[reference]).get("reference_comparisons").get(reference);
                        final boolean same = comparison.get("same_dimensions").asBoolean();
                        final boolean equal = same && comparison.get("anchor_set_equal").asBoolean();
                        dimensionsHere += same ? 1 : 0;
                        exactHere += equal ? 1 : 0;
                        matches.add(Map.of("reference", reference, "prediction", owners[reference], "iou",
                                ious[owners[reference]][reference], "same_dimensions", same,
                                "same_dimensions_and_published_unique_anchors", equal));
                    }
                }
                final var row = new LinkedHashMap<String, Object>();
                row.put("input", cases.get(index));
                row.put("family", family);
                row.put("published_tables", references);
                row.put("emitted_tables", predictions);
                row.put("spatial_matches", matches.size());
                row.put("same_dimensions_matches", dimensionsHere);
                row.put("exact_unique_anchor_matches", exactHere);
                row.put("matches", matches);
                if (entry.get("typed_failure") != null) {
                    failures++;
                    row.put("typed_failure", entry.get("typed_failure").asString());
                }
                results.add(row);
                referenceCount += references;
                emittedCount += predictions;
                associatedCount += matches.size();
                dimensions += dimensionsHere;
                exact += exactHere;
            }
            totals.add(Map.of("family", family, "cases", cases.size(), "published_tables", referenceCount,
                    "emitted_tables", emittedCount, "spatial_matches", associatedCount, "same_dimensions_matches",
                    dimensions, "exact_unique_anchor_matches", exact, "typed_failure_cases", failures));
        }
        Files.writeString(Path.of(args[3]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("runtime",
                        Runtime.version().toString(), "protocol_sha256", hash(Path.of(args[0])), "tatr_report_sha256",
                        hash(Path.of(args[1])), "yolox_report_sha256", hash(Path.of(args[2])),
                        "historical_tatr_results_exact", true, "scope",
                        "Geometry-only maximum-cardinality one-to-one IoU>=0.5 association; annotation dimensions/unique-anchor sets scored after matching; no text oracle, per-case routing or default selection",
                        "totals", totals, "results", results))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Finds maximum-cardinality spatial matches using stable descending-overlap augmenting paths.
     * @param ious complete prediction/reference matrix, without topology labels
     * @param references complete reference denominator, including sources with no predictions
     * @return prediction owner per reference, minus one for unmatched references
     */
    private static int[] associate(final double[][] ious, final int references) {
        final var owners = new int[references];
        Arrays.fill(owners, -1);
        for (int prediction = 0; prediction < ious.length; prediction++) {
            augment(prediction, ious, owners, new boolean[references]);
        }
        return owners;
    }

    /**
     * Reassigns only through spatially eligible edges; cell correctness never enters the search.
     * @param prediction current prediction
     * @param ious complete spatial matrix
     * @param owners mutable unique reference assignments
     * @param seen references already visited by this augmenting path
     * @return whether this prediction gains a unique reference
     */
    private static boolean augment(
            final int prediction, final double[][] ious, final int[] owners, final boolean[] seen) {
        final var order = new ArrayList<Integer>();
        for (int reference = 0; reference < owners.length; reference++) {
            if (ious[prediction][reference] >= .5) {
                order.add(reference);
            }
        }
        order.sort(Comparator.<Integer>comparingDouble(reference -> - ious[prediction][reference])
                        .thenComparingInt(reference -> reference));
        for (final int reference : order) {
            if (!seen[reference]) {
                seen[reference] = true;
                if (owners[reference] < 0 || augment(owners[reference], ious, owners, seen)) {
                    owners[reference] = prediction;
                    return true;
                }
            }
        }
        return false;
    }

    /** Exercises duplicate credit, augmenting paths, exact threshold, ties and empty prediction populations. */
    private static void selfTest() {
        require(Arrays.equals(new int[] {0, -1}, associate(new double[][] {{.9, 0}, {.8, 0}}, 2)),
                "Duplicate reference credit");
        require(Arrays.equals(new int[] {1, 0}, associate(new double[][] {{.9, .8}, {.85, .1}}, 2)),
                "Augmenting path failed");
        require(Arrays.equals(new int[] {0}, associate(new double[][] {{.5}}, 1)), "Exact threshold failed");
        require(Arrays.equals(new int[] {-1}, associate(new double[][] {{.499999}}, 1)), "Below-threshold match");
        require(Arrays.equals(new int[] {1, 0}, associate(new double[][] {{.8, .8}, {.8, .8}}, 2)), "Unstable tie");
        require(Arrays.equals(new int[] {-1, -1}, associate(new double[0][2], 2)), "Empty denominator lost");
    }

    /**
     * Hashes exact evaluated protocol/report bytes.
     * @param path local immutable evidence
     * @return lowercase SHA256
     * @throws Exception if input reading or hashing fails
     */
    private static String hash(final Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    /**
     * Rejects incomplete or altered populations without source text in diagnostics.
     * @param condition required invariant
     * @param message bounded content-free failure
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
