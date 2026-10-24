package io.docweave.partition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Projected baseline grouping for opt-in arbitrary-angle native text; no table or font-outline inference. */
final class PdfAngledText {
    /**
     * Crop-relative, unrotated PDFBox baseline metrics detached from font/parser objects.
     * @param text decoded glyph text
     * @param x source baseline start x
     * @param y source baseline start y
     * @param width baseline advance length
     * @param height approximate glyph height
     * @param space approximate space width
     * @param angle source counterclockwise baseline angle in degrees
     */
    record Position(String text, double x, double y, double width, double height, double space, double angle) {}

    /** Utility class. */
    private PdfAngledText() {
    }

    /**
     * Groups angles rounded to a hundredth degree, then reuses bounded ordinary baseline/gap grouping.
     * Displayed output uses enclosing rectangles; projected source orientation remains explicit.
     * @param positions page-confined source metrics, bounded by maxGlyphsPerPage
     * @param geometry displayed crop/quarter-turn frame
     * @return independent nonblank native lines
     * @throws IOException if interrupted or a projected coordinate cannot be represented
     */
    static List<PdfLayoutItem> lines(final List<Position> positions, final PdfPageGeometry geometry)
            throws IOException {
        final var groups = new LinkedHashMap<Long, List<Position>>();
        for (final var position : positions) {
            ElementCollector.checkInterrupted();
            groups.computeIfAbsent(Math.round(position.angle() * 100), ignored -> new ArrayList<>()).add(position);
        }
        final var output = new ArrayList<PdfLayoutItem>();
        for (final var group : groups.entrySet()) {
            ElementCollector.checkInterrupted();
            final double angle = group.getKey() / 100.0;
            final double cosine = Math.cos(Math.toRadians(angle));
            final double sine = Math.sin(Math.toRadians(angle));
            final var projected = new ArrayList<PdfPageText.Glyph>(group.getValue().size());
            for (final var position : group.getValue()) {
                ElementCollector.checkInterrupted();
                projected.add(
                        new PdfPageText.Glyph(position.text(), finite(cosine * position.x() + sine * position.y()),
                                finite(sine * position.x() - cosine * position.y()), finite(position.width()),
                                finite(position.height()), finite(position.space()), 0));
            }
            for (final var line : PdfGlyphLines.lines(projected)) {
                ElementCollector.checkInterrupted();
                final var box = line.box();
                float left = Float.POSITIVE_INFINITY;
                float top = Float.POSITIVE_INFINITY;
                float right = Float.NEGATIVE_INFINITY;
                float bottom = Float.NEGATIVE_INFINITY;
                for (final var pair : List.of(List.of(box.left(), box.top()), List.of(box.right(), box.top()),
                             List.of(box.right(), box.bottom()), List.of(box.left(), box.bottom()))) {
                    final var point = geometry.relativeTextPoint(finite(cosine * pair.get(0) + sine * pair.get(1)),
                            finite(sine * pair.get(0) - cosine * pair.get(1)));
                    left = Math.min(left, finite(point.x));
                    top = Math.min(top, finite(point.y));
                    right = Math.max(right, finite(point.x));
                    bottom = Math.max(bottom, finite(point.y));
                }
                output.add(
                        new PdfLayoutItem(line.type(), line.text(), new PdfLayoutItem.Bounds(left, top, right, bottom),
                                Map.of("pdf_text_rotation", geometry.textRotation(angle), "pdf_text_geometry",
                                        "projected_baseline_v1")));
            }
        }
        return List.copyOf(output);
    }

    /**
     * Keeps projected metrics representable by the native float geometry model.
     * @param value finite double metric
     * @return finite float metric
     * @throws PartitionException if source/projection metrics overflow or are nonfinite
     */
    private static float finite(final double value) throws PartitionException {
        if (!Float.isFinite((float) value)) {
            throw new PartitionException(PartitionException.Code.UNSUPPORTED_OPTION,
                    "Arbitrary-angle native PDF text geometry is not finite");
        }
        return (float) value;
    }
}
