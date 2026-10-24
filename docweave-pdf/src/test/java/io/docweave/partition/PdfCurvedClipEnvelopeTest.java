package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.core.Element;
import io.docweave.core.ElementType;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDFormContentStream;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Owned controls for explicit curved-clip envelopes. Expected rectangles are computed by hand from authored
 * path and control points, never from production geometry. The envelope is conservative, not the curve.
 */
class PdfCurvedClipEnvelopeTest {
    /** Cubic circle control-point ratio. */
    private static final float KAPPA = 0.5522848f;

    /** Creates independent curved-clip controls. */
    PdfCurvedClipEnvelopeTest() {
    }

    /**
     * The opt-in affects native rule evidence as well as image metadata in this graphics traversal.
     * @throws Exception if owned source authoring or bounded graphics traversal fails
     */
    @Test
    void curvedEnvelopeRejectsDisjointNativeRulesWithoutImageMetadataSelection() throws Exception {
        final byte[] source = page(100, 60, (document, content) -> {
            circle(content, 85, 45, 5);
            content.clip();
            for (int offset = 5; offset <= 35; offset += 10) {
                content.moveTo(offset, 5);
                content.lineTo(offset, 35);
                content.moveTo(5, offset);
                content.lineTo(35, offset);
            }
            content.stroke();
        });
        try (final var document = Loader.loadPDF(source)) {
            final var page = document.getPage(0);
            final var base = PdfOptions.defaults();
            final var selected = base.withCurvedClipEnvelopes(true);
            final var original = new PdfPageGraphics(page, base, new PdfImageBudget(base), new PdfPageGeometry(page));
            final var candidate =
                    new PdfPageGraphics(page, selected, new PdfImageBudget(selected), new PdfPageGeometry(page));
            original.processPage(page);
            candidate.processPage(page);
            assertEquals(8, original.segments().size());
            assertTrue(candidate.segments().isEmpty());
            assertFalse(selected.includeImageClipBounds());
        }
    }

    /**
     * A circular clip is ignored by default and contributes its control-point rectangle only when selected.
     * @throws Exception if source authoring or public extraction fails
     */
    @Test
    void circularClipContributesControlPointRectangleOnlyWhenSelected() throws Exception {
        final byte[] source = page(100, 60, (document, content) -> {
            circle(content, 50, 30, 10);
            content.clip();
            content.drawImage(raster(document), 40, 20, 20, 20);
        });
        final var ignored = clip(images(source, options(false)).getFirst());
        assertBounds(0, 0, 100, 60, ignored);
        final var selected = clip(images(source, options(true)).getFirst());
        assertEquals("BOUNDED", selected.get("classification"));
        assertBounds(40, 20, 60, 40, selected);
        assertEquals(false, selected.get("excludes_image_placement"));
    }

    /**
     * An image wholly outside a curved clip's control-point rectangle is proven excluded only when selected.
     * @throws Exception if source authoring or public extraction fails
     */
    @Test
    void disjointCurvedClipProvesExclusionOnlyWhenSelected() throws Exception {
        final byte[] source = page(100, 60, (document, content) -> {
            circle(content, 85, 30, 5);
            content.clip();
            content.drawImage(raster(document), 10, 10, 20, 20);
        });
        assertEquals(false, clip(images(source, options(false)).getFirst()).get("excludes_image_placement"));
        final var selected = clip(images(source, options(true)).getFirst());
        assertBounds(80, 25, 90, 35, selected);
        assertEquals(true, selected.get("excludes_image_placement"));
    }

