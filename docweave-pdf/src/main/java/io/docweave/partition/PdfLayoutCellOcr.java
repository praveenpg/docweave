package io.docweave.partition;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Page-confined bounded raster-cell recognition independent of any layout/model implementation. */
final class PdfLayoutCellOcr {
    /** White context around source pixels without removing estimated cell edges. */
    private static final int PADDING = 12;
    /** Shared source/fill-copy projected work ceiling per page. */
    private static final long MAX_PIXEL_VISITS = 64_000_000;
    /** Caller-owned OCR strategy. */
    private final PdfOcrProvider provider;
    /** Shared raw/inserted page output accounting. */
    private final PdfOcrBudget budget;
    /** Structured output policy. */
    private final TableLimits limits;
    /** Shared cancellation/geometry work policy. */
    private final PdfTableWork work;
    /** Explicit context in rendered input pixels;0 retains historical source crops. */
    private final int contextPixels;

    /**
     * Outward-rounded source rectangle, excluding existing white padding.
     * @param left inclusive source pixel edge
     * @param top inclusive source pixel edge
     * @param right exclusive source pixel edge
     * @param bottom exclusive source pixel edge
     */
    private record CropBounds(int left, int top, int right, int bottom) {}

    /**
     * Preflights every eligible crop before any per-cell external call or raster allocation.
     * @param layouts validated selected complete layouts
     * @param provider explicit caller-owned recognition strategy
     * @param budget shared PAGE/CELL aggregate output budget
     * @param options raster, source-context and raw-anchor limits
     * @param limits structured text limits
     * @param work shared cancellation/work policy
     * @throws IOException if any projected crop/work exceeds its ceiling
     */
    PdfLayoutCellOcr(final List<PdfTableLayoutProvider.Table> layouts, final PdfOcrProvider provider,
            final PdfOcrBudget budget, final PdfOcrOptions options, final TableLimits limits, final PdfTableWork work)
            throws IOException {
        this.provider = provider;
        this.budget = budget;
        this.limits = limits;
        this.work = work;
        contextPixels = options.cellContextPixels();
        long visits = 0;
        for (final var table : layouts) {
            for (final var anchor : table.anchors()) {
                work.step();
                final var box = bounds(anchor.bounds(), table.bounds());
                final long width = (long) box.right() - box.left() + 2 * PADDING;
                final long height = (long) box.bottom() - box.top() + 2 * PADDING;
                final long pixels = width * height;
                if (width > Integer.MAX_VALUE || height > Integer.MAX_VALUE || pixels > options.maxRenderPixels()
                        || pixels > (MAX_PIXEL_VISITS - visits) / 2) {
                    throw new PartitionException(
                            PartitionException.Code.OCR_LIMIT, "Provider layout cell exceeds raster/work budget");
                }
                visits += 2 * pixels;
            }
        }
    }

    /**
     * Copies one crop at a time, recognizes in TABLE_CELL purpose and releases owned raster storage.
     * Source pixels are never modified; cell boundaries use outward floor/ceil rounding and white padding.
     * Optional context is clamped to the table before rounding; reported anchor rectangles remain unchanged.
     * @param image borrowed rendered/native-masked source raster
     * @param anchors validated row-major source-pixel rectangles
     * @param table enclosing provider table, limiting optional source context
     * @return immutable authoritative cell text, including empty results
     * @throws IOException if recognition, cancellation or aggregate/cell text limits fail
     */
    List<String> recognize(final BufferedImage image, final List<PdfTableLayoutProvider.Anchor> anchors,
            final PdfTableLayoutProvider.Box table) throws IOException {
        final var values = new ArrayList<String>();
        for (final var anchor : anchors) {
            work.step();
            final var box = bounds(anchor.bounds(), table);
            final int left = box.left();
            final int top = box.top();
            final int right = box.right();
            final int bottom = box.bottom();
            final var crop = new BufferedImage(
                    right - left + 2 * PADDING, bottom - top + 2 * PADDING, BufferedImage.TYPE_INT_RGB);
            try {
                final var graphics = crop.createGraphics();
                try {
                    graphics.setColor(Color.WHITE);
                    graphics.fillRect(0, 0, crop.getWidth(), crop.getHeight());
                    graphics.drawImage(image, PADDING, PADDING, PADDING + right - left, PADDING + bottom - top, left,
                            top, right, bottom, null);
                } finally {
                    graphics.dispose();
                }
                final var lines = budget.recognize(provider, crop, PdfOcrProvider.InputKind.TABLE_CELL)
                                          .stream()
                                          .map(PdfOcrProvider.Line::text)
                                          .filter(value -> !value.isBlank())
                                          .toList();
                budget.reserveCharacters(Math.max(0, lines.size() - 1));
                final var text = String.join(" ", lines);
                if (text.codePointCount(0, text.length()) > limits.maxCellCharacters()) {
                    throw new PartitionException(
                            PartitionException.Code.TABLE_LIMIT, "Provider layout cell exceeds maxCellCharacters");
                }
                values.add(text);
            } finally {
                crop.flush();
            }
        }
        return List.copyOf(values);
    }

    /**
     * Uses identical source geometry for preflight and allocation, without modifying reported anchors.
     * Zero context bypasses table clamping to preserve every historical rounding decision.
     * @param anchor validated source anchor inside the table/image
     * @param table enclosing validated provider table
     * @return bounded outward-rounded source pixels; white padding is added separately
     */
    private CropBounds bounds(final PdfTableLayoutProvider.Box anchor, final PdfTableLayoutProvider.Box table) {
        if (contextPixels == 0) {
            return new CropBounds((int) Math.floor(anchor.left()), (int) Math.floor(anchor.top()),
                    (int) Math.ceil(anchor.right()), (int) Math.ceil(anchor.bottom()));
        }
        return new CropBounds((int) Math.floor(Math.max(table.left(), anchor.left() - contextPixels)),
                (int) Math.floor(Math.max(table.top(), anchor.top() - contextPixels)),
                (int) Math.ceil(Math.min(table.right(), anchor.right() + contextPixels)),
                (int) Math.ceil(Math.min(table.bottom(), anchor.bottom() + contextPixels)));
    }
}
