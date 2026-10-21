import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Verifies all original denominators and summarizes diagnostic statuses without selecting only located paragraphs. */
public final class VerifyTextDifferences {
    /** Prevents construction of the executable verification utility. */
    private VerifyTextDifferences() {
    }

    /**
     * Requires complete cross-runtime equality and retains unlocated/ambiguous/budget failures separately.
     * @param args protocol, reports21/25 and NEW numeric receipt
     * @throws Exception if identity, coverage, strict-score equality or complete trace accounting fails
     */
    public static void main(final String[] args) throws Exception {
        require(args.length == 4);
        final var mapper = JsonMapper.builder().build();
        final var protocolBytes = Files.readAllBytes(Path.of(args[0]));
        final var protocol = mapper.readTree(protocolBytes);
        final var first = (ObjectNode) mapper.readTree(Files.readAllBytes(Path.of(args[1])));
        final var second = (ObjectNode) mapper.readTree(Files.readAllBytes(Path.of(args[2])));
        require(first.get("runtime").asString().startsWith("21.")
                && second.get("runtime").asString().startsWith("25."));
        first.remove("runtime");
        second.remove("runtime");
        require(first.equals(second) && first.get("changed_strict_baselines").asInt() == 0);
        require(first.get("protocol_sha256").asString().equals(hash(protocolBytes)));
        final var manifest = mapper.readTree(Files.readAllBytes(Path.of(protocol.get("source_manifest").asString())));
        final var summaries = new ArrayList<Map<String, Object>>();
        final var expectedKeys = new java.util.HashSet<String>();
        for (final var source : manifest.get("cases")) {
            for (final var profile : protocol.get("profiles")) {
                expectedKeys.add(source.get("id").asString() + "/" + profile.asString());
            }
        }
        final List<String> traceFields = List.of("diagnostic_character_edits", "trace_insertions", "trace_deletions",
                "trace_substitutions", "trace_space_nbsp_substitutions", "trace_spaces_inserted_after_hyphen",
                "trace_hyphen_insertions", "trace_hyphen_deletions", "trace_steps_with_multiple_optimal_predecessors");
        int observations = 0;
        for (final var row : first.get("results")) {
            require(expectedKeys.remove(row.get("id").asString() + "/" + row.get("profile").asString()));
            require(row.get("strict_baseline_equal").asBoolean() && row.get("outcome").asString().equals("SUCCESS"));
            final var status = new LinkedHashMap<String, Integer>();
            final var totals = new LinkedHashMap<String, Integer>();
            traceFields.forEach(field -> totals.put(field, 0));
            final var strict = row.get("strict");
            require(strict.get("reference_paragraphs").asInt() == row.get("diagnostics").size());
            int ordinal = 0;
            for (final var paragraph : row.get("diagnostics")) {
                require(paragraph.get("ordinal").asInt() == ordinal++);
                status.merge(paragraph.get("status").asString(), 1, Integer::sum);
                if (paragraph.get("status").asString().equals("UNIQUE_ANCHOR_DIAGNOSTIC")) {
                    require(paragraph.get("diagnostic_character_edits").asInt()
                            == paragraph.get("trace_insertions").asInt() + paragraph.get("trace_deletions").asInt()
                                       + paragraph.get("trace_substitutions").asInt());
                    for (final String field : traceFields) {
                        totals.merge(field, paragraph.get(field).asInt(), Integer::sum);
                    }
                } else {
                    require(!paragraph.has("diagnostic_character_edits"));
                }
                observations++;
            }
            require(status.getOrDefault("EXACT", 0) == strict.get("uniquely_matched_paragraphs").asInt());
            final var summary = new LinkedHashMap<String, Object>();
            summary.put("id", row.get("id").asString());
            summary.put("profile", row.get("profile").asString());
            summary.put("reference_paragraphs", ordinal);
            summary.put("statuses", status);
            summary.put("located_trace_totals_not_whole_document_accuracy", totals);
            summaries.add(summary);
            System.out.println(
                    row.get("id").asString() + " " + row.get("profile").asString() + " " + status + " " + totals);
        }
        require(expectedKeys.isEmpty()
                && observations
                           == protocol.get("reference_paragraphs_per_profile").asInt()
                                      * protocol.get("profiles").size());
        final var result = new LinkedHashMap<String, Object>();
        result.put("all_numeric_and_strict_results_equal", true);
        result.put("strict_baselines_unchanged", first.get("results").size());
        result.put("all_paragraph_observations_retained", observations);
        result.put("diagnostic_traces_are_not_accuracy_or_causal_labels", true);
        result.put("results", summaries);
        Files.writeString(Path.of(args[3]), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Binds the exact pre-execution protocol.
     * @param bytes complete protocol bytes
     * @return lowercase SHA-256 identity
     * @throws Exception if mandatory SHA-256 is unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Rejects missing or inconsistent evidence before writing a summary.
     * @param valid required provenance/accounting condition
     */
    private static void require(final boolean valid) {
        if (!valid) {
            throw new IllegalArgumentException("Incomplete or changed scientific diagnostic evidence");
        }
    }
}
