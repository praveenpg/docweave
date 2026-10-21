package dev.docweave.validation;

import dev.docweave.DocWeave;
import dev.docweave.core.ElementType;
import dev.docweave.ocr.TesseractOcr;
import dev.docweave.ocr.TesseractOptions;
import dev.docweave.ocr.TesseractOrientation;
import dev.docweave.ocr.TesseractOrientationOptions;
import dev.docweave.partition.DocumentSource;
import dev.docweave.partition.PartitionerRegistry;
import dev.docweave.partition.PdfOcrOptions;
import dev.docweave.partition.PdfOptions;
import dev.docweave.partition.PdfPartitioner;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
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
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit official OCR fixtures; default abstention and an unfiltered diagnostic are separate profiles. */
class PdfIndependentOrientationTest {
    @Test
    void optionalOfficialQuarterTurnsPreserveLabelsAndReportBothScorePolicies() throws Exception {
        final String cache = System.getProperty("docweave.pdf.orientation.cache");
        final String report = System.getProperty("docweave.pdf.orientation.report");
        if (cache == null && report == null) {
            return;
        }
        assertNotNull(cache);
        assertNotNull(report);
        final var mapper = JsonMapper.builder().build();
        final var manifest = mapper.readTree(
                Files.readAllBytes(Path.of("benchmarks/review/pdf-orientation/independent-manifest.json")));
        for (final var input : manifest.get("inputs")) {
            final byte[] bytes = Files.readAllBytes(Path.of(cache).resolve(input.get("filename").asString()));
            assertEquals(input.get("bytes").asLong(), bytes.length);
            assertEquals(input.get("sha256").asString(), hash(bytes));
            final var digest = MessageDigest.getInstance("SHA-1");
            digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
            assertEquals(input.get("git_blob_sha1").asString(), HexFormat.of().formatHex(digest.digest(bytes)));
        }
        final String executable = System.getProperty("docweave.tesseract.executable");
        final String data = System.getProperty("docweave.tesseract.tessdata");
        assertNotNull(executable);
        assertNotNull(data);
        final var base = TesseractOptions.defaults();
        final var engine = TesseractOcr.createWithWords(new TesseractOptions(Path.of(executable), Path.of(data),
                List.of("eng"), base.timeout(), 1, base.maxImagePixels(), base.maxPngBytes(), base.maxOutputBytes(),
                base.maxLines(), base.maxCharacters()));
        final var defaults = TesseractOrientationOptions.defaults();
        final var expectedText = normalize(Files.readString(Path.of(cache).resolve("phototest.gold.txt")));
        final var results = new ArrayList<Map<String, Object>>();
        for (final double minimum : List.of(15d, 0d)) {
            final var estimator = TesseractOrientation.create(
                    new TesseractOrientationOptions(Path.of(executable), Path.of(data), defaults.timeout(), 1,
                            defaults.maxImagePixels(), defaults.maxPngBytes(), defaults.maxOutputBytes(), minimum));
            for (final var input : manifest.get("inputs")) {
                if (!input.has("clockwise_source_degrees")) {
                    continue;
                }
                final String name = input.get("filename").asString();
                final int correction = (360 - input.get("clockwise_source_degrees").asInt()) % 360;
                final var image = ImageIO.read(Path.of(cache).resolve(name).toFile());
                assertNotNull(image);
                try (final var document = new PDDocument(); final var bytes = new ByteArrayOutputStream()) {
                    final var observation = estimator.estimate(image);
                    if (observation.accepted()) {
                        assertEquals(correction, observation.clockwiseDegrees());
                    } else {
                        assertEquals(0, observation.clockwiseDegrees());
                    }
                    final var page = new PDPage(new PDRectangle(image.getWidth(), image.getHeight()));
                    document.addPage(page);
                    try (final var content = new PDPageContentStream(document, page)) {
                        content.drawImage(LosslessFactory.createFromImage(document, image), 0, 0, image.getWidth(),
                                image.getHeight());
                    }
                    document.save(bytes);
                    final var policy = new PdfOcrOptions(72, 1_000_000, 100, 10_000);
                    final var partitioner =
                            PdfPartitioner.createOriented(PdfOptions.defaults(), policy, engine, estimator);
                    final var result = DocWeave.create(PartitionerRegistry.of(List.of(partitioner)))
                                               .partition(DocumentSource.bytes(bytes.toByteArray(), name + ".pdf"));
                    assertEquals(1,
                            result.elements().stream().filter(element -> element.type() == ElementType.Image).count());
                    final var text =
                            normalize(String.join(" ", result.elements()
                                                               .stream()
                                                               .filter(element -> element.type() != ElementType.Image)
                                                               .map(element -> element.text())
                                                               .toList()));
                    if (minimum == 0) {
                        assertTrue(observation.accepted());
                        assertEquals(expectedText, text);
                    }
                    final var entry = new LinkedHashMap<String, Object>();
                    entry.put("input", name);
                    entry.put("minimum_raw_score", minimum);
                    entry.put("expected_correction", correction);
                    entry.put("accepted", observation.accepted());
                    entry.put("correction", observation.clockwiseDegrees());
                    entry.put("raw_score", observation.rawScore());
                    entry.put("exact_normalized_transcription", expectedText.equals(text));
                    entry.put("observed_characters", text.length());
                    entry.put("source_images", 1);
                    results.add(entry);
                } finally {
                    image.flush();
                }
            }
        }
        Files.write(Path.of(report),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(
                        Map.of("runtime", Runtime.version().toString(), "scope", manifest.get("scope").asString(),
                                "source_revision", manifest.get("revision").asString(), "osd_sha256",
                                hash(Files.readAllBytes(Path.of(data).resolve("osd.traineddata"))), "eng_sha256",
                                hash(Files.readAllBytes(Path.of(data).resolve("eng.traineddata"))), "engine_sha256",
                                hash(Files.readAllBytes(Path.of(executable))), "render_dpi", 72, "results", results)),
                StandardOpenOption.CREATE_NEW);
    }

    /** Compares only whitespace-normalized transcription, retaining source case and punctuation. */
    private static String normalize(final String text) {
        return text.replaceAll("\\s+", " ").strip();
    }

    /** Fingerprints already-installed models and exact pinned source assets. */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
