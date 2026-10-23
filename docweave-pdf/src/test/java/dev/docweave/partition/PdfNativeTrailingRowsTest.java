package dev.docweave.partition;

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
 * Actual source-glyph ownership and rejection controls for explicitly inferred trailing rows.
 * Each owned page paints a whitespace cell lattice: a space glyph at displayed x=82 and x=137 on every table
 * row, the producer pattern the inference relies on. Expected text is derived from the authored placements.
 */
class PdfNativeTrailingRowsTest {
    /** Creates the owned source-control suite. */
    PdfNativeTrailingRowsTest() {
    }

    /**
     * Rows below a blank lattice row join the table in source order and stop at a footnote without the lattice.
     * @throws Exception if owned PDF extraction fails
     */
    @Test
    void latticeRowsAcrossABlankGapJoinAndAFootnoteStops() throws Exception {
        final var source = new ArrayList<>(body());
        source.addAll(spacers(92));
        source.addAll(row(112, "Supplemental:", "", ""));
        source.addAll(row(132, "Taxes", "5", "7"));
        source.add(new Text("(1) Footnote text", 12, 152, 0));
        final var provider = extract(source, PdfNativeLayoutOptions.TrailingRows.PROVIDER_ONLY, 72, 16);
        final var trailing = extract(source, PdfNativeLayoutOptions.TrailingRows.SOURCE_CELL_SPACER_ROWS, 72, 16);
        assertEquals("Revenue 1 2 Costs 3 4", provider.tables().getFirst().text());
        assertEquals("Revenue 1 2 Costs 3 4 Supplemental: Taxes 5 7", trailing.tables().getFirst().text());
        final var metadata = trailing.tables().getFirst().metadata();
        assertEquals("source_trailing_cell_spacer_rows_v1", metadata.get("pdf_table_trailing_boundary_policy"));
        assertEquals(2, metadata.get("pdf_table_added_trailing_rows"));
        assertFalse(metadata.containsKey("pdf_table_boundary_policy"));
        assertEquals("owned-trailing", metadata.get("pdf_table_layout_provenance"));
        final String html = (String) metadata.get("text_as_html");
        assertTrue(html.endsWith("<tr><td>Supplemental:</td><td></td><td></td></tr>"
                                 + "<tr><td>Taxes</td><td>5</td><td>7</td></tr></tbody></table>"),
                html);
        assertEquals("(1)Footnotetext", visible(trailing.remaining()));
        assertEquals("Supplemental:Taxes57(1)Footnotetext", visible(provider.remaining()));
    }

    /**
     * The rule transfers unchanged to a higher render resolution, where provider pixels scale with the raster.
     * @throws Exception if owned PDF extraction fails
     */
    @Test
    void higherRenderResolutionInfersTheSameRows() throws Exception {
        final var source = new ArrayList<>(body());
        source.addAll(row(92, "Taxes", "5", "7"));
        final var result = extract(source, PdfNativeLayoutOptions.TrailingRows.SOURCE_CELL_SPACER_ROWS, 144, 16);
        assertEquals("Revenue 1 2 Costs 3 4 Taxes 5 7", result.tables().getFirst().text());
        assertEquals(1, result.tables().getFirst().metadata().get("pdf_table_added_trailing_rows"));
        assertEquals("", visible(result.remaining()));
    }

    /**
     * Footnotes, separate notes and coincidentally aligned numbers without the lattice remain ordinary text.
     * @throws Exception if owned PDF extraction fails
     */
    @Test
    void contentWithoutTheLatticeRemainsOrdinaryText() throws Exception {
        final var footnote = new ArrayList<>(body());
        footnote.add(new Text("(1) Footnote text", 12, 92, 0));
        final var note = new ArrayList<>(body());
        note.add(new Text("Separate note:", 12, 92, 0));
        final var aligned = new ArrayList<>(body());
        aligned.add(new Text("Other", 12, 92, 0));
        aligned.add(new Text("9", 100, 92, 0));
        aligned.add(new Text("9", 150, 92, 0));
        final var partial = new ArrayList<>(body());
        partial.add(new Text(" ", 82, 92, 0));
        partial.add(new Text("Other", 12, 92, 0));
        final var blankOnly = new ArrayList<>(body());
        blankOnly.addAll(spacers(92));
        blankOnly.addAll(spacers(112));
        for (final var source : List.of(footnote, note, aligned, partial, blankOnly)) {
            assertUnchanged(source);
        }
    }

