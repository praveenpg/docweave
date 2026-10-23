package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Owned geometry controls for the explicit smaller-type side-stream block policy. */
class PdfSideStreamBlocksTest {
    /** The authored sidebar's seven lines joined with single spaces. */
    private static final String SIDE_BLOCK =
            "Sidebar note line a Sidebar note line b Sidebar note line c "
            + "Sidebar note line d Sidebar note line e Sidebar note line f Sidebar note line g";

    /** Provenance recorded on side-stream elements. */
    private static final String POLICY = "side_stream_blocks_v1";

    /** Creates isolated geometry controls. */
    PdfSideStreamBlocksTest() {
    }

    /**
     * A smaller-type sidebar interleaved with a main column becomes contiguous blocks at their first lines, while
     * every main-stream element, structural item and the historical default stay exactly as before.
     * @throws Exception if grouping fails
     */
    @Test
    void smallerSidebarLinesFormBlocksWithoutChangingTheMainStream() throws Exception {
        final var page = sidebarPage(4.7f, 36, 180);
        final var before = new GeometricPdfReadingOrder(PdfOptions.TextLayout.COLUMN_PARAGRAPHS).apply(page);
        final var preserved = order(PdfOptions.SideStreams.PRESERVE).apply(page);
        assertEquals(before, preserved);
        final var after = order(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS).apply(page);
        final var texts = after.stream().map(PdfLayoutItem::text).toList();
        assertTrue(texts.contains("side a1 side a2 side a3"));
        assertTrue(texts.contains("side b1 side b2"));
        assertTrue(before.stream().noneMatch(item -> item.text().equals("side a1 side a2 side a3")));
        assertEquals(main(before), main(after));
        final int block = texts.indexOf("side a1 side a2 side a3");
        assertEquals("main 0 main 1 main 2", texts.get(block - 1));
        assertEquals("main 3", texts.get(block + 1));
        assertEquals(ElementType.Image, after.get(texts.indexOf("")).type());
        assertEquals(before.stream().filter(item -> item.type() == ElementType.Image).toList(),
                after.stream().filter(item -> item.type() == ElementType.Image).toList());
        for (final var item : after) {
            assertEquals(item.text().startsWith("side"), POLICY.equals(item.metadata().get("pdf_reading_order")));
        }
        assertEquals(new PdfLayoutItem.Bounds(36, 132, 180, 158.7f), after.get(block).box(),
                "Enclosing bounds cover exactly the joined source lines");
        assertEquals(characters(page), characters(after));
    }

    /**
     * Equal-size aligned text, too few side lines, a side column reaching into the main extent, small text inside
     * the main column (captions, markers) and rotated side text keep historical grouping.
     * @throws Exception if grouping fails
     */
    @Test
    void ambiguousOrUnsupportedLayoutsKeepHistoricalGrouping() throws Exception {
        final var equalType = sidebarPage(6, 36, 180);
        final var reaching = sidebarPage(4.7f, 36, 196);
        final var inside = sidebarPage(4.7f, 200, 400);
        final var tooFew = new ArrayList<>(sidebarPage(4.7f, 36, 180));
        tooFew.removeIf(item -> item.text().startsWith("side") && !item.text().startsWith("side a"));
        tooFew.removeIf(item -> item.text().equals("side a3"));
        final var rotated =
                sidebarPage(4.7f, 36, 180)
                        .stream()
                        .map(item
                                -> item.text().startsWith("side") ? new PdfLayoutItem(item.type(), item.text(),
                                                                            item.box(), Map.of("pdf_text_rotation", 90))
                                                                  : item)
                        .toList();
        for (final var page : List.of(equalType, reaching, inside, tooFew, rotated)) {
            assertEquals(order(PdfOptions.SideStreams.PRESERVE).apply(page),
                    order(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS).apply(page));
        }
    }

    /**
     * A page with an accepted two-column gutter keeps its column order even when one column is set smaller.
     * @throws Exception if grouping fails
     */
    @Test
    void acceptedTwoColumnBandsAreUnchanged() throws Exception {
        final var page = new ArrayList<PdfLayoutItem>();
        for (int row = 0; row < 6; row++) {
            page.add(line("left " + row, 40, 100 + row * 12, 240, 4.7f));
            page.add(line("right " + row, 300, 100 + row * 12, 520, 6));
        }
        final var preserved = order(PdfOptions.SideStreams.PRESERVE).apply(page);
        assertEquals("two_column_ltr_v1", preserved.getFirst().metadata().get("pdf_reading_order"));
        assertEquals(preserved, order(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS).apply(page));
    }

