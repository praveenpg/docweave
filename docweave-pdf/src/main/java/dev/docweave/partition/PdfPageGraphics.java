package dev.docweave.partition;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.OperatorName;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.graphics.blend.BlendMode;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceCMYK;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.color.PDICCBased;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

import java.awt.geom.Area;
import java.awt.geom.GeneralPath;
import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Traverses painted paths and raster occurrences, including form XObjects, with an operator budget. */
final class PdfPageGraphics extends PDFGraphicsStreamEngine {
    /** Matches the form nesting accepted by the underlying PDFBox graphics operator. */
    private static final int MAX_FORM_DEPTH = 50;
    /** Hard source-program stack bound, independent from the larger shared page-operator budget. */
    private static final int MAX_TYPE3_DEPTH = 64;

    /**
     * Axis-aligned crop-relative top-left coordinates of a painted segment.
     * @param x1 original first horizontal coordinate
     * @param y1 original first vertical coordinate
     * @param x2 original second horizontal coordinate
     * @param y2 original second vertical coordinate
     */
    record Segment(float x1, float y1, float x2, float y2) {}

    /**
     * An image placement; metadata may contain optional normalized PNG bytes.
     * @param x displayed left position
     * @param y displayed top position
     * @param width displayed placement width
     * @param height displayed placement height
     * @param metadata immutable source/image-payload description
     */
    record Image(float x, float y, float width, float height, Map<String, Object> metadata) {}

    /** Page crop geometry. */
    private final PdfPageGeometry geometry;
    /** Active traversal budgets. */
    private final PdfOptions options;
    /** Shared document-level payload counter. */
    private final PdfImageBudget budget;
    /** Stroked segments and conservative filled-rule centerlines retained for strict grids. */
    private final List<Segment> segments = new ArrayList<>();
    /** Optional original pale row paint, allocated only for explicit shaded-row recognition. */
    private final List<FilledPdfRowBand> rowBands;
    /** Pending path edges, also used to validate single rectangular fills. */
    private List<Segment> pending = new ArrayList<>();
    /** Raster occurrences, including repeated and inline images. */
    private final List<Image> images = new ArrayList<>();
    /** Current path point in transformed PDF space. */
    private Point2D current = new Point2D.Float();
    /** Start of current subpath. */
    private Point2D start = current;
    /** Source rectangle-operator candidate in transformed PDF coordinates; later construction clears it. */
    private GeneralPath rectangleClip;
    /** At most six raw vertices for a line-built clip; ordinary move/line paint allocates no extra path. */
    private float[] lineClipVertices = new float[12];
    /** Active raw vertex count, or zero after unsupported construction. */
    private int lineClipPoints;
    /** Explicit closure prevents a later line from extending a candidate with a lost closure boundary. */
    private boolean lineClipClosed;
    /** Deferred supported clip, applied only after the current path paints or is discarded. */
    private GeneralPath deferredClip;
    /** Number of visited operators, including nested form streams. */
    private int operators;
    /** Whether a curve occurred in the current path, excluding filled-rule recognition. */
    private boolean curved;
    /** Number of path subpaths, capped at two because compound fills are unsupported. */
    private int subpaths;
    /** Invisible Type3 glyph scopes suppress native rule/band evidence while retaining source traversal. */
    private boolean type3PaintSuppressed;
    /** Active Type3 character-program scopes; restored even when a descendant fails. */
    private int type3Depth;

    /**
     * Temporarily owned caller path; child interpretation receives independent mutable path storage.
     * Graphics state, accumulated paint/images and page-global budgets remain owned by their existing services.
     * @param pending unfinished caller edges, transferred without copying
     * @param current original transformed path endpoint
     * @param start original subpath start
     * @param rectangleClip original rectangle-operator candidate
     * @param deferredClip original pending clipping instruction
     * @param vertices original fixed-size raw clipping vertices
     * @param points original active raw vertex count
     * @param closed original explicit closure marker
     * @param curved whether the original path contains curves
     * @param subpaths original bounded subpath count
     */
    private record PathState(List<Segment> pending, Point2D current, Point2D start, GeneralPath rectangleClip,
            GeneralPath deferredClip, float[] vertices, int points, boolean closed, boolean curved, int subpaths) {}

