package dev.docweave.ocr;

import dev.docweave.DocWeave;
import dev.docweave.core.ElementType;
import dev.docweave.partition.DocumentSource;
import dev.docweave.partition.PartitionerRegistry;
import dev.docweave.partition.PdfOcrOptions;
import dev.docweave.partition.PdfOptions;
import dev.docweave.partition.PdfPartitioner;
import dev.docweave.partition.PdfTableLayoutProvider;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit real-engine control for actual-word aligned and explicit-cell table inference, not broad scan accuracy. */
class TesseractWordTableInstalledTest {
    @Test
    void ownedBorderlessNumericTableHasExactCellsAndRetainsOutsideText() throws Exception {
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
                                   .withPageSegmentation(TesseractOptions.PageSegmentation.SINGLE_BLOCK)
                                   .withCellSegmentation(TesseractOptions.CellSegmentation.SINGLE_BLOCK);
        final var image = new BufferedImage(1200, 400, BufferedImage.TYPE_INT_RGB);
        final var drawing = image.createGraphics();
        try {
            drawing.setColor(Color.WHITE);
            drawing.fillRect(0, 0, 1200, 400);
            drawing.setColor(Color.BLACK);
            drawing.setFont(new Font("DejaVu Sans", Font.BOLD, 40));
            final var labels = List.of(List.of("ITEM", "COUNT"), List.of("RED", "10"), List.of("BLUE", "20"));
            for (int row = 0; row < labels.size(); row++) {
                drawing.drawString(labels.get(row).get(0), 80, 100 + row * 70);
                drawing.drawString(labels.get(row).get(1), 700, 100 + row * 70);
            }
            drawing.drawString("Outside prose", 80, 350);
        } finally {
            drawing.dispose();
        }
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(576, 192));
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                content.drawImage(LosslessFactory.createFromImage(document, image), 0, 0, 576, 192);
            }
            document.save(output);
            for (final var mode : List.of(PdfOcrOptions.TableDetection.ALIGNED_WORDS,
                         PdfOcrOptions.TableDetection.PROVIDER_LAYOUT_CELLS)) {
                final var nativeOptions = PdfOptions.defaults().withTableCellCoordinates(true);
                final var ocrOptions = PdfOcrOptions.defaults().withTableDetection(mode);
                final var engine = TesseractOcr.createWithWords(policy);
                final var partitioner = mode == PdfOcrOptions.TableDetection.ALIGNED_WORDS
                                                ? PdfPartitioner.create(nativeOptions, ocrOptions, engine)
                                                : PdfPartitioner.create(nativeOptions, ocrOptions, engine,
                                                          TesseractWordTableInstalledTest::layout);
                final var processor = DocWeave.create(PartitionerRegistry.of(List.of(partitioner)));
                final var result =
                        processor.partition(DocumentSource.bytes(output.toByteArray(), "owned-word-table.pdf"));
                final var tables =
                        result.elements().stream().filter(value -> value.type() == ElementType.Table).toList();
                assertEquals(1, tables.size());
                assertEquals("ITEM COUNT RED 10 BLUE 20", tables.getFirst().text());
                assertEquals(
                        "<table><tbody><tr><td>ITEM</td><td>COUNT</td></tr><tr><td>RED</td><td>10</td></tr><tr><td>BLUE</td><td>20</td></tr></tbody></table>",
                        tables.getFirst().metadata().get("text_as_html"));
                assertEquals(List.of("Outside prose"),
                        result.elements()
                                .stream()
                                .filter(value -> value.type() != ElementType.Image && value.type() != ElementType.Table)
                                .map(value -> value.text())
                                .toList());
                assertEquals(1, result.elements().stream().filter(value -> value.type() == ElementType.Image).count());
                final var evidence = new Properties();
                evidence.setProperty("scope",
                        "owned borderless numeric table; six exact cells plus outside prose; not general accuracy");
                evidence.setProperty("runtime", Runtime.version().toString());
                evidence.setProperty("page_mode", policy.pageSegmentation().name());
                evidence.setProperty("method", mode == PdfOcrOptions.TableDetection.ALIGNED_WORDS
                                                       ? "scanned_aligned_words_heuristic_v1"
                                                       : "provider_layout_cells_v1");
                final var suffix =
                        mode == PdfOcrOptions.TableDetection.ALIGNED_WORDS ? ".word-tables.xml" : ".provider-cells.xml";
                try (final var target =
                                Files.newOutputStream(Path.of(report + suffix), StandardOpenOption.CREATE_NEW)) {
                    evidence.storeToXML(target, "Actual Tesseract word-table owned control");
                }
            }
        } finally {
            image.flush();
        }
    }

    private static List<PdfTableLayoutProvider.Table> layout(final BufferedImage image) {
        final double sx = image.getWidth() / 1200d;
        final double sy = image.getHeight() / 400d;
        final double[] xs = {40, 600, 1000};
        final double[] ys = {50, 120, 190, 270};
        final var anchors = new ArrayList<PdfTableLayoutProvider.Anchor>();
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 2; column++) {
                anchors.add(new PdfTableLayoutProvider.Anchor(row, column, 1, 1,
                        new PdfTableLayoutProvider.Box(
                                xs[column] * sx, ys[row] * sy, xs[column + 1] * sx, ys[row + 1] * sy)));
            }
        }
        return List.of(new PdfTableLayoutProvider.Table(3, 2,
                new PdfTableLayoutProvider.Box(40 * sx, 50 * sy, 1000 * sx, 270 * sy), anchors,
                "owned-known-geometry;not-model-accuracy"));
    }
}
