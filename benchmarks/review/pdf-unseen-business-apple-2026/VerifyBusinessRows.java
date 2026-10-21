import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Verifies complete fixed issuer-row observations without choosing successful policies or dropping failures. */
public final class VerifyBusinessRows {
    /** Prevents executable utility construction. */
    private VerifyBusinessRows() {
    }

    /**
     * Checks source-protocol identities, paired runtimes, every row denominator and optional prior observations.
     * @param args manifest, reports21/25, prior report or minus sign, and fresh receipt path
     * @throws Exception if identities, complete coverage, equality or fresh output fail
     */
    public static void main(final String[] args) throws Exception {
        require(args.length == 5);
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
        final var indexed = rows(first);
        int expected = 0;
        int references = 0;
        for (final var selected : manifest.get("cases")) {
            final int count = selected.get("reference_rows").asInt();
            references += count;
            for (final var profile : manifest.get("profiles")) {
                final var row = indexed.get(selected.get("id").asString() + "/" + profile.asString());
                require(row != null && row.get("reference_content_rows").asInt() == count
                        && row.get("source_page").equals(selected.get("page")));
                final int observed = row.get("observed_content_rows").asInt();
                final int exact = row.get("exact_ordinal_rows").asInt();
                require(observed >= 0 && exact >= 0 && exact <= Math.min(count, observed));
                require(row.get("missing_row_positions").asInt() == Math.max(0, count - observed)
                        && row.get("extra_row_positions").asInt() == Math.max(0, observed - count)
                        && row.get("complete_row_content").asBoolean() == (count == observed && exact == count));
                if (!row.get("associated").asBoolean()) {
                    require(observed == 0 && exact == 0);
                }
                System.out.println(selected.get("id").asString() + "/" + profile.asString() + " " + exact + "/" + count
                                   + " exact, " + observed + " observed, " + row.get("outcome").asString());
                expected++;
            }
        }
        final int declaredReferences =
                manifest.has("reference_rows_per_profile") ? manifest.get("reference_rows_per_profile").asInt() : 114;
        require(references == declaredReferences && indexed.size() == expected);
        int priorCount = 0;
        int unchangedPrior = 0;
        if (!args[3].equals("-")) {
            final var priorBytes = Files.readAllBytes(Path.of(args[3]));
            require(hash(priorBytes).equals(manifest.get("baseline_report_sha256").asString()));
            require(hash(Files.readAllBytes(Path.of(manifest.get("baseline_manifest").asString())))
                            .equals(manifest.get("baseline_manifest_sha256").asString()));
            final var prior = rows(mapper.readTree(priorBytes));
            for (final var entry : prior.entrySet()) {
                boolean changed = false;
                if (manifest.has("changed_profiles")) {
                    for (final var profile : manifest.get("changed_profiles")) {
                        changed |= entry.getValue().get("mode").asString().equals(profile.asString());
                    }
                }
                if (!changed) {
                    require(entry.getValue().equals(indexed.get(entry.getKey())));
                    unchangedPrior++;
                }
            }
            priorCount = prior.size();
            final int declaredPrior =
                    manifest.has("baseline_case_profiles") ? manifest.get("baseline_case_profiles").asInt() : 18;
            final int declaredCandidate =
                    manifest.has("candidate_case_profiles") ? manifest.get("candidate_case_profiles").asInt() : 24;
            require(priorCount == declaredPrior && expected == declaredCandidate);
        } else {
            final boolean frozenCode = manifest.has("frozen_code_commit")
                                       && manifest.get("frozen_code_commit").asString().matches("[a-f0-9]{40}");
            require(expected == 18 || expected == 24 && frozenCode
                    || frozenCode && manifest.has("candidate_case_profiles") && expected > 0
                               && expected == manifest.get("candidate_case_profiles").asInt());
        }
        Files.writeString(Path.of(args[4]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                        Map.of("manifest_sha256", hash(manifestBytes), "paired_results_equal", true, "case_profiles",
                                expected, "reference_rows_per_profile", references, "prior_rows_unchanged",
                                unchangedPrior, "all_failures_and_denominators_retained", true))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Rejects duplicate complete source case/policy observations.
     * @param report original numeric report
     * @return complete uniquely indexed rows
     */
    private static Map<String, JsonNode> rows(final JsonNode report) {
        final var result = new LinkedHashMap<String, JsonNode>();
        for (final var row : report.get("results")) {
            require(result.put(row.get("case").asString() + "/" + row.get("mode").asString(), row) == null);
        }
        return result;
    }

    /**
     * Binds exact original protocol or report bytes.
     * @param bytes complete original bytes
     * @return lowercase SHA256
     * @throws Exception if mandatory digest support is unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Fails evidence verification without printing issuer text.
     * @param condition required invariant
     */
    private static void require(final boolean condition) {
        if (!condition) {
            throw new IllegalArgumentException("Business row verification failed");
        }
    }
}