    /**
     * Transfers caller path ownership before entering a form, retaining no copied source-edge population.
     * @return suspended caller state, restored exactly once by the enclosing form callback
     */
    private PathState suspendPath() {
        final var state = new PathState(pending, current, start, rectangleClip, deferredClip, lineClipVertices,
                lineClipPoints, lineClipClosed, curved, subpaths);
        pending = new ArrayList<>();
        current = new Point2D.Float();
        start = current;
        rectangleClip = null;
        deferredClip = null;
        lineClipVertices = new float[12];
        lineClipPoints = 0;
        lineClipClosed = false;
        curved = false;
        subpaths = 0;
        return state;
    }

    /**
     * Restores only the suspended path, including after child failure or cancellation.
     * @param state original caller-owned path storage
     */
    private void resumePath(final PathState state) {
        pending = state.pending();
        current = state.current();
        start = state.start();
        rectangleClip = state.rectangleClip();
        deferredClip = state.deferredClip();
        lineClipVertices = state.vertices();
        lineClipPoints = state.points();
        lineClipClosed = state.closed();
        curved = state.curved();
        subpaths = state.subpaths();
    }

    /**
     * Interprets a form with a fresh path while PDFBox retains ownership of graphics-state/resource scopes.
     * @param form original nested form
     * @throws IOException if child parsing, cancellation or a document/page budget fails
     */
    @Override
    public void showForm(final PDFormXObject form) throws IOException {
        final var parent = suspendPath();
        try {
            super.showForm(form);
        } finally {
            resumePath(parent);
        }
    }

    /**
     * Applies the same path isolation to the separate transparency-group dispatch route.
     * This does not render group compositing or change source-paint eligibility.
     * @param group original transparency group
     * @throws IOException if child parsing, cancellation or existing budgets fail
     */
    @Override
    public void showTransparencyGroup(final PDTransparencyGroup group) throws IOException {
        final var parent = suspendPath();
        try {
            super.showTransparencyGroup(group);
        } finally {
            resumePath(parent);
        }
    }

    /**
     * Initializes a visitor with the shared displayed-page geometry.
     * @param page source page
     * @param options immutable budgets
     * @param budget document image counter
     * @param geometry shared crop/rotation transform
     */
    PdfPageGraphics(
            final PDPage page, final PdfOptions options, final PdfImageBudget budget, final PdfPageGeometry geometry) {
        super(page);
        this.geometry = geometry;
        rowBands = options.tableDetection().shadedRows() ? new ArrayList<>() : List.of();
        this.options = options;
        this.budget = budget;
    }

    /**
     * Bounds all content operators before interpreting operands or entering nested forms.
     * @param operator source operator
     * @param operands source operands
     * @throws IOException if cancelled, over budget or malformed
     */
    @Override
    protected void processOperator(final Operator operator, final List<COSBase> operands) throws IOException {
        ElementCollector.checkInterrupted();
        if (++operators > options.maxOperatorsPerPage()) {
            throw new PartitionException(PartitionException.Code.INPUT_TOO_LARGE, "PDF exceeds maxOperatorsPerPage");
        }
        checkFormDepth(operator, operands);
        super.processOperator(operator, operands);
    }

