package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.core.ElementType;
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
import org.apache.pdfbox.util.Matrix;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Independent rendered controls for inherited empty form clips and restored outer-page rule evidence. */
class PdfEmptyFormClipTest {
    /** Creates independent form/paint controls. */
    PdfEmptyFormClipTest() {
    }

    /**
     * Equivalent explicit line rectangles must clip invisible grid/shading exactly like the rectangle operator.
     * @throws Exception if independent authoring, raster equivalence or public extraction fails
     */
    @Test
    void lineConstructedRectangleClipsMatchRectangleOperators() throws Exception {
        for (final int rotation : List.of(0, 90, 180, 270)) {
            for (final String paint : List.of("stroke", "fill", "fillstroke")) {
                for (final String clip : List.of("path-empty", "path-disjoint")) {
                    final var expected = source(clip, paint, rotation, false);
                    final var actual = source(clip + "-lines", paint, rotation, false);
                    assertSameRaster(expected, actual);
                    assertEquals(extract(expected).elements(), extract(actual).elements(), clip + " " + paint);
                }
            }
            if (rotation % 180 == 0) {
                for (final String clip : List.of("path-empty", "path-disjoint")) {
                    final var actual = source(clip + "-lines", "shade", rotation, false);
                    assertSameRaster(source(clip, "shade", rotation, false), actual);
                    assertEquals(0, shadedBands(actual));
                }
            }
        }
    }

    /**
     * A transformed rectangle outside the grid must not supply invisible borders or leak its clip to later paint.
     * @throws Exception if owned source authoring, raster comparison or extraction fails
     */
    @Test
    void skewedRectangleClipExcludesDisjointRulesAndRestoresCallerState() throws Exception {
        for (final int rotation : List.of(0, 90, 180, 270)) {
            for (final String paint : List.of("stroke", "fill", "fillstroke")) {
                for (final String clip : List.of("path-disjoint-skewed", "path-disjoint-skewed-evenodd")) {
                    final var invisible = source(clip, paint, rotation, false);
                    assertSameRaster(source("none", paint, rotation, false), invisible);
                    assertTrue(extract(invisible).elements().stream().noneMatch(e -> e.type() == ElementType.Table));
                    assertEquals(extract(source("visible", paint, rotation, false)).elements(),
                            extract(source(clip, paint, rotation, true)).elements());
                }
            }
        }
    }

    /**
     * Four-corner line paths must clip the same hidden grid as the equivalent transformed rectangle operator.
     * @throws Exception if source authoring, raster comparison or public extraction fails
     */
    @Test
    void lineBuiltSkewedClipsMatchRectangleOperators() throws Exception {
        for (final int rotation : List.of(0, 90, 180, 270)) {
            for (final String paint : List.of("stroke", "fill", "fillstroke")) {
                for (final String suffix : List.of("", "-evenodd")) {
                    final var rectangle = source("path-disjoint-skewed" + suffix, paint, rotation, false);
                    final var lines = source("path-disjoint-skewed-lines" + suffix, paint, rotation, false);
                    assertSameRaster(rectangle, lines);
                    assertEquals(extract(rectangle).elements(), extract(lines).elements());
                    assertEquals(extract(source("visible", paint, rotation, false)).elements(),
                            extract(source("path-disjoint-skewed-lines" + suffix, paint, rotation, true)).elements());
                }
            }
        }
    }

    /**
     * Duplicate rectangle subpaths cancel under even-odd clipping but retain visible paint under nonzero winding.
     * @throws Exception if owned authoring, exact raster comparison or extraction fails
     */
    @Test
    void twoRectangleClipsHonorWindingAndRestoreVisiblePaint() throws Exception {
        for (final int rotation : List.of(0, 90, 180, 270)) {
            for (final String paint : List.of("stroke", "fill", "fillstroke")) {
                final var hidden = source("path-visible-duplicate-evenodd", paint, rotation, false);
                assertSameRaster(source("none", paint, rotation, false), hidden);
                assertTrue(extract(hidden).elements().stream().noneMatch(e -> e.type() == ElementType.Table));
                final var visible = source("visible", paint, rotation, false);
                final var triple = source("path-visible-triple-evenodd", paint, rotation, false);
                assertSameRaster(visible, triple);
                assertEquals(extract(visible).elements(), extract(triple).elements());
                assertSameRaster(visible, source("path-visible-duplicate", paint, rotation, false));
                assertEquals(extract(visible).elements(),
                        extract(source("path-visible-duplicate", paint, rotation, false)).elements());
                assertEquals(extract(visible).elements(),
                        extract(source("path-visible-duplicate-evenodd", paint, rotation, true)).elements());
            }
        }
    }

