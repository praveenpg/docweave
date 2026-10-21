import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/** Complete Java 21 topology/role population verification; does not select successful cases or repair labels. */
public final class VerifyReferenceTableTopology {
    /** Prevents utility construction. */
    private VerifyReferenceTableTopology() {
    }

    /**
     * Verifies original populations, association bounds, arithmetic and complete-topology flags.
     * @param args manifest, report, fresh receipt; optionally baseline report and allowed case/profile keys CSV
     * @throws Exception if any identity, duplicate, arithmetic or full-population invariant fails
     */
    public static void main(final String[] args) throws Exception {
        require(args.length == 3 || args.length == 5);
        final var mapper = JsonMapper.builder().build();
        final var manifest = mapper.readTree(Files.readAllBytes(Path.of(args[0])));
        final var report = mapper.readTree(Files.readAllBytes(Path.of(args[1])));
        require(report.get("runtime").asString().startsWith("21.")
                && report.get("manifest_sha256").asString().equals(hash(Path.of(args[0]))));
        final var rows = new HashMap<String, JsonNode>();
        for (final var row : report.get("results")) {
            require(rows.put(row.get("case").asString() + "/" + row.get("profile").asString(), row) == null);
        }
        int cases = 0;
        int references = 0;
        int headers = 0;
        for (final var selected : manifest.get("cases")) {
            final int count = selected.get("expected_anchors").asInt();
            int expectedHeaders = 0;
            for (final var cell : selected.get("anchors")) {
                if (cell.get("header").asBoolean()) {
                    expectedHeaders++;
                }
            }
            references += count;
            headers += expectedHeaders;
            for (final var profile : manifest.get("profiles")) {
                final var row = rows.get(selected.get("id").asString() + "/" + profile.asString());
                require(row != null && row.get("expected_anchors").asInt() == count
                        && row.get("expected_header_anchors").asInt() == expectedHeaders
                        && row.get("expected_rows").equals(selected.get("rows"))
                        && row.get("expected_columns").equals(selected.get("columns")));
                final int observed = row.get("observed_anchors").asInt();
                final int matched = row.get("matched_anchor_keys").asInt();
                final int roles = row.get("exact_header_roles").asInt();
                final int headerMatches = row.get("matched_header_anchors").asInt();
                require(observed >= 0 && matched >= 0 && matched <= Math.min(observed, count) && roles >= 0
                        && roles <= matched && headerMatches >= 0 && headerMatches <= Math.min(roles, expectedHeaders)
                        && row.get("missing_reference_keys").asInt() == count - matched
                        && row.get("extra_prediction_keys").asInt() == observed - matched);
                final double overlap = row.get("association_iou").asDouble();
                require(Double.isFinite(overlap) && overlap >= 0 && overlap <= 1);
                final boolean associated = row.get("associated").asBoolean();
                require(associated ? overlap >= .5 : overlap == 0 && observed == 0 && matched == 0);
                final boolean complete = associated && row.get("selected_structure_valid").asBoolean()
                                         && observed == count && matched == count
                                         && row.get("observed_rows").equals(selected.get("rows"))
                                         && row.get("observed_columns").equals(selected.get("columns"));
                require(row.get("complete_topology").asBoolean() == complete);
                if (!row.get("outcome").asString().equals("SUCCESS")) {
                    require(row.get("document_elements").asInt() == 0 && !associated && observed == 0);
                }
                cases++;
            }
        }
        require(cases == rows.size() && cases == manifest.get("case_profiles").asInt()
                && references == manifest.get("reference_anchors_per_profile").asInt()
                && headers == manifest.get("reference_header_anchors_per_profile").asInt());
        int unchanged = 0;
        if (args.length == 5) {
            final var baselineManifestPath = Path.of(manifest.get("baseline_manifest").asString());
            final var baselineManifest = mapper.readTree(Files.readAllBytes(baselineManifestPath));
            require(hash(baselineManifestPath).equals(manifest.get("baseline_manifest_sha256").asString())
                    && hash(Path.of(args[3])).equals(manifest.get("baseline_report_sha256").asString())
                    && baselineManifest.get("cases").equals(manifest.get("cases"))
                    && baselineManifest.get("profiles").equals(manifest.get("profiles")));
            final Set<String> allowed =
                    args[4].equals("-") ? Set.of() : Set.copyOf(java.util.Arrays.asList(args[4].split(",")));
            final var declared = new java.util.HashSet<String>();
            for (final var key : manifest.get("changed_case_profiles")) {
                require(declared.add(key.asString()));
            }
            require(rows.keySet().containsAll(allowed) && allowed.equals(declared));
            final var baselineRows = new HashMap<String, JsonNode>();
            final var baselineReport = mapper.readTree(Files.readAllBytes(Path.of(args[3])));
            require(baselineReport.get("runtime").asString().startsWith("21.")
                    && baselineReport.get("manifest_sha256").asString().equals(hash(baselineManifestPath)));
            for (final var row : baselineReport.get("results")) {
                final String key = row.get("case").asString() + "/" + row.get("profile").asString();
                require(baselineRows.put(key, row) == null);
                if (!allowed.contains(key)) {
                    require(row.equals(rows.get(key)));
                    unchanged++;
                }
            }
            require(baselineRows.keySet().equals(rows.keySet()));
        }
        Files.writeString(Path.of(args[2]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                        Map.of("runtime", "Java 21 only", "manifest_sha256", hash(Path.of(args[0])), "report_sha256",
                                hash(Path.of(args[1])), "case_profiles", cases, "reference_anchors_per_profile",
                                references, "reference_header_anchors_per_profile", headers, "unchanged_case_profiles",
                                unchanged, "all_cases_failures_and_denominators_retained", true))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Binds exact local protocol or numeric report bytes.
     * @param path existing evidence file
     * @return lowercase SHA-256 digest
     * @throws Exception if input or mandatory digest support is unavailable
     */
    private static String hash(final Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    /**
     * Rejects inconsistent evidence instead of altering an outcome.
     * @param valid required invariant
     */
    private static void require(final boolean valid) {
        if (!valid) {
            throw new IllegalArgumentException("Reference topology verification failed");
        }
    }
}
