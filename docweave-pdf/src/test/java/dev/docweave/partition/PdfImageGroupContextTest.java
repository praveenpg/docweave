package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDFormContentStream;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.blend.BlendMode;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Owned controls for opt-in enclosing painted transparency-group context. Expected entries are written
 * independently from the authored source; the current-stream state map and PNG samples stay unchanged.
 */
class PdfImageGroupContextTest {
    /** Creates independent group-context controls. */
    PdfImageGroupContextTest() {
    }

    /**
     * Reproduces the existing form/group/direct sequence: internal maps are unchanged and only the group's
     * image gains one enclosing entry describing the caller's .5/Multiply state.
     * @throws Exception if source authoring or public extraction fails
     */
    @Test
    void groupImageGainsEnclosingCallerStateWhileInternalStateStaysReset() throws Exception {
        final byte[] source;
        try (final var document = new PDDocument()) {
            final var page = page(document);
            final var image = raster(document);
            final var form = new PDFormXObject(document);
            form.setResources(new PDResources());
            form.setBBox(new PDRectangle(200, 100));
            try (final var content = new PDFormContentStream(form)) {
                content.drawImage(image, 0, 40, 5, 20);
            }
            final var group = group(document, image, null, null);
            try (final var content = new PDPageContentStream(document, page)) {
                content.setGraphicsStateParameters(state(.5f, BlendMode.MULTIPLY));
                content.drawForm(form);
                content.drawForm(group);
                content.drawImage(image, 90, 40, 20, 20);
            }
            source = save(document);
        }
        final var selected = images(source, options(true));
        final var historical = images(source, options(false));
        assertEquals(3, selected.size());
        assertEquals(3, historical.size());
        assertEquals(List.of(paint(.5, "Multiply"), paint(1, "Normal"), paint(.5, "Multiply")),
                selected.stream().map(e -> e.metadata().get("pdf_image_graphics_state")).toList());
        assertEquals(List.of(List.of(), List.of(entry(.5, "Multiply", false, false, false)), List.of()),
                selected.stream().map(PdfImageGroupContextTest::context).toList());
        for (int index = 0; index < 3; index++) {
            assertFalse(historical.get(index).metadata().containsKey("pdf_image_group_context"));
            final var withoutContext = new java.util.LinkedHashMap<>(selected.get(index).metadata());
            withoutContext.remove("pdf_image_group_context");
            assertEquals(historical.get(index).metadata(), withoutContext);
        }
    }

    /**
     * Nested groups are listed outermost first; each entry is the caller state at its own Do operator.
     * An image painted in the outer group after the inner group returns to a one-entry context.
     * @throws Exception if source authoring or public extraction fails
     */
    @Test
    void nestedGroupsAreOrderedOutermostFirstWithOwnInvocationState() throws Exception {
        final byte[] source;
        try (final var document = new PDDocument()) {
            final var page = page(document);
            final var image = raster(document);
            final var inner = group(document, image, true, true);
            final var outer = new PDTransparencyGroup(document);
            attributes(outer, null, null);
            outer.setResources(new PDResources());
            outer.setBBox(new PDRectangle(200, 100));
            try (final var content = new PDFormContentStream(outer)) {
                content.setGraphicsStateParameters(state(.25f, BlendMode.SCREEN));
                content.drawForm(inner);
                content.drawImage(image, 50, 40, 20, 20);
            }
            try (final var content = new PDPageContentStream(document, page)) {
                content.setGraphicsStateParameters(state(.5f, BlendMode.MULTIPLY));
                content.drawForm(outer);
            }
            source = save(document);
        }
        final var actual = images(source, options(true));
        assertEquals(2, actual.size());
        assertEquals(paint(1, "Normal"), actual.get(0).metadata().get("pdf_image_graphics_state"));
        assertEquals(List.of(entry(.5, "Multiply", false, false, false), entry(.25, "Screen", false, true, true)),
                context(actual.get(0)));
        assertEquals(paint(.25, "Screen"), actual.get(1).metadata().get("pdf_image_graphics_state"));
        assertEquals(List.of(entry(.5, "Multiply", false, false, false)), context(actual.get(1)));
    }

