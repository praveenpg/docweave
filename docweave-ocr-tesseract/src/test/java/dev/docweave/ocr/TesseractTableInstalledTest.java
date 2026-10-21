package dev.docweave.ocr;

import dev.docweave.DocWeave;
import dev.docweave.core.ElementType;
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

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class TesseractTableInstalledTest {
    @Test
    void actualCellOcrPreservesGridStructureAndOrdinaryTextExactlyOnce() throws Exception {
        final String executable = System.getProperty("docweave.tesseract.executable");
        final String data = System.getProperty("docweave.tesseract.tessdata");
        final String report = System.getProperty("docweave.tesseract.report");
        if (executable == null && data == null && report == null) {
            return;
        }
        assertNotNull(executable);
        assertNotNull(data);
        assertNotNull(report);
        final var base = TesseractOptions.defaults();
        final var policy = new TesseractOptions(Path.of(executable), Path.of(data), List.of("eng"), base.timeout(), 1,
                base.maxImagePixels(), base.maxPngBytes(), base.maxOutputBytes(), base.maxLines(),
                base.maxCharacters());
        final var raster = new BufferedImage(1200, 700, BufferedImage.TYPE_INT_RGB);
        final var drawing = raster.createGraphics();
        try {
            drawing.setColor(Color.WHITE);
            drawing.fillRect(0, 0, 1200, 700);
            drawing.setColor(Color.BLACK);
            drawing.setStroke(new BasicStroke(3));
            for (final int x : List.of(100, 600, 1100)) {
                drawing.drawLine(x, 150, x, 600);
            }
            for (final int y : List.of(150, 375, 600)) {
                drawing.drawLine(100, y, 1100, y);
            }
            drawing.setFont(new Font("DejaVu Sans", Font.BOLD, 48));
            drawing.drawString("Owned scanned table", 80, 75);
            drawing.drawString("Alpha 001", 140, 285);
            drawing.drawString("Beta 002", 640, 285);
            drawing.drawString("Gamma 003", 140, 510);
            drawing.drawString("Delta 004", 640, 510);
        } finally {
            drawing.dispose();
        }
        final byte[] pdf;
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(576, 336));
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                content.drawImage(LosslessFactory.createFromImage(document, raster), 0, 0, 576, 336);
            }
            document.save(output);
            pdf = output.toByteArray();
        } finally {
            raster.flush();
        }
        final var source = DocumentSource.bytes(pdf, "owned-scanned-grid.pdf");
        assertTrue(DocWeave.create().partition(source).elements().stream().allMatch(
                element -> element.type() == ElementType.Image));
        final var provider = TesseractOcr.create(policy);
        final var options = PdfOcrOptions.defaults().withTableDetection(PdfOcrOptions.TableDetection.RULED);
        final var processor = DocWeave.create(
                PartitionerRegistry.of(List.of(PdfPartitioner.create(PdfOptions.defaults(), options, provider))));
        final var result = processor.partition(source);
        final var tables = result.elements().stream().filter(element -> element.type() == ElementType.Table).toList();
        assertEquals(1, tables.size());
        assertEquals("Alpha 001 Beta 002 Gamma 003 Delta 004", tables.getFirst().text());
        assertEquals(
                "<table><tbody><tr><td>Alpha 001</td><td>Beta 002</td></tr><tr><td>Gamma 003</td><td>Delta 004</td></tr></tbody></table>",
                tables.getFirst().metadata().get("text_as_html"));
        assertEquals(List.of("Owned scanned table"),
                result.elements()
                        .stream()
                        .filter(element -> element.type() == ElementType.UncategorizedText)
                        .map(element -> element.text())
                        .toList());
        assertEquals(1, result.elements().stream().filter(element -> element.type() == ElementType.Image).count());
        final var evidence = new Properties();
        evidence.setProperty("checked_at", Instant.now().toString());
        evidence.setProperty("scope", "owned synthetic 2x2 scanned grid; no independent scanned-table accuracy claim");
        evidence.setProperty("pdf_sha256", sha256(pdf));
        evidence.setProperty("executable_sha256", sha256(Files.readAllBytes(policy.executable())));
        evidence.setProperty(
                "eng_model_sha256", sha256(Files.readAllBytes(policy.tessdataDirectory().resolve("eng.traineddata"))));
        evidence.setProperty("recognized_table", tables.getFirst().text());
        evidence.setProperty("recognized_html", tables.getFirst().metadata().get("text_as_html").toString());
        evidence.setProperty("ordinary_text", "Owned scanned table");
        evidence.setProperty("source_image_occurrences", "1");
        evidence.setProperty("java", System.getProperty("java.runtime.version"));
        evidence.setProperty("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        try (final var output = Files.newOutputStream(Path.of(report + ".tables.xml"), StandardOpenOption.CREATE_NEW)) {
            evidence.storeToXML(output, "Actual Tesseract scanned-grid control");
        }
    }

    private static String sha256(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
