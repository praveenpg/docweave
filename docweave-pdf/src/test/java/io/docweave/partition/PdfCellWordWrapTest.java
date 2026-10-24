package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.core.ElementType;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Independent physical/discretionary/date source controls for the composed native cell text strategy. */
class PdfCellWordWrapTest {
    /** Creates owned source-cell controls. */
    PdfCellWordWrapTest() {
    }

    /**
     * Both public geometry routes preserve physical hyphens, support dates and leave all source geometry intact.
     * @throws Exception if owned source writing or extraction fails
     */
    @Test
    void physicalCompoundsAndDatesWorkAcrossProviderAndTaggedCells() throws Exception {
        for (final boolean tagged : List.of(false, true)) {
            for (final boolean coordinates : List.of(false, true)) {
                final var options = PdfOptions.defaults().withTableCellCoordinates(coordinates);
                for (final boolean date : List.of(false, true)) {
                    final var bytes =
                            source(date ? "2026-" : "Evidence for one-", date ? "10-20" : "way analysis.", false, 0);
                    final var baseline =
                            extract(bytes, tagged, options, PdfNativeLayoutOptions.CellTextJoin.SPACE_SEPARATED);
                    final var candidate = extract(bytes, tagged, options,
                            PdfNativeLayoutOptions.CellTextJoin.SOURCE_WORD_WRAPS_AND_ISO_DATES);
                    final var before = table(baseline);
                    final var after = table(candidate);
                    assertEquals(date ? "2026-10-20" : "Evidence for one-way analysis.", after.text());
                    assertEquals(
                            "source_word_wraps_and_iso_dates_v1", after.metadata().get("pdf_table_cell_text_policy"));
                    assertEquals(date ? 0 : 1, after.metadata().getOrDefault("pdf_table_retained_hyphen_wraps", 0));
                    assertEquals(date ? 1 : 0, after.metadata().getOrDefault("pdf_table_joined_iso_dates", 0));
                    assertFalse(after.metadata().containsKey("pdf_table_joined_soft_hyphens"));
                    assertEquals(before.metadata().get("coordinates"), after.metadata().get("coordinates"));
                    assertEquals(before.metadata().get("pdf_table_cells"), after.metadata().get("pdf_table_cells"));
                    assertTrue(((String) after.metadata().get("text_as_html")).contains(after.text()));
                    assertEquals(baseline.elements().stream().filter(e -> e.type() != ElementType.Table).toList(),
                            candidate.elements().stream().filter(e -> e.type() != ElementType.Table).toList());
                    assertFalse(before.metadata().containsKey("pdf_table_retained_hyphen_wraps"));
                }
            }
        }
    }

    /**
     * A valid tagged U+00AD hint removes its discretionary glyph before physical compound interpretation.
     * Provider-only cells retain the physical hyphen because they lack authoritative source-hint mapping.
     * @throws Exception if owned source writing or extraction fails
     */
    @Test
    void taggedDiscretionaryHintsTakePrecedenceWithoutDoubleCounting() throws Exception {
        final var bytes = source("Evidence for one-", "way analysis.", true, 0);
        final var tagged = table(extract(bytes, true, PdfOptions.defaults(),
                PdfNativeLayoutOptions.CellTextJoin.SOURCE_WORD_WRAPS_AND_ISO_DATES));
        assertEquals("Evidence for oneway analysis.", tagged.text());
        assertEquals(1, tagged.metadata().get("pdf_table_joined_soft_hyphens"));
        assertFalse(tagged.metadata().containsKey("pdf_table_retained_hyphen_wraps"));
        final var provider = table(extract(bytes, false, PdfOptions.defaults(),
                PdfNativeLayoutOptions.CellTextJoin.SOURCE_WORD_WRAPS_AND_ISO_DATES));
        assertEquals("Evidence for one-way analysis.", provider.text());
        assertEquals(1, provider.metadata().get("pdf_table_retained_hyphen_wraps"));
        assertFalse(provider.metadata().containsKey("pdf_table_joined_soft_hyphens"));
    }

    /**
     * Source edge spaces, numeric/punctuation boundaries and rotated sources keep historical output;
     * original cell text limits apply before any generated separator can be omitted.
     * @throws Exception if owned source writing or extraction fails
     */
    @Test
    void unsupportedBoundariesAndOriginalCellLimitsRemainExplicit() throws Exception {
        for (final boolean tagged : List.of(false, true)) {
            for (final var lines : List.of(List.of("Evidence for one- ", "way analysis."),
                         List.of("Evidence for one-", " way analysis."),
                         List.of("Evidence for 2025-", "2026 analysis."),
                         List.of("Evidence for one-", "(way) analysis."))) {
                final var bytes = source(lines.getFirst(), lines.getLast(), false, 0);
                assertEquals(extract(bytes, tagged, PdfOptions.defaults(),
                                     PdfNativeLayoutOptions.CellTextJoin.SPACE_SEPARATED)
                                     .elements(),
                        extract(bytes, tagged, PdfOptions.defaults(),
                                PdfNativeLayoutOptions.CellTextJoin.SOURCE_WORD_WRAPS_AND_ISO_DATES)
                                .elements());
            }
            final var bytes = source("Evidence for one-", "way analysis.", false, 0);
            final var before = table(
                    extract(bytes, tagged, PdfOptions.defaults(), PdfNativeLayoutOptions.CellTextJoin.SPACE_SEPARATED));
            final var d = PdfOptions.defaults();
            final var limits = TableLimits.defaults();
            final var bounded = new PdfOptions(d.maxPages(), d.maxGlyphsPerPage(), d.maxOperatorsPerPage(),
                    d.maxImagePixels(), d.maxImageBytes(), false,
                    new TableLimits(limits.maxRows(), limits.maxColumns(), limits.maxCells(),
                            before.text().codePointCount(0, before.text().length()) - 1));
            assertEquals(PartitionException.Code.TABLE_LIMIT,
                    assertThrows(PartitionException.class,
                            ()
                                    -> extract(bytes, tagged, bounded,
                                            PdfNativeLayoutOptions.CellTextJoin.SOURCE_WORD_WRAPS_AND_ISO_DATES))
                            .code());
        }
        for (final int rotation : new int[] {90, 180, 270}) {
            final var bytes = source("Evidence for one-", "way analysis.", false, rotation);
            assertEquals(
                    extract(bytes, true, PdfOptions.defaults(), PdfNativeLayoutOptions.CellTextJoin.SPACE_SEPARATED)
                            .elements(),
                    extract(bytes, true, PdfOptions.defaults(),
                            PdfNativeLayoutOptions.CellTextJoin.SOURCE_WORD_WRAPS_AND_ISO_DATES)
                            .elements());
        }
    }