    /**
     * One group invoked twice under different outer states yields per-occurrence immutable contexts that
     * never leak into a later direct image.
     * @throws Exception if source authoring or public extraction fails
     */
    @Test
    void reusedGroupContextsArePerOccurrenceImmutableAndDoNotLeak() throws Exception {
        final byte[] source;
        try (final var document = new PDDocument()) {
            final var page = page(document);
            final var image = raster(document);
            final var group = group(document, image, null, null);
            try (final var content = new PDPageContentStream(document, page)) {
                content.saveGraphicsState();
                content.setGraphicsStateParameters(state(.5f, BlendMode.MULTIPLY));
                content.drawForm(group);
                content.restoreGraphicsState();
                content.saveGraphicsState();
                content.setGraphicsStateParameters(state(0, BlendMode.NORMAL));
                content.drawForm(group);
                content.restoreGraphicsState();
                content.drawImage(image, 90, 40, 20, 20);
            }
            source = save(document);
        }
        final var actual = images(source, options(true));
        assertEquals(3, actual.size());
        assertEquals(List.of(entry(.5, "Multiply", false, false, false)), context(actual.get(0)));
        assertEquals(List.of(entry(0, "Normal", false, false, false)), context(actual.get(1)));
        assertEquals(List.of(), context(actual.get(2)));
        assertThrows(UnsupportedOperationException.class, () -> context(actual.get(0)).clear());
        @SuppressWarnings("unchecked") final var first = (Map<String, Object>) context(actual.get(0)).getFirst();
        assertThrows(UnsupportedOperationException.class, () -> first.put("nonstroking_alpha", 1.0));
    }

    /**
     * Ordinary forms never add an entry: a group inside a form and a form inside a group each yield exactly
     * one entry, and the page state after both is restored for a later direct image.
     * @throws Exception if source authoring or public extraction fails
     */
    @Test
    void onlyActualGroupBoundariesContributeAcrossFormNesting() throws Exception {
        final byte[] source;
        try (final var document = new PDDocument()) {
            final var page = page(document);
            final var image = raster(document);
            final var innerGroup = group(document, image, null, null);
            final var outerForm = new PDFormXObject(document);
            outerForm.setResources(new PDResources());
            outerForm.setBBox(new PDRectangle(200, 100));
            try (final var content = new PDFormContentStream(outerForm)) {
                content.setGraphicsStateParameters(state(.75f, BlendMode.DARKEN));
                content.drawForm(innerGroup);
            }
            final var innerForm = new PDFormXObject(document);
            innerForm.setResources(new PDResources());
            innerForm.setBBox(new PDRectangle(200, 100));
            try (final var content = new PDFormContentStream(innerForm)) {
                content.drawImage(image, 50, 40, 20, 20);
            }
            final var outerGroup = new PDTransparencyGroup(document);
            attributes(outerGroup, true, null);
            outerGroup.setResources(new PDResources());
            outerGroup.setBBox(new PDRectangle(200, 100));
            try (final var content = new PDFormContentStream(outerGroup)) {
                content.drawForm(innerForm);
            }
            try (final var content = new PDPageContentStream(document, page)) {
                content.setGraphicsStateParameters(state(.5f, BlendMode.MULTIPLY));
                content.saveGraphicsState();
                content.drawForm(outerForm);
                content.restoreGraphicsState();
                content.drawForm(outerGroup);
                content.drawImage(image, 90, 40, 20, 20);
            }
            source = save(document);
        }
        final var actual = images(source, options(true));
        assertEquals(3, actual.size());
        assertEquals(List.of(entry(.75, "Darken", false, false, false)), context(actual.get(0)));
        assertEquals(List.of(entry(.5, "Multiply", false, true, false)), context(actual.get(1)));
        assertEquals(paint(1, "Normal"), actual.get(1).metadata().get("pdf_image_graphics_state"));
        assertEquals(List.of(), context(actual.get(2)));
        assertEquals(paint(.5, "Multiply"), actual.get(2).metadata().get("pdf_image_graphics_state"));
    }

