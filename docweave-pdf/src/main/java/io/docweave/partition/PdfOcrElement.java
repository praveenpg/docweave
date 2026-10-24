package io.docweave.partition;

import io.docweave.core.ElementType;

import java.util.Map;

/**
 * Page-local pending OCR output before conversion to displayed user-space coordinates.
 *
 * @param type      semantic category
 * @param text      recognized text
 * @param box       pixel bounds; text is ignored for rectangle-only values
 * @param signature optional bounded continuation evidence, never emitted as raw metadata
 * @param metadata  interpretation properties
 */
record PdfOcrElement(ElementType type, String text, PdfOcrProvider.Line box, Map<String, Object> metadata,
                     PdfTableSignature signature) {
    /**
     * Preserves ordinary pending output without continuation evidence.
     *
     * @param type     semantic category
     * @param text     extracted text
     * @param box      coordinate bounds
     * @param metadata strategy metadata
     */
    PdfOcrElement(final ElementType type, final String text, final PdfOcrProvider.Line box,
                  final Map<String, Object> metadata) {
        this(type, text, box, metadata, null);
    }
}
