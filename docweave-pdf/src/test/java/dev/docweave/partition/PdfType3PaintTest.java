package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.core.ElementType;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Invisible Type3 character programs must not become native table-rule evidence. */
class PdfType3PaintTest {
    /** Creates isolated native font-program controls. */
    PdfType3PaintTest() {
    }

    /**
     * An invisible glyph with a grid and one without paint render identically but must not differ in table detection.
     * @throws Exception if source authoring, independent rendering or extraction fails
     */
    @Test
    void invisibleGlyphGridDoesNotBecomeVisibleTableBorders() throws Exception {
        final var blank = source(false, true);
        final var hidden = source(true, true);
        try (final var first = Loader.loadPDF(blank); final var second = Loader.loadPDF(hidden)) {
            final var a = new PDFRenderer(first).renderImage(0);
            final var b = new PDFRenderer(second).renderImage(0);
            try {
                assertArrayEquals(a.getRGB(0, 0, a.getWidth(), a.getHeight(), null, 0, a.getWidth()),
                        b.getRGB(0, 0, b.getWidth(), b.getHeight(), null, 0, b.getWidth()));
            } finally {
                a.flush();
                b.flush();
            }
        }
        final var expected = DocWeave.create().partition(DocumentSource.bytes(blank, "type3-control.pdf"));
        final var actual = DocWeave.create().partition(DocumentSource.bytes(hidden, "type3-hidden.pdf"));
        assertTrue(expected.elements().stream().noneMatch(element -> element.type() == ElementType.Table));
        assertEquals(0, actual.elements().stream().filter(element -> element.type() == ElementType.Table).count());
        final var visible = DocWeave.create().partition(DocumentSource.bytes(source(true, false), "type3-visible.pdf"));
        assertEquals(1, visible.elements().stream().filter(element -> element.type() == ElementType.Table).count());
    }

    /**
     * Paint suppression is scoped to the character program, not a page whose last text rendering mode is NEITHER.
     * @throws Exception if source authoring or public table extraction fails
     */
    @Test
    void pageRulesAfterInvisibleGlyphStillFormTheirOriginalTable() throws Exception {
        final var actual =
                DocWeave.create().partition(DocumentSource.bytes(source(false, true, true), "type3-page-grid.pdf"));
        assertEquals(1, actual.elements().stream().filter(element -> element.type() == ElementType.Table).count());
    }

    /**
     * Both filled-rule centerlines and fill/stroke borders obey glyph-local paint suppression.
     * @throws Exception if native character-program traversal fails
     */
    @Test
    void filledAndCombinedGlyphRulesKeepVisibleControlsAndRejectInvisiblePaint() throws Exception {
        for (final String operation : List.of("f", "B")) {
            for (final boolean hidden : List.of(false, true)) {
                try (final var document = new PDDocument()) {
                    final var page = new PDPage();
                    document.addPage(page);
                    final var options = PdfOptions.defaults();
                    final var graphics =
                            new PdfPageGraphics(page, options, new PdfImageBudget(options), new PdfPageGeometry(page));
                    graphics.processPage(page);
                    graphics.getGraphicsState().getTextState().setRenderingMode(
                            hidden ? RenderingMode.NEITHER : RenderingMode.FILL);
                    final var glyphFont = font(document, false);
                    final var commands = new StringBuilder("1 0 d0\n");
                    for (final int x : new int[] {40, 100, 160}) {
                        commands.append(x).append(" 80 1 120 re ").append(operation).append('\n');
                    }
                    for (final int y : new int[] {80, 120, 160, 200}) {
                        commands.append("40 ").append(y).append(" 120 1 re ").append(operation).append('\n');
                    }
                    glyphFont.getCharProcs().setItem(COSName.getPDFName("A"), stream(document, commands.toString()));
                    // The font matrix scales glyph coordinates by .001; a 1000-unit text transform restores page units.
                    graphics.showType3Glyph(new Matrix(1000, 0, 0, 1000, 0, 0), glyphFont, 65, new Vector(1, 0));
                    if (hidden) {
                        assertTrue(graphics.segments().isEmpty());
                        assertTrue(graphics.rowBands().isEmpty());
                    } else {
                        assertEquals(operation.equals("f") ? 7 : 28, graphics.segments().size());
                    }
                }
            }
        }
    }

