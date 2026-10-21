package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.core.ElementType;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Independent authored source rules and positions for the explicit segmented-row contract. */
class SegmentedRowsPdfTableDetectorTest {
    /** Observed source column partitions; no reference positions are supplied to extraction. */
    private static final float[] XS = {10, 110, 170, 220};

    /** Source-supported row spans retain each glyph exactly once and make no semantic header declaration. */
    @Test
    void segmentedRulesDefineColumnLocalRectangularSpans() throws Exception {
        final var glyphs = glyphs(0);
        final var tables = detector(TableLimits.defaults()).detect(glyphs, rules(0), List.of(), new PdfTableWork());
        assertEquals(1, tables.size());
        final var table = tables.getFirst();
        assertEquals(5, table.rows().size());
        assertEquals(3, table.columns());
        assertEquals(0, table.headerRows());
        assertEquals(List.of(), table.numericColumns());
        assertEquals(13, table.rows().stream().mapToInt(List::size).sum());
        assertEquals(2, table.rows().get(1).get(1).rowSpan());
        assertEquals("Junior", table.rows().get(1).get(1).text());
        assertEquals(1, table.rows().get(2).size());
        assertEquals(glyphs.size(), table.members().size());
        assertEquals(glyphs.size(), table.members().stream().distinct().count());
    }

    /** Bad paint, empty anchors and original source crossings reject whole maximal components without claims. */
    @Test
    void inconsistentPaintAndTextNeverProducePartialOwnership() throws Exception {
        for (final String variant : List.of("overlap", "endpoint", "missing-first", "missing-bottom", "empty",
                     "crossing", "angled", "edge-crossing", "outside-page", "missing-top")) {
            final var segments = new ArrayList<>(rules(0));
            final var glyphs = new ArrayList<>(glyphs(0));
            switch (variant) {
            case "overlap" -> segments.add(new PdfPageGraphics.Segment(10, 60, 110, 60));
            case "endpoint" -> {
                segments.removeIf(s -> s.y1() == 60);
                segments.add(new PdfPageGraphics.Segment(10, 60, 130, 60));
            }
            case "missing-first" -> {
                segments.removeIf(s -> s.y1() == 60);
                segments.add(new PdfPageGraphics.Segment(110, 60, 170, 60));
            }
            case "missing-bottom" -> segments.removeIf(s -> s.y1() == 120);
            case "missing-top" -> segments.removeIf(s -> s.y1() == 20);
            case "outside-page" -> {
                segments.replaceAll(s -> new PdfPageGraphics.Segment(s.x1() - 30, s.y1(), s.x2() - 30, s.y2()));
                glyphs.replaceAll(g
                        -> new PdfPageText.Glyph(
                                g.text(), g.x() - 30, g.y(), g.width(), g.height(), g.space(), g.direction()));
            }
            case "empty" -> glyphs.removeIf(g -> g.text().equals("Junior"));
            case "crossing" -> glyphs.add(new PdfPageText.Glyph("X", 108, 55, 8, 6, 3, 0));
            case "angled" -> glyphs.set(0, new PdfPageText.Glyph("H1", 15, 32, 10, 6, 3, 90));
            case "edge-crossing" -> glyphs.add(new PdfPageText.Glyph("X", 5, 55, 8, 6, 3, 0));
            default -> throw new AssertionError(variant);
            }
            assertTrue(
                    detector(TableLimits.defaults()).detect(glyphs, segments, List.of(), new PdfTableWork()).isEmpty(),
                    variant);
        }
        final var vertical = new ArrayList<>(rules(0));
        vertical.add(new PdfPageGraphics.Segment(50, 45, 50, 75));
        assertTrue(
                detector(TableLimits.defaults()).detect(glyphs(0), vertical, List.of(), new PdfTableWork()).isEmpty());
    }

