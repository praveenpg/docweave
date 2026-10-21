import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Numeric-only cross-JVM and fixed-baseline verification for the source-resolution replay. */
public final class CompareReports {
    /** Prevents utility instantiation. */
    private CompareReports() {
    }

    /**
     * Requires complete matching case keys, identical JVM results and unchanged source/render pixel frames.
     * Random PDF document identifiers are the sole ignored per-case field between JVM reports.
     * @param args prior72DPI report, source-resolution Java21 report, Java25 report and fresh output
     * @throws Exception if any invariant fails or a report cannot be read or written
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("Expected prior, Java21, Java25 and fresh verification paths");
        }
        final var mapper = JsonMapper.builder().build();
        final var priorReport = mapper.readTree(Files.readAllBytes(Path.of(args[0])));
        final var firstReport = mapper.readTree(Files.readAllBytes(Path.of(args[1])));
        final var secondReport = mapper.readTree(Files.readAllBytes(Path.of(args[2])));
        require(priorReport.get("executable_sha256").equals(firstReport.get("executable_sha256"))
                && firstReport.get("executable_sha256").equals(secondReport.get("executable_sha256")));
        final var prior = rows(priorReport);
        final var first = rows(firstReport);
        final var second = rows(secondReport);
        require(!first.isEmpty() && first.keySet().equals(second.keySet()) && first.keySet().equals(prior.keySet()));
        final var differences = new ArrayList<Map<String, Object>>();
        for (final var entry : first.entrySet()) {
            final var key = entry.getKey();
            final var left = entry.getValue().deepCopy();
            final var right = second.get(key).deepCopy();
            left.remove("container_pdf_sha256");
            right.remove("container_pdf_sha256");
            require(left.equals(right));
            final var old = prior.get(key);
            require(left.get("source_render_rgb_equal").asBoolean());
            require(left.get("source_rgb_sha256").equals(old.get("source_rgb_sha256")));
            require(left.get("ocr_frames").equals(old.get("ocr_frames")));
            for (final String field : new String[] {"reference_lines", "reference_text_sha256",
                         "global_reference_code_points", "reference_line_code_points", "reference_line_order_explicit",
                         "model_sha256", "page_segmentation"}) {
                require(left.get(field).equals(old.get(field)));
            }
            final var delta = new LinkedHashMap<String, Object>();
            delta.put("case", key);
            for (final String field : new String[] {"associated_reference_lines", "exact_associated_lines",
                         "missing_reference_lines", "predicted_lines_without_reference", "global_character_edits",
                         "xml_sequence_character_edits"}) {
                if (left.has(field)) {
                    delta.put(field,
                            Map.of("prior", old.get(field).asInt(), "source_resolution", left.get(field).asInt()));
                }
            }
            differences.add(delta);
        }
        Files.writeString(Path.of(args[3]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                        Map.of("paired_cases", first.size(), "jvm_results_equal_except_random_pdf_identity", true,
                                "source_and_render_frames_unchanged", true, "baseline_diagnostics", differences))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Indexes every case without dropping duplicate or malformed identities.
     * @param report complete numeric/hash-only harness report
     * @return ordered unique case/profile records
     */
    private static Map<String, ObjectNode> rows(final JsonNode report) {
        final var result = new LinkedHashMap<String, ObjectNode>();
        for (final var row : report.get("results")) {
            final String key = row.get("id").asString() + "/" + row.get("language").asString() + "/"
                               + row.get("thresholding").asString();
            require(result.put(key, (ObjectNode) row) == null);
        }
        return result;
    }

    /**
     * Fails the comparison rather than silently excluding a broken frame or unmatched selected case.
     * @param valid whether an invariant holds
     */
    private static void require(final boolean valid) {
        if (!valid) {
            throw new IllegalStateException("Source-resolution report invariant failed");
        }
    }
}
