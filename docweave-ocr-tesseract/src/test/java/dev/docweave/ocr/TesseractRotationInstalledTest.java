package dev.docweave.ocr;

import dev.docweave.DocWeave;
import dev.docweave.core.ElementType;
import dev.docweave.partition.DocumentSource;
import dev.docweave.partition.PartitionerRegistry;
import dev.docweave.partition.PdfDeskewEstimator;
import dev.docweave.partition.PdfOcrOptions;
import dev.docweave.partition.PdfOcrProvider;
import dev.docweave.partition.PdfOptions;
import dev.docweave.partition.PdfPartitioner;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
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

/** Explicit installed-engine controls; fixed-angle correction does not establish automatic orientation accuracy. */
class TesseractRotationInstalledTest {
    @Test
    void explicitlyConfiguredCorrectionRecognizesQuarterTurnsAndSmallSkewInOwnedPdf() throws Exception {
        final String executable = System.getProperty("docweave.tesseract.executable");
        final String data = System.getProperty("docweave.tesseract.tessdata");
        final String report = System.getProperty("docweave.tesseract.report");
        if (executable == null && data == null && report == null) {
            return;
        }
        assertNotNull(executable);
        assertNotNull(data);
        assertNotNull(report);
        final var policy = new TesseractOptions(Path.of(executable), Path.of(data), List.of("eng"),
                Duration.ofSeconds(30), 1, 16_000_000, 32_000_000, 4_000_000, 1000, 100_000);
        final var options = new PdfOcrOptions(72, 16_000_000, 1000, 100_000);
        final var provider = TesseractOcr.create(policy);
        final var expected = List.of("DocWeave OCR validation", "Invoice total 12345");
        final var original = new BufferedImage(1400, 400, BufferedImage.TYPE_INT_RGB);
        final var graphics = original.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 1400, 400);
            graphics.setColor(Color.BLACK);
            graphics.setFont(new Font("DejaVu Sans", Font.BOLD, 48));
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.drawString(expected.get(0), 80, 130);
            graphics.drawString(expected.get(1), 80, 230);
        } finally {
            graphics.dispose();
        }
        final var evidence = new Properties();
        evidence.setProperty("checked_at", Instant.now().toString());
        evidence.setProperty("java", System.getProperty("java.runtime.version"));
        evidence.setProperty(
                "scope", "owned fixed-angle correction; not automatic deskew or independent scan accuracy");
        evidence.setProperty("executable_sha256", sha256(Files.readAllBytes(policy.executable())));
        evidence.setProperty(
                "eng_model_sha256", sha256(Files.readAllBytes(policy.tessdataDirectory().resolve("eng.traineddata"))));
        try {
            for (final double angle : new double[] {90, 180, 7.5}) {
                final var raster = rotated(original, angle);
                try {
                    final var corrected = PdfOcrProvider.rotated(provider, -angle, options);
                    assertEquals(
                            expected, corrected.recognize(raster).stream().map(PdfOcrProvider.Line::text).toList());
                    if (angle == 7.5) {
                        final var estimate = PdfDeskewEstimator.create(options.maxRenderPixels()).estimate(raster);
                        assertEquals(PdfDeskewEstimator.Status.ACCEPTED, estimate.status(), estimate.toString());
                        assertEquals(-angle, estimate.clockwiseDegrees(), 0.5);
                        assertEquals(expected, PdfOcrProvider.deskewed(provider, options)
                                                       .recognize(raster)
                                                       .stream()
                                                       .map(PdfOcrProvider.Line::text)
                                                       .toList());
                        evidence.setProperty("automatic_deskew", estimate.toString());
                    }
                    final byte[] pdf;
                    try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
                        final var page = new PDPage(new PDRectangle(raster.getWidth(), raster.getHeight()));
                        document.addPage(page);
                        try (final var content = new PDPageContentStream(document, page)) {
                            content.drawImage(LosslessFactory.createFromImage(document, raster), 0, 0,
                                    raster.getWidth(), raster.getHeight());
                        }
                        document.save(output);
                        pdf = output.toByteArray();
                    }
                    final var selected = angle == 7.5 ? PdfOcrProvider.deskewed(provider, options) : corrected;
                    final var result = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(
                                                               PdfOptions.defaults(), options, selected))))
                                               .partition(DocumentSource.bytes(pdf, "owned-rotated.pdf"));
                    assertEquals(expected, result.elements()
                                                   .stream()
                                                   .filter(e -> e.type() != ElementType.Image)
                                                   .map(e -> e.text())
                                                   .toList());
                    assertEquals(1, result.elements().stream().filter(e -> e.type() == ElementType.Image).count());
                    final var png = new ByteArrayOutputStream();
                    ImageIO.write(raster, "png", png);
                    evidence.setProperty("source_angle_" + angle,
                            "correction=" + (-angle) + ";exact_lines=" + expected.size()
                                    + ";raster_sha256=" + sha256(png.toByteArray()) + ";pdf_sha256=" + sha256(pdf));
                } finally {
                    raster.flush();
                }
            }
            try (final var output =
                            Files.newOutputStream(Path.of(report + ".rotation.xml"), StandardOpenOption.CREATE_NEW)) {
                evidence.storeToXML(output, "Installed Tesseract fixed-angle correction evidence");
            }
        } finally {
            original.flush();
        }
    }

    private static BufferedImage rotated(final BufferedImage source, final double degrees) {
        final double radians = Math.toRadians(degrees);
        final double cosine = Math.abs(Math.cos(radians));
        final double sine = Math.abs(Math.sin(radians));
        final int width = (int) Math.ceil(source.getWidth() * cosine + source.getHeight() * sine - 1e-9);
        final int height = (int) Math.ceil(source.getHeight() * cosine + source.getWidth() * sine - 1e-9);
        final var result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        final var graphics = result.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            final var transform = AffineTransform.getTranslateInstance(width / 2.0, height / 2.0);
            transform.rotate(radians);
            transform.translate(-source.getWidth() / 2.0, -source.getHeight() / 2.0);
            graphics.drawImage(source, transform, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    private static String sha256(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
