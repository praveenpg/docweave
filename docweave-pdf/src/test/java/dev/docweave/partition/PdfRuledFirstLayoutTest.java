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
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PdfRuledFirstLayoutTest {
    private static final class TrackingImage extends BufferedImage {
        private int flushes;

        private TrackingImage() {
            super(400, 260, BufferedImage.TYPE_INT_RGB);
        }

        @Override
        public void flush() {
            flushes++;
            super.flush();
        }
    }

    private static PdfOcrOptions options() {
        return new PdfOcrOptions(72, 120_000, 500, 10_000)
                .withTableDetection(PdfOcrOptions.TableDetection.PROVIDER_LAYOUT_CELLS);
    }

    private static PdfOcrOptions capped(final long pixels, final int cells) {
        final var base = options();
        return new PdfOcrOptions(base.dpi(), pixels, base.maxLines(), base.maxCharacters(), base.selection(),
                base.maxRegions(), base.tableDetection(), cells);
    }

    private static BufferedImage image(final boolean rules) {
        final var image = new TrackingImage();
        final var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 400, 260);
            if (rules) {
                graphics.setColor(Color.BLACK);
                for (final int position : new int[] {20, 120, 220}) {
                    graphics.drawLine(20, position, 220, position);
                    graphics.drawLine(position, 20, position, 220);
                }
            }
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static PdfTableLayoutProvider.Table table(final double left, final double right) {
        final var box = new PdfTableLayoutProvider.Box(left, 20, right, 220);
        return new PdfTableLayoutProvider.Table(
                1, 1, box, List.of(new PdfTableLayoutProvider.Anchor(0, 0, 1, 1, box)), "owned-secondary");
    }

    @Test
    void rulesReplaceOnlyOverlappingClaimsAndRetainDisjointTablesWithoutChangingPixels() throws Exception {
        final var raster = image(true);
        final var pixels = raster.getRGB(0, 0, 400, 260, null, 0, 400);
        final var calls = new AtomicInteger();
        final var disjoint = table(280, 380);
        final var provider = PdfTableLayoutProvider.preferRuled(source -> {
            calls.incrementAndGet();
            assertSame(raster, source);
            return List.of(table(10, 230), disjoint);
        }, TableLimits.defaults(), options());
        assertEquals(0, calls.get());
        final var result = provider.locate(raster);
        assertEquals(1, calls.get());
        assertEquals(2, result.size());
        assertSame(disjoint, result.get(1));
        final var ruled = result.getFirst();
        assertEquals(2, ruled.rows());
        assertEquals(2, ruled.columns());
        assertEquals(4, ruled.anchors().size());
        assertEquals(new PdfTableLayoutProvider.Box(25, 25, 115, 115), ruled.anchors().getFirst().bounds());
        assertTrue(ruled.provenance().contains("inset_pixels=5"));
        assertArrayEquals(pixels, raster.getRGB(0, 0, 400, 260, null, 0, 400));
        assertEquals(0, ((TrackingImage) raster).flushes);
        assertThrows(UnsupportedOperationException.class, () -> result.add(disjoint));
    }

    @Test
    void absentRulesPreserveSecondaryValuesAndOrder() throws Exception {
        final var expected = List.of(table(280, 380), table(20, 220));
        final var provider = PdfTableLayoutProvider.preferRuled(source -> expected, TableLimits.defaults(), options());
        assertEquals(expected, provider.locate(image(false)));
    }

    @Test
    void borderTouchingTableRemainsDisjoint() throws Exception {
        final var touching = table(220, 380);
        final var provider =
                PdfTableLayoutProvider.preferRuled(source -> List.of(touching), TableLimits.defaults(), options());
        final var result = provider.locate(image(true));
        assertEquals(2, result.size());
        assertSame(touching, result.get(1));
    }

    @Test
    void overlappingInvalidSecondaryClaimsAreNotHiddenByRulePreference() {
        final var raster = image(true);
        final var box = new PdfTableLayoutProvider.Box(20, 20, 220, 220);
        final var incomplete = new PdfTableLayoutProvider.Table(
                2, 2, box, List.of(new PdfTableLayoutProvider.Anchor(0, 0, 1, 1, box)), "invalid-incomplete");
        final var invalid = new ArrayList<List<PdfTableLayoutProvider.Table>>();
        invalid.add(Arrays.asList((PdfTableLayoutProvider.Table) null));
        invalid.add(List.of(incomplete));
        invalid.add(List.of(table(390, 450)));
        invalid.add(List.of(table(20, 220), table(30, 230)));
        for (final var claims : invalid) {
            final var provider =
                    PdfTableLayoutProvider.preferRuled(source -> claims, TableLimits.defaults(), options());
            assertEquals(PartitionException.Code.OCR_FAILURE,
                    assertThrows(PartitionException.class, () -> provider.locate(raster)).code());
        }
    }

    @Test
    void rawDiscardedAnchorsStillConsumeCombinedBudgetAndPixelsFailBeforeSecondaryCall() {
        final var raster = image(true);
        final var calls = new AtomicInteger();
        final PdfTableLayoutProvider secondary = source -> {
            calls.incrementAndGet();
            return List.of(table(10, 230), table(280, 380));
        };
        final var combined = PdfTableLayoutProvider.preferRuled(secondary, TableLimits.defaults(), capped(120_000, 5));
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class, () -> combined.locate(raster)).code());
        assertEquals(1, calls.get()); // Five selected anchors would fit, but six raw anchors do not.
        final var pixels = PdfTableLayoutProvider.preferRuled(secondary, TableLimits.defaults(), capped(100_000, 10));
        assertEquals(PartitionException.Code.OCR_LIMIT,
                assertThrows(PartitionException.class, () -> pixels.locate(raster)).code());
        assertEquals(1, calls.get());
        final var dimensions = PdfTableLayoutProvider.preferRuled(secondary, new TableLimits(1, 2, 10, 100), options());
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class, () -> dimensions.locate(raster)).code());
        assertEquals(1, calls.get());
    }

    @Test
    void secondaryFailureAndInterruptionRemainVisible() {
        final var raster = image(true);
        final var cause = new IOException("owned provider failure");
        final var failing =
                PdfTableLayoutProvider.preferRuled(source -> { throw cause; }, TableLimits.defaults(), options());
        assertSame(cause, assertThrows(PartitionException.class, () -> failing.locate(raster)).getCause());
        final var interrupted = new InterruptedIOException("owned interrupted call");
        final var cancelling =
                PdfTableLayoutProvider.preferRuled(source -> { throw interrupted; }, TableLimits.defaults(), options());
        assertSame(interrupted, assertThrows(InterruptedIOException.class, () -> cancelling.locate(raster)));
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> failing.locate(raster));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void sharedFacadeHasIndependentConcurrentGeometryBudgets() throws Exception {
        final var raster = image(true);
        final var provider = PdfTableLayoutProvider.preferRuled(
                source -> List.of(table(10, 230)), TableLimits.defaults(), options());
        final var expected = provider.locate(raster);
        try (final var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            final var futures = java.util.stream.IntStream.range(0, 20)
                                        .mapToObj(index -> workers.submit(() -> provider.locate(raster)))
                                        .toList();
            for (final var future : futures) {
                assertEquals(expected, future.get());
            }
        }
    }

    @Test
    void closedRuleInteriorsFeedCellOcrAndKeepUnownedWords() throws Exception {
        final var raster = image(true);
        final var options = options();
        final var layout =
                PdfTableLayoutProvider.preferRuled(source -> List.of(table(10, 230)), TableLimits.defaults(), options);
        final PdfWordOcrProvider words = (source, kind)
                -> List.of(new PdfWordOcrProvider.WordLine(new PdfOcrProvider.Line("A outside", 40, 40, 340, 250),
                        List.of(new PdfWordOcrProvider.Word("A", 40, 40, 60, 60),
                                new PdfWordOcrProvider.Word("outside", 300, 240, 340, 250))));
        final var calls = new AtomicInteger();
        final PdfOcrProvider cells = source -> {
            calls.incrementAndGet();
            assertEquals(114, source.getWidth());
            assertEquals(114, source.getHeight());
            assertEquals(Color.WHITE.getRGB(), source.getRGB(12, 12));
            return List.of(new PdfOcrProvider.Line("cell", 12, 12, 30, 30));
        };
        final var processor = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.createWithCellOcr(
                PdfOptions.defaults().withTableCellCoordinates(true), options, words, layout, cells))));
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(400, 260));
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                content.drawImage(LosslessFactory.createFromImage(document, raster), 0, 0, 400, 260);
            }
            document.save(output);
            final var result = processor.partition(DocumentSource.bytes(output.toByteArray(), "owned-rules.pdf"));
            assertEquals(4, calls.get());
            assertEquals(1, result.elements().stream().filter(e -> e.type() == ElementType.Table).count());
            assertTrue(result.elements().stream().anyMatch(e -> e.text().equals("outside")));
            assertEquals(1, result.elements().stream().filter(e -> e.type() == ElementType.Image).count());
            final var table =
                    result.elements().stream().filter(e -> e.type() == ElementType.Table).findFirst().orElseThrow();
            assertEquals(
                    "<table><tbody><tr><td>cell</td><td>cell</td></tr><tr><td>cell</td><td>cell</td></tr></tbody></table>",
                    table.metadata().get("text_as_html"));
            assertTrue(table.metadata().containsKey("pdf_table_cells"));
        }
    }
}
