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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Actual source-glyph ownership and rejection controls for explicitly inferred section rows. */
class PdfNativeSectionRowTest {
    /** Creates the owned source-control suite. */
    PdfNativeSectionRowTest() {
    }

    /**
     * Source indentation and separated currency runs retain their true alignment and cell ownership.
     * @throws Exception if owned PDF extraction fails
     */
    @Test
    void currencyGapsAndIndentationRecoverOnlyUnderExplicitSectionPolicy() throws Exception {
        final var source = source("Obligations:", 12, "$", "12", "34");
        final var provider = extract(source, 3, 72, PdfNativeLayoutOptions.LeadingRow.PROVIDER_ONLY, 6);
        final var numeric = extract(source, 3, 72, PdfNativeLayoutOptions.LeadingRow.SOURCE_NUMERIC_ROW, 6);
        final var section = extract(source, 3, 72, PdfNativeLayoutOptions.LeadingRow.SOURCE_NUMERIC_OR_SECTION_ROW, 6);
        assertEquals(provider.tables(), numeric.tables());
        assertFalse(provider.remaining().isEmpty());
        assertTrue(section.remaining().isEmpty());
        assertEquals("Obligations: Payables $ 12 34", section.tables().getFirst().text());
        assertEquals("source_leading_numeric_or_section_row_v1",
                section.tables().getFirst().metadata().get("pdf_table_boundary_policy"));
        assertEquals(1, section.tables().getFirst().metadata().get("pdf_table_added_leading_rows"));
        assertEquals("owned-section", section.tables().getFirst().metadata().get("pdf_table_layout_provenance"));
        final String html = (String) section.tables().getFirst().metadata().get("text_as_html");
        assertTrue(html.contains("<tr><td>Obligations:</td><td></td><td></td></tr>"), html);
        assertEquals(1, section.tables().size());
    }

    /**
     * A structurally distinct four-column owned PDF transfers the rule across render scale and currency.
     * @throws Exception if owned PDF extraction fails
     */
    @Test
    void fourColumnTransferRetainsSourceAtHigherRenderResolution() throws Exception {
        final var source = new ArrayList<>(source("Commitments:", 12, "£", "120", "340"));
        source.add(new Text("560", 205, 72, 0));
        final var result = extract(source, 4, 144, PdfNativeLayoutOptions.LeadingRow.SOURCE_NUMERIC_OR_SECTION_ROW, 8);
        assertEquals("Commitments: Payables £ 120 340 560", result.tables().getFirst().text());
        assertTrue(result.remaining().isEmpty());
        assertEquals(1, result.tables().getFirst().metadata().get("pdf_table_added_leading_rows"));
    }

    /**
     * Punctuation cannot promote prose, centered titles, incomplete rows or multiple currency marks.
     * @throws Exception if owned source extraction fails
     */
    @Test
    void unrelatedOrIncompleteSourceRetainsOriginalOwnership() throws Exception {
        final var prose = source("Notes:", 12, "", "see", "appendix");
        final var centered = source("Summary:", 40, "$", "12", "34");
        final var partial = source("Notes:", 12, "$", "12", "");
        final var doubleCurrency = source("Notes:", 12, "$$", "12", "34");
        final var noColon = source("Notes", 12, "$", "12", "34");
        for (final var source : List.of(prose, centered, partial, doubleCurrency, noColon)) {
            assertUnchanged(source);
        }
    }

    /**
     * Vertically separated currency/digits and wrapped labels do not become one body baseline.
     * @throws Exception if owned PDF extraction fails
     */
    @Test
    void separateBaselinesCannotMasqueradeAsAccountingRuns() throws Exception {
        final var currency = new ArrayList<>(source("Notes:", 12, "$", "12", "34"));
        currency.set(3, new Text("$", 90, 64, 0));
        assertUnchanged(currency);
        final var label = new ArrayList<>(source("Notes:", 12, "$", "12", "34"));
        label.add(new Text("Other", 20, 64, 0));
        assertUnchanged(label);
    }

    /**
     * Rotation, cross-column text, distance and multiple leading baselines remain unsupported.
     * @throws Exception if owned PDF extraction fails
     */
    @Test
    void ambiguousLeadingGeometryRemainsOrdinaryText() throws Exception {
        final var rotated = new ArrayList<>(source("Notes:", 12, "$", "12", "34"));
        rotated.set(0, new Text("Notes:", 12, 52, 90));
        final var crossing = source("This section crosses into another column:", 12, "$", "12", "34");
        final var distant = new ArrayList<>(source("Notes:", 12, "$", "12", "34"));
        distant.set(0, new Text("Notes:", 12, 22, 0));
        final var multiline = new ArrayList<>(source("Notes:", 12, "$", "12", "34"));
        multiline.add(new Text("More:", 12, 43, 0));
        for (final var source : List.of(rotated, crossing, distant, multiline)) {
            assertUnchanged(source);
        }
    }

