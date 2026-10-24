package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.core.Element;
import io.docweave.core.ElementType;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Owned source-evidence and ambiguity controls for explicit discretionary word joining. */
class PdfTaggedSoftHyphenTest {
    /** Creates the owned source-evidence controls without external models or OCR. */
    PdfTaggedSoftHyphenTest() {
    }

    /**
     * A valid single-reference hint repairs one wrap, retaining every source geometry coordinate.
     * @throws Exception if source authoring or public extraction fails
     */
    @Test
    void explicitSourceHintJoinsOnlyUnderTheNewPolicy() throws Exception {
        for (final String variant : List.of("valid", "mcr")) {
            final byte[] bytes = source(variant);
            final var old = table(bytes, PdfNativeLayoutOptions.CellTextJoin.WRAPPED_ISO_DATE, PdfOptions.defaults());
            final var joined =
                    table(bytes, PdfNativeLayoutOptions.CellTextJoin.SOURCE_SOFT_HYPHENS_AND_WRAPPED_ISO_DATE,
                            PdfOptions.defaults());
            assertTrue(old.text().startsWith("capabili- ties"));
            assertTrue(joined.text().startsWith("capabilities Control"));
            assertEquals(1, joined.metadata().get("pdf_table_joined_soft_hyphens"));
            assertEquals(
                    "source_soft_hyphens_and_wrapped_iso_date_v1", joined.metadata().get("pdf_table_cell_text_policy"));
            assertEquals(old.metadata().get("pdf_table_cells"), joined.metadata().get("pdf_table_cells"));
            assertTrue(joined.metadata().get("text_as_html").toString().contains("<td>capabilities</td>"));
        }
    }

    /**
     * Ambiguous alternatives, untagged hyphens and unsupported word boundaries retain old text exactly.
     * @throws Exception if source authoring or public extraction fails
     */
    @Test
    void ambiguousSourceAndNonWordBreaksKeepTheirOriginalText() throws Exception {
        for (final String variant : List.of("absent", "different", "multiple", "ancestor", "cell-ancestor",
                     "table-ancestor", "outer-ancestor", "nested", "untyped", "mcr-actual", "multi-glyph", "same-line",
                     "nonletter", "preceding-number", "long-gap", "rotated")) {
            final byte[] bytes = source(variant);
            final var old = table(bytes, PdfNativeLayoutOptions.CellTextJoin.WRAPPED_ISO_DATE, PdfOptions.defaults());
            final var joined =
                    table(bytes, PdfNativeLayoutOptions.CellTextJoin.SOURCE_SOFT_HYPHENS_AND_WRAPPED_ISO_DATE,
                            PdfOptions.defaults());
            assertEquals(old, joined, variant);
            assertFalse(joined.metadata().containsKey("pdf_table_joined_soft_hyphens"), variant);
        }
    }

