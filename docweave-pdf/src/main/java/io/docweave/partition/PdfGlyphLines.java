package io.docweave.partition;

import io.docweave.core.ElementType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/** Shared native glyph grouping without semantic classification or source-coordinate transforms. */
final class PdfGlyphLines {
    /** Utility type; all source positions remain document-confined. */
    private PdfGlyphLines() {
    }

    /**
     * Groups one source orientation before mapping its bounds into the displayed page frame.
     * @param input glyphs sharing an upright coordinate system
     * @return native upright lines
     * @throws IOException if interrupted
     */
    static List<PdfLayoutItem> lines(final List<PdfPageText.Glyph> input) throws IOException {
        return lines(input, PdfPageText.Glyph::y);
    }

    /**
     * Groups with explicit comparison baselines while retaining original glyph bounds and identities.
     * @param input complete native positions
     * @param groupingBaseline document-confined baseline assignment, used only for grouping
     * @return original-coordinate native runs
     * @throws IOException if interrupted
     */
    static List<PdfLayoutItem> lines(final List<PdfPageText.Glyph> input,
            final ToDoubleFunction<PdfPageText.Glyph> groupingBaseline) throws IOException {
        final var sorted = new ArrayList<>(input);
        sorted.sort(Comparator.comparingDouble(groupingBaseline).thenComparingDouble(PdfPageText.Glyph::x));
        final var rows = new ArrayList<List<PdfPageText.Glyph>>();
        double baseline = Double.NaN;
        for (final var glyph : sorted) {
            ElementCollector.checkInterrupted();
            if (rows.isEmpty() || Math.abs(groupingBaseline.applyAsDouble(glyph) - baseline) > 2) {
                rows.add(new ArrayList<>());
                baseline = groupingBaseline.applyAsDouble(glyph);
            }
            rows.getLast().add(glyph);
        }
        final var items = new ArrayList<PdfLayoutItem>();
        for (final var row : rows) {
            row.sort(Comparator.comparingDouble(PdfPageText.Glyph::x));
            final var run = new ArrayList<PdfPageText.Glyph>();
            float right = Float.NaN;
            for (final var glyph : row) {
                if (!run.isEmpty() && glyph.x() - right > Math.max(12, glyph.space() * 3)) {
                    addLine(items, run);
                    run.clear();
                }
                run.add(glyph);
                right = glyph.x() + glyph.width();
            }
            addLine(items, run);
        }
        return items;
    }

    /**
     * Groups each native baseline direction separately and orders enclosing runs in the displayed frame.
     * This preserves text-direction spacing while avoiding another crop/rotation of already adjusted text.
     * @param input immutable source positions sharing the page, possibly different quarter-turn baselines
     * @param geometry original displayed crop/rotation frame
     * @return nonblank lines in stable top/left order; not semantic article order
     * @throws IOException if cancelled during source grouping
     */
    static List<PdfLayoutItem> displayed(final List<PdfPageText.Glyph> input, final PdfPageGeometry geometry)
            throws IOException {
        return displayed(input, geometry, PdfPageText.Glyph::y);
    }

    /**
     * Groups like {@link #displayed(List, PdfPageGeometry)} with explicit comparison baselines per direction.
     * Baselines only decide row membership; original glyph bounds, text and identities are retained.
     * @param input immutable source positions sharing the page, possibly different quarter-turn baselines
     * @param geometry original displayed crop/rotation frame
     * @param groupingBaseline document-confined baseline assignment, used only for grouping
     * @return nonblank lines in stable top/left order; not semantic article order
     * @throws IOException if cancelled during source grouping
     */
    static List<PdfLayoutItem> displayed(final List<PdfPageText.Glyph> input, final PdfPageGeometry geometry,
            final ToDoubleFunction<PdfPageText.Glyph> groupingBaseline) throws IOException {
        if (input.isEmpty()) {
            return List.of();
        }
        if (input.stream().allMatch(glyph -> geometry.textRotation(glyph.direction()) == 0)) {
            return lines(input, groupingBaseline);
        }
        final var result = new ArrayList<PdfLayoutItem>();
        for (final int direction : new int[] {0, 90, 180, 270}) {
            final var oriented = input.stream().filter(glyph -> glyph.direction() == direction).toList();
            for (final var item : lines(oriented, groupingBaseline)) {
                final int rotation = geometry.textRotation(direction);
                result.add(new PdfLayoutItem(item.type(), item.text(), geometry.textBounds(item.box(), direction),
                        rotation == 0 ? item.metadata() : Map.of("pdf_text_rotation", rotation)));
            }
        }
        result.sort(Comparator.comparingDouble((PdfLayoutItem item) -> item.box().top())
                        .thenComparingDouble(item -> item.box().left()));
        return result;
    }

    /**
     * Builds one line, inserting spaces only for sufficiently separated glyphs.
     * @param items destination list
     * @param run non-retained source positions
     */
    private static void addLine(final List<PdfLayoutItem> items, final List<PdfPageText.Glyph> run) {
        if (run.isEmpty()) {
            return;
        }
        final var text = new StringBuilder();
        float left = Float.POSITIVE_INFINITY;
        float top = Float.POSITIVE_INFINITY;
        float right = Float.NEGATIVE_INFINITY;
        float bottom = Float.NEGATIVE_INFINITY;
        for (final var glyph : run) {
            if (!text.isEmpty() && glyph.x() - right > Math.max(1, glyph.space() * 0.35)
                    && !Character.isWhitespace(text.charAt(text.length() - 1)) && !glyph.text().startsWith(" ")) {
                text.append(' ');
            }
            text.append(glyph.text());
            left = Math.min(left, glyph.x());
            top = Math.min(top, glyph.y() - glyph.height());
            right = Math.max(right, glyph.x() + glyph.width());
            bottom = Math.max(bottom, glyph.y());
        }
        final String value = text.toString().strip();
        if (!value.isBlank()) {
            items.add(new PdfLayoutItem(ElementType.UncategorizedText, value,
                    new PdfLayoutItem.Bounds(left, top, right, bottom), Map.of()));
        }
    }
}
