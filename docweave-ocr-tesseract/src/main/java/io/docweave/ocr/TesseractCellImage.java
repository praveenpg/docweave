package io.docweave.ocr;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Call-confined optional edge-rule whitening, preserving source coordinates and borrowed image ownership. */
final class TesseractCellImage implements AutoCloseable {
    /** Maximum searched distance from each edge; independent of source document contents. */
    private static final int BAND = 24;
    /** Borrowed original image, never modified or flushed. */
    private final BufferedImage source;
    /** Borrowed original or owned same-size RGB copy. */
    private final BufferedImage image;

    /**
     * Detects edge rows/columns with at least80percent dark pixels; narrow/tiny cells remain unchanged.
     * Source dimensions and admission limits are checked by the caller before this bounded scan/copy.
     * @param source borrowed input
     * @param remove explicit TABLE_CELL-only removal decision
     * @param deadline existing total admission/recognition budget
     * @throws IOException if scanning/copying exceeds its deadline or is interrupted
     */
    TesseractCellImage(final BufferedImage source, final boolean remove, final OcrDeadline deadline)
            throws IOException {
        this.source = source;
        deadline.remaining();
        if (!remove || source.getWidth() < 64 || source.getHeight() < 64) {
            image = source;
            return;
        }
        final var rows = edges(source, false, deadline);
        final var columns = edges(source, true, deadline);
        if (rows.isEmpty() && columns.isEmpty()) {
            image = source;
            return;
        }
        image = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        final var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.drawImage(source, 0, 0, null);
            graphics.setColor(Color.WHITE);
            for (final int row : rows) {
                graphics.fillRect(0, row, image.getWidth(), 1);
            }
            for (final int column : columns) {
                graphics.fillRect(column, 0, 1, image.getHeight());
            }
        } finally {
            graphics.dispose();
        }
        try {
            deadline.remaining();
        } catch (final IOException failure) {
            image.flush();
            throw failure;
        }
    }

    /**
     * Scans at most48lines per axis with cooperative checks every256pixels and no full-image scratch.
     * @param source borrowed raster
     * @param vertical whether candidate lines are vertical
     * @param deadline total call budget
     * @return at most48 private line indices, without source pixel storage
     * @throws IOException if cancellation/deadline prevents scanning
     */
    private static List<Integer> edges(final BufferedImage source, final boolean vertical, final OcrDeadline deadline)
            throws IOException {
        final int across = vertical ? source.getWidth() : source.getHeight();
        final int along = vertical ? source.getHeight() : source.getWidth();
        final var result = new ArrayList<Integer>();
        for (int edge = 0; edge < 2 * BAND; edge++) {
            final int index = edge < BAND ? edge : across - 1 - (edge - BAND);
            long dark = 0;
            for (int offset = 0; offset < along; offset++) {
                if ((offset & 255) == 0) {
                    deadline.remaining();
                }
                final int rgb = source.getRGB(vertical ? index : offset, vertical ? offset : index);
                if ((rgb >>> 24) >= 128 && ((rgb >>> 16) & 255) + ((rgb >>> 8) & 255) + (rgb & 255) < 384) {
                    dark++;
                }
            }
            if (dark * 5 >= (long) along * 4) {
                result.add(index);
            }
        }
        return result;
    }

    /**
     * Returns the same-size raster for synchronous encoding; it must not escape this call.
     * @return original borrowed input or owned whitened RGB copy
     */
    BufferedImage image() {
        return image;
    }

    /** Flushes only an owned copy, never the borrowed source. */
    @Override
    public void close() {
        if (image != source) {
            image.flush();
        }
    }
}
