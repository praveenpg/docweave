package io.docweave.ocr;

import io.docweave.partition.PdfOcrProvider;
import io.docweave.partition.PdfWordOcrProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TesseractCellImageTest {
    @Test
    void edgeRulesAreWhitenedWithoutMovingOrMutatingInteriorPixels() throws Exception {
        final var source = white(240, 120);
        final var graphics = source.createGraphics();
        try {
            graphics.setColor(Color.BLACK);
            graphics.drawRect(12, 12, 215, 95);
            graphics.fillRect(80, 45, 20, 12);
            graphics.drawLine(40, 65, 190, 65);
        } finally {
            graphics.dispose();
        }
        final var before = source.getRGB(0, 0, 240, 120, null, 0, 240);
        try (final var prepared = new TesseractCellImage(source, true, deadline())) {
            assertNotSame(source, prepared.image());
            assertEquals(240, prepared.image().getWidth());
            assertEquals(120, prepared.image().getHeight());
            assertEquals(Color.WHITE.getRGB(), prepared.image().getRGB(12, 12));
            assertEquals(Color.WHITE.getRGB(), prepared.image().getRGB(227, 107));
            assertEquals(Color.BLACK.getRGB(), prepared.image().getRGB(85, 50));
            assertEquals(Color.BLACK.getRGB(), prepared.image().getRGB(80, 65));
        }
        assertArrayEquals(before, source.getRGB(0, 0, 240, 120, null, 0, 240));
    }

    @Test
    void unchangedPolicySmallCellsSparseRulesAndInteriorRulesKeepBorrowedImage() throws Exception {
        final var source = white(240, 120);
        final var graphics = source.createGraphics();
        try {
            graphics.setColor(Color.BLACK);
            graphics.drawLine(0, 60, 239, 60);
            graphics.drawLine(0, 12, 150, 12);
        } finally {
            graphics.dispose();
        }
        for (final boolean remove : new boolean[] {false, true}) {
            try (final var prepared = new TesseractCellImage(source, remove, deadline())) {
                assertSame(source, prepared.image());
            }
        }
        final var narrow = white(63, 120);
        try (final var prepared = new TesseractCellImage(narrow, true, deadline())) {
            assertSame(narrow, prepared.image());
        }
    }

    @Test
    void exactDensityBoundaryIsExplicitAndTransparentBlackDoesNotCount() throws Exception {
        final var source = white(100, 100);
        for (int x = 0; x < 80; x++) {
            source.setRGB(x, 10, Color.BLACK.getRGB());
            if (x < 79) {
                source.setRGB(x, 11, Color.BLACK.getRGB());
            }
        }
        try (final var prepared = new TesseractCellImage(source, true, deadline())) {
            assertEquals(Color.WHITE.getRGB(), prepared.image().getRGB(30, 10));
            assertEquals(Color.BLACK.getRGB(), prepared.image().getRGB(30, 11));
        }
        final var transparent = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        try (final var prepared = new TesseractCellImage(transparent, true, deadline())) {
            assertSame(transparent, prepared.image());
        }
    }

    @Test
    void cancellationPreservesInterruptAndOptionsRetainExplicitPolicyAcrossCopies() throws Exception {
        final var source = white(100, 100);
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class, () -> new TesseractCellImage(source, true, deadline()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals(TesseractOptions.CellBorders.RETAIN, TesseractOptions.defaults().cellBorders());
        final var options = TesseractOptions.defaults()
                                    .withCellBorders(TesseractOptions.CellBorders.REMOVE_EDGE_RULES)
                                    .withCellSegmentation(TesseractOptions.CellSegmentation.SINGLE_BLOCK)
                                    .withPageSegmentation(TesseractOptions.PageSegmentation.SINGLE_BLOCK);
        assertEquals(TesseractOptions.CellBorders.REMOVE_EDGE_RULES, options.cellBorders());
        assertThrows(NullPointerException.class, () -> options.withCellBorders(null));
    }

    @Test
    void managedAdapterChangesOnlyCellPixelsAndRetainsActualWordGeometry(@TempDir final Path directory)
            throws Exception {
        final var source = white(100, 100);
        for (int x = 0; x < 100; x++) {
            source.setRGB(x, 12, Color.BLACK.getRGB());
        }
        final var defaults = TesseractOptions.defaults();
        final var options = new TesseractOptions(defaults.executable(), null, List.of("eng", "deu"), defaults.timeout(),
                1, defaults.maxImagePixels(), defaults.maxPngBytes(), defaults.maxOutputBytes(), defaults.maxLines(),
                defaults.maxCharacters())
                                    .withCellBorders(TesseractOptions.CellBorders.REMOVE_EDGE_RULES);
        final PdfWordOcrProvider provider = new DefaultTesseractOcr(
                options, List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                 "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"),
                                 OcrChildProcess.class.getName(), "cell-edge", directory.toString()));
        assertEquals("edge-black", provider.recognize(source, PdfOcrProvider.InputKind.PAGE).getLast().text());
        assertEquals("edge-white", provider.recognize(source, PdfOcrProvider.InputKind.TABLE_CELL).getLast().text());
        final var detailed = provider.recognizeWords(source, PdfOcrProvider.InputKind.TABLE_CELL).getLast();
        assertEquals("edge-white", detailed.line().text());
        assertEquals(10, detailed.words().getFirst().left());
        assertEquals(30, detailed.words().getFirst().top());
        assertEquals(Color.BLACK.getRGB(), source.getRGB(30, 12));
    }

    /** Creates an owned white raster for pixel/ownership controls, without fonts or native OCR. */
    private static BufferedImage white(final int width, final int height) {
        final var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        final var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    /** Supplies a generous local budget; cancellation is exercised independently of wall-clock speed. */
    private static OcrDeadline deadline() {
        return new OcrDeadline(Duration.ofSeconds(10));
    }
}
