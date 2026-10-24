package io.docweave.partition;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.List;

/** Shared page-local raw OCR output budget, including cell and remaining-page calls. */
final class PdfOcrBudget {
    /** Immutable page policy. */
    private final PdfOcrOptions options;
    /** Returned lines consumed across calls. */
    private long lines;
    /** Returned/joined UTF-16 units consumed across calls. */
    private long characters;

    /**
     * Creates a new document-page budget.
     * @param options validated policy
     */
    PdfOcrBudget(final PdfOcrOptions options) {
        this.options = options;
    }

    /**
     * Invokes the provider and validates its entire output before ownership filtering.
     * @param provider synchronous caller-owned strategy
     * @param image borrowed raster
     * @return validated provider lines, including blanks
     * @throws IOException if recognition, cancellation, geometry or aggregate output limits fail
     */
    List<PdfOcrProvider.Line> recognize(final PdfOcrProvider provider, final BufferedImage image) throws IOException {
        return recognize(provider, image, PdfOcrProvider.InputKind.PAGE);
    }

    /**
     * Invokes contextual recognition while sharing all raw-output limits with other calls on the page.
     * @param provider synchronous caller-owned strategy
     * @param image borrowed raster
     * @param kind page or padded cell purpose
     * @return validated provider lines, including blanks
     * @throws IOException if recognition, cancellation, geometry or aggregate output limits fail
     */
    List<PdfOcrProvider.Line> recognize(final PdfOcrProvider provider, final BufferedImage image,
            final PdfOcrProvider.InputKind kind) throws IOException {
        ElementCollector.checkInterrupted();
        final List<PdfOcrProvider.Line> result;
        try {
            result = provider.recognize(image, kind);
        } catch (final PartitionException | InterruptedIOException failure) {
            throw failure;
        } catch (final IOException | RuntimeException failure) {
            throw new PartitionException(PartitionException.Code.OCR_FAILURE, "PDF OCR provider failed", failure);
        }
        ElementCollector.checkInterrupted();
        if (result == null) {
            throw new PartitionException(PartitionException.Code.OCR_FAILURE, "PDF OCR provider returned null");
        }
        lines += result.size();
        if (lines > options.maxLines()) {
            throw new PartitionException(PartitionException.Code.OCR_LIMIT, "PDF OCR exceeds page maxLines");
        }
        for (final var line : result) {
            ElementCollector.checkInterrupted();
            if (line == null || line.right() > image.getWidth() || line.bottom() > image.getHeight()) {
                throw new PartitionException(PartitionException.Code.OCR_FAILURE, "PDF OCR line is outside the raster");
            }
            reserveCharacters(line.text().length());
        }
        return result;
    }

    /**
     * Recognizes actual words once and charges only their reconstructed source lines.
     * WordLine validates token containment/text; line validation therefore bounds every token.
     * @param provider explicit detailed capability
     * @param image borrowed page raster
     * @return validated detailed source lines
     * @throws IOException if provider output, shared limits or cancellation fail
     */
    List<PdfWordOcrProvider.WordLine> recognizeWords(final PdfWordOcrProvider provider, final BufferedImage image)
            throws IOException {
        return recognizeWords(provider, image, PdfOcrProvider.InputKind.PAGE);
    }

    /**
     * Validates one detailed provider call while preserving an explicit raster purpose.
     * @param provider caller-owned actual-word strategy
     * @param image borrowed raster in the current recognition frame
     * @param kind nonnull PAGE or TABLE_CELL purpose
     * @return validated stable detailed lines charged to this budget
     * @throws IOException if recognition, cancellation, geometry or raw output limits fail
     */
    List<PdfWordOcrProvider.WordLine> recognizeWords(final PdfWordOcrProvider provider, final BufferedImage image,
            final PdfOcrProvider.InputKind kind) throws IOException {
        java.util.Objects.requireNonNull(kind, "kind");
        ElementCollector.checkInterrupted();
        final List<PdfWordOcrProvider.WordLine> result;
        try {
            result = provider.recognizeWords(image, kind);
        } catch (final PartitionException | InterruptedIOException failure) {
            throw failure;
        } catch (final IOException | RuntimeException failure) {
            throw new PartitionException(PartitionException.Code.OCR_FAILURE, "PDF word OCR provider failed", failure);
        }
        ElementCollector.checkInterrupted();
        if (result == null) {
            throw new PartitionException(PartitionException.Code.OCR_FAILURE, "PDF word OCR provider returned null");
        }
        lines += result.size();
        if (lines > options.maxLines()) {
            throw new PartitionException(PartitionException.Code.OCR_LIMIT, "PDF OCR exceeds page maxLines");
        }
        for (final var detailed : result) {
            ElementCollector.checkInterrupted();
            if (detailed == null || detailed.line().right() > image.getWidth()
                    || detailed.line().bottom() > image.getHeight()) {
                throw new PartitionException(
                        PartitionException.Code.OCR_FAILURE, "PDF word OCR line is outside the raster");
            }
            reserveCharacters(detailed.line().text().length());
        }
        return result;
    }

    /**
     * Reserves returned text or joining spaces without overflow.
     * @param count nonnegative UTF-16 units
     * @throws PartitionException if the shared page text ceiling is exceeded
     */
    void reserveCharacters(final int count) throws PartitionException {
        characters += count;
        if (characters > options.maxCharacters()) {
            throw new PartitionException(PartitionException.Code.OCR_LIMIT, "PDF OCR exceeds page maxCharacters");
        }
    }
}
