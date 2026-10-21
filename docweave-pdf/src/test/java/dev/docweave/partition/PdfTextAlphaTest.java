package dev.docweave.partition;

import dev.docweave.DocWeave;
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

/** Owned controls for explicit alpha-aware native text selection before duplicate suppression and OCR routing. */
class PdfTextAlphaTest {
    /** Creates isolated, engine-free PDF controls. */
    PdfTextAlphaTest() {
    }

    /**
     * Independently authored fill/stroke truth table retains positive fractional opacity and original coordinates.
     * @throws Exception if owned PDF creation or partitioning fails
     */
    @Test
    void selectsPositiveAlphaForEnabledPaintOperationsOnly() throws Exception {
        final DocumentSource source;
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage();
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                for (int mode = 0; mode < 8; mode++) {
                    for (int alpha = 0; alpha < 4; alpha++) {
                        paint(content, "M" + mode + "A" + alpha, RenderingMode.fromInt(mode),
                                (alpha & 1) == 0 ? 0 : .5f, (alpha & 2) == 0 ? 0 : .5f, 750 - (mode * 4 + alpha) * 20);
                    }
                }
            }
            document.save(output);
            source = DocumentSource.bytes(output.toByteArray(), "owned-alpha-modes.pdf");
        }
        final var original = extract(source, PdfOptions.defaults());
        assertEquals(32, original.elements().size());
        assertEquals(
                24, extract(source, PdfOptions.defaults().withTextRendering(PdfOptions.TextRendering.PAINTED_MODES))
                            .elements()
                            .size());
        final var filtered = extract(source, selected());
        assertEquals(List.of("M0A1", "M0A3", "M1A2", "M1A3", "M2A1", "M2A2", "M2A3", "M4A1", "M4A3", "M5A2", "M5A3",
                             "M6A1", "M6A2", "M6A3"),
                filtered.elements().stream().map(element -> element.text()).toList());
        for (final var element : filtered.elements()) {
            assertEquals("nonzero_alpha_painted_modes_v1", element.metadata().get("pdf_native_text_rendering_policy"));
            assertEquals(original.elements()
                                 .stream()
                                 .filter(candidate -> candidate.text().equals(element.text()))
                                 .findFirst()
                                 .orElseThrow()
                                 .metadata()
                                 .get("coordinates"),
                    element.metadata().get("coordinates"));
        }
    }

    /**
     * Transparent earlier duplicates cannot hide opaque source copies; graphic state restoration is retained.
     * @throws Exception if owned PDF creation or extraction fails
     */
    @Test
    void filtersTransparentCopiesBeforeDuplicateSuppressionAndRestoresState() throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage();
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                paint(content, "Same", RenderingMode.FILL, 0, 1, 700);
                paint(content, "Same", RenderingMode.FILL, 1, 0, 700);
                paint(content, "Stroke", RenderingMode.STROKE, 0, 1, 650);
                paint(content, "Tiny", RenderingMode.FILL, .001f, 0, 600);
            }
            document.save(output);
            final var result =
                    extract(DocumentSource.bytes(output.toByteArray(), "owned-alpha-duplicates.pdf"), selected());
            assertEquals(List.of("Same", "Stroke", "Tiny"),
                    result.elements().stream().map(element -> element.text()).toList());
        }
    }

    /**
     * Filtered source positions still consume the original glyph budget and make textless-page OCR eligible.
     * @throws Exception if source generation, real parser routing or provider invocation fails
     */
    @Test
    void preservesBudgetsAndChangesTextlessSelectionWithoutNativeProvenanceOnOcr() throws Exception {
        final DocumentSource source;
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage();
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                paint(content, "Hidden", RenderingMode.FILL, 0, 1, 700);
            }
            document.save(output);
            source = DocumentSource.bytes(output.toByteArray(), "owned-alpha-textless.pdf");
        }
        final var d = selected();
        final var bounded = new PdfOptions(d.maxPages(), 2, d.maxOperatorsPerPage(), d.maxImagePixels(),
                d.maxImageBytes(), d.includeImageBytes(), d.tableLimits())
                                    .withTextRendering(d.textRendering());
        assertEquals(PartitionException.Code.INPUT_TOO_LARGE,
                assertThrows(PartitionException.class, () -> extract(source, bounded)).code());
        final var calls = new AtomicInteger();
        final PdfOcrProvider ocr = image -> {
            calls.incrementAndGet();
            return List.of(new PdfOcrProvider.Line("Recognized", 0, 0, 30, 20));
        };
        final var original = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(
                                                     PdfOptions.defaults(), PdfOcrOptions.defaults(), ocr))))
                                     .partition(source);
        assertEquals("Hidden", original.elements().getFirst().text());
        assertEquals(0, calls.get());
        final var filtered = DocWeave.create(PartitionerRegistry.of(List.of(
                                                     PdfPartitioner.create(selected(), PdfOcrOptions.defaults(), ocr))))
                                     .partition(source);
        assertEquals(1, calls.get());
        assertEquals("Recognized", filtered.elements().getFirst().text());
        assertEquals("ocr", filtered.elements().getFirst().metadata().get("pdf_text_method"));
        assertFalse(filtered.elements().getFirst().metadata().containsKey("pdf_native_text_rendering_policy"));
    }

    /**
     * Positive-alpha clipped text remains selected, explicitly avoiding a claim of rendered visibility.
     * @throws Exception if owned clipping fixture or extraction fails
     */
    @Test
    void doesNotInterpretClippingAsAlphaOrRenderedVisibility() throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage();
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                content.addRect(0, 0, 1, 1);
                content.clip();
                paint(content, "Clipped", RenderingMode.FILL, 1, 0, 700);
            }
            document.save(output);
            assertEquals("Clipped",
                    extract(DocumentSource.bytes(output.toByteArray(), "owned-alpha-clipped.pdf"), selected())
                            .elements()
                            .getFirst()
                            .text());
        }
    }

    /**
     * Writes one independently configured source run inside a saved graphics state.
     * @param content owned page stream
     * @param text original source characters
     * @param mode authored text paint mode
     * @param fill nonstroking alpha
     * @param stroke stroking alpha
     * @param y source baseline in PDF points
     * @throws Exception if stream serialization fails
     */
    private static void paint(final PDPageContentStream content, final String text, final RenderingMode mode,
            final float fill, final float stroke, final float y) throws Exception {
        content.saveGraphicsState();
        final var state = new PDExtendedGraphicsState();
        state.setNonStrokingAlphaConstant(fill);
        state.setStrokingAlphaConstant(stroke);
        content.setGraphicsStateParameters(state);
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
        content.setRenderingMode(mode);
        content.newLineAtOffset(40, y);
        content.showText(text);
        content.endText();
        content.restoreGraphicsState();
    }

    /**
     * Selects only the new explicit graphics-state policy with default budgets.
     * @return immutable extraction options
     */
    private static PdfOptions selected() {
        return PdfOptions.defaults().withTextRendering(PdfOptions.TextRendering.NONZERO_ALPHA_PAINTED_MODES);
    }

    /**
     * Exercises the public partitioner interfaces with the original encoded fixture.
     * @param source owned complete PDF
     * @param options explicit extraction policy
     * @return real partition result
     * @throws Exception if partitioning fails
     */
    private static PartitionResult extract(final DocumentSource source, final PdfOptions options) throws Exception {
        return DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options)))).partition(source);
    }
}