    /**
     * Converts PDFBox's otherwise silent form-depth truncation into a typed source-limit failure.
     * Image XObjects remain eligible at the deepest accepted form; only another form consumes a level.
     * @param operator original source operator
     * @param operands original arguments, retaining existing malformed-operand handling
     * @throws IOException if a nested form exceeds the source limit or resource lookup fails
     */
    private void checkFormDepth(final Operator operator, final List<COSBase> operands) throws IOException {
        if (getLevel() >= MAX_FORM_DEPTH && OperatorName.DRAW_OBJECT.equals(operator.getName()) && !operands.isEmpty()
                && operands.getFirst() instanceof final COSName name
                && getResources().getXObject(name) instanceof PDFormXObject) {
            throw new PartitionException(PartitionException.Code.INPUT_TOO_LARGE, "PDF form nesting exceeds 50 levels");
        }
    }

    /**
     * Retains source traversal inside Type3 glyphs without treating invisible character programs as painted rules.
     * Only rendering mode NEITHER suppresses Type3 paint; other modes keep the glyph's own drawing operators.
     * Source-image accounting, operator budgets and cancellation remain active, including in nested forms/glyphs.
     * Glyph paths are isolated from unfinished caller paths and restored even after traversal failure.
     * At most 64 simultaneous glyph programs are accepted; deeper or cyclic programs fail before stack exhaustion.
     * @param textRenderingMatrix original glyph transform
     * @param font source Type3 font
     * @param code original character code
     * @param displacement source glyph advance
     * @throws IOException if glyph traversal or a shared limit/cancellation check fails
     */
    @Override
    protected void showType3Glyph(final Matrix textRenderingMatrix, final PDType3Font font, final int code,
            final Vector displacement) throws IOException {
        ElementCollector.checkInterrupted();
        if (type3Depth >= MAX_TYPE3_DEPTH) {
            throw new PartitionException(
                    PartitionException.Code.INPUT_TOO_LARGE, "PDF Type3 glyph nesting exceeds 64 levels");
        }
        final boolean parent = type3PaintSuppressed;
        final var parentPath = suspendPath();
        type3Depth++;
        try {
            type3PaintSuppressed |= getGraphicsState().getTextState().getRenderingMode() == RenderingMode.NEITHER;
            super.showType3Glyph(textRenderingMatrix, font, code, displacement);
        } finally {
            resumePath(parentPath);
            type3PaintSuppressed = parent;
            type3Depth--;
        }
    }

    /**
     * Prevents PDFBox recovery from swallowing typed budget, cancellation or image failures.
     * @param operator source operator
     * @param operands source operands
     * @param failure interpretation failure
     * @throws IOException always, retaining the original typed exception
     */
    @Override
    protected void operatorException(final Operator operator, final List<COSBase> operands, final IOException failure)
            throws IOException {
        throw failure;
    }

    /**
     * Records the actual image occurrence rather than unused page resources.
     * @param image painted raster
     * @throws IOException if decoding, output limits or cancellation fails
     */
    @Override
    public void drawImage(final PDImage image) throws IOException {
        final var matrix = getGraphicsState().getCurrentTransformationMatrix();
        final var points = List.of(matrix.transformPoint(0, 0), matrix.transformPoint(1, 0),
                                       matrix.transformPoint(0, 1), matrix.transformPoint(1, 1))
                                   .stream()
                                   .map(point -> geometry.point(point.x, point.y))
                                   .toList();
        final float left = (float) points.stream().mapToDouble(Point2D::getX).min().orElseThrow();
        final float right = (float) points.stream().mapToDouble(Point2D::getX).max().orElseThrow();
        final float bottom = (float) points.stream().mapToDouble(Point2D::getY).min().orElseThrow();
        final float top = (float) points.stream().mapToDouble(Point2D::getY).max().orElseThrow();
        final var metadata = new LinkedHashMap<String, Object>();
        metadata.put("image_width", image.getWidth());
        metadata.put("image_height", image.getHeight());
        final var state = getGraphicsState();
        // Describe this invocation only; group composition and intrinsic sample alpha remain separate.
        metadata.put("pdf_image_graphics_state",
                Map.of("nonstroking_alpha", state.getNonStrokeAlphaConstant(), "blend_mode",
                        state.getBlendMode().getCOSName().getName(), "has_soft_mask", state.getSoftMask() != null,
                        "alpha_is_shape", state.isAlphaSource()));
        // Keep source unit-square corner identities: the enclosing rectangle loses shear and reflection.
        metadata.put("pdf_image_coordinates",
                Map.of("system", "PDFUserSpace", "layout_width", (double) geometry.width(), "layout_height",
                        (double) geometry.height(), "point_order", "source_unit_square_00_10_11_01", "points",
                        List.of(points.get(0), points.get(1), points.get(3), points.get(2))
                                .stream()
                                .map(point -> List.of(point.getX(), point.getY()))
                                .toList()));
        budget.extract(image, getGraphicsState().getNonStrokingColor(), metadata);
        images.add(new Image(left, bottom, right - left, top - bottom, metadata));
    }

