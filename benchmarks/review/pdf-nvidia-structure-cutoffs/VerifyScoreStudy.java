import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Verifies every captured page/profile, default-control equality and unchanged strict replay populations. */
public final class VerifyScoreStudy {
    /** Prevents utility construction. */
    private VerifyScoreStudy() {
    }

    /**
     * Checks frozen identities and invokes the existing JVM population/region verifier for both cutoffs.
     * @param args study root, original captures, control captures, lower-cutoff captures, verifier classes, fresh
     *         receipt
     * @throws Exception if any identity, control, numeric population or fresh output requirement fails
     */
    public static void main(final String[] args) throws Exception {
        require(args.length == 6 && !Files.exists(Path.of(args[5])));
        final var mapper = JsonMapper.builder().build();
        final var root = Path.of(args[0]);
        final var original = Path.of(args[1]);
        final var manifestPath = root.resolve("manifest.json");
        final var manifest = mapper.readTree(Files.readAllBytes(manifestPath));
        require(hash(Path.of(manifest.get("baseline_manifest").asString()))
                        .equals(manifest.get("baseline_manifest_sha256").asString()));
        for (final var entry : manifest.get("tool_sha256").properties()) {
            require(hash(Path.of(entry.getKey())).equals(entry.getValue().asString()));
        }
        require(manifest.get("pages").size() == 11 && manifest.get("profiles").size() == 2
                && manifest.get("profiles").get(0).asString().equals("tatr")
                && manifest.get("profiles").get(1).asString().equals("yolox")
                && manifest.get("minimum_scores").size() == 2 && manifest.get("minimum_scores").get(0).asDouble() == .5
                && manifest.get("minimum_scores").get(1).asDouble() == .25);
        final var oldReplay = mapper.readTree(
                Files.readAllBytes(Path.of("benchmarks/review/pdf-nvidia-proposal-structures/replay-manifest.json")));
        final var records = new ArrayList<Map<String, Object>>();
        int unchanged = 0;
        for (int policy = 0; policy < 2; policy++) {
            final String id = policy == 0 ? "050" : "025";
            final double score = policy == 0 ? .5 : .25;
            final var captures = Path.of(args[policy + 2]);
            final var child = root.resolve("score-" + id);
            final var replay = mapper.readTree(Files.readAllBytes(child.resolve("replay-manifest.json")));
            final var protocol = mapper.readTree(Files.readAllBytes(child.resolve("manifest.json")));
            require(protocol.get("study_manifest_sha256").asString().equals(hash(manifestPath))
                    && protocol.get("minimum_score").asDouble() == score && replay.get("pages").size() == 11);
            require(Files.readAllLines(captures.resolve("policy.tsv"))
                            .equals(List.of("structure_sha256\tminimum_score\tmerge_conflicts",
                                    manifest.get("structure_sha256").asString() + "\t" + score
                                            + "\tKEEP_HIGHER_SCORE")));
            for (int page = 0; page < 11; page++) {
                final var captured = replay.get("pages").get(page);
                require(captured.get("source_page").asInt() == page + 1);
                final String name = "original-page-%04d".formatted(page + 1);
                require(captured.get("capture_id").asString().equals(name));
                for (final String profile : List.of("tatr", "yolox")) {
                    final var current = captures.resolve(name).resolve(profile);
                    final var baseline = original.resolve(name).resolve(profile);
                    for (final String file : List.of("tables.tsv", "anchors.tsv", "outcome.txt")) {
                        require(hash(current.resolve(file)).equals(captured.get(profile).get(file).asString()));
                        if (policy == 0) {
                            require(hash(baseline.resolve(file))
                                            .equals(oldReplay.get("pages").get(page).get(profile).get(file).asString())
                                    && Arrays.equals(Files.readAllBytes(current.resolve(file)),
                                            Files.readAllBytes(baseline.resolve(file))));
                        }
                    }
                    for (final String file : List.of("crops.tsv", "proposals.sha256")) {
                        require(Arrays.equals(
                                Files.readAllBytes(current.resolve(file)), Files.readAllBytes(baseline.resolve(file))));
                    }
                    if (policy == 0) {
                        unchanged++;
                    }
                    final var rows = Files.readAllLines(current.resolve("tables.tsv"));
                    int totalRows = 0;
                    for (final String row : rows.subList(1, rows.size())) {
                        final var fields = row.split("\t");
                        require(fields.length == 8);
                        totalRows += Integer.parseInt(fields[2]);
                    }
                    records.add(Map.of("score", score, "source_page", page + 1, "profile", profile, "outcome",
                            Files.readString(current.resolve("outcome.txt")).strip(), "tables", rows.size() - 1,
                            "model_rows", totalRows));
                }
            }
            // Reuse the unchanged complete-case/region arithmetic; no second JDK is required.
            VerifyReplay.main(new String[] {child.toString(), args[4], args[5] + "." + id + ".json"});
        }
        final var structure = mapper.readTree(Files.readAllBytes(root.resolve("structure-java21.json")));
        require(structure.get("runtime").asString().startsWith("21.")
                && structure.get("default_control_observations_exactly_equal").asInt() == unchanged
                && structure.get("all_proposal_and_crop_hashes_equal").asBoolean()
                && structure.get("results").equals(mapper.valueToTree(records)));
        final var previous = mapper.readTree(Files.readAllBytes(
                Path.of("benchmarks/review/pdf-nvidia-proposal-structures/native-replay-java21.json")));
        final var control = mapper.readTree(Files.readAllBytes(root.resolve("score-050/native-replay-java21.json")));
        require(previous.get("results").equals(control.get("results"))
                && previous.get("documents").equals(control.get("documents")));
        Files.writeString(Path.of(args[5]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("manifest_sha256", hash(manifestPath),
                        "captured_page_profile_cutoffs", records.size(), "default_control_observations_exactly_equal",
                        unchanged, "all_proposal_and_crop_hashes_equal", true,
                        "default_native_replay_observations_equal", true, "complete_populations_retained", true))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Hashes bounded numeric evidence without including source text or paths in receipts.
     * @param path local numeric evidence
     * @return lowercase SHA256
     * @throws Exception if bounded input or digest access fails
     */
    private static String hash(final Path path) throws Exception {
        require(Files.size(path) <= 8 * 1024 * 1024);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    /**
     * Rejects inconsistent evidence without rewriting populations or observations.
     * @param valid required invariant
     */
    private static void require(final boolean valid) {
        if (!valid) {
            throw new IllegalArgumentException("Structure score study verification failed");
        }
    }
}