    /**
     * Controlled diagnostic only: an opaque raster in an isolated-default, non-knockout group painted at alpha 0
     * leaves the white page pixel unchanged in a PDFBox rendering, while alpha .5 changes it. Both source
     * occurrences and identical PNG payloads are retained; no general visibility claim follows from this.
     * @throws Exception if source authoring, rendering or public extraction fails
     */
    @Test
    void zeroAndFractionalGroupAlphaRetainOccurrencesAndPayloads() throws Exception {
        final byte[] source;
        try (final var document = new PDDocument()) {
            final var page = page(document);
            final var image = raster(document);
            final var left = group(document, image, null, null);
            final var right = new PDTransparencyGroup(document);
            attributes(right, null, null);
            right.setResources(new PDResources());
            right.setBBox(new PDRectangle(200, 100));
            try (final var content = new PDFormContentStream(right)) {
                content.drawImage(image, 120, 40, 20, 20);
            }
            try (final var content = new PDPageContentStream(document, page)) {
                content.saveGraphicsState();
                content.setGraphicsStateParameters(state(0, BlendMode.NORMAL));
                content.drawForm(left);
                content.restoreGraphicsState();
                content.setGraphicsStateParameters(state(.5f, BlendMode.NORMAL));
                content.drawForm(right);
            }
            source = save(document);
        }
        final var actual = images(source, options(true).withImageClipBounds(false));
        final var payloads =
                images(source, new PdfOptions(10, 10_000, 10_000, 100, 10_000, true, TableLimits.defaults())
                                       .withImageGroupContext(true));
        assertEquals(2, actual.size());
        assertEquals(2, payloads.size());
        assertEquals(List.of(entry(0, "Normal", false, false, false)), context(actual.get(0)));
        assertEquals(List.of(entry(.5, "Normal", false, false, false)), context(actual.get(1)));
        assertEquals(payloads.get(0).metadata().get("image_base64"), payloads.get(1).metadata().get("image_base64"));
        try (final var original = Loader.loadPDF(source)) {
            final var rendered = new PDFRenderer(original).renderImage(0);
            try {
                assertEquals(Color.WHITE.getRGB(), rendered.getRGB(20, 50));
                assertNotEquals(Color.WHITE.getRGB(), rendered.getRGB(130, 50));
                assertNotEquals(Color.RED.getRGB(), rendered.getRGB(130, 50));
            } finally {
                rendered.flush();
            }
        }
    }

    /**
     * A soft mask at the group's invocation is reported as a source descriptor; the auxiliary mask group's own
     * raster is not traversed or emitted as an additional page occurrence.
     * @throws Exception if source authoring or public extraction fails
     */
    @Test
    void invocationSoftMaskIsADescriptorAndMaskRastersAreNotEmitted() throws Exception {
        final byte[] source;
        try (final var document = new PDDocument()) {
            final var page = page(document);
            final var image = raster(document);
            final var mask = new PDTransparencyGroup(document);
            attributes(mask, null, null);
            mask.setResources(new PDResources());
            mask.setBBox(new PDRectangle(200, 100));
            try (final var content = new PDFormContentStream(mask)) {
                content.drawImage(image, 0, 0, 200, 100);
            }
            final var softMask = new COSDictionary();
            softMask.setItem(COSName.S, COSName.LUMINOSITY);
            softMask.setItem(COSName.G, mask);
            final var masked = state(1, BlendMode.NORMAL);
            masked.getCOSObject().setItem(COSName.SMASK, softMask);
            masked.setAlphaSourceFlag(true);
            final var group = group(document, image, null, null);
            try (final var content = new PDPageContentStream(document, page)) {
                content.setGraphicsStateParameters(masked);
                content.drawForm(group);
            }
            source = save(document);
        }
        final var actual = images(source, options(true));
        assertEquals(1, actual.size());
        assertEquals(List.of(entry(1, "Normal", true, false, false, true)), context(actual.getFirst()));
        // Group entry resets alpha, blend mode and soft mask only; the alpha-source flag is inherited.
        assertEquals(Map.of("nonstroking_alpha", 1.0, "blend_mode", "Normal", "has_soft_mask", false, "alpha_is_shape",
                             true),
                actual.getFirst().metadata().get("pdf_image_graphics_state"));
    }

