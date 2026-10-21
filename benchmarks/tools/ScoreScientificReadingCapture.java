package dev.docweave.validation;

import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** JVM-only paragraph/order scoring of the pinned actual upstream fast pipeline on complete original PDFs. */
public final class ScoreScientificReadingCapture {
    /** Prevents standalone utility construction. */
    private ScoreScientificReadingCapture() {
    }

    /**
     * Hashes bounded original source, protocol or capture bytes without modifying them.
     * @param path existing pinned resource
     * @return lowercase SHA256
     * @throws Exception if input bounds or digest access fails
     */
    private static String hash(final Path path) throws Exception {
        require(Files.size(path) <= 16 * 1024 * 1024, "Evidence exceeds16MiB");
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    /**
     * Rejects evidence drift instead of silently changing the paragraph population or upstream pipeline.
     * @param valid required invariant
     * @param reason content-free diagnostic
     */
    private static void require(final boolean valid, final String reason) {
        if (!valid) {
            throw new IllegalArgumentException(reason);
        }
    }

    /**
     * Scores all declared sources using the same unchanged reference/text-order helper as DocWeave.
     * Missing/failed captures retain every declared paragraph and order pair with empty candidate text.
     * @param args capture manifest, original source directory, raw capture directory and fresh numeric report
     * @throws Exception if runtime, source, reference identity, bounded representation or report writing fails
     */
    public static void main(final String[] args) throws Exception {
        require(args.length == 4, "Expected manifest, sources, raw captures and fresh report");
        final var mapper = JsonMapper.builder().build();
        final var manifestPath = Path.of(args[0]);
        hash(manifestPath);
        final var manifest = mapper.readTree(Files.readAllBytes(manifestPath));
        require(manifest.get("unstructured_version").asString().equals("0.27.28"), "Upstream pin differs");
        require(manifest.get("upstream_profile")
                        .equals(mapper.valueToTree(Map.of("strategy", "fast", "infer_table_structure", false,
                                "languages", java.util.List.of("eng"), "include_page_breaks", false))),
                "Upstream native profile differs");
        require(hash(Path.of(manifest.get("capture_runner").asString()))
                        .equals(manifest.get("capture_runner_sha256").asString()),
                "Executed capture runner differs");
        final boolean completeBody = manifest.has("reference_selection");
        if (completeBody) {
            require(manifest.get("reference_selection").asString().equals("ABSTRACT_AND_ALL_BODY_SECTIONS"),
                    "Unsupported reference selection");
        }
        final var sourceRoot = Path.of(args[1]);
        final var captureRoot = Path.of(args[2]);
        final var runtimePath = captureRoot.resolve("runtime.json");
        hash(runtimePath);
        final var runtime = mapper.readTree(Files.readAllBytes(runtimePath));
        require(runtime.get("packages").get("unstructured").asString().equals("0.27.28"),
                "Actual upstream runtime differs");
        final var observations = new ArrayList<Map<String, Object>>();
        for (final var selected : manifest.get("cases")) {
            final String id = selected.get("id").asString();
            require(id.matches("[a-z]+"), "Invalid original source identifier");
            for (final var input : selected.get("inputs")) {
                final var path = sourceRoot.resolve(input.get("filename").asString());
                require(Files.size(path) == input.get("bytes").asLong()
                                && hash(path).equals(input.get("sha256").asString()),
                        "Original source differs");
            }
            final var references = ScientificReadingEvaluation.references(
                    Files.readAllBytes(sourceRoot.resolve(selected.get("jats").asString())), completeBody);
            require(selected.get("reference")
                            .equals(mapper.valueToTree(ScientificReadingEvaluation.identity(references))),
                    "Complete independent references differ");
            final var capturePath = captureRoot.resolve(id + ".json");
            String outcome = "UPSTREAM_CAPTURE_MISSING";
            String output = "";
            String captureHash = "MISSING";
            if (Files.exists(capturePath)) {
                captureHash = hash(capturePath);
                final var capture = mapper.readTree(Files.readAllBytes(capturePath));
                require(capture.get("outcome").isString() && capture.get("elements").isArray(),
                        "Malformed raw capture");
                outcome = capture.get("outcome").asString();
                require(outcome.equals("SUCCESS") || capture.get("elements").isEmpty(), "Failed partial capture");
                final var text = new ArrayList<String>();
                for (final var element : capture.get("elements")) {
                    require(element.get("type").isString() && element.get("text").isString(),
                            "Malformed public element");
                    if (!element.get("type").asString().equals("Image")) {
                        text.add(element.get("text").asString());
                    }
                }
                output = text.stream().collect(Collectors.joining("\n"));
            }
            final var result = new LinkedHashMap<String, Object>(ScientificReadingEvaluation.score(references, output));
            result.put("id", id);
            result.put("profile", "UNSTRUCTURED_FAST");
            result.put("outcome", outcome);
            result.put("capture_sha256", captureHash);
            result.put(
                    "ordered_reference_sha256", selected.get("reference").get("ordered_reference_sha256").asString());
            observations.add(result);
        }
        Files.writeString(Path.of(args[3]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                        Map.of("manifest_sha256", hash(manifestPath), "runtime_sha256", hash(runtimePath),
                                "java_version", System.getProperty("java.version"), "results", observations))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }
}