    /**
     * Appends transformed rectangle edges to the current path.
     * @param p0 first corner
     * @param p1 second corner
     * @param p2 third corner
     * @param p3 fourth corner
     */
    @Override
    public void appendRectangle(final Point2D p0, final Point2D p1, final Point2D p2, final Point2D p3) {
        lineClipPoints = 0;
        // The rectangle operator remains a single bounded parallelogram after an affine source transform.
        rectangleClip =
                subpaths == 0
                                && List.of(p0, p1, p2, p3)
                                           .stream()
                                           .allMatch(point
                                                   -> Double.isFinite(point.getX()) && Double.isFinite(point.getY()))
                        ? new GeneralPath()
                        : null;
        if (rectangleClip != null) {
            rectangleClip.moveTo(p0.getX(), p0.getY());
            rectangleClip.lineTo(p1.getX(), p1.getY());
            rectangleClip.lineTo(p2.getX(), p2.getY());
            rectangleClip.lineTo(p3.getX(), p3.getY());
            rectangleClip.closePath();
        }
        subpaths = Math.min(2, subpaths + 1);
        edge(p0, p1);
        edge(p1, p2);
        edge(p2, p3);
        edge(p3, p0);
        current = p0;
        start = p0;
    }

    /**
     * Recognizes only exact axis alignment, leaving skewed or malformed rectangle clips unsupported.
     * @param first original transformed corner
     * @param second adjacent transformed corner
     * @return whether finite coordinates define an axis-aligned edge, including a degenerate edge
     */
    private static boolean axisAligned(final Point2D first, final Point2D second) {
        return Double.isFinite(first.getX()) && Double.isFinite(first.getY()) && Double.isFinite(second.getX())
                && Double.isFinite(second.getY()) && (first.getX() == second.getX() || first.getY() == second.getY());
    }

    /**
     * Adds a line in crop-relative coordinates; curves are not interpreted as table borders.
     * @param from transformed start
     * @param to transformed end
     */
    private void edge(final Point2D from, final Point2D to) {
        final var first = geometry.point((float) from.getX(), (float) from.getY());
        final var second = geometry.point((float) to.getX(), (float) to.getY());
        pending.add(new Segment(first.x, first.y, second.x, second.y));
    }

    /**
     * Starts a subpath and retains a bounded finite candidate for explicit line-built rectangle clipping.
     * @param x transformed horizontal coordinate
     * @param y transformed vertical coordinate
     */
    @Override
    public void moveTo(final float x, final float y) {
        rectangleClip = null;
        lineClipPoints = subpaths == 0 && Float.isFinite(x) && Float.isFinite(y) ? 1 : 0;
        lineClipClosed = false;
        if (lineClipPoints != 0) {
            lineClipVertices[0] = x;
            lineClipVertices[1] = y;
        }
        subpaths = Math.min(2, subpaths + 1);
        current = new Point2D.Float(x, y);
        start = current;
    }

