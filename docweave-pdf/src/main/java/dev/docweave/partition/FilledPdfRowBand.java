package dev.docweave.partition;

import java.util.List;

/**
 * Pale rectangular paint or an explicit envelope of matching cell paint, never promoted to grid borders.
 * @param bounds unscaled displayed rectangle or inferred row envelope
 * @param rgb source device RGB used to keep distinct paint groups separate
 */
record FilledPdfRowBand(PdfLayoutItem.Bounds bounds, int rgb) {
    /**
     * Requires four connected axis-aligned edges, bounded cell dimensions and pale color eligibility.
     * Opacity, blending and source color-space checks belong to the graphics visitor.
     * Row aspect ratio is validated after matching cell paint has been coalesced: an individual
     * cell can be taller than a row-shaped rectangle without losing its original source evidence.
     * @param edges one original transformed closed subpath
     * @param rgb original converted device RGB
     * @return source band or null for unsupported/ambiguous paint
     */
    static FilledPdfRowBand from(final List<PdfPageGraphics.Segment> edges, final int rgb) {
        if (edges.size() != 4 || (rgb >> 16 & 255) < 128 || (rgb >> 8 & 255) < 128 || (rgb & 255) < 128
                || (rgb >> 16 & 255) >= 250 && (rgb >> 8 & 255) >= 250 && (rgb & 255) >= 250) {
            return null;
        }
        float left = Float.POSITIVE_INFINITY;
        float top = Float.POSITIVE_INFINITY;
        float right = Float.NEGATIVE_INFINITY;
        float bottom = Float.NEGATIVE_INFINITY;
        for (int index = 0; index < 4; index++) {
            final var edge = edges.get(index);
            final var next = edges.get((index + 1) % 4);
            final boolean horizontal = edge.y1() == edge.y2() && edge.x1() != edge.x2();
            final boolean vertical = edge.x1() == edge.x2() && edge.y1() != edge.y2();
            if (!Float.isFinite(edge.x1()) || !Float.isFinite(edge.y1()) || !horizontal && !vertical
                    || edge.x2() != next.x1() || edge.y2() != next.y1() || horizontal == (next.y1() == next.y2())) {
                return null;
            }
            left = Math.min(left, edge.x1());
            top = Math.min(top, edge.y1());
            right = Math.max(right, edge.x1());
            bottom = Math.max(bottom, edge.y1());
        }
        return right - left >= 48 && bottom - top >= 3 && bottom - top <= 60
                ? new FilledPdfRowBand(new PdfLayoutItem.Bounds(left, top, right, bottom), rgb)
                : null;
    }
}
