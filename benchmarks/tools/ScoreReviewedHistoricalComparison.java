package io.docweave.validation;

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
import java.util.Set;

/** JVM-only paired scoring of separately reviewed historical order using unchanged native and upstream captures. */
public final class ScoreReviewedHistoricalComparison {
    /** Prevents standalone paired evaluator construction. */
    private ScoreReviewedHistoricalComparison() {
    }

    /**
     * Retains both pinned models and every library outcome for the complete50-line reviewed original page.
     * @param args reviewed manifest, original source cache, native reviewed report, upstream cache and fresh report
     * @throws Exception if original captures, source/model bindings, reviewed evidence or complete populations fail
     */
    public static void main(final String[] args) throws Exception {
        require(args.length == 5 && !Files.exists(Path.of(args[4])), "Expected reviewed inputs and fresh report");
        final var mapper = JsonMapper.builder().build();
        final var manifestPath = Path.of(args[0]);
        final byte[] manifestBytes = read(manifestPath, 2 * 1024 * 1024);
        final var manifest = mapper.readTree(manifestBytes);
        final var selection = mapper.readTree(read(manifestPath.resolveSibling("selection.json"), 65536));
        final var sharedPath = Path.of("benchmarks/review/pdf-interim-scans/upstream-manifest.json");
        final byte[] sharedBytes = read(sharedPath, 2 * 1024 * 1024);
        require(hash(sharedBytes).equals(selection.get("paired_upstream_manifest_sha256").asString()),
                "Historical paired protocol changed");
        final var shared = mapper.readTree(sharedBytes);
        final var runtime = mapper.readTree(read(Path.of(args[3], "runtime.json"), 2 * 1024 * 1024));
        require(runtime.get("manifest_sha256").asString().equals(hash(sharedBytes))
                        && runtime.get("packages")
                                .get("unstructured")
                                .asString()
                                .equals(shared.get("unstructured_version").asString()),
                "Historical upstream execution identity changed");
        final byte[] nativeBytes = read(Path.of(args[2]), 2 * 1024 * 1024);
        final var nativeReport = mapper.readTree(nativeBytes);
        require(nativeReport.get("executable_sha256").equals(shared.get("engine_sha256")),
                "Native executable differs from paired source");
        require(manifest.get("cases").size() == 1 && manifest.get("profiles").size() == 2
                        && nativeReport.get("results").size() == 2,
                "Complete reviewed source/model population changed");
        final var source = manifest.get("cases").get(0);
        require(source.get("id").asString().equals("bebel_frau_1879") && source.get("expected_lines").asInt() == 50,
                "Predeclared reviewed source changed");
        for (final var pin : source.get("inputs")) {
            final byte[] bytes = read(Path.of(args[1], pin.get("filename").asString()), 128 * 1024 * 1024);
            require(bytes.length == pin.get("bytes").asLong() && hash(bytes).equals(pin.get("sha256").asString()),
                    "Original raster/reference changed");
        }
        final byte[] xml = read(Path.of(args[1], source.get("xml").asString()), 2 * 1024 * 1024);
        final String imageHash = hash(read(Path.of(args[1], source.get("image").asString()), 128 * 1024 * 1024));
        final var binding = source.get("reviewed_order");
        require(binding.get("sha256").equals(selection.get("reviewed_order_sha256")), "Reviewed source order changed");
        final var nativeRows = new LinkedHashMap<String, JsonNode>();
        for (final var row : nativeReport.get("results")) {
            require(row.get("id").equals(source.get("id"))
                            && nativeRows.put(row.get("language").asString(), row) == null,
                    "Unexpected or duplicate native model observation");
        }
        require(nativeRows.keySet().equals(Set.of("deu", "deu_latf")), "Pinned language population changed");
        final var results = new ArrayList<Map<String, Object>>();
        for (final String language : List.of("deu", "deu_latf")) {
            final var matching = new ArrayList<JsonNode>();
            for (final var candidate : shared.get("cases")) {
                if (candidate.get("source_id").equals(source.get("id"))
                        && candidate.get("language").asString().equals(language)) {
                    matching.add(candidate);
                }
            }
            require(matching.size() == 1, "Ambiguous historical upstream model binding");
            final var pin = matching.getFirst();
            final var nativeRow = nativeRows.get(language);
            require(nativeRow.get("model_sha256").equals(pin.get("model_sha256"))
                            && nativeRow.get("container_pdf_sha256").equals(pin.get("sha256"))
                            && nativeRow.get("container_dpi").equals(pin.get("dpi"))
                            && nativeRow.get("page_segmentation").asString().equals("AUTO")
                            && nativeRow.get("thresholding").asString().equals("ENGINE_DEFAULT")
                            && !nativeRow.get("reference_line_order_explicit").asBoolean()
                            && nativeRow.get("source_render_rgb_equal").asBoolean()
                            && nativeRow.get("reviewed_order_evidence_sha256").equals(binding.get("sha256")),
                    "Native model/frame/reviewed policy changed");
            final byte[] upstreamBytes = read(Path.of(args[3], pin.get("id").asString() + ".json"), 2 * 1024 * 1024);
            final var upstream = mapper.readTree(upstreamBytes);
            for (final String field : List.of("model_sha256", "effective_upstream_language")) {
                require(upstream.get(field).equals(pin.get(field)), "Upstream model/language changed");
            }
            require(upstream.get("source_sha256").equals(pin.get("sha256")), "Upstream container changed");
            final String output = publicText(upstream);
            final var published = PdfHistoricalTextCorpusTest.scorePublicComparisonText(xml, output);
            final var reviewed =
                    PdfHistoricalTextCorpusTest.scoreReviewedPublicComparisonText(xml, imageHash, binding, output);
            require(published.get("reference_text_sha256").equals(source.get("reference_text_sha256").asString())
                            && ((Number) published.get("reference_code_points")).intValue() == 3049
                            && ((Number) reviewed.get("reviewed_order_reference_code_points")).intValue() == 3049
                            && reviewed.get("reviewed_order_reference_text_sha256")
                                    .equals(nativeRow.get("reviewed_order_reference_text_sha256").asString()),
                    "Complete reference content differs between libraries");
            for (final boolean nativeProfile : List.of(true, false)) {
                final var result = new LinkedHashMap<String, Object>();
                result.put("profile", nativeProfile ? "DOCWEAVE_CONFIGURED_OCR" : "UNSTRUCTURED_CONFIGURED_OCR_ONLY");
                result.put("language", language);
                result.put("outcome", (nativeProfile ? nativeRow : upstream).get("outcome").asString());
                result.put("published_xml_line_order_explicit", false);
                result.put("reviewed_order_reference_line_order_explicit", false);
                result.put("published_xml_character_edits",
                        nativeProfile ? nativeRow.get("xml_sequence_character_edits").asInt()
                                      : published.get("xml_sequence_character_edits"));
                if (nativeProfile) {
                    nativeRow.properties().forEach(entry -> {
                        if (entry.getKey().startsWith("reviewed_order_")) {
                            result.put(entry.getKey(), mapper.convertValue(entry.getValue(), Object.class));
                        }
                    });
                    result.put("prediction_text_sha256", nativeRow.get("prediction_text_sha256").asString());
                } else {
                    result.putAll(reviewed);
                    result.put("prediction_text_sha256", published.get("prediction_text_sha256"));
                }
                result.put("capture_sha256", hash(nativeProfile ? nativeBytes : upstreamBytes));
                result.put("container_pdf_sha256", pin.get("sha256").asString());
                result.put("model_sha256", pin.get("model_sha256").asString());
                results.add(result);
            }
        }
        require(results.size() == 4, "Lost a library/model outcome");
        Files.writeString(Path.of(args[4]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("scope",
                        "Complete one-page/two-model reviewed-order comparison; cached upstream0.27.28 outputs unchanged; no extraction change, aggregate winner or held-out accuracy claim.",
                        "reviewed_manifest_sha256", hash(manifestBytes), "historical_paired_manifest_sha256",
                        hash(sharedBytes), "reviewed_order_evidence_sha256", binding.get("sha256").asString(),
                        "java_runtime", System.getProperty("java.runtime.version"), "results", results))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Retains complete successful public text in original order; failed partial captures cannot supply text.
     * @param capture original pinned upstream public capture
     * @return space-separated original text, excluding source image elements
     */
    private static String publicText(final JsonNode capture) {
        if (!capture.get("outcome").asString().equals("SUCCESS")) {
            require(capture.get("elements").isEmpty(), "Failed capture contains partial text");
            return "";
        }
        return capture.get("elements")
                .valueStream()
                .filter(element -> !element.get("type").asString().equals("Image"))
                .map(element -> element.get("text").asString())
                .collect(java.util.stream.Collectors.joining(" "));
    }

    /**
     * Reads only explicitly supplied bounded fixture bytes.
     * @param path exact existing local file
     * @param maximum maximum allowed bytes before allocation
     * @return complete original bytes
     * @throws Exception if size or reading fails
     */
    private static byte[] read(final Path path, final int maximum) throws Exception {
        require(Files.size(path) <= maximum, "Paired evidence exceeds file cap");
        return Files.readAllBytes(path);
    }

    /**
     * Computes the exact original source/capture/reference identity.
     * @param bytes complete bounded bytes
     * @return lowercase SHA256
     * @throws Exception if the standard digest is unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Rejects changed or incomplete paired evidence rather than dropping a source/model outcome.
     * @param condition required invariant
     * @param message bounded failure explanation
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
