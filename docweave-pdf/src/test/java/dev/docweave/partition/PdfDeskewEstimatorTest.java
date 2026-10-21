package dev.docweave.partition;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.InterruptedIOException;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/** Owned projection controls are not a general page-layout or calibrated confidence oracle. */
class PdfDeskewEstimatorTest {
    @Test
    void estimatesSmallPositiveAndNegativeAnglesWithoutChangingSourcePixels() throws Exception {
        final var estimator = PdfDeskewEstimator.create(2_000_000);
        for (final double angle : new double[] {-7.5, -3, 3, 7.5}) {
            final var image = fixture(angle);
            try {
                final int original = image.getRGB(0, 0);
                final var estimate = estimator.estimate(image);
                assertEquals(PdfDeskewEstimator.Status.ACCEPTED, estimate.status(), estimate.toString());
                assertEquals(-angle, estimate.clockwiseDegrees(), 0.5);
                assertTrue(estimate.bestScore() >= 1.15 * estimate.baselineScore());
                assertTrue(estimate.bestScore() >= 1.05 * estimate.separatedScore());
                assertEquals(original, image.getRGB(0, 0));
                try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    final var tasks = java.util.stream.IntStream.range(0, 4)
                                              .mapToObj(i -> executor.submit(() -> estimator.estimate(image)))
                                              .toList();
                    for (final var task : tasks) {
                        assertEquals(estimate, task.get());
                    }
                }
            } finally {
                image.flush();
            }
        }
    }

    @Test
    void blankDenseUprightAndOutOfRangeInputsDoNotSelectACorrection() throws Exception {
        final var estimator = PdfDeskewEstimator.create(2_000_000);
        final var blank = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        final var graphics = blank.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 400, 400);
        } finally {
            graphics.dispose();
        }
        assertEquals(PdfDeskewEstimator.Status.INSUFFICIENT_INK, estimator.estimate(blank).status());
        final var transparent = new BufferedImage(400, 400, BufferedImage.TYPE_INT_ARGB);
        assertEquals(PdfDeskewEstimator.Status.INSUFFICIENT_INK, estimator.estimate(transparent).status());
        final var line = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        final var drawing = line.createGraphics();
        try {
            drawing.setColor(Color.WHITE);
            drawing.fillRect(0, 0, 400, 400);
            drawing.setColor(Color.BLACK);
            drawing.setStroke(new java.awt.BasicStroke(3));
            drawing.drawLine(40, 250, 360, 220);
        } finally {
            drawing.dispose();
        }
        assertNotEquals(PdfDeskewEstimator.Status.ACCEPTED, estimator.estimate(line).status());
        final var dense = new BufferedImage(400, 400, BufferedImage.TYPE_INT_RGB);
        assertEquals(PdfDeskewEstimator.Status.AMBIGUOUS, estimator.estimate(dense).status());
        for (final double angle : new double[] {0, 12}) {
            final var estimate = estimator.estimate(fixture(angle));
            assertNotEquals(PdfDeskewEstimator.Status.ACCEPTED, estimate.status(), estimate.toString());
            assertEquals(0, estimate.clockwiseDegrees());
        }
    }

    @Test
    void inputLimitsAndCancellationAreExplicit() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> PdfDeskewEstimator.create(0));
        assertEquals(PartitionException.Code.OCR_LIMIT,
                assertThrows(PartitionException.class,
                        ()
                                -> PdfDeskewEstimator.create(1).estimate(
                                        new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)))
                        .code());
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class,
                    () -> PdfDeskewEstimator.create(100).estimate(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void decoratorMapsAcceptedPageGeometryAndForwardsCellPurposeWithoutAnalysis() throws Exception {
        final var image = fixture(7.5);
        final var limits = new PdfOcrOptions(72, 2_000_000, 10, 100);
        final var calls = new java.util.concurrent.atomic.AtomicInteger();
        final PdfOcrProvider provider = new PdfOcrProvider() {
            @Override
            public java.util.List<Line> recognize(final BufferedImage input) {
                throw new AssertionError("Purpose must be forwarded");
            }
            @Override
            public java.util.List<Line> recognize(final BufferedImage input, final InputKind kind) {
                calls.incrementAndGet();
                if (kind == InputKind.TABLE_CELL) {
                    assertSame(image, input);
                } else {
                    assertNotSame(image, input);
                }
                return java.util.List.of(new Line("known", 0, 0, input.getWidth(), input.getHeight()));
            }
        };
        final var corrected = PdfOcrProvider.deskewed(provider, limits);
        assertEquals(0, calls.get());
        final var page = corrected.recognize(image).getFirst();
        assertEquals(image.getWidth(), page.right(), 1e-9);
        assertEquals(image.getHeight(), page.bottom(), 1e-9);
        assertEquals(0, page.left(), 1e-9);
        assertEquals(0, page.top(), 1e-9);
        corrected.recognize(image, PdfOcrProvider.InputKind.TABLE_CELL);
        assertEquals(2, calls.get());
    }

    private static BufferedImage fixture(final double angle) {
        final var source = new BufferedImage(1000, 400, BufferedImage.TYPE_INT_RGB);
        final var drawing = source.createGraphics();
        try {
            drawing.setColor(Color.WHITE);
            drawing.fillRect(0, 0, 1000, 400);
            drawing.setColor(Color.BLACK);
            drawing.setFont(new Font("DejaVu Sans", Font.PLAIN, 26));
            drawing.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            for (int line = 0; line < 5; line++) {
                drawing.drawString(
                        "DocWeave projection baseline sample " + line + " with several words", 40, 70 + line * 60);
            }
        } finally {
            drawing.dispose();
        }
        final var transform = AffineTransform.getRotateInstance(Math.toRadians(angle));
        final var bounds = transform.createTransformedShape(new java.awt.Rectangle(1000, 400)).getBounds2D();
        final var result = new BufferedImage(
                (int) Math.ceil(bounds.getWidth()), (int) Math.ceil(bounds.getHeight()), BufferedImage.TYPE_INT_RGB);
        final var graphics = result.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, result.getWidth(), result.getHeight());
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            final var position = AffineTransform.getTranslateInstance(-bounds.getMinX(), -bounds.getMinY());
            position.concatenate(transform);
            graphics.drawImage(source, position, null);
        } finally {
            graphics.dispose();
            source.flush();
        }
        return result;
    }
}
