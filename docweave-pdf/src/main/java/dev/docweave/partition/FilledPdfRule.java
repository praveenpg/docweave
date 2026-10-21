package dev.docweave.partition;

import java.util.List;

/** Converts only one closed axis-aligned thin rectangular fill to an unscaled displayed-space centerline. */
final class FilledPdfRule {
    /** Utility type; source paint and color eligibility remain the graphics visitor's responsibility. */
    private FilledPdfRule() {
    }

    /**
     * Validates four connected nonzero orthogonal edges and a thin elongated bounding rectangle.
     * Normalizes each longitudinal cap by half the thin dimension (at most0.5 units),
     * accommodating separately painted junctions without relaxing the downstream grid tolerance.
     * @param edges displayed path edges, at most four are inspected
     * @return centerline, or null for unsupported/ambiguous geometry
     */
    static PdfPageGraphics.Segment centerline(final List<PdfPageGraphics.Segment> edges) {
        if (edges.size() != 4) {
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
                    || edge.x2() != next.x1() || edge.y2() != next.y1()) {
                return null;
            }
            // Adjacent edges must turn, excluding backtracking and collinear fragments.
            if (horizontal == (next.y1() == next.y2())) {
                return null;
            }
            left = Math.min(left, edge.x1());
            top = Math.min(top, edge.y1());
            right = Math.max(right, edge.x1());
            bottom = Math.max(bottom, edge.y1());
        }
        final float width = right - left;
        final float height = bottom - top;
        final float thin = Math.min(width, height);
        final float length = Math.max(width, height);
        if (!Float.isFinite(width) || !Float.isFinite(height) || thin <= 0 || thin > 1 || length < 4
                || length < thin * 8) {
            return null;
        }
        final float cap = thin / 2;
        return width > height
                ? new PdfPageGraphics.Segment(left - cap, top + height / 2, right + cap, top + height / 2)
                : new PdfPageGraphics.Segment(left + width / 2, top - cap, left + width / 2, bottom + cap);
    }
}
