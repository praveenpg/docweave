package dev.docweave.ocr;

import dev.docweave.DocWeave;
import dev.docweave.core.ElementType;
import dev.docweave.partition.DocumentSource;
import dev.docweave.partition.PartitionerRegistry;
import dev.docweave.partition.PdfOcrOptions;
import dev.docweave.partition.PdfOcrProvider;
import dev.docweave.partition.PdfOptions;
import dev.docweave.partition.PdfPartitioner;
import dev.docweave.partition.PdfTableLayoutProvider;
import dev.docweave.partition.PdfWordOcrProvider;
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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit installed OSD/English controls; authored rasters are not independent physical scans. */
class TesseractOrientationInstalledTest {
    @Test
    void explicitOsdAndCoreOrientationRestoreOwnedCellsAndSourceCoordinates() throws Exception {
        if (!Boolean.getBoolean("docweave.tesseract.orientation.enabled")) {
            return;
        }
        final String executableValue = System.getProperty("docweave.tesseract.executable");
        final String dataValue = System.getProperty("docweave.tesseract.tessdata");
        final String reportValue = System.getProperty("docweave.tesseract.report");
        assertNotNull(executableValue);
        assertNotNull(dataValue);
        assertNotNull(reportValue);
        final var executable = Path.of(executableValue);
        final var data = Path.of(dataValue);
        final var report = Path.of(reportValue + ".orientation.xml");
        final var defaults = TesseractOrientationOptions.defaults();
        final var options =
                new TesseractOrientationOptions(executable, data, defaults.timeout(), 1, defaults.maxImagePixels(),
                        defaults.maxPngBytes(), defaults.maxOutputBytes(), defaults.minimumOrientationScore());
        final var estimator = TesseractOrientation.create(options);
        final var engineDefaults = TesseractOptions.defaults();
        final var engineOptions = new TesseractOptions(executable, data, List.of("eng"), engineDefaults.timeout(), 1,
                engineDefaults.maxImagePixels(), engineDefaults.maxPngBytes(), engineDefaults.maxOutputBytes(),
                engineDefaults.maxLines(), engineDefaults.maxCharacters())
                                          .withCellSegmentation(TesseractOptions.CellSegmentation.SINGLE_BLOCK);
        final var engine = TesseractOcr.createWithWords(engineOptions);
        final var cleaned = TesseractOcr.createWithWords(
                engineOptions.withCellBorders(TesseractOptions.CellBorders.REMOVE_EDGE_RULES));
        final var evidence = new Properties();
        evidence.setProperty("runtime", Runtime.version().toString());
        evidence.setProperty("scope",
                "Owned serif and sans orientation observations; serif exact six-cell pipeline controls; not independent scan accuracy/calibration");
        evidence.setProperty("positive_table_profiles",
                "RULED/RETAIN;PROVIDER_LAYOUT_CELLS/REMOVE_EDGE_RULES;SEPARATE_PAGE_RETAIN_CELL_REMOVE_EDGE_RULES");
        evidence.setProperty("minimum_score", "" + options.minimumOrientationScore());
        evidence.setProperty("osd_sha256", hash(data.resolve("osd.traineddata")));
        evidence.setProperty("engine_sha256", hash(executable));
        for (final boolean serif : List.of(true, false)) {
            final var source = fixture(serif);
            try {
                for (final int angle : List.of(0, 90, 180, 270)) {
                    final var image = rotate(source, angle);
                    try {
                        final var observation = estimator.estimate(image);
                        final var key = (serif ? "serif" : "sans") + "." + angle;
                        evidence.setProperty(key + ".accepted", "" + observation.accepted());
                        evidence.setProperty(key + ".correction", "" + observation.clockwiseDegrees());
                        evidence.setProperty(key + ".raw_score", "" + observation.rawScore());
                        if (observation.accepted()) {
                            assertEquals((360 - angle) % 360, observation.clockwiseDegrees());
                        } else {
                            assertEquals(0, observation.clockwiseDegrees());
                        }
                        if (serif) {
                            assertTrue(observation.accepted(), "Positive serif control at default raw-score gate");
                            checkPipeline(image, angle, engine, estimator, false, null);
                            checkPipeline(image, angle, cleaned, estimator, true, null);
                            final PdfOcrProvider cells =
                                    crop -> cleaned.recognize(crop, PdfOcrProvider.InputKind.TABLE_CELL);
                            assertFalse(cells instanceof PdfWordOcrProvider);
                            checkPipeline(image, angle, engine, estimator, true, cells);
                        }
                    } finally {
                        image.flush();
                    }
                }
            } finally {
                source.flush();
            }
        }
        final var blank = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        final var graphics = blank.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 100, 100);
        } finally {
            graphics.dispose();
        }
        try {
            assertFalse(estimator.estimate(blank).accepted());
            evidence.setProperty("blank", "undetermined");
        } finally {
            blank.flush();
        }
        try (final var target = Files.newOutputStream(report, StandardOpenOption.CREATE_NEW)) {
            evidence.storeToXML(target, "Explicit installed Tesseract OSD controls");
        }
    }

    /**
     * Checks full owned source-image/word/layout/cell integration against fixed text and source coordinates.
     * @param image original whole raster at a supplied quarter turn
     * @param angle known source clockwise turn
     * @param engine explicit PAGE word engine; also handles cells on the historical shared path
     * @param estimator installed bounded OSD strategy
     * @param providerCells whether to select known complete provider topology instead of ruled-grid inference
     * @param cells separate line-only CELL strategy, or null for the historical shared engine
     * @throws Exception if rendering, native recognition or a fixed expectation fails
     */
    private static void checkPipeline(final BufferedImage image, final int angle, final PdfWordOcrProvider engine,
            final TesseractOrientation estimator, final boolean providerCells, final PdfOcrProvider cells)
            throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(image.getWidth() * 72f / 150, image.getHeight() * 72f / 150));
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                content.drawImage(LosslessFactory.createFromImage(document, image), 0, 0, page.getMediaBox().getWidth(),
                        page.getMediaBox().getHeight());
            }
            document.save(output);
            final var ocr = PdfOcrOptions.defaults().withTableDetection(
                    providerCells ? PdfOcrOptions.TableDetection.PROVIDER_LAYOUT_CELLS
                                  : PdfOcrOptions.TableDetection.RULED);
            final var nativeOptions = PdfOptions.defaults().withTableCellCoordinates(true);
            final PdfPartitioner partitioner;
            if (cells != null) {
                partitioner = PdfPartitioner.createOrientedWithCellOcr(
                        nativeOptions, ocr, engine, TesseractOrientationInstalledTest::layout, cells, estimator);
            } else if (providerCells) {
                partitioner = PdfPartitioner.createOriented(
                        nativeOptions, ocr, engine, TesseractOrientationInstalledTest::layout, estimator);
            } else {
                partitioner = PdfPartitioner.createOriented(nativeOptions, ocr, engine, estimator);
            }
            final var result = DocWeave.create(PartitionerRegistry.of(List.of(partitioner)))
                                       .partition(DocumentSource.bytes(output.toByteArray(), "owned-quarter-turn.pdf"));
            final var tables =
                    result.elements().stream().filter(element -> element.type() == ElementType.Table).toList();
            assertEquals(1, tables.size());
            final var table = tables.getFirst();
            assertEquals("ITEM COUNT RED 10 BLUE 20", table.text());
            assertEquals((360 - angle) % 360, table.metadata().get("pdf_ocr_orientation_correction"));
            assertTrue(result.elements()
                            .stream()
                            .filter(element
                                    -> element.type() != ElementType.Table && element.type() != ElementType.Image)
                            .map(element -> element.text())
                            .anyMatch(text -> text.contains("Outside marker")));
            assertEquals(1, result.elements().stream().filter(element -> element.type() == ElementType.Image).count());
            final var coordinates = (Map<?, ?>) table.metadata().get("coordinates");
            final var points = (List<?>) coordinates.get("points");
            final var first = (List<?>) points.getFirst();
            final var third = (List<?>) points.get(2);
            final double[] expected = switch (angle) {
                case 90 -> new double[] {340, 100, 700, 1700};
                case 180 -> new double[] {100, 340, 1700, 700};
                case 270 -> new double[] {1700, 100, 2060, 1700};
                default -> new double[] {100, 1700, 1700, 2060};
            };
            assertEquals(expected[0] * 72 / 150, ((Number) first.get(0)).doubleValue(), .5);
            assertEquals(expected[1] * 72 / 150, ((Number) first.get(1)).doubleValue(), .5);
            assertEquals(expected[2] * 72 / 150, ((Number) third.get(0)).doubleValue(), .5);
            assertEquals(expected[3] * 72 / 150, ((Number) third.get(1)).doubleValue(), .5);
        }
    }

    /** Known owned grid geometry after orientation, independent of recognized cell text. */
    private static List<PdfTableLayoutProvider.Table> layout(final BufferedImage image) {
        final double sx = image.getWidth() / 1800d;
        final double sy = image.getHeight() / 2400d;
        final double[] xs = {100, 900, 1700};
        final double[] ys = {1700, 1820, 1940, 2060};
        final var anchors = new ArrayList<PdfTableLayoutProvider.Anchor>();
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 2; column++) {
                anchors.add(new PdfTableLayoutProvider.Anchor(row, column, 1, 1,
                        new PdfTableLayoutProvider.Box(
                                xs[column] * sx, ys[row] * sy, xs[column + 1] * sx, ys[row + 1] * sy)));
            }
        }
        return List.of(new PdfTableLayoutProvider.Table(3, 2,
                new PdfTableLayoutProvider.Box(100 * sx, 1700 * sy, 1700 * sx, 2060 * sy), anchors,
                "owned-known-geometry"));
    }

    private static BufferedImage fixture(final boolean serif) {
        final var image = new BufferedImage(1800, 2400, BufferedImage.TYPE_INT_RGB);
        final var drawing = image.createGraphics();
        try {
            drawing.setColor(Color.WHITE);
            drawing.fillRect(0, 0, 1800, 2400);
            drawing.setColor(Color.BLACK);
            drawing.setFont(new Font(serif ? "DejaVu Serif" : "DejaVu Sans", Font.PLAIN, serif ? 40 : 34));
            drawing.drawString("Document orientation and table extraction validation", 100, 100);
            for (int row = 0; row < 20; row++) {
                drawing.drawString("The printed page contains clear English words and numbered records " + row + ".",
                        100, 190 + row * 65);
            }
            drawing.setStroke(new BasicStroke(3));
            drawing.drawRect(100, 1700, 1600, 360);
            drawing.drawLine(900, 1700, 900, 2060);
            drawing.drawLine(100, 1820, 1700, 1820);
            drawing.drawLine(100, 1940, 1700, 1940);
            drawing.setFont(new Font("DejaVu Sans", Font.BOLD, 45));
            final var labels = List.of(List.of("ITEM", "COUNT"), List.of("RED", "10"), List.of("BLUE", "20"));
            for (int row = 0; row < 3; row++) {
                drawing.drawString(labels.get(row).get(0), 160, 1775 + row * 120);
                drawing.drawString(labels.get(row).get(1), 1050, 1775 + row * 120);
            }
            drawing.setFont(new Font(serif ? "DejaVu Serif" : "DejaVu Sans", Font.PLAIN, serif ? 40 : 34));
            drawing.drawString("Outside marker", 100, 2250);
        } finally {
            drawing.dispose();
        }
        return image;
    }

    private static BufferedImage rotate(final BufferedImage source, final int angle) {
        final var image = new BufferedImage(
                angle % 180 == 0 ? 1800 : 2400, angle % 180 == 0 ? 2400 : 1800, BufferedImage.TYPE_INT_RGB);
        final var drawing = image.createGraphics();
        try {
            switch (angle) {
            case 90 -> drawing.translate(2400, 0);
            case 180 -> drawing.translate(1800, 2400);
            case 270 -> drawing.translate(0, 1800);
            default -> {
            }
            }
            drawing.rotate(Math.toRadians(angle));
            drawing.drawImage(source, 0, 0, null);
        } finally {
            drawing.dispose();
        }
        return image;
    }

    private static String hash(final Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
