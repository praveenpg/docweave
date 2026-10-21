import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/** Verifies Java 21 replay identities and complete denominators without requiring a second runtime. */
public final class VerifyReplay {
    /** Prevents utility construction. */
    private VerifyReplay() {
    }

    /**
     * Checks every original document, case and region, retaining unsuccessful extraction observations.
     * Uses the existing multi-page region verifier without modifying its arithmetic or labels.
     * @param args evidence directory, existing multi-page verifier class directory and fresh receipt
     * @throws Exception if any identity, population or arithmetic invariant is violated
     */
    public static void main(final String[] args) throws Exception {
        require(args.length == 3);
        final var root = Path.of(args[0]);
        final var mapper = JsonMapper.builder().build();
        final var manifestPath = root.resolve("replay-manifest.json");
        final var manifest = mapper.readTree(Files.readAllBytes(manifestPath));
        final var tool = mapper.readTree(Files.readAllBytes(root.resolve("replay-tool.json")));
        require(hash(manifestPath).equals(tool.get("replay_manifest_sha256").asString())
                && hash(Path.of(tool.get("tool").asString())).equals(tool.get("tool_sha256").asString()));
        final var proposalPath = root.resolve("manifest.json");
        require(hash(proposalPath).equals(manifest.get("proposal_manifest_sha256").asString()));
        final var proposal = mapper.readTree(Files.readAllBytes(proposalPath));
        require(hash(Path.of(proposal.get("capture_tool").asString()))
                        .equals(proposal.get("capture_tool_sha256").asString()));
        final var referencePath = Path.of(manifest.get("reference_manifest").asString());
        require(hash(referencePath).equals(manifest.get("reference_manifest_sha256").asString()));
        final var reference = mapper.readTree(Files.readAllBytes(referencePath));
        final var reportPath = root.resolve("native-replay-java21.json");
        final var report = mapper.readTree(Files.readAllBytes(reportPath));
        require(report.get("runtime").asString().startsWith("21.")
                && report.get("replay_manifest_sha256").asString().equals(hash(manifestPath))
                && report.get("scope").equals(manifest.get("scope")));
        // Reuse the historical geometry verifier; only its paired-runtime entry point is bypassed.
        try (final var loader = new java.net.URLClassLoader(
                     new java.net.URL[] {Path.of(args[1]).toUri().toURL()}, VerifyReplay.class.getClassLoader())) {
            final var regions = loader.loadClass("VerifyMultiplePageRows")
                                        .getDeclaredMethod("regions", JsonNode.class, JsonNode.class);
            regions.setAccessible(true);
            final var indexed = new HashMap<String, JsonNode>();
            for (final var row : report.get("results")) {
                require(indexed.put(row.get("case").asString() + "/" + row.get("mode").asString(), row) == null);
            }
            final var documents = new HashMap<String, JsonNode>();
            for (final var document : report.get("documents")) {
                require(documents.put(document.get("profile").asString(), document) == null);
            }
            int cases = 0;
            int fragments = 0;
            int references = 0;
            for (final var selected : reference.get("cases")) {
                final int expected = selected.get("reference_rows").asInt();
                require(expected > 0);
                references += expected;
                for (final var profile : manifest.get("profiles")) {
                    final var document = documents.get(profile.asString());
                    final var row = indexed.get(selected.get("id").asString() + "/" + profile.asString());
                    require(document != null && row != null && row.get("reference_content_rows").asInt() == expected
                            && row.get("outcome").equals(document.get("outcome"))
                            && row.get("document_elements").equals(document.get("document_elements"))
                            && document.get("original_pages").asInt() == reference.get("source_pages").asInt());
                    final boolean associated = (boolean) regions.invoke(null, selected.get("regions"), row);
                    final int observed = row.get("observed_content_rows").asInt();
                    final int exact = row.get("exact_ordinal_rows").asInt();
                    require(observed >= 0 && exact >= 0 && exact <= Math.min(expected, observed)
                            && row.get("missing_row_positions").asInt() == Math.max(0, expected - observed)
                            && row.get("extra_row_positions").asInt() == Math.max(0, observed - expected)
                            && row.get("complete_row_content").asBoolean()
                                       == (associated && expected == observed && exact == expected));
                    if (row.get("outcome").asString().equals("SUCCESS")) {
                        require(document.get("layout_calls").asInt() == reference.get("source_pages").asInt());
                    } else {
                        require(observed == 0 && exact == 0 && row.get("document_elements").asInt() == 0);
                    }
                    cases++;
                    fragments += selected.get("regions").size();
                }
            }
            require(cases == indexed.size() && documents.size() == manifest.get("profiles").size()
                    && cases == reference.get("cases").size() * manifest.get("profiles").size()
                    && references == reference.get("reference_rows_per_profile").asInt());
            Files.writeString(Path.of(args[2]),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("runtime", "Java 21 only",
                            "report_sha256", hash(reportPath), "case_profiles", cases, "region_profiles", fragments,
                            "reference_rows_per_profile", references, "complete_populations_retained", true))
                            + "\n",
                    StandardOpenOption.CREATE_NEW);
        }
    }

    /**
     * Hashes a bounded local evidence file without interpreting publisher text.
     * @param path existing identity-bearing file
     * @return lowercase SHA-256 digest
     * @throws Exception if input or digest access fails
     */
    private static String hash(final Path path) throws Exception {
        require(Files.size(path) <= 8 * 1024 * 1024);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    /**
     * Rejects inconsistent evidence rather than changing measurements.
     * @param valid required invariant
     */
    private static void require(final boolean valid) {
        if (!valid) {
            throw new IllegalArgumentException("Native replay evidence verification failed");
        }
    }
}