    /**
     * Self-recursive groups hit the existing typed form-depth limit, and a tight operator budget fails typed;
     * an independent later extraction from the same options remains usable with empty contexts.
     * @throws Exception if source authoring or an unexpected failure occurs
     */
    @Test
    void typedFailuresInNestedGroupsLeaveLaterExtractionUsable() throws Exception {
        final byte[] recursive;
        try (final var document = new PDDocument()) {
            final var page = page(document);
            final var group = new PDTransparencyGroup(document);
            attributes(group, null, null);
            group.setResources(new PDResources());
            group.setBBox(new PDRectangle(200, 100));
            try (final var content = new PDFormContentStream(group)) {
                content.drawForm(group);
            }
            try (final var content = new PDPageContentStream(document, page)) {
                content.drawForm(group);
            }
            recursive = save(document);
        }
        final var options = options(true);
        final var depth = assertThrows(PartitionException.class, () -> images(recursive, options));
        assertEquals(PartitionException.Code.INPUT_TOO_LARGE, depth.code());
        final byte[] busy;
        try (final var document = new PDDocument()) {
            final var page = page(document);
            final var image = raster(document);
            final var group = group(document, image, null, null);
            try (final var content = new PDPageContentStream(document, page)) {
                for (int index = 0; index < 20; index++) {
                    content.drawForm(group);
                }
            }
            busy = save(document);
        }
        final var tight =
                new PdfOptions(10, 10_000, 10, 100, 10_000, false, TableLimits.defaults()).withImageGroupContext(true);
        final var budget = assertThrows(PartitionException.class, () -> images(busy, tight));
        assertEquals(PartitionException.Code.INPUT_TOO_LARGE, budget.code());
        final byte[] plain;
        try (final var document = new PDDocument()) {
            final var page = page(document);
            try (final var content = new PDPageContentStream(document, page)) {
                content.drawImage(raster(document), 10, 10, 20, 20);
            }
            plain = save(document);
        }
        assertEquals(List.of(), context(images(plain, options).getFirst()));
    }

    /**
     * Defaults and every retained constructor leave the option off; copy methods preserve it independently.
     */
    @Test
    void defaultsConstructorsAndCopiesPreserveTheSelection() {
        final var base = new PdfOptions(10, 10_000, 10_000, 100, 10_000, false, TableLimits.defaults());
        assertFalse(PdfOptions.defaults().includeImageGroupContext());
        assertFalse(base.includeImageGroupContext());
        assertFalse(new PdfOptions(10, 10_000, 10_000, 100, 10_000, false, TableLimits.defaults(),
                PdfOptions.TableDetection.RULED_ONLY, PdfOptions.TextLayout.LINES, false,
                PdfOptions.TableContinuation.NONE, PdfOptions.UnicodeNormalization.RAW, PdfOptions.TextRendering.ALL,
                PdfOptions.FigureCaptions.NONE, true)
                        .includeImageGroupContext());
        final var selected = base.withImageGroupContext(true);
        assertTrue(selected.withTextLayout(PdfOptions.TextLayout.PARAGRAPHS)
                        .withImageClipBounds(true)
                        .withFigureCaptions(PdfOptions.FigureCaptions.NONE)
                        .includeImageGroupContext());
        assertTrue(selected.withImageClipBounds(true).includeImageClipBounds());
        assertFalse(selected.includeImageClipBounds());
        assertFalse(selected.withImageGroupContext(false).includeImageGroupContext());
        for (final var copy : List.of(selected.withTableDetection(selected.tableDetection()),
                     selected.withTextLayout(selected.textLayout()),
                     selected.withTableCellCoordinates(selected.includeTableCellCoordinates()),
                     selected.withTableContinuation(selected.tableContinuation()),
                     selected.withUnicodeNormalization(selected.unicodeNormalization()),
                     selected.withTextRendering(selected.textRendering()),
                     selected.withFigureCaptions(selected.figureCaptions()),
                     selected.withImageClipBounds(selected.includeImageClipBounds()))) {
            assertEquals(selected, copy);
        }
    }

    /**
     * Builds expected current-stream state independently of production helpers.
     * @param alpha nonstroking constant
     * @param blend blend-mode name
     * @return expected map
     */
    private static Map<String, Object> paint(final double alpha, final String blend) {
        return Map.of("nonstroking_alpha", alpha, "blend_mode", blend, "has_soft_mask", false, "alpha_is_shape", false);
    }

    /**
     * Builds one expected group invocation entry without an alpha-source flag.
     * @param alpha caller nonstroking constant
     * @param blend caller blend-mode name
     * @param softMask whether a caller soft mask is active
     * @param isolated group isolated attribute
     * @param knockout group knockout attribute
     * @return expected entry
     */
    private static Map<String, Object> entry(final double alpha, final String blend, final boolean softMask,
            final boolean isolated, final boolean knockout) {
        return entry(alpha, blend, softMask, isolated, knockout, false);
    }

