package dev.docweave.validation;

import dev.docweave.DocWeave;
import dev.docweave.ocr.TesseractOcr;
import dev.docweave.ocr.TesseractOptions;
import dev.docweave.ocr.TesseractThresholding;
import dev.docweave.partition.DocumentSource;
import dev.docweave.partition.PartitionException;
import dev.docweave.partition.PartitionerRegistry;
import dev.docweave.partition.PdfOcrOptions;
import dev.docweave.partition.PdfOcrProvider;
import dev.docweave.partition.PdfOptions;
import dev.docweave.partition.PdfPartitioner;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.jupiter.api.Assertions.*;

/** Optional whole-scan historical text evaluation against independent, immutable PAGE transcriptions. */
class PdfHistoricalTextCorpusTest {
    /** Fixed native-raster ceiling, also applied before decoding source images. */
    private static final long MAX_PIXELS = 16_000_000;
    /** Bounded diagnostic dynamic-programming work; never truncates a reference or prediction. */
    private static final long MAX_EDIT_WORK = 50_000_000;
    /** PAGE custom line order syntax; text-style annotations do not supply reading order. */
    private static final Pattern ORDER = Pattern.compile("readingOrder\\s*\\{\\s*index\\s*:\\s*(\\d+)\\s*;");

    /**
     * One positive original-pixel envelope.
     * @param left lower horizontal coordinate
     * @param top lower vertical coordinate
     * @param right upper horizontal coordinate
     * @param bottom upper vertical coordinate
     */
    private record Box(double left, double top, double right, double bottom) {}

    /**
     * One ordered source or prediction line; reference text is never supplied to extraction.
     * @param id stable reference identity or prediction encounter index
     * @param text NFC/ASCII-whitespace normalized text, without orthographic repair
     * @param box original-pixel envelope
     */
    private record Line(String id, String text, Box box) {}

    /**
     * One complete independently labelled source page.
     * @param width original image width
     * @param height original image height
     * @param lines complete unique transcribed lines in declared reading order
     * @param declaredLineOrder whether every multiline region supplies explicit line ordering
     */
    private record Reference(int width, int height, List<Line> lines, boolean declaredLineOrder) {}

    /**
     * Separately reviewed source order; original XML order and line objects remain unchanged.
     * @param lines complete permutation of original immutable source lines
     * @param evidenceSha256 exact separately frozen source-image review
     */
    private record ReviewedOrder(List<Line> lines, String evidenceSha256) {}

    /**
     * One explicitly selected ordered language/model policy, independent of source reference text.
     * @param policy original fixed segmentation/threshold profile
     * @param languages immutable ordered engine identifiers
     * @param models immutable ordered component identities; one component retains historical report fields
     * @param identity SHA256 of ordered language/binary-hash descriptors, not a single traineddata hash
     */
    private record LanguageProfile(
            JsonNode policy, List<String> languages, List<Map<String, Object>> models, String identity) {}

    /**
     * One geometry-only association candidate; text cannot affect selection.
     * @param reference reference encounter index
     * @param prediction prediction encounter index
     * @param overlap original-pixel envelope IoU
     */
    private record Pair(int reference, int prediction, double overlap) {}

    /** Creates one independent fixture without I/O, model discovery or recognition. */
    PdfHistoricalTextCorpusTest() {
    }

