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
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Default behavior and source-raster agreement, separate from configured OCR/table and semantic-figure quality. */
class PdfDefaultComparisonTest {
    /** Creates isolated source-raster and whole-output controls. */
    PdfDefaultComparisonTest() {
    }

    /** Agreement requires page/dimensions and duplicate multiplicity, not merely the same number of images. */
    @Test
    void rasterAgreementRetainsDimensionsPagesAndRepeatedPlacements() {
        final var mapper = JsonMapper.builder().build();
        final var nativeImage = Map.of("type", "Image", "text", "", "metadata",
                Map.of("page_number", 1, "image_width", 10, "image_height", 20));
        final var poppler = mapper.valueToTree(List.of(Map.of("kind", "image", "page", 1, "width", 10, "height", 20),
                Map.of("kind", "smask", "page", 1, "width", 10, "height", 20)));
        final var source = rasterReferences(poppler);
        assertEquals(source, rasterPredictions(mapper.valueToTree(List.of(nativeImage))));
        assertNotEquals(source, rasterPredictions(mapper.valueToTree(List.of(nativeImage, nativeImage))));
        assertNotEquals(
                source, rasterPredictions(mapper.valueToTree(List.of(Map.of("type", "Image", "text", "", "metadata",
                                Map.of("page_number", 2, "image_width", 10, "image_height", 20))))));
        assertNotEquals(
                source, rasterPredictions(mapper.valueToTree(List.of(Map.of("type", "Image", "text", "", "metadata",
                                Map.of("page_number", 1, "image_width", 20, "image_height", 10))))));
    }

    /** Failure cannot be summarized as partial successful text; all original text categories stay visible. */
    @Test
    void defaultSummaryPreservesCompleteOutputAndFailures() {
        final var mapper = JsonMapper.builder().build();
        final var capture = mapper.valueToTree(Map.of("outcome", "SUCCESS", "elements",
                List.of(Map.of("type", "Title", "text", "A- B"), Map.of("type", "NarrativeText", "text", "ſ 😀"))));
        final var result = summary(capture);
        assertEquals(2, result.get("elements"));
        assertEquals(8, result.get("text_code_points"));
        assertEquals(Map.of("Title", 1, "NarrativeText", 1), result.get("element_types"));
        assertThrows(IllegalArgumentException.class,
                ()
                        -> summary(mapper.valueToTree(
                                Map.of("outcome", "RuntimeFailure", "elements", capture.get("elements")))));
    }