    /**
     * A footnote between the table and later lattice lines ends the scan, so the later lines are not absorbed.
     * @throws Exception if owned PDF extraction fails
     */
    @Test
    void anInterruptingLineEndsTheLattice() throws Exception {
        final var source = new ArrayList<>(body());
        source.add(new Text("(1) Footnote text", 12, 92, 0));
        source.addAll(row(112, "Taxes", "5", "7"));
        assertUnchanged(source);
    }

    /**
     * Wider lines, rotated glyphs and glyphs crossing a column boundary retain the provider table.
     * @throws Exception if owned PDF extraction fails
     */
    @Test
    void unsupportedTrailingGeometryRetainsTheProviderTable() throws Exception {
        final var wide = new ArrayList<>(body());
        wide.addAll(row(92, "Taxes", "5", "7"));
        wide.add(new Text("beyond", 200, 92, 0));
        final var rotated = new ArrayList<>(body());
        rotated.addAll(spacers(92));
        rotated.add(new Text("Taxes", 12, 92, 90));
        final var crossing = new ArrayList<>(body());
        crossing.addAll(spacers(92));
        crossing.add(new Text("A label crossing columns", 12, 92, 0));
        for (final var source : List.of(wide, rotated, crossing)) {
            assertUnchanged(source);
        }
    }

    /**
     * A table whose own rows do not all repeat at least two positions has no lattice and infers nothing.
     * @throws Exception if owned PDF extraction fails
     */
    @Test
    void insufficientOrInconsistentTableLatticeInfersNothing() throws Exception {
        final var single = new ArrayList<Text>();
        for (final var text : body()) {
            if (text.x() != 137) {
                single.add(text);
            }
        }
        single.add(new Text(" ", 82, 92, 0));
        single.add(new Text("Taxes", 12, 92, 0));
        final var inconsistent = new ArrayList<Text>();
        for (final var text : body()) {
            if (!(text.y() == 52 && text.value().isBlank())) {
                inconsistent.add(text);
            }
        }
        inconsistent.addAll(row(92, "Taxes", "5", "7"));
        for (final var source : List.of(single, inconsistent)) {
            assertUnchanged(source);
        }
    }

    /**
     * Original anchors keep their indices and bounds, and a competing table below is never overlapped.
     * @throws Exception if owned PDF extraction fails
     */
    @Test
    void topologyAndCompetingOwnershipRemainValidated() throws Exception {
        final var source = new ArrayList<>(body());
        source.addAll(row(92, "Taxes", "5", "7"));
        try (final var document = document(source)) {
            final var geometry = new PdfPageGeometry(document.getPage(0));
            final var stripper = new PdfPageText(PdfOptions.defaults());
            stripper.getText(document);
            final var original = table(1);
            final var expanded =
                    PdfNativeTrailingRows
                            .extend(List.of(original), stripper.glyphs(), geometry, 1, 1, List.of(), new PdfTableWork())
                            .getFirst();
            assertEquals(3, expanded.rows());
            assertEquals(original.anchors(), expanded.anchors().subList(0, original.anchors().size()));
            assertEquals(original.bounds().top(), expanded.bounds().top());
            assertTrue(expanded.bounds().bottom() > original.bounds().bottom());
            final var box = new PdfTableLayoutProvider.Box(10, 84, 190, 100);
            final var other = new PdfTableLayoutProvider.Table(
                    1, 1, box, List.of(new PdfTableLayoutProvider.Anchor(0, 0, 1, 1, box)), "competing");
            assertSame(original, PdfNativeTrailingRows
                                         .extend(List.of(original, other), stripper.glyphs(), geometry, 1, 1, List.of(),
                                                 new PdfTableWork())
                                         .getFirst());
        }
    }