    /**
     * The policy shares the layout work ceiling and cancellation with historical grouping.
     * @throws Exception if limits are not enforced
     */
    @Test
    void layoutWorkCeilingAndCancellationRemainTyped() throws Exception {
        final var service = order(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS);
        final var page = sidebarPage(4.7f, 36, 180);
        final var repeated = new ArrayList<PdfLayoutItem>();
        while (repeated.size() < 400_000) {
            repeated.addAll(page);
        }
        assertEquals(PartitionException.Code.LAYOUT_LIMIT,
                assertThrows(PartitionException.class, () -> service.apply(repeated)).code());
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> service.apply(page));
        } finally {
            // The test owns this interrupt and clears it before the runner reuses the thread.
            Thread.interrupted();
        }
    }

    /**
     * Through the public partitioner, the policy groups an authored sidebar under a paragraph layout only; the line
     * layout and the default are unchanged, and the options record keeps compatibility and copies.
     * @throws Exception if source writing or extraction fails
     */
    @Test
    void publicOptionReachesParagraphLayoutsOnly() throws Exception {
        final byte[] bytes = authoredSidebar();
        final var columns = PdfOptions.defaults().withTextLayout(PdfOptions.TextLayout.COLUMN_PARAGRAPHS);
        final var selected = columns.withSideStreams(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS);
        final var before = extract(bytes, columns);
        final var after = extract(bytes, selected);
        assertTrue(before.stream().noneMatch(e -> e.text().equals(SIDE_BLOCK)));
        assertTrue(after.stream().anyMatch(
                e -> e.text().equals(SIDE_BLOCK) && POLICY.equals(e.metadata().get("pdf_reading_order"))));
        final var lines = PdfOptions.defaults();
        assertEquals(texts(extract(bytes, lines)),
                texts(extract(bytes, lines.withSideStreams(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS))));
        assertEquals(PdfOptions.SideStreams.PRESERVE, PdfOptions.defaults().sideStreams());
        assertEquals(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS,
                selected.withInlineScripts(PdfOptions.InlineScripts.NEARBY_SMALL_GLYPHS)
                        .withTextLayout(PdfOptions.TextLayout.COLUMN_PARAGRAPHS_COMPOUND_WRAPS)
                        .withCurvedClipEnvelopes(true)
                        .sideStreams());
        final var old = new PdfOptions(10, 100, 100, 100, 100, false, TableLimits.defaults(),
                PdfOptions.TableDetection.RULED_ONLY, PdfOptions.TextLayout.LINES, false,
                PdfOptions.TableContinuation.NONE, PdfOptions.UnicodeNormalization.RAW, PdfOptions.TextRendering.ALL,
                PdfOptions.FigureCaptions.NONE, false, false, false, PdfOptions.InlineScripts.NEARBY_SMALL_GLYPHS);
        assertEquals(PdfOptions.SideStreams.PRESERVE, old.sideStreams());
        assertEquals(PdfOptions.InlineScripts.NEARBY_SMALL_GLYPHS, old.inlineScripts());
        assertThrows(NullPointerException.class, () -> PdfOptions.defaults().withSideStreams(null));
    }

    /**
     * A smaller-type column of statement labels beside numeric value columns is row-bound: every label shares its
     * baseline with values. Independently spaced and indented labels, plus value-less section headings, keep the
     * historical output, so each label stays adjacent to its own row values and no labels are merged.
     * @throws Exception if grouping fails
     */
    @Test
    void statementLabelColumnsKeepRowAssociation() throws Exception {
        final var page = new ArrayList<PdfLayoutItem>();
        final float[] pitches = {14, 17, 15, 13, 18, 14, 16, 15, 17, 14, 15, 16};
        final float[] indents = {52, 70, 70, 88, 52, 70, 88, 88, 52, 70, 70, 106};
        float top = 100;
        for (int row = 0; row < pitches.length; row++) {
            final boolean heading = row == 0 || row == 4 || row == 8;
            page.add(line("label " + row, indents[row], top + 1.3f, indents[row] + 90, 4.7f));
            if (!heading) {
                page.add(line("value " + row, 300, top, 340, 6));
                page.add(line("prior " + row, 420, top, 460, 6));
            }
            top += pitches[row];
        }
        final var preserved = order(PdfOptions.SideStreams.PRESERVE).apply(page);
        final var selected = order(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS).apply(page);
        // Text, membership, bounds and order are historical. Value-less headings at their own indent are not
        // row-bound and may carry side-stream provenance as single lines; they cannot merge across label rows.
        assertEquals(preserved.stream().map(item -> List.of(item.text(), item.box())).toList(),
                selected.stream().map(item -> List.of(item.text(), item.box())).toList());
        final var texts = selected.stream().map(PdfLayoutItem::text).toList();
        assertTrue(texts.stream().noneMatch(text -> text.indexOf("label") != text.lastIndexOf("label")),
                "No element holds two labels");
        for (int row = 0; row < pitches.length; row++) {
            if (row == 0 || row == 4 || row == 8) {
                continue;
            }
            final var positions = java.util.stream.Stream.of("label " + row, "value " + row, "prior " + row)
                                          .mapToInt(texts::indexOf)
                                          .sorted()
                                          .toArray();
            assertTrue(positions[0] >= 0, "Each label and value remains its own element");
            assertEquals(positions[0] + 2, positions[2], "A row's label and values stay contiguous");
        }
        assertTrue(selected.stream()
                           .filter(item -> "side_stream_blocks_v1".equals(item.metadata().get("pdf_reading_order")))
                           .allMatch(item -> List.of("label 0", "label 4", "label 8").contains(item.text())),
                "Only the value-less headings may carry side-stream provenance");
    }

    /**
     * A full-width image, a Table and a spanning text line between joinable sidebar lines each divide the block:
     * no element holds side lines from both sides, the barrier stays between them and bounds cover one side only.
     * An item lying beside the sidebar inside the main column does not divide it.
     * @throws Exception if grouping fails
     */
    @Test
    void interveningStructuralBarriersDivideSideBlocks() throws Exception {
        for (final var barrier :
                List.of(new PdfLayoutItem(ElementType.Image, "", new PdfLayoutItem.Bounds(0, 138, 600, 141), Map.of()),
                        new PdfLayoutItem(
                                ElementType.Table, "cell", new PdfLayoutItem.Bounds(30, 137.5f, 570, 141.5f), Map.of()),
                        line("Spanning statement across both columns", 36, 137.5f, 560, 4))) {
            final var page = barrierPage(barrier);
            final var preserved = order(PdfOptions.SideStreams.PRESERVE).apply(page);
            final var selected = order(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS).apply(page);
            final var texts = selected.stream().map(PdfLayoutItem::text).toList();
            assertTrue(texts.contains("side 0"), "The line above the barrier stays apart");
            final int below = texts.indexOf("side 1 side 2 side 3 side 4 side 5");
            assertTrue(below >= 0, "Lines below the barrier still form a block");
            final int at = selected.indexOf(barrier);
            assertTrue(texts.indexOf("side 0") < at && at < below, "The barrier stays between the two parts");
            assertEquals(new PdfLayoutItem.Bounds(36, 143, 180, 191.7f), selected.get(below).box());
            assertEquals(main(preserved), main(selected));
            assertTrue(selected.stream().noneMatch(item -> item.text().contains("side 0 side 1")));
        }
        final var beside = barrierPage(
                new PdfLayoutItem(ElementType.Image, "", new PdfLayoutItem.Bounds(300, 138, 500, 141), Map.of()));
        assertTrue(order(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS)
                        .apply(beside)
                        .stream()
                        .anyMatch(item -> item.text().equals("side 0 side 1 side 2 side 3 side 4 side 5")));
    }

    /**
     * Builds the review geometry: eleven main lines, six joinable smaller side lines and one barrier item.
     * @param barrier item placed between the first and second side lines
     * @return page items
     */
    private static List<PdfLayoutItem> barrierPage(final PdfLayoutItem barrier) {
        final var page = new ArrayList<PdfLayoutItem>();
        for (int index = 0; index < 11; index++) {
            page.add(line("main " + index, 198, 100 + index * 15, 560, 6));
        }
        for (int index = 0; index < 6; index++) {
            page.add(line("side " + index, 36, 132 + index * 11, 180, 4.7f));
        }
        page.add(barrier);
        return List.copyOf(page);
    }

    /**
     * Builds a main column of eleven lines, two smaller sidebar blocks and one image barrier.
     * @param sideHeight sidebar line height
     * @param sideLeft sidebar left edge
     * @param sideRight sidebar right edge
     * @return page items in arbitrary authoring order
     */
    private static List<PdfLayoutItem> sidebarPage(
            final float sideHeight, final float sideLeft, final float sideRight) {
        final var page = new ArrayList<PdfLayoutItem>();
        for (int index = 0; index < 11; index++) {
            page.add(line("main " + index, 198, 100 + index * 15, 560, 6));
        }
        for (int index = 0; index < 3; index++) {
            page.add(line("side a" + (index + 1), sideLeft, 132 + index * 11, sideRight, sideHeight));
        }
        for (int index = 0; index < 2; index++) {
            page.add(line("side b" + (index + 1), sideLeft, 200 + index * 11, sideRight, sideHeight));
        }
        page.add(new PdfLayoutItem(ElementType.Image, "", new PdfLayoutItem.Bounds(36, 60, 90, 90), Map.of()));
        return List.copyOf(page);
    }

    /**
     * Creates one ordinary native line.
     * @param text source text
     * @param left left edge
     * @param top top edge
     * @param right right edge
     * @param height line height
     * @return native line
     */
    private static PdfLayoutItem line(
            final String text, final float left, final float top, final float right, final float height) {
        return new PdfLayoutItem(ElementType.UncategorizedText, text,
                new PdfLayoutItem.Bounds(left, top, right, top + height), Map.of());
    }

    /**
     * Creates the column-paragraph service with one side-stream policy.
     * @param policy side-stream policy
     * @return reading order
     */
    private static PdfReadingOrder order(final PdfOptions.SideStreams policy) {
        return new GeometricPdfReadingOrder(
                PdfOptions.TextLayout.COLUMN_PARAGRAPHS, group -> java.util.Set.of(), policy);
    }

    /**
     * Returns the main-stream items in order.
     * @param items grouped items
     * @return items whose text starts with the main prefix
     */
    private static List<PdfLayoutItem> main(final List<PdfLayoutItem> items) {
        return items.stream().filter(item -> item.text().startsWith("main")).toList();
    }

    /**
     * Returns the sorted non-space characters of all items, which grouping must never add, drop or change.
     * @param items items
     * @return sorted characters
     */
    private static String characters(final List<PdfLayoutItem> items) {
        return items.stream()
                .map(PdfLayoutItem::text)
                .flatMapToInt(String::codePoints)
                .filter(codePoint -> !Character.isWhitespace(codePoint))
                .sorted()
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }

    /**
     * Writes an owned page: a 9-point main column of eight lines beside a 6-point sidebar of seven lines.
     * @return complete owned PDF bytes
     * @throws Exception if writing fails
     */
    private static byte[] authoredSidebar() throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(600, 800));
            document.addPage(page);
            final var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (final var content = new PDPageContentStream(document, page)) {
                for (int index = 0; index < 8; index++) {
                    draw(content, font, "Main column line number " + index + " continues here", 200, 700 - index * 15,
                            9);
                }
                final var side = List.of(SIDE_BLOCK.split("(?<=line [a-g]) "));
                for (int index = 0; index < side.size(); index++) {
                    draw(content, font, side.get(index), 36, 672 - index * 9, 6);
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    /**
     * Writes one original run.
     * @param content open stream
     * @param font font
     * @param value characters
     * @param x left edge
     * @param y baseline
     * @param size font size
     * @throws Exception if writing fails
     */
    private static void draw(final PDPageContentStream content, final PDType1Font font, final String value,
            final float x, final float y, final float size) throws Exception {
        content.beginText();
        content.setFont(font, size);
        content.newLineAtOffset(x, y);
        content.showText(value);
        content.endText();
    }

    /**
     * Extracts through the public partitioner.
     * @param bytes owned source
     * @param options explicit options
     * @return elements
     * @throws Exception if extraction fails
     */
    private static List<Element> extract(final byte[] bytes, final PdfOptions options) throws Exception {
        return DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options))))
                .partition(DocumentSource.bytes(bytes, "owned.pdf"))
                .elements();
    }

    /**
     * Returns element texts in order.
     * @param elements elements
     * @return texts
     */
    private static List<String> texts(final List<Element> elements) {
        return elements.stream().map(Element::text).toList();
    }
}
