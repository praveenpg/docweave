package io.docweave.ocr;

import io.docweave.DocWeave;
import io.docweave.core.ElementType;
import io.docweave.partition.DocumentSource;
import io.docweave.partition.PartitionerRegistry;
import io.docweave.partition.PdfOcrOptions;
import io.docweave.partition.PdfOptions;
import io.docweave.partition.PdfPartitioner;
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
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** Optional actual-engine adjacent-page control; no external corpus or semantic-continuation accuracy claim. */
class TesseractContinuationInstalledTest {
    @Test
    void actualRepeatedHeadersLinkPageLocalTablesWithoutRemovingRows() throws Exception {
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
        final byte[] pdf;
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            for (final int top : List.of(400, 40)) {
                final var raster = new BufferedImage(500, 800, BufferedImage.TYPE_INT_RGB);
                final var drawing = raster.createGraphics();
                try {
                    drawing.setColor(Color.WHITE);
                    drawing.fillRect(0, 0, 500, 800);
                    drawing.setColor(Color.BLACK);
                    drawing.setStroke(new BasicStroke(3));
                    final int bottom = top == 400 ? 760 : 400;
                    for (final int x : List.of(40, 220, 400)) {
                        drawing.drawLine(x, top, x, bottom);
                    }
                    for (final int y : List.of(top, top + 80, bottom)) {
                        drawing.drawLine(40, y, 400, y);
                    }
                    drawing.setFont(new Font("DejaVu Sans", Font.BOLD, 24));
                    drawing.drawString("Item", 55, top + 50);
                    drawing.drawString("Qty", 235, top + 50);
                    drawing.drawString("Alpha 001", 55, top + 150);
                    drawing.drawString("10", 235, top + 150);
                } finally {
                    drawing.dispose();
                }
                try {
                    final var page = new PDPage(new PDRectangle(240, 384));
                    document.addPage(page);
                    try (final var content = new PDPageContentStream(document, page)) {
                        content.drawImage(LosslessFactory.createFromImage(document, raster), 0, 0, 240, 384);
                    }
                } finally {
                    raster.flush();
                }
            }
            document.save(output);
            pdf = output.toByteArray();
        }
        final var options = PdfOptions.defaults().withTableCellCoordinates(true).withTableContinuation(
                PdfOptions.TableContinuation.REPEATED_HEADER);
        final var ocr = PdfOcrOptions.defaults().withTableDetection(PdfOcrOptions.TableDetection.RULED);
        final var processor = DocWeave.create(
                PartitionerRegistry.of(List.of(PdfPartitioner.create(options, ocr, TesseractOcr.create(policy)))));
        final var elements = processor.partition(DocumentSource.bytes(pdf, "owned-continuation.pdf")).elements();
        final var tables = elements.stream().filter(e -> e.type() == ElementType.Table).toList();
        assertEquals(2, tables.size());
        assertEquals(2, elements.stream().filter(e -> e.type() == ElementType.Image).count());
        for (final var table : tables) {
            assertEquals("Item Qty Alpha 001 10", table.text());
            assertEquals(
                    "<table><tbody><tr><td>Item</td><td>Qty</td></tr><tr><td>Alpha 001</td><td>10</td></tr></tbody></table>",
                    table.metadata().get("text_as_html"));
            assertEquals(4, ((List<?>) ((Map<?, ?>) table.metadata().get("pdf_table_cells")).get("cells")).size());
        }
        assertFalse(tables.getFirst().metadata().containsKey("pdf_table_continuation"));
        final var link = (Map<?, ?>) tables.getLast().metadata().get("pdf_table_continuation");
        assertNotNull(link);
        assertEquals(tables.getFirst().elementId(), link.get("root_element_id"));
        assertEquals(tables.getFirst().elementId(), link.get("previous_element_id"));
        assertEquals(1, link.get("part_index"));
        final var evidence = new Properties();
        evidence.setProperty("checked_at", Instant.now().toString());
        evidence.setProperty(
                "scope", "owned two-page raster/repeated-header geometry; no independent semantic accuracy claim");
        evidence.setProperty("pdf_sha256", sha256(pdf));
        evidence.setProperty("executable_sha256", sha256(Files.readAllBytes(policy.executable())));
        evidence.setProperty(
                "eng_model_sha256", sha256(Files.readAllBytes(policy.tessdataDirectory().resolve("eng.traineddata"))));
        evidence.setProperty("java", System.getProperty("java.runtime.version"));
        evidence.setProperty("root_id", tables.getFirst().elementId());
        evidence.setProperty("continuation_id", tables.getLast().elementId());
        evidence.setProperty("previous_id", link.get("previous_element_id").toString());
        evidence.setProperty("part_index", link.get("part_index").toString());
        evidence.setProperty("exact_page_text_and_html", "true");
        evidence.setProperty("cell_geometry_retained", "true");
        try (final var output =
                        Files.newOutputStream(Path.of(report + ".continuations.xml"), StandardOpenOption.CREATE_NEW)) {
            evidence.storeToXML(output, "Actual Tesseract repeated-header continuation control");
        }
    }

    /** Returns exact generated PDF/executable/model identities without retaining binaries. */
    private static String sha256(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
