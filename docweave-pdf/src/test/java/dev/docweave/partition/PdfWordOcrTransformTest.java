package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.core.ElementType;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PdfWordOcrTransformTest {
    @Test
    void quarterTurnsPreserveActualWordsPixelFramePurposeAndBorrowedOwnership() throws Exception {
        final var source = new BufferedImage(100, 60, BufferedImage.TYPE_INT_RGB);
        source.setRGB(0, 0, Color.RED.getRGB());
        final var before = source.getRGB(0, 0, 100, 60, null, 0, 100);
        final var pixel = List.of(List.of(0, 0), List.of(59, 0), List.of(99, 59), List.of(0, 99));
        final var first = List.of(List.of(5d, 5d, 12d, 15d), List.of(5d, 48d, 15d, 55d), List.of(88d, 45d, 95d, 55d),
                List.of(85d, 5d, 95d, 12d));
        for (int index = 0; index < 4; index++) {
            final int turn = index;
            final var calls = new AtomicInteger();
            final PdfWordOcrProvider provider = (image, kind) -> {
                calls.incrementAndGet();
                assertEquals(turn % 2 == 0 ? 100 : 60, image.getWidth());
                assertEquals(turn % 2 == 0 ? 60 : 100, image.getHeight());
                assertEquals(Color.RED.getRGB(), image.getRGB(pixel.get(turn).get(0), pixel.get(turn).get(1)));
                return List.of(detailed("A", 5, 5, 12, 15));
            };
            final var decorated = PdfWordOcrProvider.rotated(provider, index * 90, limits());
            for (final var kind : PdfOcrProvider.InputKind.values()) {
                final var output = decorated.recognizeWords(source, kind);
                final var word = output.getFirst().words().getFirst();
                assertEquals("A", word.text());
                assertEquals(first.get(index), List.of(word.left(), word.top(), word.right(), word.bottom()));
                assertThrows(UnsupportedOperationException.class, () -> output.clear());
            }
            assertEquals(2, calls.get());
            assertArrayEquals(before, source.getRGB(0, 0, 100, 60, null, 0, 100));
        }
    }

    @Test
    void arbitraryAngleClipsActualTokenPolygonsAndRebuildsTextWithoutInventingBoxes() throws Exception {
        final var source = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        final PdfWordOcrProvider provider = (image, kind)
                -> List.of(new PdfWordOcrProvider.WordLine(new PdfOcrProvider.Line("padding inside", 0, 0, 75, 75),
                        List.of(new PdfWordOcrProvider.Word("padding", 0, 0, 4, 4),
                                new PdfWordOcrProvider.Word("inside", 65, 65, 75, 75))));
        final var output = PdfWordOcrProvider.rotated(provider, 45, limits())
                                   .recognizeWords(source, PdfOcrProvider.InputKind.PAGE);
        assertEquals(1, output.size());
        assertEquals("inside", output.getFirst().line().text());
        assertEquals(1, output.getFirst().words().size());
        final var word = output.getFirst().words().getFirst();
        assertEquals(41.92388155425118, word.left(), 1e-10);
        assertEquals(56.06601717798212, word.right(), 1e-10);
        assertEquals(42.928932188134524, word.top(), 1e-10);
        assertEquals(57.071067811865476, word.bottom(), 1e-10);
    }

    @Test
    void malformedOutputRawBudgetsAndPreentryCancellationFailBeforeDiscardingWords() throws Exception {
        final var source = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        final PdfWordOcrProvider padding = (image, kind) -> List.of(detailed("padding", 0, 0, 4, 4));
        final var limited = new PdfOcrOptions(72, 100_000, 10, 3);
        assertEquals(PartitionException.Code.OCR_LIMIT,
                assertThrows(PartitionException.class,
                        ()
                                -> PdfWordOcrProvider.rotated(padding, 45, limited)
                                        .recognizeWords(source, PdfOcrProvider.InputKind.PAGE))
                        .code());
        final PdfWordOcrProvider nulls = (image, kind) -> null;
        final PdfWordOcrProvider nullEntry = (image, kind) -> Arrays.asList((PdfWordOcrProvider.WordLine) null);
        final PdfWordOcrProvider outside = (image, kind) -> List.of(detailed("bad", 0, 0, 101, 101));
        for (final var invalid : List.of(nulls, nullEntry, outside)) {
            assertEquals(PartitionException.Code.OCR_FAILURE,
                    assertThrows(PartitionException.class,
                            ()
                                    -> PdfWordOcrProvider.rotated(invalid, 0, limits())
                                            .recognizeWords(source, PdfOcrProvider.InputKind.PAGE))
                            .code());
        }
        final var calls = new AtomicInteger();
        final PdfWordOcrProvider provider = (image, kind) -> {
            calls.incrementAndGet();
            return List.of(detailed("ok", 10, 10, 20, 20));
        };
        final var expandedLimit = new PdfOcrOptions(72, 12_000, 100, 1000);
        assertEquals(PartitionException.Code.OCR_LIMIT,
                assertThrows(PartitionException.class,
                        ()
                                -> PdfWordOcrProvider.rotated(provider, 45, expandedLimit)
                                        .recognizeWords(source, PdfOcrProvider.InputKind.PAGE))
                        .code());
        assertEquals(0, calls.get());
        final PdfWordOcrProvider two =
                (image, kind) -> List.of(detailed("one", 0, 0, 4, 4), detailed("two", 0, 0, 4, 4));
        assertEquals(PartitionException.Code.OCR_LIMIT,
                assertThrows(PartitionException.class,
                        ()
                                -> PdfWordOcrProvider.rotated(two, 45, new PdfOcrOptions(72, 100_000, 1, 1000))
                                        .recognizeWords(source, PdfOcrProvider.InputKind.PAGE))
                        .code());
        final var decorated = PdfWordOcrProvider.rotated(provider, 0, limits());
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class,
                    () -> decorated.recognizeWords(source, PdfOcrProvider.InputKind.PAGE));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals(0, calls.get());
        assertEquals("ok", decorated.recognize(source).getFirst().text());
        final PdfWordOcrProvider interrupted = (image, kind) -> {
            Thread.currentThread().interrupt();
            return List.of(detailed("ok", 10, 10, 20, 20));
        };
        try {
            assertThrows(InterruptedIOException.class,
                    ()
                            -> PdfWordOcrProvider.rotated(interrupted, 0, limits())
                                    .recognizeWords(source, PdfOcrProvider.InputKind.PAGE));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertThrows(IllegalArgumentException.class, () -> PdfWordOcrProvider.rotated(provider, Double.NaN, limits()));
        assertFalse(PdfOcrProvider.rotated(provider, 0, limits()) instanceof PdfWordOcrProvider);
    }

    @Test
    void seededEdgeTokensRemainInsideRestoredLineBoundsAcrossArbitraryAngles() throws Exception {
        final var random = new Random(41029);
        final var source = new BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB);
        for (int iteration = 0; iteration < 200; iteration++) {
            final double angle = random.nextDouble(-35, 35);
            final double fx = random.nextDouble(.01, .4);
            final double fy = random.nextDouble(.01, .4);
            final PdfWordOcrProvider provider = (image, kind) -> {
                final double l = fx * image.getWidth();
                final double t = fy * image.getHeight();
                final double r = l + image.getWidth() * .5;
                final double b = t + image.getHeight() * .5;
                return List.of(new PdfWordOcrProvider.WordLine(new PdfOcrProvider.Line("upper lower", l, t, r, b),
                        List.of(new PdfWordOcrProvider.Word("upper", l, t, l + 4, t + 4),
                                new PdfWordOcrProvider.Word("lower", r - 4, b - 4, r, b))));
            };
            final var output = PdfWordOcrProvider.rotated(provider, angle, limits())
                                       .recognizeWords(source, PdfOcrProvider.InputKind.PAGE);
            for (final var line : output) {
                assertTrue(line.line().left() >= 0 && line.line().right() <= 100);
                assertTrue(line.line().top() >= 0 && line.line().bottom() <= 80);
                for (final var word : line.words()) {
                    assertTrue(List.of("upper", "lower").contains(word.text()));
                    assertTrue(word.left() >= line.line().left() && word.right() <= line.line().right());
                    assertTrue(word.top() >= line.line().top() && word.bottom() <= line.line().bottom());
                }
            }
        }
    }

    @Test
    void deskewPreservesWordCapabilityAndPassesCellInputsDirectlyToTheirProvider() throws Exception {
        final var source = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        final var graphics = source.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 100, 100);
        } finally {
            graphics.dispose();
        }
        final PdfWordOcrProvider provider = (image, kind) -> {
            if (kind == PdfOcrProvider.InputKind.TABLE_CELL) {
                assertSame(source, image);
            }
            return List.of(detailed("actual", 10, 10, 30, 20));
        };
        final var decorated = PdfWordOcrProvider.deskewed(provider, limits());
        for (final var kind : PdfOcrProvider.InputKind.values()) {
            assertEquals("actual", decorated.recognizeWords(source, kind).getFirst().words().getFirst().text());
        }
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var futures =
                    java.util.stream.IntStream.range(0, 20)
                            .mapToObj(index
                                    -> executor.submit(
                                            () -> decorated.recognizeWords(source, PdfOcrProvider.InputKind.PAGE)))
                            .toList();
            for (final var future : futures) {
                assertEquals(List.of(detailed("actual", 10, 10, 30, 20)), future.get());
            }
        }
    }

    @Test
    void providerLayoutIntegrationAcceptsDecoratedWordsInTheOriginalSourceFrame() throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage(new PDRectangle(300, 180)));
            document.save(output);
            final PdfWordOcrProvider correctedWords = (image, kind)
                    -> List.of(new PdfWordOcrProvider.WordLine(new PdfOcrProvider.Line("A B", 120, 20, 150, 50),
                            List.of(new PdfWordOcrProvider.Word("A", 140, 20, 150, 30),
                                    new PdfWordOcrProvider.Word("B", 120, 40, 130, 50))));
            final var words = PdfWordOcrProvider.rotated(correctedWords, 90, limits());
            final var box = new PdfTableLayoutProvider.Box(10, 20, 70, 80);
            final PdfTableLayoutProvider layout = image
                    -> List.of(new PdfTableLayoutProvider.Table(1, 1, box,
                            List.of(new PdfTableLayoutProvider.Anchor(0, 0, 1, 1, box)), "owned-original-frame"));
            final var policy = new PdfOcrOptions(72, 100_000, 100, 1000)
                                       .withTableDetection(PdfOcrOptions.TableDetection.PROVIDER_LAYOUT);
            final var pdf = PdfPartitioner.create(PdfOptions.defaults(), policy, words, layout);
            final var result = DocWeave.create(PartitionerRegistry.of(List.of(pdf)))
                                       .partition(DocumentSource.bytes(output.toByteArray(), "corrected-words.pdf"));
            assertEquals(ElementType.Table, result.elements().getFirst().type());
            assertEquals("A B", result.elements().getFirst().text());
        }
    }

    /** Builds one real supplied token and its exact containing line without synthetic splitting. */
    private static PdfWordOcrProvider.WordLine detailed(
            final String text, final double l, final double t, final double r, final double b) {
        return new PdfWordOcrProvider.WordLine(
                new PdfOcrProvider.Line(text, l, t, r, b), List.of(new PdfWordOcrProvider.Word(text, l, t, r, b)));
    }

    /** Supplies generous deterministic per-call limits for small owned controls. */
    private static PdfOcrOptions limits() {
        return new PdfOcrOptions(72, 100_000, 100, 1000);
    }
}
