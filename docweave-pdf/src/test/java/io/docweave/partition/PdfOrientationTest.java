package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.core.ElementType;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PdfOrientationTest {
    private static PdfOrientationEstimator estimator(final int correction) {
        return image -> new PdfOrientationEstimator.Estimate(correction, true, 12, "owned-quarter-turn");
    }

    @Test
    void exactQuarterTurnPixelsAndInverseBoundsLeaveBorrowedSourceUntouched() throws Exception {
        final var image = new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 2; x++) {
                image.setRGB(x, y, 0xff000000 | (y * 2 + x + 1) * 0x112233);
            }
        }
        final var before = image.getRGB(0, 0, 2, 3, null, 0, 2);
        for (final int correction : List.of(0, 90, 180, 270)) {
            try (final var frame = new PdfOcrFrame(image, estimator(correction))) {
                assertEquals(correction % 180 == 0 ? 2 : 3, frame.image().getWidth());
                assertEquals(correction % 180 == 0 ? 3 : 2, frame.image().getHeight());
                for (int y = 0; y < 3; y++) {
                    for (int x = 0; x < 2; x++) {
                        final int rx = switch (correction) {
                            case 90 -> 2 - y;
                            case 180 -> 1 - x;
                            case 270 -> y;
                            default -> x;
                        };
                        final int ry = switch (correction) {
                            case 90 -> x;
                            case 180 -> 2 - y;
                            case 270 -> 1 - x;
                            default -> y;
                        };
                        assertEquals(image.getRGB(x, y), frame.image().getRGB(rx, ry));
                    }
                }
                final double[] box = switch (correction) {
                    case 90 -> new double[] {1, 0, 3, 1};
                    case 180 -> new double[] {1, 1, 2, 3};
                    case 270 -> new double[] {0, 1, 2, 2};
                    default -> new double[] {0, 0, 1, 2};
                };
                assertEquals(new PdfOcrProvider.Line("owned", 0, 0, 1, 2),
                        frame.toSource(new PdfOcrProvider.Line("owned", box[0], box[1], box[2], box[3])));
            }
            assertArrayEquals(before, image.getRGB(0, 0, 2, 3, null, 0, 2));
        }
    }

    @Test
    void parentAndMergedCellCoordinatesRestoreOriginalCropPageRotationAndUserUnitOnce() throws Exception {
        for (final int pageRotation : List.of(0, 90)) {
            final double width = pageRotation == 0 ? 300 : 180;
            final double height = pageRotation == 0 ? 180 : 300;
            for (final int correction : List.of(0, 90, 180, 270)) {
                final double uprightWidth = correction % 180 == 0 ? width : height;
                final double uprightHeight = correction % 180 == 0 ? height : width;
                for (final int dpi : List.of(72, 144)) {
                    final var estimates = new AtomicInteger();
                    final var pageCalls = new AtomicInteger();
                    final var layoutCalls = new AtomicInteger();
                    final PdfWordOcrProvider words = (image, kind) -> {
                        assertEquals(PdfOcrProvider.InputKind.PAGE, kind);
                        pageCalls.incrementAndGet();
                        final double sx = image.getWidth() / uprightWidth;
                        final double sy = image.getHeight() / uprightHeight;
                        return List.of(word("A", 30 * sx, 30 * sy, sx, sy), word("B", 110 * sx, 30 * sy, sx, sy),
                                word("C", 30 * sx, 80 * sy, sx, sy), word("D", 110 * sx, 80 * sy, sx, sy));
                    };
                    final PdfTableLayoutProvider layout = image -> {
                        layoutCalls.incrementAndGet();
                        final double sx = image.getWidth() / uprightWidth;
                        final double sy = image.getHeight() / uprightHeight;
                        return List.of(new PdfTableLayoutProvider.Table(2, 2, box(20, 20, 160, 120, sx, sy),
                                List.of(new PdfTableLayoutProvider.Anchor(0, 0, 1, 2, box(20, 20, 160, 70, sx, sy)),
                                        new PdfTableLayoutProvider.Anchor(1, 0, 1, 1, box(20, 70, 90, 120, sx, sy)),
                                        new PdfTableLayoutProvider.Anchor(1, 1, 1, 1, box(90, 70, 160, 120, sx, sy))),
                                "owned-layout"));
                    };
                    final var policy = new PdfOcrOptions(dpi, 2_000_000, 100, 1000)
                                               .withTableDetection(PdfOcrOptions.TableDetection.PROVIDER_LAYOUT);
                    final var partitioner = PdfPartitioner.createOriented(
                            PdfOptions.defaults().withTableCellCoordinates(true), policy, words, layout, image -> {
                                estimates.incrementAndGet();
                                return estimator(correction).estimate(image);
                            });
                    final var result = DocWeave.create(PartitionerRegistry.of(List.of(partitioner)))
                                               .partition(source(pageRotation));
                    assertEquals(1, estimates.get());
                    assertEquals(1, pageCalls.get());
                    assertEquals(1, layoutCalls.get());
                    final var table = result.elements().getFirst();
                    assertEquals(ElementType.Table, table.type());
                    assertEquals("A B C D", table.text());
                    assertEquals(correction, table.metadata().get("pdf_ocr_orientation_correction"));
                    assertEquals("accepted", table.metadata().get("pdf_ocr_orientation_status"));
                    checkCoordinates((Map<?, ?>) table.metadata().get("coordinates"), expected(correction, width, height, 20, 20, 160, 120), width, height);
                    final var extension = (Map<?, ?>) table.metadata().get("pdf_table_cells");
                    final var cell = (Map<?, ?>) ((List<?>) extension.get("cells")).getFirst();
                    assertEquals(2, cell.get("column_span"));
                    checkCoordinates((Map<?, ?>) cell.get("coordinates"), expected(correction, width, height, 20, 20, 160, 70), width, height);
                    final var physical = PdfCoordinateConverter.create().toPoints(table);
                    checkCoordinates((Map<?, ?>) physical.metadata().get("coordinates"),
                            expected(correction, width, height, 20, 20, 160, 120).stream().map(value -> value * 3).toList(), width * 3, height * 3);
                }
            }
        }
    }

    @Test
    void alignedWordsRestoreParentAndCellCoordinatesForEveryQuarterTurn() throws Exception {
        for (final int correction : List.of(0, 90, 180, 270)) {
            final PdfWordOcrProvider words = (image, kind) -> {
                final var tokens = new ArrayList<PdfWordOcrProvider.Word>();
                final var values = List.of(
                        List.of("Item", "name", "Value"), List.of("Red", "apple", "1"), List.of("Blue", "pear", "2"));
                for (int row = 0; row < 3; row++) {
                    final double top = 20 + row * 20;
                    tokens.add(new PdfWordOcrProvider.Word(values.get(row).get(0), 30, top, 48, top + 8));
                    tokens.add(new PdfWordOcrProvider.Word(values.get(row).get(1), 52, top, 75, top + 8));
                    tokens.add(new PdfWordOcrProvider.Word(values.get(row).get(2), 150, top, 165, top + 8));
                }
                return List.of(new PdfWordOcrProvider.WordLine(
                        new PdfOcrProvider.Line(
                                String.join(" ", tokens.stream().map(PdfWordOcrProvider.Word::text).toList()), 0, 0,
                                image.getWidth(), image.getHeight()),
                        tokens));
            };
            final var ocr = new PdfOcrOptions(72, 100_000, 100, 1000)
                                    .withTableDetection(PdfOcrOptions.TableDetection.ALIGNED_WORDS);
            final var partitioner = PdfPartitioner.createOriented(
                    PdfOptions.defaults().withTableCellCoordinates(true), ocr, words, estimator(correction));
            final var result = DocWeave.create(PartitionerRegistry.of(List.of(partitioner))).partition(source(0));
            final var tables =
                    result.elements().stream().filter(element -> element.type() == ElementType.Table).toList();
            assertEquals(1, tables.size());
            final var table = tables.getFirst();
            assertEquals("Item name Value Red apple 1 Blue pear 2", table.text());
            assertEquals(correction, table.metadata().get("pdf_ocr_orientation_correction"));
            checkCoordinates((Map<?, ?>) table.metadata().get("coordinates"),
                    expected(correction, 300, 180, 30, 20, 165, 68), 300, 180);
            final var extension = (Map<?, ?>) table.metadata().get("pdf_table_cells");
            assertEquals(6, ((List<?>) extension.get("cells")).size());
            final var cell = (Map<?, ?>) ((List<?>) extension.get("cells")).getFirst();
            final var coordinates = (Map<?, ?>) cell.get("coordinates");
            checkCoordinates(coordinates, expected(correction, 300, 180, 30, 20, 75, 28), 300, 180);
        }
    }

    @Test
    void mixedNativeOwnershipUsesInverseFrameBeforeFilteringWordsAndLayouts() throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(300, 180));
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                content.drawImage(LosslessFactory.createFromImage(
                                          document, new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB)),
                        20, 20, 260, 100);
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(40, 70);
                content.showText("Native text");
                content.endText();
            }
            document.save(output);
            final PdfWordOcrProvider words =
                    (image, kind) -> List.of(word("Raster", 75, 175, 1, 1), word("Duplicate", 65, 45, 1, 1));
            final PdfTableLayoutProvider layout = image
                    -> List.of(new PdfTableLayoutProvider.Table(1, 1, box(50, 160, 100, 210, 1, 1),
                            List.of(new PdfTableLayoutProvider.Anchor(0, 0, 1, 1, box(50, 160, 100, 210, 1, 1))),
                            "mixed"));
            final var ocr = new PdfOcrOptions(72, 100_000, 100, 1000)
                                    .withSelection(PdfOcrOptions.Selection.IMAGE_REGIONS)
                                    .withTableDetection(PdfOcrOptions.TableDetection.PROVIDER_LAYOUT);
            final var processor = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.createOriented(
                    PdfOptions.defaults().withTableCellCoordinates(true), ocr, words, layout, estimator(90)))));
            final var result = processor.partition(DocumentSource.bytes(output.toByteArray(), "mixed-oriented.pdf"));
            assertEquals(List.of("Native text", "Raster"), result.elements()
                                                                   .stream()
                                                                   .filter(value -> value.type() != ElementType.Image)
                                                                   .map(value -> value.text())
                                                                   .toList());
            assertEquals(1, result.elements().stream().filter(value -> value.type() == ElementType.Image).count());
        }
    }

    @Test
    void undecidedEstimatorPreservesInputAndFailuresAreTypedBeforeRecognition() throws Exception {
        final var calls = new AtomicInteger();
        final PdfOcrProvider provider = image -> {
            calls.incrementAndGet();
            assertEquals(300, image.getWidth());
            return List.of(new PdfOcrProvider.Line("unchanged", 20, 20, 100, 40));
        };
        final var ocr = new PdfOcrOptions(72, 100_000, 100, 1000);
        final var undecided = PdfPartitioner.createOriented(PdfOptions.defaults(), ocr, provider,
                image -> new PdfOrientationEstimator.Estimate(0, false, .5, "ambiguous"));
        final var result = DocWeave.create(PartitionerRegistry.of(List.of(undecided))).partition(source(0));
        assertEquals("undetermined", result.elements().getFirst().metadata().get("pdf_ocr_orientation_status"));
        assertEquals(1, calls.get());
        final var invalid = PdfPartitioner.createOriented(PdfOptions.defaults(), ocr, provider, image -> null);
        assertEquals(PartitionException.Code.OCR_FAILURE,
                assertThrows(PartitionException.class,
                        () -> DocWeave.create(PartitionerRegistry.of(List.of(invalid))).partition(source(0)))
                        .code());
        assertEquals(1, calls.get());
        final var interrupted = new InterruptedIOException("owned cancellation");
        final var cancelled =
                PdfPartitioner.createOriented(PdfOptions.defaults(), ocr, provider, image -> { throw interrupted; });
        assertSame(interrupted,
                assertThrows(InterruptedIOException.class,
                        () -> DocWeave.create(PartitionerRegistry.of(List.of(cancelled))).partition(source(0))));
        assertEquals(1, calls.get());
    }

    private static PdfWordOcrProvider.WordLine word(
            final String text, final double x, final double y, final double sx, final double sy) {
        return new PdfWordOcrProvider.WordLine(new PdfOcrProvider.Line(text, x, y, x + 10 * sx, y + 5 * sy),
                List.of(new PdfWordOcrProvider.Word(text, x, y, x + 10 * sx, y + 5 * sy)));
    }

    private static PdfTableLayoutProvider.Box box(
            final double l, final double t, final double r, final double b, final double sx, final double sy) {
        return new PdfTableLayoutProvider.Box(l * sx, t * sy, r * sx, b * sy);
    }

    private static List<Double> expected(final int correction, final double w, final double h, final double l,
            final double t, final double r, final double b) {
        return switch (correction) {
            case 90 -> List.of(t, h - r, b, h - l);
            case 180 -> List.of(w - r, h - b, w - l, h - t);
            case 270 -> List.of(w - b, l, w - t, r);
            default -> List.of(l, t, r, b);
        };
    }

    private static void checkCoordinates(
            final Map<?, ?> coordinates, final List<Double> box, final double width, final double height) {
        assertEquals(width, ((Number) coordinates.get("layout_width")).doubleValue(), 1e-5);
        assertEquals(height, ((Number) coordinates.get("layout_height")).doubleValue(), 1e-5);
        final var points = (List<?>) coordinates.get("points");
        final var first = (List<?>) points.getFirst();
        final var third = (List<?>) points.get(2);
        assertEquals(box.get(0), ((Number) first.get(0)).doubleValue(), 1e-5);
        assertEquals(box.get(1), ((Number) first.get(1)).doubleValue(), 1e-5);
        assertEquals(box.get(2), ((Number) third.get(0)).doubleValue(), 1e-5);
        assertEquals(box.get(3), ((Number) third.get(1)).doubleValue(), 1e-5);
    }

    private static DocumentSource source(final int rotation) throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(400, 300));
            page.setCropBox(new PDRectangle(10, 20, 300, 180));
            page.setRotation(rotation);
            page.getCOSObject().setFloat(COSName.USER_UNIT, 3);
            document.addPage(page);
            document.save(output);
            return DocumentSource.bytes(output.toByteArray(), "oriented.pdf");
        }
    }
}