    /**
     * Original retained text is charged before a join can reduce the cell character count.
     * @throws Exception if source authoring or public extraction fails
     */
    @Test
    void originalTextBudgetsRemainAuthoritative() throws Exception {
        final var d = PdfOptions.defaults();
        final var bounded = new PdfOptions(d.maxPages(), d.maxGlyphsPerPage(), d.maxOperatorsPerPage(),
                d.maxImagePixels(), d.maxImageBytes(), false, new TableLimits(100, 10, 100, 13));
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class,
                        ()
                                -> table(source("valid"),
                                        PdfNativeLayoutOptions.CellTextJoin.SOURCE_SOFT_HYPHENS_AND_WRAPPED_ISO_DATE,
                                        bounded))
                        .code());
    }

    /**
     * Runs the ordinary public source-table API with original cell coordinates enabled.
     * @param bytes complete original authored PDF
     * @param policy caller-selected cell text policy
     * @param options active page/table limits
     * @return the one source-owned table
     * @throws Exception if source extraction fails
     */
    private static Element table(final byte[] bytes, final PdfNativeLayoutOptions.CellTextJoin policy,
            final PdfOptions options) throws Exception {
        final var partitioner = PdfPartitioner.createWithTaggedTables(options.withTableCellCoordinates(true), policy);
        final var result = DocWeave.create(PartitionerRegistry.of(List.of(partitioner)))
                                   .partition(DocumentSource.bytes(bytes, "owned-soft-hyphen.pdf"));
        assertEquals(1, result.elements().stream().filter(e -> e.type() == ElementType.Table).count());
        return result.elements().stream().filter(e -> e.type() == ElementType.Table).findFirst().orElseThrow();
    }

    /**
     * Authors independently controlled source tags and native positions, with no text cleanup or raster input.
     * @param variant one source ownership, alternate-text or native-boundary variation
     * @return complete original source bytes
     * @throws Exception if source PDF construction fails
     */
    private static byte[] source(final String variant) throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(400, 400));
            page.setRotation(variant.equals("rotated") ? 90 : 0);
            document.addPage(page);
            final var root = new PDStructureTreeRoot();
            document.getDocumentCatalog().setStructureTreeRoot(root);
            final var outer = new PDStructureElement("Document", root);
            root.appendKid(outer);
            final var table = new PDStructureElement("Table", outer);
            table.setPage(page);
            outer.appendKid(table);
            final var row = new PDStructureElement("TR", table);
            table.appendKid(row);
            final var word = new PDStructureElement("TD", row);
            row.appendKid(word);
            final var hint = new PDStructureElement("Span", word);
            if (!variant.equals("absent")) {
                hint.setActualText(variant.equals("different") ? "replacement" : "\u00ad");
            }
            if (variant.equals("untyped")) {
                hint.getCOSObject().removeItem(COSName.S);
            }
            if (variant.equals("mcr") || variant.equals("mcr-actual")) {
                final var reference = new org.apache.pdfbox.cos.COSDictionary();
                reference.setItem(COSName.TYPE, COSName.MCR);
                reference.setInt(COSName.MCID, 2);
                if (variant.equals("mcr-actual")) {
                    hint.getCOSObject().removeItem(COSName.ACTUAL_TEXT);
                    reference.setString(COSName.ACTUAL_TEXT, "\u00ad");
                }
                hint.getCOSObject().setItem(COSName.K, reference);
            } else if (variant.equals("multiple")) {
                final var children = new COSArray();
                children.add(COSInteger.get(1));
                children.add(COSInteger.get(2));
                hint.getCOSObject().setItem(COSName.K, children);
            } else {
                hint.getCOSObject().setInt(COSName.K, 2);
            }
            if (!variant.equals("multiple")) {
                word.getCOSObject().setInt(COSName.K, 1);
            }
            if (variant.equals("ancestor") || variant.equals("nested")) {
                final var wrapper = new PDStructureElement("Span", word);
                wrapper.setActualText(variant.equals("nested") ? "\u00ad" : "competing");
                wrapper.appendKid(hint);
                hint.setParent(wrapper);
                if (variant.equals("nested")) {
                    hint.setActualText("competing");
                }
                word.appendKid(wrapper);
            } else {
                word.appendKid(hint);
            }
            append(word, 3);
            if (variant.equals("cell-ancestor")) {
                word.setActualText("competing");
            } else if (variant.equals("table-ancestor")) {
                table.setActualText("competing");
            } else if (variant.equals("outer-ancestor")) {
                outer.setActualText("competing");
            }
            final var control = new PDStructureElement("TD", row);
            row.appendKid(control);
            append(control, 4);
            final var last = new PDStructureElement("TR", table);
            table.appendKid(last);
            for (final int id : List.of(5, 6)) {
                final var cell = new PDStructureElement("TD", last);
                last.appendKid(cell);
                append(cell, id);
            }
            final var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            final String prefix = variant.equals("preceding-number") ? "123" : "capabili";
            final float dashX = 30 + font.getStringWidth(prefix) * 0.012f;
            try (final var stream = new PDPageContentStream(document, page)) {
                draw(stream, font, 1, prefix, 30, 300);
                draw(stream, font, 2, variant.equals("multi-glyph") ? "x-" : "-", dashX, 300);
                draw(stream, font, 3, variant.equals("nonletter") ? "7ties" : "ties",
                        variant.equals("same-line") ? dashX + font.getStringWidth("-") * 0.012f : 30,
                        variant.equals("same-line")  ? 300
                        : variant.equals("long-gap") ? 220
                                                     : 286);
                draw(stream, font, 4, "Control", 200, 300);
                draw(stream, font, 5, "Keep", 30, 260);
                draw(stream, font, 6, "Suffix", 200, 260);
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    /**
     * Appends one integer page MCID without constructing content-stream substitutes.
     * @param element source structure element
     * @param id original page-scoped reference
     */
    private static void append(final PDStructureElement element, final int id) {
        final var existing = element.getCOSObject().getDictionaryObject(COSName.K);
        if (existing == null) {
            element.getCOSObject().setInt(COSName.K, id);
        } else if (existing instanceof final COSArray array) {
            array.add(COSInteger.get(id));
        } else {
            final var array = new COSArray();
            array.add(existing);
            array.add(COSInteger.get(id));
            element.getCOSObject().setItem(COSName.K, array);
        }
    }

    /**
     * Draws one native run in its original page MCID scope.
     * @param stream borrowed original stream
     * @param font caller-owned source font
     * @param id page MCID
     * @param value original physical text
     * @param x source left edge
     * @param y source baseline
     * @throws IOException if source text encoding fails
     */
    private static void draw(final PDPageContentStream stream, final PDType1Font font, final int id, final String value,
            final float x, final float y) throws IOException {
        stream.beginMarkedContent(COSName.getPDFName("Span"), id);
        stream.beginText();
        stream.setFont(font, 12);
        stream.newLineAtOffset(x, y);
        stream.showText(value);
        stream.endText();
        stream.endMarkedContent();
    }
}
