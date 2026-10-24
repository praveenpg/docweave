package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.core.Element;
import io.docweave.core.ElementType;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.util.Matrix;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Public native controls for combining upright column paragraphs and isolated projected angle labels. */
class PdfOrientedColumnTest {
    /** Creates independently authored source controls. */
    PdfOrientedColumnTest() {
    }

    /**
     * The combined mode groups both complete upright columns while preserving one angled line unchanged.
     * @throws Exception if independent source authoring or public extraction fails
     */
    @Test
    void groupsUprightColumnsAndRetainsAngledLinesThroughNativeAndTaggedFactories() throws Exception {
        final byte[] bytes = source(30, 0, true);
        for (final boolean tagged : List.of(false, true)) {
            final var failure = assertThrows(PartitionException.class,
                    () -> extract(bytes, PdfOptions.TextLayout.COLUMN_PARAGRAPHS_COMPOUND_WRAPS, tagged));
            assertEquals(PartitionException.Code.UNSUPPORTED_OPTION, failure.code());
            final var baseline = extract(bytes, PdfOptions.TextLayout.ORIENTED_LINES, tagged);
            final var candidate =
                    extract(bytes, PdfOptions.TextLayout.ORIENTED_COLUMN_PARAGRAPHS_COMPOUND_WRAPS, tagged);
            assertEquals(List.of("Evidence for one-way evidence continues here.",
                                 "Right first line second right line last right line.", "Angled label"),
                    candidate.stream().map(Element::text).toList());
            assertEquals(List.of(1, 2),
                    candidate.subList(0, 2).stream().map(element -> element.metadata().get("pdf_column")).toList());
            assertEquals(1, candidate.getFirst().metadata().get("pdf_native_retained_hyphen_wraps"));
            assertProjectedEqual(baseline.stream()
                                         .filter(element -> element.text().equals("Angled label"))
                                         .findFirst()
                                         .orElseThrow(),
                    candidate.getLast());
        }
    }

    /**
     * Without angled positions, every element and its provenance match the historical compound-column mode.
     * @throws Exception if source authoring or extraction fails
     */
    @Test
    void uprightOnlyDocumentsRetainExactHistoricalColumnOutput() throws Exception {
        final byte[] bytes = source(0, 0, false);
        for (final boolean tagged : List.of(false, true)) {
            assertEquals(extract(bytes, PdfOptions.TextLayout.COLUMN_PARAGRAPHS_COMPOUND_WRAPS, tagged),
                    extract(bytes, PdfOptions.TextLayout.ORIENTED_COLUMN_PARAGRAPHS_COMPOUND_WRAPS, tagged));
        }
    }

    /**
     * Fractional-degree projected items remain barriers rather than being truncated to upright rotation zero.
     * @throws Exception if geometry grouping unexpectedly fails
     */
    @Test
    void fractionalRotationNeverFormsAnOrdinaryParagraphOrColumn() throws Exception {
        final var first = new PdfLayoutItem(ElementType.UncategorizedText, "Angled first",
                new PdfLayoutItem.Bounds(40, 40, 150, 50), Map.of("pdf_text_rotation", 0.25));
        final var second = new PdfLayoutItem(ElementType.UncategorizedText, "Angled second",
                new PdfLayoutItem.Bounds(40, 54, 150, 64), Map.of("pdf_text_rotation", 0.25));
        assertEquals(List.of(first, second),
                new GeometricPdfReadingOrder(PdfOptions.TextLayout.ORIENTED_COLUMN_PARAGRAPHS_COMPOUND_WRAPS)
                        .apply(List.of(first, second)));
    }

    /**
     * Crop, UserUnit and displayed quarter turns preserve projected label content, bounds and source provenance.
     * @throws Exception if source authoring or either explicit strategy fails
     */
    @Test
    void displayedFramesRetainExactProjectedAngleElements() throws Exception {
        for (final int rotation : new int[] {0, 90, 180, 270}) {
            for (final double angle : new double[] {0.25, 30, 315}) {
                final byte[] bytes = source(angle, rotation, true);
                final var baseline = extract(bytes, PdfOptions.TextLayout.ORIENTED_LINES, false);
                final var candidate =
                        extract(bytes, PdfOptions.TextLayout.ORIENTED_COLUMN_PARAGRAPHS_COMPOUND_WRAPS, false);
                assertProjectedEqual(baseline.stream()
                                             .filter(element -> element.text().equals("Angled label"))
                                             .findFirst()
                                             .orElseThrow(),
                        candidate.stream()
                                .filter(element -> element.text().equals("Angled label"))
                                .findFirst()
                                .orElseThrow());
                assertEquals(1, candidate.stream().filter(element -> element.text().equals("Angled label")).count());
            }
        }
    }

