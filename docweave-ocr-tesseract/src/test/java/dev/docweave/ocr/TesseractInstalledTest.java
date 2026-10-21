package dev.docweave.ocr;

import dev.docweave.DocWeave;
import dev.docweave.core.ElementType;
import dev.docweave.partition.DocumentSource;
import dev.docweave.partition.PartitionException;
import dev.docweave.partition.PartitionerRegistry;
import dev.docweave.partition.PdfOcrOptions;
import dev.docweave.partition.PdfOcrProvider;
import dev.docweave.partition.PdfOptions;
import dev.docweave.partition.PdfPartitioner;
import dev.docweave.partition.PdfWordOcrProvider;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in installed-engine controls using owned raster/PDF content and explicitly supplied local assets. */
class TesseractInstalledTest {
    /** Creates the fixture without discovering, downloading or launching an engine. */
    TesseractInstalledTest() {
    }

    /**
     * Checks real line/word geometry, every page mode, source DPI, immutable pixels and typed engine failures.
     * @throws Exception if explicitly configured recognition, evidence writing or owned PDF construction fails
     */
    @Test
    void explicitlyConfiguredRealOcrRecognizesOwnedRasterAndPdf() throws Exception {
        final String executable = System.getProperty("docweave.tesseract.executable");
        final String data = System.getProperty("docweave.tesseract.tessdata");
        final String report = System.getProperty("docweave.tesseract.report");
        if (executable == null && data == null && report == null) {
            return;
        }
        assertNotNull(executable, "Real OCR requires executable, tessdata and fresh report paths together");
        assertNotNull(data);
        assertNotNull(report);
        final var policy = new TesseractOptions(Path.of(executable), Path.of(data), List.of("eng"),
                Duration.ofSeconds(30), 1, 16_000_000, 32_000_000, 4_000_000, 1000, 100_000);
        final var raster = new BufferedImage(1400, 400, BufferedImage.TYPE_INT_RGB);
        final var font = new Font("DejaVu Sans", Font.BOLD, 48);
        final var graphics = raster.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, raster.getWidth(), raster.getHeight());
            graphics.setColor(Color.BLACK);
            graphics.setFont(font);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.drawString("DocWeave OCR validation", 80, 130);
            graphics.drawString("Invoice total 12345", 80, 230);
        } finally {
            graphics.dispose();
        }
        try {
            final var provider = TesseractOcr.create(policy);
            final var expected = List.of("DocWeave OCR validation", "Invoice total 12345");
            final var direct = provider.recognize(raster);
            assertEquals(expected, direct.stream().map(line -> line.text()).toList());
            final var detailed = ((dev.docweave.partition.PdfWordOcrProvider) provider)
                                         .recognizeWords(raster, TesseractOcr.InputKind.PAGE);
            assertEquals(direct, detailed.stream().map(line -> line.line()).toList());
            assertEquals(List.of("DocWeave", "OCR", "validation", "Invoice", "total", "12345"),
                    detailed.stream().flatMap(line -> line.words().stream()).map(word -> word.text()).toList());
            for (final var line : detailed) {
                for (final var word : line.words()) {
                    assertTrue(word.right() <= raster.getWidth());
                    assertTrue(word.bottom() <= raster.getHeight());
                }
            }
            // This owned raster represents a 300-DPI scan; only engine metadata changes, never its pixels.
            final int[] beforeResolution =
                    raster.getRGB(0, 0, raster.getWidth(), raster.getHeight(), null, 0, raster.getWidth());
            for (final var thresholding : TesseractThresholding.values()) {
                final var resolved = TesseractOcr.create(policy, thresholding, 300).recognize(raster);
                final var resolvedWords = TesseractOcr.createWithWords(policy, thresholding, 300)
                                                  .recognizeWords(raster, TesseractOcr.InputKind.PAGE);
                assertEquals(expected, resolved.stream().map(line -> line.text()).toList());
                assertEquals(resolved, resolvedWords.stream().map(PdfWordOcrProvider.WordLine::line).toList());
                assertEquals(List.of("DocWeave", "OCR", "validation", "Invoice", "total", "12345"),
                        resolvedWords.stream()
                                .flatMap(line -> line.words().stream())
                                .map(word -> word.text())
                                .toList());
                for (final var line : resolvedWords) {
                    for (final var word : line.words()) {
                        assertTrue(word.left() >= 0 && word.top() >= 0);
                        assertTrue(word.right() <= raster.getWidth() && word.bottom() <= raster.getHeight());
                    }
                }
                assertArrayEquals(beforeResolution,
                        raster.getRGB(0, 0, raster.getWidth(), raster.getHeight(), null, 0, raster.getWidth()));
            }
            final var magnifiedWords =
                    PdfWordOcrProvider.upscaled((PdfWordOcrProvider) provider, 3, PdfOcrOptions.defaults())
                            .recognizeWords(raster, PdfOcrProvider.InputKind.PAGE);
            assertEquals(expected, magnifiedWords.stream().map(line -> line.line().text()).toList());
            assertEquals(List.of("DocWeave", "OCR", "validation", "Invoice", "total", "12345"),
                    magnifiedWords.stream().flatMap(line -> line.words().stream()).map(word -> word.text()).toList());
            for (final var line : magnifiedWords) {
                assertTrue(line.line().right() <= raster.getWidth());
                assertTrue(line.line().bottom() <= raster.getHeight());
                for (final var word : line.words()) {
                    assertTrue(word.right() <= raster.getWidth());
                    assertTrue(word.bottom() <= raster.getHeight());
                }
            }
            final var magnifiedLines = PdfOcrProvider.upscaled(provider, 3, PdfOcrOptions.defaults()).recognize(raster);
            assertEquals(expected, magnifiedLines.stream().map(line -> line.text()).toList());
            for (final var mode : TesseractOptions.PageSegmentation.values()) {
                final var selected = TesseractOcr.create(policy.withPageSegmentation(mode));
                assertEquals(
                        expected, selected.recognize(raster).stream().map(line -> line.text()).toList(), mode.name());
            }
            final byte[] pdf;
            try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
                final var page = new PDPage(new PDRectangle(672, 192));
                document.addPage(page);
                try (final var content = new PDPageContentStream(document, page)) {
                    content.drawImage(LosslessFactory.createFromImage(document, raster), 0, 0, 672, 192);
                }
                document.save(output);
                pdf = output.toByteArray();
            }
            final var source = DocumentSource.bytes(pdf, "owned-scan.pdf");
            final var nativeResult = DocWeave.create().partition(source);
            assertTrue(nativeResult.elements().stream().allMatch(element -> element.type() == ElementType.Image));
            final var processor = DocWeave.create(PartitionerRegistry.of(
                    List.of(PdfPartitioner.create(PdfOptions.defaults(), PdfOcrOptions.defaults(), provider))));
            final var result = processor.partition(source);
            assertEquals(expected, result.elements()
                                           .stream()
                                           .filter(element -> element.type() != ElementType.Image)
                                           .map(element -> element.text())
                                           .toList());
            assertEquals(1, result.elements().stream().filter(element -> element.type() == ElementType.Image).count());
            final var missingLanguage = new TesseractOptions(policy.executable(), policy.tessdataDirectory(),
                    List.of("docweave_missing"), policy.timeout(), 1, policy.maxImagePixels(), policy.maxPngBytes(),
                    policy.maxOutputBytes(), policy.maxLines(), policy.maxCharacters());
            assertEquals(PartitionException.Code.OCR_FAILURE,
                    assertThrows(PartitionException.class, () -> TesseractOcr.create(missingLanguage).recognize(raster))
                            .code());
            final var png = new ByteArrayOutputStream();
            ImageIO.write(raster, "png", png);
            final var evidence = new Properties();
            evidence.setProperty("checked_at", Instant.now().toString());
            evidence.setProperty("scope", "owned synthetic raster and image-only PDF, not general OCR accuracy");
            evidence.setProperty("executable_sha256", sha256(Files.readAllBytes(policy.executable())));
            evidence.setProperty("eng_model_sha256",
                    sha256(Files.readAllBytes(policy.tessdataDirectory().resolve("eng.traineddata"))));
            evidence.setProperty("raster_png_sha256", sha256(png.toByteArray()));
            evidence.setProperty("pdf_sha256", sha256(pdf));
            evidence.setProperty("font", font.getFamily() + " bold 48");
            evidence.setProperty("java", System.getProperty("java.runtime.version"));
            evidence.setProperty("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
            evidence.setProperty("recognized_lines", String.join(" | ", expected));
            evidence.setProperty("owned_page_modes", java.util.Arrays.stream(TesseractOptions.PageSegmentation.values())
                                                                     .map(mode -> mode.name() + mode.argument())
                                                                     .collect(java.util.stream.Collectors.joining(" "))
                                                             + ": exact two lines each");
            evidence.setProperty("owned_source_dpi",
                    "300: ENGINE_DEFAULT OTSU ADAPTIVE_OTSU SAUVOLA; exact lines and words; unchanged pixels");
            evidence.setProperty(
                    "owned_word_geometry", "6 actual TSV word boxes; exact line projection and raster containment");
            evidence.setProperty(
                    "owned_magnification", "3x nearest; exact2 lines and6 actual words in original raster bounds");
            evidence.setProperty("missing_language", "OCR_FAILURE");
            evidence.setProperty("pdf_image_elements", "1");
            try (final var output = Files.newOutputStream(Path.of(report), StandardOpenOption.CREATE_NEW)) {
                evidence.storeToXML(output, "Real Tesseract owned-control evidence");
            }
        } finally {
            raster.flush();
        }
    }

    /**
     * Records exact local asset identities without retaining model or source bytes in evidence.
     * @param bytes original local artifact bytes
     * @return lowercase SHA256
     * @throws Exception if SHA256 is unavailable
     */
    private static String sha256(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
