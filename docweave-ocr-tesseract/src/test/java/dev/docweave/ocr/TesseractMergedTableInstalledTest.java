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

/** Optional installed-engine check of full merged interiors, span HTML and ordinary-text ownership. */
class TesseractMergedTableInstalledTest {
    @Test
    void actualOcrRecognizesMergedMultilineAnchorAndRemainingCellsExactlyOnce() throws Exception {
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
                base.maxImagePixels(), base.maxPngBytes(), base.maxOutputBytes(), base.maxLines(), base.maxCharacters())
                                   .withCellSegmentation(TesseractOptions.CellSegmentation.SINGLE_BLOCK);
        final var raster = new BufferedImage(1200, 900, BufferedImage.TYPE_INT_RGB);
        final var drawing = raster.createGraphics();
        try {
            drawing.setColor(Color.WHITE);
            drawing.fillRect(0, 0, 1200, 900);
            drawing.setColor(Color.BLACK);
            drawing.setStroke(new BasicStroke(3));
            drawing.drawRect(100, 150, 1000, 675);
            drawing.drawLine(433, 600, 433, 825);
            drawing.drawLine(766, 150, 766, 825);
            drawing.drawLine(766, 375, 1100, 375);
            drawing.drawLine(100, 600, 1100, 600);
            drawing.setFont(new Font("DejaVu Sans", Font.BOLD, 36));
            drawing.drawString("Owned merged table", 80, 75);
            drawing.drawString("Alpha 001", 140, 285);
            drawing.drawString("Second line", 140, 450);
            drawing.drawString("Beta 002", 800, 285);
            drawing.drawString("Gamma 003", 800, 510);
            drawing.drawString("Delta 004", 140, 735);
            drawing.drawString("Echo 005", 473, 735);
            drawing.drawString("Foxtrot 006", 806, 735);
        } finally {
            drawing.dispose();
        }
        final byte[] pdf;
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(576, 432));
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                content.drawImage(LosslessFactory.createFromImage(document, raster), 0, 0, 576, 432);
            }
            document.save(output);
            pdf = output.toByteArray();
        } finally {
            raster.flush();
        }
        final var ocr = PdfOcrOptions.defaults().withTableDetection(PdfOcrOptions.TableDetection.RULED);
        final var processor = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(
                PdfOptions.defaults().withTableCellCoordinates(true), ocr, TesseractOcr.create(policy)))));
        final var result = processor.partition(DocumentSource.bytes(pdf, "owned-merged-scan.pdf"));
        final var tables = result.elements().stream().filter(e -> e.type() == ElementType.Table).toList();
        assertEquals(1, tables.size());
        final var table = tables.getFirst();
        assertEquals("Alpha 001 Second line Beta 002 Gamma 003 Delta 004 Echo 005 Foxtrot 006", table.text());
        assertEquals(
                "<table><tbody><tr><td rowspan=\"2\" colspan=\"2\">Alpha 001 Second line</td><td>Beta 002</td></tr>"
                        + "<tr><td>Gamma 003</td></tr><tr><td>Delta 004</td><td>Echo 005</td><td>Foxtrot 006</td></tr></tbody></table>",
                table.metadata().get("text_as_html"));
        assertEquals("scanned_ruled_grid_with_spans_v1", table.metadata().get("pdf_table_method"));
        final var geometry = (java.util.Map<?, ?>) table.metadata().get("pdf_table_cells");
        assertEquals("rule_centerlines", geometry.get("geometry"));
        assertEquals(3, geometry.get("row_count"));
        assertEquals(3, geometry.get("column_count"));
        final var cells = (List<?>) geometry.get("cells");
        assertEquals(6, cells.size());
        final var merged = (java.util.Map<?, ?>) cells.getFirst();
        assertEquals(2, merged.get("row_span"));
        assertEquals(2, merged.get("column_span"));
        final var bounds = (java.util.Map<?, ?>) merged.get("coordinates");
        final var points = (List<?>) bounds.get("points");
        final var first = (List<?>) points.getFirst();
        final var third = (List<?>) points.get(2);
        assertEquals(48, ((Number) first.getFirst()).doubleValue(), 0.5);
        assertEquals(72, ((Number) first.getLast()).doubleValue(), 0.5);
        assertEquals(367.68, ((Number) third.getFirst()).doubleValue(), 0.5);
        assertEquals(288, ((Number) third.getLast()).doubleValue(), 0.5);
        assertEquals(1, result.elements().stream().filter(e -> e.type() == ElementType.Image).count());
        assertEquals(List.of("Owned merged table"), result.elements()
                                                            .stream()
                                                            .filter(e -> e.type() == ElementType.UncategorizedText)
                                                            .map(e -> e.text())
                                                            .toList());
        final var evidence = new Properties();
        evidence.setProperty("checked_at", Instant.now().toString());
        evidence.setProperty(
                "scope", "owned raster 3x3 atomic grid with 2x2 merged anchor; no independent scan accuracy claim");
        evidence.setProperty("pdf_sha256", sha256(pdf));
        evidence.setProperty("executable_sha256", sha256(Files.readAllBytes(policy.executable())));
        evidence.setProperty(
                "eng_model_sha256", sha256(Files.readAllBytes(policy.tessdataDirectory().resolve("eng.traineddata"))));
        evidence.setProperty("java", System.getProperty("java.runtime.version"));
        evidence.setProperty("recognized_html", table.metadata().get("text_as_html").toString());
        evidence.setProperty("cell_coordinates_verified", "true");
        evidence.setProperty("cell_geometry", "rule_centerlines/PDFUserSpace");
        evidence.setProperty("anchors", "6");
        evidence.setProperty("atomic_slots", "9");
        evidence.setProperty("ordinary_text", "Owned merged table");
        try (final var output =
                        Files.newOutputStream(Path.of(report + ".merged-tables.xml"), StandardOpenOption.CREATE_NEW)) {
            evidence.storeToXML(output, "Actual Tesseract owned merged-table control");
        }
    }

    /** Returns a byte-exact source/executable/model identity without retaining external binaries. */
    private static String sha256(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
