package io.docweave.partition;

import org.apache.pdfbox.contentstream.operator.Operator;

import java.io.IOException;

/**
 * Per-operator checks shared by every PDFBox pass over page content (graphics, text, font inspection, rendering).
 * They bound work and memory independently of the optional per-page operator ceiling
 * ({@link PdfOptions#UNLIMITED_OPERATORS}): the calling thread's interruption is checked before each operator, and a
 * graphics-state save ({@code q}) past {@link #MAX_GRAPHICS_STATE_DEPTH} nested states fails instead of cloning
 * another state.
 */
final class PdfContentGuard {
    /**
     * Most nested graphics states ({@code q} without a matching {@code Q}) in one content stream. PDF processors commonly
     * support far fewer (the PDF reference's implementation limit is 28); a deeper stack is malformed or hostile.
     */
    static final int MAX_GRAPHICS_STATE_DEPTH = 4_096;

    private PdfContentGuard() {
    }

    /**
     * Checks one operator before it is dispatched.
     * @param operator operator about to run
     * @param graphicsStackSize the engine's current graphics-state stack size (one when nothing is saved)
     * @throws IOException if the thread was interrupted, or {@code INPUT_TOO_LARGE} for a too-deep save
     */
    static void check(final Operator operator, final int graphicsStackSize) throws IOException {
        ElementCollector.checkInterrupted();
        if (graphicsStackSize > MAX_GRAPHICS_STATE_DEPTH && "q".equals(operator.getName())) {
            throw new PartitionException(PartitionException.Code.INPUT_TOO_LARGE,
                    "PDF page exceeds " + MAX_GRAPHICS_STATE_DEPTH + " nested graphics states");
        }
    }
}
