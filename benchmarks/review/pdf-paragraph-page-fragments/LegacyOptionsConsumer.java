import dev.docweave.partition.PdfTaggedParagraphOptions;

/** Compiled against beta.6 before execution with the new record shape; no new API is linked at compilation. */
public final class LegacyOptionsConsumer {
    /** Prevents utility instantiation. */
    private LegacyOptionsConsumer() {
    }

    /**
     * Verifies original constructor/accessors and immutable copying retain default single-page behavior.
     * @param args unused
     * @throws Exception if binary linkage or reflective new-policy inspection fails
     */
    public static void main(final String[] args) throws Exception {
        final var options = new PdfTaggedParagraphOptions(7, 100, PdfTaggedParagraphOptions.WordWrap.PRESERVE)
                                    .withWordWrap(PdfTaggedParagraphOptions.WordWrap.SOURCE_SOFT_HYPHENS);
        if (options.maxParagraphsPerPage() != 7 || options.maxParagraphCharacters() != 100
                || options.wordWrap() != PdfTaggedParagraphOptions.WordWrap.SOURCE_SOFT_HYPHENS
                || !options.getClass().getMethod("pageBoundary").invoke(options).toString().equals("SINGLE_PAGE")) {
            throw new AssertionError("Original constructor/copy contract changed");
        }
        System.out.println("Previously compiled three-argument options consumer passed on " + Runtime.version());
    }
}
