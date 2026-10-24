package io.docweave.partition;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Analytical inverse-transform and ownership controls, independent of an OCR engine. */
class RotatedPdfOcrProviderTest {
    @Test
    void allQuarterTurnsMapBackExactlyAndPreservePurposeAndBorrowedPixels() throws Exception {
        final var image = new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB);
        image.setRGB(2, 3, Color.RED.getRGB());
        final double[][] boxes = {{2, 3, 8, 6}, {4, 2, 7, 8}, {12, 4, 18, 7}, {3, 12, 6, 18}};
        for (int quarter = 0; quarter < 4; quarter++) {
            final int selected = quarter;
            final var calls = new AtomicInteger();
            final PdfOcrProvider provider = new PdfOcrProvider() {
                @Override
                public List<Line> recognize(final BufferedImage input) {
                    throw new AssertionError("Purpose overload must be forwarded");
                }
                @Override
                public List<Line> recognize(final BufferedImage input, final InputKind kind) {
                    calls.incrementAndGet();
                    assertNotSame(image, input);
                    assertEquals(InputKind.TABLE_CELL, kind);
                    assertEquals(selected % 2 == 0 ? 20 : 10, input.getWidth());
                    assertEquals(selected % 2 == 0 ? 10 : 20, input.getHeight());
                    final var box = boxes[selected];
                    return List.of(new Line("value", box[0], box[1], box[2], box[3]));
                }
            };
            final var corrected = PdfOcrProvider.rotated(provider, quarter * 90, PdfOcrOptions.defaults());
            assertEquals(0, calls.get());
            assertEquals(List.of(new PdfOcrProvider.Line("value", 2, 3, 8, 6)),
                    corrected.recognize(image, PdfOcrProvider.InputKind.TABLE_CELL));
            assertEquals(Color.RED.getRGB(), image.getRGB(2, 3));
        }
    }

    @Test
    void arbitraryAngleEnvelopesAreAnalyticalAndPaddingOnlyPredictionsAreDiscarded() throws Exception {
        final var image = new BufferedImage(80, 20, BufferedImage.TYPE_INT_RGB);
        final PdfOcrProvider provider = input -> {
            assertEquals(71, input.getWidth());
            assertEquals(71, input.getHeight());
            assertEquals(Color.WHITE.getRGB(), input.getRGB(0, 0));
            return List.of(
                    new PdfOcrProvider.Line("padding", 0, 0, 1, 1), new PdfOcrProvider.Line("source", 20, 20, 30, 30));
        };
        final var lines = PdfOcrProvider.rotated(provider, 45, PdfOcrOptions.defaults()).recognize(image);
        assertEquals(1, lines.size());
        final var line = lines.getFirst();
        assertEquals("source", line.text());
        assertEquals(20 * Math.sqrt(2) - 10, line.left(), 1e-9);
        assertEquals(30 * Math.sqrt(2) - 10, line.right(), 1e-9);
        assertEquals(10 - 5 * Math.sqrt(2), line.top(), 1e-9);
        assertEquals(10 + 5 * Math.sqrt(2), line.bottom(), 1e-9);
        final PdfOcrProvider whole =
                input -> List.of(new PdfOcrProvider.Line("all", 0, 0, input.getWidth(), input.getHeight()));
        assertEquals(List.of(new PdfOcrProvider.Line("all", 0, 0, 80, 20)),
                PdfOcrProvider.rotated(whole, -45, PdfOcrOptions.defaults()).recognize(image));
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var corrected = PdfOcrProvider.rotated(provider, 405, PdfOcrOptions.defaults());
            final var tasks = java.util.stream.IntStream.range(0, 4)
                                      .mapToObj(i -> executor.submit(() -> corrected.recognize(image)))
                                      .toList();
            for (final var task : tasks) {
                assertEquals(lines, task.get());
            }
        }
    }

    @Test
    void budgetsProviderFailuresAndCancellationAreExplicit() throws Exception {
        final var image = new BufferedImage(80, 20, BufferedImage.TYPE_INT_RGB);
        final var called = new AtomicInteger();
        final PdfOcrProvider empty = input -> {
            called.incrementAndGet();
            return List.of();
        };
        final var small = new PdfOcrOptions(72, 1600, 2, 10);
        assertEquals(PartitionException.Code.OCR_LIMIT,
                assertThrows(PartitionException.class, () -> PdfOcrProvider.rotated(empty, 45, small).recognize(image))
                        .code());
        assertEquals(0, called.get());
        final var line = new PdfOcrProvider.Line("text", 1, 1, 2, 2);
        assertEquals(PartitionException.Code.OCR_LIMIT,
                assertThrows(PartitionException.class,
                        () -> PdfOcrProvider.rotated(input -> List.of(line, line, line), 0, small).recognize(image))
                        .code());
        assertEquals(PartitionException.Code.OCR_LIMIT,
                assertThrows(PartitionException.class,
                        ()
                                -> PdfOcrProvider
                                        .rotated(input
                                                -> List.of(new PdfOcrProvider.Line("01234567890", 1, 1, 2, 2)),
                                                0, small)
                                        .recognize(image))
                        .code());
        assertEquals(PartitionException.Code.OCR_FAILURE,
                assertThrows(PartitionException.class,
                        () -> PdfOcrProvider.rotated(input -> null, 0, small).recognize(image))
                        .code());
        assertEquals(PartitionException.Code.OCR_FAILURE,
                assertThrows(PartitionException.class,
                        ()
                                -> PdfOcrProvider
                                        .rotated(input
                                                -> List.of(new PdfOcrProvider.Line("bad", 0, 0, 81, 21)),
                                                0, small)
                                        .recognize(image))
                        .code());
        assertThrows(IllegalArgumentException.class, () -> PdfOcrProvider.rotated(empty, Double.NaN, small));
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> PdfOcrProvider.rotated(empty, 0, small).recognize(image));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals(0, called.get());
    }
}
