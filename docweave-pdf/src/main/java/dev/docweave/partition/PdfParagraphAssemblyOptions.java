package dev.docweave.partition;

/**
 * Bounds explicit logical paragraph assembly independently of PDF parsing and page-fragment extraction.
 * Input text plus inserted separators additionally has a fixed16million UTF16-unit ceiling per call.
 * These are validation/allocation budgets, not a heap or total-duration guarantee.
 * @param maxElements maximum input element count, including unrelated retained elements
 * @param maxFragmentsPerParagraph maximum actual elements sharing one source paragraph identity
 * @param maxParagraphCharacters maximum assembled UTF16 units, including inserted spaces
 */
public record PdfParagraphAssemblyOptions(int maxElements, int maxFragmentsPerParagraph, int maxParagraphCharacters) {
    /**
     * Rejects nonpositive caller budgets before processing any input.
     * @throws IllegalArgumentException if a budget is not positive
     */
    public PdfParagraphAssemblyOptions {
        if (maxElements < 1 || maxFragmentsPerParagraph < 1 || maxParagraphCharacters < 1) {
            throw new IllegalArgumentException("Paragraph assembly budgets must be positive");
        }
    }

    /**
     * Returns100,000 input elements,1024 source fragments and one million assembled UTF16 units per paragraph.
     * @return immutable bounded policy
     */
    public static PdfParagraphAssemblyOptions defaults() {
        return new PdfParagraphAssemblyOptions(100_000, 1024, 1_000_000);
    }
}
