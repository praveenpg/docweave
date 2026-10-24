package io.docweave.partition;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.util.Matrix;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actual source-glyph controls for explicit physical-line row splitting. The provider row at displayed y 40-66 holds
 * a two-line date header (baselines 50 and 62) in two of its three cells; the body row below holds one line.
 */
class PdfNativeRowLinesTest {
    /** Creates the owned control suite. */
    PdfNativeRowLinesTest() {
    }

    /**
     * Two aligned physical lines in every glyph-owning cell become two rows; characters and ownership are unchanged.
     * @throws Exception if owned extraction fails
     */
    @Test
    void alignedTwoLineCellsBecomeTwoRows() throws Exception {
        final var source = header(62, 62, null);
        final var provider = extract(source, PdfNativeLayoutOptions.RowLines.PROVIDER_ROWS, 0, false, 16);
        final var split = extract(source, PdfNativeLayoutOptions.RowLines.SPLIT_ALIGNED_TWO_LINE_ROWS, 0, false, 16);
        assertEquals("Jan 25, 2026 Jan 26, 2025 Revenue 1 2", provider.tables().getFirst().text());
        assertEquals("Jan 25, Jan 26, 2026 2025 Revenue 1 2", split.tables().getFirst().text());
        final var metadata = split.tables().getFirst().metadata();
        assertEquals("aligned_two_line_rows_v1", metadata.get("pdf_table_row_line_policy"));
        assertEquals(1, metadata.get("pdf_table_split_rows"));
        assertEquals("<table><tbody><tr><td></td><td>Jan 25,</td><td>Jan 26,</td></tr>"
                             + "<tr><td></td><td>2026</td><td>2025</td></tr>"
                             + "<tr><td>Revenue</td><td>1</td><td>2</td></tr></tbody></table>",
                metadata.get("text_as_html"));
        assertFalse(provider.tables().getFirst().metadata().containsKey("pdf_table_row_line_policy"));
        assertEquals(provider.remaining(), split.remaining());
        assertEquals(provider.tables().getFirst().box(), split.tables().getFirst().box());
        // A producer's whitespace spacer glyph in the otherwise empty label cell is structure, not a line.
        final var spaced = new ArrayList<>(source);
        spaced.add(new Text(" ", 40, 50));
        final var spacedSplit =
                extract(spaced, PdfNativeLayoutOptions.RowLines.SPLIT_ALIGNED_TWO_LINE_ROWS, 0, false, 16);
        assertEquals(1, spacedSplit.tables().getFirst().metadata().get("pdf_table_split_rows"));
    }

    /**
     * Misaligned second lines, a single two-line cell, a three-line cell and a vertical span keep provider rows.
     * @throws Exception if owned extraction fails
     */
    @Test
    void misalignedSingleThreeLineAndSpannedRowsAreKept() throws Exception {
        assertUnchanged(header(62, 65, null), false);
        final var single = new ArrayList<Text>();
        single.add(new Text("Jan 25,", 100, 50));
        single.add(new Text("2026", 100, 62));
        single.add(new Text("Jan 26,", 150, 50));
        single.addAll(body());
        assertUnchanged(single, false);
        assertUnchanged(header(62, 62, new Text("note", 150, 57)), false);
        assertUnchanged(header(62, 62, null), true);
    }

    /**
     * Two aligned but upside-down physical lines are outside the documented upright-row policy.
     * @throws Exception if source-frame construction fails
     */
    @Test
    void rotatedPhysicalLinesKeepProviderRows() throws Exception {
        final var page = new PDPage(new PDRectangle(260, 200));
        final var glyphs = List.of(new PdfPageText.Glyph("A", 135, 155, 25, 6, 4, 180),
                new PdfPageText.Glyph("B", 135, 143, 25, 6, 4, 180), new PdfPageText.Glyph("C", 85, 155, 25, 6, 4, 180),
                new PdfPageText.Glyph("D", 85, 143, 25, 6, 4, 180));
        final var provider = table(1, 0, false);
        final var result =
                PdfNativeRowLines.split(List.of(provider), glyphs, new PdfPageGeometry(page), 1, 1, new PdfTableWork());
        assertSame(provider, result.getFirst());
    }

