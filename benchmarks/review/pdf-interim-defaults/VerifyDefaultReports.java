import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** Requires complete default behavior and independent source-raster agreement records on both actual JVMs. */
public final class VerifyDefaultReports {
    /** Prevents utility construction. */
    private VerifyDefaultReports() {
    }

    /**
     * Verifies all fixed default outcomes without relabelling semantic Image detections as source rasters.
     * @param args manifest, Java21 results, Java25 results and NEW numeric receipt
     * @throws Exception if coverage, output identities or cross-runtime behavior differ
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("Expected manifest, default21/25 and NEW receipt");
        }
        final var mapper = JsonMapper.builder().build();
        final var manifest = mapper.readTree(Files.readAllBytes(Path.of(args[0])));
        final var first = rows(mapper.readTree(Files.readAllBytes(Path.of(args[1]))));
        final var second = rows(mapper.readTree(Files.readAllBytes(Path.of(args[2]))));
        require(first.equals(second));
        int expected = 0;
        int agreement = 0;
        int sourceImages = 0;
        int nativeImages = 0;
        for (final var source : manifest.get("cases")) {
            final String id = source.get("id").asString();
            for (final String profile : java.util.List.of("DOCWEAVE_DEFAULT_NATIVE", "UNSTRUCTURED_DEFAULT_AUTO")) {
                final var row = first.get(id + "/" + profile);
                require(row != null && row.get("source_sha256").equals(source.get("sha256")));
                if (profile.startsWith("DOCWEAVE")) {
                    if (row.get("source_page_dimension_multiplicity_agreement").asBoolean()) {
                        agreement++;
                    }
                    sourceImages += row.get("source_ordinary_raster_placements").asInt();
                    nativeImages += row.get("native_raster_placements").asInt();
                }
                System.out.printf("%s %s outcome=%s text_points=%d types=%s%n", id, profile,
                        row.get("outcome").asString(), row.get("text_code_points").asInt(), row.get("element_types"));
                expected++;
            }
        }
        require(first.size() == expected);
        Files.writeString(Path.of(args[3]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("paired_case_profiles", expected,
                        "all_numeric_hash_and_category_results_equal", true,
                        "source_raster_dimension_page_multiplicity_agreement_documents", agreement, "source_documents",
                        manifest.get("cases").size(), "independent_ordinary_source_raster_placements", sourceImages,
                        "native_source_raster_placements", nativeImages,
                        "semantic_figure_region_payload_quality_not_inferred", true))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Indexes all comparable behavior while retaining every mechanical raw hash in the original report.
     * @param report complete default-run outcome summary
     * @return uniquely keyed unchanged comparable outcomes
     */
    private static Map<String, JsonNode> rows(final JsonNode report) {
        final var rows = new LinkedHashMap<String, JsonNode>();
        for (final var row : report.get("results")) {
            final var copy = (ObjectNode) row.deepCopy();
            copy.remove("capture_sha256");
            require(rows.put(row.get("id").asString() + "/" + row.get("profile").asString(), copy) == null);
        }
        return rows;
    }

    /**
     * Rejects missing or altered outcomes without printing publisher text.
     * @param condition required identity/coverage invariant
     */
    private static void require(final boolean condition) {
        if (!condition) {
            throw new IllegalArgumentException("Default PDF evidence differs or is incomplete");
        }
    }
}