    /**
     * A bulging curve's control points lie beyond the curve itself: the envelope reaches the control points
     * (displayed top 10), while a PDFBox rendering of a clipped fill leaves a point inside the envelope but
     * outside the true curve unpainted. The envelope is a superset, not the exact clip.
     * @throws Exception if source authoring, rendering or public extraction fails
     */
    @Test
    void envelopeIsAConservativeSupersetNotTheCurve() throws Exception {
        final byte[] source = page(100, 60, (document, content) -> {
            content.moveTo(10, 10);
            content.curveTo(10, 50, 50, 50, 50, 10);
            content.closePath();
            content.clip();
            content.setNonStrokingColor(Color.RED);
            content.addRect(0, 0, 100, 60);
            content.fill();
            content.drawImage(raster(document), 20, 15, 10, 10);
        });
        assertBounds(10, 10, 50, 50, clip(images(source, options(true)).getFirst()));
        try (final var original = Loader.loadPDF(source)) {
            final var rendered = new PDFRenderer(original).renderImage(0);
            try {
                // True curve peaks at y = 40 (displayed 20); y = 45 (displayed 15) is inside the envelope only.
                assertEquals(Color.WHITE.getRGB(), rendered.getRGB(30, 15));
                assertEquals(Color.RED.getRGB(), rendered.getRGB(30, 35));
            } finally {
                rendered.flush();
            }
        }
    }

