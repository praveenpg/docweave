package io.docweave.partition;

import io.docweave.core.ElementType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Owned geometry controls for the contiguous side-stream policy (reading-order contract rules R4 and R5). */
class PdfContiguousSideStreamsTest {
    /** Provenance recorded on contiguous side-stream elements. */
    private static final String CONTIGUOUS = "side_stream_contiguous_v1";

    /** Creates isolated geometry controls. */
    PdfContiguousSideStreamsTest() {
    }

    /**
     * Two blocks of one margin side stream are emitted consecutively at the first block's position; every block's
     * text and bounds, the main stream and all characters are exactly those of the block policy.
     * @throws Exception if grouping fails
     */
    @Test
    void aSideStreamIsEmittedAsOneUnitAtItsFirstBlock() throws Exception {
        final var page = sidebarPage(List.of());
        final var blocks = order(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS).apply(page);
        final var contiguous = order(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_CONTIGUOUS).apply(page);
        final var texts = contiguous.stream().map(PdfLayoutItem::text).toList();
        final int first = texts.indexOf("side a1 side a2 side a3");
        assertEquals("side b1 side b2", texts.get(first + 1), "the second block follows the first directly");
        assertEquals(blocks.stream().map(PdfLayoutItem::text).toList().indexOf("side a1 side a2 side a3"), first,
                "the unit starts where the block policy puts its first block");
        assertNotEquals(blocks.stream().map(PdfLayoutItem::text).toList(), texts,
                "the block policy interleaves the second block with the main stream");
        assertEquals(main(blocks), main(contiguous));
        assertEquals(boxes(side(blocks)), boxes(side(contiguous)));
        assertEquals(characters(page), characters(contiguous));
        for (final var item : contiguous) {
            assertEquals(item.text().startsWith("side"), CONTIGUOUS.equals(item.metadata().get("pdf_reading_order")));
        }
    }

    /**
     * An item crossing the side column between two side lines divides the stream into two units; neither moves
     * past it, so the order is the block policy's.
     * @throws Exception if grouping fails
     */
    @Test
    void aCrossingBarrierDividesTheStreamAndNothingMovesPastIt() throws Exception {
        final var page = sidebarPage(List.of(new PdfLayoutItem(ElementType.Image, "",
                new PdfLayoutItem.Bounds(30, 170, 560, 190), Map.of())));
        final var blocks = order(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS).apply(page);
        final var contiguous = order(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_CONTIGUOUS).apply(page);
        assertEquals(blocks.stream().map(PdfLayoutItem::text).toList(),
                contiguous.stream().map(PdfLayoutItem::text).toList());
        assertEquals(boxes(blocks), boxes(contiguous));
    }

    /**
     * Pages without a side stream, and accepted two-column bands, keep the block policy's output exactly.
     * @throws Exception if grouping fails
     */
    @Test
    void pagesWithoutASideStreamAndTwoColumnBandsAreUnchanged() throws Exception {
        final var plain = new ArrayList<PdfLayoutItem>();
        for (int row = 0; row < 8; row++) {
            plain.add(line("main " + row, 198, 100 + row * 15, 560, 6));
        }
        final var twoColumns = new ArrayList<PdfLayoutItem>();
        for (int row = 0; row < 6; row++) {
            twoColumns.add(line("left " + row, 40, 100 + row * 12, 240, 4.7f));
            twoColumns.add(line("right " + row, 300, 100 + row * 12, 520, 6));
        }
        for (final var page : List.of(plain, twoColumns)) {
            assertEquals(order(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS).apply(page),
                    order(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_CONTIGUOUS).apply(page));
        }
    }

    /**
     * The policy is explicit: defaults stay {@code PRESERVE} and the option copies.
     */
    @Test
    void thePolicyIsExplicit() {
        assertEquals(PdfOptions.SideStreams.PRESERVE, PdfOptions.defaults().sideStreams());
        assertEquals(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_CONTIGUOUS,
                PdfOptions.defaults().withSideStreams(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_CONTIGUOUS)
                        .sideStreams());
    }

    /**
     * Builds a main column of eleven lines and a smaller margin side stream of two blocks (three and two lines).
     * @param extra additional items
     * @return page items
     */
    private static List<PdfLayoutItem> sidebarPage(final List<PdfLayoutItem> extra) {
        final var page = new ArrayList<PdfLayoutItem>();
        for (int index = 0; index < 11; index++) {
            page.add(line("main " + index, 198, 100 + index * 15, 560, 6));
        }
        for (int index = 0; index < 3; index++) {
            page.add(line("side a" + (index + 1), 36, 132 + index * 11, 180, 4.7f));
        }
        for (int index = 0; index < 2; index++) {
            page.add(line("side b" + (index + 1), 36, 200 + index * 11, 180, 4.7f));
        }
        page.addAll(extra);
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
     * Returns the side-stream items in order.
     * @param items grouped items
     * @return items whose text starts with the side prefix
     */
    private static List<PdfLayoutItem> side(final List<PdfLayoutItem> items) {
        return items.stream().filter(item -> item.text().startsWith("side")).toList();
    }

    /**
     * Returns items' bounds in order.
     * @param items items
     * @return bounds
     */
    private static List<PdfLayoutItem.Bounds> boxes(final List<PdfLayoutItem> items) {
        return items.stream().map(PdfLayoutItem::box).toList();
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
}
