import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** Verifies complete paired scientific reading reports without selecting easier references or matching text. */
public final class VerifyReports {
    /** Prevents utility instantiation. */
    private VerifyReports() {
    }

    /**
     * Requires every frozen source/profile exactly once and equal complete numeric/hash results on both JVMs.
     * The optional initial report verifies that isolating colliding test property names did not change outputs.
     * @param args frozen manifest, finalJava21 report, finalJava25 report, initialJava21 report or dash, fresh output
     * @throws Exception if reading, coverage, runtime equality or fresh writing fails
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException("Expected manifest, Java21, Java25, initial-or-dash and fresh output");
        }
        final var mapper = JsonMapper.builder().build();
        final var manifest = mapper.readTree(Files.readAllBytes(Path.of(args[0])));
        final var first = rows(mapper.readTree(Files.readAllBytes(Path.of(args[1]))));
        final var second = rows(mapper.readTree(Files.readAllBytes(Path.of(args[2]))));
        int expected = 0;
        int paragraphs = 0;
        int pairs = 0;
        for (final var selected : manifest.get("cases")) {
            final int count = selected.get("reference").get("reference_paragraphs").asInt();
            paragraphs += count;
            pairs += count * (count - 1) / 2;
            for (final var profile : manifest.get("profiles")) {
                final String key = selected.get("id").asString() + "/" + profile.asString();
                require(first.containsKey(key) && second.containsKey(key));
                require(first.get(key).get("reference_paragraphs").asInt() == count);
                require(first.get(key).get("reference_order_pairs").asInt() == count * (count - 1) / 2);
                require(first.get(key)
                                .get("ordered_reference_sha256")
                                .equals(selected.get("reference").get("ordered_reference_sha256")));
                expected++;
            }
        }
        require(first.size() == expected && first.equals(second));
        if (!args[3].equals("-")) {
            require(first.equals(rows(mapper.readTree(Files.readAllBytes(Path.of(args[3]))))));
        }
        Files.writeString(Path.of(args[4]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("paired_case_profiles", expected,
                        "paired_results_equal", true, "frozen_paragraphs_per_profile", paragraphs,
                        "frozen_order_pairs_per_profile", pairs, "initial_namespace_change_outputs_checked",
                        !args[3].equals("-"), "complete_denominators_retained", true))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Indexes every numeric report row, rejecting duplicate case/profile keys.
     * @param report complete JVM harness output
     * @return uniquely indexed unchanged rows
     */
    private static Map<String, JsonNode> rows(final JsonNode report) {
        final var result = new LinkedHashMap<String, JsonNode>();
        for (final var row : report.get("results")) {
            final String key = row.get("id").asString() + "/" + row.get("profile").asString();
            require(result.put(key, row) == null);
        }
        return result;
    }

    /**
     * Stops verification instead of silently omitting mismatched cases or modifying their reference counts.
     * @param valid whether the invariant holds
     */
    private static void require(final boolean valid) {
        if (!valid) {
            throw new IllegalArgumentException("Scientific reading report verification failed");
        }
    }
}
