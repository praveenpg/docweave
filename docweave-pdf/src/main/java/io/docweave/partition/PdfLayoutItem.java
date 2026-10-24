package io.docweave.partition;

import io.docweave.core.ElementType;

import java.util.Map;

/**
 * Page-confined pending element, before central IDs/source metadata are assigned.
 * @param type extracted category
 * @param text native text or structural content
 * @param box approximate crop-relative bounds
 * @param signature optional bounded continuation evidence, never emitted as raw metadata
 * @param metadata strategy properties; unchanged structural elements retain their original metadata
 */
record PdfLayoutItem(
        ElementType type, String text, Bounds box, Map<String, Object> metadata, PdfTableSignature signature) {
    /**
     * Creates pending ordinary/native output without continuation evidence.
     * @param type extracted category
     * @param text native text or structural content
     * @param box displayed page bounds
     * @param metadata strategy properties
     */
    PdfLayoutItem(final ElementType type, final String text, final Bounds box, final Map<String, Object> metadata) {
        this(type, text, box, metadata, null);
    }

    /**
     * Approximate crop-relative bounds in unscaled PDF user-space units, with top-left origin.
     * @param left left edge
     * @param top top edge
     * @param right right edge
     * @param bottom bottom edge; also the baseline for displayed horizontal text
     */
    record Bounds(float left, float top, float right, float bottom) {}
}
