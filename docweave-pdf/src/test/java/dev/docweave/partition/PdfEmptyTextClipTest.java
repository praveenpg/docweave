package dev.docweave.partition;

import dev.docweave.DocWeave;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDFormContentStream;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Owned text-selection and independent rendered controls for empty inherited form clips. */
class PdfEmptyTextClipTest {
    /** Creates independently authored form/text controls. */
    PdfEmptyTextClipTest() {
    }

    /**
     * Empty inherited clips exclude source characters only under the explicit new policy, preserving visible text
     * geometry.
     * @throws Exception if source authoring, exact raster comparison or extraction fails
     */
    @Test
    void emptyFormTextIsFilteredBeforeNativeRetentionOnlyWhenRequested() throws Exception {
        for (final int rotation : List.of(0, 90, 180, 270)) {
            final var ordinary = source("none", rotation, false, true);
            for (final String clip : List.of("zero", "outside", "nested-zero")) {
                final var candidate = source(clip, rotation, false, true);
                sameRaster(ordinary, candidate);
                for (final var policy : List.of(PdfOptions.TextRendering.ALL, PdfOptions.TextRendering.PAINTED_MODES,
                             PdfOptions.TextRendering.NONZERO_ALPHA_PAINTED_MODES)) {
                    assertEquals(List.of("Hidden", "Visible"), text(candidate, policy).stream().sorted().toList());
                }
                assertEquals(List.of("Visible"), text(candidate, selected()));
                final var result = extract(candidate, selected());
                final var expected = extract(ordinary, selected());
                assertEquals(expected.elements(), result.elements());
                assertEquals("nonzero_alpha_nonempty_clip_painted_modes_v1",
                        result.elements().getFirst().metadata().get("pdf_native_text_rendering_policy"));
            }
        }
    }

    /**
     * Filtering precedes duplicate suppression, while nonempty clips do not promise individual glyph visibility.
     * @throws Exception if source authoring or extraction fails
     */
    @Test
    void visibleDuplicateSurvivesAndNonemptyClipRemainsConservative() throws Exception {
        assertEquals(List.of("Same"), text(source("zero", 0, true, true), selected()));
        final var transparent = source("transparent", 0, false, true);
        sameRaster(source("none", 0, false, true), transparent);
        assertEquals(List.of("Visible"), text(transparent, selected()));
        assertEquals(List.of("Hidden", "Visible"), text(source("visible", 0, false, true), selected()));
        final var disjoint = source("nonempty-disjoint", 0, false, true);
        sameRaster(source("none", 0, false, true), disjoint);
        assertEquals(List.of("Hidden", "Visible"), text(disjoint, selected()));
    }

