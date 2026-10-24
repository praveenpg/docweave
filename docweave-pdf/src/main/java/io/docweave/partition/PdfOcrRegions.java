package io.docweave.partition;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Page-confined conservative image/native ownership. One masked raster avoids running OCR separately
 * over overlapping image placements. Geometry describes enclosing rectangles, not clipping/font outlines.
 */
final class PdfOcrRegions implements PdfOcrSelection {
    /** Fixed geometry-probe ceiling, including returned-line ownership checks. */
    private static final int MAX_PROBES = 2_000_000;
    /** Fixed sum of white-fill, image-copy and native-mask pixel visits per mixed page. */
    private static final long MAX_PIXEL_VISITS = 64_000_000;
    /** Native text guard band in displayed user-space units, compensating for approximate glyph metrics. */
    private static final float NATIVE_PADDING = 2;
    /** In-crop displayed image rectangles. */
    private final List<PdfLayoutItem.Bounds> candidates = new ArrayList<>();
    /** Native glyph metrics, retained only for this page. */
    private final List<PdfPageText.Glyph> glyphs;
    /** Additional projected native line envelopes, already in the displayed frame. */
    private final List<PdfLayoutItem.Bounds> angled;
    /** Displayed crop geometry. */
    private final PdfPageGeometry geometry;
    /** Deduplicated clipped image rectangles in actual rendered pixels. */
    private final Set<Rectangle> regions = new LinkedHashSet<>();
    /** Native glyph exclusion rectangles intersecting the image-region envelope. */
    private final List<Rectangle> nativeText = new ArrayList<>();
    /** Exact returned OCR line duplicates, distinguished by both text and pixel box. */
    private final Set<PdfOcrProvider.Line> accepted = new HashSet<>();
    /** Shared geometry traversal counter. */
    private int probes;
    /** Cumulative projected mask drawing work. */
    private long pixelVisits;
    /** Excluded provider lines; no text is retained for diagnostics. */
    private int rejected;

    /**
     * Collects visible enclosing image rectangles under an explicit placement ceiling.
     * @param images source raster occurrences
     * @param glyphs native glyphs retained by the native path
     * @param geometry displayed crop frame
     * @param options image-placement limit
     * @throws IOException if limits, cancellation or invalid geometry prevents region selection
     */
    PdfOcrRegions(final List<PdfPageGraphics.Image> images, final List<PdfPageText.Glyph> glyphs,
            final PdfPageGeometry geometry, final PdfOcrOptions options) throws IOException {
        this(images, glyphs, geometry, options, List.of());
    }

    /**
     * Adds arbitrary-angle native line exclusion envelopes to the existing mixed-page mask policy.
     * @param images raster placements
     * @param glyphs quarter-turn native positions
     * @param geometry displayed page frame
     * @param options region budget
     * @param angled displayed projected line bounds
     * @throws IOException if region limits or cancellation fail
     */
    PdfOcrRegions(final List<PdfPageGraphics.Image> images, final List<PdfPageText.Glyph> glyphs,
            final PdfPageGeometry geometry, final PdfOcrOptions options, final List<PdfLayoutItem.Bounds> angled)
            throws IOException {
        this.angled = List.copyOf(angled);
        this.glyphs = glyphs;
        this.geometry = geometry;
        if (images.size() > options.maxRegions()) {
            throw limit("PDF OCR exceeds maxRegions");
        }
        for (final var image : images) {
            visit();
            final var box = new PdfLayoutItem.Bounds(
                    image.x(), image.y(), image.x() + image.width(), image.y() + image.height());
            validate(box);
            final float left = Math.max(0, box.left());
            final float top = Math.max(0, box.top());
            final float right = Math.min(geometry.width(), box.right());
            final float bottom = Math.min(geometry.height(), box.bottom());
            if (right > left && bottom > top) {
                candidates.add(new PdfLayoutItem.Bounds(left, top, right, bottom));
            }
        }
    }

    /**
     * Indicates whether rendering can be avoided entirely.
     * @return true when every image placement is outside the displayed crop or degenerate
     */
    boolean isEmpty() {
        return candidates.isEmpty();
    }

