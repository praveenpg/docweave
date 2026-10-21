import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Verifies full paired OCR text coverage while retaining undeclared-order qualifications and previous native controls.
 */
public final class VerifyScanReports {
    /** Prevents utility construction. */
    private VerifyScanReports() {
    }

    /**
     * Requires all source/model/profile results on both JVMs and unchanged old single-model native evidence.
     * Only per-process capture hashes are omitted from numeric equality; shared container identities remain checked.
     * @param args native manifest, paired21, paired25, native21, native25, previous native study and NEW receipt
     * @throws Exception if coverage, frozen references, qualifications or numeric equality fail
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 7) {
            throw new IllegalArgumentException(
                    "Expected manifest, paired21/25, native21/25, prior native study, NEW receipt");
        }
        final var mapper = JsonMapper.builder().build();
        final var manifest = mapper.readTree(Files.readAllBytes(Path.of(args[0])));
        final var first = paired(mapper.readTree(Files.readAllBytes(Path.of(args[1]))));
        final var second = paired(mapper.readTree(Files.readAllBytes(Path.of(args[2]))));
        require(first.equals(second));
        final var nativeFirst = nativeRows(mapper.readTree(Files.readAllBytes(Path.of(args[3]))));
        final var nativeSecond = nativeRows(mapper.readTree(Files.readAllBytes(Path.of(args[4]))));
        require(nativeFirst.equals(nativeSecond));
        final var previous = nativeRows(mapper.readTree(Files.readAllBytes(Path.of(args[5]))));
        int expected = 0;
        int explicitOrderSources = 0;
        int characters = 0;
        for (final var source : manifest.get("cases")) {
            characters += source.get("reference_code_points").asInt();
            boolean explicit = false;
            for (final var model : manifest.get("profiles")) {
                final String id = source.get("id").asString() + "-" + model.get("language").asString();
                require(nativeFirst.get(id).equals(previous.get(id)));
                for (final String profile :
                        java.util.List.of("DOCWEAVE_CONFIGURED_OCR", "UNSTRUCTURED_CONFIGURED_OCR_ONLY")) {
                    final var row = first.get(id + "/" + profile);
                    require(row != null
                            && row.get("reference_code_points").equals(source.get("reference_code_points")));
                    require(row.get("reference_text_sha256").equals(source.get("reference_text_sha256")));
                    explicit = row.get("reference_line_order_explicit").asBoolean();
                    final String prefix = explicit ? "global_" : "xml_sequence_";
                    require(row.has(prefix + "character_edits")
                            && (explicit || !row.has("global_character_error_rate")));
                    System.out.printf("%s %s %scharacter_edits=%d/%d outcome=%s%n", id, profile, prefix,
                            row.get(prefix + "character_edits").asInt(), row.get("reference_code_points").asInt(),
                            row.get("outcome").asString());
                    expected++;
                }
            }
            if (explicit) {
                explicitOrderSources++;
            }
        }
        require(first.size() == expected && nativeFirst.size() * 2 == expected);
        Files.writeString(Path.of(args[6]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("paired_case_profiles", expected,
                        "all_numeric_and_reference_results_equal", true, "native_complete_line_metrics_unchanged",
                        nativeFirst.size(), "reference_code_points_per_single_model_profile", characters,
                        "sources_with_declared_logical_line_order", explicitOrderSources,
                        "other_sources_keep_xml_sequence_qualification",
                        manifest.get("cases").size() - explicitOrderSources,
                        "all_shared_container_model_and_failed_denominators_retained", true))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Indexes paired numeric rows without accepting duplicates or discarding source/container/model identities.
     * @param report complete paired output
     * @return all comparable outcomes
     */
    private static Map<String, JsonNode> paired(final JsonNode report) {
        final var result = new LinkedHashMap<String, JsonNode>();
        for (final var row : report.get("results")) {
            final var copy = (ObjectNode) row.deepCopy();
            copy.remove("capture_sha256");
            require(result.put(row.get("id").asString() + "/" + row.get("profile").asString(), copy) == null);
        }
        return result;
    }

    /**
     * Indexes all native cases; only generated PDF-byte identities are excluded from historical comparison.
     * Same-byte pairing is checked separately by the scored reports; old containers had random generated PDF IDs.
     * @param report complete native study, including any other declared profiles
     * @return unchanged native metric/frame records
     */
    private static Map<String, JsonNode> nativeRows(final JsonNode report) {
        final var result = new LinkedHashMap<String, JsonNode>();
        for (final var row : report.get("results")) {
            final var copy = (ObjectNode) row.deepCopy();
            copy.remove("container_pdf_sha256");
            require(result.put(row.get("id").asString() + "-" + row.get("language").asString(), copy) == null);
        }
        return result;
    }

    /**
     * Rejects incomplete or altered evidence without printing independent reference text.
     * @param condition required paired invariant
     */
    private static void require(final boolean condition) {
        if (!condition) {
            throw new IllegalArgumentException("Paired OCR evidence is incomplete or changed");
        }
    }
}
