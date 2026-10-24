package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.core.ElementType;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Source-line and production ownership controls for opt-in wrapped ISO dates. */
class PdfNativeCellTextTest {
    /** Creates a call-confined set of native cell text contract controls. */
    PdfNativeCellTextTest() {
    }

    /**
     * Script interpretation does not implicitly activate date joining when no small-glyph run exists.
     * @throws Exception if owned PDF creation or native extraction fails
     */
    @Test
    void inlineScriptPolicyPreservesOrdinaryWrappedDates() throws Exception {
        final var bytes = source();
        for (final boolean first : List.of(false, true)) {
            assertEquals(
                    extract(PdfOptions.defaults(), PdfNativeLayoutOptions.CellTextJoin.SPACE_SEPARATED, first, bytes)
                            .elements(),
                    extract(PdfOptions.defaults(), PdfNativeLayoutOptions.CellTextJoin.INLINE_SCRIPT_RUNS, first, bytes)
                            .elements());
        }
    }

    /**
     * Distinguishes actual baseline changes from horizontally separated runs and ambiguous directions.
     * @throws Exception if source-line processing is interrupted
     */
    @Test
    void nativeLineBreaksKeepSameBaselineRunsAndEscapeSourceMarkup() throws Exception {
        final var lines =
                List.of(line("A & <B>", 10, 20, Map.of()), line("C", 90, 20, Map.of()), line("D", 10, 32, Map.of()));
        final String text = PdfNativeCellText.preserveLineBreaks(lines, "A & <B> C D");
        assertEquals("A & <B> C\nD", text);
        assertEquals("A &amp; &lt;B&gt; C<br>D",
                PdfNativeCellText.html(text, PdfNativeLayoutOptions.CellTextJoin.PRESERVE_LINE_BREAKS));
        final String original = "A B";
        assertSame(original,
                PdfNativeCellText.preserveLineBreaks(
                        List.of(line("A", 10, 20, Map.of()), line("B", 10, 32, Map.of("pdf_text_rotation", 90))),
                        original));
        assertSame(original, PdfNativeCellText.preserveLineBreaks(List.of(), original));
    }

    /**
     * Both provider factories retain native date lines without inventing table rows or moving ordinary text.
     * @throws Exception if owned PDF creation or native extraction fails
     */
    @Test
    void nativeProviderLineBreaksPreserveTopologyCoordinatesAndOutsideOwnership() throws Exception {
        for (final boolean first : List.of(false, true)) {
            for (final boolean coordinates : List.of(false, true)) {
                final var options = PdfOptions.defaults().withTableCellCoordinates(coordinates);
                final var bytes = source();
                final var before = extract(options, PdfNativeLayoutOptions.CellTextJoin.SPACE_SEPARATED, first, bytes);
                final var after =
                        extract(options, PdfNativeLayoutOptions.CellTextJoin.PRESERVE_LINE_BREAKS, first, bytes);
                final var original =
                        before.elements().stream().filter(e -> e.type() == ElementType.Table).findFirst().orElseThrow();
                final var table =
                        after.elements().stream().filter(e -> e.type() == ElementType.Table).findFirst().orElseThrow();
                assertEquals("2025-01-\n02", table.text());
                assertEquals("<table><tbody><tr><td>2025-01-<br>02</td></tr></tbody></table>",
                        table.metadata().get("text_as_html"));
                assertEquals("native_line_breaks_v1", table.metadata().get("pdf_table_cell_text_policy"));
                assertEquals(1, table.metadata().get("pdf_table_multiline_cells"));
                assertFalse(table.metadata().containsKey("pdf_table_joined_iso_dates"));
                assertEquals(original.metadata().get("coordinates"), table.metadata().get("coordinates"));
                assertEquals(original.metadata().get("pdf_table_cells"), table.metadata().get("pdf_table_cells"));
                assertEquals(before.elements().stream().filter(e -> e.type() != ElementType.Table).toList(),
                        after.elements().stream().filter(e -> e.type() != ElementType.Table).toList());
            }
        }
    }