    /**
     * A real source row preserves original anchors and cannot steal another provider's envelope.
     * @throws Exception if owned PDF extraction fails
     */
    @Test
    void topologyAndCompetingOwnershipRemainValidated() throws Exception {
        try (final var document = document(source("Notes:", 12, "$", "12", "34"))) {
            final var geometry = new PdfPageGeometry(document.getPage(0));
            final var stripper = new PdfPageText(PdfOptions.defaults());
            stripper.getText(document);
            final var original = table(3, 1);
            final var expanded = PdfNativeLeadingRow
                                         .extend(List.of(original), stripper.glyphs(), geometry, 1, 1, List.of(), true,
                                                 new PdfTableWork())
                                         .getFirst();
            assertEquals(2, expanded.rows());
            for (int index = 0; index < 3; index++) {
                assertEquals(original.anchors().get(index).bounds(), expanded.anchors().get(index + 3).bounds());
                assertEquals(1, expanded.anchors().get(index + 3).row());
            }
            final var box = new PdfTableLayoutProvider.Box(10, 40, 190, 60);
            final var other = new PdfTableLayoutProvider.Table(
                    1, 1, box, List.of(new PdfTableLayoutProvider.Anchor(0, 0, 1, 1, box)), "competing");
            assertSame(original, PdfNativeLeadingRow
                                         .extend(List.of(other, original), stripper.glyphs(), geometry, 1, 1, List.of(),
                                                 true, new PdfTableWork())
                                         .get(1));
        }
    }

    /**
     * Inferred blank cells count against the same raw-anchor limit, and option copies remain independent.
     * @throws Exception if owned extraction fails unexpectedly
     */
    @Test
    void addedAnchorsAndOptionCopiesRetainExistingContracts() throws Exception {
        final var failure = assertThrows(PartitionException.class,
                ()
                        -> extract(source("Notes:", 12, "$", "12", "34"), 3, 72,
                                PdfNativeLayoutOptions.LeadingRow.SOURCE_NUMERIC_OR_SECTION_ROW, 5));
        assertEquals(PartitionException.Code.TABLE_LIMIT, failure.code());
        final var policy = PdfNativeLayoutOptions.defaults()
                                   .withLeadingRow(PdfNativeLayoutOptions.LeadingRow.SOURCE_NUMERIC_OR_SECTION_ROW)
                                   .withCellTextJoin(PdfNativeLayoutOptions.CellTextJoin.PRESERVE_LINE_BREAKS);
        assertEquals(PdfNativeLayoutOptions.LeadingRow.SOURCE_NUMERIC_OR_SECTION_ROW, policy.leadingRow());
        assertEquals(PdfNativeLayoutOptions.LeadingRow.PROVIDER_ONLY, PdfNativeLayoutOptions.defaults().leadingRow());
    }

    /**
     * Compares full extraction ownership against the unchanged provider-only policy.
     * @param source actual owned source placements
     * @throws Exception if extraction fails
     */
    private static void assertUnchanged(final List<Text> source) throws Exception {
        final var original = extract(source, 3, 72, PdfNativeLayoutOptions.LeadingRow.PROVIDER_ONLY, 6);
        final var candidate =
                extract(source, 3, 72, PdfNativeLayoutOptions.LeadingRow.SOURCE_NUMERIC_OR_SECTION_ROW, 6);
        assertEquals(original.tables(), candidate.tables());
        assertEquals(original.remaining(), candidate.remaining());
    }

    /**
     * Creates the source layout with a real leading indentation-space glyph in the body label cell.
     * @param label section text
     * @param x section start
     * @param currency optional separated mark
     * @param first first numeric value
     * @param second second numeric value
     * @return immutable source placements in displayed coordinates
     */
    private static List<Text> source(
            final String label, final float x, final String currency, final String first, final String second) {
        return List.of(new Text(label, x, 52, 0), new Text(" ", 12, 72, 0), new Text("Payables", 20, 72, 0),
                new Text(currency, 90, 72, 0), new Text(first, 116, 72, 0), new Text(second, 150, 72, 0));
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
     * @param columns provider column count
     * @param dpi render resolution
     * @param leadingRow explicit inference policy
     * @param anchors aggregate anchor ceiling
     * @return extracted tables and unclaimed source positions
     * @throws Exception if PDF construction or extraction fails
     */
    private static PdfNativeProviderTables.Result extract(final List<Text> source, final int columns, final int dpi,
            final PdfNativeLayoutOptions.LeadingRow leadingRow, final int anchors) throws Exception {
        try (final var document = document(source)) {
            final var stripper = new PdfPageText(PdfOptions.defaults());
            stripper.getText(document);
            return PdfNativeProviderTables.extract(document, 0, new PdfPageGeometry(document.getPage(0)),
                    stripper.glyphs(), PdfOptions.defaults().withTableCellCoordinates(true),
                    new PdfNativeLayoutOptions(dpi, 500_000, anchors).withLeadingRow(leadingRow),
                    image -> List.of(table(columns, image.getWidth() / 260.0)));
        }
    }

    /**
     * Builds one complete uniform provider row in the renderer's pixel frame.
     * @param columns three or four physical columns
     * @param scale rendered pixels per displayed source unit
     * @return immutable original provider topology
     */
    private static PdfTableLayoutProvider.Table table(final int columns, final double scale) {
        final double[] edges = columns == 3 ? new double[] {10, 80, 135, 190} : new double[] {10, 80, 135, 190, 250};
        final var anchors = new ArrayList<PdfTableLayoutProvider.Anchor>();
        for (int column = 0; column < columns; column++) {
            anchors.add(new PdfTableLayoutProvider.Anchor(0, column, 1, 1,
                    new PdfTableLayoutProvider.Box(
                            edges[column] * scale, 60 * scale, edges[column + 1] * scale, 80 * scale)));
        }
        return new PdfTableLayoutProvider.Table(1, columns,
                new PdfTableLayoutProvider.Box(10 * scale, 60 * scale, edges[columns] * scale, 80 * scale), anchors,
                "owned-section");
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
