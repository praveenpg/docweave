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
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

/** Owned geometry controls for the explicit side-stream policy that resolves same-row ties with whole elements. */
class PdfSideStreamRowTiesTest {
    /** Provenance recorded on side-stream elements. */
    private static final String POLICY = "side_stream_blocks_v1";

    /** Text of the ten-line left sidebar block. */
    private static final String SIDE_BLOCK = "side 0 side 1 side 2 side 3 side 4 side 5 side 6 side 7 side 8 side 9";

    /** Creates isolated geometry controls. */
    PdfSideStreamRowTiesTest() {
    }

    /**
     * A left sidebar block whose first line starts on the visual row of a one-line main element is emitted before
     * that element; both elements are otherwise identical, nothing else moves and no text or bounds change.
     * @throws Exception if grouping fails
     */
    @Test
    void eligibleLeftSidebarPrecedesItsOneLineMainRow() throws Exception {
        // A first line sharing the row's top sorts before it by left edge already, so ties start strictly below.
        for (final float sideTop : new float[] {164, 165}) {
            final var page = tiePage(30, sideTop, 36, 180, UnaryOperator.identity());
            final var blocks = order(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS).apply(page);
            final var ties = order(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_TIES).apply(page);
            final int side = indexOf(blocks, SIDE_BLOCK);
            assertEquals("main 2", blocks.get(side - 1).text(), "The existing policy emits the tie after the row");
            assertEquals(1, swaps(blocks, ties));
            assertEquals("main 1", ties.get(side - 2).text());
            assertEquals(blocks.get(side), ties.get(side - 1), "The sidebar element moves intact");
            assertEquals(blocks.get(side - 1), ties.get(side), "The main element moves intact");
            assertEquals(main(blocks), main(ties));
            assertPreserved(blocks, ties);
            assertEquals(new PdfLayoutItem.Bounds(36, sideTop, 180, sideTop + 94.5f + 4.7f), ties.get(side - 1).box());
        }
    }

    /**
     * Geometry outside the rule keeps the existing policy's exact output: a joined multi-line main paragraph whose
     * last line shares the sidebar row is neither split nor moved, a right-hand sidebar, a sidebar starting below the
     * row, and a sidebar touching the row's bottom edge only all stay in place.
     * @throws Exception if grouping fails
     */
    @Test
    void ineligibleGeometryKeepsExistingOutput() throws Exception {
        final var joined = tiePage(15, 164, 36, 180, UnaryOperator.identity());
        final var joinedBlocks = order(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS).apply(joined);
        final int side = indexOf(joinedBlocks, SIDE_BLOCK);
        assertEquals(5, joinedBlocks.get(side - 1).metadata().get("pdf_line_count"),
                "The fixture's predecessor is a joined paragraph whose last line overlaps the sidebar row");
        assertUnchanged(joined);
        assertUnchanged(tiePage(30, 164, 580, 700, UnaryOperator.identity()));
        assertUnchanged(tiePage(30, 167, 36, 180, UnaryOperator.identity()));
        assertUnchanged(tiePage(30, 166, 36, 180, UnaryOperator.identity()));
    }

    /**
     * An image, a table, a spanning text line, a source paragraph and rotated text in the predecessor position are
     * ineligible, so the sidebar stays after them.
     * @throws Exception if grouping fails
     */
    @Test
    void structuralSpanningSourceParagraphAndRotatedPredecessorsBlockMovement() throws Exception {
        final var predecessors = List.<UnaryOperator<PdfLayoutItem>>of(item
                -> new PdfLayoutItem(ElementType.Image, "", item.box(), Map.of()),
                item
                -> new PdfLayoutItem(ElementType.Table, "cell", item.box(), Map.of()),
                item
                -> new PdfLayoutItem(item.type(), item.text(), new PdfLayoutItem.Bounds(30, 160, 560, 166), Map.of()),
                item
                -> new PdfLayoutItem(item.type(), item.text(), item.box(), Map.of("pdf_source_paragraph_ordinal", 1)),
                item -> new PdfLayoutItem(item.type(), item.text(), item.box(), Map.of("pdf_text_rotation", 90)));
        for (final var predecessor : predecessors) {
            final var page = tiePage(30, 164, 36, 180, predecessor);
            final var blocks = order(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS).apply(page);
            final int side = indexOf(blocks, SIDE_BLOCK);
            assertTrue(side > 0 && blocks.get(side - 1).box().top() == 160, "The replaced row precedes the sidebar");
            assertUnchanged(page);
        }
    }

