package dev.docweave.validation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Applies unchanged JVM paragraph scoring to pinned upstream captures and existing complete DocWeave results. */
public final class ScoreScientificNativeComparison {
    /** Prevents utility construction. */
    private ScoreScientificNativeComparison() {
    }

    /**
     * Keeps all source references and existing candidate rows; upstream failures retain every denominator.
     * No extraction, model, OCR, text repair, reference-dependent source selection or Python scoring occurs here.
     * @param args frozen manifest, original cache, upstream captures, existing DocWeave report, fresh output
     * @throws Exception if source/reference identity, capture schema, duplicate keys or fresh output checks fail
     */
    public static void main(final String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--self-test")) {
            controls();
            return;
        }
        if (args.length != 5) {
            throw new IllegalArgumentException("Expected manifest, sources, captures, DocWeave report and NEW output");
        }
        final var mapper = JsonMapper.builder().build();
        final var manifest = mapper.readTree(bounded(Path.of(args[0])));
        final var existing = mapper.readTree(bounded(Path.of(args[3])));
        final var results = new ArrayList<JsonNode>();
        for (final var row : existing.get("results")) {
            results.add(row.deepCopy());
        }
        for (final var selected : manifest.get("cases")) {
            for (final var input : selected.get("inputs")) {
                final byte[] bytes = bounded(Path.of(args[1], input.get("filename").asString()));
                require(bytes.length == input.get("bytes").asLong());
                require(HexFormat.of()
                                .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                                .equals(input.get("sha256").asString()));
            }
            final var references =
                    ScientificReadingEvaluation.references(bounded(Path.of(args[1], selected.get("jats").asString())));
            require(selected.get("reference")
                            .equals(mapper.valueToTree(ScientificReadingEvaluation.identity(references))));
            final String id = selected.get("id").asString();
            require(id.matches("[a-z]+"));
            final var capture = mapper.readTree(bounded(Path.of(args[2], id + ".json")));
            final var outcome = capture.get("outcome");
            require(outcome != null && outcome.isString());
            final var elements = capture.get("elements");
            require(elements != null && elements.isArray());
            final var texts = new ArrayList<String>();
            for (final var element : elements) {
                final var type = element.get("type");
                final var text = element.get("text");
                require(type != null && type.isString() && text != null && text.isString());
                if (!type.asString().equals("Image")) {
                    texts.add(text.asString());
                }
            }
            require(outcome.asString().equals("SUCCESS") || elements.isEmpty());
            final var result = new LinkedHashMap<String, Object>(
                    ScientificReadingEvaluation.score(references, String.join("\n", texts)));
            result.put("id", id);
            result.put("profile", "UNSTRUCTURED_FAST");
            result.put("outcome", outcome.asString());
            result.put(
                    "ordered_reference_sha256", selected.get("reference").get("ordered_reference_sha256").asString());
            results.add(mapper.valueToTree(result));
        }
        // Require the complete declared Cartesian product; no missing or duplicated old/new rows may pass silently.
        final var keys = new java.util.HashSet<String>();
        for (final var result : results) {
            require(keys.add(result.get("id").asString() + "/" + result.get("profile").asString()));
        }
        int expected = 0;
        for (final var selected : manifest.get("cases")) {
            for (final var profile : manifest.get("profiles")) {
                require(keys.contains(selected.get("id").asString() + "/" + profile.asString()));
                expected++;
            }
        }
        require(keys.size() == expected);
        Files.writeString(Path.of(args[4]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                        Map.of("runtime", Runtime.version().toString(), "results", results))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Checks full failure denominators, unchanged existing rows and rejection of inconsistent captures/coverage.
     * All fixture text and source bytes are authored here; no external runtime or source is required.
     * @throws Exception if a scoring/coverage guard regresses or temporary fixture I/O fails
     */
    private static void controls() throws Exception {
        final var root = Files.createTempDirectory("docweave-native-comparison-controls");
        try {
            final var mapper = JsonMapper.builder().build();
            final var xml = ("<article><front><article-meta><abstract><p>Alpha text.</p></abstract></article-meta>"
                             + "</front><body><sec><p>Beta text.</p></sec></body></article>")
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            final var pdf = "owned identity bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(root.resolve("owned.xml"), xml);
            Files.write(root.resolve("owned.pdf"), pdf);
            final var inputs = new ArrayList<Map<String, Object>>();
            for (final String filename : List.of("owned.xml", "owned.pdf")) {
                final var bytes = Files.readAllBytes(root.resolve(filename));
                inputs.add(Map.of("filename", filename, "bytes", bytes.length, "sha256",
                        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))));
            }
            final var references = ScientificReadingEvaluation.references(xml);
            final var reference = ScientificReadingEvaluation.identity(references);
            final var selected = Map.of(
                    "id", "owned", "jats", "owned.xml", "pdf", "owned.pdf", "inputs", inputs, "reference", reference);
            final var manifest = Map.of("cases", List.of(selected), "profiles", List.of("LINES", "UNSTRUCTURED_FAST"));
            final var old = new LinkedHashMap<String, Object>(
                    ScientificReadingEvaluation.score(references, "Alpha text. Beta text."));
            old.putAll(Map.of("id", "owned", "profile", "LINES", "outcome", "SUCCESS", "ordered_reference_sha256",
                    reference.get("ordered_reference_sha256")));
            final var oldReport = root.resolve("previous.json");
            Files.writeString(oldReport, mapper.writeValueAsString(Map.of("results", List.of(old))));
            Files.writeString(root.resolve("manifest.json"), mapper.writeValueAsString(manifest));
            Files.writeString(root.resolve("owned.json"),
                    mapper.writeValueAsString(Map.of("outcome", "RuntimeFailure", "elements", List.of())));
            final String[] args = {root.resolve("manifest.json").toString(), root.toString(), root.toString(),
                    oldReport.toString(), root.resolve("valid.json").toString()};
            main(args);
            final var results = mapper.readTree(Files.readAllBytes(root.resolve("valid.json"))).get("results");
            require(results.size() == 2 && results.get(0).equals(mapper.valueToTree(old)));
            final var failed = results.get(1);
            require(failed.get("reference_paragraphs").asInt() == 2 && failed.get("missing_paragraphs").asInt() == 2
                    && failed.get("reference_order_pairs").asInt() == 1
                    && failed.get("unresolved_order_pairs").asInt() == 1
                    && failed.get("preserved_order_pairs").asInt() == 0);
            args[4] = root.resolve("invalid.json").toString();
            for (final var rows : List.of(List.of(old, old), List.<Map<String, Object>>of())) {
                Files.writeString(oldReport, mapper.writeValueAsString(Map.of("results", rows)));
                expectRejected(args);
            }
            Files.writeString(oldReport, mapper.writeValueAsString(Map.of("results", List.of(old))));
            Files.writeString(root.resolve("owned.json"),
                    mapper.writeValueAsString(Map.of("outcome", "RuntimeFailure", "elements",
                            List.of(Map.of("type", "NarrativeText", "text", "Alpha text. Beta text.")))));
            expectRejected(args);
            Files.writeString(root.resolve("owned.json"),
                    mapper.writeValueAsString(Map.of("outcome", "SUCCESS", "elements", List.of())));
            Files.writeString(root.resolve("owned.pdf"), "changed identity");
            expectRejected(args);
        } finally {
            try (final var paths = Files.walk(root)) {
                for (final var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    /**
     * Requires inconsistent source/capture/coverage input to fail without writing a usable result.
     * @param args owned temporary scorer inputs and fresh output
     * @throws Exception if the invariant is silently accepted or fixture I/O fails
     */
    private static void expectRejected(final String[] args) throws Exception {
        try {
            main(args);
        } catch (final IllegalArgumentException expected) {
            require(!Files.exists(Path.of(args[4])));
            return;
        }
        throw new AssertionError("Inconsistent scientific native comparison was accepted");
    }

    /**
     * Bounds every external source, manifest, capture and report before allocating its bytes.
     * @param path selected local file
     * @return original bytes
     * @throws Exception if input is missing, empty, over16MiB or unreadable
     */
    private static byte[] bounded(final Path path) throws Exception {
        require(Files.size(path) > 0 && Files.size(path) <= 16 * 1024 * 1024);
        return Files.readAllBytes(path);
    }

    /**
     * Rejects schema/identity/coverage failures without changing reference text or printing it.
     * @param valid whether the full invariant holds
     */
    private static void require(final boolean valid) {
        if (!valid) {
            throw new IllegalArgumentException("Scientific native comparison invariant failed");
        }
    }
}
