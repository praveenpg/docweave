package dev.docweave.partition;

import dev.docweave.core.ElementType;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Source ownership, topology, conservative rejection and budget controls for explicit leading rows. */
class PdfNativeLeadingRowTest {
    /** Creates the owned control suite. */
    PdfNativeLeadingRowTest() {
    }

    /**
     * An actual source row moves exactly once into the table only when explicitly selected.
     * @throws Exception if owned PDF construction or bounded extraction fails
     */
    @Test
    void sourceRowIsOptInAndPreservesOldAnchorsAndProviderProvenance() throws Exception {
        try (final var document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(200, 200));
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                final var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                for (final var text : List.of(new Object[] {"Earlier", 12f, 148f}, new Object[] {"12", 90f, 148f},
                             new Object[] {"34", 145f, 148f}, new Object[] {"Current", 12f, 128f},
                             new Object[] {"56", 90f, 128f}, new Object[] {"78", 145f, 128f})) {
                    content.beginText();
                    content.setFont(font, 8);
                    content.newLineAtOffset((float) text[1], (float) text[2]);
                    content.showText((String) text[0]);
                    content.endText();
                }
            }
            final var geometry = new PdfPageGeometry(page);
            final var stripper = new PdfPageText(PdfOptions.defaults());
            stripper.getText(document);
            final var options = PdfOptions.defaults().withTableCellCoordinates(true);
            final var policy = new PdfNativeLayoutOptions(72, 40_000, 6);
            final var plain = PdfNativeProviderTables.extract(
                    document, 0, geometry, stripper.glyphs(), options, policy, image -> List.of(table()));
            final var extended = PdfNativeProviderTables.extract(document, 0, geometry, stripper.glyphs(), options,
                    policy.withLeadingRow(PdfNativeLayoutOptions.LeadingRow.SOURCE_NUMERIC_ROW),
                    image -> List.of(table()));
            assertEquals("Current 56 78", plain.tables().getFirst().text());
            assertEquals("Earlier 12 34 Current 56 78", extended.tables().getFirst().text());
            assertFalse(plain.remaining().isEmpty());
            assertTrue(extended.remaining().isEmpty());
            final var metadata = extended.tables().getFirst().metadata();
            assertEquals("source_leading_numeric_row_v1", metadata.get("pdf_table_boundary_policy"));
            assertEquals(1, metadata.get("pdf_table_added_leading_rows"));
            assertEquals("owned-provider", metadata.get("pdf_table_layout_provenance"));
            assertFalse(plain.tables().getFirst().metadata().containsKey("pdf_table_boundary_policy"));
            final var limited = assertThrows(PartitionException.class,
                    ()
                            -> PdfNativeProviderTables.extract(document, 0, geometry, stripper.glyphs(), options,
                                    new PdfNativeLayoutOptions(72, 40_000, 5)
                                            .withLeadingRow(PdfNativeLayoutOptions.LeadingRow.SOURCE_NUMERIC_ROW),
                                    image -> List.of(table())));
            assertEquals(PartitionException.Code.TABLE_LIMIT, limited.code());
        }
    }

    /**
     * Inference preserves the old physical anchors, shifts logical indices and passes complete validation.
     * @throws Exception if owned PDF construction or bounded extraction fails
     */
    @Test
    void completeNumericRowAddsOnlyOneValidatedRow() throws Exception {
        final var original = table();
        final var expanded = extend(original, glyphs());
        assertEquals(2, expanded.rows());
        assertEquals(6, expanded.anchors().size());
        for (int i = 0; i < 3; i++) {
            assertEquals(original.anchors().get(i).bounds(), expanded.anchors().get(i + 3).bounds());
            assertEquals(1, expanded.anchors().get(i + 3).row());
        }
        final var image = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        try {
            assertEquals(List.of(expanded),
                    PdfTableLayouts.validate(List.of(expanded), image, TableLimits.defaults(),
                            new PdfNativeLayoutOptions(72, 40_000, 6).validation(), new PdfTableWork()));
        } finally {
            image.flush();
        }
        assertEquals("owned-provider", expanded.provenance());
    }

    /**
     * Headers, merged first rows and two-column layouts cannot be extended by this body-row policy.
     * @throws Exception if owned PDF construction or bounded extraction fails
     */
    @Test
    void ambiguousTopologyRetainsOriginalIdentity() throws Exception {
        final var original = table();
        final var header = new PdfTableLayoutProvider.Table(1, 3, original.bounds(), original.anchors(), "owned", 1);
        assertSame(header, extend(header, glyphs()));
        final var merged = new PdfTableLayoutProvider.Table(1, 3, original.bounds(),
                List.of(new PdfTableLayoutProvider.Anchor(0, 0, 1, 3, original.bounds())), "owned");
        assertSame(merged, extend(merged, glyphs()));
        final var two = new PdfTableLayoutProvider.Table(1, 2, original.bounds(),
                List.of(new PdfTableLayoutProvider.Anchor(0, 0, 1, 1, box(10, 60, 80, 80)),
                        new PdfTableLayoutProvider.Anchor(0, 1, 1, 1, box(80, 60, 190, 80))),
                "owned");
        assertSame(two, extend(two, glyphs()));
    }

    /**
     * Partial, multiline, nonnumeric, rotated, distant and boundary-crossing source rows are not guessed.
     * @throws Exception if owned PDF construction or bounded extraction fails
     */
    @Test
    void uncertainSourceRowsRemainOrdinaryText() throws Exception {
        final var original = table();
        final var controls = new ArrayList<List<PdfPageText.Glyph>>();
        controls.add(List.of(glyph("Earlier", 12, 52, 30, 0), glyph("12", 90, 52, 10, 0)));
        controls.add(List.of(glyph("Earlier", 12, 52, 30, 0), glyph("12", 90, 52, 10, 0), glyph("34", 145, 48, 10, 0)));
        controls.add(
                List.of(glyph("Earlier", 12, 52, 30, 0), glyph("words", 90, 52, 20, 0), glyph("34", 145, 52, 10, 0)));
        controls.add(
                List.of(glyph("Earlier", 12, 52, 30, 90), glyph("12", 90, 52, 10, 0), glyph("34", 145, 52, 10, 0)));
        controls.add(List.of(glyph("Earlier", 12, 22, 30, 0), glyph("12", 90, 22, 10, 0), glyph("34", 145, 22, 10, 0)));
        controls.add(List.of(glyph("Earlier", 70, 52, 20, 0), glyph("12", 90, 52, 10, 0), glyph("34", 145, 52, 10, 0)));
        controls.add(
                List.of(glyph("Earlier", 12, 52, 30, 0), glyph("(12", 90, 52, 10, 0), glyph("34", 145, 52, 10, 0)));
        for (final var control : controls) {
            assertSame(original, extend(original, control));
        }
    }

    /**
     * Other provider and native tables block an expansion while preserving the raw table.
     * @throws Exception if owned PDF construction or bounded extraction fails
     */
    @Test
    void competingOwnershipPreventsExpansion() throws Exception {
        final var original = table();
        final var other = new PdfTableLayoutProvider.Table(1, 1, box(10, 40, 190, 60),
                List.of(new PdfTableLayoutProvider.Anchor(0, 0, 1, 1, box(10, 40, 190, 60))), "other");
        final var geometry = new PdfPageGeometry(new PDPage(new PDRectangle(200, 200)));
        final var competing = PdfNativeLeadingRow.extend(
                List.of(other, original), glyphs(), geometry, 1, 1, List.of(), new PdfTableWork());
        assertSame(original, competing.get(1));
        final var nativeTable = new PdfLayoutItem(
                ElementType.Table, "existing", new PdfLayoutItem.Bounds(10, 40, 190, 60), java.util.Map.of());
        assertSame(original,
                PdfNativeLeadingRow
                        .extend(List.of(original), glyphs(), geometry, 1, 1, List.of(nativeTable), new PdfTableWork())
                        .getFirst());
    }

    /** Existing constructors stay conservative; copy methods retain both independent policies. */
    @Test
    void optionCompatibilityAndNullContracts() {
        final var defaults = PdfNativeLayoutOptions.defaults();
        assertEquals(PdfNativeLayoutOptions.LeadingRow.PROVIDER_ONLY, defaults.leadingRow());
        assertEquals(PdfNativeLayoutOptions.LeadingRow.PROVIDER_ONLY,
                new PdfNativeLayoutOptions(72, 40_000, 6, PdfNativeLayoutOptions.CellTextJoin.PRESERVE_LINE_BREAKS)
                        .leadingRow());
        final var explicit = defaults.withLeadingRow(PdfNativeLayoutOptions.LeadingRow.SOURCE_NUMERIC_ROW)
                                     .withCellTextJoin(PdfNativeLayoutOptions.CellTextJoin.PRESERVE_LINE_BREAKS);
        assertEquals(PdfNativeLayoutOptions.LeadingRow.SOURCE_NUMERIC_ROW, explicit.leadingRow());
        assertEquals(PdfNativeLayoutOptions.CellTextJoin.PRESERVE_LINE_BREAKS, explicit.cellTextJoin());
        assertThrows(NullPointerException.class, () -> defaults.withLeadingRow(null));
    }

    /**
     * Builds the immutable original provider claim.
     * @return complete owned three-column provider table
     */
    private static PdfTableLayoutProvider.Table table() {
        return new PdfTableLayoutProvider.Table(1, 3, box(10, 60, 190, 80),
                List.of(new PdfTableLayoutProvider.Anchor(0, 0, 1, 1, box(10, 60, 80, 80)),
                        new PdfTableLayoutProvider.Anchor(0, 1, 1, 1, box(80, 60, 135, 80)),
                        new PdfTableLayoutProvider.Anchor(0, 2, 1, 1, box(135, 60, 190, 80))),
                "owned-provider");
    }

    /**
     * Supplies one independently placed native row.
     * @return complete source row above the owned table
     */
    private static List<PdfPageText.Glyph> glyphs() {
        return List.of(glyph("Earlier", 12, 52, 30, 0), glyph("12", 90, 52, 10, 0), glyph("34", 145, 52, 10, 0));
    }

    /**
     * Creates an owned source position without font-engine state.
     * @param text source Unicode
     * @param x source left
     * @param y baseline
     * @param width advance
     * @param direction source orientation
     * @return owned glyph metrics
     */
    private static PdfPageText.Glyph glyph(
            final String text, final float x, final float y, final float width, final int direction) {
        return new PdfPageText.Glyph(text, x, y, width, 6, 3, direction);
    }

    /**
     * Applies the bounded helper in an upright owned frame.
     * @param table raw table
     * @param glyphs unclaimed positions
     * @return sole inferred or unchanged table
     * @throws IOException if cancelled or bounded work fails
     */
    private static PdfTableLayoutProvider.Table extend(
            final PdfTableLayoutProvider.Table table, final List<PdfPageText.Glyph> glyphs) throws IOException {
        return PdfNativeLeadingRow
                .extend(List.of(table), glyphs, new PdfPageGeometry(new PDPage(new PDRectangle(200, 200))), 1, 1,
                        List.of(), new PdfTableWork())
                .getFirst();
    }

    /**
     * Creates a positive owned rectangle.
     * @param left left edge
     * @param top top edge
     * @param right right edge
     * @param bottom bottom edge
     * @return owned rectangle
     */
    private static PdfTableLayoutProvider.Box box(
            final double left, final double top, final double right, final double bottom) {
        return new PdfTableLayoutProvider.Box(left, top, right, bottom);
    }
}
