package dev.docweave.validation;

import dev.docweave.core.Element;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Development-only ownership oracle, deliberately independent of the production DOCX parser.
 * Reads accepted body/text-box stories; it is not a renderer or a cell-order oracle.
 * Implementations use only per-call state and can be reused across test invocations.
 */
interface DocxOwnership {
    /** Creates a stateless validator; it adds no production dependency or service provider. */
    static DocxOwnership create() {
        return new DefaultDocxOwnership();
    }

    /**
     * Reads source owners from document.xml, rejecting unsupported story selection.
     * @param path owned or independently hash-verified DOCX input
     * @return immutable owners with disjoint accepted paragraph text
     * @throws IOException for malformed, oversized or unsupported source markup
     */
    List<Owner> read(Path path) throws IOException;

    /**
     * Checks body owner order/type, text-box parents and distinct substring occurrences.
     * Table paragraphs share a root owner; exact equality, cells and table order are not checked.
     * Greedy longest-first allocation may fail on ambiguous overlaps.
     * @param elements immutable public-API extraction results
     * @param owners source expectations, never written as raw text in reports
     * @return immutable diagnostic summary
     */
    Report check(List<Element> elements, List<Owner> owners);

    /** Root ownership granularity; nested tables deliberately share their emitted root table. */
    enum Kind { PARAGRAPH, TABLE }

    /** Only the main body and its anchored text-box descendants are in this oracle's scope. */
    enum Story { BODY, TEXTBOX }

    /** Source identity is an element-only XML path; parent is null for main-body owners. */
    record Owner(String key, Story story, String parent, Kind kind, List<String> paragraphs) {
        public Owner {
            paragraphs = List.copyOf(paragraphs);
        }
    }

    /** Text-free result for one inferred emitted owner. Missing paragraphs are represented by hashes. */
    record OwnerCheck(String sourceOwner, Story story, Kind kind, int paragraphsChecked, boolean kindMatches,
            List<String> unmatchedParagraphSha256) {
        public OwnerCheck {
            unmatchedParagraphSha256 = List.copyOf(unmatchedParagraphSha256);
        }

        boolean passed() {
            return kindMatches && unmatchedParagraphSha256.isEmpty();
        }

        Map<String, Object> json() {
            return Map.of("source_owner", sourceOwner, "story", story == Story.BODY ? "body" : "textbox", "kind",
                    kind == Kind.TABLE ? "table" : "paragraph", "paragraphs_checked", paragraphsChecked, "kind_matches",
                    kindMatches, "checks_passed", passed(), "unmatched_paragraph_sha256", unmatchedParagraphSha256);
        }
    }

    /** Immutable, text-free report compatible with the saved ownership-v1 summary shape. */
    record Report(List<OwnerCheck> owners, int unexpectedElements) {
        public Report {
            owners = List.copyOf(owners);
        }

        int paragraphsChecked() {
            return owners.stream().mapToInt(OwnerCheck::paragraphsChecked).sum();
        }

        List<String> unmatched() {
            return owners.stream().flatMap(owner -> owner.unmatchedParagraphSha256().stream()).toList();
        }

        boolean passed() {
            return unexpectedElements == 0 && owners.stream().allMatch(OwnerCheck::passed);
        }

        Map<String, Object> json() {
            return Map.of("baseline", "ownership_v1", "paragraphs_checked", paragraphsChecked(), "paragraphs_unmatched",
                    unmatched().size(), "unmatched_paragraph_sha256", unmatched(), "owners_checked", owners.size(),
                    "owners_failed", (int) owners.stream().filter(o -> !o.passed()).count(), "unexpected_elements",
                    unexpectedElements, "checks_passed", passed(), "owners",
                    owners.stream().map(OwnerCheck::json).toList());
        }
    }

    /** Match Python's Unicode whitespace set, including U+001C..U+001F, for saved-baseline compatibility. */
    Pattern SPACE = Pattern.compile("[\\s\\x1c-\\x1f]+", Pattern.UNICODE_CHARACTER_CLASS);

    static String normalize(final String text) {
        return SPACE.matcher(text).replaceAll(" ").strip();
    }

    static String sha256(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (final NoSuchAlgorithmException error) {
            throw new IllegalStateException("Required SHA-256 algorithm unavailable", error);
        }
    }

    static String textHash(final String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }
}
