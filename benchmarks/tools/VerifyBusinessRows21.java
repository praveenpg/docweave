import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/** Java 21-only complete issuer-row verification, retaining failed sources and fixed denominators. */
public final class VerifyBusinessRows21 {
    /** Prevents utility construction. */
    private VerifyBusinessRows21() {
    }

    /**
     * Checks every original case/profile and compares all profiles except explicitly declared changed ones.
     * @param args manifest, report, baseline report or minus, changed profiles CSV or minus, fresh receipt
     * @throws Exception if source identities, complete populations, arithmetic or regression equality fail
     */
    public static void main(final String[] args) throws Exception {
        require(args.length == 5);
        final var mapper = JsonMapper.builder().build();
        final var manifest = mapper.readTree(Files.readAllBytes(Path.of(args[0])));
        final var report = mapper.readTree(Files.readAllBytes(Path.of(args[1])));
        require(report.get("runtime").asString().startsWith("21."));
        final var indexed = rows(report);
        final var changed = args[3].equals("-") ? Set.<String>of() : Set.copyOf(Arrays.asList(args[3].split(",")));
        final var baseline = args[2].equals("-") ? Map.<String, JsonNode>of()
                                                 : rows(mapper.readTree(Files.readAllBytes(Path.of(args[2]))));
        int cases = 0;
        int references = 0;
        int unchanged = 0;
        for (final var selected : manifest.get("cases")) {
            require(!selected.has("regions"));
            final int count = selected.get("reference_rows").asInt();
            require(count > 0);
            references += count;
            for (final var profile : manifest.get("profiles")) {
                final String key = selected.get("id").asString() + "/" + profile.asString();
                final var row = indexed.get(key);
                require(row != null && row.get("reference_content_rows").asInt() == count
                        && row.get("source_page").equals(selected.get("page")));
                final int observed = row.get("observed_content_rows").asInt();
                final int exact = row.get("exact_ordinal_rows").asInt();
                final double overlap = row.get("association_iou").asDouble();
                require(observed >= 0 && exact >= 0 && exact <= Math.min(count, observed)
                        && row.get("missing_row_positions").asInt() == Math.max(0, count - observed)
                        && row.get("extra_row_positions").asInt() == Math.max(0, observed - count)
                        && row.get("complete_row_content").asBoolean() == (count == observed && exact == count)
                        && Double.isFinite(overlap) && overlap >= 0 && overlap <= 1);
                if (row.get("associated").asBoolean()) {
                    require(overlap >= .5 && row.get("tables_on_page").asInt() > 0);
                } else {
                    require(observed == 0 && exact == 0 && overlap == 0);
                }
                if (!row.get("outcome").asString().equals("SUCCESS")) {
                    require(row.get("document_elements").asInt() == 0 && observed == 0 && exact == 0);
                }
                if (!baseline.isEmpty() && !changed.contains(profile.asString())) {
                    require(row.equals(baseline.get(key)));
                    unchanged++;
                }
                cases++;
            }
        }
        require(indexed.size() == cases && indexed.keySet().containsAll(baseline.keySet()));
        if (manifest.has("candidate_case_profiles")) {
            require(cases == manifest.get("candidate_case_profiles").asInt());
        }
        if (manifest.has("reference_rows_per_profile")) {
            require(references == manifest.get("reference_rows_per_profile").asInt());
        }
        Files.writeString(Path.of(args[4]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("runtime", "Java 21 only",
                        "manifest_sha256", hash(Path.of(args[0])), "report_sha256", hash(Path.of(args[1])),
                        "baseline_report_sha256", args[2].equals("-") ? "none" : hash(Path.of(args[2])),
                        "case_profiles", cases, "reference_rows_per_profile", references, "unchanged_case_profiles",
                        unchanged, "all_failures_and_denominators_retained", true))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Rejects duplicate case/profile observations before calculating population sizes.
     * @param report complete original report
     * @return unique numeric observation index
     */
    private static Map<String, JsonNode> rows(final JsonNode report) {
        final var indexed = new HashMap<String, JsonNode>();
        for (final var row : report.get("results")) {
            require(indexed.put(row.get("case").asString() + "/" + row.get("mode").asString(), row) == null);
        }
        return indexed;
    }

    /**
     * Pins an unchanged local source protocol or numeric report.
     * @param path existing evidence file
     * @return lowercase SHA-256 digest
     * @throws Exception if file access or mandatory digest support fails
     */
    private static String hash(final Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    /**
     * Rejects inconsistent evidence without repairing observations.
     * @param valid required invariant
     */
    private static void require(final boolean valid) {
        if (!valid) {
            throw new IllegalArgumentException("Business row evidence verification failed");
        }
    }
}