    /**
     * Hidden-glyph paint filtering does not discard source image occurrences or avoid their existing resource charges.
     * @throws Exception if owned source resources or visitor traversal fail
     */
    @Test
    void invisibleGlyphStillAccountsForOriginalSourceImage() throws Exception {
        try (final var document = new PDDocument()) {
            final var page = new PDPage();
            document.addPage(page);
            final var glyphFont = font(document, false);
            final var pixels = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            final var image = LosslessFactory.createFromImage(document, pixels);
            pixels.flush();
            glyphFont.getResources().put(COSName.getPDFName("Im1"), image);
            glyphFont.getCharProcs().setItem(
                    COSName.getPDFName("A"), stream(document, "1 0 d0 q 10 0 0 10 10 10 cm /Im1 Do Q\n"));
            final var options = PdfOptions.defaults();
            final var graphics =
                    new PdfPageGraphics(page, options, new PdfImageBudget(options), new PdfPageGeometry(page));
            graphics.processPage(page);
            graphics.getGraphicsState().getTextState().setRenderingMode(RenderingMode.NEITHER);
            graphics.showType3Glyph(new Matrix(), glyphFont, 65, new Vector(1, 0));
            assertEquals(1, graphics.images().size());
            assertTrue(graphics.segments().isEmpty());
        }
    }

