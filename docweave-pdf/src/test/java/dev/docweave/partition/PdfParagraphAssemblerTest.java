package dev.docweave.partition;

import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/** Owned logical-assembly controls independent of publisher text and reference scores. */
class PdfParagraphAssemblerTest {
    /** Creates isolated assembly controls. */
    PdfParagraphAssemblerTest() {
    }

    /** Retains intervening elements, exact characters, original frames and code-point offsets. */
    @Test
    void completeGroupRetainsProvenanceAndInterveningContent() {
        final var first = part("a", "Hello😀", 1);
        final var last = part("b", "world", 2);
        final var note = new Element("b", ElementType.Image, "image", Map.of("page_number", 1));
        final var input = List.of(first, note, last);
        final var assembler = PdfParagraphAssembler.create();
        final var result = assembler.assemble(input);
        assertEquals(2, result.size());
        assertSame(note, result.getLast());
        final var joined = result.getFirst();
        assertEquals("Hello😀 world", joined.text());
        assertFalse(joined.metadata().containsKey("page_number"));
        assertFalse(joined.metadata().containsKey("coordinates"));
        assertEquals(List.of(1, 2), joined.metadata().get("page_numbers"));
        final var provenance = (List<?>) joined.metadata().get("pdf_source_paragraph_parts");
        final var firstPart = (Map<?, ?>) provenance.getFirst();
        final var lastPart = (Map<?, ?>) provenance.getLast();
        assertEquals(first.metadata(), firstPart.get("metadata"));
        assertEquals(0, firstPart.get("text_start_code_point"));
        assertEquals(6, firstPart.get("text_end_code_point"));
        assertEquals(7, lastPart.get("text_start_code_point"));
        assertEquals(12, lastPart.get("text_end_code_point"));
        assertEquals(result, assembler.assemble(input));
        assertEquals(result, assembler.assemble(result));
        assertEquals(3, input.size());
        assertThrows(UnsupportedOperationException.class, () -> result.clear());
        assertTrue(PdfParagraphAssembler.class.isInterface());
        assertEquals("left- right",
                assembler.assemble(List.of(part("a", "left-", 1), part("b", "right", 2))).getFirst().text());
    }

    /** Incomplete, conflicting, reordered and malformed source groups retain every occurrence unchanged. */
    @Test
    void malformedGroupsFallBackWhole() {
        final var first = part("a", "first", 1);
        final var last = part("b", "last", 2);
        final var assembler = PdfParagraphAssembler.create();
        assertEquals(List.of(first), assembler.assemble(List.of(first)));
        for (final var replacement : List.of(changed(last, "pdf_source_paragraph_fragment", 1),
                     changed(last, "pdf_source_paragraph_fragments", 3), changed(last, "page_number", 3),
                     changed(last, "page_number", 1.5), changed(last, "page_number", "2"),
                     changed(last, "pdf_source_paragraph_ordinal", 1), changed(last, "pdf_source_word_wrap", "unknown"),
                     changed(last, "pdf_native_unicode_policy", "presentation_forms_v1"),
                     changed(last, "pdf_text_layout", "other"),
                     new Element("b", ElementType.Table, last.text(), last.metadata()),
                     new Element("b", ElementType.UncategorizedText, " ", last.metadata()))) {
            final var input = List.of(first, replacement);
            assertEquals(input, assembler.assemble(input));
        }
        final var reverse = List.of(last, first);
        assertEquals(reverse, assembler.assemble(reverse));
        final var duplicate = List.of(first, last, last);
        assertEquals(duplicate, assembler.assemble(duplicate));
        final var differentDocument = List.of(first, changed(last, "pdf_source_paragraph_id", "b".repeat(64) + ":0"));
        assertEquals(differentDocument, assembler.assemble(differentDocument));
        final var wrongOrdinal = List.of(changed(first, "pdf_source_paragraph_id", "a".repeat(64) + ":1"),
                changed(last, "pdf_source_paragraph_id", "a".repeat(64) + ":1"));
        assertEquals(wrongOrdinal, assembler.assemble(wrongOrdinal));
    }

