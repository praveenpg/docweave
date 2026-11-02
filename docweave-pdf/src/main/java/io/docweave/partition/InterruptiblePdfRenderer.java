package io.docweave.partition;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;

import java.io.IOException;
import java.util.List;

/**
 * A PDFBox renderer whose page drawing applies {@link PdfContentGuard} before every content operator, as the native
 * graphics and text passes do: interruption is checked and graphics-state nesting is bounded. Without it a page of very many operators (no per-page operator ceiling,
 * {@link PdfOptions#UNLIMITED_OPERATORS}) renders to the end before cancellation or a caller's deadline is noticed.
 */
final class InterruptiblePdfRenderer extends PDFRenderer {
    /**
     * Creates a renderer for one document.
     * @param document open source document
     */
    InterruptiblePdfRenderer(final PDDocument document) {
        super(document);
    }

    @Override
    protected PageDrawer createPageDrawer(final PageDrawerParameters parameters) throws IOException {
        return new PageDrawer(parameters) {
            @Override
            protected void processOperator(final Operator operator, final List<COSBase> operands) throws IOException {
                PdfContentGuard.check(operator, getGraphicsStackSize());
                super.processOperator(operator, operands);
            }

            @Override
            protected void operatorException(final Operator operator, final List<COSBase> operands,
                    final IOException failure) throws IOException {
                // PDFBox logs and skips a failing form; a cancellation must stop the render instead.
                if (failure instanceof java.io.InterruptedIOException) {
                    throw failure;
                }
                super.operatorException(operator, operands, failure);
            }
        };
    }
}