    /**
     * Selects one extracted table without treating ordinary source text as cell text.
     * @param result complete public output
     * @return unique expected table
     */
    private static io.docweave.core.Element table(final PartitionResult result) {
        assertEquals(1, result.elements().stream().filter(e -> e.type() == ElementType.Table).count());
        return result.elements().stream().filter(e -> e.type() == ElementType.Table).findFirst().orElseThrow();
    }

    /**
     * Uses one fixed cell through independent public native-provider or source-tagged factories.
     * @param bytes complete owned PDF
     * @param tagged whether source structure owns the cell
     * @param options original source and cell budgets
     * @param join explicit native cell text strategy
     * @return complete public result, including ordinary sidebar text
     * @throws Exception if the public source cannot be read
     */
    private static PartitionResult extract(final byte[] bytes, final boolean tagged, final PdfOptions options,
            final PdfNativeLayoutOptions.CellTextJoin join) throws Exception {
        final var box = new PdfTableLayoutProvider.Box(20, 80, 240, 150);
        final PdfPartitioner service =
                tagged ? PdfPartitioner.createWithTaggedTables(options, join)
                       : PdfPartitioner.createWithNativeLayout(options,
                                 new PdfNativeLayoutOptions(72, 200_000, 16).withCellTextJoin(join),
                                 image
                                 -> List.of(new PdfTableLayoutProvider.Table(1, 1, box,
                                         List.of(new PdfTableLayoutProvider.Anchor(0, 0, 1, 1, box)),
                                         "owned-word-cell")));
        return DocWeave.create(PartitionerRegistry.of(List.of(service)))
                .partition(DocumentSource.bytes(bytes, "cell-words.pdf"));
    }

    /**
     * Authors independently positioned source references, physical hyphen and optional source discretionary hint.
     * @param first first physical line ending with a hyphen and optional literal source space
     * @param second second physical line
     * @param soft whether the hyphen's source Span declares U+00AD
     * @param rotation original page quarter turn
     * @return complete owned PDF bytes
     * @throws Exception if source encoding fails
     */
    private static byte[] source(final String first, final String second, final boolean soft, final int rotation)
            throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(400, 400));
            page.setRotation(rotation);
            document.addPage(page);
            final var root = new PDStructureTreeRoot();
            document.getDocumentCatalog().setStructureTreeRoot(root);
            final var table = new PDStructureElement("Table", root);
            table.setPage(page);
            root.appendKid(table);
            final var row = new PDStructureElement("TR", table);
            table.appendKid(row);
            final var cell = new PDStructureElement("TD", row);
            row.appendKid(cell);
            final var refs = new COSArray();
            refs.add(COSInteger.get(1));
            if (soft) {
                final var hint = new PDStructureElement("Span", cell);
                hint.setActualText("\u00ad");
                hint.getCOSObject().setInt(COSName.K, 2);
                refs.add(hint.getCOSObject());
            } else {
                refs.add(COSInteger.get(2));
            }
            refs.add(COSInteger.get(3));
            cell.getCOSObject().setItem(COSName.K, refs);
            final var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            final int hyphen = first.lastIndexOf('-');
            try (final var content = new PDPageContentStream(document, page)) {
                draw(content, font, 1, first.substring(0, hyphen), 30, 300);
                draw(content, font, 2, first.substring(hyphen),
                        30 + font.getStringWidth(first.substring(0, hyphen)) * .012f, 300);
                draw(content, font, 3, second, 30, 286);
                draw(content, font, -1, "sidebar", 270, 300);
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    /**
     * Encodes one original native run with an optional independently declared MCID.
     * @param content open source stream
     * @param font original source font
     * @param id marked content identity, negative for ordinary outside text
     * @param text original characters
     * @param x original left edge
     * @param y original baseline
     * @throws Exception if source writing fails
     */
    private static void draw(final PDPageContentStream content, final PDType1Font font, final int id, final String text,
            final float x, final float y) throws Exception {
        if (id >= 0) {
            final var properties = new COSDictionary();
            properties.setInt(COSName.MCID, id);
            content.beginMarkedContent(COSName.getPDFName("Span"), PDPropertyList.create(properties));
        }
        content.beginText();
        content.setFont(font, 12);
        content.newLineAtOffset(x, y);
        content.showText(text);
        content.endText();
        if (id >= 0) {
            content.endMarkedContent();
        }
    }
}
