package io.docweave.validation;

import io.docweave.core.Element;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Independent stored-XML corpus diagnostics; no rendering, ownership or category-accuracy claim. */
interface DocxCorpusSource {
    static DocxCorpusSource create() {
        return new DefaultDocxCorpusSource();
    }

    /**
     * Inventories document.xml including revision/fallback branches and stored-part filename counts.
     * Captures the legacy paragraph baseline separately, without evaluating cached field instructions.
     * @param input hash-verified local DOCX file
     * @return immutable inventory and nonblank normalized paragraph strings retained only in memory
     * @throws IOException on invalid/oversized XML or invalid archive contents
     */
    Snapshot inspect(Path input) throws IOException;

    /** Text/type projection supports both public Java elements and raw upstream reference records. */
    record TextElement(String type, String text) {
        static TextElement from(final Element element) {
            return new TextElement(element.type().name(), element.text());
        }
    }

    /** Inventory is deliberately distinct from accepted-text checks; deleted/fallback nodes are counted. */
    record Snapshot(Map<String, Integer> body, int maximumTableDepth, Map<String, Integer> storedParts,
            List<String> storedParagraphs) {
        public Snapshot {
            body = Map.copyOf(body);
            storedParts = Map.copyOf(storedParts);
            storedParagraphs = List.copyOf(storedParagraphs);
        }

        boolean substringSupported() {
            return body.getOrDefault("nested_paragraphs", 0) == 0;
        }

        /** Nested paragraphs need the separate ownership oracle; overlapping probes would be misleading. */
        List<String> paragraphs() throws IOException {
            if (!substringSupported()) {
                throw new IOException("Nested paragraphs require an ownership-aware corpus baseline");
            }
            return storedParagraphs;
        }

        Map<String, Object> inventory() {
            return Map.of("body", body, "maximum_table_depth", maximumTableDepth, "stored_parts", storedParts,
                    "substring_baseline_supported", substringSupported());
        }
    }

    /** Substring presence after whitespace normalization does not prove multiplicity, order or ownership. */
    static Map<String, Object> metrics(final List<TextElement> elements, final List<String> paragraphs) {
        final String text =
                DocxOwnership.normalize(String.join(" ", elements.stream().map(TextElement::text).toList()));
        final var missing = paragraphs.stream().filter(paragraph -> !text.contains(paragraph)).toList();
        final var types = new java.util.TreeMap<String, Integer>();
        elements.forEach(element -> types.merge(element.type(), 1, Integer::sum));
        return Map.of("element_count", elements.size(), "types", Map.copyOf(types), "text_code_points",
                elements.stream().mapToInt(e -> e.text().codePointCount(0, e.text().length())).sum(),
                "body_paragraphs_checked", paragraphs.size(), "body_paragraphs_unmatched", missing.size(),
                "unmatched_paragraph_sha256", missing.stream().map(DocxOwnership::textHash).toList());
    }
}
