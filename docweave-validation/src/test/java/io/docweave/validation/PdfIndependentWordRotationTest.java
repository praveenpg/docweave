package io.docweave.validation;

import io.docweave.DocWeave;
import io.docweave.core.ElementType;
import io.docweave.ocr.TesseractOcr;
import io.docweave.ocr.TesseractOptions;
import io.docweave.partition.DocumentSource;
import io.docweave.partition.PartitionerRegistry;
import io.docweave.partition.PdfOcrOptions;
import io.docweave.partition.PdfOptions;
import io.docweave.partition.PdfPartitioner;
import io.docweave.partition.PdfWordOcrProvider;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit published-angle actual-word controls; labels are supplied correction, never inferred confidence. */
class PdfIndependentWordRotationTest {
    @Test
    void optionalOfficialQuarterTurnsRetainActualWordsSourceGeometryAndTranscription() throws Exception {
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
        final var expected = normalize(Files.readString(Path.of(cache).resolve("phototest.gold.txt")));
        final var results = new ArrayList<Map<String, Object>>();
        for (final var input : manifest.get("inputs")) {
            if (!input.has("clockwise_source_degrees")) {
                continue;
            }
            final String name = input.get("filename").asString();
            final int correction = (360 - input.get("clockwise_source_degrees").asInt()) % 360;
            final var image = ImageIO.read(Path.of(cache).resolve(name).toFile());
            assertNotNull(image);
            try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
                final var page = new PDPage(new PDRectangle(image.getWidth(), image.getHeight()));
                document.addPage(page);
                try (final var content = new PDPageContentStream(document, page)) {
                    content.drawImage(LosslessFactory.createFromImage(document, image), 0, 0, image.getWidth(),
                            image.getHeight());
                }
                document.save(output);
                final var limits = new PdfOcrOptions(72, 1_000_000, 100, 10_000);
                final var rotated = PdfWordOcrProvider.rotated(engine, correction, limits);
                final var calls = new AtomicInteger();
                final var retained = new AtomicInteger();
                final PdfWordOcrProvider observed = (raster, kind) -> {
                    calls.incrementAndGet();
                    final var lines = rotated.recognizeWords(raster, kind);
                    for (final var line : lines) {
                        for (final var word : line.words()) {
                            assertTrue(word.left() >= 0 && word.right() <= image.getWidth());
                            assertTrue(word.top() >= 0 && word.bottom() <= image.getHeight());
                            assertTrue(word.left() >= line.line().left() && word.right() <= line.line().right());
                            assertTrue(word.top() >= line.line().top() && word.bottom() <= line.line().bottom());
                            retained.incrementAndGet();
                        }
                    }
                    return lines;
                };
                final var pdf = PdfPartitioner.create(PdfOptions.defaults(), limits, observed);
                final var result = DocWeave.create(PartitionerRegistry.of(List.of(pdf)))
                                           .partition(DocumentSource.bytes(output.toByteArray(), name + ".pdf"));
                final var text =
                        normalize(String.join(" ", result.elements()
                                                           .stream()
                                                           .filter(element -> element.type() != ElementType.Image)
                                                           .map(element -> element.text())
                                                           .toList()));
                assertEquals(expected, text);
                assertEquals(1, calls.get());
                assertTrue(retained.get() > 0);
                assertEquals(
                        1, result.elements().stream().filter(element -> element.type() == ElementType.Image).count());
                results.add(Map.of("input", name, "explicit_published_correction", correction, "retained_actual_words",
                        retained.get(), "word_and_line_source_bounds_valid", true, "exact_normalized_transcription",
                        expected.equals(text), "source_images", 1));
            } finally {
                image.flush();
            }
        }
        Files.write(Path.of(report + ".words.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.of("runtime",
                        Runtime.version().toString(), "scope",
                        "Official OCR fixtures with supplied published corrections; actual-word integration, not automatic estimation/calibration/physical-scan accuracy",
                        "source_revision", manifest.get("revision").asString(), "render_dpi", 72, "engine_sha256",
                        hash(Files.readAllBytes(Path.of(executable))), "eng_sha256",
                        hash(Files.readAllBytes(Path.of(data).resolve("eng.traineddata"))), "results", results)),
                StandardOpenOption.CREATE_NEW);
    }

    /** Compares source case/punctuation with whitespace normalization only, without OCR repair. */
    private static String normalize(final String value) {
        return value.replaceAll("\\s+", " ").strip();
    }

    /** Fingerprints exact source assets and explicitly installed native recognition dependencies. */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