    /**
     * Plans all work before allocating a second raster, then copies only image areas and masks native glyphs.
     * @param source caller-owned full rendered page
     * @return new owned RGB raster, to be flushed by the caller
     * @throws IOException if planning exceeds budgets or is interrupted
     */
    BufferedImage mask(final BufferedImage source) throws IOException {
        final int width = source.getWidth();
        final int height = source.getHeight();
        reserve((long) width * height);
        for (final var box : candidates) {
            visit();
            final var rectangle = pixels(box, 0, width, height);
            if (regions.add(rectangle)) {
                reserve(rectangle.area());
            }
        }
        final int left = regions.stream().mapToInt(Rectangle::left).min().orElseThrow();
        final int top = regions.stream().mapToInt(Rectangle::top).min().orElseThrow();
        final int right = regions.stream().mapToInt(Rectangle::right).max().orElseThrow();
        final int bottom = regions.stream().mapToInt(Rectangle::bottom).max().orElseThrow();
        final var envelope = new Rectangle(left, top, right, bottom);
        for (final var glyph : glyphs) {
            visit();
            if (glyph.text().isBlank()) {
                continue;
            }
            final var box = geometry.textBounds(new PdfLayoutItem.Bounds(glyph.x(), glyph.y() - glyph.height(),
                                                        glyph.x() + glyph.width(), glyph.y()),
                    glyph.direction());
            exclude(box, width, height, envelope);
        }
        for (final var box : angled) {
            visit();
            exclude(box, width, height, envelope);
        }
        final var masked = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        final var drawing = masked.createGraphics();
        try {
            drawing.setColor(Color.WHITE);
            drawing.fillRect(0, 0, width, height);
            for (final var region : regions) {
                visit();
                drawing.drawImage(source, region.left(), region.top(), region.right(), region.bottom(), region.left(),
                        region.top(), region.right(), region.bottom(), null);
            }
            for (final var rectangle : nativeText) {
                visit();
                drawing.fillRect(rectangle.left(), rectangle.top(), rectangle.right() - rectangle.left(),
                        rectangle.bottom() - rectangle.top());
            }
            return masked;
        } catch (final IOException | RuntimeException failure) {
            masked.flush();
            throw failure;
        } finally {
            drawing.dispose();
        }
    }

    /**
     * Adds one guarded native exclusion with the same pixel/work policy for either orientation family.
     * @param box displayed native enclosing bounds
     * @param width actual render width
     * @param height actual render height
     * @param envelope union of selected rendered image regions
     * @throws IOException if coordinates, projected pixel budget or cancellation fail
     */
    private void exclude(final PdfLayoutItem.Bounds box, final int width, final int height, final Rectangle envelope)
            throws IOException {
        validate(box);
        final var rectangle = pixels(box, NATIVE_PADDING, width, height);
        if (rectangle.right() > envelope.left() && rectangle.left() < envelope.right()
                && rectangle.bottom() > envelope.top() && rectangle.top() < envelope.bottom() && rectangle.area() > 0) {
            reserve(rectangle.area());
            nativeText.add(rectangle);
        }
    }

    /**
     * Retains lines contained in one selected image rectangle, disjoint from native text and not exact duplicates.
     * @param line provider line, already validated against raster and output budgets
     * @return whether this line owns an unambiguous image region
     * @throws IOException if ownership probes exceed the ceiling or are interrupted
     */
    @Override
    public boolean retain(final PdfOcrProvider.Line line) throws IOException {
        final boolean contained = owns(line);
        if (!contained || !accepted.add(line)) {
            rejected++;
            return false;
        }
        return true;
    }

    /**
     * Tests region/native ownership without recording a line or affecting duplicate/exclusion diagnostics.
     * @param line rectangle expressed using the provider line value; its text is ignored
     * @return whether one image region contains the rectangle without native overlap
     * @throws IOException if interrupted or geometry work is exhausted
     */
    @Override
    public boolean owns(final PdfOcrProvider.Line line) throws IOException {
        boolean contained = false;
        for (final var region : regions) {
            visit();
            if (region.left() <= line.left() && region.top() <= line.top() && region.right() >= line.right()
                    && region.bottom() >= line.bottom()) {
                contained = true;
                break;
            }
        }
        if (contained) {
            for (final var rectangle : nativeText) {
                visit();
                if (rectangle.left() < line.right() && rectangle.right() > line.left()
                        && rectangle.top() < line.bottom() && rectangle.bottom() > line.top()) {
                    contained = false;
                    break;
                }
            }
        }
        return contained;
    }