    /**
     * Runs no engine/model/download unless all four explicit historical properties are supplied.
     * @throws Exception if independent identities, reference decoding or output invariants fail
     */
    @Test
    void optionalOriginalHistoricalTextUsesCompletePublishedReferences() throws Exception {
        final String cache = System.getProperty("docweave.pdf.historical.cache");
        final String report = System.getProperty("docweave.pdf.historical.report");
        final String executable = System.getProperty("docweave.pdf.historical.executable");
        final String data = System.getProperty("docweave.pdf.historical.tessdata");
        if (cache == null && report == null && executable == null && data == null) {
            return;
        }
        assertNotNull(cache);
        assertNotNull(report);
        assertNotNull(executable);
        assertNotNull(data);
        assertEquals("1", System.getenv("OMP_THREAD_LIMIT"), "Replay fixes native OCR thread count explicitly");
        final var mapper = JsonMapper.builder().build();
        final var manifest = mapper.readTree(Files.readAllBytes(Path.of(System.getProperty(
                "docweave.pdf.historical.manifest", "benchmarks/review/pdf-historical-text/manifest.json"))));
        final boolean sourceResolution = Boolean.getBoolean("docweave.pdf.historical.source.resolution");
        final boolean median = Boolean.getBoolean("docweave.pdf.historical.median3x3");
        final boolean background = Boolean.getBoolean("docweave.pdf.historical.background.whitepoint");
        final boolean contrast = Boolean.getBoolean("docweave.pdf.historical.contrast.limited");
        assertTrue(!contrast || sourceResolution, "Contrast trial requires declared TIFF source resolution");
        assertTrue(!contrast || (!median && !background), "Independent contrast trial cannot compose preprocessing");
        final boolean managedBackground = Boolean.getBoolean("docweave.pdf.historical.background.managed");
        assertTrue(!background || sourceResolution, "Background trial requires declared TIFF source resolution");
        assertTrue(!managedBackground || (background && sourceResolution),
                "Managed background trial requires explicit background/source resolution");
        assertFalse(median && background, "Independent preprocessing hypotheses must not be composed implicitly");
        final var profiles = new ArrayList<LanguageProfile>();
        for (final var profile : manifest.get("profiles")) {
            profiles.add(languageProfile(Path.of(data), profile));
        }
        final var segmentations = new java.util.TreeSet<String>();
        final var thresholds = new java.util.TreeSet<String>();
        for (final var profile : manifest.get("profiles")) {
            segmentations.add(segmentation(profile).name());
            thresholds.add(thresholding(profile).name());
        }
        final var results = new ArrayList<Map<String, Object>>();
        for (final var selected : manifest.get("cases")) {
            for (final var input : selected.get("inputs")) {
                verify(Path.of(cache), input);
            }
            final byte[] referenceXml = Files.readAllBytes(Path.of(cache, selected.get("xml").asString()));
            final var reference = reference(referenceXml);
            assertEquals(selected.get("width").asInt(), reference.width());
            assertEquals(selected.get("height").asInt(), reference.height());
            assertEquals(selected.get("expected_lines").asInt(), reference.lines().size());
            final String text = joined(reference.lines());
            assertEquals(selected.get("reference_code_points").asInt(), text.codePointCount(0, text.length()));
            assertEquals(selected.get("reference_text_sha256").asString(), hash(text.getBytes(StandardCharsets.UTF_8)));
            final ReviewedOrder reviewed;
            if (selected.get("reviewed_order") == null) {
                reviewed = null;
            } else {
                final var imagePins = new ArrayList<JsonNode>();
                for (final var input : selected.get("inputs")) {
                    if (input.get("filename").asString().equals(selected.get("image").asString())) {
                        imagePins.add(input);
                    }
                }
                assertEquals(1, imagePins.size());
                reviewed = reviewedOrder(reference, referenceXml, imagePins.getFirst().get("sha256").asString(),
                        selected.get("reviewed_order"));
            }
            final var image =
                    image(Path.of(cache, selected.get("image").asString()), reference.width(), reference.height());
            final int dpi = sourceResolution ? selected.get("source_dpi").asInt() : 72;
            if (sourceResolution) {
                assertEquals(dpi, sourceResolution(Path.of(cache, selected.get("image").asString())));
                assertTrue(dpi >= 70 && dpi <= 600, "Source resolution must fit both engine and PDF render contracts");
            }
            final byte[] container;
            final String sourceRgb;
            try {
                sourceRgb = pixels(image);
                final String shared = System.getProperty("docweave.pdf.historical.container.cache");
                if (shared == null) {
                    container = container(image, dpi);
                } else {
                    final var descriptor = mapper.readTree(Files.readAllBytes(Path.of(shared, "frames.json")))
                                                   .get(selected.get("id").asString());
                    assertNotNull(descriptor);
                    assertEquals(dpi, descriptor.get("dpi").asInt());
                    assertEquals(sourceRgb, descriptor.get("source_rgb_sha256").asString());
                    container = Files.readAllBytes(Path.of(shared, selected.get("id").asString() + ".pdf"));
                    assertEquals(descriptor.get("container_pdf_sha256").asString(), hash(container));
                }
            } finally {
                image.flush();
            }
            for (final var resolved : profiles) {
                final var profile = resolved.policy();
                final var frames = new ArrayList<Map<String, Object>>();
                final var options = new TesseractOptions(Path.of(executable), Path.of(data), resolved.languages(),
                        Duration.ofSeconds(60), 1, MAX_PIXELS, 32L * 1024 * 1024, 4 * 1024 * 1024, 10_000, 1_000_000)
                                            .withPageSegmentation(segmentation(profile))
                                            .withLineSplitting(
                                                    profile.has("line_splitting")
                                                            ? TesseractOptions.LineSplitting.valueOf(
                                                                      profile.get("line_splitting").asString())
                                                            : TesseractOptions.LineSplitting.TESSERACT_LINES);
                final var engine = managedBackground ? TesseractOcr.create(options, thresholding(profile), dpi,
                                                               dev.docweave.ocr.TesseractPreprocessing.LOCAL_WHITEPOINT)
                                   : sourceResolution ? TesseractOcr.create(options, thresholding(profile), dpi)
                                                      : TesseractOcr.create(options, thresholding(profile));
                final PdfOcrProvider observed = raster -> {
                    assertEquals(reference.width(), raster.getWidth());
                    assertEquals(reference.height(), raster.getHeight());
                    assertEquals(sourceRgb, pixels(raster),
                            "Source-resolution replay must retain exact RGB pixels before OCR");
                    frames.add(Map.of(
                            "width", raster.getWidth(), "height", raster.getHeight(), "rgb_sha256", pixels(raster)));
                    if ((!median && !background && !contrast) || managedBackground) {
                        return engine.recognize(raster);
                    }
                    final var filtered = contrast     ? ContrastLimitedScanProbe.apply(raster, MAX_PIXELS, dpi)
                                         : background ? BackgroundWhitepointScanProbe.apply(raster, MAX_PIXELS, dpi)
                                                      : MedianScanProbe.apply(raster, MAX_PIXELS);
                    try {
                        frames.set(frames.size() - 1,
                                Map.of("width", raster.getWidth(), "height", raster.getHeight(), "rgb_sha256",
                                        sourceRgb, "engine_rgb_sha256", pixels(filtered)));
                        final var recognized = engine.recognize(filtered);
                        assertEquals(sourceRgb, pixels(raster), "Filtering must not mutate the borrowed source raster");
                        return recognized;
                    } finally {
                        filtered.flush();
                    }
                };
                final var pdf = PdfPartitioner.create(
                        PdfOptions.defaults(), new PdfOcrOptions(dpi, MAX_PIXELS, 10_000, 1_000_000), observed);
                String outcome = "SUCCESS";
                List<Line> predictions = List.of();
                try {
                    final var extracted =
                            DocWeave.create(PartitionerRegistry.of(List.of(pdf)))
                                    .partition(DocumentSource.bytes(container, "historical-container.pdf"));
                    predictions = predictions(extracted.elements(), dpi / 72.0);
                    assertEquals(1, frames.size());
                    assertEquals(reference.width(), frames.getFirst().get("width"));
                    assertEquals(reference.height(), frames.getFirst().get("height"));
                    assertEquals(sourceRgb, frames.getFirst().get("rgb_sha256"),
                            "Evaluation container must retain the independently decoded RGB pixel grid");
                } catch (final PartitionException failure) {
                    // A whole-container failure keeps every source reference and the full character denominator.
                    outcome = failure.code().name();
                }
                final var result = new LinkedHashMap<String, Object>(metrics(reference.lines(), predictions));
                result.put("reference_line_order_explicit", reference.declaredLineOrder());
                if (!reference.declaredLineOrder()) {
                    // Region order does not establish line order. Preserve XML diagnostics without claiming a
                    // logical-page error rate or adapting the reference to OCR/geometry.
                    for (final String suffix :
                            List.of("character_edits", "character_error_rate", "word_edits", "word_error_rate")) {
                        result.put("xml_sequence_" + suffix, result.remove("global_" + suffix));
                    }
                }
                if (reviewed != null) {
                    // A reviewed physical order adds separate metrics; it never relabels published XML order.
                    final var reviewedMetrics = metrics(reviewed.lines(), predictions);
                    for (final String suffix :
                            List.of("character_edits", "character_error_rate", "word_edits", "word_error_rate")) {
                        result.put("reviewed_order_" + suffix, reviewedMetrics.get("global_" + suffix));
                    }
                    final String reviewedText = joined(reviewed.lines());
                    result.put("reviewed_order_evidence_sha256", reviewed.evidenceSha256());
                    result.put("reviewed_order_reference_text_sha256",
                            hash(reviewedText.getBytes(StandardCharsets.UTF_8)));
                    result.put("reviewed_order_reference_code_points",
                            reviewedText.codePointCount(0, reviewedText.length()));
                    result.put("reviewed_order_basis", "source_image_reviewed_complete_v1");
                }
                result.put("id", selected.get("id").asString());
                result.put("year", selected.get("year").asInt());
                result.put("language", profile.get("language").asString());
                if (profile.has("line_splitting")) {
                    result.put("line_splitting", profile.get("line_splitting").asString());
                }
                result.put("page_segmentation", options.pageSegmentation().name());
                result.put("thresholding", thresholding(profile).name());
                if (median || background || contrast) {
                    result.put(
                            "experimental_preprocessing", contrast ? "CONTRAST_LIMIT2_TILE64_AT300DPI_UNIFORM_IDENTITY"
                                                          : background ? "LOCAL_WHITEPOINT_95_TILE64_AT300DPI"
                                                                       : "MEDIAN_3X3_REPLICATED_RGB");
                }
                if (managedBackground) {
                    result.put("managed_page_preprocessing", "LOCAL_WHITEPOINT");
                }
                result.put("container_dpi", dpi);
                result.put("engine_source_dpi", sourceResolution ? dpi : "ENGINE_DEFAULT");
                if (resolved.models().size() == 1) {
                    result.put("model_sha256", resolved.models().getFirst().get("sha256"));
                } else {
                    result.put("model_set_sha256", resolved.identity());
                    result.put("ordered_models", resolved.models());
                    result.put("ordered_languages", resolved.languages());
                }
                result.put("outcome", outcome);
                result.put("source_rgb_sha256", sourceRgb);
                result.put("container_pdf_sha256", hash(container));
                result.put("ocr_frames", frames);
                result.put("source_render_rgb_equal",
                        frames.size() == 1 && sourceRgb.equals(frames.getFirst().get("rgb_sha256")));
                results.add(result);
                System.out.printf("Scan OCR complete: %s language=%s layout=%s threshold=%s outcome=%s%n",
                        selected.get("id").asString(), profile.get("language").asString(),
                        options.pageSegmentation().name(), thresholding(profile).name(), outcome);
            }
        }
        Files.writeString(Path.of(report),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                        Map.of("runtime", Runtime.version().toString(), "executable_sha256",
                                hash(Files.readAllBytes(Path.of(executable))), "container_dpi",
                                sourceResolution ? "SOURCE_TIFF" : 72, "page_segmentation",
                                segmentations.size() == 1 ? segmentations.first() : "MIXED_EXPLICIT", "thresholding",
                                thresholds.size() == 1 ? thresholds.first() : "MIXED_EXPLICIT", "omp_thread_limit", 1,
                                "results", results))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Exports one lossless evaluation container for identical-byte paired library execution.
     * Original source dimensions/pixels and metadata DPI are verified before encoding; reference text is not written.
     * @param source verified original TIFF
     * @param selected frozen source case and source DPI
     * @param target new PDF path
     * @return opaque source/container identity and pixel frame metadata
     * @throws Exception if image metadata, bounded decoding or fresh output requirements fail
     */
    static Map<String, Object> exportComparisonContainer(final Path source, final JsonNode selected, final Path target)
            throws Exception {
        final int dpi = selected.get("source_dpi").asInt();
        assertEquals(dpi, sourceResolution(source));
        final var raster = image(source, selected.get("width").asInt(), selected.get("height").asInt());
        try {
            final String rgb = pixels(raster);
            final byte[] bytes = container(raster, dpi);
            Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
            return Map.of("width", raster.getWidth(), "height", raster.getHeight(), "dpi", dpi, "source_rgb_sha256",
                    rgb, "container_pdf_sha256", hash(bytes), "container_bytes", bytes.length);
        } finally {
            raster.flush();
        }
    }

    /**
     * Scores complete public output against unchanged PAGE text without fabricating per-line output geometry.
     * Undeclared line order remains an XML-sequence diagnostic, never a logical page error-rate claim.
     * @param xml original verified PAGE reference
     * @param output complete emitted public text in original order
     * @return numeric/hash-only text metrics retaining the full reference denominator
     * @throws Exception if independent reference or bounded edit-distance requirements fail
     */
    static Map<String, Object> scorePublicComparisonText(final byte[] xml, final String output) throws Exception {
        final var reference = reference(xml);
        return comparisonTextMetrics(reference.lines(), reference.declaredLineOrder(),
                reference.declaredLineOrder() ? "global_" : "xml_sequence_", output);
    }

    /**
     * Scores unchanged public text against separately reviewed source order without inventing upstream line boxes.
     * Original XML order is still undeclared; every returned key is explicitly scoped to reviewed-order evidence.
     * @param xml exact original PAGE reference
     * @param imageSha256 independently verified original raster identity
     * @param binding separately frozen review file and SHA256
     * @param output complete unchanged public extraction text
     * @return reviewed-order-only numeric/hash metrics with the full source denominator
     * @throws Exception if reference identities, review validation or bounded distances fail
     */
    static Map<String, Object> scoreReviewedPublicComparisonText(
            final byte[] xml, final String imageSha256, final JsonNode binding, final String output) throws Exception {
        final var reference = reference(xml);
        final var reviewed = reviewedOrder(reference, xml, imageSha256, binding);
        final var metrics = comparisonTextMetrics(reviewed.lines(), false, "reviewed_order_", output);
        final var result = new LinkedHashMap<String, Object>();
        metrics.forEach(
                (key, value) -> result.put(key.startsWith("reviewed_order_") ? key : "reviewed_order_" + key, value));
        result.put("reviewed_order_evidence_sha256", reviewed.evidenceSha256());
        result.put("reviewed_order_basis", "source_image_reviewed_complete_v1");
        return result;
    }

    /**
     * Shares identical whole-public-text normalization and scalar/word distances across qualified reference orders.
     * @param lines complete unchanged reference line objects in the explicitly supplied evidence order
     * @param declaredLineOrder whether this order is explicitly declared in the published source XML
     * @param prefix exact evidence qualification applied to edit/rate fields
     * @param output complete public output in original encounter order
     * @return complete numeric/hash-only sequence metrics without fabricated per-line geometry
     * @throws Exception if hashing or bounded edit-distance validation fails
     */
    private static Map<String, Object> comparisonTextMetrics(final List<Line> lines, final boolean declaredLineOrder,
            final String prefix, final String output) throws Exception {
        final String expected = joined(lines);
        final String actual = normalize(output);
        final int[] first = expected.codePoints().toArray();
        final int[] second = actual.codePoints().toArray();
        final var result = new LinkedHashMap<String, Object>();
        result.put("reference_line_order_explicit", declaredLineOrder);
        result.put("reference_code_points", first.length);
        result.put("reference_lines", lines.size());
        result.put("prediction_code_points", second.length);
        final int edits = distance(first, second);
        result.put(prefix + "character_edits", edits);
        result.put(prefix + "character_error_rate", edits / (double) first.length);
        final var referenceWords = words(expected);
        final var vocabulary = new HashMap<String, Integer>();
        final int[] originalWords =
                referenceWords.stream()
                        .mapToInt(word -> vocabulary.computeIfAbsent(word, ignored -> vocabulary.size()))
                        .toArray();
        final int[] predictedWords =
                words(actual)
                        .stream()
                        .mapToInt(word -> vocabulary.computeIfAbsent(word, ignored -> vocabulary.size()))
                        .toArray();
        final int wordEdits = distance(originalWords, predictedWords);
        result.put("reference_words", referenceWords.size());
        result.put(prefix + "word_edits", wordEdits);
        result.put(prefix + "word_error_rate", wordEdits / (double) referenceWords.size());
        result.put("reference_text_sha256", hash(expected.getBytes(StandardCharsets.UTF_8)));
        result.put("prediction_text_sha256", hash(actual.getBytes(StandardCharsets.UTF_8)));
        return result;
    }

    /**
     * Public text scoring retains empty-output penalties, canonical Unicode and the independent order qualification.
     * @throws Exception if owned PAGE parsing or bounded edit scoring fails
     */
    @Test
    void pairedPublicTextRetainsOrderQualificationsAndFailurePenalties() throws Exception {
        final byte[] declared = owned().getBytes(StandardCharsets.UTF_8);
        final var exact = scorePublicComparisonText(declared, "B ſ e\u0301 A");
        assertEquals(0, exact.get("global_character_edits"));
        final var failed = scorePublicComparisonText(declared, "");
        assertEquals(7, failed.get("reference_code_points"));
        assertEquals(7, failed.get("global_character_edits"));
        assertEquals(4, failed.get("global_word_edits"));
        assertEquals(1, scorePublicComparisonText(declared, "B s é A").get("global_character_edits"));
        final byte[] undeclared = owned().replace(" custom='readingOrder {index:1;}'", "")
                                          .replace(" custom='readingOrder {index:0;}'", "")
                                          .getBytes(StandardCharsets.UTF_8);
        final var diagnostic = scorePublicComparisonText(undeclared, "B A ſ é");
        assertEquals(false, diagnostic.get("reference_line_order_explicit"));
        assertFalse(diagnostic.containsKey("global_character_error_rate"));
        assertEquals(0, diagnostic.get("xml_sequence_character_edits"));
    }

    /**
     * Explicit region/line order owns every source line once, without compatibility/long-s/hyphen repair.
     * @throws Exception if the independently authored reference cannot be parsed
     */
    @Test
    void declaredReadingOrderPreservesEveryReferenceLine() throws Exception {
        final var reference = reference(owned().getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of("B", "ſ é", "A"), reference.lines().stream().map(Line::text).toList());
        assertEquals(3, reference.lines().size());
        assertEquals(100, reference.width());
        assertTrue(reference.declaredLineOrder());
        final var undeclared = reference(
                owned().replace(" custom='readingOrder {index:1;}'", "")
                        .replace(" custom='readingOrder {index:0;}'", "")
                        .getBytes(StandardCharsets.UTF_8));
        assertFalse(undeclared.declaredLineOrder());
        assertEquals(List.of("B", "A", "ſ é"), undeclared.lines().stream().map(Line::text).toList());
    }

    /**
     * Separate reviewed labels reorder only original line objects and add a distinct complete-page metric.
     * Original XML encounter order, normalized text, geometry and undeclared-order status remain untouched.
     * @param root owned review sidecar directory
     * @throws Exception if fixture parsing, hashing or complete-order validation fails
     */
    @Test
    void reviewedOrderAddsSeparateMetricsWithoutRewritingPublishedReference(
            final @org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        final byte[] xml = undeclaredOwnedXml();
        final var original = reference(xml);
        final var review = ownedReviewedLabels(xml);
        final var result = reviewedOrder(original, xml, "0".repeat(64), writeReviewedSpec(root, review));
        assertEquals(List.of("B", "ſ é", "A"), result.lines().stream().map(Line::text).toList());
        assertEquals(List.of("B", "A", "ſ é"), original.lines().stream().map(Line::text).toList());
        assertFalse(original.declaredLineOrder());
        assertSame(original.lines().get(0), result.lines().get(0));
        assertSame(original.lines().get(2), result.lines().get(1));
        assertSame(original.lines().get(1), result.lines().get(2));
        final var predictions = List.of(new Line("p0", "B", new Box(1, 50, 90, 60)),
                new Line("p1", "ſ é", new Box(1, 10, 90, 20)), new Line("p2", "A", new Box(1, 30, 90, 40)));
        final var previous = metrics(original.lines(), predictions);
        final var separate = metrics(result.lines(), predictions);
        assertTrue(((Number) previous.get("global_character_edits")).intValue() > 0);
        assertEquals(0, separate.get("global_character_edits"));
        assertEquals(3, separate.get("exact_associated_lines"));
        for (final String field : List.of("reference_lines", "global_reference_code_points",
                     "associated_reference_lines", "exact_associated_lines", "missing_reference_lines")) {
            assertEquals(previous.get(field), separate.get(field));
        }
        assertEquals(original, reference(xml));
        assertThrows(UnsupportedOperationException.class, () -> result.lines().clear());
        final var publicMetrics =
                scoreReviewedPublicComparisonText(xml, "0".repeat(64), writeReviewedSpec(root, review), "B ſ é A");
        assertEquals(0, publicMetrics.get("reviewed_order_character_edits"));
        assertEquals(false, publicMetrics.get("reviewed_order_reference_line_order_explicit"));
        assertFalse(publicMetrics.containsKey("global_character_edits"));
        assertFalse(publicMetrics.containsKey("xml_sequence_character_edits"));
    }

    /**
     * A complete review cannot change source identities, bounds, dimensions or membership, or override XML order.
     * @param root owned separately hashed review fixtures
     * @throws Exception if fixture preparation or expected guarded rejection fails
     */
    @Test
    void reviewedOrderRejectsIncompleteUnreviewedAndChangedSourceEvidence(
            final @org.junit.jupiter.api.io.TempDir Path root) throws Exception {
        final byte[] xml = undeclaredOwnedXml();
        final var original = reference(xml);
        final var valid = ownedReviewedLabels(xml);
        final List<java.util.function.Consumer<ObjectNode>> corruptions = List.of(node
                -> ((ObjectNode) node.get("lines").get(1)).put("line_id", "b0"),
                node
                -> ((ObjectNode) node.get("lines").get(1)).put("line_id", "unknown"),
                node
                -> ((ObjectNode) node.get("lines").get(1)).put("index", 0),
                node
                -> ((tools.jackson.databind.node.ArrayNode) node.get("lines")).remove(1),
                node
                -> ((tools.jackson.databind.node.ArrayNode) node.get("lines").get(1).get("bounds")).set(0, 2),
                node
                -> node.put("source_xml_sha256", "0".repeat(64)),
                node
                -> node.put("source_tiff_sha256", "1".repeat(64)),
                node -> node.put("source_image_width", 101), node -> node.put("visual_review_status", "PENDING"));
        for (final var corruption : corruptions) {
            final var changed = valid.deepCopy();
            corruption.accept(changed);
            final var spec = writeReviewedSpec(root, changed);
            assertThrows(IllegalArgumentException.class, () -> reviewedOrder(original, xml, "0".repeat(64), spec));
        }
        final var declared = reference(owned().getBytes(StandardCharsets.UTF_8));
        final var spec = writeReviewedSpec(root, valid);
        assertThrows(IllegalArgumentException.class, () -> reviewedOrder(declared, xml, "0".repeat(64), spec));
        final var wrongHash = (ObjectNode) spec.deepCopy();
        wrongHash.put("sha256", "0".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> reviewedOrder(original, xml, "0".repeat(64), wrongHash));
    }

    /**
     * Supplies the same owned multiline reference without source-declared line indices.
     * @return complete original XML bytes whose encounter order differs from the separately authored order
     */
    private static byte[] undeclaredOwnedXml() {
        return owned()
                .replace(" custom='readingOrder {index:1;}'", "")
                .replace(" custom='readingOrder {index:0;}'", "")
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Authors independent complete labels for the known three-line control without copying prediction text.
     * @param xml exact owned original reference
     * @return mutable test-only numeric review before its file identity is frozen
     * @throws Exception if original parsing or source hashing fails
     */
    private static ObjectNode ownedReviewedLabels(final byte[] xml) throws Exception {
        final var original = reference(xml);
        final var lines = new ArrayList<Map<String, Object>>();
        for (final int ordinal : List.of(0, 2, 1)) {
            final var line = original.lines().get(ordinal);
            final var box = line.box();
            lines.add(Map.of("index", lines.size(), "line_id", line.id(), "bounds",
                    List.of(box.left(), box.top(), box.right(), box.bottom())));
        }
        return (ObjectNode) JsonMapper.builder().build().valueToTree(
                Map.of("source_xml_sha256", hash(xml), "source_tiff_sha256", "0".repeat(64), "source_image_width", 100,
                        "source_image_height", 100, "visual_review_status", "SOURCE_IMAGE_REVIEWED", "lines", lines));
    }

    /**
     * Freezes an owned review file independently of the source reference.
     * @param root temporary fixture directory
     * @param labels complete or deliberately corrupt review
     * @return explicit local path/hash binding
     * @throws Exception if fixture writing or hashing fails
     */
    private static JsonNode writeReviewedSpec(final Path root, final JsonNode labels) throws Exception {
        final var file = Files.createTempFile(root, "review-", ".json");
        final byte[] bytes = JsonMapper.builder().build().writeValueAsBytes(labels);
        Files.write(file, bytes);
        return JsonMapper.builder().build().valueToTree(Map.of("file", file.toString(), "sha256", hash(bytes)));
    }

    /** Missing/duplicate reading references, alternate transcriptions and external XML definitions fail closed. */
    @Test
    void ambiguousOrUnsafeReferenceDocumentsAreRejected() {
        for (final String xml : List.of(owned().replace("regionRef='a'", "regionRef='b'"),
                     owned().replace("index='1' regionRef='a'", "index='0' regionRef='a'"),
                     owned().replace("<Unicode>A</Unicode>", "<Unicode></Unicode>"),
                     owned().replace("<TextEquiv><Unicode>A</Unicode></TextEquiv>", ""),
                     "<!DOCTYPE PcGts SYSTEM 'file:///unavailable'>" + owned(),
                     "<!DOCTYPE PcGts [<!ENTITY x SYSTEM 'file:///unavailable'>]>" + owned())) {
            assertThrows(Exception.class, () -> reference(xml.getBytes(StandardCharsets.UTF_8)));
        }
    }

    /** Scalar edit distance counts Unicode code points and preserves all empty/missing/extra-text penalties. */
    @Test
    void unicodeMetricsKeepPrintedOrthographyAndBoundDiagnosticWork() {
        assertEquals(0, distance(normalize("e\u0301").codePoints().toArray(), normalize("é").codePoints().toArray()));
        assertEquals(1, distance("ſ".codePoints().toArray(), "s".codePoints().toArray()));
        assertEquals(1, distance("🙂".codePoints().toArray(), "A".codePoints().toArray()));
        assertEquals(1, distance("A-B".codePoints().toArray(), "AB".codePoints().toArray()));
        assertEquals(3, distance("ABC".codePoints().toArray(), new int[0]));
        assertEquals(4, distance(new int[0], "ABCD".codePoints().toArray()));
        assertThrows(IllegalArgumentException.class, () -> distance(new int[8000], new int[8000]));
    }

    /** Spatial association never chooses the better text, and retains all missing reference/extra prediction lines. */
    @Test
    void lineAssociationUsesGeometryRatherThanRecognizedText() {
        final var box = new Box(10, 10, 90, 20);
        final var other = new Box(10, 30, 90, 40);
        final var refs = List.of(new Line("a", "A", box), new Line("b", "B", other));
        final var reversed = metrics(refs, List.of(new Line("0", "B", box), new Line("1", "A", other)));
        assertEquals(2, reversed.get("associated_reference_lines"));
        assertEquals(0, reversed.get("exact_associated_lines"));
        final var tied = metrics(refs, List.of(new Line("0", "Wrong", box), new Line("1", "A", box)));
        assertEquals(1, tied.get("missing_reference_lines"));
        assertEquals(1, tied.get("predicted_lines_without_reference"));
        assertEquals(0, tied.get("exact_associated_lines"));
    }

    /**
     * A full-image lossless container has no native text and renders the original opaque RGB pixel grid exactly.
     * @throws Exception if source/container encoding or rendering fails
     */
    @Test
    void imageOnlyContainerRetainsTheOriginalRaster() throws Exception {
        for (final int type :
                List.of(BufferedImage.TYPE_INT_RGB, BufferedImage.TYPE_BYTE_GRAY, BufferedImage.TYPE_USHORT_565_RGB)) {
            final var source = new BufferedImage(80, 60, type);
            for (int y = 0; y < source.getHeight(); y++) {
                for (int x = 0; x < source.getWidth(); x++) {
                    source.setRGB(x, y, (x + y) % 3 == 0 ? 0xff224466 : 0xffffffff);
                }
            }
            try {
                for (final int dpi : List.of(72, 300, 600)) {
                    try (final var pdf = Loader.loadPDF(container(source, dpi))) {
                        assertEquals(1, pdf.getNumberOfPages());
                        assertTrue(new org.apache.pdfbox.text.PDFTextStripper().getText(pdf).isBlank());
                        final var rendered = new PDFRenderer(pdf).renderImageWithDPI(0, dpi, ImageType.RGB);
                        try {
                            assertEquals(source.getWidth(), rendered.getWidth());
                            assertEquals(source.getHeight(), rendered.getHeight());
                            assertEquals(pixels(source), pixels(rendered));
                        } finally {
                            rendered.flush();
                        }
                    }
                }
            } finally {
                source.flush();
            }
        }
    }

    /**
     * Public displayed PDF coordinates return to the original labelled pixel frame at every study resolution.
     * @throws Exception if owned container extraction or coordinate conversion fails
     */
    @Test
    void sourceResolutionPredictionsRetainIndependentPixelCoordinates() throws Exception {
        final var source = new BufferedImage(80, 60, BufferedImage.TYPE_INT_RGB);
        try {
            for (final int dpi : List.of(72, 300, 600)) {
                final PdfOcrProvider control = raster -> {
                    assertEquals(80, raster.getWidth());
                    assertEquals(60, raster.getHeight());
                    return List.of(new PdfOcrProvider.Line("CONTROL", 10, 5, 40, 15));
                };
                final var pdf = PdfPartitioner.create(
                        PdfOptions.defaults(), new PdfOcrOptions(dpi, MAX_PIXELS, 10_000, 1_000_000), control);
                final var extracted =
                        DocWeave.create(PartitionerRegistry.of(List.of(pdf)))
                                .partition(DocumentSource.bytes(container(source, dpi), "resolution-control.pdf"));
                final var lines = predictions(extracted.elements(), dpi / 72.0);
                assertEquals(1, lines.size());
                assertEquals("CONTROL", lines.getFirst().text());
                final var box = lines.getFirst().box();
                assertEquals(10, box.left(), 0.0001);
                assertEquals(5, box.top(), 0.0001);
                assertEquals(40, box.right(), 0.0001);
                assertEquals(15, box.bottom(), 0.0001);
            }
        } finally {
            source.flush();
        }
    }

    /**
     * Selects only existing trusted page segmentation values from an explicitly supplied evaluation profile.
     * Historical manifests without this field preserve automatic mode; malformed/unknown values fail the
     * diagnostic rather than silently changing layout assumptions or dropping a selected profile.
     * @param profile pinned caller-selected model and optional segmentation configuration
     * @return explicit fixed page segmentation, never inferred from recognized text
     * @throws IllegalArgumentException if an explicit mode is not supported
     */
    private static TesseractOptions.PageSegmentation segmentation(final JsonNode profile) {
        final var selected = profile.get("page_segmentation");
        return selected == null ? TesseractOptions.PageSegmentation.AUTO
                                : TesseractOptions.PageSegmentation.valueOf(selected.asString());
    }

    /**
     * Selects only trusted explicit Tesseract5 binarization modes from a frozen diagnostic profile.
     * Historical manifests retain the engine default. No arbitrary process/configuration text is accepted.
     * @param profile immutable selected evaluation policy
     * @return fixed internal image policy, preserving engine defaults when absent
     * @throws IllegalArgumentException if the explicit policy is unknown
     */
    private static TesseractThresholding thresholding(final JsonNode profile) {
        final var selected = profile.get("thresholding");
        return selected == null ? TesseractThresholding.ENGINE_DEFAULT
                                : TesseractThresholding.valueOf(selected.asString());
    }

    /**
     * Verifies explicit ordered model bindings before source decoding or recognition.
     * Historical single-language profiles retain their original field schema and binary hash.
     * @param root explicit local traineddata cache
     * @param policy frozen profile; optional languages/models must be supplied together in matching order
     * @return immutable resolved engine identifiers and independently verified component identities
     * @throws Exception if shape, language/file binding, duplicate models or original bytes differ
     */
    private static LanguageProfile languageProfile(final Path root, final JsonNode policy) throws Exception {
        assertEquals(policy.has("languages"), policy.has("models"));
        final var languages = new ArrayList<String>();
        final var pins = new ArrayList<JsonNode>();
        if (policy.has("languages")) {
            assertTrue(policy.get("languages").isArray() && policy.get("models").isArray());
            assertTrue(policy.get("languages").size() >= 1 && policy.get("languages").size() <= 16);
            assertEquals(policy.get("languages").size(), policy.get("models").size());
            for (final var language : policy.get("languages")) {
                assertTrue(language.isString());
                languages.add(language.asString());
            }
            for (final var model : policy.get("models")) {
                pins.add(model);
            }
        } else {
            languages.add(policy.get("language").asString());
            pins.add(policy);
        }
        assertEquals(languages.size(), new HashSet<>(languages).size(), "Repeated models are not independent profiles");
        assertEquals(String.join("+", languages), policy.get("language").asString());
        final var components = new ArrayList<Map<String, Object>>();
        final var descriptors = new ArrayList<String>();
        for (int index = 0; index < languages.size(); index++) {
            final String language = languages.get(index);
            assertTrue(language.matches("[A-Za-z0-9_]+(?:/[A-Za-z0-9_]+)?"));
            final var pin = pins.get(index);
            assertEquals(language, pin.get("language").asString());
            assertEquals(language + ".traineddata", pin.get("filename").asString());
            verify(root, pin);
            final String sha = pin.get("sha256").asString();
            components.add(Map.of("language", language, "filename", pin.get("filename").asString(), "sha256", sha,
                    "bytes", pin.get("bytes").asLong()));
            descriptors.add(language + ":" + sha);
        }
        return new LanguageProfile(policy, List.copyOf(languages), List.copyOf(components),
                hash(String.join("\n", descriptors).getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Ordered language bindings cannot be substituted, deduplicated or silently attributed to one model hash.
     * @param root owned temporary model bytes; no engine reads these fixtures
     * @throws Exception if fixture hashing or profile resolution fails
     */
    @Test
    void orderedLanguageProfilesRetainBindingsAndRejectAmbiguity(final @org.junit.jupiter.api.io.TempDir Path root)
            throws Exception {
        final var mapper = JsonMapper.builder().build();
        final var models = new ArrayList<Map<String, Object>>();
        for (final String language : List.of("first", "second")) {
            final byte[] bytes = ("owned " + language).getBytes(StandardCharsets.UTF_8);
            Files.write(root.resolve(language + ".traineddata"), bytes);
            // Owned pins must exercise the same mandatory Git-object identity guard as downloaded model pins.
            final var git = MessageDigest.getInstance("SHA-1");
            git.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
            models.add(Map.of("language", language, "filename", language + ".traineddata", "bytes", bytes.length,
                    "sha256", hash(bytes), "git_blob_sha1", HexFormat.of().formatHex(git.digest(bytes))));
        }
        final var forward = mapper.valueToTree(
                Map.of("language", "first+second", "languages", List.of("first", "second"), "models", models));
        final var reverse = mapper.valueToTree(Map.of("language", "second+first", "languages",
                List.of("second", "first"), "models", List.of(models.getLast(), models.getFirst())));
        final var first = languageProfile(root, forward);
        final var second = languageProfile(root, reverse);
        assertEquals(List.of("first", "second"), first.languages());
        assertEquals(List.of("second", "first"), second.languages());
        assertNotEquals(first.identity(), second.identity());
        assertThrows(UnsupportedOperationException.class, () -> first.languages().clear());
        assertEquals(models.getFirst().get("sha256"), first.models().getFirst().get("sha256"));
        final var mismatched = mapper.valueToTree(Map.of("language", "first+second", "languages",
                List.of("first", "second"), "models", List.of(models.getLast(), models.getFirst())));
        assertThrows(AssertionError.class, () -> languageProfile(root, mismatched));
        final var duplicate = mapper.valueToTree(Map.of("language", "first+first", "languages",
                List.of("first", "first"), "models", List.of(models.getFirst(), models.getFirst())));
        assertThrows(AssertionError.class, () -> languageProfile(root, duplicate));
        final var missing =
                mapper.valueToTree(Map.of("language", "first+second", "languages", List.of("first", "second")));
        assertThrows(AssertionError.class, () -> languageProfile(root, missing));
        final var legacy = languageProfile(root, mapper.valueToTree(models.getFirst()));
        assertEquals(List.of("first"), legacy.languages());
        assertEquals(models.getFirst().get("sha256"), legacy.models().getFirst().get("sha256"));
        Files.writeString(root.resolve("first.traineddata"), "changed original bytes");
        assertThrows(AssertionError.class, () -> languageProfile(root, forward));
    }

    /**
     * Verifies immutable source/model bytes before decoding or executing external recognition.
     * @param root explicit trusted cache directory
     * @param pin manifest file identity
     * @throws Exception if any size/hash differs
     */
    private static void verify(final Path root, final JsonNode pin) throws Exception {
        final var path = root.resolve(pin.get("filename").asString());
        assertEquals(pin.get("bytes").asLong(), Files.size(path));
        final var bytes = Files.readAllBytes(path);
        assertEquals(pin.get("sha256").asString(), hash(bytes));
        final var digest = MessageDigest.getInstance("SHA-1");
        digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
        assertEquals(pin.get("git_blob_sha1").asString(), HexFormat.of().formatHex(digest.digest(bytes)));
    }

    /**
     * Validates a separately frozen complete source-image review without altering original line objects or order.
     * Source-transcribed strings never come from the review. The review is optional evaluation evidence,
     * not an automatic geometry sort, published XML annotation or production extraction parameter.
     * @param original already verified original PAGE reference
     * @param xml exact original source XML
     * @param imageSha256 already verified original raster identity
     * @param binding explicit local review file and exact SHA256
     * @return complete immutable original-line permutation with independent evidence identity
     * @throws Exception if source identities, review status, membership, geometry or bounded file validation fail
     */
    private static ReviewedOrder reviewedOrder(final Reference original, final byte[] xml, final String imageSha256,
            final JsonNode binding) throws Exception {
        require(!original.declaredLineOrder());
        final var path = Path.of(binding.get("file").asString());
        require(Files.size(path) <= 65536);
        final byte[] bytes = Files.readAllBytes(path);
        final String identity = hash(bytes);
        require(identity.equals(binding.get("sha256").asString()));
        final var review = JsonMapper.builder().build().readTree(bytes);
        require(review.get("visual_review_status").asString().equals("SOURCE_IMAGE_REVIEWED")
                && review.get("source_xml_sha256").asString().equals(hash(xml))
                && review.get("source_tiff_sha256").asString().equals(imageSha256)
                && review.get("source_image_width").asDouble() == original.width()
                && review.get("source_image_height").asDouble() == original.height()
                && review.get("lines").size() == original.lines().size());
        final var originals = new HashMap<String, Line>();
        original.lines().forEach(line -> require(originals.put(line.id(), line) == null));
        final var result = new ArrayList<Line>();
        final var seen = new HashSet<String>();
        for (final var label : review.get("lines")) {
            require(label.get("index").asDouble() == result.size());
            final String id = label.get("line_id").asString();
            require(seen.add(id) && originals.containsKey(id));
            final var line = originals.get(id);
            final var bounds = label.get("bounds");
            require(bounds.size() == 4 && bounds.get(0).asDouble() == line.box().left()
                    && bounds.get(1).asDouble() == line.box().top() && bounds.get(2).asDouble() == line.box().right()
                    && bounds.get(3).asDouble() == line.box().bottom());
            result.add(line);
        }
        require(seen.equals(originals.keySet()));
        return new ReviewedOrder(List.copyOf(result), identity);
    }

    /**
     * Reads bounded PAGE transcriptions with explicit complete order and no external resource resolution.
     * @param bytes original PAGE XML
     * @return all independently transcribed lines exactly once
     * @throws Exception if the reference is unsafe, ambiguous or outside diagnostic bounds
     */
    private static Reference reference(final byte[] bytes) throws Exception {
        if (bytes.length > 2 * 1024 * 1024) {
            throw new IllegalArgumentException("Reference exceeds diagnostic XML budget");
        }
        final var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        final var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        final var pages = document.getElementsByTagNameNS("*", "Page");
        require(pages.getLength() == 1);
        final var page = (Element) pages.item(0);
        final int width = Integer.parseInt(page.getAttribute("imageWidth"));
        final int height = Integer.parseInt(page.getAttribute("imageHeight"));
        require(width > 0 && height > 0 && (long) width * height <= MAX_PIXELS);
        final var regions = new HashMap<String, Element>();
        for (final var region : children(page, "TextRegion")) {
            require(!region.getAttribute("id").isBlank() && regions.put(region.getAttribute("id"), region) == null);
        }
        final var reading = only(page, "ReadingOrder");
        final var group = only(reading, "OrderedGroup");
        final var refs = indexed(children(group, "RegionRefIndexed"), false);
        require(refs.size() == regions.size());
        final var used = new HashSet<String>();
        final var identities = new HashSet<String>();
        final var result = new ArrayList<Line>();
        boolean declaredLineOrder = true;
        for (final var ref : refs) {
            final String id = ref.getAttribute("regionRef");
            require(used.add(id) && regions.containsKey(id));
            final var originalLines = children(regions.get(id), "TextLine");
            if (originalLines.size() > 1
                    && originalLines.stream().noneMatch(line -> ORDER.matcher(line.getAttribute("custom")).find())) {
                declaredLineOrder = false;
            }
            final var lines = indexed(originalLines, true);
            for (final var line : lines) {
                final String identity = line.getAttribute("id");
                require(!identity.isBlank() && identities.add(identity));
                final String text = normalize(only(only(line, "TextEquiv"), "Unicode").getTextContent());
                require(!text.isBlank());
                result.add(new Line(identity, text, box(only(line, "Coords").getAttribute("points"), width, height)));
            }
        }
        require(result.size() == page.getElementsByTagNameNS("*", "TextLine").getLength() && !result.isEmpty());
        return new Reference(width, height, List.copyOf(result), declaredLineOrder);
    }

    /**
     * Orders complete explicit indices, or preserves line XML order when every custom line index is absent.
     * @param elements original direct children
     * @param custom whether line custom syntax supplies indices
     * @return unique, contiguous source order
     */
    private static List<Element> indexed(final List<Element> elements, final boolean custom) {
        final var indices = new HashMap<Element, Integer>();
        for (final var element : elements) {
            if (custom) {
                final var matcher = ORDER.matcher(element.getAttribute("custom"));
                if (matcher.find()) {
                    indices.put(element, Integer.parseInt(matcher.group(1)));
                    require(!matcher.find());
                }
            } else {
                indices.put(element, Integer.parseInt(element.getAttribute("index")));
            }
        }
        if (custom && indices.isEmpty()) {
            return List.copyOf(elements);
        }
        require(indices.size() == elements.size() && new HashSet<>(indices.values()).size() == indices.size());
        for (int index = 0; index < elements.size(); index++) {
            require(indices.containsValue(index));
        }
        return elements.stream().sorted(Comparator.comparingInt(indices::get)).toList();
    }

    /**
     * Reads direct namespace-independent children, excluding nested Word/region duplicates.
     * @param parent original DOM element
     * @param name local element name
     * @return direct children in source order
     */
    private static List<Element> children(final Element parent, final String name) {
        final var result = new ArrayList<Element>();
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof final Element element && name.equals(element.getLocalName())) {
                result.add(element);
            }
        }
        return result;
    }

    /**
     * Requires one unambiguous direct source child.
     * @param parent original element
     * @param name local name
     * @return unique child
     */
    private static Element only(final Element parent, final String name) {
        final var values = children(parent, name);
        require(values.size() == 1);
        return values.getFirst();
    }

    /**
     * Reads the original polygon envelope without changing/clipping invalid annotations.
     * @param points original PAGE points
     * @param width original image width
     * @param height original image height
     * @return positive contained envelope
     */
    private static Box box(final String points, final int width, final int height) {
        double left = Double.POSITIVE_INFINITY;
        double top = Double.POSITIVE_INFINITY;
        double right = Double.NEGATIVE_INFINITY;
        double bottom = Double.NEGATIVE_INFINITY;
        final var pairs = points.trim().split("\\s+");
        require(pairs.length >= 3);
        for (final String pair : pairs) {
            require(pair.matches("[0-9]+,[0-9]+"));
            final var coordinates = pair.split(",");
            final int x = Integer.parseInt(coordinates[0]);
            final int y = Integer.parseInt(coordinates[1]);
            require(x <= width && y <= height);
            left = Math.min(left, x);
            right = Math.max(right, x);
            top = Math.min(top, y);
            bottom = Math.max(bottom, y);
        }
        require(left < right && top < bottom);
        return new Box(left, top, right, bottom);
    }

    /**
     * Enforces decoded-image bounds using reader metadata before allocating the complete source raster.
     * @param path original image path
     * @param width independently pinned width
     * @param height independently pinned height
     * @return caller-owned decoded original raster
     * @throws Exception if decoding/frame checks fail
     */
    private static BufferedImage image(final Path path, final int width, final int height) throws Exception {
        try (final var input = ImageIO.createImageInputStream(path.toFile())) {
            final var readers = ImageIO.getImageReaders(input);
            require(readers.hasNext());
            final var reader = readers.next();
            try {
                reader.setInput(input);
                require(reader.getWidth(0) == width && reader.getHeight(0) == height
                        && (long) width * height <= MAX_PIXELS);
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * Creates an explicitly derivative full-image PDF container without cropping or text insertion.
     * The decoded opaque RGB values are copied into a canonical RGB raster so embedded TIFF profiles
     * cannot cause a second color interpretation during PDF rendering. No contrast or pixel geometry changes.
     * Encodes the original pixel grid with explicit physical dimensions at the supplied resolution.
     * Float bounds are rounded outward by one representable step to prevent PDFBox flooring away a pixel.
     * Exact rendered RGB equality remains mandatory; no post-render resizing or pixel correction is permitted.
     * @param image borrowed opaque source raster
     * @param dpi declared rendering resolution
     * @return complete lossless image-only PDF
     * @throws Exception if encoding fails
     */
    private static byte[] container(final BufferedImage image, final int dpi) throws Exception {
        require(!image.getColorModel().hasAlpha());
        final var rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        final int[] row = new int[image.getWidth()];
        for (int y = 0; y < image.getHeight(); y++) {
            image.getRGB(0, y, image.getWidth(), 1, row, 0, image.getWidth());
            rgb.setRGB(0, y, image.getWidth(), 1, row, 0, image.getWidth());
        }
        try (final var document = new PDDocument(); final var bytes = new ByteArrayOutputStream()) {
            final float width = dpi == 72 ? image.getWidth() : Math.nextUp(image.getWidth() * 72.0f / dpi);
            final float height = dpi == 72 ? image.getHeight() : Math.nextUp(image.getHeight() * 72.0f / dpi);
            final var page = new PDPage(new PDRectangle(width, height));
            document.addPage(page);
            try (final var stream = new PDPageContentStream(document, page)) {
                stream.drawImage(LosslessFactory.createFromImage(document, rgb), 0, 0, width, height);
            }
            document.save(bytes);
            return bytes.toByteArray();
        } finally {
            rgb.flush();
        }
    }

    /**
     * Collects emitted OCR lines and maps displayed PDF points into independently labelled source pixels.
     * @param elements ordinary public partition result
     * @param scale rendering pixels per PDF point
     * @return ordered emitted predictions, never source-selected text
     */
    private static List<Line> predictions(final List<dev.docweave.core.Element> elements, final double scale) {
        final var result = new ArrayList<Line>();
        for (final var element : elements) {
            if ("ocr".equals(element.metadata().get("pdf_text_method"))) {
                final var coordinate = (Map<?, ?>) element.metadata().get("coordinates");
                final var points = (List<?>) coordinate.get("points");
                final var first = (List<?>) points.getFirst();
                final var last = (List<?>) points.get(2);
                result.add(new Line(Integer.toString(result.size()), normalize(element.text()),
                        new Box(((Number) first.get(0)).doubleValue() * scale,
                                ((Number) first.get(1)).doubleValue() * scale,
                                ((Number) last.get(0)).doubleValue() * scale,
                                ((Number) last.get(1)).doubleValue() * scale)));
            }
        }
        return List.copyOf(result);
    }

    /**
     * Reads explicit square integer inch resolution from original TIFF metadata without inferring it from text.
     * @param path hash-verified original TIFF
     * @return source-declared dots per inch
     * @throws Exception if metadata is absent, anisotropic, noninteger or not expressed in inches
     */
    private static int sourceResolution(final Path path) throws Exception {
        try (final var input = ImageIO.createImageInputStream(path.toFile())) {
            final var reader = ImageIO.getImageReaders(input).next();
            try {
                reader.setInput(input);
                final var directory =
                        javax.imageio.plugins.tiff.TIFFDirectory.createFromMetadata(reader.getImageMetadata(0));
                final var x = directory.getTIFFField(282);
                final var y = directory.getTIFFField(283);
                final var unit = directory.getTIFFField(296);
                require(x != null && y != null && unit != null && unit.getAsInt(0) == 2);
                final double dpi = x.getAsDouble(0);
                require(dpi >= 70 && dpi <= 600 && dpi == Math.rint(dpi) && y.getAsDouble(0) == dpi);
                return (int) dpi;
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * Scores complete text plus separate geometry-only line associations, keeping missing and extra predictions.
     * @param reference complete independent source lines
     * @param predictions all emitted OCR lines
     * @return numeric/hash-only joint transcription and association evidence
     */
    private static Map<String, Object> metrics(final List<Line> reference, final List<Line> predictions) {
        final var pairs = new ArrayList<Pair>();
        for (int ref = 0; ref < reference.size(); ref++) {
            for (int prediction = 0; prediction < predictions.size(); prediction++) {
                final double overlap = iou(reference.get(ref).box(), predictions.get(prediction).box());
                if (overlap >= 0.5) {
                    pairs.add(new Pair(ref, prediction, overlap));
                }
            }
        }
        pairs.sort(Comparator.comparingDouble(Pair::overlap)
                        .reversed()
                        .thenComparingInt(Pair::reference)
                        .thenComparingInt(Pair::prediction));
        final var refs = new boolean[reference.size()];
        final var candidates = new boolean[predictions.size()];
        int associated = 0;
        int exact = 0;
        int localErrors = 0;
        int localCharacters = 0;
        for (final var pair : pairs) {
            if (!refs[pair.reference()] && !candidates[pair.prediction()]) {
                refs[pair.reference()] = true;
                candidates[pair.prediction()] = true;
                associated++;
                final String expected = reference.get(pair.reference()).text();
                final String actual = predictions.get(pair.prediction()).text();
                exact += expected.equals(actual) ? 1 : 0;
                localErrors += distance(expected.codePoints().toArray(), actual.codePoints().toArray());
            }
        }
        for (int index = 0; index < reference.size(); index++) {
            final int length = reference.get(index).text().codePointCount(0, reference.get(index).text().length());
            localCharacters += length;
            if (!refs[index]) {
                localErrors += length;
            }
        }
        final String expected = joined(reference);
        final String actual = joined(predictions);
        final int[] original = expected.codePoints().toArray();
        final int[] observed = actual.codePoints().toArray();
        final int edits = distance(original, observed);
        final var expectedWords = words(expected);
        final var actualWords = words(actual);
        final var vocabulary = new HashMap<String, Integer>();
        final int[] first = expectedWords.stream()
                                    .mapToInt(word -> vocabulary.computeIfAbsent(word, ignored -> vocabulary.size()))
                                    .toArray();
        final int[] second = actualWords.stream()
                                     .mapToInt(word -> vocabulary.computeIfAbsent(word, ignored -> vocabulary.size()))
                                     .toArray();
        final var result = new LinkedHashMap<String, Object>();
        result.put("reference_lines", reference.size());
        result.put("recognized_lines", predictions.size());
        result.put("associated_reference_lines", associated);
        result.put("exact_associated_lines", exact);
        result.put("missing_reference_lines", reference.size() - associated);
        result.put("predicted_lines_without_reference", predictions.size() - associated);
        result.put("joint_reference_line_character_errors", localErrors);
        result.put("reference_line_code_points", localCharacters);
        result.put("global_reference_code_points", original.length);
        result.put("global_prediction_code_points", observed.length);
        result.put("global_character_edits", edits);
        result.put("global_character_error_rate", original.length == 0 ? null : edits / (double) original.length);
        result.put("global_reference_words", first.length);
        final int wordEdits = distance(first, second);
        result.put("global_word_edits", wordEdits);
        result.put("global_word_error_rate", first.length == 0 ? null : wordEdits / (double) first.length);
        try {
            result.put("reference_text_sha256", hash(expected.getBytes(StandardCharsets.UTF_8)));
            result.put("prediction_text_sha256", hash(actual.getBytes(StandardCharsets.UTF_8)));
        } catch (final Exception failure) {
            throw new IllegalStateException("Required SHA-256 unavailable", failure);
        }
        return result;
    }

    /**
     * Computes ordinary insertion/deletion/substitution distance over Unicode scalars or interned word identities.
     * @param first complete reference units
     * @param second complete prediction units
     * @return edit count, never clipped to the reference length
     */
    private static int distance(final int[] first, final int[] second) {
        if ((long) first.length * second.length > MAX_EDIT_WORK) {
            throw new IllegalArgumentException("Diagnostic edit work budget exceeded");
        }
        int[] previous = new int[second.length + 1];
        int[] current = new int[second.length + 1];
        Arrays.setAll(previous, index -> index);
        for (int row = 1; row <= first.length; row++) {
            current[0] = row;
            for (int column = 1; column <= second.length; column++) {
                current[column] = Math.min(Math.min(previous[column] + 1, current[column - 1] + 1),
                        previous[column - 1] + (first[row - 1] == second[column - 1] ? 0 : 1));
            }
            final var swap = previous;
            previous = current;
            current = swap;
        }
        return previous[second.length];
    }

    /**
     * Computes source-envelope intersection over union without using recognized text.
     * @param first reference envelope
     * @param second prediction envelope
     * @return overlap in0..1
     */
    private static double iou(final Box first, final Box second) {
        final double area =
                Math.max(0, Math.min(first.right(), second.right()) - Math.max(first.left(), second.left()))
                * Math.max(0, Math.min(first.bottom(), second.bottom()) - Math.max(first.top(), second.top()));
        return area
                / ((first.right() - first.left()) * (first.bottom() - first.top())
                        + (second.right() - second.left()) * (second.bottom() - second.top()) - area);
    }

    /**
     * Normalizes canonical Unicode and ASCII whitespace only; historical orthography remains visible.
     * @param text original source or recognized text
     * @return normalized complete text
     */
    private static String normalize(final String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC).replaceAll("[ \\t\\r\\n\\f]+", " ").trim();
    }

    /**
     * Joins complete line encounter order for the separate whole-page transcription metric.
     * @param lines complete ordered lines
     * @return normalized page text
     */
    private static String joined(final List<Line> lines) {
        return String.join(" ", lines.stream().map(Line::text).toList());
    }

    /**
     * Splits already normalized text without manufacturing an empty token for missing output.
     * @param text complete normalized text
     * @return complete exact word tokens
     */
    private static List<String> words(final String text) {
        return text.isEmpty() ? List.of() : List.of(text.split(" "));
    }

    /**
     * Hashes opaque RGB pixels row-wise without retaining a second full raster.
     * @param image borrowed source or actual rendered raster
     * @return stable RGB SHA-256
     * @throws IOException if the required digest is unavailable
     */
    private static String pixels(final BufferedImage image) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final java.security.NoSuchAlgorithmException failure) {
            throw new IOException("Required SHA-256 unavailable", failure);
        }
        final int[] row = new int[image.getWidth()];
        final byte[] rgb = new byte[image.getWidth() * 3];
        for (int y = 0; y < image.getHeight(); y++) {
            image.getRGB(0, y, image.getWidth(), 1, row, 0, image.getWidth());
            for (int x = 0; x < row.length; x++) {
                rgb[x * 3] = (byte) (row[x] >> 16);
                rgb[x * 3 + 1] = (byte) (row[x] >> 8);
                rgb[x * 3 + 2] = (byte) row[x];
            }
            digest.update(rgb);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Hashes exact source/model/report text bytes.
     * @param bytes complete verified bytes
     * @return SHA-256
     * @throws Exception if the required digest is unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Rejects ambiguous references rather than adapting annotations to candidate output.
     * @param valid independent reference invariant
     */
    private static void require(final boolean valid) {
        if (!valid) {
            throw new IllegalArgumentException("Invalid or ambiguous PAGE reference");
        }
    }

    /**
     * Builds a small independently authored reference with explicit region/line-order reversal.
     * @return original owned PAGE XML
     */
    private static String owned() {
        return "<PcGts xmlns='urn:owned'><Page imageWidth='100' imageHeight='100'><ReadingOrder><OrderedGroup>"
                + "<RegionRefIndexed index='0' regionRef='b'/><RegionRefIndexed index='1' regionRef='a'/>"
                + "</OrderedGroup></ReadingOrder><TextRegion id='a'>"
                + "<TextLine id='a1' custom='readingOrder {index:1;}'><Coords points='1,30 90,30 90,40 1,40'/>"
                + "<TextEquiv><Unicode>A</Unicode></TextEquiv></TextLine>"
                + "<TextLine id='a0' custom='readingOrder {index:0;}'><Coords points='1,10 90,10 90,20 1,20'/>"
                + "<TextEquiv><Unicode> ſ  é </Unicode></TextEquiv></TextLine></TextRegion><TextRegion id='b'>"
                + "<TextLine id='b0'><Coords points='1,50 90,50 90,60 1,60'/><TextEquiv><Unicode>B</Unicode>"
                + "</TextEquiv></TextLine></TextRegion></Page></PcGts>";
    }
}
