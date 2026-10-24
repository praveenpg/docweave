package io.docweave.partition;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Immutable service configuration with document-confined font dictionaries/counters. */
final class DefaultPdfFontInspector implements PdfFontInspector {
    /** Shared immutable traversal limits. */
    private final PdfOptions limits;
    /** Maximum distinct used dictionary identities. */
    private final int maxFonts;
    /** Encoded input ceiling before parsing. */
    private final int maxInputBytes;
    /** Optional caller-owned credential lookup, without retained password characters. */
    private final PdfPasswordProvider passwords;

    /**
     * Captures already validated policies without loading font resources.
     * @param limits traversal limits
     * @param maxFonts used-font bound
     * @param maxInputBytes encoded byte bound
     */
    DefaultPdfFontInspector(final PdfOptions limits, final int maxFonts, final int maxInputBytes) {
        this(limits, maxFonts, maxInputBytes, null);
    }

    /**
     * Copies the complete inspection policy with optional explicit credentials.
     * @param limits traversal limits
     * @param maxFonts used-font bound
     * @param maxInputBytes encoded byte bound
     * @param passwords caller-owned credential lookup, or null for historical opening
     */
    private DefaultPdfFontInspector(
            final PdfOptions limits, final int maxFonts, final int maxInputBytes, final PdfPasswordProvider passwords) {
        this.passwords = passwords;
        this.limits = limits;
        this.maxFonts = maxFonts;
        this.maxInputBytes = maxInputBytes;
    }

    @Override
    public PdfFontInspector withPasswords(final PdfPasswordProvider passwords) {
        return new DefaultPdfFontInspector(
                limits, maxFonts, maxInputBytes, Objects.requireNonNull(passwords, "passwords"));
    }

    @Override
    public Inspection inspect(final byte[] pdf) throws IOException {
        Objects.requireNonNull(pdf, "pdf");
        ElementCollector.checkInterrupted();
        if (pdf.length > maxInputBytes) {
            throw new PartitionException(
                    PartitionException.Code.INPUT_TOO_LARGE, "PDF font inspection exceeds maxInputBytes");
        }
        try (final var document = PdfDocumentLoader.load(pdf, null, passwords)) {
            ElementCollector.checkInterrupted();
            if (!document.getCurrentAccessPermission().canExtractContent()) {
                throw new PartitionException(
                        PartitionException.Code.UNSUPPORTED_OPTION, "PDF permissions prohibit font inspection");
            }
            if (document.getNumberOfPages() > limits.maxPages()) {
                throw new PartitionException(
                        PartitionException.Code.INPUT_TOO_LARGE, "PDF font inspection exceeds maxPages");
            }
            final var scan = new Scan(limits, maxFonts);
            scan.writeText(document, Writer.nullWriter());
            ElementCollector.checkInterrupted();
            return new Inspection(document.getNumberOfPages(), scan.inventory());
        } catch (final PartitionException | InterruptedIOException failure) {
            throw failure;
        } catch (final InvalidPasswordException failure) {
            throw new PartitionException(PartitionException.Code.UNSUPPORTED_OPTION,
                    "PDF font inspection password is missing or incorrect", failure);
        } catch (final IOException | IllegalArgumentException failure) {
            throw new PartitionException(PartitionException.Code.MALFORMED_DOCUMENT,
                    "PDF font inspection could not parse document", failure);
        }
    }

    /** Glyph-stream traversal without retaining PDFBox TextPositions, glyph geometry or extracted strings. */
    private static final class Scan extends PDFTextStripper {
        /** Per-page resource ceilings. */
        private final PdfOptions limits;
        /** Document font dictionary ceiling. */
        private final int maxFonts;
        /** Identity lookup avoids conflating equal names or dictionaries with equal contents. */
        private final IdentityHashMap<COSDictionary, UsedFont> lookup = new IdentityHashMap<>();
        /** Deterministic first-use order, independent of identity hash ordering. */
        private final List<UsedFont> fonts = new ArrayList<>();
        /** Current physical one-based page index. */
        private int page;
        /** Glyph visits on the current page. */
        private long glyphs;
        /** Operator visits on the current page, including nested form operators. */
        private long operators;

        /**
         * Initializes one document-confined scanner.
         * @param limits page/operator/glyph policy
         * @param maxFonts dictionary ceiling
         */
        Scan(final PdfOptions limits, final int maxFonts) {
            this.limits = limits;
            this.maxFonts = maxFonts;
        }