    /**
     * Records the selected geometric policy without claiming semantic region detection.
     * @param metadata mutable pending OCR element metadata
     */
    void describe(final Map<String, Object> metadata) {
        metadata.put("pdf_ocr_selection", "image_regions_v1");
        metadata.put("pdf_ocr_region_count", regions.size());
    }

    /**
     * Reports exclusions without embedding provider text in diagnostics.
     * @param context document diagnostics sink
     */
    void finish(final PdfPageOutput context) {
        if (rejected > 0) {
            context.warn(PartitionResult.WarningCode.PDF_EXTRACTION_LIMITATION,
                    "Mixed-page OCR excluded " + rejected + " ambiguous, native-overlapping or duplicate lines");
        }
    }

    /**
     * Clips and rounds displayed user-space bounds outward into actual raster pixels.
     * @param box displayed box
     * @param padding guard band in user-space units
     * @param width actual raster width
     * @param height actual raster height
     * @return clipped integer rectangle, possibly empty
     */
    private Rectangle pixels(final PdfLayoutItem.Bounds box, final float padding, final int width, final int height) {
        final double xScale = width / (double) geometry.width();
        final double yScale = height / (double) geometry.height();
        return new Rectangle((int) Math.max(0, Math.min(width, Math.floor((box.left() - padding) * xScale))),
                (int) Math.max(0, Math.min(height, Math.floor((box.top() - padding) * yScale))),
                (int) Math.max(0, Math.min(width, Math.ceil((box.right() + padding) * xScale))),
                (int) Math.max(0, Math.min(height, Math.ceil((box.bottom() + padding) * yScale))));
    }

    /**
     * Rejects nonfinite or reversed source geometry explicitly.
     * @param box displayed source bounds
     * @throws PartitionException if the source geometry is invalid
     */
    private static void validate(final PdfLayoutItem.Bounds box) throws PartitionException {
        if (!Float.isFinite(box.left()) || !Float.isFinite(box.top()) || !Float.isFinite(box.right())
                || !Float.isFinite(box.bottom()) || box.right() < box.left() || box.bottom() < box.top()) {
            throw new PartitionException(PartitionException.Code.MALFORMED_DOCUMENT, "Invalid PDF OCR region geometry");
        }
    }

    /**
     * Counts geometry work and cancellation boundaries across selection, drawing and ownership.
     * @throws IOException if cancelled or over budget
     */
    private void visit() throws IOException {
        ElementCollector.checkInterrupted();
        if (++probes > MAX_PROBES) {
            throw limit("PDF OCR region geometry exceeds work ceiling");
        }
    }

    /**
     * Admits a projected mask operation without overflow or an unbounded graphics loop.
     * @param pixels number of affected raster pixels
     * @throws PartitionException if cumulative drawing work is over budget
     */
    private void reserve(final long pixels) throws PartitionException {
        if (pixels > MAX_PIXEL_VISITS - pixelVisits) {
            throw limit("PDF OCR region masking exceeds pixel-work ceiling");
        }
        pixelVisits += pixels;
    }

    /**
     * Creates a stable typed resource failure without source text.
     * @param message limit diagnostic
     * @return typed OCR budget failure
     */
    private static PartitionException limit(final String message) {
        return new PartitionException(PartitionException.Code.OCR_LIMIT, message);
    }

    /**
     * Clipped integer raster rectangle.
     * @param left left edge
     * @param top top edge
     * @param right exclusive right edge
     * @param bottom exclusive bottom edge
     */
    private record Rectangle(int left, int top, int right, int bottom) {
        /**
         * Returns area without integer multiplication overflow.
         * @return nonnegative affected pixels
         */
        long area() {
            return (long) (right - left) * (bottom - top);
        }
    }
}
