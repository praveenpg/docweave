package dev.docweave.validation;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Complete public OCR text comparison; no upstream line boxes or undeclared logical reading order are invented. */
class PdfInterimScanComparisonTest {
    /** Creates isolated public-capture controls and opt-in paired scoring. */
    PdfInterimScanComparisonTest() {
    }

    /** Failed extraction cannot carry partial successful text; successful text remains in original encounter order. */
    @Test
    void publicCapturesRetainEveryCharacterAndRejectPartialFailures() {
        final var mapper = JsonMapper.builder().build();
        final var elements = List.of(Map.of("type", "Title", "text", "ſ left-"),
                Map.of("type", "NarrativeText", "text", "right 😀"), Map.of("type", "Image", "text", "payload"));
        assertEquals(
                "ſ left- right 😀", publicText(mapper.valueToTree(Map.of("outcome", "SUCCESS", "elements", elements))));
        assertEquals("", publicText(mapper.valueToTree(Map.of("outcome", "RuntimeFailure", "elements", List.of()))));
        assertThrows(IllegalArgumentException.class,
                () -> publicText(mapper.valueToTree(Map.of("outcome", "RuntimeFailure", "elements", elements))));
    }

    /**
     * Scores every source/model pair using unchanged full PAGE transcription and exact shared container identities.
     * Native per-line association remains in the separately retained original report, not compared to paragraph boxes.
     * @throws Exception if model/source/capture identities, reference denominators or fresh output requirements fail
     */
    @Test
    void optionalSharedContainerPublicText() throws Exception {
        final String originals = System.getProperty("docweave.pdf.interim.scans.originals");
        final String nativePath = System.getProperty("docweave.pdf.interim.scans.native.report");
        final String upstream = System.getProperty("docweave.pdf.interim.scans.upstream");
        final String output = System.getProperty("docweave.pdf.interim.scans.report");
        if (originals == null && nativePath == null && upstream == null && output == null) {
            return;
        }
        assertNotNull(originals);
        assertNotNull(nativePath);
        assertNotNull(upstream);
        assertNotNull(output);
        final var mapper = JsonMapper.builder().build();
        final var directory = Path.of("benchmarks/review/pdf-interim-scans");
        final byte[] manifestBytes = Files.readAllBytes(directory.resolve("upstream-manifest.json"));
        final var manifest = mapper.readTree(manifestBytes);
        final byte[] nativeManifestBytes = Files.readAllBytes(directory.resolve("native-manifest.json"));
        assertEquals(manifest.get("native_manifest_sha256").asString(), hash(nativeManifestBytes));
        final var nativeManifest = mapper.readTree(nativeManifestBytes);
        final var nativeReport = mapper.readTree(Files.readAllBytes(Path.of(nativePath)));
        assertEquals(manifest.get("engine_sha256"), nativeReport.get("executable_sha256"));
        final var nativeRows = new LinkedHashMap<String, JsonNode>();
        for (final var row : nativeReport.get("results")) {
            assertNull(nativeRows.put(row.get("id").asString() + "-" + row.get("language").asString(), row));
        }
        assertEquals(manifest.get("cases").size(), nativeRows.size());
        final var runtime = mapper.readTree(Files.readAllBytes(Path.of(upstream, "runtime.json")));
        assertEquals(hash(manifestBytes), runtime.get("manifest_sha256").asString());
        assertEquals("eng", runtime.get("literal_language_mapping").get("deu_latf").asString());
        assertEquals("frk", runtime.get("literal_language_mapping").get("frk").asString());
        final var frames = mapper.readTree(Files.readAllBytes(directory.resolve("frames.json")));
        final var results = new ArrayList<Map<String, Object>>();
        for (final var selected : manifest.get("cases")) {
            final String id = selected.get("id").asString();
            final String sourceId = selected.get("source_id").asString();
            JsonNode source = null;
            for (final var candidate : nativeManifest.get("cases")) {
                if (candidate.get("id").asString().equals(sourceId)) {
                    assertNull(source);
                    source = candidate;
                }
            }
            assertNotNull(source);
            for (final var input : source.get("inputs")) {
                final byte[] bytes = Files.readAllBytes(Path.of(originals, input.get("filename").asString()));
                assertEquals(input.get("bytes").asLong(), bytes.length);
                assertEquals(input.get("sha256").asString(), hash(bytes));
            }
            final byte[] xml = Files.readAllBytes(Path.of(originals, source.get("xml").asString()));
            final var reference = PdfHistoricalTextCorpusTest.scorePublicComparisonText(xml, "");
            assertEquals(source.get("reference_code_points").asInt(), reference.get("reference_code_points"));
            assertEquals(source.get("reference_text_sha256").asString(), reference.get("reference_text_sha256"));
            final var nativeRow = nativeRows.get(id);
            assertNotNull(nativeRow);
            assertEquals(selected.get("sha256"), nativeRow.get("container_pdf_sha256"));
            assertEquals(frames.get(sourceId).get("container_pdf_sha256"), nativeRow.get("container_pdf_sha256"));
            assertEquals(selected.get("model_sha256"), nativeRow.get("model_sha256"));
            assertEquals(selected.get("dpi"), nativeRow.get("container_dpi"));
            assertEquals("AUTO", nativeRow.get("page_segmentation").asString());
            assertEquals("ENGINE_DEFAULT", nativeRow.get("thresholding").asString());
            assertEquals(reference.get("reference_line_order_explicit"),
                    nativeRow.get("reference_line_order_explicit").asBoolean());
            assertEquals(reference.get("reference_code_points"), nativeRow.get("global_reference_code_points").asInt());
            assertEquals(reference.get("reference_text_sha256"), nativeRow.get("reference_text_sha256").asString());
            final var rawPath = Path.of(upstream, id + ".json");
            final var raw = mapper.readTree(Files.readAllBytes(rawPath));
            for (final String identity : List.of("model_sha256", "effective_upstream_language")) {
                assertEquals(selected.get(identity), raw.get(identity));
            }
            assertEquals(selected.get("sha256"), raw.get("source_sha256"));
            final var upstreamMetrics = PdfHistoricalTextCorpusTest.scorePublicComparisonText(xml, publicText(raw));
            final var nativeMetrics = new LinkedHashMap<String, Object>();
            for (final String key : List.of("reference_lines", "reference_text_sha256", "prediction_text_sha256",
                         "reference_line_order_explicit")) {
                nativeMetrics.put(key, mapper.convertValue(nativeRow.get(key), Object.class));
            }
            nativeMetrics.put("reference_code_points", nativeRow.get("global_reference_code_points").asInt());
            nativeMetrics.put("prediction_code_points", nativeRow.get("global_prediction_code_points").asInt());
            nativeMetrics.put("reference_words", nativeRow.get("global_reference_words").asInt());
            final String prefix =
                    nativeRow.get("reference_line_order_explicit").asBoolean() ? "global_" : "xml_sequence_";
            for (final String suffix :
                    List.of("character_edits", "character_error_rate", "word_edits", "word_error_rate")) {
                nativeMetrics.put(prefix + suffix, mapper.convertValue(nativeRow.get(prefix + suffix), Object.class));
            }
            for (final String profile : List.of("DOCWEAVE_CONFIGURED_OCR", "UNSTRUCTURED_CONFIGURED_OCR_ONLY")) {
                final boolean nativeProfile = profile.startsWith("DOCWEAVE");
                final var metrics = new LinkedHashMap<>(nativeProfile ? nativeMetrics : upstreamMetrics);
                metrics.put("id", id);
                metrics.put("source_id", sourceId);
                metrics.put("profile", profile);
                metrics.put("outcome", (nativeProfile ? nativeRow : raw).get("outcome").asString());
                metrics.put("language", selected.get("language").asString());
                metrics.put("model_sha256", selected.get("model_sha256").asString());
                metrics.put("container_pdf_sha256", selected.get("sha256").asString());
                metrics.put("capture_sha256", hash(Files.readAllBytes(nativeProfile ? Path.of(nativePath) : rawPath)));
                if (!nativeProfile) {
                    metrics.put("effective_upstream_language", selected.get("effective_upstream_language").asString());
                    if (raw.has("render_frames")) {
                        metrics.put(
                                "upstream_render_frames", mapper.convertValue(raw.get("render_frames"), Object.class));
                    }
                }
                results.add(metrics);
            }
        }
        assertEquals(16, results.size());
        Files.writeString(Path.of(output),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                        Map.of("runtime", Runtime.version().toString(), "results", results))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Joins all public text in original output order without sorting, word repair or partial-failure reuse.
     * @param capture original complete upstream extraction result
     * @return original text separated only by ASCII spaces
     */
    private static String publicText(final JsonNode capture) {
        if (!capture.get("outcome").asString().equals("SUCCESS") && !capture.get("elements").isEmpty()) {
            throw new IllegalArgumentException("Failed upstream extraction contains partial successful text");
        }
        return capture.get("elements")
                .valueStream()
                .filter(element -> !element.get("type").asString().equals("Image"))
                .map(element -> element.get("text").asString())
                .collect(Collectors.joining(" "));
    }

    /**
     * Computes the byte identity of a local source/reference/capture without altering its contents.
     * @param bytes original bytes
     * @return lowercase SHA256
     * @throws Exception if mandatory JDK hash support is unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
