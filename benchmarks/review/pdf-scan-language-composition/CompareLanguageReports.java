import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Complete source/model-order verification and both-single-model comparisons without best-case selection. */
public final class CompareLanguageReports {
    /** Prevents utility construction. */
    private CompareLanguageReports() {
    }

    /**
     * Requires all frozen profiles, unchanged single-model baselines and complete paired JVM equality.
     * Random PDF container identity is the only ignored row field. Combined-model identities are explicitly
     * recomputed from ordered verified component descriptors rather than attributed to one traineddata binary.
     * @param args baseline, frozen manifest, Java21 report, Java25 report and fresh verification output
     * @throws Exception if any identity, coverage, source frame, default-output or fresh-writing check fails
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException("Expected baseline, manifest, Java21, Java25 and NEW output");
        }
        final var mapper = JsonMapper.builder().build();
        final var baseline = read(mapper, args[0]);
        final var manifest = read(mapper, args[1]);
        final var firstReport = read(mapper, args[2]);
        final var secondReport = read(mapper, args[3]);
        require(baseline.get("executable_sha256").equals(firstReport.get("executable_sha256"))
                && firstReport.get("executable_sha256").equals(secondReport.get("executable_sha256")));
        final var old = rows(baseline, true);
        final var first = rows(firstReport, false);
        final var second = rows(secondReport, false);
        require(first.size() == 16 && first.keySet().equals(second.keySet()));
        final var diagnostics = new ArrayList<Map<String, Object>>();
        int singles = 0;
        int expected = 0;
        for (final var selected : manifest.get("cases")) {
            for (final var profile : manifest.get("profiles")) {
                final String key = selected.get("id").asString() + "/" + profile.get("language").asString();
                require(first.containsKey(key));
                expected++;
                final var left = comparable(first.get(key));
                require(left.equals(comparable(second.get(key))));
                require(left.get("page_segmentation").asString().equals("AUTO")
                        && left.get("thresholding").asString().equals("ENGINE_DEFAULT"));
                require(!left.has("experimental_preprocessing"));
                final String primary = profile.has("languages") ? profile.get("languages").get(0).asString()
                                                                : profile.get("language").asString();
                final var source = old.get(selected.get("id").asString() + "/" + primary);
                require(source != null);
                for (final String field : new String[] {"source_rgb_sha256", "ocr_frames", "reference_lines",
                             "reference_text_sha256", "global_reference_code_points", "reference_line_code_points",
                             "reference_line_order_explicit", "container_dpi", "engine_source_dpi"}) {
                    require(left.get(field).equals(source.get(field)));
                }
                require(left.get("source_render_rgb_equal").asBoolean());
                if (!profile.has("models")) {
                    require(left.equals(comparable(source)));
                    singles++;
                    continue;
                }
                require(!left.has("model_sha256"));
                require(profile.get("languages").equals(left.get("ordered_languages")));
                require(left.get("ordered_models").size() == profile.get("models").size());
                final var descriptors = new ArrayList<String>();
                final var singleComparisons = new ArrayList<Map<String, Object>>();
                for (int index = 0; index < profile.get("models").size(); index++) {
                    final var pinned = profile.get("models").get(index);
                    final var component = left.get("ordered_models").get(index);
                    for (final String field : new String[] {"language", "filename", "sha256", "bytes"}) {
                        require(pinned.get(field).equals(component.get(field)));
                    }
                    final String language = component.get("language").asString();
                    require(language.equals(profile.get("languages").get(index).asString()));
                    final var single = first.get(selected.get("id").asString() + "/" + language);
                    require(single != null && single.get("model_sha256").equals(component.get("sha256")));
                    descriptors.add(language + ":" + component.get("sha256").asString());
                    final var comparison = new LinkedHashMap<String, Object>();
                    comparison.put("single_language", language);
                    for (final String field : new String[] {"associated_reference_lines", "exact_associated_lines",
                                 "missing_reference_lines", "predicted_lines_without_reference",
                                 "global_character_edits", "xml_sequence_character_edits"}) {
                        if (left.has(field)) {
                            comparison.put(field,
                                    Map.of("single", single.get(field).asInt(), "combined", left.get(field).asInt()));
                        }
                    }
                    singleComparisons.add(comparison);
                }
                require(left.get("model_set_sha256")
                                .asString()
                                .equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                                        String.join("\n", descriptors).getBytes(StandardCharsets.UTF_8)))));
                diagnostics.add(Map.of("case", key, "outcome", left.get("outcome").asString(),
                        "reference_line_order_explicit", left.get("reference_line_order_explicit").asBoolean(),
                        "against_each_single_language", singleComparisons));
            }
        }
        require(expected == first.size() && singles == 8 && diagnostics.size() == 8);
        Files.writeString(Path.of(args[4]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                        Map.of("paired_case_profiles", first.size(), "jvm_results_equal_except_random_pdf_identity",
                                true, "unchanged_single_model_baselines", singles, "source_and_render_frames_unchanged",
                                true, "complete_ordered_model_diagnostics", diagnostics))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Copies one complete row while excluding only random container identity.
     * @param row original numeric/hash row
     * @return independent comparison view
     */
    private static ObjectNode comparable(final ObjectNode row) {
        final var result = row.deepCopy();
        result.remove("container_pdf_sha256");
        return result;
    }

    /**
     * Indexes only the declared AUTO/default threshold path and rejects duplicated source/model-order keys.
     * @param report complete baseline or composition report
     * @param historicalSubset whether unrelated thresholds in the older baseline may be excluded explicitly
     * @return unique fixed-policy rows
     */
    private static Map<String, ObjectNode> rows(final JsonNode report, final boolean historicalSubset) {
        final var result = new LinkedHashMap<String, ObjectNode>();
        for (final var row : report.get("results")) {
            if (row.get("page_segmentation").asString().equals("AUTO")
                    && row.get("thresholding").asString().equals("ENGINE_DEFAULT")) {
                final String key = row.get("id").asString() + "/" + row.get("language").asString();
                require(result.put(key, (ObjectNode) row) == null);
            } else {
                require(historicalSubset);
            }
        }
        return result;
    }

    /**
     * Bounds local JSON before parsing.
     * @param mapper shared parser
     * @param filename original local evidence
     * @return parsed report or manifest
     * @throws Exception if empty, over16MiB or unreadable
     */
    private static JsonNode read(final JsonMapper mapper, final String filename) throws Exception {
        final var path = Path.of(filename);
        require(Files.size(path) > 0 && Files.size(path) <= 16 * 1024 * 1024);
        return mapper.readTree(Files.readAllBytes(path));
    }

    /**
     * Rejects inconsistent evidence without changing a reference or printing its text.
     * @param valid whether the invariant holds
     */
    private static void require(final boolean valid) {
        if (!valid) {
            throw new IllegalStateException("Ordered-language comparison invariant failed");
        }
    }
}