    /**
     * Explicit/implicit closure, even-odd rules and transformed positive clips preserve the original table.
     * @throws Exception if independent raster comparison or extraction fails
     */
    @Test
    void lineRectangleClosureTransformsAndRestoredPaintRemainSupported() throws Exception {
        for (final int rotation : List.of(0, 90, 180, 270)) {
            for (final String paint : List.of("stroke", "fill", "fillstroke")) {
                final var expected = source("visible", paint, rotation, false);
                for (final String clip : List.of("path-visible-lines", "path-visible-lines-open",
                             "path-visible-lines-return", "path-visible-lines-evenodd", "path-visible-lines-mirrored",
                             "path-visible-lines-scaled")) {
                    final var actual = source(clip, paint, rotation, false);
                    assertSameRaster(expected, actual);
                    assertEquals(extract(expected).elements(), extract(actual).elements(), clip);
                }
                assertEquals(extract(expected).elements(),
                        extract(source("path-empty-lines", paint, rotation, true)).elements());
            }
        }
    }

    /**
     * Invisible form paint must not claim page text; form clipping never leaks into subsequent visible rules.
     * @throws Exception if owned source authoring, rendering or extraction fails
     */
    @Test
    void emptyInheritedClipsCannotSupplyTableRules() throws Exception {
        for (final int rotation : List.of(0, 90, 180, 270)) {
            final var ordinary = source("none", "stroke", rotation, false);
            if (rotation % 180 == 0) {
                assertEquals(3, shadedBands(source("visible", "shade", rotation, false)));
                for (final String clip :
                        List.of("empty", "outside", "nested-empty", "disjoint", "path-empty", "path-disjoint")) {
                    final var shaded = source(clip, "shade", rotation, false);
                    assertSameRaster(ordinary, shaded);
                    assertEquals(0, shadedBands(shaded), clip);
                }
            }
            for (final String clip :
                    List.of("empty", "outside", "nested-empty", "disjoint", "path-empty", "path-disjoint")) {
                for (final String paint : List.of("stroke", "fill", "fillstroke")) {
                    final var bytes = source(clip, paint, rotation, false);
                    assertSameRaster(ordinary, bytes);
                    final var result = extract(bytes);
                    assertTrue(result.elements().stream().noneMatch(element -> element.type() == ElementType.Table),
                            clip + " " + paint);
                    final String text =
                            result.elements().stream().map(element -> element.text()).collect(Collectors.joining(" "));
                    for (final String value : List.of("Item", "Qty", "Alpha", "10", "Beta", "20")) {
                        assertTrue(text.contains(value), value);
                    }
                }
            }
        }
    }

    /**
     * Nonempty inherited clips and automatic form-state restoration retain the same original native table.
     * @throws Exception if owned source authoring or extraction fails
     */
    @Test
    void visibleFormsAndRestoredOuterPaintRetainTables() throws Exception {
        for (final int rotation : List.of(0, 90, 180, 270)) {
            final var expected = extract(source("visible", "stroke", rotation, false));
            assertEquals(1, expected.elements().size());
            assertEquals(ElementType.Table, expected.elements().getFirst().type());
            for (final String paint : List.of("stroke", "fill", "fillstroke")) {
                for (final String clip : List.of("visible", "empty", "outside", "nested-empty", "disjoint",
                             "path-empty", "path-disjoint", "path-visible")) {
                    final var result = extract(source(clip, paint, rotation, !clip.equals("visible")));
                    assertEquals(1, result.elements().size(), clip + " " + paint);
                    assertEquals(ElementType.Table, result.elements().getFirst().type());
                    assertEquals(expected.elements().getFirst().text(), result.elements().getFirst().text());
                }
            }
        }
    }

