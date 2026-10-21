import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Verifies the complete median experiment without changing source labels or selecting successful profiles. */
public final class CompareMedianReports {
    /** Prevents utility construction. */
    private CompareMedianReports() {
    }

    /**
     * Checks complete JVM equality and baseline source/model/reference identities, retaining all metric deltas.
     * Only random PDF container identity is ignored between JVMs. Filtered pixels are explicitly distinct
     * from the borrowed source/render frame; failed cases remain in the same full Cartesian product.
     * @param args unchanged baseline, Java21 median, Java25 median and fresh verification path
     * @throws Exception if any identity, coverage, frame or fresh-output invariant fails
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
        final var prior = rows(baseline);
        final var first = rows(firstReport);
        final var second = rows(secondReport);
        require(first.size() == 24 && first.keySet().equals(second.keySet()) && first.keySet().equals(prior.keySet()));
        final var differences = new ArrayList<Map<String, Object>>();
        int filteredFrames = 0;
        for (final var entry : first.entrySet()) {
            final var left = entry.getValue().deepCopy();
            final var right = second.get(entry.getKey()).deepCopy();
            left.remove("container_pdf_sha256");
            right.remove("container_pdf_sha256");
            require(left.equals(right));
            require(left.get("experimental_preprocessing").asString().equals("MEDIAN_3X3_REPLICATED_RGB"));
            final var old = prior.get(entry.getKey());
            for (final String field : new String[] {"source_rgb_sha256", "reference_lines", "reference_text_sha256",
                         "global_reference_code_points", "reference_line_code_points", "reference_line_order_explicit",
                         "model_sha256", "page_segmentation", "container_dpi", "engine_source_dpi"}) {
                require(left.get(field).equals(old.get(field)));
            }
            final var frames = left.get("ocr_frames");
            require(frames.isArray() && frames.size() <= 1);
            if (left.get("outcome").asString().equals("SUCCESS")) {
                require(frames.size() == 1 && left.get("source_render_rgb_equal").asBoolean());
            }
            if (!frames.isEmpty()) {
                final var sourceFrame = (ObjectNode) frames.get(0).deepCopy();
                if (sourceFrame.has("engine_rgb_sha256")) {
                    require(sourceFrame.get("engine_rgb_sha256").asString().matches("[a-f0-9]{64}"));
                    filteredFrames++;
                    sourceFrame.remove("engine_rgb_sha256");
                } else {
                    require(!left.get("outcome").asString().equals("SUCCESS"));
                }
                require(old.get("ocr_frames").size() == 1 && sourceFrame.equals(old.get("ocr_frames").get(0)));
            }
            final var delta = new LinkedHashMap<String, Object>();
            delta.put("case", entry.getKey());
            delta.put("outcome", left.get("outcome").asString());
            delta.put("reference_line_order_explicit", left.get("reference_line_order_explicit").asBoolean());
            for (final String field : new String[] {"associated_reference_lines", "exact_associated_lines",
                         "missing_reference_lines", "predicted_lines_without_reference", "global_character_edits",
                         "xml_sequence_character_edits"}) {
                if (left.has(field)) {
                    delta.put(field, Map.of("baseline", old.get(field).asInt(), "median", left.get(field).asInt()));
                }
            }
            differences.add(delta);
        }
        Files.writeString(Path.of(args[3]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("paired_cases", first.size(),
                        "jvm_results_equal_except_random_pdf_identity", true, "source_and_render_frames_unchanged",
                        true, "filtered_engine_frames", filteredFrames, "baseline_diagnostics", differences))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Bounds local report input before parsing.
     * @param mapper shared JSON parser
     * @param filename complete local report
     * @return unchanged parsed content
     * @throws Exception if empty, over16MiB or unreadable
     */
    private static JsonNode read(final JsonMapper mapper, final String filename) throws Exception {
        final var path = Path.of(filename);
        require(Files.size(path) > 0 && Files.size(path) <= 16 * 1024 * 1024);
        return mapper.readTree(Files.readAllBytes(path));
    }

    /**
     * Rejects duplicated case/profile identities rather than losing failures or adding extra evaluations.
     * @param report complete numeric harness output
     * @return uniquely indexed rows
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
     * Rejects inconsistent evidence without printing source text or adjusting any reference.
     * @param valid whether the full invariant holds
     */
    private static void require(final boolean valid) {
        if (!valid) {
            throw new IllegalStateException("Median scan experiment invariant failed");
        }
    }
}
