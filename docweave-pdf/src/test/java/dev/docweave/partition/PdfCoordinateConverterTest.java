package dev.docweave.partition;

import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/** Analytical physical-unit controls cover source transforms, cell-only chunks and rejected geometry. */
class PdfCoordinateConverterTest {
    @Test
    void convertsBothParentAndMergedCellWithoutTouchingSourceIdentityOrAssetDimensions() throws Exception {
        final var converter = PdfCoordinateConverter.create();
        final var source = source(Map.of("pdf_user_unit", 2, "pdf_crop_box", List.of(10, 20, 110, 220), "pdf_rotation",
                90, "image_width", 640, "pdf_ocr_dpi", 150));
        final var pixels = converter.toPixels(source, 144);
        assertEquals(source.elementId(), pixels.elementId());
        assertEquals(source.type(), pixels.type());
        assertEquals(source.text(), pixels.text());
        assertEquals(640, pixels.metadata().get("image_width"));
        assertEquals(150, pixels.metadata().get("pdf_ocr_dpi"));
        assertEquals(source.metadata().get("pdf_crop_box"), pixels.metadata().get("pdf_crop_box"));
        check(pixels, "PDFPixels", 400, 800, -40, 80);
        assertEquals(144.0, geometry(pixels).get("dpi"));
        final var grid = (Map<?, ?>)pixels.metadata().get("pdf_table_cells");
        final var cell = (Map<?, ?>)((List<?>)grid.get("cells")).getFirst();
        assertEquals(2, cell.get("column_span"));
        assertEquals(geometry(pixels), cell.get("coordinates"));
        check(converter.toPoints(pixels), "PDFPoints", 200, 400, -20, 40);
        check(converter.toMillimeters(pixels), "PDFMillimeters", 200 * 25.4 / 72, 400 * 25.4 / 72, -20 * 25.4 / 72,
                40 * 25.4 / 72);
        check(converter.toUserSpace(pixels), "PDFUserSpace", 100, 200, -10, 20);
        assertFalse(geometry(source).containsKey("dpi"));
        assertEquals("PDFUserSpace", geometry(source).get("system"));
        assertThrows(UnsupportedOperationException.class, () -> pixels.metadata().put("x", 1));
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var tasks = java.util.stream.IntStream.range(0, 8)
                                      .mapToObj(i -> executor.submit(() -> converter.toPixels(source, 144)))
                                      .toList();
            for (final var task : tasks) {
                assertEquals(pixels, task.get());
            }
        }
    }

    @Test
    void conversionsAreIdempotentAndReversibleWithIndependentPixelDensity() {
        final var converter = PdfCoordinateConverter.create();
        final var original = source(Map.of());
        for (final var value : List.of(converter.toPoints(original), converter.toMillimeters(original),
                     converter.toPixels(original, 300))) {
            check(converter.toUserSpace(value), "PDFUserSpace", 100, 200, -10, 20);
            assertEquals(converter.toPoints(value), converter.toPoints(converter.toPoints(value)));
            assertEquals(converter.toMillimeters(value), converter.toMillimeters(converter.toMillimeters(value)));
            assertEquals(converter.toPixels(value, 300), converter.toPixels(converter.toPixels(value, 300), 300));
        }
        final var resized = converter.toPixels(converter.toPixels(original, 150), 300);
        check(resized, "PDFPixels", 100 * 300.0 / 72, 200 * 300.0 / 72, -10 * 300.0 / 72, 20 * 300.0 / 72);
        final var cellsOnly = new LinkedHashMap<>(original.metadata());
        cellsOnly.remove("coordinates");
        final var chunk = new Element("chunk", ElementType.Table, "body", cellsOnly);
        final var converted = converter.toPoints(chunk);
        assertFalse(converted.metadata().containsKey("coordinates"));
        final var cell = (Map<?, ?>)((List<?>)((Map<?, ?>)converted.metadata().get("pdf_table_cells")).get("cells")).getFirst();
        assertEquals("PDFPoints", ((Map<?, ?>)cell.get("coordinates")).get("system"));
        final var plain = new Element("plain", ElementType.NarrativeText, "text", Map.of());
        assertSame(plain, converter.toPoints(plain));
    }

    @Test
    void cropAndQuarterTurnNormalizationIsAppliedExactlyOnce() throws Exception {
        final var converter = PdfCoordinateConverter.create();
        final double[][] expected = {{20, 170}, {30, 20}, {80, 30}, {170, 80}};
        for (int index = 0; index < 4; index++) {
            final var page = new PDPage(new PDRectangle(300, 400));
            page.setCropBox(new PDRectangle(10, 20, 100, 200));
            page.setUserUnit(2);
            page.setRotation(index * 90);
            final var frame = new PdfPageGeometry(page);
            final var point = frame.point(30, 50);
            final var metadata = new LinkedHashMap<String, Object>();
            frame.describe(metadata);
            metadata.put("coordinates", Map.of("system", "PDFUserSpace", "layout_width", (double) frame.width(),
                                                "layout_height", (double) frame.height(), "points",
                                                List.of(List.of(point.x, point.y), List.of(point.x, point.y),
                                                        List.of(point.x, point.y), List.of(point.x, point.y))));
            final var element = new Element("rotation", ElementType.Image, "", metadata);
            final var points = geometry(converter.toPoints(element));
            final var first = (List<?>) ((List<?>) points.get("points")).getFirst();
            assertEquals(expected[index][0] * 2, ((Number) first.get(0)).doubleValue(), 1e-9);
            assertEquals(expected[index][1] * 2, ((Number) first.get(1)).doubleValue(), 1e-9);
        }
    }

    @Test
    void rejectsInvalidFramesAndNonfiniteArithmeticWithoutMutatingSource() {
        final var converter = PdfCoordinateConverter.create();
        final var source = source(Map.of());
        for (final double dpi : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY, Double.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> converter.toPixels(source, dpi));
        }
        for (final var entry : List.of(Map.entry("system", "PixelSpace"), Map.entry("layout_width", 0),
                     Map.entry("layout_height", "bad"), Map.entry("points", List.of(List.of(1, 2))),
                     Map.entry("points", List.of(List.of(1), List.of(1), List.of(1), List.of(1))))) {
            final var geometry = new LinkedHashMap<Object, Object>(geometry(source));
            geometry.put(entry.getKey(), entry.getValue());
            final var broken = new Element("bad", ElementType.Table, "", Map.of("coordinates", geometry));
            assertThrows(IllegalArgumentException.class, () -> converter.toPoints(broken));
        }
        assertThrows(IllegalArgumentException.class, () -> converter.toPoints(source(Map.of("pdf_user_unit", 0))));
        assertThrows(IllegalArgumentException.class, () -> converter.toPixels(source, Double.MAX_VALUE));
        final var missingDpi = new LinkedHashMap<Object, Object>(geometry(source));
        missingDpi.put("system", "PDFPixels");
        assertThrows(IllegalArgumentException.class,
                () -> converter.toPoints(new Element("bad", ElementType.Image, "", Map.of("coordinates", missingDpi))));
    }

    private static Element source(final Map<String, Object> extra) {
        final var geometry = Map.of("system", "PDFUserSpace", "layout_width", 100.0, "layout_height", 200.0, "points",
                List.of(List.of(-10.0, 20.0), List.of(30.0, 20.0), List.of(30.0, 40.0), List.of(-10.0, 40.0)));
        final var metadata = new LinkedHashMap<>(extra);
        metadata.put("coordinates", geometry);
        metadata.put("pdf_table_cells", Map.of("version", 1, "geometry", "rule_centerlines", "cells",
                                                List.of(Map.of("row_index", 0, "column_index", 0, "row_span", 1,
                                                        "column_span", 2, "coordinates", geometry))));
        return new Element("table-id", ElementType.Table, "Item Qty", metadata);
    }

    private static Map<?, ?> geometry(final Element element) {
        return (Map<?, ?>)element.metadata().get("coordinates");
    }

    private static void check(final Element value, final String system, final double width, final double height,
            final double x, final double y) {
        final var geometry = geometry(value);
        assertEquals(system, geometry.get("system"));
        assertEquals(width, ((Number) geometry.get("layout_width")).doubleValue(), 1e-9);
        assertEquals(height, ((Number) geometry.get("layout_height")).doubleValue(), 1e-9);
        final var first = (List<?>) ((List<?>) geometry.get("points")).getFirst();
        assertEquals(x, ((Number) first.get(0)).doubleValue(), 1e-9);
        assertEquals(y, ((Number) first.get(1)).doubleValue(), 1e-9);
    }
}
