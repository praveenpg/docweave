import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/** Checks complete multi-page statement evidence without repairing or dropping any page fragment. */
public final class VerifyMultiplePageRows {
    /** Prevents executable utility construction. */
    private VerifyMultiplePageRows() {
    }

    /**
     * Validates paired runtime results, fixed regions, arithmetic and complete reference coverage.
     * @param args frozen manifest, reports21/25 and fresh verification receipt
     * @throws Exception if evidence is inconsistent or fresh output cannot be written
     */
    public static void main(final String[] args) throws Exception {
        require(args.length == 4);
        final var mapper = JsonMapper.builder().build();
        final var manifestBytes = Files.readAllBytes(Path.of(args[0]));
        final var manifest = mapper.readTree(manifestBytes);
        final var first = (ObjectNode) mapper.readTree(Files.readAllBytes(Path.of(args[1])));
        final var second = (ObjectNode) mapper.readTree(Files.readAllBytes(Path.of(args[2])));
        require(first.get("runtime").asString().startsWith("21.")
                && second.get("runtime").asString().startsWith("25."));
        first.remove("runtime");
        second.remove("runtime");
        require(first.equals(second));
        final var indexed = new HashMap<String, JsonNode>();
        for (final var row : first.get("results")) {
            require(indexed.put(row.get("case").asString() + "/" + row.get("mode").asString(), row) == null);
        }
        int cases = 0;
        int references = 0;
        int fragments = 0;
        for (final var selected : manifest.get("cases")) {
            final int count = selected.get("reference_rows").asInt();
            require(count > 0);
            references += count;
            for (final var profile : manifest.get("profiles")) {
                final var row = indexed.get(selected.get("id").asString() + "/" + profile.asString());
                require(row != null && row.get("reference_content_rows").asInt() == count);
                final boolean completeRegions = regions(selected.get("regions"), row);
                final int observed = row.get("observed_content_rows").asInt();
                final int exact = row.get("exact_ordinal_rows").asInt();
                require(observed >= 0 && exact >= 0 && exact <= Math.min(count, observed));
                require(row.get("missing_row_positions").asInt() == Math.max(0, count - observed)
                        && row.get("extra_row_positions").asInt() == Math.max(0, observed - count));
                require(row.get("complete_row_content").asBoolean()
                        == (completeRegions && count == observed && exact == count));
                if (!row.get("outcome").asString().equals("SUCCESS")) {
                    require(row.get("document_elements").asInt() == 0 && observed == 0 && exact == 0
                            && row.get("associated_regions").asInt() == 0);
                }
                fragments += selected.get("regions").size();
                cases++;
                System.out.println(selected.get("id").asString() + "/" + profile.asString() + " " + exact + "/" + count
                                   + " exact, " + observed + " observed");
            }
        }
        require(cases == manifest.get("candidate_case_profiles").asInt() && indexed.size() == cases
                && references == manifest.get("reference_rows_per_profile").asInt());
        Files.writeString(Path.of(args[3]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("manifest_sha256",
                        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(manifestBytes)),
                        "paired_results_equal", true, "case_profiles", cases, "page_region_profiles", fragments,
                        "reference_rows_per_profile", references, "all_failures_and_denominators_retained", true))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Requires all original regions in order, including missing associations and partial row observations.
     * @param expected frozen original page regions
     * @param row complete statement observation
     * @return whether every original region has a qualifying geometry association
     */
    private static boolean regions(final JsonNode expected, final JsonNode row) {
        require(expected.isArray() && expected.size() >= 2 && expected.size() <= 32);
        require(row.get("regions").size() == expected.size() && row.get("source_pages").size() == expected.size());
        int previous = 0;
        int associated = 0;
        int observed = 0;
        for (int index = 0; index < expected.size(); index++) {
            final int page = expected.get(index).get("page").asInt();
            final var actual = row.get("regions").get(index);
            require(page > previous && actual.get("source_page").asInt() == page
                    && row.get("source_pages").get(index).asInt() == page);
            previous = page;
            final int rows = actual.get("observed_content_rows").asInt();
            final int tables = actual.get("tables_on_page").asInt();
            final double overlap = actual.get("association_iou").asDouble();
            require(rows >= 0 && tables >= 0 && actual.get("selected_page_elements").asInt() >= tables
                    && Double.isFinite(overlap) && overlap >= 0 && overlap <= 1);
            if (actual.get("associated").asBoolean()) {
                require(tables > 0 && overlap >= 0.5);
                associated++;
            } else {
                require(rows == 0 && overlap == 0);
            }
            observed += rows;
        }
        require(row.get("observed_content_rows").asInt() == observed
                && row.get("associated_regions").asInt() == associated
                && row.get("all_regions_associated").asBoolean() == (associated == expected.size()));
        return associated == expected.size();
    }

    /**
     * Fails evidence verification without printing publisher content.
     * @param valid required invariant
     */
    private static void require(final boolean valid) {
        if (!valid) {
            throw new IllegalArgumentException("Multiple-page business verification failed");
        }
    }
}