    /**
     * A declared header prefix covering the split row grows by one, and the split rows count toward the anchor
     * budget, failing with the typed table limit when it is exhausted.
     * @throws Exception if owned extraction fails
     */
    @Test
    void headerPrefixGrowsAndAnchorsRemainBudgeted() throws Exception {
        final var split = extract(
                header(62, 62, null), PdfNativeLayoutOptions.RowLines.SPLIT_ALIGNED_TWO_LINE_ROWS, 1, false, 16);
        assertEquals(2, split.tables().getFirst().metadata().get("pdf_table_header_rows"));
        final var failure = assertThrows(PartitionException.class,
                ()
                        -> extract(header(62, 62, null), PdfNativeLayoutOptions.RowLines.SPLIT_ALIGNED_TWO_LINE_ROWS, 0,
                                false, 6));
        assertEquals(PartitionException.Code.TABLE_LIMIT, failure.code());
        try (final var document = document(header(62, 62, null))) {
            final var stripper = new PdfPageText(PdfOptions.defaults());
            stripper.getText(document);
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedIOException.class,
                        ()
                                -> PdfNativeRowLines.split(List.of(table(1, 0, false)), stripper.glyphs(),
                                        new PdfPageGeometry(document.getPage(0)), 1, 1, new PdfTableWork()));
            } finally {
                Thread.interrupted();
            }
        }
    }

    /**
     * Defaults keep provider rows; retained constructors and copies preserve every selection.
     */
    @Test
    void defaultsConstructorsAndCopiesPreserveSelections() {
        assertEquals(PdfNativeLayoutOptions.RowLines.PROVIDER_ROWS, PdfNativeLayoutOptions.defaults().rowLines());
        final var six = new PdfNativeLayoutOptions(150, 1_000, 10, PdfNativeLayoutOptions.CellTextJoin.SPACE_SEPARATED,
                PdfNativeLayoutOptions.LeadingRow.PROVIDER_ONLY, PdfNativeLayoutOptions.TrailingRows.PROVIDER_ONLY);
        assertEquals(PdfNativeLayoutOptions.RowLines.PROVIDER_ROWS, six.rowLines());
        final var selected = six.withRowLines(PdfNativeLayoutOptions.RowLines.SPLIT_ALIGNED_TWO_LINE_ROWS);
        assertEquals(PdfNativeLayoutOptions.RowLines.SPLIT_ALIGNED_TWO_LINE_ROWS,
                selected.withLeadingRow(PdfNativeLayoutOptions.LeadingRow.SOURCE_NUMERIC_ROW)
                        .withTrailingRows(PdfNativeLayoutOptions.TrailingRows.SOURCE_CELL_SPACER_ROWS)
                        .withCellTextJoin(PdfNativeLayoutOptions.CellTextJoin.PRESERVE_LINE_BREAKS)
                        .rowLines());
        assertThrows(NullPointerException.class, () -> six.withRowLines(null));
    }

    /**
     * Asserts that splitting changes nothing for a source.
     * @param source placements
     * @param spanned whether the provider's first-column cell spans both rows
     * @throws Exception if extraction fails
     */
    private static void assertUnchanged(final List<Text> source, final boolean spanned) throws Exception {
        final var provider = extract(source, PdfNativeLayoutOptions.RowLines.PROVIDER_ROWS, 0, spanned, 16);
        final var split = extract(source, PdfNativeLayoutOptions.RowLines.SPLIT_ALIGNED_TWO_LINE_ROWS, 0, spanned, 16);
        assertEquals(provider.tables(), split.tables());
        assertEquals(provider.remaining(), split.remaining());
    }

    /**
     * A split in one provider table never moves, duplicates or drops a glyph of a second table on the same page.
     * @throws Exception if owned extraction fails
     */
    @Test
    void competingTablesKeepDisjointOwnership() throws Exception {
        final var source = new ArrayList<>(header(62, 62, null));
        source.addAll(List.of(new Text("Cost", 12, 124), new Text("3", 100, 124), new Text("4", 150, 124),
                new Text("Total", 12, 142), new Text("5", 100, 142), new Text("6", 150, 142)));
        final var provider = extractTwo(source, PdfNativeLayoutOptions.RowLines.PROVIDER_ROWS);
        final var split = extractTwo(source, PdfNativeLayoutOptions.RowLines.SPLIT_ALIGNED_TWO_LINE_ROWS);
        assertEquals(2, provider.tables().size());
        assertEquals(2, split.tables().size());
        assertEquals("Jan 25, Jan 26, 2026 2025 Revenue 1 2", split.tables().getFirst().text());
        assertEquals(1, split.tables().getFirst().metadata().get("pdf_table_split_rows"));
        assertEquals(provider.tables().get(1), split.tables().get(1));
        assertEquals("Cost 3 4 Total 5 6", split.tables().get(1).text());
        assertEquals(provider.remaining(), split.remaining());
        final String owned =
                split.tables().get(0).text().replace(" ", "") + split.tables().get(1).text().replace(" ", "");
        assertEquals(owned.length(), provider.tables().get(0).text().replace(" ", "").length()
                                             + provider.tables().get(1).text().replace(" ", "").length());
    }

    /**
     * Runs the production pipeline with the owned header table and a second, single-line provider table below it.
     * @param source placements
     * @param rowLines row-line policy
     * @return extracted tables and unclaimed glyphs
     * @throws Exception if extraction fails
     */
    private static PdfNativeProviderTables.Result extractTwo(
            final List<Text> source, final PdfNativeLayoutOptions.RowLines rowLines) throws Exception {
        try (final var document = document(source)) {
            final var stripper = new PdfPageText(PdfOptions.defaults());
            stripper.getText(document);
            return PdfNativeProviderTables.extract(document, 0, new PdfPageGeometry(document.getPage(0)),
                    stripper.glyphs(), PdfOptions.defaults(),
                    new PdfNativeLayoutOptions(72, 500_000, 32).withRowLines(rowLines), image -> {
                        final double scale = image.getWidth() / 260.0;
                        final double[] edges = {10, 80, 135, 190};
                        final double[] rows = {112, 130, 148};
                        final var anchors = new ArrayList<PdfTableLayoutProvider.Anchor>();
                        for (int row = 0; row < 2; row++) {
                            for (int column = 0; column < 3; column++) {
                                anchors.add(new PdfTableLayoutProvider.Anchor(row, column, 1, 1,
                                        new PdfTableLayoutProvider.Box(edges[column] * scale, rows[row] * scale,
                                                edges[column + 1] * scale, rows[row + 1] * scale)));
                            }
                        }
                        return List.of(table(scale, 0, false), new PdfTableLayoutProvider.Table(2, 3,
                                                                       new PdfTableLayoutProvider.Box(10 * scale,
                                                                               112 * scale, 190 * scale, 148 * scale),
                                                                       anchors, "owned-row-lines-second", 0));
                    });
        }
    }

    /**
     * A two-line date header in columns two and three above one body row.
     * @param firstYear baseline of the second line in column two
     * @param secondYear baseline of the second line in column three
     * @param extra optional third line, or null
     * @return placements
     */
    private static List<Text> header(final float firstYear, final float secondYear, final Text extra) {
        final var result = new ArrayList<Text>();
        result.add(new Text("Jan 25,", 100, 50));
        result.add(new Text("2026", 100, firstYear));
        result.add(new Text("Jan 26,", 150, 50));
        result.add(new Text("2025", 150, secondYear));
        if (extra != null) {
            result.add(extra);
        }
        result.addAll(body());
        return List.copyOf(result);
    }

    /**
     * The single-line body row.
     * @return placements at baseline 78
     */
    private static List<Text> body() {
        return List.of(new Text("Revenue", 12, 78), new Text("1", 100, 78), new Text("2", 150, 78));
    }

    /**
     * Authors an owned PDF with actual text operators.
     * @param source placements
     * @return open document; caller closes it
     * @throws IOException if writing fails
     */
    private static PDDocument document(final List<Text> source) throws IOException {
        final var document = new PDDocument();
        final var page = new PDPage(new PDRectangle(260, 200));
        document.addPage(page);
        try (final var content = new PDPageContentStream(document, page)) {
            final var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (final var text : source) {
                content.beginText();
                content.setFont(font, 8);
                content.setTextMatrix(Matrix.getTranslateInstance(text.x(), 200 - text.y()));
                content.showText(text.value());
                content.endText();
            }
        } catch (final IOException | RuntimeException failure) {
            document.close();
            throw failure;
        }
        return document;
    }

    /**
     * Runs the production native-provider pipeline with actual glyph extraction and rendering at 72 DPI.
     * @param source placements
     * @param rowLines row-line policy
     * @param headerRows declared provider header prefix
     * @param spanned whether column one spans both provider rows
     * @param anchors aggregate anchor ceiling
     * @return extracted tables and unclaimed glyphs
     * @throws Exception if extraction fails
     */
    private static PdfNativeProviderTables.Result extract(final List<Text> source,
            final PdfNativeLayoutOptions.RowLines rowLines, final int headerRows, final boolean spanned,
            final int anchors) throws Exception {
        try (final var document = document(source)) {
            final var stripper = new PdfPageText(PdfOptions.defaults());
            stripper.getText(document);
            return PdfNativeProviderTables.extract(document, 0, new PdfPageGeometry(document.getPage(0)),
                    stripper.glyphs(), PdfOptions.defaults(),
                    new PdfNativeLayoutOptions(72, 500_000, anchors).withRowLines(rowLines),
                    image -> List.of(table(image.getWidth() / 260.0, headerRows, spanned)));
        }
    }

    /**
     * Builds a two-row, three-column provider table: header band 40-66 and body band 66-84.
     * @param scale rendered pixels per displayed unit
     * @param headerRows declared header prefix
     * @param spanned whether column one spans both rows
     * @return provider topology
     */
    private static PdfTableLayoutProvider.Table table(final double scale, final int headerRows, final boolean spanned) {
        final double[] edges = {10, 80, 135, 190};
        final double[] rows = {40, 66, 84};
        final var anchors = new ArrayList<PdfTableLayoutProvider.Anchor>();
        for (int row = 0; row < 2; row++) {
            for (int column = 0; column < 3; column++) {
                if (spanned && column == 0 && row == 1) {
                    continue;
                }
                final int span = spanned && column == 0 ? 2 : 1;
                anchors.add(new PdfTableLayoutProvider.Anchor(row, column, span, 1,
                        new PdfTableLayoutProvider.Box(edges[column] * scale, rows[row] * scale,
                                edges[column + 1] * scale, rows[row + span] * scale)));
            }
        }
        return new PdfTableLayoutProvider.Table(2, 3,
                new PdfTableLayoutProvider.Box(10 * scale, 40 * scale, 190 * scale, 84 * scale), anchors,
                "owned-row-lines", headerRows);
    }

    /**
     * Placement used only to author owned PDFs.
     * @param value characters
     * @param x displayed left
     * @param y displayed baseline
     */
    private record Text(String value, float x, float y) {}
}
