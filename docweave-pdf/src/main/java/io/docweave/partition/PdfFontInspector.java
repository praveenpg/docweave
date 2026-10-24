package io.docweave.partition;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Optional used-font and Unicode-mapping diagnostics, independent of element extraction.
 * Inspects actual glyph encounters, including form content, without retaining document text or
 * exposing PDFBox objects. A declared ToUnicode map, an embedded font, or a successful fallback
 * mapping does not establish correct reading order, visual fidelity or semantic Unicode accuracy.
 * Implementations are reusable and thread-safe; each inspection owns its traversal state.
 */
public interface PdfFontInspector {
    /**
     * Copies a built-in inspector with explicit credential lookup and unchanged traversal/input limits.
     * Lookup receives a null filename because inspection accepts byte arrays without source metadata.
     * Credentials use the same single-attempt, clearing and permission contract as PDF partitioning.
     * The original inspector stays unchanged; construction performs no lookup or parsing.
     * Custom inspectors may override this optional method; the default rejects configuration.
     * @param passwords nonnull caller-owned thread-safe credential provider
     * @return hidden reusable configured inspector
     * @throws NullPointerException if passwords is null
     * @throws UnsupportedOperationException if a custom inspector does not support this policy
     */
    default PdfFontInspector withPasswords(final PdfPasswordProvider passwords) {
        Objects.requireNonNull(passwords, "passwords");
        throw new UnsupportedOperationException("Custom PDF font inspector does not support password configuration");
    }

    /**
     * Creates a bounded inspector with default PDF traversal limits,256 used fonts and64MiB input.
     * @return hidden reusable implementation
     */
    static PdfFontInspector create() {
        return create(PdfOptions.defaults(), 256, 64 * 1024 * 1024);
    }

    /**
     * Creates an inspector without parsing input or resolving any fonts at construction time.
     * Only maxPages, maxGlyphsPerPage and maxOperatorsPerPage from the PDF policy apply;
     * extraction/layout/image settings are irrelevant. Limits do not bound PDFBox intermediate
     * allocations, font parsing/decompression or system fallback-font lookup.
     * @param limits immutable PDF traversal policy
     * @param maxFonts maximum distinct used font dictionaries per document
     * @param maxInputBytes maximum encoded input bytes before parsing
     * @return hidden reusable implementation
     * @throws NullPointerException if limits is null
     * @throws IllegalArgumentException if a supplied limit is nonpositive
     */
    static PdfFontInspector create(final PdfOptions limits, final int maxFonts, final int maxInputBytes) {
        Objects.requireNonNull(limits, "limits");
        if (maxFonts < 1 || maxInputBytes < 1) {
            throw new IllegalArgumentException("Invalid PDF font inspection limits");
        }
        return new DefaultPdfFontInspector(limits, maxFonts, maxInputBytes);
    }

    /**
     * Inspects borrowed PDF bytes synchronously without modifying or retaining them after return.
     * The caller must keep the array stable during this call. Encryption/extraction permissions,
     * page/operator/glyph/font/input limits and cooperative interruption are enforced.
     * @param pdf complete encoded PDF, stable during inspection
     * @return immutable encountered-font inventory; unused resource fonts are excluded
     * @throws NullPointerException if pdf is null
     * @throws IOException if input, permissions, traversal, cancellation or limits prevent inspection
     */
    Inspection inspect(byte[] pdf) throws IOException;

    /**
     * Immutable inventory in first-glyph font encounter order, including the physical page count.
     * @param pageCount physical document pages, including blank pages
     * @param fonts distinct encountered font dictionaries, defensively copied
     */
    record Inspection(int pageCount, List<FontInfo> fonts) {
        /**
         * Snapshots the inventory without retaining library objects.
         * @throws IllegalArgumentException if pageCount is negative
         * @throws NullPointerException if the list or an entry is null
         */
        public Inspection {
            if (pageCount < 0) {
                throw new IllegalArgumentException("Invalid PDF page count");
            }
            fonts = List.copyOf(fonts);
        }
    }

    /**
     * Used-font properties and observed code-to-Unicode mapping diagnostics; no source text is included.
     * Same-name distinct dictionaries remain separate, and counters include repeated paint operations.
     * Unmapped codes have null/empty PDFBox mapping; replacement mappings contain U+FFFD. Neither
     * metric detects incorrect but plausible mappings, ligature semantics or invisible text layers.
     * Missing ToUnicode is legal for many simple fonts; its presence does not prove a valid map.
     * @param id one-based font encounter identifier, local to this inspection
     * @param name source font name, or empty if absent
     * @param subtype source PDF subtype, or empty if absent
     * @param embedded whether PDFBox reports embedded font data
     * @param damaged whether PDFBox reports damaged font data
     * @param vertical whether the font uses vertical writing metrics
     * @param standard14 whether PDFBox identifies a standard14 font
     * @param toUnicodeDeclared whether the source dictionary declares a ToUnicode entry
     * @param glyphs encountered character codes, including repeated source paints
     * @param unmappedGlyphs encounters without a nonempty Unicode mapping
     * @param replacementGlyphs encounters whose mapping contains U+FFFD
     * @param pages distinct one-based physical page numbers in encounter order
     */
    record FontInfo(int id, String name, String subtype, boolean embedded, boolean damaged, boolean vertical,
            boolean standard14, boolean toUnicodeDeclared, long glyphs, long unmappedGlyphs, long replacementGlyphs,
            List<Integer> pages) {
        /**
         * Validates counters and snapshots page membership.
         * @throws NullPointerException if a textual/list component or page is null
         * @throws IllegalArgumentException if identifiers, counters or page membership are invalid
         */
        public FontInfo {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(subtype, "subtype");
            pages = List.copyOf(pages);
            if (id < 1 || glyphs < 0 || unmappedGlyphs < 0 || replacementGlyphs < 0 || unmappedGlyphs > glyphs
                    || replacementGlyphs > glyphs - unmappedGlyphs || pages.stream().anyMatch(page -> page < 1)
                    || pages.stream().distinct().count() != pages.size()) {
                throw new IllegalArgumentException("Invalid PDF font diagnostics");
            }
        }
    }
}