    /**
     * Restoring the graphics state discards the curved envelope, and an ordinary form inherits it.
     * @throws Exception if source authoring or public extraction fails
     */
    @Test
    void restoreAndFormScopesFollowGraphicsState() throws Exception {
        final byte[] source;
        try (final var document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(100, 60));
            document.addPage(page);
            final var image = raster(document);
            final var form = new PDFormXObject(document);
            form.setResources(new PDResources());
            form.setBBox(new PDRectangle(100, 60));
            try (final var content = new PDFormContentStream(form)) {
                content.drawImage(image, 45, 25, 5, 5);
            }
            try (final var content = new PDPageContentStream(document, page)) {
                content.saveGraphicsState();
                circle(content, 50, 30, 10);
                content.clip();
                content.drawForm(form);
                content.restoreGraphicsState();
                content.drawImage(image, 80, 25, 5, 5);
            }
            source = save(document);
        }
        final var actual = images(source, options(true));
        assertEquals(2, actual.size());
        assertBounds(40, 20, 60, 40, clip(actual.get(0)));
        assertBounds(0, 0, 100, 60, clip(actual.get(1)));
    }

    /**
     * Transfer control: nonzero crop origin and a quarter-turn rotation transform the curved envelope exactly
     * like other clip envelopes (crop (10,20,200,140), rotation 90: displayed point = (y - 20, x - 10)).
     * @throws Exception if source authoring or public extraction fails
     */
    @Test
    void cropAndRotationTransformTheCurvedEnvelope() throws Exception {
        final byte[] source;
        try (final var document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(300, 300));
            page.setCropBox(new PDRectangle(10, 20, 200, 140));
            page.setRotation(90);
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                circle(content, 60, 80, 10);
                content.clip();
                content.drawImage(raster(document), 55, 75, 10, 10);
            }
            source = save(document);
        }
        assertBounds(50, 40, 70, 60, clip(images(source, options(true)).getFirst()));
    }

    /**
     * Straight-only documents produce byte-identical elements with and without the option, and copies preserve it.
     * @throws Exception if source authoring or public extraction fails
     */
    @Test
    void straightClipsAndOptionCopiesAreUnaffected() throws Exception {
        final byte[] source = page(100, 60, (document, content) -> {
            content.addRect(20, 0, 20, 60);
            content.clip();
            content.drawImage(raster(document), 10, 10, 20, 20);
        });
        assertEquals(images(source, options(false)), images(source, options(true)));
        final var base = new PdfOptions(10, 10_000, 10_000, 100, 10_000, false, TableLimits.defaults());
        assertFalse(base.curvedClipEnvelopes());
        assertFalse(PdfOptions.defaults().curvedClipEnvelopes());
        assertFalse(base.withImageGroupContext(true).withImageClipBounds(true).curvedClipEnvelopes());
        final var selected = base.withCurvedClipEnvelopes(true);
        assertTrue(selected.withImageGroupContext(true)
                        .withTextLayout(PdfOptions.TextLayout.PARAGRAPHS)
                        .curvedClipEnvelopes());
        assertFalse(selected.includeImageClipBounds());
        assertFalse(selected.withCurvedClipEnvelopes(false).curvedClipEnvelopes());
    }

    /**
     * Appends a four-segment cubic circle approximation whose control points stay inside its bounding square.
     * @param content target stream
     * @param x center horizontal position
     * @param y center vertical position
     * @param r radius
     * @throws Exception if writing fails
     */
    private static void circle(final PDPageContentStream content, final float x, final float y, final float r)
            throws Exception {
        final float k = KAPPA * r;
        content.moveTo(x + r, y);
        content.curveTo(x + r, y + k, x + k, y + r, x, y + r);
        content.curveTo(x - k, y + r, x - r, y + k, x - r, y);
        content.curveTo(x - r, y - k, x - k, y - r, x, y - r);
        content.curveTo(x + k, y - r, x + r, y - k, x + r, y);
        content.closePath();
    }

    /**
     * Builds small-budget options with clip bounds and an explicit curved-clip selection.
     * @param curved whether curved clips contribute envelopes
     * @return immutable options
     */
    private static PdfOptions options(final boolean curved) {
        return new PdfOptions(10, 10_000, 10_000, 100, 10_000, false, TableLimits.defaults())
                .withImageClipBounds(true)
                .withCurvedClipEnvelopes(curved);
    }

    /** Authors page content against its owning document. */
    private interface Author {
        /**
         * Writes owned content.
         * @param document owning document
         * @param content open page stream
         * @throws Exception if writing fails
         */
        void write(PDDocument document, PDPageContentStream content) throws Exception;
    }

    /**
     * Authors one page and returns complete bytes.
     * @param width page width
     * @param height page height
     * @param author content callback
     * @return owned PDF bytes
     * @throws Exception if authoring fails
     */
    private static byte[] page(final float width, final float height, final Author author) throws Exception {
        try (final var document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(width, height));
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                author.write(document, content);
            }
            return save(document);
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
     * Creates an opaque two-pixel raster.
     * @param document owning document
     * @return embedded image
     * @throws Exception if encoding fails
     */
    private static PDImageXObject raster(final PDDocument document) throws Exception {
        final var pixels = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
        pixels.setRGB(0, 0, Color.BLUE.getRGB());
        pixels.setRGB(1, 0, Color.GREEN.getRGB());
        try {
            return LosslessFactory.createFromImage(document, pixels);
        } finally {
            pixels.flush();
        }
    }

    /**
     * Runs the public extraction path.
     * @param source complete bytes
     * @param options extraction policy
     * @return image occurrences
     * @throws Exception if partitioning fails
     */
    private static List<Element> images(final byte[] source, final PdfOptions options) throws Exception {
        return DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options))))
                .partition(DocumentSource.bytes(source, "owned-curved-clip.pdf"))
                .elements()
                .stream()
                .filter(element -> element.type() == ElementType.Image)
                .toList();
    }

    /**
     * Reads the clip-bounds map.
     * @param image emitted image
     * @return classification map
     */
    private static Map<?, ?> clip(final Element image) {
        return (Map<?, ?>) image.metadata().get("pdf_image_clip_bounds");
    }

    /**
     * Asserts a BOUNDED envelope's displayed rectangle within outward-rounding tolerance.
     * @param left expected left
     * @param top expected top
     * @param right expected right
     * @param bottom expected bottom
     * @param clip classification map
     */
    private static void assertBounds(
            final double left, final double top, final double right, final double bottom, final Map<?, ?> clip) {
        assertEquals("BOUNDED", clip.get("classification"));
        final var points = (List<?>) ((Map<?, ?>) clip.get("bounds")).get("points");
        final double delta = 1e-3;
        assertEquals(left, ((Number) ((List<?>) points.get(0)).get(0)).doubleValue(), delta);
        assertEquals(top, ((Number) ((List<?>) points.get(0)).get(1)).doubleValue(), delta);
        assertEquals(right, ((Number) ((List<?>) points.get(2)).get(0)).doubleValue(), delta);
        assertEquals(bottom, ((Number) ((List<?>) points.get(2)).get(1)).doubleValue(), delta);
    }
}
