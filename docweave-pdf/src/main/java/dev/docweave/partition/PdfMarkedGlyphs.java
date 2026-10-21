package dev.docweave.partition;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Page-confined identity bridge from source marked content to duplicate-suppressed native glyphs. */
final class PdfMarkedGlyphs {
    /**
     * Original position identity and expected Unicode, before PDFBox duplicate/diacritic processing.
     * @param id original page MCID
     * @param unicode decoded source text or the exact existing content-stream ActualText substitution
     */
    private record Position(int id, String unicode) {}

    /** Bounded outer marked-content identifiers; unmarked/artifact content uses minus one. */
    private final ArrayDeque<Integer> outer = new ArrayDeque<>();
    /** Source identities bounded by the existing visited glyph limit. */
    private final Map<TextPosition, Position> positions = new IdentityHashMap<>();
    /** Retained native positions grouped by their original page-scoped identifier. */
    private final Map<Integer, List<PdfPageText.Glyph>> glyphs = new HashMap<>();
    /** Identifiers declared more than once, referenced through forms, or changed by diacritic merging. */
    private final Set<Integer> invalid = new HashSet<>();
    /** Declared page-stream identifiers; nested unnumbered wrappers do not redeclare them. */
    private final Set<Integer> declared = new HashSet<>();
    /** Innermost inherited identifier. */
    private int current = -1;
    /** Active forms have a distinct marked-content scope and are deliberately not claimed. */
    private int forms;
    /** Invalid nesting invalidates the page's tag ownership without changing ordinary native extraction. */
    private boolean valid = true;
    /** Tracks only whether each PDFBox marked-content callback declared ActualText. */
    private final ArrayDeque<Boolean> replacements = new ArrayDeque<>();
    /** PDFBox's current replacement, including its documented soft-hyphen removal. */
    private String actualText;
    /** The first processed position receives the replacement; later positions receive empty text. */
    private boolean firstActualPosition;

    /** Creates one empty page tracker; never shared across extraction calls. */
    PdfMarkedGlyphs() {
    }

    /**
     * Observes only marked-content operators; the caller still executes the original PDFBox operator.
     * @param operator current operator
     * @param operands source operands
     * @param resources current resources, possibly absent
     * @throws IOException if resolving a source property resource fails
     */
    void operator(final Operator operator, final List<COSBase> operands, final PDResources resources)
            throws IOException {
        if (!valid || forms > 0) {
            return;
        }
        final String name = operator.getName();
        if (name.equals("BMC") || name.equals("BDC")) {
            if (outer.size() >= 128 || operands.isEmpty() || !(operands.getFirst() instanceof COSName)
                    || name.equals("BMC") && operands.size() != 1) {
                valid = false;
                return;
            }
            outer.push(current);
            if (operands.getFirst().equals(COSName.ARTIFACT)) {
                current = -1;
            }
            if (name.equals("BDC")) {
                if (operands.size() != 2) {
                    valid = false;
                    return;
                }
                COSDictionary properties = null;
                if (operands.get(1) instanceof final COSDictionary dictionary) {
                    properties = dictionary;
                } else if (operands.get(1) instanceof final COSName resource && resources != null) {
                    final var property = resources.getProperties(resource);
                    properties = property == null ? null : property.getCOSObject();
                }
                if (properties == null) {
                    valid = false;
                    return;
                }
                final var id = properties.getDictionaryObject(COSName.MCID);
                if (id != null && !operands.getFirst().equals(COSName.ARTIFACT)) {
                    if (!(id instanceof final COSInteger integer) || integer.longValue() < 0
                            || integer.longValue() > Integer.MAX_VALUE) {
                        valid = false;
                        return;
                    }
                    current = integer.intValue();
                    if (!declared.add(current)) {
                        invalid.add(current);
                    }
                }
            }
        } else if (name.equals("EMC")) {
            if (outer.isEmpty()) {
                valid = false;
            } else {
                current = outer.pop();
            }
        }
    }

    /** Marks a separate form scope without allowing its IDs to alias page IDs. */
    void enterForm() {
        if (current >= 0) {
            invalid.add(current);
        }
        forms++;
    }

    /** Restores page scope after the caller's finally block. */
    void leaveForm() {
        forms--;
    }

    /**
     * Mirrors the existing PDFBox replacement state without substituting or changing any source position.
     * PDFBox assigns the current property on every begin, including unnumbered wrappers; it does not restore
     * an enclosing replacement when a nested wrapper ends. Keep that behavior to validate actual retained text.
     * @param properties original content-stream properties, possibly absent
     */
    void beginText(final COSDictionary properties) {
        if (!valid) {
            return;
        }
        if (replacements.size() >= 128) {
            valid = false;
            return;
        }
        final String replacement = properties == null ? null : properties.getString(COSName.ACTUAL_TEXT);
        replacements.push(replacement != null);
        actualText = replacement == null ? null : replacement.replace("\u00ad", "");
        if (actualText != null) {
            firstActualPosition = true;
        }
    }

    /** Ends the current PDFBox replacement scope without guessing restoration of an outer value. */
    void endText() {
        if (!valid) {
            return;
        }
        if (replacements.isEmpty()) {
            valid = false;
        } else if (replacements.pop()) {
            actualText = null;
        }
    }

    /**
     * Records source identity only after the caller has enforced glyph/rendering limits.
     * @param position original source position
     */
    void capture(final TextPosition position) {
        final String expected;
        if (actualText == null) {
            expected = position.getUnicode();
        } else {
            expected = firstActualPosition ? actualText : "";
            firstActualPosition = false;
        }
        if (valid && forms == 0 && current >= 0) {
            positions.put(position, new Position(current, expected));
        }
    }

    /**
     * Associates a retained glyph without duplicating suppressed positions or guessing merged diacritic ownership.
     * @param position position delivered by PDFBox after suppression
     * @param glyph immutable retained source glyph
     */
    void retain(final TextPosition position, final PdfPageText.Glyph glyph) {
        final var original = positions.get(position);
        if (original != null) {
            if (!java.util.Objects.equals(original.unicode(), position.getUnicode())) {
                invalid.add(original.id());
            }
            glyphs.computeIfAbsent(original.id(), unused -> new ArrayList<>()).add(glyph);
        }
    }

    /**
     * Supplies exact retained glyph identities only for a valid, unambiguous source scope.
     * @param id source page MCID
     * @return immutable source positions, empty when absent/ambiguous
     */
    List<PdfPageText.Glyph> get(final int id) {
        return valid && outer.isEmpty() && !invalid.contains(id) ? glyphs.getOrDefault(id, List.of()) : List.of();
    }

    /**
     * Distinguishes a valid empty declaration from a missing, repeated or form-scoped identifier.
     * @param id original page MCID
     * @return valid source declaration, even when no retained glyph exists
     */
    boolean declared(final int id) {
        return valid && outer.isEmpty() && declared.contains(id) && !invalid.contains(id);
    }
}
