package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.core.ElementType;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDFormContentStream;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Render-equivalent source programs verify that form-local paths cannot consume or leak into their caller. */
class PdfFormPathTest {
    /** Creates isolated parent/form path controls. */
    PdfFormPathTest() {
    }

    /** Independently specified child paint rather than candidate-dependent extraction setup. */
    private enum Paint {
        /** Off-page painted rectangle still executes source path operators. */ EMPTY,
        /** Separate complete visible ruled table. */ GRID,
        /** Nested off-page paint with an unfinished outer-form path. */ NESTED_EMPTY
    }

    /**
     * Complete fixed source construction policy.
     * @param paint child path content
     * @param transparency whether form dispatch uses the transparency-group callback
     * @param clipParent whether the unfinished parent path defines an empty rectangle clip
     * @param rotation original displayed page quarter turn
     */
    private record Setup(Paint paint, boolean transparency, boolean clipParent, int rotation) {}

    /**
     * Moving a disjoint child invocation before an unfinished parent stroke cannot remove either table.
     * @throws Exception if owned authoring, rendering or public extraction fails
     */
    @Test
    void normalTransparentAndNestedFormsPreservePendingCallerRules() throws Exception {
        for (final int rotation : List.of(0, 90, 180, 270)) {
            for (final boolean transparency : List.of(false, true)) {
                for (final var paint : Paint.values()) {
                    final var setup = new Setup(paint, transparency, false, rotation);
                    final var before = source(setup, false);
                    final var during = source(setup, true);
                    sameRaster(before, during);
                    final var expected = extract(before);
                    assertEquals(paint == Paint.GRID ? 2 : 1,
                            expected.elements().stream().filter(e -> e.type() == ElementType.Table).count());
                    assertEquals(expected.elements(), extract(during).elements());
                }
            }
        }
    }

    /**
     * Parent raw rectangle vertices/current point survive child path operators until the parent's later clip.
     * @throws Exception if render equivalence or source ownership fails
     */
    @Test
    void callerRectangleClipRemainsIntactAcrossFormDispatch() throws Exception {
        for (final int rotation : List.of(0, 90, 180, 270)) {
            for (final boolean transparency : List.of(false, true)) {
                final var setup = new Setup(Paint.EMPTY, transparency, true, rotation);
                final var before = source(setup, false);
                final var during = source(setup, true);
                sameRaster(before, during);
                final var expected = extract(before);
                assertEquals(6, expected.elements().size());
                assertTrue(expected.elements().stream().noneMatch(e -> e.type() == ElementType.Table));
                assertEquals(expected.elements(), extract(during).elements());
            }
        }
    }

    /**
     * Failure/cancellation restore parent path ownership while the page-global operator budget remains consumed.
     * @throws Exception if direct visitor invariants fail
     */
    @Test
    void formFailureAndCancellationRestoreCallerPathWithoutResettingPageBudget() throws Exception {
        for (final boolean transparency : List.of(false, true)) {
            try (final var document = new PDDocument()) {
                final var page = new PDPage(new PDRectangle(400, 400));
                document.addPage(page);
                final var options = new PdfOptions(10, 1000, 1, 100, 1000, false, TableLimits.defaults());
                final var graphics =
                        new PdfPageGraphics(page, options, new PdfImageBudget(options), new PdfPageGeometry(page));
                graphics.processPage(page);
                graphics.moveTo(10, 10);
                graphics.lineTo(80, 10);
                final var form = child(document, Paint.EMPTY, transparency);
                Thread.currentThread().interrupt();
                try {
                    assertThrows(InterruptedIOException.class, () -> dispatch(graphics, form, transparency));
                } finally {
                    Thread.interrupted();
                }
                final var failure =
                        assertThrows(PartitionException.class, () -> dispatch(graphics, form, transparency));
                assertEquals(PartitionException.Code.INPUT_TOO_LARGE, failure.code());
                graphics.strokePath();
                assertEquals(List.of(new PdfPageGraphics.Segment(10, 390, 80, 390)), graphics.segments());
                assertThrows(
                        PartitionException.class, () -> graphics.processOperator(Operator.getOperator("n"), List.of()));
            }
        }
    }

    /**
     * Selects the actual form callback without changing source state or budget handling.
     * @param graphics call-confined visitor
     * @param form owned child resource
     * @param transparency whether transparency dispatch is required
     * @throws Exception if child interpretation fails
     */
    private static void dispatch(final PdfPageGraphics graphics, final PDFormXObject form, final boolean transparency)
            throws Exception {
        if (transparency) {
            graphics.showTransparencyGroup((PDTransparencyGroup) form);
        } else {
            graphics.showForm(form);
        }
    }

    /**
     * Builds a self-contained child whose graphics state is independent of its caller's unfinished path.
     * @param document owned authoring document
     * @param paint fixed original child content
     * @param transparency actual source form/group type
     * @return complete child resource
     * @throws Exception if source writing fails
     */
    private static PDFormXObject child(final PDDocument document, final Paint paint, final boolean transparency)
            throws Exception {
        final PDFormXObject form = transparency ? new PDTransparencyGroup(document) : new PDFormXObject(document);
        form.setResources(new PDResources());
        form.setBBox(paint == Paint.EMPTY ? new PDRectangle(-100, -100, 10, 10) : new PDRectangle(400, 400));
        if (transparency) {
            final var group = new COSDictionary();
            group.setItem(COSName.S, COSName.TRANSPARENCY);
            group.setBoolean(COSName.I, true);
            form.getCOSObject().setItem(COSName.GROUP, group);
        }
        try (final var content = new PDFormContentStream(form)) {
            if (paint == Paint.EMPTY) {
                content.addRect(-100, -100, 10, 10);
                content.fill();
            } else if (paint == Paint.GRID) {
                grid(content, 220, 40);
                content.stroke();
                text(content, 220, 40, "Child");
            } else {
                content.moveTo(-200, -200);
                content.lineTo(-150, -200);
                content.drawForm(child(document, Paint.EMPTY, transparency));
                // An unfinished child path is discarded at stream exit, not transferred into its caller.
            }
        }
        return form;
    }