    /** Script interpretation must agree across every fragment and remain in assembled provenance. */
    @Test
    void inlineScriptPoliciesCannotBeMixedAcrossFragments() {
        final var first = changed(part("a", "first", 1), "pdf_source_inline_scripts", "nearby_small_glyphs_v1");
        final var last = changed(part("b", "last", 2), "pdf_source_inline_scripts", "nearby_small_glyphs_v1");
        final var assembler = PdfParagraphAssembler.create();
        final var joined = assembler.assemble(List.of(first, last));
        assertEquals("first last", joined.getFirst().text());
        assertEquals("nearby_small_glyphs_v1", joined.getFirst().metadata().get("pdf_source_inline_scripts"));
        for (final var mismatch :
                List.of(part("b", "last", 2), changed(last, "pdf_source_inline_scripts", "unknown"))) {
            final var input = List.of(first, mismatch);
            assertEquals(input, assembler.assemble(input));
        }
        final var unknown = List.of(changed(first, "pdf_source_inline_scripts", "unknown"),
                changed(last, "pdf_source_inline_scripts", "unknown"));
        assertEquals(unknown, assembler.assemble(unknown));
    }

    /** Input, actual fragment, paragraph and fixed aggregate budgets fail without partial output. */
    @Test
    void budgetsAndNullsAreExplicit() {
        final var input = List.of(part("a", "first", 1), part("b", "last", 2));
        for (final var policy : List.of(new PdfParagraphAssemblyOptions(1, 2, 100),
                     new PdfParagraphAssemblyOptions(2, 1, 100), new PdfParagraphAssemblyOptions(2, 2, 9))) {
            assertThrows(IllegalArgumentException.class, () -> PdfParagraphAssembler.create(policy).assemble(input));
        }
        assertThrows(IllegalArgumentException.class, () -> new PdfParagraphAssemblyOptions(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new PdfParagraphAssemblyOptions(1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new PdfParagraphAssemblyOptions(1, 1, 0));
        assertThrows(NullPointerException.class, () -> PdfParagraphAssembler.create(null));
        assertThrows(NullPointerException.class, () -> PdfParagraphAssembler.create().assemble(null));
        final var nullable = new ArrayList<Element>();
        nullable.add(null);
        assertThrows(NullPointerException.class, () -> PdfParagraphAssembler.create().assemble(nullable));
        final var oversized = new Element("large", ElementType.UncategorizedText, "x".repeat(16_000_001), Map.of());
        assertThrows(IllegalArgumentException.class, () -> PdfParagraphAssembler.create().assemble(List.of(oversized)));
    }

    /**
     * Shared stateless assembly is deterministic on virtual threads and retains caller cancellation.
     * @throws Exception if a concurrent assembly fails
     */
    @Test
    void concurrentReuseAndCancellation() throws Exception {
        final var assembler = PdfParagraphAssembler.create();
        final var input = List.of(part("a", "first", 1), part("b", "last", 2));
        final var expected = assembler.assemble(input);
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var futures = java.util.stream.IntStream.range(0, 12)
                                        .mapToObj(index -> executor.submit(() -> assembler.assemble(input)))
                                        .toList();
            for (final var future : futures) {
                assertEquals(expected, future.get());
            }
        }
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> assembler.assemble(input));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            // Clear only the test's deliberately set flag so subsequent JUnit controls can run.
            Thread.interrupted();
        }
        assertEquals(expected, assembler.assemble(input));
    }

    /**
     * Creates one linked original-frame occurrence.
     * @param id original element identity
     * @param text original characters
     * @param fragment one-based fragment and page
     * @return immutable owned source element
     */
    private static Element part(final String id, final String text, final int fragment) {
        return new Element(id, ElementType.UncategorizedText, text,
                Map.of("pdf_source_paragraph_id", "a".repeat(64) + ":0", "pdf_source_paragraph_ordinal", 0,
                        "pdf_source_paragraph_fragment", fragment, "pdf_source_paragraph_fragments", 2,
                        "pdf_text_layout", "source_tagged_paragraph_v1", "pdf_source_word_wrap", "preserve_v1",
                        "page_number", fragment, "coordinates", Map.of("points", List.of(fragment, 10))));
    }

    /**
     * Changes one owned metadata value without mutating the original.
     * @param element original source occurrence
     * @param key metadata key
     * @param value adversarial replacement
     * @return independently frozen element
     */
    private static Element changed(final Element element, final String key, final Object value) {
        final var metadata = new LinkedHashMap<>(element.metadata());
        metadata.put(key, value);
        return new Element(element.elementId(), element.type(), element.text(), metadata);
    }
}
