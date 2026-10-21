package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.core.ElementType;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static dev.docweave.partition.PdfTableLayoutProvider.RuledProfile.*;
import static org.junit.jupiter.api.Assertions.*;

/** Production-facade compact geometry, ownership, failure and actual padded-cell integration controls. */
class PdfCompactRuledLayoutTest {
    /** Tracks borrowed-raster disposal without changing pixel access. */
    private static final class TrackingImage extends BufferedImage {
        /** Number of disposal attempts, which must remain zero while the raster is borrowed. */
        private int flushes;

        /** Allocates an owned test raster with space for compact grids and outside words. */
        private TrackingImage() {
            super(260, 140, BufferedImage.TYPE_INT_RGB);
        }

        /** Records library disposal attempts before delegating to normal image cleanup. */
        @Override
        public void flush() {
            flushes++;
            super.flush();
        }
    }

    /** Draws an authored6x5 grid with independent blue interior markers, never OCR-generated geometry. */
    private static TrackingImage grid(final int width, final int height, final Color color, final boolean broken) {
        final var image = new TrackingImage();
        final var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 260, 140);
            graphics.setColor(color);
            for (int row = 0; row <= 6; row++) {
                graphics.drawLine(10, 10 + row * height, 10 + 5 * width, 10 + row * height);
            }
            for (int column = 0; column <= 5; column++) {
                graphics.drawLine(10 + column * width, 10, 10 + column * width, 10 + 6 * height);
            }
            if (broken) {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(35, 9, 20, 3);
            }
            graphics.setColor(Color.BLUE);
            for (int row = 0; row < 6; row++) {
                for (int column = 0; column < 5; column++) {
                    graphics.fillRect(10 + column * width + 8, 10 + row * height + 6, 1, 1);
                }
            }
        } finally {
            graphics.dispose();
        }
        return image;
    }

    /** Keeps exact owned pixel dimensions through72DPI PDF rendering. */
    private static PdfOcrOptions options(final long pixels, final int cells) {
        return new PdfOcrOptions(72, pixels, 500, 10_000, PdfOcrOptions.Selection.TEXTLESS_PAGES, 256,
                PdfOcrOptions.TableDetection.PROVIDER_LAYOUT_CELLS, cells);
    }

    @Test
    void explicitProfileKeepsDefaultsAndReturnsPositiveImmutableInteriorsWithoutTakingOwnership() throws Exception {
        final var image = grid(16, 12, Color.BLACK, false);
        final var pixels = image.getRGB(0, 0, 260, 140, null, 0, 260);
        final var options = options(40_000, 256);
        assertTrue(PdfTableLayoutProvider.ruled(TableLimits.defaults(), options, STANDARD).locate(image).isEmpty());
        assertTrue(PdfTableLayoutProvider.preferRuled(source -> List.of(), TableLimits.defaults(), options)
                        .locate(image)
                        .isEmpty());
        final var tables = PdfTableLayoutProvider.ruled(TableLimits.defaults(), options, COMPACT).locate(image);
        assertEquals(1, tables.size());
        final var table = tables.getFirst();
        assertEquals(6, table.rows());
        assertEquals(5, table.columns());
        assertEquals(30, table.anchors().size());
        assertEquals(new PdfTableLayoutProvider.Box(15, 15, 21, 17), table.anchors().getFirst().bounds());
        assertTrue(table.anchors().stream().allMatch(anchor
                -> anchor.bounds().right() - anchor.bounds().left() == 6
                           && anchor.bounds().bottom() - anchor.bounds().top() == 2));
        assertTrue(table.provenance().endsWith("rule_profile=compact_dark_v1"));
        assertThrows(UnsupportedOperationException.class, () -> tables.clear());
        assertThrows(UnsupportedOperationException.class, () -> table.anchors().clear());
        assertArrayEquals(pixels, image.getRGB(0, 0, 260, 140, null, 0, 260));
        assertEquals(0, image.flushes);
        assertEquals(32, COMPACT.minimumRunPixels());
        assertThrows(
                NullPointerException.class, () -> PdfTableLayoutProvider.ruled(TableLimits.defaults(), options, null));
    }

    @Test
    void compactRejectsBrokenPerimetersColoredRulesAndCellsBelowItsMinimum() throws Exception {
        final var layout = PdfTableLayoutProvider.ruled(TableLimits.defaults(), options(40_000, 256), COMPACT);
        for (final var image : List.of(grid(16, 12, Color.BLACK, true), grid(16, 12, new Color(160, 80, 180), false),
                     grid(16, 12, new Color(160, 160, 160), false), grid(15, 11, Color.BLACK, false))) {
            assertTrue(layout.locate(image).isEmpty());
        }
    }

    @Test
    void prospectiveSlotsAndDiscardedSecondaryAnchorsStillConsumeBudgets() throws Exception {
        final var image = grid(16, 12, Color.BLACK, false);
        final var calls = new AtomicInteger();
        final var box = new PdfTableLayoutProvider.Box(5, 5, 100, 90);
        final PdfTableLayoutProvider secondary = source -> {
            calls.incrementAndGet();
            return List.of(new PdfTableLayoutProvider.Table(
                    1, 1, box, List.of(new PdfTableLayoutProvider.Anchor(0, 0, 1, 1, box)), "owned"));
        };
        final var slotLimited =
                PdfTableLayoutProvider.preferRuled(secondary, TableLimits.defaults(), options(40_000, 29), COMPACT);
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class, () -> slotLimited.locate(image)).code());
        assertEquals(0, calls.get());
        final var aggregateLimited =
                PdfTableLayoutProvider.preferRuled(secondary, TableLimits.defaults(), options(40_000, 30), COMPACT);
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class, () -> aggregateLimited.locate(image)).code());
        assertEquals(1, calls.get());
        assertEquals(
                1, PdfTableLayoutProvider.preferRuled(secondary, TableLimits.defaults(), options(40_000, 31), COMPACT)
                           .locate(image)
                           .size());
        final var pixelLimited =
                PdfTableLayoutProvider.preferRuled(secondary, TableLimits.defaults(), options(1, 256), COMPACT);
        assertEquals(PartitionException.Code.OCR_LIMIT,
                assertThrows(PartitionException.class, () -> pixelLimited.locate(image)).code());
        assertEquals(2, calls.get());
    }

    @Test
    void cancellationAndSecondaryFailureRemainVisible() throws Exception {
        final var image = grid(16, 12, Color.BLACK, false);
        final var failure = new java.io.IOException("owned failure");
        final var layout = PdfTableLayoutProvider.preferRuled(
                source -> { throw failure; }, TableLimits.defaults(), options(40_000, 256), COMPACT);
        final var reported = assertThrows(PartitionException.class, () -> layout.locate(image));
        assertEquals(PartitionException.Code.OCR_FAILURE, reported.code());
        assertSame(failure, reported.getCause());
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class, () -> layout.locate(image));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void sharedCompactStrategyKeepsConcurrentCallStateIndependent() throws Exception {
        final var image = grid(16, 12, Color.BLACK, false);
        final var layout = PdfTableLayoutProvider.ruled(TableLimits.defaults(), options(40_000, 256), COMPACT);
        final var expected = layout.locate(image);
        try (final var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            final var futures =
                    IntStream.range(0, 16).mapToObj(index -> workers.submit(() -> layout.locate(image))).toList();
            for (final var future : futures) {
                assertEquals(expected, future.get());
            }
        }
    }

    @Test
    void actualPdfCellPipelinePreservesTinyInteriorsPaddingMarkersAndUnownedWords() throws Exception {
        final var image = grid(16, 12, Color.BLACK, false);
        final var options = options(40_000, 256);
        final var layout = PdfTableLayoutProvider.ruled(TableLimits.defaults(), options, COMPACT);
        final PdfWordOcrProvider words = (source, purpose)
                -> List.of(new PdfWordOcrProvider.WordLine(new PdfOcrProvider.Line("outside", 150, 100, 180, 110),
                        List.of(new PdfWordOcrProvider.Word("outside", 150, 100, 180, 110))));
        final var calls = new AtomicInteger();
        final PdfOcrProvider cells = source -> {
            calls.incrementAndGet();
            assertEquals(30, source.getWidth());
            assertEquals(26, source.getHeight());
            assertEquals(Color.BLUE.getRGB(), source.getRGB(15, 13));
            assertEquals(Color.WHITE.getRGB(), source.getRGB(0, 0));
            return List.of(new PdfOcrProvider.Line("cell", 12, 12, 18, 14));
        };
        final var processor = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.createWithCellOcr(
                PdfOptions.defaults().withTableCellCoordinates(true), options, words, layout, cells))));
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(260, 140));
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                content.drawImage(LosslessFactory.createFromImage(document, image), 0, 0, 260, 140);
            }
            document.save(output);
            final var result = processor.partition(DocumentSource.bytes(output.toByteArray(), "compact.pdf"));
            assertEquals(30, calls.get());
            final var tables =
                    result.elements().stream().filter(element -> element.type() == ElementType.Table).toList();
            assertEquals(1, tables.size());
            final var html = tables.getFirst().metadata().get("text_as_html").toString();
            assertEquals(30, html.split("<td>cell</td>", -1).length - 1);
            assertTrue(tables.getFirst().metadata().containsKey("pdf_table_cells"));
            assertTrue(result.elements().stream().anyMatch(element -> element.text().equals("outside")));
            assertEquals(1, result.elements().stream().filter(element -> element.type() == ElementType.Image).count());
        }
    }
}
