package io.docweave.ocr;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/** Optional real-engine multiline/empty cell control, independent of subprocess argument doubles. */
class TesseractCellInstalledTest {
    @Test
    void explicitBlockModeRetainsMultilineTextAndBlankCells() throws Exception {
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
        final var provider = TesseractOcr.create(policy);
        final var image = new BufferedImage(700, 230, BufferedImage.TYPE_INT_RGB);
        final var drawing = image.createGraphics();
        try {
            drawing.setColor(Color.WHITE);
            drawing.fillRect(0, 0, 700, 230);
            drawing.setColor(Color.BLACK);
            drawing.setFont(new Font("DejaVu Sans", Font.BOLD, 40));
            drawing.drawString("Alpha 001", 30, 85);
            drawing.drawString("Second line", 30, 160);
        } finally {
            drawing.dispose();
        }
        try {
            final var lines = provider.recognize(image, TesseractOcr.InputKind.TABLE_CELL);
            assertEquals(List.of("Alpha 001", "Second line"), lines.stream().map(line -> line.text()).toList());
            final var erase = image.createGraphics();
            try {
                erase.setColor(Color.WHITE);
                erase.fillRect(0, 0, 700, 230);
            } finally {
                erase.dispose();
            }
            assertTrue(provider.recognize(image, TesseractOcr.InputKind.TABLE_CELL).isEmpty());
            final var evidence = new Properties();
            evidence.setProperty("checked_at", Instant.now().toString());
            evidence.setProperty("scope", "owned multiline and blank cell rasters; no independent accuracy claim");
            evidence.setProperty("cell_segmentation", policy.cellSegmentation().name());
            evidence.setProperty("multiline_exact", "true");
            evidence.setProperty("blank_empty", "true");
            evidence.setProperty("executable_sha256", sha256(Files.readAllBytes(policy.executable())));
            evidence.setProperty("eng_model_sha256",
                    sha256(Files.readAllBytes(policy.tessdataDirectory().resolve("eng.traineddata"))));
            evidence.setProperty("java", System.getProperty("java.runtime.version"));
            try (final var output =
                            Files.newOutputStream(Path.of(report + ".cells.xml"), StandardOpenOption.CREATE_NEW)) {
                evidence.storeToXML(output, "Actual Tesseract explicit cell segmentation control");
            }
        } finally {
            image.flush();
        }
    }

    /** Returns the exact external executable/model identity without copying either binary. */
    private static String sha256(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