    /**
     * Each sidebar moves at most once. With two one-line items on the sidebar's row, the sidebar passes only the
     * adjacent one. Two interleaved side columns produce successive single-line side elements; each eligible one
     * passes its row's main line once, keeping their own order, and no element is lost, duplicated or changed.
     * @throws Exception if grouping fails
     */
    @Test
    void movementIsOneAdjacentSwapPerSidebarWithoutBubbling() throws Exception {
        final var split = new ArrayList<PdfLayoutItem>(tiePage(30, 164, 36, 180, UnaryOperator.identity()));
        final int row = split.indexOf(split.stream().filter(item -> item.text().equals("main 2")).findFirst().get());
        split.set(row, line("main 2a", 198, 160, 370, 6));
        split.add(line("main 2b", 380, 160, 560, 6));
        final var splitBlocks = order(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS).apply(split);
        final var splitTies = order(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_TIES).apply(split);
        final int side = indexOf(splitTies, SIDE_BLOCK);
        assertEquals(List.of("main 2a", SIDE_BLOCK, "main 2b"),
                splitTies.subList(side - 1, side + 2).stream().map(PdfLayoutItem::text).toList());
        assertEquals(1, swaps(splitBlocks, splitTies));
        assertPreserved(splitBlocks, splitTies);

        // Narrow and wide side lines alternate in top order, so neither column joins and each line is its own side
        // element. Five of them start inside a main row: narrow 0 and wide 0 (row 2), narrow 3 and wide 3 (row 3)
        // and narrow 6 (row 4).
        final var pair = new ArrayList<PdfLayoutItem>();
        for (int index = 0; index < 14; index++) {
            pair.add(line("main " + index, 198, 100 + index * 30, 560, 6));
        }
        for (int index = 0; index < 10; index++) {
            pair.add(line("narrow " + index, 36, 162 + index * 10.5f, 110, 4.7f));
            pair.add(line("wide " + index, 120, 164 + index * 10.5f, 180, 4.7f));
        }
        final var pairBlocks = order(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS).apply(pair);
        final var pairTies = order(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_TIES).apply(pair);
        final var texts = pairTies.stream().map(PdfLayoutItem::text).toList();
        assertEquals(List.of("main 1", "narrow 0", "wide 0", "main 2", "narrow 1"), texts.subList(1, 6));
        assertEquals(List.of("narrow 3", "wide 3", "main 3", "narrow 4"),
                texts.subList(texts.indexOf("narrow 3"), texts.indexOf("narrow 3") + 4));
        assertEquals(List.of("narrow 6", "main 4", "wide 6"),
                texts.subList(texts.indexOf("narrow 6"), texts.indexOf("narrow 6") + 3));
        assertTrue(pairTies.stream()
                        .filter(item -> item.text().startsWith("narrow") || item.text().startsWith("wide"))
                        .allMatch(item -> POLICY.equals(item.metadata().get("pdf_reading_order"))));
        assertEquals(5, swaps(pairBlocks, pairTies));
        for (int index = 0; index < pairTies.size(); index++) {
            final int before = pairBlocks.indexOf(pairTies.get(index));
            assertTrue(Math.abs(before - index) <= 2, "No element travels further than two adjacent swaps");
        }
        assertPreserved(pairBlocks, pairTies);
    }

    /**
     * The default stays PRESERVE; the older policies and an accepted two-column page give their historical output.
     * @throws Exception if grouping fails
     */
    @Test
    void defaultsExistingPoliciesAndAcceptedColumnsAreUnchanged() throws Exception {
        assertEquals(PdfOptions.SideStreams.PRESERVE, PdfOptions.defaults().sideStreams());
        final var page = tiePage(30, 164, 36, 180, UnaryOperator.identity());
        final var historical = new GeometricPdfReadingOrder(PdfOptions.TextLayout.COLUMN_PARAGRAPHS).apply(page);
        assertEquals(historical, order(PdfOptions.SideStreams.PRESERVE).apply(page));
        final var blocks = order(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS).apply(page);
        assertTrue(blocks.stream().anyMatch(item -> item.text().equals(SIDE_BLOCK)));
        assertEquals(List.of("main 2", SIDE_BLOCK),
                blocks.subList(indexOf(blocks, SIDE_BLOCK) - 1, indexOf(blocks, SIDE_BLOCK) + 1)
                        .stream()
                        .map(PdfLayoutItem::text)
                        .toList(),
                "SMALLER_TYPE_BLOCKS keeps its documented first-line placement");
        final var columns = new ArrayList<PdfLayoutItem>();
        for (int row = 0; row < 6; row++) {
            columns.add(line("left " + row, 40, 100 + row * 12, 240, 4.7f));
            columns.add(line("right " + row, 300, 100 + row * 12, 520, 6));
        }
        final var preserved = order(PdfOptions.SideStreams.PRESERVE).apply(columns);
        assertEquals("two_column_ltr_v1", preserved.getFirst().metadata().get("pdf_reading_order"));
        assertEquals(preserved, order(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_TIES).apply(columns));
    }