    /**
     * Adds a straight path edge, retaining at most five axis-aligned candidate clipping edges.
     * @param x transformed end x
     * @param y transformed end y
     */
    @Override
    public void lineTo(final float x, final float y) {
        final var next = new Point2D.Float(x, y);
        rectangleClip = null;
        if (lineClipPoints > 0 && lineClipPoints < 6 && !lineClipClosed && axisAligned(current, next)) {
            lineClipVertices[lineClipPoints * 2] = x;
            lineClipVertices[lineClipPoints * 2 + 1] = y;
            lineClipPoints++;
        } else {
            lineClipPoints = 0;
        }
        edge(current, next);
        current = next;
    }

    /**
     * Updates the path endpoint without inventing a straight border for a curve.
     * @param x1 first control x
     * @param y1 first control y
     * @param x2 second control x
     * @param y2 second control y
     * @param x3 end x
     * @param y3 end y
     */
    @Override
    public void curveTo(
            final float x1, final float y1, final float x2, final float y2, final float x3, final float y3) {
        curved = true;
        rectangleClip = null;
        lineClipPoints = 0;
        current = new Point2D.Float(x3, y3);
    }

    /**
     * Returns the current transformed path point.
     * @return current point
     */
    @Override
    public Point2D getCurrentPoint() {
        return current;
    }

    /** Closes the current subpath and preserves a candidate only when its closing edge is axis-aligned. */
    @Override
    public void closePath() {
        lineClipPoints = axisAligned(current, start) ? lineClipPoints : 0;
        lineClipClosed = true;
        if (rectangleClip != null && axisAligned(current, start)) {
            rectangleClip.closePath();
        } else {
            rectangleClip = null;
        }
        edge(current, start);
        current = start;
    }

    /** Applies deferred supported clipping after painting, then clears all current-path state. */
    @Override
    public void endPath() {
        if (deferredClip != null) {
            getGraphicsState().intersectClippingPath(deferredClip);
        }
        deferredClip = null;
        rectangleClip = null;
        lineClipPoints = 0;
        lineClipClosed = false;
        pending.clear();
        curved = false;
        subpaths = 0;
    }

    /**
     * Retains nonzero-opacity stroke evidence only when the inherited clipping area is nonempty.
     * Conservative inherited bounding rectangles additionally reject wholly disjoint stroke envelopes.
     * Partial clipping, nonrectangular/skewed clips, dash patterns and final visibility remain unsupported.
     * @throws IOException if interrupted while filtering a bounded retained path
     */
    @Override
    public void strokePath() throws IOException {
        final var state = getGraphicsState();
        if (!type3PaintSuppressed && state.getAlphaConstant() > 0) {
            final var clip = PdfInheritedRuleClip.stroke(state, geometry);
            for (int index = 0; index < pending.size(); index++) {
                if ((index & 255) == 0) {
                    ElementCollector.checkInterrupted();
                }
                final var segment = pending.get(index);
                if (clip.intersects(segment)) {
                    segments.add(segment);
                }
            }
        }
        endPath();
    }

