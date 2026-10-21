import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** Verifies all original scores and complete paired assembly results without selecting successful cases. */
public final class VerifyAssemblyReports {
    /** Prevents utility instantiation. */
    private VerifyAssemblyReports() {
    }

    /**
     * Requires paired complete results, every prior row unchanged and every assembly denominator retained.
     * @param args manifest, Java21 report, Java25 report, prior32-row report and fresh receipt
     * @throws Exception if inputs, identities, coverage or fresh output fail
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException("Expected manifest, Java21, Java25, prior report and fresh receipt");
        }
        final var mapper = JsonMapper.builder().build();
        final var manifest = mapper.readTree(Files.readAllBytes(Path.of(args[0])));
        final var first = rows(mapper.readTree(Files.readAllBytes(Path.of(args[1]))));
        final var second = rows(mapper.readTree(Files.readAllBytes(Path.of(args[2]))));
        final var prior = rows(mapper.readTree(Files.readAllBytes(Path.of(args[3]))));
        require(first.equals(second));
        int expected = 0;
        int paragraphs = 0;
        int pairs = 0;
        for (final var selected : manifest.get("cases")) {
            final int count = selected.get("reference").get("reference_paragraphs").asInt();
            paragraphs += count;
            pairs += count * (count - 1) / 2;
            for (final var profile : manifest.get("profiles")) {
                final String key = selected.get("id").asString() + "/" + profile.asString();
                final var row = first.get(key);
                require(row != null && row.get("reference_paragraphs").asInt() == count);
                require(row.get("reference_order_pairs").asInt() == count * (count - 1) / 2);
                require(row.get("ordered_reference_sha256")
                                .equals(selected.get("reference").get("ordered_reference_sha256")));
                if (!profile.asString().equals("SOURCE_PARAGRAPHS_ASSEMBLED")) {
                    require(row.equals(prior.get(key)));
                }
                expected++;
            }
        }
        require(first.size() == expected && prior.size() == expected - manifest.get("cases").size());
        Files.writeString(Path.of(args[4]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("paired_case_profiles", expected,
                        "prior_rows_unchanged", prior.size(), "paired_results_equal", true,
                        "reference_paragraphs_per_profile", paragraphs, "reference_order_pairs_per_profile", pairs,
                        "all_failures_and_denominators_retained", true))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Indexes complete numeric/hash rows without accepting duplicate observations.
     * @param report original harness report
     * @return uniquely keyed unchanged rows
     */
    private static Map<String, JsonNode> rows(final JsonNode report) {
        final var result = new LinkedHashMap<String, JsonNode>();
        for (final var row : report.get("results")) {
            require(result.put(row.get("id").asString() + "/" + row.get("profile").asString(), row) == null);
        }
        return result;
    }

    /**
     * Rejects incomplete or changed evidence without printing source text.
     * @param condition required evidence invariant
     */
    private static void require(final boolean condition) {
        if (!condition) {
            throw new IllegalArgumentException("Assembly report verification failed");
        }
    }
}