    /**
     * Even-odd, mirrored, scaled and skewed rectangles preserve visible paint; unsupported compound paths stay
     * conservative.
     * @throws Exception if source authoring, exact raster comparison or extraction fails
     */
    @Test
    void rectangularClipTransformsAndUnsupportedPathsPreserveVisibleTables() throws Exception {
        for (final int rotation : List.of(0, 90, 180, 270)) {
            for (final String paint : List.of("stroke", "fill", "fillstroke")) {
                final var ordinary = source("visible", paint, rotation, false);
                final String expected = extract(ordinary).elements().getFirst().text();
                for (final String clip : List.of("path-visible-evenodd", "path-visible-mirrored", "path-visible-scaled",
                             "path-visible-compound", "path-visible-curved", "path-visible-skewed")) {
                    final var candidate = source(clip, paint, rotation, false);
                    assertSameRaster(ordinary, candidate);
                    final var result = extract(candidate);
                    assertEquals(1, result.elements().size(), clip);
                    assertEquals(ElementType.Table, result.elements().getFirst().type(), clip);
                    assertEquals(expected, result.elements().getFirst().text(), clip);
                }
            }
        }
    }

    /**
     * Rectangle clipping applies after its own paint and cannot leak beyond a saved graphics-state scope.
     * @throws Exception if owned authoring or operator traversal fails
     */
    @Test
    void explicitRectangleClipWaitsForPathCompletionAndRestoresState() throws Exception {
        try (final var document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(400, 400));
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                content.saveGraphicsState();
                // W does not consume the path; its intersection becomes effective after this S paint.
                content.appendRawCommands("40 150 0 150 re W S\n");
                content.moveTo(50, 100);
                content.lineTo(200, 100);
                content.stroke();
                content.restoreGraphicsState();
                content.moveTo(50, 120);
                content.lineTo(200, 120);
                content.stroke();
            }
            final var options = PdfOptions.defaults();
            final var graphics =
                    new PdfPageGraphics(page, options, new PdfImageBudget(options), new PdfPageGeometry(page));
            graphics.processPage(page);
            assertEquals(5, graphics.segments().size());
            assertEquals(new PdfPageGraphics.Segment(50, 280, 200, 280), graphics.segments().getLast());
        }
    }

    /**
     * Actual visible stroke width crosses a form edge even when its centerline is outside that edge.
     * Device-dependent zero-width strokes deliberately retain their historical geometry.
     * @throws Exception if independent rendering or source traversal fails
     */
    @Test
    void strokeWidthAndTransformedExtentsAreNotMistakenForDisjointPaint() throws Exception {
        for (final int rotation : List.of(0, 90, 180, 270)) {
            for (final boolean scaled : List.of(false, true)) {
                for (final int width : List.of(0, 4)) {
                    try (final var document = new PDDocument()) {
                        final var page = new PDPage(new PDRectangle(400, 400));
                        page.setRotation(rotation);
                        document.addPage(page);
                        final var form = new PDFormXObject(document);
                        form.setResources(new PDResources());
                        form.setBBox(new PDRectangle(40, 60, 9, 20));
                        if (scaled) {
                            form.setMatrix(java.awt.geom.AffineTransform.getScaleInstance(2, .5));
                        }
                        try (final var content = new PDFormContentStream(form)) {
                            content.setLineWidth(width);
                            content.moveTo(50, 50);
                            content.lineTo(50, 100);
                            content.stroke();
                        }
                        try (final var content = new PDPageContentStream(document, page)) {
                            content.drawForm(form);
                        }
                        final var options = PdfOptions.defaults();
                        final var graphics = new PdfPageGraphics(
                                page, options, new PdfImageBudget(options), new PdfPageGeometry(page));
                        graphics.processPage(page);
                        assertEquals(1, graphics.segments().size(), "Stroke extent or hairline fallback lost");
                        if (width > 0) {
                            final var pixels = new PDFRenderer(document).renderImageWithDPI(0, 72);
                            try {
                                boolean painted = false;
                                for (int y = 0; y < pixels.getHeight() && !painted; y++) {
                                    for (int x = 0; x < pixels.getWidth(); x++) {
                                        painted |= (pixels.getRGB(x, y) & 0xffffff) != 0xffffff;
                                    }
                                }
                                assertTrue(painted, "Independent renderer must paint the stroke inside the clip");
                            } finally {
                                pixels.flush();
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * A fill enclosing the complete clip remains eligible even when no rectangle edge intersects the clip.
     * @throws Exception if owned source traversal fails
     */
    @Test
    void completeFillEnvelopeCanEncloseTheInheritedClip() throws Exception {
        try (final var document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(400, 400));
            document.addPage(page);
            final var form = new PDFormXObject(document);
            form.setResources(new PDResources());
            form.setBBox(new PDRectangle(40, 65, 9, 10));
            try (final var content = new PDFormContentStream(form)) {
                content.setNonStrokingColor(.9f);
                content.addRect(30, 60, 100, 20);
                content.fill();
            }
            try (final var content = new PDPageContentStream(document, page)) {
                content.drawForm(form);
            }
            final var options =
                    PdfOptions.defaults().withTableDetection(PdfOptions.TableDetection.RULED_AND_SHADED_ROWS);
            final var graphics =
                    new PdfPageGraphics(page, options, new PdfImageBudget(options), new PdfPageGeometry(page));
            graphics.processPage(page);
            assertEquals(1, graphics.rowBands().size());
            assertTrue(graphics.segments().isEmpty());
        }
    }

    /**
     * Counts original pale row paint under the explicit shaded-row strategy, without inferring a table.
     * @param bytes complete owned PDF
     * @return source row-band evidence count
     * @throws Exception if source parsing or graphics traversal fails
     */
    private static int shadedBands(final byte[] bytes) throws Exception {
        try (final var document = Loader.loadPDF(bytes)) {
            final var page = document.getPage(0);
            final var options =
                    PdfOptions.defaults().withTableDetection(PdfOptions.TableDetection.RULED_AND_SHADED_ROWS);
            final var graphics =
                    new PdfPageGraphics(page, options, new PdfImageBudget(options), new PdfPageGeometry(page));
            graphics.processPage(page);
            return graphics.rowBands().size();
        }
    }

    /**
     * Extracts the complete fixture with native ruled tables enabled.
     * @param bytes complete owned PDF
     * @return full extraction result
     * @throws Exception if parsing or extraction fails
     */
    private static PartitionResult extract(final byte[] bytes) throws Exception {
        return DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(PdfOptions.defaults()))))
                .partition(DocumentSource.bytes(bytes, "form-clip.pdf"));
    }

    /**
     * Requires exact equal rendered RGB for source programs with and without invisible grid paint.
     * @param first no-grid PDF
     * @param second candidate PDF
     * @throws Exception if rendering or pixel equality fails
     */
    private static void assertSameRaster(final byte[] first, final byte[] second) throws Exception {
        try (final var original = Loader.loadPDF(first); final var candidate = Loader.loadPDF(second)) {
            final var before = new PDFRenderer(original).renderImageWithDPI(0, 72);
            final var after = new PDFRenderer(candidate).renderImageWithDPI(0, 72);
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
     * Authors one complete grid inside a form with an independently specified bounding-box clip.
     * @param document owned authoring document
     * @param clip fixed inherited or explicit rectangular clipping control, or visible form scope
     * @param paint source stroke, fill, fill/stroke or pale-row operators
     * @return reusable form containing actual grid operators
     * @throws Exception if form content serialization fails
     */
    private static PDFormXObject form(final PDDocument document, final String clip, final String paint)
            throws Exception {
        final var form = new PDFormXObject(document);
        form.setResources(new PDResources());
        form.setBBox(clip.equals("empty") || clip.equals("nested-empty") ? new PDRectangle(0, 0)
                     : clip.equals("outside")                            ? new PDRectangle(10, 10)
                     : clip.equals("disjoint")                           ? new PDRectangle(20, 30, 20, 20)
                                                                         : new PDRectangle(400, 400));
        try (final var content = new PDFormContentStream(form)) {
            if (clip.startsWith("path-")) {
                final String mode = clip.replace("-lines", "").replace("-open", "").replace("-return", "");
                final Matrix transform = mode.equals("path-visible-mirrored") ? new Matrix(-1, 0, 0, 1, 400, 0)
                                         : mode.equals("path-visible-scaled") ? new Matrix(2, 0, 0, .5f, -20, 0)
                                         : mode.contains("-skewed")           ? new Matrix(1, .125f, 0, 1, 0, 0)
                                                                              : new Matrix();
                content.transform(transform);
                final boolean visible = clip.startsWith("path-visible");
                final float y = mode.equals("path-visible-scaled") ? 0 : 30;
                final float width = visible ? 360 : mode.equals("path-empty") ? 0 : 20;
                final float height = mode.equals("path-visible-scaled") ? 800 : visible ? 340 : 20;
                if (clip.contains("-lines")) {
                    content.moveTo(20, y);
                    content.lineTo(20 + width, y);
                    content.lineTo(20 + width, y + height);
                    content.lineTo(20, y + height);
                    if (clip.endsWith("-return")) {
                        content.lineTo(20, y);
                    }
                    if (!clip.endsWith("-open")) {
                        content.closePath();
                    }
                } else {
                    content.addRect(20, y, width, height);
                }
                if (clip.startsWith("path-visible-duplicate") || clip.startsWith("path-visible-triple")) {
                    content.addRect(20, y, width, height);
                }
                if (clip.startsWith("path-visible-triple")) {
                    content.addRect(20, y, width, height);
                }
                if (clip.equals("path-visible-compound")) {
                    content.addRect(0, 0, 10, 10);
                } else if (clip.equals("path-visible-curved")) {
                    content.moveTo(0, 0);
                    content.curveTo(0, 5, 5, 5, 5, 0);
                    content.closePath();
                }
                if (mode.endsWith("-evenodd")) {
                    content.clipEvenOdd();
                } else {
                    content.clip();
                }
                // Undo only the CTM: q/Q would also restore the clip whose lifetime is under test.
                content.transform(new Matrix(transform.createAffineTransform().createInverse()));
            }
            if (clip.equals("nested-empty")) {
                content.drawForm(form(document, "visible", paint));
            } else if (paint.equals("shade")) {
                content.setNonStrokingColor(.9f);
                for (final int y : List.of(150, 200, 250)) {
                    content.addRect(40, y, 300, 40);
                    content.fill();
                }
            } else {
                for (final int x : List.of(40, 190, 340)) {
                    if (paint.equals("stroke")) {
                        content.moveTo(x, 150);
                        content.lineTo(x, 300);
                    } else {
                        content.addRect(x - .25f, 149.75f, .5f, 150.5f);
                        if (paint.equals("fill")) {
                            content.fill();
                        } else {
                            content.fillAndStroke();
                        }
                    }
                }
                for (final int y : List.of(150, 200, 250, 300)) {
                    if (paint.equals("stroke")) {
                        content.moveTo(40, y);
                        content.lineTo(340, y);
                    } else {
                        content.addRect(39.75f, y - .25f, 300.5f, .5f);
                        if (paint.equals("fill")) {
                            content.fill();
                        } else {
                            content.fillAndStroke();
                        }
                    }
                }
                if (paint.equals("stroke")) {
                    content.stroke();
                }
            }
        }
        return form;
    }

    /**
     * Keeps native text outside the form, so only invisible rule ownership changes between paired sources.
     * @param clip fixed inherited clipping control, or none for the no-grid source
     * @param paint source grid operator family
     * @param rotation page quarter turn
     * @param restore whether to draw a second visible form after leaving the invisible form scope
     * @return complete owned PDF bytes
     * @throws Exception if source serialization fails
     */
    private static byte[] source(final String clip, final String paint, final int rotation, final boolean restore)
            throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(400, 400));
            page.setCropBox(new PDRectangle(20, 30, 360, 340));
            page.setUserUnit(2);
            page.setRotation(rotation);
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                if (!clip.equals("none")) {
                    content.drawForm(form(document, clip, paint));
                }
                if (restore) {
                    content.drawForm(form(document, "visible", paint));
                }
                final var values = List.of("Item", "Qty", "Alpha", "10", "Beta", "20");
                final var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                for (int index = 0; index < values.size(); index++) {
                    content.beginText();
                    content.setFont(font, 10);
                    content.newLineAtOffset(index % 2 == 0 ? 50 : 200, 280 - index / 2 * 50);
                    content.showText(values.get(index));
                    content.endText();
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
