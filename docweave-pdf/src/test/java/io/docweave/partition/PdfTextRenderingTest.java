package io.docweave.partition;

import io.docweave.DocWeave;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PdfTextRenderingTest {
    @Test
    void preservesDefaultAndSelectsSixPaintingModesWithExplicitProvenance() throws Exception {
        final var modes = RenderingMode.values();
        final var source = fixture(modes, false);
        final var all = extract(source, PdfOptions.defaults());
        assertEquals(8, all.elements().size());
        assertTrue(
                all.elements().stream().noneMatch(e -> e.metadata().containsKey("pdf_native_text_rendering_policy")));
        final var painted = extract(source, selected());
        assertEquals(List.of("Mode0", "Mode1", "Mode2", "Mode4", "Mode5", "Mode6"),
                painted.elements().stream().map(e -> e.text()).toList());
        assertTrue(painted.elements().stream().allMatch(
                e -> "painted_modes_v1".equals(e.metadata().get("pdf_native_text_rendering_policy"))));
        for (final var element : painted.elements()) {
            final var original =
                    all.elements().stream().filter(e -> e.text().equals(element.text())).findFirst().orElseThrow();
            assertEquals(original.metadata().get("coordinates"), element.metadata().get("coordinates"));
        }
    }

    @Test
    void invisibleDuplicateCannotSuppressLaterPaintedCopy() throws Exception {
        final var result =
                extract(fixture(new RenderingMode[] {RenderingMode.NEITHER, RenderingMode.FILL}, true), selected());
        assertEquals(List.of("Same"), result.elements().stream().map(e -> e.text()).toList());
    }

    @Test
    void excludedGlyphsStillConsumeTheInputPositionBudget() throws Exception {
        final var d = selected();
        final var limits = new PdfOptions(d.maxPages(), 2, d.maxOperatorsPerPage(), d.maxImagePixels(),
                d.maxImageBytes(), d.includeImageBytes(), d.tableLimits())
                                   .withTextRendering(d.textRendering());
        final var source = fixture(new RenderingMode[] {RenderingMode.NEITHER}, false);
        assertEquals(PartitionException.Code.INPUT_TOO_LARGE,
                assertThrows(PartitionException.class, () -> extract(source, limits)).code());
    }

    @Test
    void explicitFilteringChangesTextlessOcrSelectionWithoutLabelingOcrAsNative() throws Exception {
        final var source = fixture(new RenderingMode[] {RenderingMode.NEITHER}, false);
        final var calls = new AtomicInteger();
        final PdfOcrProvider provider = image -> {
            calls.incrementAndGet();
            return List.of(new PdfOcrProvider.Line("Recognized", 0, 0, 20, 20));
        };
        final var original = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(
                                                     PdfOptions.defaults(), PdfOcrOptions.defaults(), provider))))
                                     .partition(source);
        assertEquals(0, calls.get());
        assertEquals("Mode0", original.elements().getFirst().text());
        final var filtered =
                DocWeave.create(PartitionerRegistry.of(
                                        List.of(PdfPartitioner.create(selected(), PdfOcrOptions.defaults(), provider))))
                        .partition(source);
        assertEquals(1, calls.get());
        assertEquals("Recognized", filtered.elements().getFirst().text());
        assertEquals("ocr", filtered.elements().getFirst().metadata().get("pdf_text_method"));
        assertFalse(filtered.elements().getFirst().metadata().containsKey("pdf_native_text_rendering_policy"));
    }

    @Test
    void paintingModeIsNotMistakenForRenderedVisibility() throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage();
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                final var transparent = new PDExtendedGraphicsState();
                transparent.setNonStrokingAlphaConstant(0f);
                content.setGraphicsStateParameters(transparent);
                content.addRect(0, 0, 1, 1);
                content.clip();
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(40, 700);
                content.showText("Still selected");
                content.endText();
            }
            document.save(output);
            final var result =
                    extract(DocumentSource.bytes(output.toByteArray(), "owned-transparent-clipped.pdf"), selected());
            assertEquals("Still selected", result.elements().getFirst().text());
        }
    }

    @Test
    void oldConstructorAndCopiesRetainTheirIntendedPolicies() {
        final var d = selected()
                              .withTextLayout(PdfOptions.TextLayout.ORIENTED_LINES)
                              .withTableDetection(PdfOptions.TableDetection.RULED_AND_DOUBLE_BORDERS)
                              .withTableCellCoordinates(true)
                              .withTableContinuation(PdfOptions.TableContinuation.REPEATED_HEADER)
                              .withUnicodeNormalization(PdfOptions.UnicodeNormalization.PRESENTATION_FORMS);
        assertEquals(PdfOptions.TextRendering.PAINTED_MODES, d.textRendering());
        final var legacy = new PdfOptions(d.maxPages(), d.maxGlyphsPerPage(), d.maxOperatorsPerPage(),
                d.maxImagePixels(), d.maxImageBytes(), d.includeImageBytes(), d.tableLimits(), d.tableDetection(),
                d.textLayout(), d.includeTableCellCoordinates(), d.tableContinuation(), d.unicodeNormalization());
        assertEquals(PdfOptions.TextRendering.ALL, legacy.textRendering());
        assertThrows(NullPointerException.class, () -> d.withTextRendering(null));
    }

    /** Creates the explicit policy under test; budgets remain the standard bounded defaults. */
    private static PdfOptions selected() {
        return PdfOptions.defaults().withTextRendering(PdfOptions.TextRendering.PAINTED_MODES);
    }

    /** Runs the real native parser via its public strategy and orchestration interfaces. */
    private static PartitionResult extract(final DocumentSource source, final PdfOptions options) throws Exception {
        return DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options)))).partition(source);
    }

    /** Builds an owned PDF with independently specified rendering modes and optional exact overlap. */
    private static DocumentSource fixture(final RenderingMode[] modes, final boolean overlap) throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage();
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                for (int index = 0; index < modes.length; index++) {
                    content.saveGraphicsState();
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.setRenderingMode(modes[index]);
                    content.newLineAtOffset(40, overlap ? 700 : 700 - index * 30);
                    content.showText(overlap ? "Same" : "Mode" + index);
                    content.endText();
                    content.restoreGraphicsState();
                }
            }
            document.save(output);
            return DocumentSource.bytes(output.toByteArray(), "owned-rendering-modes.pdf");
        }
    }
}