    /**
     * Leading- and trailing-row inference compose with separate metadata and one shared validation.
     * @throws Exception if owned PDF extraction fails
     */
    @Test
    void leadingAndTrailingInferenceCompose() throws Exception {
        final var source = new ArrayList<>(body());
        source.addAll(row(92, "Taxes", "5", "7"));
        source.addAll(row(32, "Opening", "8", "9"));
        try (final var document = document(source)) {
            final var stripper = new PdfPageText(PdfOptions.defaults());
            stripper.getText(document);
            final var policy = new PdfNativeLayoutOptions(72, 500_000, 16)
                                       .withLeadingRow(PdfNativeLayoutOptions.LeadingRow.SOURCE_NUMERIC_ROW)
                                       .withTrailingRows(PdfNativeLayoutOptions.TrailingRows.SOURCE_CELL_SPACER_ROWS);
            final var result = PdfNativeProviderTables.extract(document, 0, new PdfPageGeometry(document.getPage(0)),
                    stripper.glyphs(), PdfOptions.defaults(), policy, image -> List.of(table(1)));
            final var metadata = result.tables().getFirst().metadata();
            assertEquals("Opening 8 9 Revenue 1 2 Costs 3 4 Taxes 5 7", result.tables().getFirst().text());
            assertEquals(1, metadata.get("pdf_table_added_leading_rows"));
            assertEquals(1, metadata.get("pdf_table_added_trailing_rows"));
            assertEquals("source_leading_numeric_row_v1", metadata.get("pdf_table_boundary_policy"));
        }
    }

