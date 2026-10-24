package io.docweave.partition;

/** Private checked-exception bridge for PDFBox's non-throwing glyph callback. */
final class PdfTraversalFailure extends RuntimeException {
    /** Whether cancellation rather than a resource budget caused this signal. */
    private final boolean interrupted;

    /**
     * Captures a content-free failure reason.
     * @param interrupted true for thread interruption
     */
    PdfTraversalFailure(final boolean interrupted) {
        this.interrupted = interrupted;
    }

    /**
     * Converts the callback signal to the library's checked failure contract.
     * @return cancellation or input limit failure
     */
    java.io.IOException checked() {
        return interrupted
                ? new java.io.InterruptedIOException("PDF extraction interrupted")
                : new PartitionException(PartitionException.Code.INPUT_TOO_LARGE, "PDF exceeds maxGlyphsPerPage");
    }
}
