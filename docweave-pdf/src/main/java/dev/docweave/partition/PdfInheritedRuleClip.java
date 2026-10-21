package dev.docweave.partition;

import org.apache.pdfbox.pdmodel.graphics.state.PDGraphicsState;

import java.awt.geom.Point2D;
import java.util.List;

/**
 * Conservative displayed enclosing rectangle of an inherited clip, never a general path-visibility oracle.
 * @param left minimum displayed horizontal edge including conservative stroke allowance
 * @param top minimum displayed vertical edge
 * @param right maximum displayed horizontal edge
 * @param bottom maximum displayed vertical edge
 * @param empty whether the inherited area is known to be empty
 */
record PdfInheritedRuleClip(double left, double top, double right, double bottom, boolean empty) {
    /** Unrepresentable geometry and device-dependent hairlines retain historical evidence. */
    private static final PdfInheritedRuleClip UNBOUNDED = new PdfInheritedRuleClip(Double.NEGATIVE_INFINITY,
            Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, false);
    /** Proven empty inherited area, independent of stroke allowance. */
    private static final PdfInheritedRuleClip EMPTY = new PdfInheritedRuleClip(0, 0, 0, 0, true);

    /**
     * Encloses inherited clip bounds with a conservative transformed line-width/cap/miter allowance.
     * Zero-width hairlines have device-dependent extent and deliberately retain existing evidence.
     * @param state current source graphics state
     * @param geometry source-to-displayed page transform
     * @return immutable conservative envelope
     */
    static PdfInheritedRuleClip stroke(final PDGraphicsState state, final PdfPageGeometry geometry) {
        final var matrix = state.getCurrentTransformationMatrix();
        final double width = Math.abs(state.getLineWidth());
        final double extent = width == 0 ? Double.POSITIVE_INFINITY
                                         : width * Math.max(1, Math.abs(state.getMiterLimit()))
                                                   * (matrix.getScalingFactorX() + matrix.getScalingFactorY());
        return create(state, geometry, extent);
    }

    /**
     * Encloses fill clipping without extending source paint by stroke metrics.
     * @param state current source graphics state
     * @param geometry source-to-displayed page transform
     * @return immutable conservative envelope
     */
    static PdfInheritedRuleClip fill(final PDGraphicsState state, final PdfPageGeometry geometry) {
        return create(state, geometry, 0);
    }

    /**
     * Transforms only the four corners of the inherited area's bounding rectangle.
     * The envelope is a superset of a rotated or nonrectangular actual clip, so disjointness is sufficient
     * for rejecting evidence; overlap does not prove visibility. No new clip intersections are computed.
     * @param state existing PDFBox clipping state
     * @param geometry original displayed-page transform
     * @param extent conservative additional stroke allowance, or infinity for unsupported metrics
     * @return bounded envelope, known-empty marker or conservative unbounded fallback
     */
    private static PdfInheritedRuleClip create(
            final PDGraphicsState state, final PdfPageGeometry geometry, final double extent) {
        final var area = state.getCurrentClippingPath();
        if (area.isEmpty()) {
            return EMPTY;
        }
        if (!Double.isFinite(extent)) {
            return UNBOUNDED;
        }
        final var bounds = area.getBounds2D();
        // Outward source rounding keeps the enclosing interval conservative in the shared float page frame.
        final float rawLeft = Math.nextDown((float) bounds.getMinX());
        final float rawTop = Math.nextDown((float) bounds.getMinY());
        final float rawRight = Math.nextUp((float) bounds.getMaxX());
        final float rawBottom = Math.nextUp((float) bounds.getMaxY());
        final var points = List.of(point(geometry, rawLeft, rawTop), point(geometry, rawRight, rawTop),
                point(geometry, rawLeft, rawBottom), point(geometry, rawRight, rawBottom));
        double left = Double.POSITIVE_INFINITY;
        double top = Double.POSITIVE_INFINITY;
        double right = Double.NEGATIVE_INFINITY;
        double bottom = Double.NEGATIVE_INFINITY;
        for (final var point : points) {
            if (!Double.isFinite(point.getX()) || !Double.isFinite(point.getY())) {
                return UNBOUNDED;
            }
            left = Math.min(left, point.getX());
            top = Math.min(top, point.getY());
            right = Math.max(right, point.getX());
            bottom = Math.max(bottom, point.getY());
        }
        return new PdfInheritedRuleClip(Math.nextDown((float) left) - extent, Math.nextDown((float) top) - extent,
                Math.nextUp((float) right) + extent, Math.nextUp((float) bottom) + extent, false);
    }

    /**
     * Uses the same crop/rotation/UserUnit interpretation as retained rule geometry.
     * @param geometry displayed page frame
     * @param x original transformed PDF horizontal position
     * @param y original transformed PDF vertical position
     * @return displayed source-point position
     */
    private static Point2D point(final PdfPageGeometry geometry, final double x, final double y) {
        return geometry.point((float) x, (float) y);
    }

    /**
     * Tests a complete line envelope while preserving touching/unsupported evidence conservatively.
     * @param segment original displayed straight edge
     * @return false only for proven empty or disjoint inherited clip envelopes
     */
    boolean intersects(final PdfPageGraphics.Segment segment) {
        return overlaps(Math.min(segment.x1(), segment.x2()), Math.min(segment.y1(), segment.y2()),
                Math.max(segment.x1(), segment.x2()), Math.max(segment.y1(), segment.y2()));
    }

    /**
     * Tests the complete filled rectangle, including cases where the clip lies strictly inside all its edges.
     * @param edges original four-edge rectangle, not its derived centerline
     * @return false only for proven empty or disjoint enclosing rectangles
     */
    boolean intersects(final List<PdfPageGraphics.Segment> edges) {
        double x1 = Double.POSITIVE_INFINITY;
        double y1 = Double.POSITIVE_INFINITY;
        double x2 = Double.NEGATIVE_INFINITY;
        double y2 = Double.NEGATIVE_INFINITY;
        for (final var edge : edges) {
            x1 = Math.min(x1, Math.min(edge.x1(), edge.x2()));
            y1 = Math.min(y1, Math.min(edge.y1(), edge.y2()));
            x2 = Math.max(x2, Math.max(edge.x1(), edge.x2()));
            y2 = Math.max(y2, Math.max(edge.y1(), edge.y2()));
        }
        return overlaps(x1, y1, x2, y2);
    }

    /**
     * Rejects only finite bounding envelopes wholly outside the conservative inherited clip envelope.
     * @param x1 minimum source horizontal position
     * @param y1 minimum source vertical position
     * @param x2 maximum source horizontal position
     * @param y2 maximum source vertical position
     * @return whether original evidence must remain available to existing interpretation
     */
    private boolean overlaps(final double x1, final double y1, final double x2, final double y2) {
        return !empty
                && (!Double.isFinite(x1) || !Double.isFinite(y1) || !Double.isFinite(x2) || !Double.isFinite(y2)
                        || x2 >= left && x1 <= right && y2 >= top && y1 <= bottom);
    }
}
