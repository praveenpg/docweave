package io.docweave.validation;

import org.w3c.dom.Element;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict publisher-paragraph presence/order diagnostics, independent of extraction strategy or source geometry. */
final class ScientificReadingEvaluation {
    /**
     * One complete selected source paragraph, without candidate-dependent trimming or repair.
     * @param section abstract, first body section or complete body selection
     * @param text complete ASCII-whitespace-normalized publisher text
     */
    record Paragraph(String section, String text) {}

    /** Prevents utility instantiation. */
    private ScientificReadingEvaluation() {
    }

    /**
     * Selects the first abstract's paragraphs and up to eight direct paragraphs of the first body section.
     * Nested sections, figures, tables and later subarticles are never substituted for missing selections.
     * @param xml unchanged bounded original publisher XML
     * @return ordered independently declared paragraphs, including any repeated text
     * @throws Exception if parsing or the explicit reference-selection contract fails
     */
    static List<Paragraph> references(final byte[] xml) throws Exception {
        return references(xml, false);
    }

    /**
     * Retains the historical selection or all direct body and recursively nested section paragraphs.
     * Figures, tables, boxed material, back matter and subarticles remain outside this explicit prose selection.
     * Complete-body mode rejects more than1024 paragraphs or32 nested section levels instead of truncating.
     * @param xml original publisher bytes
     * @param completeBody whether all body sections are selected independently of extraction results
     * @return complete selected source population in declared publisher order
     * @throws Exception if source parsing, required content or selection bounds fail
     */
    static List<Paragraph> references(final byte[] xml, final boolean completeBody) throws Exception {
        final var article = ScientificPublisherXml.parse(xml).getDocumentElement();
        require("article".equals(article.getTagName()));
        final var metadata = child(child(article, "front"), "article-meta");
        final var abstractElement = child(metadata, "abstract");
        final var result = new ArrayList<Paragraph>();
        abstractParagraphs(abstractElement, result, 0);
        require(!result.isEmpty() && result.size() <= 64);
        if (completeBody) {
            final int abstractCount = result.size();
            bodyParagraphs(child(article, "body"), result, 0);
            require(result.size() > abstractCount);
            return List.copyOf(result);
        }
        final var section = child(child(article, "body"), "sec");
        int selected = 0;
        for (var node = section.getFirstChild(); node != null && selected < 8; node = node.getNextSibling()) {
            if (node instanceof final Element element && element.getTagName().equals("p")) {
                result.add(paragraph("first_body_section", element));
                selected++;
            }
        }
        require(selected > 0);
        return List.copyOf(result);
    }