    /**
     * Inferred anchors count against the raw-anchor ceiling, cancellation is observed, and options stay explicit.
     * @throws Exception if owned extraction fails unexpectedly
     */
    @Test
    void budgetsCancellationAndOptionsRetainExistingContracts() throws Exception {
        final var source = new ArrayList<>(body());
        source.addAll(row(92, "Taxes", "5", "7"));
        assertEquals(
                1, extract(source, PdfNativeLayoutOptions.TrailingRows.SOURCE_CELL_SPACER_ROWS, 72, 9).tables().size());
        final var failure = assertThrows(PartitionException.class,
                () -> extract(source, PdfNativeLayoutOptions.TrailingRows.SOURCE_CELL_SPACER_ROWS, 72, 8));
        assertEquals(PartitionException.Code.TABLE_LIMIT, failure.code());
        try (final var document = document(source)) {
            final var stripper = new PdfPageText(PdfOptions.defaults());
            stripper.getText(document);
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedIOException.class,
                        ()
                                -> PdfNativeTrailingRows.extend(List.of(table(1)), stripper.glyphs(),
                                        new PdfPageGeometry(document.getPage(0)), 1, 1, List.of(), new PdfTableWork()));
            } finally {
                Thread.interrupted();
            }
        }
        final var defaults = PdfNativeLayoutOptions.defaults();
        assertEquals(PdfNativeLayoutOptions.TrailingRows.PROVIDER_ONLY, defaults.trailingRows());
        assertEquals(PdfNativeLayoutOptions.TrailingRows.PROVIDER_ONLY,
                new PdfNativeLayoutOptions(72, 1, 1, PdfNativeLayoutOptions.CellTextJoin.SPACE_SEPARATED,
                        PdfNativeLayoutOptions.LeadingRow.SOURCE_NUMERIC_ROW)
                        .trailingRows());
        final var explicit = defaults.withTrailingRows(PdfNativeLayoutOptions.TrailingRows.SOURCE_CELL_SPACER_ROWS)
                                     .withLeadingRow(PdfNativeLayoutOptions.LeadingRow.SOURCE_NUMERIC_ROW)
                                     .withCellTextJoin(PdfNativeLayoutOptions.CellTextJoin.PRESERVE_LINE_BREAKS);
        assertEquals(PdfNativeLayoutOptions.TrailingRows.SOURCE_CELL_SPACER_ROWS, explicit.trailingRows());
        assertEquals(PdfNativeLayoutOptions.TrailingRows.PROVIDER_ONLY, defaults.trailingRows());
        assertThrows(NullPointerException.class, () -> defaults.withTrailingRows(null));
    }

    /**
     * Compares full extraction ownership against the unchanged provider-only policy.
     * @param source actual owned source placements
     * @throws Exception if extraction fails
     */
    private static void assertUnchanged(final List<Text> source) throws Exception {
        final var original = extract(source, PdfNativeLayoutOptions.TrailingRows.PROVIDER_ONLY, 72, 16);
        final var candidate = extract(source, PdfNativeLayoutOptions.TrailingRows.SOURCE_CELL_SPACER_ROWS, 72, 16);
        assertEquals(original.tables(), candidate.tables());
        assertEquals(original.remaining(), candidate.remaining());
    }

    /**
     * Two provider-covered table rows, each repeating the lattice.
     * @return immutable placements at baselines 52 and 72
     */
    private static List<Text> body() {
        final var result = new ArrayList<Text>();
        result.addAll(row(52, "Revenue", "1", "2"));
        result.addAll(row(72, "Costs", "3", "4"));
        return List.copyOf(result);
    }

    /**
     * One source line painting the lattice spacers with optional visible cells.
     * @param y displayed baseline
     * @param label first-column text, possibly empty
     * @param first second-column text, possibly empty
     * @param second third-column text, possibly empty
     * @return immutable placements
     */
    private static List<Text> row(final float y, final String label, final String first, final String second) {
        final var result = new ArrayList<>(spacers(y));
        if (!label.isEmpty()) {
            result.add(new Text(label, 12, y, 0));
        }
        if (!first.isEmpty()) {
            result.add(new Text(first, 100, y, 0));
        }
        if (!second.isEmpty()) {
            result.add(new Text(second, 150, y, 0));
        }
        return result;
    }

    /**
     * The two whitespace cell glyphs of one lattice line.
     * @param y displayed baseline
     * @return immutable spacer placements
     */
    private static List<Text> spacers(final float y) {
        return List.of(new Text(" ", 82, y, 0), new Text(" ", 137, y, 0));
    }

    /**
     * Concatenates visible unclaimed characters so ownership can be asserted without layout spacing.
     * @param glyphs unclaimed source positions
     * @return visible characters in source order
     */
    private static String visible(final List<PdfPageText.Glyph> glyphs) {
        final var text = new StringBuilder();
        for (final var glyph : glyphs) {
            if (!glyph.text().isBlank()) {
                text.append(glyph.text());
            }
        }
        return text.toString();
    }

    /**
     * Constructs a small PDF using actual text operators and independent glyph positions.
     * @param source displayed placements
     * @return open owned document; caller closes it
     * @throws IOException if PDF construction fails
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
                content.setTextMatrix(
                        Matrix.getRotateInstance(Math.toRadians(text.rotation()), text.x(), 200 - text.y()));
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
     * Runs the production native-provider pipeline with actual PDFBox glyph extraction and rendering.
     * @param source owned placements
     * @param trailingRows explicit inference policy
     * @param dpi render resolution
     * @param anchors aggregate anchor ceiling
     * @return extracted tables and unclaimed source positions
     * @throws Exception if PDF construction or extraction fails
     */
    private static PdfNativeProviderTables.Result extract(final List<Text> source,
            final PdfNativeLayoutOptions.TrailingRows trailingRows, final int dpi, final int anchors) throws Exception {
        try (final var document = document(source)) {
            final var stripper = new PdfPageText(PdfOptions.defaults());
            stripper.getText(document);
            return PdfNativeProviderTables.extract(document, 0, new PdfPageGeometry(document.getPage(0)),
                    stripper.glyphs(), PdfOptions.defaults().withTableCellCoordinates(true),
                    new PdfNativeLayoutOptions(dpi, 500_000, anchors).withTrailingRows(trailingRows),
                    image -> List.of(table(image.getWidth() / 260.0)));
        }
    }

    /**
     * Builds two complete uniform provider rows in the renderer's pixel frame.
     * @param scale rendered pixels per displayed source unit
     * @return immutable original provider topology covering displayed y 40 through 80
     */
    private static PdfTableLayoutProvider.Table table(final double scale) {
        final double[] edges = {10, 80, 135, 190};
        final var anchors = new ArrayList<PdfTableLayoutProvider.Anchor>();
        for (int row = 0; row < 2; row++) {
            for (int column = 0; column < 3; column++) {
                anchors.add(new PdfTableLayoutProvider.Anchor(row, column, 1, 1,
                        new PdfTableLayoutProvider.Box(edges[column] * scale, (40 + 20 * row) * scale,
                                edges[column + 1] * scale, (60 + 20 * row) * scale)));
            }
        }
        return new PdfTableLayoutProvider.Table(2, 3,
                new PdfTableLayoutProvider.Box(10 * scale, 40 * scale, 190 * scale, 80 * scale), anchors,
                "owned-trailing");
    }

    /**
     * Immutable placement used only to author owned source PDFs.
     * @param value source characters
     * @param x displayed left
     * @param y displayed baseline
     * @param rotation source text angle in degrees
     */
    private record Text(String value, float x, float y, int rotation) {}
}