    /**
     * Scores all default captures and independently records ordinary source-raster extractor agreement.
     * No configured OCR/language/model/strategy choices are attributed to these default API calls.
     * @throws Exception if whole-source identities, source listings, complete outcomes or fresh reports fail
     */
    @Test
    void optionalDefaultAndRasterContracts() throws Exception {
        final String nativeRoot = System.getProperty("docweave.pdf.interim.defaults.native");
        final String upstream = System.getProperty("docweave.pdf.interim.defaults.upstream");
        final String originals = System.getProperty("docweave.pdf.interim.defaults.originals");
        final String output = System.getProperty("docweave.pdf.interim.defaults.report");
        if (nativeRoot == null && upstream == null && originals == null && output == null) {
            return;
        }
        assertNotNull(nativeRoot);
        assertNotNull(upstream);
        assertNotNull(originals);
        assertNotNull(output);
        final var mapper = JsonMapper.builder().build();
        final var directory = Path.of("benchmarks/review/pdf-interim-defaults");
        final byte[] manifestBytes = Files.readAllBytes(directory.resolve("manifest.json"));
        final var manifest = mapper.readTree(manifestBytes);
        final var nativeRuntime = mapper.readTree(Files.readAllBytes(Path.of(nativeRoot, "runtime.json")));
        final var upstreamRuntime = mapper.readTree(Files.readAllBytes(Path.of(upstream, "runtime.json")));
        assertEquals(hash(manifestBytes), nativeRuntime.get("manifest_sha256").asString());
        assertEquals(hash(manifestBytes), upstreamRuntime.get("manifest_sha256").asString());
        final var results = new ArrayList<Map<String, Object>>();
        for (final var selected : manifest.get("cases")) {
            final String id = selected.get("id").asString();
            final byte[] bytes = Files.readAllBytes(Path.of(originals, selected.get("pdf").asString()));
            assertEquals(selected.get("bytes").asLong(), bytes.length);
            assertEquals(selected.get("sha256").asString(), hash(bytes));
            final var nativePath = Path.of(nativeRoot, id + ".json");
            final var nativeCapture = mapper.readTree(Files.readAllBytes(nativePath));
            final var upstreamPath = Path.of(upstream, id + ".json");
            final var upstreamCapture = mapper.readTree(Files.readAllBytes(upstreamPath));
            final var imageReference = mapper.readTree(Files.readAllBytes(Path.of(nativeRoot, id + "-images.json")));
            assertEquals(imageReference.get("listing_sha256").asString(),
                    hash(Files.readAllBytes(Path.of(nativeRoot, id + "-pdfimages.txt"))));
            final var sourceRasters = rasterReferences(imageReference.get("images"));
            final var observedRasters = rasterPredictions(nativeCapture.get("elements"));
            for (final String profile : List.of("DOCWEAVE_DEFAULT_NATIVE", "UNSTRUCTURED_DEFAULT_AUTO")) {
                final boolean nativeProfile = profile.startsWith("DOCWEAVE");
                final var capture = nativeProfile ? nativeCapture : upstreamCapture;
                assertEquals(selected.get("sha256"), capture.get("source_sha256"));
                final var result = summary(capture);
                result.put("id", id);
                result.put("profile", profile);
                result.put("source_sha256", selected.get("sha256").asString());
                result.put("capture_sha256", hash(Files.readAllBytes(nativeProfile ? nativePath : upstreamPath)));
                if (nativeProfile) {
                    result.put("source_ordinary_raster_placements",
                            sourceRasters.values().stream().mapToInt(Integer::intValue).sum());
                    result.put("source_mask_entries",
                            imageReference.get("images")
                                    .valueStream()
                                    .filter(image -> !image.get("kind").asString().equals("image"))
                                    .count());
                    result.put("native_raster_placements",
                            observedRasters.values().stream().mapToInt(Integer::intValue).sum());
                    result.put("source_page_dimension_multiplicity_agreement", sourceRasters.equals(observedRasters));
                    result.put("source_raster_bag", sourceRasters);
                    result.put("native_raster_bag", observedRasters);
                    result.put("warning_codes", mapper.convertValue(capture.get("warnings"), Object.class));
                }
                results.add(result);
            }
        }
        assertEquals(18, results.size());
        Files.writeString(Path.of(output),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                        Map.of("runtime", Runtime.version().toString(), "results", results))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Counts only ordinary source images by original page and source dimensions; masks remain separate observations.
     * @param images original independent Poppler listing rows
     * @return exact placement-multiplicity bag, not semantic figure annotations
     */
    private static Map<String, Integer> rasterReferences(final JsonNode images) {
        final var result = new TreeMap<String, Integer>();
        for (final var image : images) {
            if (image.get("kind").asString().equals("image")) {
                result.merge(key(image.get("page").asInt(), image.get("width").asInt(), image.get("height").asInt()), 1,
                        Integer::sum);
            }
        }
        return result;
    }

    /**
     * Retains every original native Image occurrence independently of repeated element IDs or image resources.
     * @param elements complete default native result
     * @return exact source-page/source-dimension multiplicity bag
     */
    private static Map<String, Integer> rasterPredictions(final JsonNode elements) {
        final var result = new TreeMap<String, Integer>();
        for (final var element : elements) {
            if (element.get("type").asString().equals("Image")) {
                final var metadata = element.get("metadata");
                result.merge(key(metadata.get("page_number").asInt(), metadata.get("image_width").asInt(),
                                     metadata.get("image_height").asInt()),
                        1, Integer::sum);
            }
        }
        return result;
    }

    /**
     * Summarizes behavior without text normalization, category repair or partial-failure reuse.
     * @param capture complete raw extraction result
     * @return numeric/hash-only outcome, categories and complete public text identity
     */
    private static Map<String, Object> summary(final JsonNode capture) {
        final var elements = capture.get("elements");
        if (!capture.get("outcome").asString().equals("SUCCESS") && !elements.isEmpty()) {
            throw new IllegalArgumentException("Failed default capture contains partial successful output");
        }
        final var types = new TreeMap<String, Integer>();
        for (final var element : elements) {
            types.merge(element.get("type").asString(), 1, Integer::sum);
        }
        final String text = elements.valueStream()
                                    .filter(element -> !element.get("type").asString().equals("Image"))
                                    .map(element -> element.get("text").asString())
                                    .collect(Collectors.joining("\n"));
        final var result = new LinkedHashMap<String, Object>();
        result.put("outcome", capture.get("outcome").asString());
        result.put("elements", elements.size());
        result.put("element_types", types);
        result.put("text_code_points", text.codePointCount(0, text.length()));
        try {
            result.put("public_text_sha256", hash(text.getBytes(StandardCharsets.UTF_8)));
        } catch (final Exception impossible) {
            throw new IllegalStateException("Mandatory SHA256 unavailable", impossible);
        }
        return result;
    }

    /**
     * Encodes an exact page/dimension key without treating dimensions alone as placement identity.
     * @param page one-based original page
     * @param width source raster width
     * @param height source raster height
     * @return stable opaque placement-class key
     */
    private static String key(final int page, final int width, final int height) {
        if (page < 1 || width < 1 || height < 1) {
            throw new IllegalArgumentException("Source raster dimensions/page are invalid");
        }
        return page + ":" + width + "x" + height;
    }

    /**
     * Computes an original byte identity without changing source data.
     * @param bytes original bytes
     * @return lowercase SHA256
     * @throws Exception if mandatory JDK hash support is unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