    /**
     * The combined selection preserves source glyph budgets, cooperative cancellation and reuse after failure.
     * @throws Exception if owned source authoring or post-failure extraction fails
     */
    @Test
    void originalBudgetsAndCancellationRemainInForce() throws Exception {
        final byte[] bytes = source(30, 0, true);
        final var defaults = PdfOptions.defaults();
        final var limited = new PdfOptions(defaults.maxPages(), 1, defaults.maxOperatorsPerPage(),
                defaults.maxImagePixels(), defaults.maxImageBytes(), false, defaults.tableLimits(),
                defaults.tableDetection(), PdfOptions.TextLayout.ORIENTED_COLUMN_PARAGRAPHS_COMPOUND_WRAPS);
        final var bounded = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(limited))));
        assertEquals(PartitionException.Code.INPUT_TOO_LARGE,
                assertThrows(
                        PartitionException.class, () -> bounded.partition(DocumentSource.bytes(bytes, "owned.pdf")))
                        .code());
        final var options = defaults.withTextLayout(PdfOptions.TextLayout.ORIENTED_COLUMN_PARAGRAPHS_COMPOUND_WRAPS);
        final var reusable = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options))));
        final var expected = reusable.partition(DocumentSource.bytes(bytes, "owned.pdf")).elements();
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.io.InterruptedIOException.class,
                    () -> reusable.partition(DocumentSource.bytes(bytes, "owned.pdf")));
        } finally {
            Thread.interrupted();
        }
        assertEquals(expected, reusable.partition(DocumentSource.bytes(bytes, "owned.pdf")).elements());
    }

    /**
     * Compares source content and complete provenance; ordinal-derived IDs may change when prior lines group.
     * @param expected original projected line
     * @param actual projected line after upright grouping
     */
    private static void assertProjectedEqual(final Element expected, final Element actual) {
        assertEquals(expected.type(), actual.type());
        assertEquals(expected.text(), actual.text());
        assertEquals(expected.metadata(), actual.metadata());
    }

    /**
     * Exercises only public interfaces with identical source names and native/source-tagged table choices.
     * @param bytes complete owned source
     * @param layout explicit native layout
     * @param tagged whether source-tagged tables are enabled
     * @return complete immutable output element sequence
     * @throws Exception if normal bounded extraction fails
     */
    private static List<Element> extract(final byte[] bytes, final PdfOptions.TextLayout layout, final boolean tagged)
            throws Exception {
        final var options = PdfOptions.defaults().withTextLayout(layout);
        final var parser = tagged ? PdfPartitioner.createWithTaggedTables(options) : PdfPartitioner.create(options);
        return DocWeave.create(PartitionerRegistry.of(List.of(parser)))
                .partition(DocumentSource.bytes(bytes, "owned.pdf"))
                .elements();
    }

    /**
     * Authors two unambiguous three-line columns and an optional distant rotated source label.
     * @param angle source label angle in degrees
     * @param rotation displayed source page quarter turn
     * @param angled whether the extra projected label is painted
     * @return complete original PDF bytes
     * @throws Exception if PDF authoring fails
     */
    private static byte[] source(final double angle, final int rotation, final boolean angled) throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(600, 400));
            page.setCropBox(new PDRectangle(10, 20, 580, 380));
            page.setUserUnit(2);
            page.setRotation(rotation);
            document.addPage(page);
            final var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (final var content = new PDPageContentStream(document, page)) {
                final var columns = List.of(List.of("Evidence for one-", "way evidence", "continues here."),
                        List.of("Right first line", "second right line", "last right line."));
                for (int column = 0; column < columns.size(); column++) {
                    for (int row = 0; row < 3; row++) {
                        content.beginText();
                        content.setFont(font, 10);
                        content.newLineAtOffset(40 + column * 290, 320 - row * 12);
                        content.showText(columns.get(column).get(row));
                        content.endText();
                    }
                }
                if (angled) {
                    content.beginText();
                    content.setFont(font, 10);
                    content.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(angle), 70, 80));
                    content.showText("Angled label");
                    content.endText();
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