    /**
     * A page containing only empty-clip text becomes OCR-eligible only under the new explicit selection policy.
     * @throws Exception if controlled OCR composition or extraction fails
     */
    @Test
    void emptyClipSelectionCanTriggerCallerSuppliedOcrWithoutNativeProvenance() throws Exception {
        final var calls = new AtomicInteger();
        final PdfOcrProvider provider = image -> {
            calls.incrementAndGet();
            return List.of(new PdfOcrProvider.Line("Recognized", 1, 1, 20, 20));
        };
        final var source = DocumentSource.bytes(source("zero", 0, false, false), "empty-form-text.pdf");
        final var original = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(
                                                     PdfOptions.defaults(), PdfOcrOptions.defaults(), provider))))
                                     .partition(source);
        assertEquals(0, calls.get());
        assertEquals("Hidden", original.elements().getFirst().text());
        final var filtered = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(
                                                     PdfOptions.defaults().withTextRendering(selected()),
                                                     PdfOcrOptions.defaults(), provider))))
                                     .partition(source);
        assertEquals(1, calls.get());
        assertEquals("Recognized", filtered.elements().getFirst().text());
        assertFalse(filtered.elements().getFirst().metadata().containsKey("pdf_native_text_rendering_policy"));
    }

    /**
     * Hidden source positions remain charged before filtering and cannot bypass traversal ceilings.
     * @throws Exception if owned source authoring fails
     */
    @Test
    void excludedEmptyClipGlyphsStillConsumeInputBudget() throws Exception {
        final var d = PdfOptions.defaults();
        final var limits = new PdfOptions(d.maxPages(), 2, d.maxOperatorsPerPage(), d.maxImagePixels(),
                d.maxImageBytes(), d.includeImageBytes(), d.tableLimits())
                                   .withTextRendering(selected());
        final var processor = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(limits))));
        assertEquals(PartitionException.Code.INPUT_TOO_LARGE,
                assertThrows(PartitionException.class,
                        ()
                                -> processor.partition(
                                        DocumentSource.bytes(source("zero", 0, false, false), "empty-form-text.pdf")))
                        .code());
    }

    /**
     * Supplies the explicit inherited-clip native text policy.
     * @return immutable public enum value
     */
    private static PdfOptions.TextRendering selected() {
        return PdfOptions.TextRendering.NONZERO_ALPHA_NONEMPTY_CLIP_PAINTED_MODES;
    }

    /**
     * Extracts complete owned bytes under a single native-only policy.
     * @param bytes original source
     * @param policy explicit native text selection
     * @return complete result
     * @throws Exception if extraction fails
     */
    private static PartitionResult extract(final byte[] bytes, final PdfOptions.TextRendering policy) throws Exception {
        return DocWeave
                .create(PartitionerRegistry.of(
                        List.of(PdfPartitioner.create(PdfOptions.defaults().withTextRendering(policy)))))
                .partition(DocumentSource.bytes(bytes, "empty-form-text.pdf"));
    }

    /**
     * Reads exact element text from a complete owned source.
     * @param bytes original source
     * @param policy explicit native text selection
     * @return all emitted element texts in source-layout order
     * @throws Exception if extraction fails
     */
    private static List<String> text(final byte[] bytes, final PdfOptions.TextRendering policy) throws Exception {
        return extract(bytes, policy).elements().stream().map(e -> e.text()).toList();
    }

    /**
     * Proves an invisible form does not affect any rendered RGB sample, including crop and rotated frames.
     * @param ordinary complete source without hidden form
     * @param candidate complete source with invisible form
     * @throws Exception if rendering or equality fails
     */
    private static void sameRaster(final byte[] ordinary, final byte[] candidate) throws Exception {
        try (final var first = Loader.loadPDF(ordinary); final var second = Loader.loadPDF(candidate)) {
            final var before = new PDFRenderer(first).renderImageWithDPI(0, 72);
            final var after = new PDFRenderer(second).renderImageWithDPI(0, 72);
            try {
                assertEquals(before.getWidth(), after.getWidth());
                assertEquals(before.getHeight(), after.getHeight());
                assertArrayEquals(
                        before.getRGB(0, 0, before.getWidth(), before.getHeight(), null, 0, before.getWidth()),
                        after.getRGB(0, 0, after.getWidth(), after.getHeight(), null, 0, after.getWidth()));
            } finally {
                before.flush();
                after.flush();
            }
        }
    }

    /**
     * Authors a text-bearing form with a fixed inherited clip, optionally nesting it inside an empty parent.
     * @param document authoring document
     * @param clip zero, outside, nested-zero, visible or nonempty-disjoint source form bounds
     * @param duplicate whether hidden text must coincide exactly with a later visible duplicate
     * @return complete source form
     * @throws Exception if serialization or font encoding fails
     */
    private static PDFormXObject form(final PDDocument document, final String clip, final boolean duplicate)
            throws Exception {
        final var form = new PDFormXObject(document);
        form.setResources(new PDResources());
        form.setBBox(clip.equals("zero") || clip.equals("nested-zero") ? new PDRectangle(0, 0)
                     : clip.equals("outside")                          ? new PDRectangle(0, 0, 10, 10)
                     : clip.equals("nonempty-disjoint")                ? new PDRectangle(20, 30, 20, 20)
                                                                       : new PDRectangle(612, 792));
        try (final var content = new PDFormContentStream(form)) {
            if (clip.equals("nested-zero")) {
                content.drawForm(form(document, "visible", duplicate));
            } else {
                if (clip.equals("transparent")) {
                    final var alpha = new org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState();
                    alpha.setNonStrokingAlphaConstant(0f);
                    content.setGraphicsStateParameters(alpha);
                }
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(40, 700);
                content.showText(duplicate ? "Same" : "Hidden");
                content.endText();
            }
        }
        return form;
    }

    /**
     * Authors complete unmodified form text followed by an independent visible outer-page source word.
     * @param clip fixed form bounds, or none
     * @param rotation page quarter turn
     * @param duplicate whether both source copies coincide in text and geometry
     * @param visible whether outer-page visible text is present
     * @return original complete one-page PDF bytes
     * @throws Exception if authoring or serialization fails
     */
    private static byte[] source(final String clip, final int rotation, final boolean duplicate, final boolean visible)
            throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage();
            page.setCropBox(new PDRectangle(20, 30, 550, 730));
            page.setUserUnit(2);
            page.setRotation(rotation);
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                if (!clip.equals("none")) {
                    content.drawForm(form(document, clip, duplicate));
                }
                if (visible) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(40, duplicate ? 700 : 650);
                    content.showText(duplicate ? "Same" : "Visible");
                    content.endText();
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
