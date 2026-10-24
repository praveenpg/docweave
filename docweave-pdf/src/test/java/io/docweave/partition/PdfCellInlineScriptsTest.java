package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.core.ElementType;
import org.apache.pdfbox.cos.COSDictionary;
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

/** Owned font placement controls for explicit script interpretation inside existing native cells. */
class PdfCellInlineScriptsTest {
    /** Creates isolated owned-source controls. */
    PdfCellInlineScriptsTest() {
    }

    /**
     * Raised/lowered one- and two-glyph runs preserve source characters and the independent outside text.
     * @throws Exception if owned source writing or extraction fails
     */
    @Test
    void providerAndTaggedCellsKeepScriptRunsInSourceWordOrder() throws Exception {
        for (final String script : List.of("2", "12")) {
            for (final boolean lowered : List.of(false, true)) {
                final var bytes = source(script, lowered, 0);
                for (final boolean tagged : List.of(false, true)) {
                    final var before = extract(bytes, tagged, PdfOptions.defaults().withTableCellCoordinates(true),
                            PdfNativeLayoutOptions.CellTextJoin.SPACE_SEPARATED);
                    final var after = extract(bytes, tagged, PdfOptions.defaults().withTableCellCoordinates(true),
                            PdfNativeLayoutOptions.CellTextJoin.INLINE_SCRIPT_RUNS);
                    final var original = before.elements()
                                                 .stream()
                                                 .filter(e -> e.type() == ElementType.Table)
                                                 .findFirst()
                                                 .orElseThrow();
                    final var table = after.elements()
                                              .stream()
                                              .filter(e -> e.type() == ElementType.Table)
                                              .findFirst()
                                              .orElseThrow();
                    assertNotEquals("alpha" + script + "beta gamma", original.text());
                    assertEquals("alpha" + script + "beta gamma", table.text());
                    assertEquals(script.length(), table.metadata().get("pdf_table_inline_script_glyphs"));
                    assertEquals("native_inline_script_runs_v1", table.metadata().get("pdf_table_cell_text_policy"));
                    assertFalse(table.metadata().containsKey("pdf_table_joined_iso_dates"));
                    assertEquals(original.metadata().get("coordinates"), table.metadata().get("coordinates"));
                    assertEquals(original.metadata().get("pdf_table_cells"), table.metadata().get("pdf_table_cells"));
                    assertEquals(before.elements().stream().filter(e -> e.type() != ElementType.Table).toList(),
                            after.elements().stream().filter(e -> e.type() != ElementType.Table).toList());
                    assertEquals(1, after.elements().stream().filter(e -> e.text().equals("sidebar")).count());
                }
            }
        }
    }

    /**
     * Original text limits apply before removing generated separators, and rotated sources stay unchanged.
     * @throws Exception if source writing or extraction fails
     */
    @Test
    void originalCellBudgetAndRotatedSourceBehaviorRemainIntact() throws Exception {
        final var bytes = source("12", false, 0);
        for (final boolean tagged : List.of(false, true)) {
            final var original =
                    extract(bytes, tagged, PdfOptions.defaults(), PdfNativeLayoutOptions.CellTextJoin.SPACE_SEPARATED)
                            .elements()
                            .stream()
                            .filter(e -> e.type() == ElementType.Table)
                            .findFirst()
                            .orElseThrow();
            final var limits = TableLimits.defaults();
            final var defaults = PdfOptions.defaults();
            final var options =
                    new PdfOptions(defaults.maxPages(), defaults.maxGlyphsPerPage(), defaults.maxOperatorsPerPage(),
                            defaults.maxImagePixels(), defaults.maxImageBytes(), defaults.includeImageBytes(),
                            new TableLimits(limits.maxRows(), limits.maxColumns(), limits.maxCells(),
                                    original.text().codePointCount(0, original.text().length()) - 1),
                            defaults.tableDetection(), defaults.textLayout(), defaults.includeTableCellCoordinates(),
                            defaults.tableContinuation(), defaults.unicodeNormalization(), defaults.textRendering());
            assertEquals(PartitionException.Code.TABLE_LIMIT,
                    assertThrows(PartitionException.class,
                            ()
                                    -> extract(bytes, tagged, options,
                                            PdfNativeLayoutOptions.CellTextJoin.INLINE_SCRIPT_RUNS))
                            .code());
        }
        for (final int rotation : new int[] {90, 180, 270}) {
            final var rotated = source("12", false, rotation);
            assertEquals(
                    extract(rotated, true, PdfOptions.defaults(), PdfNativeLayoutOptions.CellTextJoin.SPACE_SEPARATED)
                            .elements(),
                    extract(rotated, true, PdfOptions.defaults(),
                            PdfNativeLayoutOptions.CellTextJoin.INLINE_SCRIPT_RUNS)
                            .elements());
        }
    }

    /**
     * Exercises one fixed source cell through either public geometry strategy.
     * @param bytes unchanged complete owned PDF
     * @param tagged whether source table ownership supplies the cell
     * @param options native text/output limits
     * @param join explicit interpretation policy
     * @return complete extraction result
     * @throws Exception if extraction fails
     */
    private static PartitionResult extract(final byte[] bytes, final boolean tagged, final PdfOptions options,
            final PdfNativeLayoutOptions.CellTextJoin join) throws Exception {
        final var box = new PdfTableLayoutProvider.Box(20, 70, 200, 135);
        final PdfPartitioner service =
                tagged ? PdfPartitioner.createWithTaggedTables(options, join)
                       : PdfPartitioner.createWithNativeLayout(options,
                                 new PdfNativeLayoutOptions(72, 200_000, 16).withCellTextJoin(join),
                                 image
                                 -> List.of(new PdfTableLayoutProvider.Table(1, 1, box,
                                         List.of(new PdfTableLayoutProvider.Anchor(0, 0, 1, 1, box)), "owned-cell")));
        return DocWeave.create(PartitionerRegistry.of(List.of(service)))
                .partition(DocumentSource.bytes(bytes, "cell-scripts.pdf"));
    }

    /**
     * Authors independent source structure and font positions without alternate text or model inference.
     * @param script original one- or two-character script run
     * @param lowered whether the run is below rather than above the body baseline
     * @param rotation declared page quarter turn
     * @return complete owned PDF bytes
     * @throws Exception if source encoding fails
     */
    private static byte[] source(final String script, final boolean lowered, final int rotation) throws Exception {
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
            cell.getCOSObject().setInt(COSName.K, 1);
            final var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (final var content = new PDPageContentStream(document, page)) {
                final var properties = new COSDictionary();
                properties.setInt(COSName.MCID, 1);
                content.beginMarkedContent(COSName.getPDFName("Span"), PDPropertyList.create(properties));
                draw(content, font, "alpha", 30, 300, 12);
                final float scriptX = 30 + font.getStringWidth("alpha") * .012f;
                draw(content, font, script, scriptX, lowered ? 296 : 304, 7);
                draw(content, font, "beta", scriptX + font.getStringWidth(script) * .007f, 300, 12);
                draw(content, font, "gamma", 30, 284, 12);
                content.endMarkedContent();
                draw(content, font, "sidebar", 270, 300, 12);
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    /**
     * Writes one original run at independently specified font metrics and coordinates.
     * @param content open source stream
     * @param font source font
     * @param value original characters
     * @param x original left edge
     * @param y original baseline
     * @param size original font size
     * @throws Exception if source writing fails
     */
    private static void draw(final PDPageContentStream content, final PDType1Font font, final String value,
            final float x, final float y, final int size) throws Exception {
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(x, y);
        content.showText(value);
        content.endText();
    }
}
