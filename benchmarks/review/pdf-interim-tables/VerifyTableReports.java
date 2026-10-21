import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** Verifies complete paired table metrics and exact native metadata without conflating JVM/capture identities. */
public final class VerifyTableReports {
    /** Prevents utility construction. */
    private VerifyTableReports() {
    }

    /**
     * Requires all ten case/profile rows, full independent denominators and equal current native captures.
     * Runtime strings and raw capture hashes are retained in reports but excluded from numeric equality.
     * @param args manifest, Java21 report, Java25 report, native21 directory, native25 directory and NEW receipt
     * @throws Exception if case coverage, source frames, table metadata or paired metrics differ
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 6) {
            throw new IllegalArgumentException(
                    "Expected manifest, scores21, scores25, native21, native25 and NEW receipt");
        }
        final var mapper = JsonMapper.builder().build();
        final var manifest = mapper.readTree(Files.readAllBytes(Path.of(args[0])));
        final var first = rows(mapper.readTree(Files.readAllBytes(Path.of(args[1]))));
        final var second = rows(mapper.readTree(Files.readAllBytes(Path.of(args[2]))));
        require(first.equals(second));
        int expected = 0;
        int rowReferences = 0;
        int anchorReferences = 0;
        for (final var document : manifest.get("cases")) {
            final boolean business = document.get("id").asString().equals("business");
            for (final var selected : document.get("regions")) {
                final String id = selected.get("id").asString();
                for (final String filename : java.util.List.of("source.json", "native-first.json")) {
                    final var left = mapper.readTree(Files.readAllBytes(Path.of(args[3], id, filename)));
                    final var right = mapper.readTree(Files.readAllBytes(Path.of(args[4], id, filename)));
                    require(left.equals(right));
                }
                final int denominator = selected.get(business ? "reference_rows" : "expected_anchors").asInt();
                if (business) {
                    rowReferences += denominator;
                } else {
                    anchorReferences += denominator;
                }
                for (final String profile :
                        java.util.List.of("DOCWEAVE_CONFIGURED_NATIVE", "UNSTRUCTURED_CONFIGURED_HI_RES")) {
                    final var row = first.get(id + "/" + profile);
                    require(row != null);
                    require(row.get(business ? "reference_content_rows" : "expected_anchors").asInt() == denominator);
                    System.out.printf("%s %s exact=%d/%d outcome=%s%n", id, profile,
                            row.get(business ? "exact_ordinal_rows" : "exact_span_text_anchors").asInt(), denominator,
                            row.get("outcome").asString());
                    expected++;
                }
            }
        }
        require(first.size() == expected);
        Files.writeString(Path.of(args[5]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("paired_case_profiles", expected,
                        "all_numeric_results_equal", true, "native_source_frames_and_complete_table_metadata_equal",
                        true, "issuer_row_references_per_profile", rowReferences,
                        "publisher_anchor_references_per_profile", anchorReferences,
                        "all_missing_extra_and_failed_cases_retained", true,
                        "runtime_and_capture_hash_fields_retained_separately", true))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Indexes every result without duplicates, removing only explicitly mechanical identity fields.
     * @param report complete raw scored report
     * @return all comparable numeric/structural outcomes by original case/profile
     */
    private static Map<String, JsonNode> rows(final JsonNode report) {
        final var result = new LinkedHashMap<String, JsonNode>();
        for (final var row : report.get("results")) {
            final var copy = (ObjectNode) row.deepCopy();
            copy.remove("runtime");
            copy.remove("capture_sha256");
            require(result.put(row.get("id").asString() + "/" + row.get("profile").asString(), copy) == null);
        }
        return result;
    }

    /**
     * Rejects missing or altered evidence without printing publisher text.
     * @param condition required evidence invariant
     */
    private static void require(final boolean condition) {
        if (!condition) {
            throw new IllegalArgumentException("Paired table evidence differs or is incomplete");
        }
    }
}
