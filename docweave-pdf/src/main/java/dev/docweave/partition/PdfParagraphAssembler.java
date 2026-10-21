package dev.docweave.partition;

import dev.docweave.core.Element;

import java.util.List;

/**
 * Explicit logical projection of complete source-tagged PDF paragraph fragments.
 * Configure the partitioner with PAGE_FRAGMENTS first. Only complete, compatible, ordered sets
 * carrying the same document-bound paragraph identity are assembled. Unsupported, ambiguous or
 * incomplete sets pass through unchanged; source declarations do not prove semantic correctness.
 *
 * <p>Assembly emits a paragraph at its first fragment's position and retains every intervening
 * element in relative order. It neither deletes headers/tables/images nor establishes general
 * logical article order. Text is joined with one space, without trimming, normalization or cross-page
 * dehyphenation. The original page-level element list is never modified.</p>
 *
 * <p>Assembled elements have no single page number or coordinate box. Their
 * {@code pdf_source_paragraph_parts} preserve each original element ID, metadata and half-open
 * Unicode code-point text offsets. Shared document/Unicode fields are retained; other original
 * metadata stays in the parts. IDs are deterministic from source identity, text and assembly rule.
 * Implementations are synchronous, reusable and thread-safe; no I/O, providers or executors run.</p>
 */
public interface PdfParagraphAssembler {
    /**
     * Creates the hidden implementation with conservative default budgets.
     * @return reusable bounded assembler
     */
    static PdfParagraphAssembler create() {
        return create(PdfParagraphAssemblyOptions.defaults());
    }

    /**
     * Creates a reusable assembler without parsing or processing input.
     * @param options nonnull immutable assembly budgets
     * @return hidden thread-safe service
     * @throws NullPointerException if options is null
     */
    static PdfParagraphAssembler create(final PdfParagraphAssemblyOptions options) {
        return new DefaultPdfParagraphAssembler(options);
    }

    /**
     * Assembles only complete compatible source sets and preserves all other original elements.
     * All source text plus inserted spaces must fit16million UTF16 units. Malformed source-link
     * metadata is retained without assembly; resource breaches fail the call without partial output.
     * @param elements immutable elements in original document/page order; multiple documents remain separately bound
     * @return immutable logical projection with original page frames retained only in provenance
     * @throws NullPointerException if the list or an element is null
     * @throws IllegalArgumentException if element, fragment, paragraph or total-text budgets are exceeded
     * @throws java.util.concurrent.CancellationException if interrupted; the interrupt flag remains set
     */
    List<Element> assemble(List<Element> elements);
}