        /**
         * Resets only per-page counters; font usage spans the whole document.
         * @param source source physical page
         * @throws IOException if cancellation or PDFBox traversal initialization fails
         */
        @Override
        protected void startPage(final PDPage source) throws IOException {
            ElementCollector.checkInterrupted();
            page = getCurrentPageNo();
            glyphs = 0;
            operators = 0;
            super.startPage(source);
        }

        /**
         * Counts operators before dispatch, including text/image/form operations without rasterizing.
         * @param operator parsed source operator
         * @param operands source operands
         * @throws IOException if traversal, cancellation or operator limits fail
         */
        @Override
        protected void processOperator(final Operator operator, final List<COSBase> operands) throws IOException {
            ElementCollector.checkInterrupted();
            if (++operators > limits.maxOperatorsPerPage()) {
                throw new PartitionException(
                        PartitionException.Code.INPUT_TOO_LARGE, "PDF font inspection exceeds maxOperatorsPerPage");
            }
            super.processOperator(operator, operands);
        }

        /**
         * Propagates interpretation failures instead of allowing recovery to swallow nested-form limits.
         * @param operator source operator
         * @param operands source operands
         * @param failure original typed or parsing failure
         * @throws IOException always, preserving the original failure
         */
        @Override
        protected void operatorException(
                final Operator operator, final List<COSBase> operands, final IOException failure) throws IOException {
            throw failure;
        }

        /**
         * Observes actual code mappings before PDFTextStripper's missing-map fallback/position suppression.
         * No super call is needed: this diagnostic pass never generates positioned text.
         * @param matrix source text matrix, deliberately not interpreted as layout
         * @param font actual encountered PDF font
         * @param code source character code
         * @param displacement glyph displacement, unused by inventory
         * @throws IOException if cancellation, font or glyph ceilings fail
         */
        @Override
        protected void showGlyph(final Matrix matrix, final PDFont font, final int code, final Vector displacement)
                throws IOException {
            ElementCollector.checkInterrupted();
            if (++glyphs > limits.maxGlyphsPerPage()) {
                throw new PartitionException(
                        PartitionException.Code.INPUT_TOO_LARGE, "PDF font inspection exceeds maxGlyphsPerPage");
            }
            var used = lookup.get(font.getCOSObject());
            if (used == null) {
                if (fonts.size() >= maxFonts) {
                    throw new PartitionException(
                            PartitionException.Code.INPUT_TOO_LARGE, "PDF font inspection exceeds maxFonts");
                }
                used = new UsedFont(fonts.size() + 1, font);
                lookup.put(font.getCOSObject(), used);
                fonts.add(used);
            }
            used.glyphs++;
            used.pages.add(page);
            final String mapped = font.toUnicode(code);
            if (mapped == null || mapped.isEmpty()) {
                used.unmapped++;
            } else if (mapped.indexOf('\ufffd') >= 0) {
                used.replacement++;
            }
        }

        /**
         * Copies scalar diagnostics before the source document/font dictionaries are released.
         * @return immutable inventory with no retained PDFBox objects
         */
        List<FontInfo> inventory() {
            return fonts.stream().map(UsedFont::snapshot).toList();
        }
    }

    /** One mutable used-font counter, reachable only during one inspection. */
    private static final class UsedFont {
        /** Stable document-local identifier. */
        private final int id;
        /** Document-owned font, never retained in public output. */
        private final PDFont font;
        /** Unique physical pages in encounter order. */
        private final LinkedHashSet<Integer> pages = new LinkedHashSet<>();
        /** Encountered code count. */
        private long glyphs;
        /** Null/empty Unicode-map results. */
        private long unmapped;
        /** Nonempty map results containing U+FFFD. */
        private long replacement;

        /**
         * Captures identity only while the owning document remains open.
         * @param id first-use ordinal
         * @param font source font dictionary wrapper
         */
        UsedFont(final int id, final PDFont font) {
            this.id = id;
            this.font = font;
        }

        /**
         * Converts observed properties to immutable values without source text or library objects.
         * @return source properties and counters
         */
        FontInfo snapshot() {
            return new FontInfo(id, Objects.toString(font.getName(), ""), Objects.toString(font.getSubType(), ""),
                    font.isEmbedded(), font.isDamaged(), font.isVertical(), font.isStandard14(),
                    font.getCOSObject().containsKey(COSName.TO_UNICODE), glyphs, unmapped, replacement,
                    List.copyOf(pages));
        }
    }
}
