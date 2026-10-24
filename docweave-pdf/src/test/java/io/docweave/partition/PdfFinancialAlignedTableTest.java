package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.core.ElementType;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Owned native financial-table controls; classified source tokens are never parsed or rewritten. */
class PdfFinancialAlignedTableTest {
    /** Creates independent detector and full-PDF controls. */
    PdfFinancialAlignedTableTest() {
    }

    /**
     * Accounting/currency bodies become tables only under the new explicit native policy, with exact source values.
     * @throws Exception if source authoring or extraction fails
     */
    @Test
    void financialPolicyPreservesCompleteSourceCellsAndExistingPolicies() throws Exception {
        final var source = source("(10.00)", "$20.00");
        for (final var policy :
                List.of(PdfOptions.TableDetection.RULED_ONLY, PdfOptions.TableDetection.RULED_AND_ALIGNED_TEXT,
                        PdfOptions.TableDetection.RULED_AND_MULTILINE_ALIGNED_TEXT)) {
            assertTrue(processor(policy).partition(source).elements().stream().noneMatch(
                    e -> e.type() == ElementType.Table));
        }
        final var result = processor(PdfOptions.TableDetection.RULED_AND_FINANCIAL_ALIGNED_TEXT).partition(source);
        assertEquals(1, result.elements().size());
        final var table = result.elements().getFirst();
        assertEquals(ElementType.Table, table.type());
        assertEquals("Item Value Alpha (10.00) Beta $20.00", table.text());
        assertEquals("<table><tbody><tr><td>Item</td><td>Value</td></tr><tr><td>Alpha</td><td>(10.00)</td></tr>"
                             + "<tr><td>Beta</td><td>$20.00</td></tr></tbody></table>",
                table.metadata().get("text_as_html"));
        assertEquals(Map.of("rows", 3, "columns", 2, "numeric_columns", List.of(1), "policy", "aligned_financial_v1"),
                table.metadata().get("pdf_table_evidence"));
        assertTrue(result.warnings().stream().anyMatch(w -> w.message().contains("is heuristic")));
    }

    /**
     * Fixed token grammar admits currencies/accounting/Unicode minus without relaxing complete-block geometry.
     * @throws Exception if detector work or resource checks fail
     */
    @Test
    void financialTokensRetainNarrowNumericAndGeometricBoundaries() throws Exception {
        final var detector = AlignedPdfTableDetector.financial(TableLimits.defaults());
        for (final String value : List.of("$10", "€ 10.25", "£\u00a0100", "¥\u202f2,000", "(10)", "($10)", "$(10)",
                     "−12.5", "+10%", "(12.5%)", "001", "-10.00")) {
            final var rows = runs(value, value);
            assertEquals(1, detector.detect(rows, List.of(), List.of(), new PdfTableWork()).size(), value);
            assertEquals(List.of(1),
                    detector.detect(rows, List.of(), List.of(), new PdfTableWork()).getFirst().numericColumns());
        }
        for (final String value : List.of("$", "()", "(10", "10)", "($10", "(-10)", "$$10", "About 10", "10 USD",
                     "10/20", "2026-10-20", "1e3", "—", "-", "NaN", "１０", "10*", "10\n20", "10\t20")) {
            assertTrue(detector.detect(runs(value, "20"), List.of(), List.of(), new PdfTableWork()).isEmpty(), value);
        }
        final var ragged = new ArrayList<>(runs("$10", "(20)"));
        ragged.removeLast();
        assertTrue(detector.detect(ragged, List.of(), List.of(), new PdfTableWork()).isEmpty());
        final var uneven = new ArrayList<>(runs("$10", "(20)"));
        uneven.set(4, new PdfTextRun("Beta", 40, 159, 80, 166));
        uneven.set(5, new PdfTextRun("(20)", 220, 159, 280, 166));
        assertTrue(detector.detect(uneven, List.of(), List.of(), new PdfTableWork()).isEmpty());
        assertTrue(
                detector.detect(runs("$10", "(20)"), List.of(new PdfPageGraphics.Segment(30, 115, 300, 115)), List.of(),
                                new PdfTableWork())
                        .isEmpty());
    }