    /**
     * Visits only body/section children, preserving their original interleaved paragraph order.
     * @param parent original body or section
     * @param result complete abstract/body references collected so far
     * @param depth source section nesting, bounded before descending
     */
    private static void bodyParagraphs(final Element parent, final List<Paragraph> result, final int depth) {
        require(depth <= 32);
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof final Element element) {
                if (element.getTagName().equals("p")) {
                    require(result.size() < 1024);
                    result.add(paragraph("body", element));
                } else if (element.getTagName().equals("sec")) {
                    bodyParagraphs(element, result, depth + 1);
                }
            }
        }
    }

    /**
     * Freezes ordered source identity without writing any publisher transcription to Git.
     * @param paragraphs complete selected references
     * @return counts and SHA256 identities suitable for a pre-extraction manifest
     */
    static Map<String, Object> identity(final List<Paragraph> paragraphs) {
        final var references = new ArrayList<Map<String, Object>>();
        for (int index = 0; index < paragraphs.size(); index++) {
            final var paragraph = paragraphs.get(index);
            references.add(Map.of("ordinal", index, "section", paragraph.section(), "code_points",
                    paragraph.text().codePointCount(0, paragraph.text().length()), "sha256", hash(paragraph.text())));
        }
        return Map.of("reference_paragraphs", paragraphs.size(), "paragraphs", references, "ordered_reference_sha256",
                hash(String.join("\n", paragraphs.stream().map(Paragraph::text).toList())));
    }

    /**
     * Counts exact unique matches and order pairs against the full selected denominator.
     * Missing or duplicated paragraphs cannot satisfy an order pair, even if another occurrence would fit.
     * This is a strict text/order diagnostic, not a visual paragraph-box or general semantic-order metric.
     * @param paragraphs all selected publisher references
     * @param output complete candidate text in its emitted order
     * @return numeric/hash-only full-reference diagnostics
     */
    static Map<String, Object> score(final List<Paragraph> paragraphs, final String output) {
        final String candidate = normalize(output);
        final var offsets = new int[paragraphs.size()];
        final var ends = new int[paragraphs.size()];
        final var details = new ArrayList<Map<String, Object>>();
        int unique = 0;
        int missing = 0;
        int duplicated = 0;
        for (int index = 0; index < paragraphs.size(); index++) {
            final String text = paragraphs.get(index).text();
            require(!text.isEmpty());
            final int first = candidate.indexOf(text);
            int occurrences = 0;
            for (int position = first; position >= 0; position = candidate.indexOf(text, position + 1)) {
                occurrences++;
            }
            offsets[index] = occurrences == 1 ? first : -1;
            ends[index] = occurrences == 1 ? first + text.length() : -1;
            unique += occurrences == 1 ? 1 : 0;
            missing += occurrences == 0 ? 1 : 0;
            duplicated += occurrences > 1 ? 1 : 0;
            details.add(Map.of("ordinal", index, "section", paragraphs.get(index).section(), "reference_sha256",
                    hash(text), "occurrences", occurrences, "first_offset", first));
        }
        int ordered = 0;
        int reversed = 0;
        for (int first = 0; first < offsets.length; first++) {
            for (int second = first + 1; second < offsets.length; second++) {
                if (offsets[first] >= 0 && offsets[second] >= 0) {
                    ordered += ends[first] <= offsets[second] ? 1 : 0;
                    reversed += ends[second] <= offsets[first] ? 1 : 0;
                }
            }
        }
        final int pairs = paragraphs.size() * (paragraphs.size() - 1) / 2;
        final var result = new LinkedHashMap<String, Object>();
        result.put("reference_paragraphs", paragraphs.size());
        result.put("uniquely_matched_paragraphs", unique);
        result.put("missing_paragraphs", missing);
        result.put("duplicated_paragraphs", duplicated);
        result.put("reference_order_pairs", pairs);
        result.put("preserved_order_pairs", ordered);
        result.put("reversed_order_pairs", reversed);
        result.put("unresolved_order_pairs", pairs - ordered - reversed);
        result.put(
                "all_exact_in_source_order", !paragraphs.isEmpty() && unique == paragraphs.size() && ordered == pairs);
        result.put("candidate_code_points", candidate.codePointCount(0, candidate.length()));
        result.put("candidate_sha256", hash(candidate));
        result.put("paragraphs", details);
        return result;
    }

    /**
     * Collapses ASCII whitespace only; preserves case, punctuation, soft/hard hyphens and all other Unicode.
     * @param value source or candidate text
     * @return normalized text without ASCII edge spaces
     */
    static String normalize(final String value) {
        return value.replaceAll("[ \\t\\n\\r\\f\\x0B]+", " ").replaceAll("^ +| +$", "");
    }

    /**
     * Hashes text without exporting its content into numeric evidence.
     * @param value immutable source text
     * @return lowercase UTF8 SHA256
     */
    private static String hash(final String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (final java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Required SHA256 unavailable", failure);
        }
    }

    /**
     * Finds the first direct source child under the prospectively fixed ordinal selection rule.
     * @param parent original source parent
     * @param name exact source element name
     * @return first matching direct element
     */
    private static Element child(final Element parent, final String name) {
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof final Element element && element.getTagName().equals(name)) {
                return element;
            }
        }
        throw new IllegalArgumentException("Required publisher selection is absent");
    }

    /**
     * Retains all abstract paragraphs in source order, including structured abstract sections.
     * @param parent first original abstract or its section
     * @param result selected paragraphs
     * @param depth bounded abstract section nesting, at most32
     */
    private static void abstractParagraphs(final Element parent, final List<Paragraph> result, final int depth) {
        require(depth <= 32);
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof final Element element) {
                if (element.getTagName().equals("p")) {
                    require(result.size() < 64);
                    result.add(paragraph("abstract", element));
                } else if (element.getTagName().equals("sec")) {
                    abstractParagraphs(element, result, depth + 1);
                }
            }
        }
    }

    /**
     * Reads full inline paragraph content; explicit publisher breaks become spaces.
     * @param section reference group identity
     * @param source original paragraph
     * @return complete nonblank paragraph
     */
    private static Paragraph paragraph(final String section, final Element source) {
        final var copy = (Element) source.cloneNode(true);
        final var breaks = copy.getElementsByTagName("break");
        while (breaks.getLength() > 0) {
            final var lineBreak = breaks.item(0);
            lineBreak.getParentNode().replaceChild(copy.getOwnerDocument().createTextNode(" "), lineBreak);
        }
        final String text = normalize(copy.getTextContent());
        require(!text.isEmpty());
        return new Paragraph(section, text);
    }

    /**
     * Rejects unsupported source/evaluation state instead of dropping a selected reference.
     * @param valid whether the required invariant holds
     */
    private static void require(final boolean valid) {
        if (!valid) {
            throw new IllegalArgumentException("Scientific reading reference contract failed");
        }
    }
}
