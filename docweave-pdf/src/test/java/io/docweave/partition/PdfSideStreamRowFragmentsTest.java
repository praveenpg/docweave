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
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Owned geometry controls for explicit row-tie paragraph fragments. */
class PdfSideStreamRowFragmentsTest {
    /** Text of the ten-line left sidebar block. */
    private static final String SIDE_BLOCK = "side 0 side 1 side 2 side 3 side 4 side 5 side 6 side 7 side 8 side 9";

    /** Fragment provenance value. */
    private static final String FRAGMENTS = "row_tie_fragments_v1";

    /** Creates isolated controls. */
    PdfSideStreamRowFragmentsTest() {
    }

    /**
     * A sidebar starting on the row of a joined paragraph's last line is placed between that line and the rest of
     * the paragraph. Joining the two fragments with one space restores the row-tie paragraph text and bounds, and
     * every other element is identical to the row-tie output.
     * @throws Exception if grouping fails
     */
    @Test
    void sidebarOnTheLastLineRowSplitsOffOnlyThatLine() throws Exception {
        final var page = page(164, 36, 180);
        final var ties = order(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_TIES, Set.of()).apply(page);
        final var fragments = order(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_FRAGMENTS, Set.of()).apply(page);
        final int side = indexOf(ties, SIDE_BLOCK);
        final var joined = ties.get(side - 1);
        assertEquals(5, joined.metadata().get("pdf_line_count"));
        assertEquals(ties.size() + 1, fragments.size());
        final var first = fragments.get(side - 1);
        final var second = fragments.get(side + 1);
        assertEquals(ties.get(side), fragments.get(side), "The sidebar element is unchanged");
        assertEquals("main 0 main 1 main 2 main 3", first.text());
        assertEquals("main 4", second.text());
        assertEquals(joined.text(), first.text() + " " + second.text());
        assertEquals(joined.box(), union(first.box(), second.box()));
        assertEquals(4, first.metadata().get("pdf_line_count"));
        for (final var fragment : List.of(first, second)) {
            assertEquals(FRAGMENTS, fragment.metadata().get("pdf_reading_order_fragment_policy"));
            assertEquals(0, fragment.metadata().get("pdf_paragraph_fragment_group"));
            assertEquals(2, fragment.metadata().get("pdf_paragraph_fragment_count"));
        }
        assertEquals(1, first.metadata().get("pdf_paragraph_fragment_index"));
        assertEquals(2, second.metadata().get("pdf_paragraph_fragment_index"));
        assertEquals(ties, rejoin(fragments));
    }

    /**
     * Geometry outside the tie, a boundary whose source wrap omits the joining space, and a one-line predecessor
     * (already handled by whole-element row ties) all give exactly the row-tie output.
     * @throws Exception if grouping fails
     */
    @Test
    void nonTiesHyphenWrapBoundariesAndOneLineRowsKeepRowTieOutput() throws Exception {
        for (final var page : List.of(page(167, 36, 180), page(166, 36, 180), page(164, 580, 700))) {
            assertEquals(order(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_TIES, Set.of()).apply(page),
                    order(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_FRAGMENTS, Set.of()).apply(page));
        }
        final var tied = page(164, 36, 180);
        assertEquals(order(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_TIES, Set.of(3)).apply(tied),
                order(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_FRAGMENTS, Set.of(3)).apply(tied),
                "A retained-hyphen boundary before the last line cannot be split losslessly");
        final var single = new ArrayList<PdfLayoutItem>();
        for (int index = 0; index < 14; index++) {
            single.add(line("main " + index, 198, 100 + index * 30, 560, 6));
        }
        for (int index = 0; index < 10; index++) {
            single.add(line("side " + index, 36, 164 + index * 10.5f, 180, 4.7f));
        }
        assertEquals(order(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_TIES, Set.of()).apply(single),
                order(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_FRAGMENTS, Set.of()).apply(single));
    }

    /**
     * Defaults and older policies are unchanged, plain paragraphs keep side-stream placement, and accepted
     * two-column pages keep their banded output.
     * @throws Exception if grouping fails
     */
    @Test
    void defaultsOlderPoliciesPlainParagraphsAndColumnsAreUnchanged() throws Exception {
        assertEquals(PdfOptions.SideStreams.PRESERVE, PdfOptions.defaults().sideStreams());
        final var page = page(164, 36, 180);
        final var plainBlocks = new GeometricPdfReadingOrder(
                PdfOptions.TextLayout.PARAGRAPHS, group -> Set.of(), PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS)
                                        .apply(page);
        assertEquals(plainBlocks, new GeometricPdfReadingOrder(PdfOptions.TextLayout.PARAGRAPHS,
                                          group -> Set.of(), PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_FRAGMENTS)
                                          .apply(page));
        final var columns = new ArrayList<PdfLayoutItem>();
        for (int row = 0; row < 6; row++) {
            columns.add(line("left " + row, 40, 100 + row * 12, 240, 4.7f));
            columns.add(line("right " + row, 300, 100 + row * 12, 520, 6));
        }
        assertEquals(order(PdfOptions.SideStreams.PRESERVE, Set.of()).apply(columns),
                order(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_FRAGMENTS, Set.of()).apply(columns));
    }