    /**
     * Cancellation and operator exhaustion unwind the glyph-only scope without resetting the shared page budget.
     * @throws Exception if direct visitor/resource invariants fail
     */
    @Test
    void failedGlyphRestoresPaintEligibilityAndPreservesOperatorBudget() throws Exception {
        try (final var document = new PDDocument()) {
            final var page = new PDPage();
            document.addPage(page);
            final var options = new PdfOptions(10, 1000, 1, 100, 1000, false, TableLimits.defaults());
            final var graphics =
                    new PdfPageGraphics(page, options, new PdfImageBudget(options), new PdfPageGeometry(page));
            graphics.processPage(page);
            graphics.getGraphicsState().getTextState().setRenderingMode(RenderingMode.NEITHER);
            final var glyphFont = font(document, true);
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedIOException.class,
                        () -> graphics.showType3Glyph(new Matrix(), glyphFont, 65, new Vector(1, 0)));
            } finally {
                Thread.interrupted();
            }
            final var limit = assertThrows(PartitionException.class,
                    () -> graphics.showType3Glyph(new Matrix(), glyphFont, 65, new Vector(1, 0)));
            assertEquals(PartitionException.Code.INPUT_TOO_LARGE, limit.code());
            graphics.moveTo(10, 10);
            graphics.lineTo(80, 10);
            graphics.strokePath();
            assertEquals(1, graphics.segments().size());
            assertThrows(
                    PartitionException.class, () -> graphics.processOperator(Operator.getOperator("n"), List.of()));
        }
    }

    /**
     * A malformed self-referencing character program must fail through a typed limit rather than exhaust the JVM stack.
     * @throws Exception if source authoring or the typed-limit assertion fails
     */
    @Test
    void recursiveGlyphFailsWithTypedLimitRatherThanStackOverflow() throws Exception {
        try (final var document = new PDDocument()) {
            final var page = new PDPage();
            document.addPage(page);
            final var glyphFont = font(document, false);
            glyphFont.getResources().put(COSName.getPDFName("Recursive"), glyphFont);
            glyphFont.getCharProcs().setItem(
                    COSName.getPDFName("A"), stream(document, "1 0 d0 BT /Recursive 1 Tf (A) Tj ET\n"));
            final var options = PdfOptions.defaults();
            final var graphics =
                    new PdfPageGraphics(page, options, new PdfImageBudget(options), new PdfPageGeometry(page));
            graphics.processPage(page);
            final var failure = assertThrows(PartitionException.class,
                    () -> graphics.showType3Glyph(new Matrix(), glyphFont, 65, new Vector(1, 0)));
            assertEquals(PartitionException.Code.INPUT_TOO_LARGE, failure.code());
            assertTrue(failure.getMessage().contains("Type3 glyph nesting"));
            final var resources = new PDResources();
            resources.put(COSName.getPDFName("Root"), glyphFont);
            page.setResources(resources);
            page.setContents(stream(document, "BT /Root 1 Tf 0 0 Td (A) Tj ET\n"));
            final var encoded = new ByteArrayOutputStream();
            document.save(encoded);
            final var publicFailure = assertThrows(PartitionException.class,
                    ()
                            -> DocWeave.create().partition(
                                    DocumentSource.bytes(encoded.toByteArray(), "recursive-type3.pdf")));
            assertEquals(PartitionException.Code.INPUT_TOO_LARGE, publicFailure.code());
            assertDoesNotThrow(
                    () -> graphics.showType3Glyph(new Matrix(), font(document, false), 65, new Vector(1, 0)));
        }
    }

    /**
     * The documented boundary accepts 64 finite character programs and rejects the 65th through the same typed limit.
     * @throws Exception if finite source authoring or scope-boundary assertions fail
     */
    @Test
    void finiteGlyphNestingHasAnExplicitBoundaryAndUnwindsAfterRejection() throws Exception {
        for (final int depth : new int[] {64, 65}) {
            try (final var document = new PDDocument()) {
                final var page = new PDPage();
                document.addPage(page);
                var root = font(document, false);
                for (int index = 1; index < depth; index++) {
                    final var parent = font(document, false);
                    parent.getResources().put(COSName.getPDFName("Next"), root);
                    parent.getCharProcs().setItem(
                            COSName.getPDFName("A"), stream(document, "1 0 d0 BT /Next 1 Tf (A) Tj ET\n"));
                    root = parent;
                }
                final var entry = root;
                final var options = PdfOptions.defaults();
                final var graphics =
                        new PdfPageGraphics(page, options, new PdfImageBudget(options), new PdfPageGeometry(page));
                graphics.processPage(page);
                if (depth == 64) {
                    assertDoesNotThrow(() -> graphics.showType3Glyph(new Matrix(), entry, 65, new Vector(1, 0)));
                } else {
                    final var failure = assertThrows(PartitionException.class,
                            () -> graphics.showType3Glyph(new Matrix(), entry, 65, new Vector(1, 0)));
                    assertEquals(PartitionException.Code.INPUT_TOO_LARGE, failure.code());
                }
                assertDoesNotThrow(
                        () -> graphics.showType3Glyph(new Matrix(), font(document, false), 65, new Vector(1, 0)));
            }
        }
    }

    /**
     * Authors a real Type3 font with one glyph and unchanged surrounding six-cell source labels.
     * @param grid whether the glyph contains an actual stroked grid
     * @param invisible whether the text rendering mode suppresses the glyph program
     * @return complete owned source document
     * @throws Exception if native source authoring fails
     */
    private static byte[] source(final boolean grid, final boolean invisible) throws Exception {
        return source(grid, invisible, false);
    }

    /**
     * Optionally adds ordinary page rules while preserving the preceding invisible text state.
     * @param grid whether the source glyph paints a grid
     * @param invisible whether the glyph is invisible
     * @param pageGrid whether the page independently paints its own grid
     * @return complete source bytes
     * @throws Exception if source authoring fails
     */
    private static byte[] source(final boolean grid, final boolean invisible, final boolean pageGrid) throws Exception {
        try (final var document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(240, 280));
            document.addPage(page);
            final var resources = new PDResources();
            page.setResources(resources);
            resources.put(COSName.getPDFName("T3"), font(document, grid));
            resources.put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            final var commands =
                    new StringBuilder("BT /T3 1000 Tf ").append(invisible ? "3" : "0").append(" Tr 0 0 Td (A) Tj ET\n");
            if (pageGrid) {
                commands.append("40 80 m 40 200 l 100 80 m 100 200 l 160 80 m 160 200 l\n")
                        .append("40 80 m 160 80 l 40 120 m 160 120 l 40 160 m 160 160 l 40 200 m 160 200 l S\n");
            }
            final String[] labels = {"Item", "Qty", "Alpha", "10", "Beta", "20"};
            for (int index = 0; index < labels.length; index++) {
                commands.append("BT /F1 10 Tf 0 Tr ")
                        .append(45 + 60 * (index % 2))
                        .append(' ')
                        .append(180 - 40 * (index / 2))
                        .append(" Td (")
                        .append(labels[index])
                        .append(") Tj ET\n");
            }
            page.setContents(stream(document, commands.toString()));
            final var output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        }
    }

    /**
     * Defines the glyph's source encoding, geometry and bounded drawing commands independently of extraction.
     * @param document owned native resource container
     * @param grid whether the glyph paints the six-cell grid
     * @return valid Type3 font resource with one encoded A character
     * @throws Exception if the source font or character stream cannot be encoded
     */
    private static PDType3Font font(final PDDocument document, final boolean grid) throws Exception {
        final var dictionary = new COSDictionary();
        dictionary.setItem(COSName.TYPE, COSName.FONT);
        dictionary.setItem(COSName.SUBTYPE, COSName.TYPE3);
        dictionary.setItem(COSName.FONT_BBOX, array(39, 79, 161, 201));
        dictionary.setItem(COSName.FONT_MATRIX, array(.001f, 0, 0, .001f, 0, 0));
        dictionary.setInt(COSName.FIRST_CHAR, 65);
        dictionary.setInt(COSName.LAST_CHAR, 65);
        dictionary.setItem(COSName.WIDTHS, array(1));
        dictionary.setItem(COSName.RESOURCES, new COSDictionary());
        final var encoding = new COSDictionary();
        encoding.setItem(COSName.TYPE, COSName.ENCODING);
        final var differences = new COSArray();
        differences.add(COSInteger.get(65));
        differences.add(COSName.getPDFName("A"));
        encoding.setItem(COSName.DIFFERENCES, differences);
        dictionary.setItem(COSName.ENCODING, encoding);
        final var commands = new StringBuilder("1 0 d0\n");
        if (grid) {
            for (final int x : new int[] {40, 100, 160}) {
                commands.append(x).append(" 80 m ").append(x).append(" 200 l\n");
            }
            for (final int y : new int[] {80, 120, 160, 200}) {
                commands.append("40 ").append(y).append(" m 160 ").append(y).append(" l\n");
            }
            commands.append("S\n");
        }
        final var procedures = new COSDictionary();
        procedures.setItem(COSName.getPDFName("A"), stream(document, commands.toString()));
        dictionary.setItem(COSName.CHAR_PROCS, procedures);
        return new PDType3Font(dictionary);
    }

    /**
     * Writes exact original ASCII commands without involving a text/font encoder under test.
     * @param document owned native container
     * @param commands source operator program
     * @return complete source stream
     * @throws Exception if stream creation fails
     */
    private static PDStream stream(final PDDocument document, final String commands) throws Exception {
        return new PDStream(document, new ByteArrayInputStream(commands.getBytes(StandardCharsets.US_ASCII)));
    }

    /**
     * Encodes small independently specified font arrays.
     * @param values source numeric attributes
     * @return original COS array
     */
    private static COSArray array(final float... values) {
        final var result = new COSArray();
        for (final float value : values) {
            result.add(new COSFloat(value));
        }
        return result;
    }
}