    /**
     * Existing plain numeric inputs retain identical table content/geometry except the explicit evidence-policy label.
     * @throws Exception if owned source authoring or extraction fails
     */
    @Test
    void ordinaryNumericTablesRetainSourceAndExactOwnershipRestrictions() throws Exception {
        final var source = source("10", "20");
        final var previous =
                processor(PdfOptions.TableDetection.RULED_AND_ALIGNED_TEXT).partition(source).elements().getFirst();
        final var selected = processor(PdfOptions.TableDetection.RULED_AND_FINANCIAL_ALIGNED_TEXT)
                                     .partition(source)
                                     .elements()
                                     .getFirst();
        assertEquals(previous.elementId(), selected.elementId());
        assertEquals(previous.text(), selected.text());
        final var previousMetadata = new java.util.LinkedHashMap<>(previous.metadata());
        final var selectedMetadata = new java.util.LinkedHashMap<>(selected.metadata());
        previousMetadata.remove("pdf_table_evidence");
        selectedMetadata.remove("pdf_table_evidence");
        assertEquals(previousMetadata, selectedMetadata);
        final var options =
                PdfOptions.defaults().withTableDetection(PdfOptions.TableDetection.RULED_AND_FINANCIAL_ALIGNED_TEXT);
        assertThrows(IllegalArgumentException.class,
                ()
                        -> PdfPartitioner.createWithNativeTablesFirst(
                                options, PdfNativeLayoutOptions.defaults(), image -> List.of()));
        assertThrows(IllegalArgumentException.class,
                ()
                        -> PdfPartitioner.createWithTaggedTablesFirst(
                                options, PdfNativeLayoutOptions.defaults(), image -> List.of()));
    }

    /**
     * Constructs complete uniform two-column source-run blocks with explicit independent bounds.
     * @param first first body numeric token
     * @param second second body numeric token
     * @return source runs in page order
     */
    private static List<PdfTextRun> runs(final String first, final String second) {
        return List.of(new PdfTextRun("Item", 40, 93, 80, 100), new PdfTextRun("Value", 220, 93, 280, 100),
                new PdfTextRun("Alpha", 40, 113, 80, 120), new PdfTextRun(first, 220, 113, 280, 120),
                new PdfTextRun("Beta", 40, 133, 80, 140), new PdfTextRun(second, 220, 133, 280, 140));
    }

    /**
     * Selects one explicit native table policy without providers or OCR.
     * @param policy immutable table recognition policy
     * @return shared facade exposing only partitioner interfaces
     */
    private static DocWeave processor(final PdfOptions.TableDetection policy) {
        return DocWeave.create(PartitionerRegistry.of(
                List.of(PdfPartitioner.create(PdfOptions.defaults().withTableDetection(policy)))));
    }

    /**
     * Authors all six independent source strings, aligning values by measured original font advances.
     * @param first first original numeric cell
     * @param second second original numeric cell
     * @return complete owned PDF source
     * @throws Exception if font encoding, source authoring or serialization fails
     */
    private static DocumentSource source(final String first, final String second) throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage();
            document.addPage(page);
            final var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            final var values = List.of("Item", "Value", "Alpha", first, "Beta", second);
            try (final var content = new PDPageContentStream(document, page)) {
                for (int index = 0; index < values.size(); index++) {
                    final String value = values.get(index);
                    content.beginText();
                    content.setFont(font, 10);
                    content.newLineAtOffset(
                            index % 2 == 0 ? 40 : 280 - font.getStringWidth(value) / 100, 700 - index / 2 * 20);
                    content.showText(value);
                    content.endText();
                }
            }
            document.save(output);
            return DocumentSource.bytes(output.toByteArray(), "owned-financial.pdf");
        }
    }
}