    /**
     * The policy shares the layout work ceiling and cancellation with historical grouping.
     * @throws Exception if limits are not enforced
     */
    @Test
    void layoutWorkCeilingAndCancellationRemainTyped() throws Exception {
        final var service = order(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_FRAGMENTS, Set.of());
        final var page = page(164, 36, 180);
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
            Thread.interrupted();
        }
    }

    /**
     * Through the public partitioner, the fragments and their provenance reach element metadata; LINES output and
     * option copies are unchanged.
     * @throws Exception if writing or extraction fails
     */
    @Test
    void publicOptionEmitsFragmentProvenance() throws Exception {
        final byte[] bytes = authored();
        final var columns = PdfOptions.defaults().withTextLayout(PdfOptions.TextLayout.COLUMN_PARAGRAPHS);
        final var ties = extract(bytes, columns.withSideStreams(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_TIES));
        final var fragments =
                extract(bytes, columns.withSideStreams(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_FRAGMENTS));
        assertEquals(ties.size() + 1, fragments.size());
        final var marked = fragments.stream()
                                   .filter(e -> FRAGMENTS.equals(e.metadata().get("pdf_reading_order_fragment_policy")))
                                   .toList();
        assertEquals(2, marked.size());
        final int first = fragments.indexOf(marked.getFirst());
        assertTrue(fragments.get(first + 1).text().startsWith("Sidebar note line a"));
        assertEquals(fragments.get(first + 2), marked.get(1));
        assertTrue(ties.stream().anyMatch(e -> e.text().equals(marked.get(0).text() + " " + marked.get(1).text())));
        final var lines = PdfOptions.defaults();
        assertEquals(extract(bytes, lines).stream().map(Element::text).toList(),
                extract(bytes, lines.withSideStreams(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_FRAGMENTS))
                        .stream()
                        .map(Element::text)
                        .toList());
        assertEquals(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_FRAGMENTS,
                columns.withSideStreams(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_FRAGMENTS)
                        .withTextLayout(PdfOptions.TextLayout.COLUMN_PARAGRAPHS_COMPOUND_WRAPS)
                        .sideStreams());
    }

    /**
     * Joins each two-fragment group back into one paragraph item and removes fragment provenance.
     * @param items fragment output
     * @return reconstructed items
     */
    private static List<PdfLayoutItem> rejoin(final List<PdfLayoutItem> items) {
        final var result = new ArrayList<PdfLayoutItem>();
        PdfLayoutItem pending = null;
        int pendingIndex = -1;
        for (final var item : items) {
            if (item.metadata().get("pdf_paragraph_fragment_index") instanceof Integer index && index == 1) {
                pending = item;
                pendingIndex = result.size();
                result.add(item);
            } else if (item.metadata().get("pdf_paragraph_fragment_index") instanceof Integer index && index == 2) {
                final var metadata = new java.util.LinkedHashMap<>(pending.metadata());
                metadata.keySet().removeIf(key
                        -> key.startsWith("pdf_paragraph_fragment") || key.equals("pdf_reading_order_fragment_policy"));
                metadata.put("pdf_line_count", (Integer) pending.metadata().getOrDefault("pdf_line_count", 1) + 1);
                result.set(pendingIndex, new PdfLayoutItem(pending.type(), pending.text() + " " + item.text(),
                                                 union(pending.box(), item.box()), metadata));
            } else {
                result.add(item);
            }
        }
        return result;
    }

    /**
     * Returns the union of two boxes.
     * @param a first box
     * @param b second box
     * @return enclosing box
     */
    private static PdfLayoutItem.Bounds union(final PdfLayoutItem.Bounds a, final PdfLayoutItem.Bounds b) {
        return new PdfLayoutItem.Bounds(Math.min(a.left(), b.left()), Math.min(a.top(), b.top()),
                Math.max(a.right(), b.right()), Math.max(a.bottom(), b.bottom()));
    }

    /**
     * Builds 24 joined main lines (pitch 15) and a ten-line smaller sidebar starting at a given top.
     * @param sideTop first sidebar line top
     * @param sideLeft sidebar left edge
     * @param sideRight sidebar right edge
     * @return page items
     */
    private static List<PdfLayoutItem> page(final float sideTop, final float sideLeft, final float sideRight) {
        final var page = new ArrayList<PdfLayoutItem>();
        for (int index = 0; index < 24; index++) {
            page.add(line("main " + index, 198, 100 + index * 15, 560, 6));
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
     * Creates the column-paragraph service with one side-stream policy and fixed retained-wrap boundaries.
     * @param policy side-stream policy
     * @param omitted boundary indices whose joining space is omitted
     * @return reading order
     */
    private static PdfReadingOrder order(final PdfOptions.SideStreams policy, final Set<Integer> omitted) {
        return new GeometricPdfReadingOrder(PdfOptions.TextLayout.COLUMN_PARAGRAPHS,
                group
                -> omitted.stream()
                        .filter(index -> index < group.size() - 1)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                policy);
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
     * Writes an owned page: nine-point main rows 30 points apart, plus one extra line 12 points above row two so
     * that rows one, one-b and two join into one paragraph, beside a six-point sidebar of twelve lines whose first
     * line starts on the row of main row two.
     * @return owned PDF bytes
     * @throws Exception if writing fails
     */
    private static byte[] authored() throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(600, 800));
            document.addPage(page);
            final var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (final var content = new PDPageContentStream(document, page)) {
                for (int index = 0; index < 14; index++) {
                    draw(content, font, "Main row " + (char) ('a' + index), 200, 700 - index * 30, 9);
                }
                draw(content, font, "Main row b continued", 200, 652, 9);
                for (int index = 0; index < 12; index++) {
                    draw(content, font, "Sidebar note line " + (char) ('a' + index), 36, 639 - index * 9, 6);
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    /**
     * Writes one run.
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
     * Extracts with explicit options.
     * @param bytes source
     * @param options options
     * @return elements
     * @throws Exception if extraction fails
     */
    private static List<Element> extract(final byte[] bytes, final PdfOptions options) throws Exception {
        return DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options))))
                .partition(DocumentSource.bytes(bytes, "fragments.pdf"))
                .elements();
    }
}