    /**
     * The additional tie pass is scoped to column layouts; plain paragraphs retain the older sidebar placement.
     * This prevents moving a sidebar between row partners outside the evaluated column-layout contract.
     * @throws Exception if grouping fails
     */
    @Test
    void plainParagraphsKeepExistingSidebarPlacement() throws Exception {
        final var page = tiePage(30, 164, 36, 180, UnaryOperator.identity());
        final var blocks = new GeometricPdfReadingOrder(PdfOptions.TextLayout.PARAGRAPHS,
                group -> java.util.Set.of(), PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS)
                                   .apply(page);
        final var ties = new GeometricPdfReadingOrder(PdfOptions.TextLayout.PARAGRAPHS,
                group -> java.util.Set.of(), PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_TIES)
                                 .apply(page);
        assertTrue(blocks.stream().anyMatch(item -> item.text().equals(SIDE_BLOCK)));
        assertEquals(blocks, ties);
    }

    /**
     * The policy shares the layout work ceiling and cancellation with historical grouping.
     * @throws Exception if limits are not enforced
     */
    @Test
    void layoutWorkCeilingAndCancellationRemainTyped() throws Exception {
        final var service = order(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_TIES);
        final var page = tiePage(30, 164, 36, 180, UnaryOperator.identity());
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
     * Through the public partitioner, the option moves an authored sidebar before its one-line main row under a
     * paragraph layout only; LINES output and the default are unchanged, and option copies retain the selection.
     * @throws Exception if source writing or extraction fails
     */
    @Test
    void publicOptionReachesParagraphLayoutsOnly() throws Exception {
        final byte[] bytes = authoredTie();
        final var columns = PdfOptions.defaults().withTextLayout(PdfOptions.TextLayout.COLUMN_PARAGRAPHS);
        final var blocks = extract(bytes, columns.withSideStreams(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS));
        final var ties = extract(bytes, columns.withSideStreams(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_TIES));
        final var blockTexts = texts(blocks);
        final var tieTexts = texts(ties);
        final int side = blockTexts.indexOf(
                blockTexts.stream().filter(text -> text.startsWith("Sidebar note line a")).findFirst().orElseThrow());
        assertEquals("Main row two", blockTexts.get(side - 1));
        assertEquals(blockTexts.get(side), tieTexts.get(side - 1));
        assertEquals("Main row two", tieTexts.get(side));
        assertEquals(POLICY, ties.get(side - 1).metadata().get("pdf_reading_order"));
        assertEquals(blocks.size(), ties.size());
        assertEquals(sorted(blocks), sorted(ties));
        final var lines = PdfOptions.defaults();
        assertEquals(texts(extract(bytes, lines)),
                texts(extract(bytes, lines.withSideStreams(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_TIES))));
        assertEquals(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_TIES,
                columns.withSideStreams(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_TIES)
                        .withInlineScripts(PdfOptions.InlineScripts.NEARBY_SMALL_GLYPHS)
                        .withTextLayout(PdfOptions.TextLayout.COLUMN_PARAGRAPHS_COMPOUND_WRAPS)
                        .sideStreams());
    }

    /**
     * Asserts that the new policy gives exactly the existing policy's output.
     * @param page page items
     * @throws Exception if grouping fails
     */
    private static void assertUnchanged(final List<PdfLayoutItem> page) throws Exception {
        final var blocks = order(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS).apply(page);
        assertTrue(blocks.stream().anyMatch(item -> item.text().equals(SIDE_BLOCK)), "The fixture has a side block");
        assertEquals(blocks, order(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_TIES).apply(page));
    }

    /**
     * Asserts that the new output holds the same element multiset, the same non-side sequence and the same source
     * characters as the existing output.
     * @param blocks existing policy output
     * @param ties new policy output
     */
    private static void assertPreserved(final List<PdfLayoutItem> blocks, final List<PdfLayoutItem> ties) {
        assertEquals(blocks.size(), ties.size());
        final var expected = new HashMap<PdfLayoutItem, Integer>();
        blocks.forEach(item -> expected.merge(item, 1, Integer::sum));
        final var actual = new HashMap<PdfLayoutItem, Integer>();
        ties.forEach(item -> actual.merge(item, 1, Integer::sum));
        assertEquals(expected, actual, "The same elements, none lost, duplicated or changed");
        final var nonSide =
                blocks.stream().filter(item -> !POLICY.equals(item.metadata().get("pdf_reading_order"))).toList();
        assertEquals(nonSide,
                ties.stream().filter(item -> !POLICY.equals(item.metadata().get("pdf_reading_order"))).toList());
        assertEquals(sortedCharacters(blocks), sortedCharacters(ties));
    }

    /**
     * Counts sidebar elements whose position differs between two outputs.
     * @param blocks existing policy output
     * @param ties new policy output
     * @return moved sidebar elements
     */
    private static int swaps(final List<PdfLayoutItem> blocks, final List<PdfLayoutItem> ties) {
        int moved = 0;
        for (int index = 0; index < blocks.size(); index++) {
            if (POLICY.equals(blocks.get(index).metadata().get("pdf_reading_order"))
                    && !blocks.get(index).equals(ties.get(index))) {
                moved++;
            }
        }
        return moved;
    }

    /**
     * Builds a main column of fourteen lines and a ten-line smaller sidebar whose first line starts at a given top.
     * Row two of the main column (top 160, bottom 166) is passed through a replacement function.
     * @param mainPitch main line pitch; 30 keeps main lines apart, 15 joins them
     * @param sideTop first sidebar line top
     * @param sideLeft sidebar left edge
     * @param sideRight sidebar right edge
     * @param rowTwo replacement for the main item at top 160
     * @return page items
     */
    private static List<PdfLayoutItem> tiePage(final float mainPitch, final float sideTop, final float sideLeft,
            final float sideRight, final UnaryOperator<PdfLayoutItem> rowTwo) {
        final var page = new ArrayList<PdfLayoutItem>();
        final int rows = mainPitch == 30 ? 14 : 24;
        for (int index = 0; index < rows; index++) {
            final float top = mainPitch == 30 ? 100 + index * 30 : 100 + index * 15;
            final var item = line("main " + index, 198, top, 560, 6);
            page.add(top == 160 ? rowTwo.apply(item) : item);
        }
        for (int index = 0; index < 10; index++) {
            page.add(line("side " + index, sideLeft, sideTop + index * 10.5f, sideRight, 4.7f));
        }
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
     * Finds the element with an exact text.
     * @param items grouped items
     * @param text exact text
     * @return index, or -1
     */
    private static int indexOf(final List<PdfLayoutItem> items, final String text) {
        return items.stream().map(PdfLayoutItem::text).toList().indexOf(text);
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
     * Returns the sorted non-space characters of all items.
     * @param items items
     * @return sorted characters
     */
    private static String sortedCharacters(final List<PdfLayoutItem> items) {
        return items.stream()
                .map(PdfLayoutItem::text)
                .flatMapToInt(String::codePoints)
                .filter(codePoint -> !Character.isWhitespace(codePoint))
                .sorted()
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }

    /**
     * Writes an owned page: nine-point main rows 30 points apart beside a six-point sidebar of twelve lines whose
     * first line starts on the visual row of main row two.
     * @return complete owned PDF bytes
     * @throws Exception if writing fails
     */
    private static byte[] authoredTie() throws Exception {
        final var names = List.of("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
                "eleven", "twelve", "thirteen");
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(600, 800));
            document.addPage(page);
            final var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (final var content = new PDPageContentStream(document, page)) {
                for (int index = 0; index < names.size(); index++) {
                    draw(content, font, "Main row " + names.get(index), 200, 700 - index * 30, 9);
                }
                for (int index = 0; index < 12; index++) {
                    draw(content, font, "Sidebar note line " + (char) ('a' + index), 36, 639 - index * 9, 6);
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

    /**
     * Returns type, text and metadata of every element in a stable sorted order, as a whole-element multiset.
     * Element identifiers are excluded because they encode the element's sequence position.
     * @param elements elements
     * @return sorted element contents
     */
    private static List<String> sorted(final List<Element> elements) {
        return elements.stream()
                .map(element -> List.of(element.type(), element.text(), element.metadata()).toString())
                .sorted()
                .toList();
    }
}