    /** Checks valid leap years and rejects incomplete, malformed, ambiguous and non-ASCII date fragments. */
    @Test
    void validCalendarDatesRequireCompleteHyphenTerminatedSourceLines() {
        for (final var source :
                List.of(List.of("2025-01-", "02"), List.of("2024-", "02-", "29"), List.of("2000-", "02-29"))) {
            assertEquals(String.join("", source), join(source));
        }
        for (final var source : List.of(List.of("2025-02-", "29"), List.of("1900-02-", "29"), List.of("2025-13-", "01"),
                     List.of("2025-00-", "01"), List.of("2025-01-", "00"), List.of("2025-01-0", "2"),
                     List.of("2025", "-01-02"), List.of("2025-01-", "02 extra"), List.of("2025-01-", "02 03"),
                     List.of("2025-", "01-", "0", "2"), List.of("2025-01-", "０２"), List.of("2025-01-", " 02"),
                     List.of("Range-", "02"))) {
            assertEquals(String.join(" ", source), join(source));
        }
    }

    /** Keeps same-baseline, offset and rotated source runs unchanged. */
    @Test
    void sameBaselineSeparateRunsOffsetAndRotatedLinesRetainOriginalText() {
        final var first = line("2025-01-", 10, 20, Map.of());
        for (final var last : List.of(line("02", 10, 20, Map.of()), line("02", 13, 32, Map.of()),
                     line("02", 10, 32, Map.of("pdf_text_rotation", 90)))) {
            final String original = "2025-01- 02";
            assertSame(original, PdfNativeCellText.wrappedIsoDate(List.of(first, last), original));
        }
    }

    /**
     * Exercises both native factories and coordinate modes against unchanged source ownership.
     * @throws Exception if owned PDF creation or extraction fails
     */
    @Test
    void nativeProviderFactoriesPreserveGeometryOutsideTextAndDefaults() throws Exception {
        final var bytes = source();
        for (final boolean nativeFirst : List.of(false, true)) {
            for (final boolean coordinates : List.of(false, true)) {
                final var options = PdfOptions.defaults().withTableCellCoordinates(coordinates);
                final var original =
                        extract(options, PdfNativeLayoutOptions.CellTextJoin.SPACE_SEPARATED, nativeFirst, bytes);
                final var joined =
                        extract(options, PdfNativeLayoutOptions.CellTextJoin.WRAPPED_ISO_DATE, nativeFirst, bytes);
                final var before = original.elements()
                                           .stream()
                                           .filter(e -> e.type() == ElementType.Table)
                                           .findFirst()
                                           .orElseThrow();
                final var after =
                        joined.elements().stream().filter(e -> e.type() == ElementType.Table).findFirst().orElseThrow();
                assertEquals("2025-01- 02", before.text());
                assertEquals("2025-01-02", after.text());
                assertEquals("<table><tbody><tr><td>2025-01-02</td></tr></tbody></table>",
                        after.metadata().get("text_as_html"));
                assertEquals(before.metadata().get("coordinates"), after.metadata().get("coordinates"));
                assertEquals(before.metadata().get("pdf_table_cells"), after.metadata().get("pdf_table_cells"));
                assertEquals("wrapped_iso_date_v1", after.metadata().get("pdf_table_cell_text_policy"));
                assertEquals(1, after.metadata().get("pdf_table_joined_iso_dates"));
                assertFalse(before.metadata().containsKey("pdf_table_cell_text_policy"));
                assertEquals(original.elements().stream().filter(e -> e.type() != ElementType.Table).toList(),
                        joined.elements().stream().filter(e -> e.type() != ElementType.Table).toList());
                assertEquals(1, joined.elements().stream().filter(e -> e.text().equals("Outside")).count());
            }
        }
    }

    /**
     * Ensures removing joining spaces cannot bypass the original native cell character budget.
     * @throws Exception if owned PDF creation fails
     */
    @Test
    void originalCellBudgetIsEnforcedBeforeJoining() throws Exception {
        final var d = PdfOptions.defaults();
        final var options = new PdfOptions(d.maxPages(), d.maxGlyphsPerPage(), d.maxOperatorsPerPage(),
                d.maxImagePixels(), d.maxImageBytes(), false, new TableLimits(10, 10, 100, 10));
        final var failure = assertThrows(PartitionException.class,
                () -> extract(options, PdfNativeLayoutOptions.CellTextJoin.WRAPPED_ISO_DATE, false, source()));
        assertEquals(PartitionException.Code.TABLE_LIMIT, failure.code());
        final var preserved = assertThrows(PartitionException.class,
                () -> extract(options, PdfNativeLayoutOptions.CellTextJoin.PRESERVE_LINE_BREAKS, false, source()));
        assertEquals(PartitionException.Code.TABLE_LIMIT, preserved.code());
    }