    /** Far disjoint components remain independent; caption positions stay outside candidate ownership. */
    @Test
    void disjointComponentsRetainUnclaimedSourcePositions() throws Exception {
        final var segments = new ArrayList<>(rules(0));
        segments.addAll(rules(220));
        final var glyphs = new ArrayList<>(glyphs(0));
        glyphs.addAll(glyphs(220));
        glyphs.add(new PdfPageText.Glyph("Outside", 15, 10, 30, 6, 3, 0));
        final var tables = detector(TableLimits.defaults()).detect(glyphs, segments, List.of(), new PdfTableWork());
        assertEquals(2, tables.size());
        assertEquals(glyphs.size() - 1, tables.stream().mapToInt(t -> t.members().size()).sum());
    }

    /** Caller ceilings and cancellation remain typed before an accepted table is returned. */
    @Test
    void sourceBudgetsAndCancellationRemainExplicit() throws Exception {
        for (final var limits : List.of(new TableLimits(4, 10, 100, 100), new TableLimits(10, 2, 100, 100),
                     new TableLimits(10, 10, 14, 100), new TableLimits(10, 10, 100, 5))) {
            assertEquals(PartitionException.Code.TABLE_LIMIT,
                    assertThrows(PartitionException.class,
                            () -> detector(limits).detect(glyphs(0), rules(0), List.of(), new PdfTableWork()))
                            .code());
        }
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.io.InterruptedIOException.class,
                    () -> detector(TableLimits.defaults()).detect(glyphs(0), rules(0), List.of(), new PdfTableWork()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        final var exhausted = new PdfTableWork();
        for (int index = 0; index < 2_000_000; index++) {
            exhausted.step();
        }
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class,
                        () -> detector(TableLimits.defaults()).detect(glyphs(0), rules(0), List.of(), exhausted))
                        .code());
    }

    /** Multiline source text may occupy a real inferred row span, while raster/rotation ambiguity rejects whole. */
    @Test
    void multilineSpansAndExplicitGeometryBarriersRemainDistinct() throws Exception {
        final var glyphs = new ArrayList<>(glyphs(0));
        glyphs.add(new PdfPageText.Glyph("Group", 115, 72, 20, 6, 3, 0));
        final var table =
                detector(TableLimits.defaults()).detect(glyphs, rules(0), List.of(), new PdfTableWork()).getFirst();
        assertEquals("Junior Group", table.rows().get(1).get(1).text());
        assertTrue(detector(TableLimits.defaults())
                        .detect(glyphs, rules(0), List.of(new PdfPageGraphics.Image(50, 50, 8, 8, Map.of())),
                                new PdfTableWork())
                        .isEmpty());
        assertEquals(1, detector(TableLimits.defaults())
                                .detect(glyphs, rules(0), List.of(new PdfPageGraphics.Image(250, 250, 8, 8, Map.of())),
                                        new PdfTableWork())
                                .size());
        final var page = new PDPage(new PDRectangle(300, 500));
        page.setRotation(90);
        assertTrue(new SegmentedRowsPdfTableDetector(TableLimits.defaults(), new PdfPageGeometry(page))
                        .detect(glyphs, rules(0), List.of(), new PdfTableWork())
                        .isEmpty());
    }

    /** Public extraction emits real rowspan HTML/geometry, keeps ordinary source text and leaves old modes intact. */
    @Test
    void publicFactoriesSelectTheExplicitStrategyAtRuntime() throws Exception {
        final var bytes = source();
        final var options = PdfOptions.defaults().withTableCellCoordinates(true).withTableDetection(
                PdfOptions.TableDetection.RULED_AND_SEGMENTED_ROWS);
        final var result = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options))))
                                   .partition(DocumentSource.bytes(bytes, "source.pdf"));
        final var table =
                result.elements().stream().filter(e -> e.type() == ElementType.Table).findFirst().orElseThrow();
        assertEquals("segmented_rule_text_heuristic", table.metadata().get("pdf_table_method"));
        assertTrue(table.metadata().get("text_as_html").toString().contains("rowspan=\"2\""));
        final var cells = (Map<?, ?>) table.metadata().get("pdf_table_cells");
        assertEquals(13, ((List<?>) cells.get("cells")).size());
        assertEquals(1, result.elements().stream().filter(e -> e.text().equals("Outside")).count());
        final var old = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options.withTableDetection(
                                                PdfOptions.TableDetection.RULED_AND_PARTIAL_TEXT)))))
                                .partition(DocumentSource.bytes(bytes, "source.pdf"));
        assertTrue(old.elements().stream().noneMatch(e -> e.type() == ElementType.Table));
        final var calls = new AtomicInteger();
        final PdfTableLayoutProvider overlapping = image -> {
            calls.incrementAndGet();
            final var box = new PdfTableLayoutProvider.Box(5, 10, 250, 150);
            return List.of(new PdfTableLayoutProvider.Table(
                    1, 1, box, List.of(new PdfTableLayoutProvider.Anchor(0, 0, 1, 1, box)), "overlap"));
        };
        final var failure = assertThrows(PartitionException.class,
                ()
                        -> DocWeave
                                .create(PartitionerRegistry.of(List.of(PdfPartitioner.createWithNativeTablesFirst(
                                        options, new PdfNativeLayoutOptions(72, 500_000, 13), overlapping))))
                                .partition(DocumentSource.bytes(bytes, "source.pdf")));
        assertEquals(PartitionException.Code.TABLE_LIMIT, failure.code());
        assertEquals(1, calls.get());
    }

    /**
     * Creates one reusable source-only service with an upright source frame.
     * @param limits caller structured limits
     * @return hidden interface strategy
     * @throws PartitionException if the authored frame is unsupported
     */
    private static PdfNativeTableDetector detector(final TableLimits limits) throws PartitionException {
        return new SegmentedRowsPdfTableDetector(limits, new PdfPageGeometry(new PDPage(new PDRectangle(300, 500))));
    }

    /**
     * Defines complete source paint, with exactly one partial divider inside the two-row body group.
     * @param offset vertical offset in displayed source units
     * @return independent original segments
     */
    private static List<PdfPageGraphics.Segment> rules(final float offset) {
        final var result = new ArrayList<PdfPageGraphics.Segment>();
        for (final float y : new float[] {20, 40, 60, 80, 100, 120}) {
            final int columns = y == 60 ? 1 : 3;
            for (int column = 0; column < columns; column++) {
                result.add(new PdfPageGraphics.Segment(XS[column], y + offset, XS[column + 1], y + offset));
            }
        }
        return result;
    }

    /**
     * Authors native positions without using detector output as reference truth.
     * @param offset source vertical offset
     * @return complete original nonblank source positions
     */
    private static List<PdfPageText.Glyph> glyphs(final float offset) {
        final String[][] values = {
                {"H1", "H2", "H3"}, {"A", "Junior", "10"}, {"B"}, {"C", "Senior", "20"}, {"D", "Other", "30"}};
        final var glyphs = new ArrayList<PdfPageText.Glyph>();
        for (int row = 0; row < values.length; row++) {
            for (int column = 0; column < values[row].length; column++) {
                final String text = values[row][column];
                glyphs.add(new PdfPageText.Glyph(
                        text, XS[column] + 5, 32 + row * 20 + offset, text.length() * 4, 6, 3, 0));
            }
        }
        return glyphs;
    }

    /**
     * Serializes the same independently authored source through ordinary PDF content operators.
     * @return original untagged PDF bytes
     * @throws Exception if source authoring fails
     */
    private static byte[] source() throws Exception {
        try (final var document = new PDDocument(); final var bytes = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(300, 500));
            document.addPage(page);
            try (final var stream = new PDPageContentStream(document, page)) {
                for (final var segment : rules(0)) {
                    stream.moveTo(segment.x1(), 500 - segment.y1());
                    stream.lineTo(segment.x2(), 500 - segment.y2());
                }
                stream.stroke();
                final var positions = new ArrayList<>(glyphs(0));
                positions.add(new PdfPageText.Glyph("Outside", 15, 10, 30, 6, 3, 0));
                for (final var glyph : positions) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                    stream.newLineAtOffset(glyph.x(), 500 - glyph.y());
                    stream.showText(glyph.text());
                    stream.endText();
                }
            }
            document.save(bytes);
            return bytes.toByteArray();
        }
    }
}