    /**
     * Retains nearly opaque dark thin single-rectangle centerlines, never arbitrary fill outlines.
     * The explicit shaded-row policy separately retains eligible pale rectangles without adding grid borders.
     * A single three-edge subpath is closed using PDF fill semantics before rectangle validation;
     * this does not close stroked paths, bridge grid gaps or combine separate subpaths.
     * @param windingRule source fill rule
     * @throws IOException if a supported device/ICC color cannot be read
     */
    @Override
    public void fillPath(final int windingRule) throws IOException {
        if (!curved && subpaths == 1 && pending.size() == 3) {
            // The PDF fill operator closes its subpath even when no explicit close-path operator occurs.
            edge(current, start);
        }
        final var state = getGraphicsState();
        final var color = state.getNonStrokingColor();
        final var space = color.getColorSpace();
        if (!type3PaintSuppressed && !curved && subpaths == 1 && pending.size() == 4
                && !state.getCurrentClippingPath().isEmpty() && state.getNonStrokeAlphaConstant() >= 0.99
                && state.getSoftMask() == null && state.getBlendMode() == BlendMode.NORMAL
                && (space instanceof PDDeviceGray || space instanceof PDDeviceRGB || space instanceof PDDeviceCMYK
                        || space instanceof PDICCBased)) {
            if (!PdfInheritedRuleClip.fill(state, geometry).intersects(pending)) {
                endPath();
                return;
            }
            if (options.tableDetection().shadedRows()) {
                final var band = FilledPdfRowBand.from(pending, color.toRGB());
                if (band != null) {
                    if (rowBands.size() >= 4096) {
                        throw new PartitionException(
                                PartitionException.Code.TABLE_LIMIT, "PDF shaded row paint exceeds page limit");
                    }
                    rowBands.add(band);
                }
            }
            final var rule = FilledPdfRule.centerline(pending);
            if (rule == null) {
                endPath();
                return;
            }
            final int rgb = color.toRGB();
            // All channels must be dark; pale backgrounds and white erasers are not rules.
            if (((rgb >> 16) & 255) <= 127 && ((rgb >> 8) & 255) <= 127 && (rgb & 255) <= 127) {
                segments.add(rule);
            }
        }
        endPath();
    }

    /**
     * Retains nonzero-opacity stroked edges, or independently supported fill evidence when strokes are invisible.
     * Fill interpretation uses the existing thin/dark/opacity constraints; an invisible stroke does not turn
     * an arbitrary filled outline into table borders. Positive-opacity strokes retain historical behavior.
     * @param windingRule source fill rule
     * @throws IOException if a supported fill color or existing paint budget cannot be interpreted
     */
    @Override
    public void fillAndStrokePath(final int windingRule) throws IOException {
        if (getGraphicsState().getAlphaConstant() > 0) {
            strokePath();
        } else {
            fillPath(windingRule);
        }
    }

    /**
     * Defers a single source rectangle clip until the current path finishes painting.
     * Affine-transformed rectangle operators and bounded axis-aligned move/line rectangles are supported.
     * Existing graphics-state save/restore and form scopes own its lifetime. Unsupported compound,
     * general skewed line-built and curved clip paths remain ignored; rule geometry is never partially trimmed.
     * @param windingRule source clip rule
     */
    @Override
    public void clip(final int windingRule) {
        deferredClip = null;
        if (!curved && subpaths == 1 && pending.size() >= 3 && pending.size() <= 5
                && (rectangleClip != null || axisAligned(current, start))) {
            GeneralPath candidate = rectangleClip;
            if (candidate == null && lineClipPoints >= 4) {
                candidate = new GeneralPath();
                candidate.moveTo(lineClipVertices[0], lineClipVertices[1]);
                for (int point = 1; point < lineClipPoints; point++) {
                    candidate.lineTo(lineClipVertices[point * 2], lineClipVertices[point * 2 + 1]);
                }
            }
            if (candidate == null) {
                return;
            }
            // W/W* implicitly close paths. A transformed re is bounded; line-built paths still require a rectangle.
            final var area = new Area(candidate);
            if (rectangleClip != null || area.isEmpty() || area.isRectangular()) {
                deferredClip = new GeneralPath(candidate);
            }
        }
    }

    /**
     * Leaves shadings outside the supported element model.
     * @param shadingName resource name
     */
    @Override
    public void shadingFill(final COSName shadingName) {
    }

    /**
     * Returns nonzero-opacity stroke geometry and validated filled-rule centerlines.
     * @return document-confined segments
     */
    List<Segment> segments() {
        return segments;
    }

    /**
     * Returns original pale row rectangles for the explicitly selected strategy.
     * @return page-confined band evidence; empty under historical policies
     */
    List<FilledPdfRowBand> rowBands() {
        return rowBands;
    }

    /**
     * Returns painted image occurrences.
     * @return document-confined placements
     */
    List<Image> images() {
        return images;
    }
}