    /** Retains the historical constructor/default contract and validates explicit immutable copies. */
    @Test
    void legacyPolicyConstructionPreservesDefaultAndRejectsNull() {
        final var old = new PdfNativeLayoutOptions(72, 40_000, 16);
        assertEquals(PdfNativeLayoutOptions.CellTextJoin.SPACE_SEPARATED, old.cellTextJoin());
        assertEquals(
                old, new PdfNativeLayoutOptions(72, 40_000, 16, PdfNativeLayoutOptions.CellTextJoin.SPACE_SEPARATED));
        assertThrows(NullPointerException.class, () -> old.withCellTextJoin(null));
    }

    /**
     * Supplies aligned native lines to the bounded policy for source-form controls.
     * @param text explicit original lines
     * @return policy result
     */
    private static String join(final List<String> text) {
        final var lines = new ArrayList<PdfLayoutItem>();
        for (int i = 0; i < text.size(); i++) {
            lines.add(line(text.get(i), 10, 20 + i * 12, Map.of()));
        }
        return PdfNativeCellText.wrappedIsoDate(lines, String.join(" ", text));
    }

    /**
     * Creates a native run with an explicit source baseline.
     * @param text native run text
     * @param left left source coordinate
     * @param baseline native baseline
     * @param metadata native orientation metadata
     * @return synthetic source run
     */
    private static PdfLayoutItem line(
            final String text, final float left, final float baseline, final Map<String, Object> metadata) {
        return new PdfLayoutItem(ElementType.UncategorizedText, text,
                new PdfLayoutItem.Bounds(left, baseline - 8, left + 60, baseline), metadata);
    }

    /**
     * Exercises public factories with one complete, independently supplied cell.
     * @param options native source and output budgets
     * @param join explicit text policy
     * @param nativeFirst whether accepted native tables have precedence
     * @param bytes original owned PDF
     * @return ordinary document result
     * @throws Exception if the real source pipeline fails
     */
    private static PartitionResult extract(final PdfOptions options, final PdfNativeLayoutOptions.CellTextJoin join,
            final boolean nativeFirst, final byte[] bytes) throws Exception {
        final var bounds = new PdfTableLayoutProvider.Box(20, 30, 180, 90);
        final PdfTableLayoutProvider provider = image
                -> List.of(new PdfTableLayoutProvider.Table(
                        1, 1, bounds, List.of(new PdfTableLayoutProvider.Anchor(0, 0, 1, 1, bounds)), "owned-date"));
        final var policy = new PdfNativeLayoutOptions(72, 40_000, 16).withCellTextJoin(join);
        final var partitioner = nativeFirst ? PdfPartitioner.createWithNativeTablesFirst(options, policy, provider)
                                            : PdfPartitioner.createWithNativeLayout(options, policy, provider);
        return DocWeave.create(PartitionerRegistry.of(List.of(partitioner)))
                .partition(DocumentSource.bytes(bytes, "date.pdf"));
    }

    /**
     * Writes an entire date on two source baselines plus independently owned outside text.
     * @return owned original PDF bytes
     * @throws Exception if PDF creation fails
     */
    private static byte[] source() throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(200, 200));
            document.addPage(page);
            try (final var stream = new PDPageContentStream(document, page)) {
                draw(stream, "2025-01-", 30, 150);
                draw(stream, "02", 30, 138);
                draw(stream, "Outside", 20, 185);
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    /**
     * Draws one owned source line without inferred spacing.
     * @param stream open caller-owned content stream
     * @param text original line
     * @param x source x coordinate
     * @param y source baseline
     * @throws java.io.IOException if PDF writing fails
     */
    private static void draw(final PDPageContentStream stream, final String text, final float x, final float y)
            throws java.io.IOException {
        stream.beginText();
        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
        stream.newLineAtOffset(x, y);
        stream.showText(text);
        stream.endText();
    }
}