    /**
     * Builds one expected group invocation entry.
     * @param alpha caller nonstroking constant
     * @param blend caller blend-mode name
     * @param softMask whether a caller soft mask is active
     * @param isolated group isolated attribute
     * @param knockout group knockout attribute
     * @param shape caller alpha-source flag
     * @return expected entry
     */
    private static Map<String, Object> entry(final double alpha, final String blend, final boolean softMask,
            final boolean isolated, final boolean knockout, final boolean shape) {
        return Map.of("nonstroking_alpha", alpha, "blend_mode", blend, "has_soft_mask", softMask, "alpha_is_shape",
                shape, "isolated", isolated, "knockout", knockout);
    }

    /**
     * Creates a 200x100 page.
     * @param document owning document
     * @return added page
     */
    private static PDPage page(final PDDocument document) {
        final var page = new PDPage(new PDRectangle(200, 100));
        document.addPage(page);
        return page;
    }

    /**
     * Authors a transparency group drawing one image at (10,40) with optional explicit I/K attributes.
     * @param document owning document
     * @param image reused raster
     * @param isolated explicit isolated value, or null to omit
     * @param knockout explicit knockout value, or null to omit
     * @return group XObject
     * @throws Exception if authoring fails
     */
    private static PDTransparencyGroup group(final PDDocument document, final PDImageXObject image,
            final Boolean isolated, final Boolean knockout) throws Exception {
        final var group = new PDTransparencyGroup(document);
        attributes(group, isolated, knockout);
        group.setResources(new PDResources());
        group.setBBox(new PDRectangle(200, 100));
        try (final var content = new PDFormContentStream(group)) {
            content.drawImage(image, 10, 40, 20, 20);
        }
        return group;
    }

    /**
     * Writes a transparency /Group dictionary with optional explicit I/K booleans.
     * @param group target group
     * @param isolated explicit isolated value, or null to omit
     * @param knockout explicit knockout value, or null to omit
     */
    private static void attributes(final PDTransparencyGroup group, final Boolean isolated, final Boolean knockout) {
        final var attributes = new COSDictionary();
        attributes.setItem(COSName.S, COSName.TRANSPARENCY);
        if (isolated != null) {
            attributes.setBoolean(COSName.I, isolated);
        }
        if (knockout != null) {
            attributes.setBoolean(COSName.K, knockout);
        }
        group.getCOSObject().setItem(COSName.GROUP, attributes);
    }

    /**
     * Builds a graphics state with explicit nonstroking alpha and blend mode.
     * @param alpha nonstroking constant
     * @param blend blend mode
     * @return owned state
     */
    private static PDExtendedGraphicsState state(final float alpha, final BlendMode blend) {
        final var result = new PDExtendedGraphicsState();
        result.setNonStrokingAlphaConstant(alpha);
        result.setBlendMode(blend);
        return result;
    }

    /**
     * Creates an opaque red 2x2 raster.
     * @param document owning document
     * @return embedded image
     * @throws Exception if encoding fails
     */
    private static PDImageXObject raster(final PDDocument document) throws Exception {
        final var pixels = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) {
                pixels.setRGB(x, y, Color.RED.getRGB());
            }
        }
        try {
            return LosslessFactory.createFromImage(document, pixels);
        } finally {
            pixels.flush();
        }
    }

    /**
     * Saves an owned document.
     * @param document authored document
     * @return complete bytes
     * @throws Exception if encoding fails
     */
    private static byte[] save(final PDDocument document) throws Exception {
        final var output = new ByteArrayOutputStream();
        document.save(output);
        return output.toByteArray();
    }

    /**
     * Builds small-budget options with explicit group-context selection.
     * @param include whether to emit group context
     * @return immutable options
     */
    private static PdfOptions options(final boolean include) {
        return new PdfOptions(10, 10_000, 10_000, 100, 10_000, false, TableLimits.defaults())
                .withImageGroupContext(include);
    }

    /**
     * Runs the public extraction path.
     * @param source complete bytes
     * @param options extraction policy
     * @return image occurrences in painted order
     * @throws Exception if partitioning fails
     */
    private static List<Element> images(final byte[] source, final PdfOptions options) throws Exception {
        return DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options))))
                .partition(DocumentSource.bytes(source, "owned-image-group-context.pdf"))
                .elements()
                .stream()
                .filter(element -> element.type() == ElementType.Image)
                .toList();
    }

    /**
     * Reads the immutable public group-context list.
     * @param image emitted image
     * @return ordered entries
     */
    private static List<?> context(final Element image) {
        assertInstanceOf(List.class, image.metadata().get("pdf_image_group_context"));
        return (List<?>) image.metadata().get("pdf_image_group_context");
    }
}
