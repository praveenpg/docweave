package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.core.ElementType;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PdfMixedOcrTest {
    @Test
    void selectionIsOptInAndNativeElementsRemainExactlyUnchanged() throws Exception {
        final var source = fixture(0, false, 1, false);
        final var calls = new AtomicInteger();
        final PdfOcrProvider provider = image -> {
            calls.incrementAndGet();
            return List.of(line("scan", 40, 60, 100, 75));
        };
        final var baseline = DocWeave.create().partition(source);
        assertEquals(baseline.elements(), processor(PdfOcrOptions.defaults(), provider).partition(source).elements());
        assertEquals(0, calls.get());
        final var result = processor(mixed(), provider).partition(source);
        assertEquals(1, calls.get());
        assertEquals(baseline.elements(), result.elements()
                                                  .stream()
                                                  .filter(element -> !element.metadata().containsKey("pdf_text_method"))
                                                  .toList());
        final var ocr = result.elements().getLast();
        assertEquals("scan", ocr.text());
        assertEquals("image_regions_v1", ocr.metadata().get("pdf_ocr_selection"));
        assertEquals(1, ocr.metadata().get("pdf_ocr_region_count"));
    }

    @Test
    void rasterMaskAndOwnershipExcludeNativeOutsideAndExactDuplicates() throws Exception {
        final var duplicate = line("scan", 40, 60, 100, 75);
        final var result = processor(mixed(), image -> {
            assertEquals(0xffffff, image.getRGB(10, 60) & 0xffffff);
            assertEquals(0, image.getRGB(100, 60) & 0xffffff);
            assertEquals(0xffffff, image.getRGB(45, 97) & 0xffffff);
            return List.of(duplicate, duplicate, line("Native overlay", 40, 92, 100, 104),
                    line("outside", 5, 5, 50, 20), line("crosses region", 20, 25, 100, 65),
                    line("scan", 40, 130, 100, 145));
        }).partition(fixture(0, false, 1, false));
        assertEquals(List.of("scan", "scan"), ocrTexts(result));
        assertTrue(result.warnings().stream().anyMatch(w -> w.message().contains("excluded 4")));
        assertEquals(1, result.elements().stream().filter(element -> element.text().equals("Native overlay")).count());
    }

    @Test
    void repeatedImagePlacementsUseOnePassAndRetainBothImages() throws Exception {
        final var calls = new AtomicInteger();
        final var result = processor(mixed(), image -> {
            calls.incrementAndGet();
            return List.of(line("scan", 40, 60, 100, 75));
        }).partition(fixture(0, false, 2, false));
        assertEquals(1, calls.get());
        assertEquals(2, result.elements().stream().filter(element -> element.type() == ElementType.Image).count());
        assertEquals(1, result.elements().getLast().metadata().get("pdf_ocr_region_count"));
    }

    @Test
    void separateOverlappingAndPartiallyClippedRegionsHaveExplicitOwnership() throws Exception {
        final var result = processor(mixed(), image -> {
            assertEquals(0, image.getRGB(130, 190) & 0xffffff);
            assertEquals(0, image.getRGB(5, 30) & 0xffffff);
            return List.of(line("first", 40, 60, 80, 70), line("second", 120, 185, 150, 195),
                    line("clipped", 2, 25, 12, 35), line("ambiguous bridge", 25, 30, 45, 60));
        }).partition(fixture(0, false, 3, false));
        assertEquals(List.of("first", "second", "clipped"), ocrTexts(result));
        assertEquals(3, result.elements().getLast().metadata().get("pdf_ocr_region_count"));
        assertEquals(3, result.elements().stream().filter(element -> element.type() == ElementType.Image).count());
    }

    @Test
    void actualRasterDimensionsCropRotationAndUserUnitRemainConsistent() throws Exception {
        for (final int rotation : List.of(0, 90, 180, 270)) {
            final var source = fixture(rotation, true, 1, false);
            final var nativeImage = DocWeave.create()
                                            .partition(source)
                                            .elements()
                                            .stream()
                                            .filter(element -> element.type() == ElementType.Image)
                                            .findFirst()
                                            .orElseThrow();
            final var coords = (Map<?, ?>) nativeImage.metadata().get("coordinates");
            final var points = (List<?>) coords.get("points");
            final var first = (List<?>) points.getFirst();
            final var third = (List<?>) points.get(2);
            final double left = ((Number) first.getFirst()).doubleValue();
            final double top = ((Number) first.getLast()).doubleValue();
            final double width = ((Number) third.getFirst()).doubleValue() - left;
            final double height = ((Number) third.getLast()).doubleValue() - top;
            final double x = left + width * .1;
            final double y = top + height * .1;
            final var options =
                    new PdfOcrOptions(100, 1_000_000, 100, 10_000).withSelection(PdfOcrOptions.Selection.IMAGE_REGIONS);
            final var result = processor(options, image -> {
                final double xScale = image.getWidth() / 200.0;
                final double yScale = image.getHeight() / 200.0;
                assertEquals(0, image.getRGB((int) ((x + 2) * xScale), (int) ((y + 2) * yScale)) & 0xffffff);
                assertEquals(0xffffff, image.getRGB(1, 1) & 0xffffff);
                return List.of(line("scan", x * xScale, y * yScale, (x + 4) * xScale, (y + 4) * yScale));
            }).partition(source);
            assertEquals(List.of("scan"), ocrTexts(result));
            final var ocrCoords = (Map<?, ?>) result.elements().getLast().metadata().get("coordinates");
            final var point = (List<?>) ((List<?>) ocrCoords.get("points")).getFirst();
            assertEquals(x, ((Number) point.getFirst()).doubleValue(), .0001);
            assertEquals(y, ((Number) point.getLast()).doubleValue(), .0001);
            assertEquals(2.0, result.elements().getLast().metadata().get("pdf_user_unit"));
        }
    }

    @Test
    void noImageAndOutOfCropImagesDoNotRenderOrInvokeProvider() throws Exception {
        final var options = new PdfOcrOptions(72, 1, 10, 100).withSelection(PdfOcrOptions.Selection.IMAGE_REGIONS);
        for (final var source : List.of(fixture(0, false, 0, false), fixture(0, false, 1, true))) {
            assertTrue(ocrTexts(processor(options, image -> {
                throw new AssertionError("No image region");
            }).partition(source)).isEmpty());
        }
    }

    @Test
    void regionAndRawOutputBudgetsFailBeforeRecognitionOrFiltering() throws Exception {
        final var options = new PdfOcrOptions(72, 1_000_000, 10, 100, PdfOcrOptions.Selection.IMAGE_REGIONS, 1);
        assertEquals(PartitionException.Code.OCR_LIMIT,
                assertThrows(PartitionException.class, () -> processor(options, image -> {
                    throw new AssertionError("Too many images");
                }).partition(fixture(0, false, 2, false))).code());
        final var raw = new PdfOcrOptions(72, 1_000_000, 1, 100).withSelection(PdfOcrOptions.Selection.IMAGE_REGIONS);
        assertEquals(PartitionException.Code.OCR_LIMIT,
                assertThrows(PartitionException.class,
                        ()
                                -> processor(
                                        raw, image -> List.of(line("outside", 1, 1, 2, 2), line("outside", 3, 3, 4, 4)))
                                        .partition(fixture(0, false, 1, false)))
                        .code());
        assertEquals(PdfOcrOptions.Selection.TEXTLESS_PAGES, new PdfOcrOptions(72, 1, 1, 1).selection());
        assertEquals(256, mixed().maxRegions());
        assertThrows(NullPointerException.class, () -> mixed().withSelection(null));
        assertThrows(IllegalArgumentException.class,
                () -> new PdfOcrOptions(72, 1, 1, 1, PdfOcrOptions.Selection.IMAGE_REGIONS, 0));
    }

    @Test
    void internalGeometryAndPixelWorkAreBoundedAndCancellationIsPreserved() throws Exception {
        final var geometry = new PdfPageGeometry(new PDPage(new PDRectangle(100, 100)));
        final var images = List.of(new PdfPageGraphics.Image(0, 0, 100, 100, Map.of()));
        final var full = new PdfPageText.Glyph("x", 0, 100, 100, 100, 1, 0);
        final var tooMuchMasking = new PdfOcrRegions(images, Collections.nCopies(6500, full), geometry, mixed());
        final var source = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        assertEquals(PartitionException.Code.OCR_LIMIT,
                assertThrows(PartitionException.class, () -> tooMuchMasking.mask(source)).code());
        final var glyph = new PdfPageText.Glyph("x", 80, 80, 1, 1, 1, 0);
        final var probes = new PdfOcrRegions(images, Collections.nCopies(1000, glyph), geometry, mixed());
        final var masked = probes.mask(source);
        try {
            assertEquals(PartitionException.Code.OCR_LIMIT, assertThrows(PartitionException.class, () -> {
                for (int i = 0; i < 2100; i++) {
                    probes.retain(line("line " + i, 0, 0, 5, 5));
                }
            }).code());
        } finally {
            masked.flush();
            source.flush();
        }
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> new PdfOcrRegions(images, List.of(), geometry, mixed()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void sharedMixedStrategyHasNoPageOrCallOwnershipLeakage() throws Exception {
        final var processor = processor(mixed(), image -> List.of(line("scan", 40, 60, 100, 75)));
        final var source = fixture(0, false, 1, false);
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

    private static PdfOcrOptions mixed() {
        return new PdfOcrOptions(72, 1_000_000, 100, 10_000).withSelection(PdfOcrOptions.Selection.IMAGE_REGIONS);
    }

    private static DocWeave processor(final PdfOcrOptions options, final PdfOcrProvider provider) {
        return DocWeave.create(
                PartitionerRegistry.of(List.of(PdfPartitioner.create(PdfOptions.defaults(), options, provider))));
    }

    private static List<String> ocrTexts(final PartitionResult result) {
        return result.elements()
                .stream()
                .filter(element -> element.metadata().containsKey("pdf_text_method"))
                .map(element -> element.text())
                .toList();
    }

    private static PdfOcrProvider.Line line(
            final String text, final double left, final double top, final double right, final double bottom) {
        return new PdfOcrProvider.Line(text, left, top, right, bottom);
    }

    private static DocumentSource fixture(
            final int rotation, final boolean crop, final int images, final boolean outside) throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final int offset = crop ? 10 : 0;
            final var page = new PDPage(new PDRectangle(200 + offset * 2, 200 + offset * 2));
            if (crop) {
                page.setCropBox(new PDRectangle(offset, offset, 200, 200));
                page.getCOSObject().setFloat(COSName.USER_UNIT, 2);
            }
            page.setRotation(rotation);
            document.addPage(page);
            final var raster = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
            try (final var content = new PDPageContentStream(document, page)) {
                final var image = LosslessFactory.createFromImage(document, raster);
                for (int i = 0; i < images; i++) {
                    if (images == 3 && i == 1) {
                        content.drawImage(image, 100, 0, 80, 80);
                    } else if (images == 3 && i == 2) {
                        content.drawImage(image, -10, 150, 40, 30);
                    } else {
                        content.drawImage(image, outside ? 300 : offset + 20, outside ? 300 : offset + 20, 160, 140);
                    }
                }
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                content.newLineAtOffset(offset + 20, offset + 185);
                content.showText("Native heading");
                content.newLineAtOffset(20, -85);
                content.showText("Native overlay");
                content.endText();
            } finally {
                raster.flush();
            }
            document.save(output);
            return DocumentSource.bytes(output.toByteArray(), "mixed.pdf");
        }
    }
}
