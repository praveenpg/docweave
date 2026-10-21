package dev.docweave.partition;

import dev.docweave.DocWeave;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PdfOcrTest {
    @Test
    void optInRecognizesOnlyPagesWithoutNativeText() throws Exception {
        final var calls = new AtomicInteger();
        final var source = fixture(0);
        final var nativeResult = DocWeave.create().partition(source);
        final var result = processor(PdfOcrOptions.defaults(), image -> {
            calls.incrementAndGet();
            return List.of(new PdfOcrProvider.Line("Recognized scan", 0, 0, image.getWidth(), image.getHeight()));
        }).partition(source);
        assertEquals(1, calls.get());
        assertEquals(List.of("Recognized scan", "Native text"), result.elements().stream().map(e -> e.text()).toList());
        assertEquals(nativeResult.elements().getFirst().text(), result.elements().getLast().text());
        assertEquals("ocr", result.elements().getFirst().metadata().get("pdf_text_method"));
        assertFalse(result.elements().getLast().metadata().containsKey("pdf_text_method"));
        assertFalse(result.warnings().stream().anyMatch(w -> w.message().contains("OCR was not performed")));
    }

    @Test
    void imagePlacementsSurviveOcrAndEmptyRecognitionIsValid() throws Exception {
        final byte[] bytes;
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(100, 200));
            document.addPage(page);
            final var raster = new java.awt.image.BufferedImage(10, 10, java.awt.image.BufferedImage.TYPE_INT_RGB);
            try (final var content = new PDPageContentStream(document, page)) {
                content.drawImage(
                        org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(document, raster), 0,
                        0, 100, 200);
            } finally {
                raster.flush();
            }
            document.save(output);
            bytes = output.toByteArray();
        }
        final var source = DocumentSource.bytes(bytes, "raster.pdf");
        final var result = processor(PdfOcrOptions.defaults(), image -> {
            assertEquals(0, image.getRGB(image.getWidth() / 2, image.getHeight() / 2) & 0xffffff);
            return List.of(line("owned recognition"));
        }).partition(source);
        assertEquals(2, result.elements().size());
        assertEquals(dev.docweave.core.ElementType.Image, result.elements().getFirst().type());
        assertEquals("owned recognition", result.elements().getLast().text());
        final var empty = processor(PdfOcrOptions.defaults(), image -> List.of()).partition(source);
        assertEquals(1, empty.elements().size());
        assertFalse(empty.warnings().stream().anyMatch(w -> w.message().contains("OCR was not performed")));
    }

    @Test
    void rotatedRasterCoordinatesUseActualDimensions() throws Exception {
        for (final int rotation : List.of(0, 90, 180, 270)) {
            final var result = processor(new PdfOcrOptions(100, 1_000_000, 10, 100), image -> {
                assertEquals(rotation % 180 == 0, image.getWidth() < image.getHeight());
                return List.of(new PdfOcrProvider.Line("scan", image.getWidth() / 4.0, image.getHeight() / 4.0,
                        image.getWidth() / 2.0, image.getHeight() / 2.0));
            }).partition(fixture(rotation));
            final var coords = (Map<?, ?>) result.elements().getFirst().metadata().get("coordinates");
            final double width = rotation % 180 == 0 ? 100 : 200;
            final double height = rotation % 180 == 0 ? 200 : 100;
            assertEquals(width, coords.get("layout_width"));
            assertEquals(height, coords.get("layout_height"));
            assertEquals(List.of(width / 4, height / 4), ((List<?>) coords.get("points")).getFirst());
        }
    }

    @Test
    void budgetsAndInvalidOutputFailExplicitly() throws Exception {
        final var source = fixture(0);
        final var calls = new AtomicInteger();
        final var limit =
                assertThrows(PartitionException.class, () -> processor(new PdfOcrOptions(150, 1, 10, 100), image -> {
                    calls.incrementAndGet();
                    return List.of();
                }).partition(source));
        assertEquals(PartitionException.Code.OCR_LIMIT, limit.code());
        assertEquals(0, calls.get());
        assertFailure(PartitionException.Code.OCR_LIMIT, new PdfOcrOptions(72, 100_000, 1, 100),
                image -> List.of(line("a"), line("b")));
        assertFailure(
                PartitionException.Code.OCR_LIMIT, new PdfOcrOptions(72, 100_000, 10, 1), image -> List.of(line("ab")));
        assertFailure(PartitionException.Code.OCR_FAILURE, PdfOcrOptions.defaults(), image -> null);
        assertFailure(PartitionException.Code.OCR_FAILURE, PdfOcrOptions.defaults(),
                image -> List.of(new PdfOcrProvider.Line("outside", 0, 0, image.getWidth() + 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> new PdfOcrOptions(0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new PdfOcrProvider.Line("x", 0, 0, Double.NaN, 1));
        assertThrows(NullPointerException.class,
                () -> PdfPartitioner.create(PdfOptions.defaults(), PdfOcrOptions.defaults(), null));
    }

    @Test
    void providerCauseAndCancellationArePreserved() throws Exception {
        final var cause = new IOException("owned failure");
        final var failure = assertThrows(PartitionException.class,
                () -> processor(PdfOcrOptions.defaults(), image -> { throw cause; }).partition(fixture(0)));
        assertEquals(PartitionException.Code.OCR_FAILURE, failure.code());
        assertSame(cause, failure.getCause());
        try {
            assertThrows(InterruptedIOException.class, () -> processor(PdfOcrOptions.defaults(), image -> {
                Thread.currentThread().interrupt();
                return List.of(line("cancelled"));
            }).partition(fixture(0)));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals(1, DocWeave.create().partition(fixture(0)).elements().size());
    }

    @Test
    void sharedStrategySupportsVirtualThreadCalls() throws Exception {
        final var processor = processor(PdfOcrOptions.defaults(), image -> List.of(line("scan")));
        final var source = fixture(0);
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var tasks = java.util.stream.IntStream.range(0, 8)
                                      .mapToObj(i -> executor.submit(() -> processor.partition(source)))
                                      .toList();
            final var expected = tasks.getFirst().get().elements();
            for (final var task : tasks) {
                assertEquals(expected, task.get().elements());
            }
        }
    }

    private static PdfOcrProvider.Line line(final String text) {
        return new PdfOcrProvider.Line(text, 0, 0, 10, 10);
    }

    private static DocWeave processor(final PdfOcrOptions options, final PdfOcrProvider provider) {
        return DocWeave.create(
                PartitionerRegistry.of(List.of(PdfPartitioner.create(PdfOptions.defaults(), options, provider))));
    }

    private static void assertFailure(final PartitionException.Code code, final PdfOcrOptions options,
            final PdfOcrProvider provider) throws Exception {
        assertEquals(
                code, assertThrows(PartitionException.class, () -> processor(options, provider).partition(fixture(0)))
                              .code());
    }

    private static DocumentSource fixture(final int rotation) throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var scan = new PDPage(new PDRectangle(100, 200));
            scan.setRotation(rotation);
            document.addPage(scan);
            final var nativePage = new PDPage(new PDRectangle(100, 200));
            document.addPage(nativePage);
            try (final var content = new PDPageContentStream(document, nativePage)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                content.newLineAtOffset(10, 150);
                content.showText("Native text");
                content.endText();
            }
            document.save(output);
            return DocumentSource.bytes(output.toByteArray(), "ocr.pdf");
        }
    }
}
