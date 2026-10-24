package io.docweave.partition;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit magnification preserves source pixels, real word capability and per-call resource contracts. */
class PdfOcrScalingTest {
    @Test
    void integerBlocksPurposeAndInverseLinesLeaveBorrowedPixelsUntouched() throws Exception {
        final var source = new TrackingImage();
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 2; x++) {
                source.setRGB(x, y, 0xff000000 | (y * 2 + x + 1) * 0x112233);
            }
        }
        final var before = source.getRGB(0, 0, 2, 3, null, 0, 2);
        for (final int multiplier : List.of(1, 2, 3, 8)) {
            final PdfOcrProvider delegate = new PdfOcrProvider() {
                @Override
                public List<Line> recognize(final BufferedImage image) {
                    throw new AssertionError("Context must reach the provider");
                }

                @Override
                public List<Line> recognize(final BufferedImage image, final InputKind kind) {
                    assertEquals(InputKind.TABLE_CELL, kind);
                    assertEquals(2 * multiplier, image.getWidth());
                    assertEquals(3 * multiplier, image.getHeight());
                    if (multiplier == 1) {
                        assertSame(source, image);
                    } else {
                        assertNotSame(source, image);
                    }
                    for (int y = 0; y < image.getHeight(); y++) {
                        for (int x = 0; x < image.getWidth(); x++) {
                            assertEquals(source.getRGB(x / multiplier, y / multiplier), image.getRGB(x, y));
                        }
                    }
                    return List.of(new Line("alpha", 0, 0, image.getWidth(), image.getHeight()));
                }
            };
            final var result = PdfOcrProvider.upscaled(delegate, multiplier, limits(10_000, 1, 5))
                                       .recognize(source, PdfOcrProvider.InputKind.TABLE_CELL);
            assertEquals(List.of(new PdfOcrProvider.Line("alpha", 0, 0, 2, 3)), result);
            assertThrows(UnsupportedOperationException.class, () -> result.add(result.getFirst()));
        }
        assertEquals(0, source.flushes);
        assertArrayEquals(before, source.getRGB(0, 0, 2, 3, null, 0, 2));
    }

    @Test
    void wordFactoryRetainsActualTokensAndLineFactoryRetainsOnlyLineCapability() throws Exception {
        final var purpose = new AtomicReference<PdfOcrProvider.InputKind>();
        final PdfWordOcrProvider delegate = (image, kind) -> {
            purpose.set(kind);
            assertEquals(6, image.getWidth());
            assertEquals(9, image.getHeight());
            return List.of(new PdfWordOcrProvider.WordLine(new PdfOcrProvider.Line("red blue", 0, 0, 6, 9),
                    List.of(new PdfWordOcrProvider.Word("red", 0, 0, 3, 3),
                            new PdfWordOcrProvider.Word("blue", 3, 3, 6, 9))));
        };
        final var source = new TrackingImage();
        assertCode(PartitionException.Code.OCR_LIMIT,
                () -> PdfWordOcrProvider.upscaled(delegate, 3, limits(54, 1, 7)).recognize(source));
        final var words = PdfWordOcrProvider.upscaled(delegate, 3, limits(54, 1, 8));
        final var detailed = words.recognizeWords(source, PdfOcrProvider.InputKind.TABLE_CELL);
        assertEquals(PdfOcrProvider.InputKind.TABLE_CELL, purpose.get());
        assertEquals(List.of(new PdfWordOcrProvider.Word("red", 0, 0, 1, 1),
                             new PdfWordOcrProvider.Word("blue", 1, 1, 2, 3)),
                detailed.getFirst().words());
        assertEquals(new PdfOcrProvider.Line("red blue", 0, 0, 2, 3), detailed.getFirst().line());
        assertEquals(List.of(detailed.getFirst().line()), words.recognize(source));
        assertEquals(PdfOcrProvider.InputKind.PAGE, purpose.get());
        final var lines = PdfOcrProvider.upscaled(delegate, 3, limits(54, 1, 8));
        assertFalse(lines instanceof PdfWordOcrProvider);
        assertEquals(List.of(detailed.getFirst().line()), lines.recognize(source));
        assertEquals(0, source.flushes);
    }

    @Test
    void preflightRawOutputInvalidGeometryAndCancellationRemainTyped() throws Exception {
        final var calls = new AtomicInteger();
        final var source = new TrackingImage();
        final PdfOcrProvider provider = image -> {
            calls.incrementAndGet();
            return List.of(new PdfOcrProvider.Line("alpha", 0, 0, 1, 1));
        };
        assertThrows(IllegalArgumentException.class, () -> PdfOcrProvider.upscaled(provider, 0, limits(100, 1, 5)));
        assertThrows(IllegalArgumentException.class,
                () -> PdfWordOcrProvider.upscaled((image, kind) -> List.of(), 9, limits(100, 1, 5)));
        assertCode(PartitionException.Code.OCR_LIMIT,
                () -> PdfOcrProvider.upscaled(provider, 3, limits(53, 1, 5)).recognize(source));
        assertCode(PartitionException.Code.OCR_LIMIT,
                () -> PdfOcrProvider.upscaled(provider, 1, limits(5, 1, 5)).recognize(source));
        assertEquals(0, calls.get());
        assertCode(PartitionException.Code.OCR_LIMIT,
                ()
                        -> PdfOcrProvider
                                .upscaled(image
                                        -> List.of(new PdfOcrProvider.Line("", 0, 0, 1, 1),
                                                new PdfOcrProvider.Line("", 0, 0, 1, 1)),
                                        3, limits(54, 1, 5))
                                .recognize(source));
        assertCode(PartitionException.Code.OCR_LIMIT,
                () -> PdfOcrProvider.upscaled(provider, 3, limits(54, 1, 4)).recognize(source));
        assertCode(PartitionException.Code.OCR_FAILURE,
                () -> PdfOcrProvider.upscaled(image -> null, 3, limits(54, 1, 5)).recognize(source));
        assertCode(PartitionException.Code.OCR_FAILURE,
                ()
                        -> PdfOcrProvider
                                .upscaled(
                                        image -> List.of(new PdfOcrProvider.Line("x", 0, 0, 7, 1)), 3, limits(54, 1, 5))
                                .recognize(source));
        assertCode(PartitionException.Code.OCR_FAILURE,
                ()
                        -> PdfOcrProvider
                                .upscaled(image
                                        -> List.of(new PdfOcrProvider.Line("x", 0, 0, Double.MIN_VALUE, 1)),
                                        3, limits(54, 1, 5))
                                .recognize(source));
        final var expected = new IOException("owned recognition failure");
        final var failure = assertThrows(PartitionException.class,
                () -> PdfOcrProvider.upscaled(image -> { throw expected; }, 3, limits(54, 1, 5)).recognize(source));
        assertEquals(PartitionException.Code.OCR_FAILURE, failure.code());
        assertSame(expected, failure.getCause());
        final int before = calls.get();
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class,
                    () -> PdfOcrProvider.upscaled(provider, 3, limits(54, 1, 5)).recognize(source));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals(before, calls.get());
        assertEquals(0, source.flushes);
    }

    @Test
    void reusableDecoratorKeepsVirtualThreadCallBudgetsAndRastersIndependent() throws Exception {
        final var source = new TrackingImage();
        final var calls = new AtomicInteger();
        final var provider = PdfOcrProvider.upscaled(image -> {
            calls.incrementAndGet();
            return List.of(new PdfOcrProvider.Line("alpha", 0, 0, image.getWidth(), image.getHeight()));
        }, 3, limits(54, 1, 5));
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var pending = new ArrayList<Future<List<PdfOcrProvider.Line>>>();
            for (int index = 0; index < 20; index++) {
                pending.add(executor.submit(() -> provider.recognize(source)));
            }
            for (final var result : pending) {
                assertEquals(List.of(new PdfOcrProvider.Line("alpha", 0, 0, 2, 3)), result.get());
            }
        }
        assertEquals(20, calls.get());
        assertEquals(0, source.flushes);
    }

    /** Builds small independent per-call ceilings without enabling PDF table interpretation. */
    private static PdfOcrOptions limits(final long pixels, final int lines, final int characters) {
        return new PdfOcrOptions(72, pixels, lines, characters);
    }

    /** Checks stable machine-readable failure classification without matching provider text. */
    private static void assertCode(final PartitionException.Code code, final Executable operation) {
        assertEquals(code, assertThrows(PartitionException.class, operation).code());
    }

    /** Detects accidental release of a caller-owned image, including the no-allocation multiplier 1 path. */
    private static final class TrackingImage extends BufferedImage {
        private int flushes;

        private TrackingImage() {
            super(2, 3, BufferedImage.TYPE_INT_RGB);
        }

        @Override
        public void flush() {
            flushes++;
            super.flush();
        }
    }
}
