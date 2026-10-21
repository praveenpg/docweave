import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Complete paired single-column/AUTO comparison with unchanged default-output and reference checks. */
public final class CompareSingleColumnReports {
    /** Prevents utility construction. */
    private CompareSingleColumnReports() {
    }

    /**
     * Verifies every case/model/mode and retains all line/character errors without selecting a winning policy.
     * Random container PDF identity is the only ignored per-row JVM/default field.
     * @param args source-resolution baseline, Java21 report, Java25 report and fresh verification output
     * @throws Exception if source/reference/runtime/coverage/default-output invariants fail
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("Expected baseline, Java21, Java25 and NEW output");
        }
        final var mapper = JsonMapper.builder().build();
        final var baseline = read(mapper, args[0]);
        final var firstReport = read(mapper, args[1]);
        final var secondReport = read(mapper, args[2]);
        require(baseline.get("executable_sha256").equals(firstReport.get("executable_sha256"))
                && firstReport.get("executable_sha256").equals(secondReport.get("executable_sha256")));
        final var old = rows(baseline);
        final var first = rows(firstReport);
        final var second = rows(secondReport);
        require(first.size() == 16 && first.keySet().equals(second.keySet()));
        final var deltas = new ArrayList<Map<String, Object>>();
        int defaults = 0;
        for (final var entry : first.entrySet()) {
            final var left = comparable(entry.getValue());
            require(left.equals(comparable(second.get(entry.getKey()))));
            require(left.get("thresholding").asString().equals("ENGINE_DEFAULT"));
            require(!left.has("experimental_preprocessing"));
            final String sourceKey = left.get("id").asString() + "/" + left.get("language").asString();
            final String baselineKey = sourceKey + "/AUTO/ENGINE_DEFAULT";
            require(old.containsKey(baselineKey) && first.containsKey(baselineKey));
            final var previous = comparable(old.get(baselineKey));
            for (final String field : new String[] {"source_rgb_sha256", "ocr_frames", "reference_lines",
                         "reference_text_sha256", "global_reference_code_points", "reference_line_code_points",
                         "reference_line_order_explicit", "model_sha256", "container_dpi", "engine_source_dpi"}) {
                require(left.get(field).equals(previous.get(field)));
            }
            require(left.get("source_render_rgb_equal").asBoolean());
            final String mode = left.get("page_segmentation").asString();
            if (mode.equals("AUTO")) {
                require(left.equals(previous));
                defaults++;
                continue;
            }
            require(mode.equals("SINGLE_COLUMN"));
            final var auto = first.get(baselineKey);
            final var delta = new LinkedHashMap<String, Object>();
            delta.put("case", sourceKey);
            delta.put("outcome", left.get("outcome").asString());
            delta.put("reference_line_order_explicit", left.get("reference_line_order_explicit").asBoolean());
            for (final String field : new String[] {"associated_reference_lines", "exact_associated_lines",
                         "missing_reference_lines", "predicted_lines_without_reference", "global_character_edits",
                         "xml_sequence_character_edits"}) {
                if (left.has(field)) {
                    delta.put(field, Map.of("auto", auto.get(field).asInt(), "single_column", left.get(field).asInt()));
                }
            }
            deltas.add(delta);
        }
        require(defaults == 8 && deltas.size() == 8);
        Files.writeString(Path.of(args[3]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("paired_case_profiles", first.size(),
                        "jvm_results_equal_except_random_pdf_identity", true, "unchanged_auto_baselines", defaults,
                        "source_and_render_frames_unchanged", true, "complete_mode_diagnostics", deltas))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Copies one row while excluding only the intentionally random PDF document identifier.
     * @param row full original numeric/hash row
     * @return independent comparison view
     */
    private static ObjectNode comparable(final ObjectNode row) {
        final var result = row.deepCopy();
        result.remove("container_pdf_sha256");
        return result;
    }

    /**
     * Indexes complete source/model/mode/threshold keys and rejects duplicated rows.
     * @param report complete harness output
     * @return unique ordered rows
     */
    private static Map<String, ObjectNode> rows(final JsonNode report) {
        final var result = new LinkedHashMap<String, ObjectNode>();
        for (final var row : report.get("results")) {
            final String key = row.get("id").asString() + "/" + row.get("language").asString() + "/"
                               + row.get("page_segmentation").asString() + "/" + row.get("thresholding").asString();
            require(result.put(key, (ObjectNode) row) == null);
        }
        return result;
    }

    /**
     * Bounds local report input before parsing.
     * @param mapper shared parser
     * @param filename original report
     * @return parsed original report
     * @throws Exception if empty, over16MiB or unreadable
     */
    private static JsonNode read(final JsonMapper mapper, final String filename) throws Exception {
        final var path = Path.of(filename);
        require(Files.size(path) > 0 && Files.size(path) <= 16 * 1024 * 1024);
        return mapper.readTree(Files.readAllBytes(path));
    }

    /**
     * Fails instead of silently excluding a broken case, changing a label or printing source text.
     * @param valid whether the full invariant holds
     */
    private static void require(final boolean valid) {
        if (!valid) {
            throw new IllegalStateException("Single-column OCR comparison invariant failed");
        }
    }
}