    /**
     * Leaves a complete parent grid or rectangle path unfinished while optionally invoking the child.
     * @param setup fixed owned source geometry
     * @param childBeforePaint whether the child interrupts an unfinished parent path
     * @return complete original PDF bytes
     * @throws Exception if source serialization fails
     */
    private static byte[] source(final Setup setup, final boolean childBeforePaint) throws Exception {
        try (final var document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(400, 400));
            page.setCropBox(new PDRectangle(20, 30, 360, 340));
            page.setRotation(setup.rotation());
            page.setUserUnit(2);
            document.addPage(page);
            final var form = child(document, setup.paint(), setup.transparency());
            try (final var content = new PDPageContentStream(document, page)) {
                if (setup.clipParent()) {
                    content.moveTo(20, 30);
                    content.lineTo(20, 30);
                    content.lineTo(20, 50);
                    content.lineTo(20, 50);
                } else {
                    grid(content, 40, 240);
                }
                if (childBeforePaint) {
                    content.drawForm(form);
                }
                if (setup.clipParent()) {
                    content.closePath();
                    content.clip();
                    grid(content, 40, 240);
                }
                content.stroke();
                if (!childBeforePaint) {
                    content.drawForm(form);
                }
                text(content, 40, 240, "Parent");
            }
            final var output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        }
    }

    /**
     * Authors a complete two-column, three-row grid without painting it yet.
     * @param content owned source stream
     * @param left original grid left
     * @param bottom original grid bottom
     * @throws Exception if source writing fails
     */
    private static void grid(final PDPageContentStream content, final float left, final float bottom) throws Exception {
        for (final int offset : List.of(0, 70, 140)) {
            content.moveTo(left + offset, bottom);
            content.lineTo(left + offset, bottom + 120);
        }
        for (final int offset : List.of(0, 40, 80, 120)) {
            content.moveTo(left, bottom + offset);
            content.lineTo(left + 140, bottom + offset);
        }
    }

    /**
     * Authors a complete two-column, three-row grid without painting it yet.
     * @param content owned source stream
     * @param left original grid left
     * @param bottom original grid bottom
     * @throws Exception if source writing fails
     */
    private static void grid(final PDFormContentStream content, final float left, final float bottom) throws Exception {
        for (final int offset : List.of(0, 70, 140)) {
            content.moveTo(left + offset, bottom);
            content.lineTo(left + offset, bottom + 120);
        }
        for (final int offset : List.of(0, 40, 80, 120)) {
            content.moveTo(left, bottom + offset);
            content.lineTo(left + 140, bottom + offset);
        }
    }

    /**
     * Supplies independently positioned original table labels inside the completed source grid.
     * @param content owned authoring stream
     * @param left original grid left
     * @param bottom original grid bottom
     * @param label first cell source label
     * @throws Exception if font encoding or source writing fails
     */
    private static void text(final PDPageContentStream content, final float left, final float bottom,
            final String label) throws Exception {
        final var values = List.of(label, "Qty", "Alpha", "10", "Beta", "20");
        for (int index = 0; index < values.size(); index++) {
            content.beginText();
            content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
            content.newLineAtOffset(left + 5 + 70 * (index % 2), bottom + 100 - 40 * (index / 2));
            content.showText(values.get(index));
            content.endText();
        }
    }

    /**
     * Supplies independently positioned original table labels inside the completed source grid.
     * @param content owned authoring stream
     * @param left original grid left
     * @param bottom original grid bottom
     * @param label first cell source label
     * @throws Exception if font encoding or source writing fails
     */
    private static void text(final PDFormContentStream content, final float left, final float bottom,
            final String label) throws Exception {
        final var values = List.of(label, "Qty", "Alpha", "10", "Beta", "20");
        for (int index = 0; index < values.size(); index++) {
            content.beginText();
            content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
            content.newLineAtOffset(left + 5 + 70 * (index % 2), bottom + 100 - 40 * (index / 2));
            content.showText(values.get(index));
            content.endText();
        }
    }

    /**
     * Runs the public native partitioner over complete original bytes.
     * @param source owned PDF bytes
     * @return complete native output
     * @throws Exception if extraction fails
     */
    private static PartitionResult extract(final byte[] source) throws Exception {
        return DocWeave.create().partition(DocumentSource.bytes(source, "form-path.pdf"));
    }

    /**
     * Checks actual renderer pixels rather than inferring visibility from the extraction visitor.
     * @param before original counterpart
     * @param after form-interrupted counterpart
     * @throws Exception if rendering or exact pixel equality fails
     */
    private static void sameRaster(final byte[] before, final byte[] after) throws Exception {
        try (final var first = Loader.loadPDF(before); final var second = Loader.loadPDF(after)) {
            final var expected = new PDFRenderer(first).renderImageWithDPI(0, 72);
            final var actual = new PDFRenderer(second).renderImageWithDPI(0, 72);
            try {
                assertArrayEquals(
                        expected.getRGB(0, 0, expected.getWidth(), expected.getHeight(), null, 0, expected.getWidth()),
                        actual.getRGB(0, 0, actual.getWidth(), actual.getHeight(), null, 0, actual.getWidth()));
            } finally {
                expected.flush();
                actual.flush();
            }
        }
    }
}
