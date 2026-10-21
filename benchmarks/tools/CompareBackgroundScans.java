package dev.docweave.validation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Complete fixed-scan comparison; preserves failed cases and distinguishes XML order from validated logical order. */
public final class CompareBackgroundScans {
    /** Prevents standalone comparator construction. */
    private CompareBackgroundScans() {
    }

    /**
     * Compares the complete fixed source/model population without selecting winners or changing metrics.
     * Without a manifest the historical four-source/two-model denominator remains required; an explicit
     * manifest fixes an alternate complete population by exact source/model identities.
     * The historical report includes other thresholds; only its frozen AUTO/ENGINE_DEFAULT baseline is used.
     * An explicit manifest may declare the single AUTO-to-SPARSE_TEXT composition comparison; without
     * that declaration segmentation identity remains mandatory. No source/model/frame guard is relaxed.
     * @param args baseline report, candidate report, fresh comparison report and optional --require-equivalent or
     *         frozen manifest path
     * @throws Exception if populations, source/model/frame/reference identities or fresh output fail
     */
    public static void main(final String[] args) throws Exception {
        require(args.length == 3 || args.length == 4,
                "Expected baseline, candidate, fresh report and optional equivalence flag or frozen manifest");
        final boolean equivalent = args.length == 4 && args[3].equals("--require-equivalent");
        final Path protocol = args.length == 4 && !equivalent ? Path.of(args[3]) : null;
        final var mapper = JsonMapper.builder().build();
        final var manifest = protocol == null ? null : mapper.readTree(Files.readAllBytes(protocol));
        final var transition = manifest == null ? null : manifest.get("comparison_transition");
        if (transition != null) {
            require(transition.get("baseline_page_segmentation").asString().equals("AUTO")
                            && transition.get("candidate_page_segmentation").asString().equals("SPARSE_TEXT")
                            && transition.get("page_preprocessing").asString().equals("LOCAL_WHITEPOINT")
                            && transition.get("thresholding").asString().equals("ENGINE_DEFAULT"),
                    "Unsupported declared segmentation transition");
        }
        final var before = mapper.readTree(Files.readAllBytes(Path.of(args[0])));
        final var after = mapper.readTree(Files.readAllBytes(Path.of(args[1])));
        require(before.get("executable_sha256").equals(after.get("executable_sha256")), "OCR executable changed");
        require(after.get("omp_thread_limit").asInt() == 1, "Native thread policy changed");
        final var baseline = new LinkedHashMap<String, JsonNode>();
        for (final var row : before.get("results")) {
            if (row.get("page_segmentation").asString().equals("AUTO")
                    && row.get("thresholding").asString().equals("ENGINE_DEFAULT")) {
                require(baseline.put(key(row), row) == null, "Duplicate baseline");
            }
        }
        final var seen = new java.util.HashSet<String>();
        final var results = new ArrayList<Map<String, Object>>();
        for (final var candidate : after.get("results")) {
            final String id = key(candidate);
            final var original = baseline.get(id);
            require(original != null && seen.add(id), "Unexpected/duplicate candidate");
            for (final String field : List.of("reference_lines", "global_reference_code_points",
                         "reference_text_sha256", "reference_line_order_explicit", "model_sha256", "source_rgb_sha256",
                         "container_dpi", "engine_source_dpi", "thresholding")) {
                require(original.get(field).equals(candidate.get(field)),
                        "Source/reference/model/frame changed: " + field);
            }
            require(candidate.get("source_render_rgb_equal").asBoolean(), "Original render pixels changed");
            final var result = new LinkedHashMap<String, Object>();
            result.put("case", id);
            result.put("baseline_outcome", original.get("outcome").asString());
            result.put("candidate_outcome", candidate.get("outcome").asString());
            result.put("reference_line_order_explicit", candidate.get("reference_line_order_explicit").asBoolean());
            if (transition == null) {
                require(original.get("page_segmentation").equals(candidate.get("page_segmentation")),
                        "Source/reference/model/frame changed: page_segmentation");
            } else {
                require(original.get("page_segmentation").asString().equals("AUTO")
                                && candidate.get("page_segmentation").asString().equals("SPARSE_TEXT")
                                && original.get("managed_page_preprocessing") != null
                                && original.get("managed_page_preprocessing").asString().equals("LOCAL_WHITEPOINT")
                                && original.get("managed_page_preprocessing")
                                        .equals(candidate.get("managed_page_preprocessing")),
                        "Report does not match the predeclared segmentation transition");
                result.put("page_segmentation", Map.of("baseline", "AUTO", "candidate", "SPARSE_TEXT"));
            }
            for (final String metric : List.of("reference_lines", "associated_reference_lines",
                         "exact_associated_lines", "missing_reference_lines", "predicted_lines_without_reference",
                         "joint_reference_line_character_errors")) {
                result.put(metric,
                        Map.of("baseline", original.get(metric).asInt(), "candidate", candidate.get(metric).asInt()));
            }
            final String sequence = candidate.get("reference_line_order_explicit").asBoolean()
                                            ? "global_character_edits"
                                            : "xml_sequence_character_edits";
            result.put(sequence,
                    Map.of("baseline", original.get(sequence).asInt(), "candidate", candidate.get(sequence).asInt()));
            if (equivalent) {
                for (final String field : List.of("outcome", "prediction_text_sha256", "recognized_lines",
                             "associated_reference_lines", "exact_associated_lines", "missing_reference_lines",
                             "predicted_lines_without_reference", "joint_reference_line_character_errors",
                             "reference_line_code_points", "global_prediction_code_points", "global_reference_words",
                             sequence, sequence.replace("character_edits", "word_edits"))) {
                    require(java.util.Objects.equals(original.get(field), candidate.get(field)),
                            "Managed/prototype prediction or metric changed: " + field);
                }
                result.put("predictions_and_metrics_equal", true);
            }
            results.add(result);
        }
        final var expectedKeys = new java.util.HashSet<String>();
        if (protocol != null) {
            for (final var source : manifest.get("cases")) {
                for (final var model : manifest.get("profiles")) {
                    require(expectedKeys.add(source.get("id").asString() + "/" + model.get("language").asString()),
                            "Duplicate frozen source/model profile");
                }
            }
            require(baseline.keySet().equals(expectedKeys), "Baseline does not retain the complete frozen population");
        }
        final int expected = protocol == null ? 8 : expectedKeys.size();
        require(expected > 0 && baseline.size() == expected && seen.size() == expected
                        && baseline.keySet().equals(seen),
                "Incomplete fixed population");
        Files.writeString(Path.of(args[2]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("scope",
                        "Complete predeclared scan/model population; no source-specific selection, reference repair, extra normalization or whole-page CER claim where order is undeclared",
                        "runtime", Runtime.version().toString(), "baseline_report_sha256", hash(Path.of(args[0])),
                        "candidate_report_sha256", hash(Path.of(args[1])),
                        "all_source_model_reference_frames_unchanged", true, "results", results))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Identifies one complete source/model observation without using text scores.
     * @param node report row
     * @return source and model key
     */
    private static String key(final JsonNode node) {
        return node.get("id").asString() + "/" + node.get("language").asString();
    }

    /**
     * Fingerprints complete existing report bytes.
     * @param file report path
     * @return lowercase SHA256
     * @throws Exception if reading or digest creation fails
     */
    private static String hash(final Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    /**
     * Rejects changed protocols without omitting failed observations.
     * @param condition required invariant
     * @param message content-free diagnostic
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
