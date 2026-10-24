package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.core.ElementType;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Owned native pale-row paint, numeric support, transactional ownership and ambiguity controls. */
class ShadedRowsPdfTableTest {
    /** Creates isolated source fixtures. */
    ShadedRowsPdfTableTest() {
    }

    /**
     * A whole supported table owns every original member once while retaining outside text.
     * @throws Exception if owned detection fails
     */
    @Test
    void repeatedPaintAndNumericRulesOwnCompleteRows() throws Exception {
        final var glyphs = glyphs();
        final var tables =
                detector(TableLimits.defaults(), bands()).detect(glyphs, rules(), List.of(), new PdfTableWork());
        assertEquals(1, tables.size());
        final var table = tables.getFirst();
        assertEquals(3, table.columns());
        assertEquals(1, table.headerRows());
        assertEquals(9, table.rows().size());
        assertEquals(List.of(1, 2), table.numericColumns());
        assertEquals(List.of("", "2026", "2025"),
                table.rows().getFirst().stream().map(PdfInferredTable.Cell::text).toList());
        assertEquals(
                List.of("section", "", ""), table.rows().get(1).stream().map(PdfInferredTable.Cell::text).toList());
        assertEquals(glyphs.size() - 1, table.members().size());
        assertEquals(table.members().size(), table.members().stream().distinct().count());
        assertFalse(table.members().contains(glyphs.size() - 1));
        assertThrows(UnsupportedOperationException.class, () -> table.rows().clear());
    }

    /**
     * Whole small markers retain their source ownership while earlier table policies keep their projection.
     * @throws Exception if owned detection fails
     */
    @Test
    void completeScriptMarkersKeepOriginalTableOwnership() throws Exception {
        final var source = new ArrayList<>(glyphs());
        source.remove(3);
        for (int index = 0; index < 5; index++) {
            source.add(new PdfPageText.Glyph("label".substring(index, index + 1), 24 + index * 5, 107, 5, 6, 3, 0));
        }
        source.addAll(List.of(new PdfPageText.Glyph("(", 52, 104.5f, 2, 3.5f, 2, 0),
                new PdfPageText.Glyph("2", 54, 104.5f, 3, 3.5f, 2, 0),
                new PdfPageText.Glyph(")", 57, 104.5f, 2, 3.5f, 2, 0)));
        final var geometry = new PdfPageGeometry(new PDPage(new PDRectangle(400, 400)));
        final var earlier = new ShadedRowsPdfTableDetector(TableLimits.defaults(), geometry, bands(), true)
                                    .detect(source, rules(), List.of(), new PdfTableWork())
                                    .getFirst();
        final var grouped = new ShadedRowsPdfTableDetector(TableLimits.defaults(), geometry, bands(), true, true)
                                    .detect(source, rules(), List.of(), new PdfTableWork())
                                    .getFirst();
        assertEquals("label (2)", grouped.rows().get(2).getFirst().text());
        assertNotEquals(earlier.rows().get(2).getFirst().text(), grouped.rows().get(2).getFirst().text());
        assertEquals(earlier.members(), grouped.members());
        assertEquals(earlier.frame(), grouped.frame());
        assertEquals(earlier.rows().get(2).getFirst().bounds(), grouped.rows().get(2).getFirst().bounds());
    }

    /**
     * A clipped top line supported by both source header rules becomes complete without consuming outside text.
     * @throws Exception if owned detection fails
     */
    @Test
    void clippedTopHeaderCompletesAcrossIndependentSpans() throws Exception {
        final var source = new ArrayList<>(glyphs());
        source.add(new PdfPageText.Glyph("Q1", 200, 58, 12, 6, 3, 0));
        source.add(new PdfPageText.Glyph("Q2", 260, 58, 12, 6, 3, 0));
        final var support = new ArrayList<>(rules());
        support.add(new PdfPageGraphics.Segment(180, 70, 235, 70));
        support.add(new PdfPageGraphics.Segment(240, 70, 300, 70));
        final var table = detector(TableLimits.defaults(), bands())
                                  .detect(source, support, List.of(), new PdfTableWork())
                                  .getFirst();
        assertEquals(52, table.frame().top());
        assertEquals(2, table.headerRows());
        assertEquals(
                List.of("", "Q1", "Q2"), table.rows().getFirst().stream().map(PdfInferredTable.Cell::text).toList());
        assertEquals(source.size() - 1, table.members().size());
        assertFalse(table.members().contains(glyphs().size() - 1));
    }

    /**
     * Complete repeated header spans append their own lines without overlapping another anchor.
     * @throws Exception if owned header detection fails
     */
    @Test
    void multilineHeaderCanRepeatTheSameCompleteSpan() throws Exception {
        final var source = new ArrayList<>(glyphs());
        source.removeFirst();
        source.removeFirst();
        source.add(new PdfPageText.Glyph("Quarter", 210, 73, 60, 6, 3, 0));
        source.add(new PdfPageText.Glyph("December", 210, 83, 60, 6, 3, 0));
        final var table = detector(TableLimits.defaults(), bands())
                                  .detect(source, rules(), List.of(), new PdfTableWork())
                                  .getFirst();
        assertEquals(2, table.rows().getFirst().get(1).columnSpan());
        assertEquals("Quarter December", table.rows().getFirst().get(1).text());
        assertEquals(2, table.rows().getFirst().size());
        assertEquals(source.size() - 1, table.members().size());
    }

    /**
     * No numeric support, incomplete rows, overlapping paint, raster and vertical barriers fall back whole.
     * @throws Exception if owned controls fail
     */
    @Test
    void unsupportedEvidenceNeverConsumesPartialText() throws Exception {
        final var detector = detector(TableLimits.defaults(), bands());
        assertTrue(detector.detect(glyphs(), List.of(), List.of(), new PdfTableWork()).isEmpty());
        final var missing = new ArrayList<>(glyphs());
        missing.remove(4);
        assertTrue(detector.detect(missing, rules(), List.of(), new PdfTableWork()).isEmpty());
        final var overlap = new ArrayList<>(bands());
        overlap.add(new FilledPdfRowBand(new PdfLayoutItem.Bounds(20, 105, 300, 115), 0xeeeeee));
        assertTrue(detector(TableLimits.defaults(), overlap)
                        .detect(glyphs(), rules(), List.of(), new PdfTableWork())
                        .isEmpty());
        final var image = new PdfPageGraphics.Image(30, 105, 8, 8, Map.of());
        assertTrue(detector.detect(glyphs(), rules(), List.of(image), new PdfTableWork()).isEmpty());
        final var vertical = new ArrayList<>(rules());
        vertical.add(new PdfPageGraphics.Segment(150, 100, 150, 170));
        assertTrue(detector.detect(glyphs(), vertical, List.of(), new PdfTableWork()).isEmpty());
        final var croppedPaint =
                bands().stream()
                        .map(b
                                -> new FilledPdfRowBand(
                                        new PdfLayoutItem.Bounds(b.bounds().left(), b.bounds().top() - 90,
                                                b.bounds().right(), b.bounds().bottom() - 90),
                                        b.rgb()))
                        .toList();
        final var croppedRules =
                rules().stream()
                        .map(s -> new PdfPageGraphics.Segment(s.x1(), s.y1() - 90, s.x2(), s.y2() - 90))
                        .toList();
        final var croppedText = glyphs().stream()
                                        .map(g
                                                -> new PdfPageText.Glyph(g.text(), g.x(), g.y() - 90, g.width(),
                                                        g.height(), g.space(), g.direction()))
                                        .toList();
        assertTrue(detector(TableLimits.defaults(), croppedPaint)
                        .detect(croppedText, croppedRules, List.of(), new PdfTableWork())
                        .isEmpty());
    }

    /**
     * Repeated text-like row paint without three separate numeric rule ordinates is insufficient.
     * @throws Exception if owned detection fails
     */
    @Test
    void doubleUnderlinesCannotManufactureRepeatedSupport() throws Exception {
        final var rules = new ArrayList<PdfPageGraphics.Segment>();
        rules.add(new PdfPageGraphics.Segment(180, 90, 300, 90));
        for (final int y : List.of(110, 111, 112)) {
            rules.add(new PdfPageGraphics.Segment(180, y, 235, y));
            rules.add(new PdfPageGraphics.Segment(240, y, 300, y));
        }
        assertTrue(detector(TableLimits.defaults(), bands())
                        .detect(glyphs(), rules, List.of(), new PdfTableWork())
                        .isEmpty());
    }

    /**
     * Per-cell pale paints and contained overpaint infer one row; large gaps and mismatched colors do not.
     * @throws Exception if owned paint grouping fails
     */
    @Test
    void perCellBackgroundsAndContainedDuplicatePaintKeepOneRow() throws Exception {
        final var cells = new ArrayList<FilledPdfRowBand>();
        for (final var band : bands()) {
            final float top = band.bounds().top();
            cells.add(new FilledPdfRowBand(new PdfLayoutItem.Bounds(20, top, 180, top + 10), 0xeeeeee));
            cells.add(new FilledPdfRowBand(new PdfLayoutItem.Bounds(22, top, 178, top + 10), 0xeeeeee));
            cells.add(new FilledPdfRowBand(new PdfLayoutItem.Bounds(180, top, 235, top + 10), 0xeeeeee));
            cells.add(new FilledPdfRowBand(new PdfLayoutItem.Bounds(238, top, 300, top + 10), 0xeeeeee));
            cells.add(new FilledPdfRowBand(new PdfLayoutItem.Bounds(20, top + 2, 300, top + 8), 0xeeeeee));
        }
        assertEquals(detector(TableLimits.defaults(), bands()).detect(glyphs(), rules(), List.of(), new PdfTableWork()),
                detector(TableLimits.defaults(), cells).detect(glyphs(), rules(), List.of(), new PdfTableWork()));
        final var gap =
                cells.stream()
                        .map(b
                                -> b.bounds().left() == 238
                                           ? new FilledPdfRowBand(new PdfLayoutItem.Bounds(250, b.bounds().top(), 300,
                                                                          b.bounds().bottom()),
                                                     b.rgb())
                                           : b)
                        .toList();
        assertTrue(detector(TableLimits.defaults(), gap)
                        .detect(glyphs(), rules(), List.of(), new PdfTableWork())
                        .isEmpty());
        final var colors = cells.stream()
                                   .map(b -> b.bounds().left() == 238 ? new FilledPdfRowBand(b.bounds(), 0xddeeff) : b)
                                   .toList();
        assertTrue(detector(TableLimits.defaults(), colors)
                        .detect(glyphs(), rules(), List.of(), new PdfTableWork())
                        .isEmpty());
    }

    /**
     * Boundary-crossing blank padding remains unowned while visible crossing content rejects the candidate.
     * @throws Exception if owned ownership checks fail
     */
    @Test
    void boundaryPaddingCannotMoveInferredCellEdges() throws Exception {
        final var source = new ArrayList<>(glyphs());
        source.add(new PdfPageText.Glyph(" ", 298.6f, 107, 2.2f, 6, 3, 0));
        source.add(new PdfPageText.Glyph(" ", 179, 107, 4, 6, 3, 0));
        final var table = detector(TableLimits.defaults(), bands())
                                  .detect(source, rules(), List.of(), new PdfTableWork())
                                  .getFirst();
        assertFalse(table.members().contains(source.size() - 1));
        assertFalse(table.members().contains(source.size() - 2));
        source.set(source.size() - 2, new PdfPageText.Glyph("X", 298.6f, 107, 2.2f, 6, 3, 0));
        assertTrue(detector(TableLimits.defaults(), bands())
                        .detect(source, rules(), List.of(), new PdfTableWork())
                        .isEmpty());
    }

    /**
     * Native header padding cannot manufacture a cross-column title span or consume another header.
     * @throws Exception if owned header grouping fails
     */
    @Test
    void headerSpansFollowNonblankPositionsRatherThanPadding() throws Exception {
        final var source = new ArrayList<>(glyphs());
        source.add(new PdfPageText.Glyph(" ", 236, 82, 3, 6, 3, 0));
        final var table = detector(TableLimits.defaults(), bands())
                                  .detect(source, rules(), List.of(), new PdfTableWork())
                                  .getFirst();
        assertEquals(List.of("", "2026", "2025"),
                table.rows().getFirst().stream().map(PdfInferredTable.Cell::text).toList());
        assertTrue(table.rows().getFirst().stream().allMatch(cell -> cell.columnSpan() == 1));
    }

    /**
     * Label-column-only section paint and a lower rule touching its edge cannot manufacture another header.
     * @throws Exception if owned row inference fails
     */
    @Test
    void labelOnlyRowPaintKeepsSectionAndOpeningDataSeparate() throws Exception {
        final var paint = new ArrayList<>(bands());
        paint.add(new FilledPdfRowBand(new PdfLayoutItem.Bounds(20, 90.5f, 180, 100), 0xeeeeee));
        final var positions = new ArrayList<>(glyphs());
        positions.set(0, new PdfPageText.Glyph("2026", 210, 76, 20, 6, 3, 0));
        positions.set(1, new PdfPageText.Glyph("2025", 270, 76, 20, 6, 3, 0));
        positions.add(new PdfPageText.Glyph("Years", 230, 66, 20, 6, 3, 0));
        positions.add(new PdfPageText.Glyph("opening", 24, 87, 35, 6, 3, 0));
        positions.add(new PdfPageText.Glyph("100", 210, 87, 18, 6, 3, 0));
        positions.add(new PdfPageText.Glyph("200", 270, 87, 18, 6, 3, 0));
        final var paintRules = new ArrayList<>(rules());
        paintRules.add(new PdfPageGraphics.Segment(180, 70, 300, 70));
        paintRules.add(new PdfPageGraphics.Segment(180, 80, 300, 80));
        final var table = detector(TableLimits.defaults(), paint)
                                  .detect(positions, paintRules, List.of(), new PdfTableWork())
                                  .getFirst();
        assertEquals(2, table.headerRows());
        assertEquals(List.of("opening", "100", "200"),
                table.rows().get(2).stream().map(PdfInferredTable.Cell::text).toList());
        assertEquals(
                List.of("section", "", ""), table.rows().get(3).stream().map(PdfInferredTable.Cell::text).toList());
    }

    /**
     * Multilevel headers retain inferred spans; multiline labels and original accounting syntax stay intact.
     * @throws Exception if owned geometry checks fail
     */
    @Test
    void headerSpansWrappedLabelsAndAccountingTextRemainOriginal() throws Exception {
        final var rules = new ArrayList<>(rules());
        rules.add(new PdfPageGraphics.Segment(180, 70, 300, 70));
        final var positions = new ArrayList<>(glyphs());
        positions.add(new PdfPageText.Glyph("Years", 230, 66, 20, 6, 3, 0));
        positions.set(3, new PdfPageText.Glyph("wrapped", 24, 104, 32, 4, 3, 0));
        positions.add(new PdfPageText.Glyph("label", 24, 109, 24, 4, 3, 0));
        positions.set(4, new PdfPageText.Glyph("$ (100)", 208, 107, 24, 6, 3, 0));
        final var table = detector(TableLimits.defaults(), bands())
                                  .detect(positions, rules, List.of(), new PdfTableWork())
                                  .getFirst();
        assertEquals(2, table.headerRows());
        final var heading = table.rows().getFirst().get(1);
        assertEquals("Years", heading.text());
        assertEquals(2, heading.columnSpan());
        assertEquals("wrapped label", table.rows().get(3).getFirst().text());
        assertEquals("$ (100)", table.rows().get(3).get(1).text());
    }

    /**
     * Exact row, column, slot and character bounds remain typed rather than yielding partial output.
     * @throws Exception if controls fail
     */
    @Test
    void tableLimitsAndCancellationAreEnforced() throws Exception {
        for (final var limits : List.of(new TableLimits(3, 10, 100, 100), new TableLimits(20, 2, 100, 100),
                     new TableLimits(20, 10, 10, 100), new TableLimits(20, 10, 100, 2))) {
            assertEquals(PartitionException.Code.TABLE_LIMIT,
                    assertThrows(PartitionException.class,
                            () -> detector(limits, bands()).detect(glyphs(), rules(), List.of(), new PdfTableWork()))
                            .code());
        }
        final var exhausted = new PdfTableWork();
        for (int index = 0; index < 2_000_000; index++) {
            exhausted.step();
        }
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class,
                        () -> detector(TableLimits.defaults(), bands()).detect(glyphs(), rules(), List.of(), exhausted))
                        .code());
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class,
                    ()
                            -> detector(TableLimits.defaults(), bands())
                                    .detect(glyphs(), rules(), List.of(), new PdfTableWork()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            // This test owns the interruption and clears it before the runner reuses the thread.
            Thread.interrupted();
        }
    }

    /**
     * White/dark rectangles and disconnected or thin paint remain outside row-band evidence.
     */
    @Test
    void paintedBandEligibilityDoesNotPromoteBackgroundsToBorders() {
        final var rectangle = rectangle(20, 100, 300, 110);
        assertNotNull(FilledPdfRowBand.from(rectangle, 0xeeeeee));
        assertNull(FilledPdfRule.centerline(rectangle));
        assertNull(FilledPdfRowBand.from(rectangle, 0xffffff));
        assertNull(FilledPdfRowBand.from(rectangle, 0x111111));
        assertNull(FilledPdfRowBand.from(rectangle(20, 100, 300, 101), 0xeeeeee));
        final var broken = new ArrayList<>(rectangle);
        broken.set(0, new PdfPageGraphics.Segment(21, 100, 300, 100));
        assertNull(FilledPdfRowBand.from(broken, 0xeeeeee));
    }

    /**
     * Original untagged PDF paint activates only the explicit public policy, preserving unrelated text.
     * @throws Exception if PDF creation or public extraction fails
     */
    @Test
    void publicPolicyExtractsActualPaintedRowsAndKeepsDefaults() throws Exception {
        for (final var mode : List.of(PdfOptions.TableDetection.RULED_AND_SHADED_ROWS,
                     PdfOptions.TableDetection.RULED_AND_SHADED_ROWS_COMPACT_PADDING,
                     PdfOptions.TableDetection.RULED_AND_SHADED_ROWS_INLINE_SCRIPTS)) {
            final boolean overlaid = mode == PdfOptions.TableDetection.RULED_AND_SHADED_ROWS_COMPACT_PADDING;
            final boolean scripts = mode == PdfOptions.TableDetection.RULED_AND_SHADED_ROWS_INLINE_SCRIPTS;
            try (final var document = new PDDocument(); final var bytes = new ByteArrayOutputStream()) {
                final var page = new PDPage(new PDRectangle(400, 400));
                document.addPage(page);
                final var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                try (final var stream = new PDPageContentStream(document, page)) {
                    stream.setNonStrokingColor(new Color(238, 238, 238));
                    for (final var band : bands()) {
                        final var box = band.bounds();
                        stream.addRect(
                                box.left(), 400 - box.bottom(), box.right() - box.left(), box.bottom() - box.top());
                        stream.fill();
                    }
                    stream.setStrokingColor(Color.BLACK);
                    for (final var rule : rules()) {
                        stream.moveTo(rule.x1(), 400 - rule.y1());
                        stream.lineTo(rule.x2(), 400 - rule.y2());
                        stream.stroke();
                    }
                    stream.setNonStrokingColor(Color.BLACK);
                    for (final var glyph : glyphs()) {
                        if (scripts && glyph.x() == 24 && glyph.y() == 107) {
                            draw(stream, font, "label", 24, 107);
                            stream.beginText();
                            stream.setFont(font, 6);
                            stream.newLineAtOffset(24 + font.getStringWidth("label") * 0.01f + 3, 296);
                            stream.showText("(2)");
                            stream.endText();
                            continue;
                        }
                        if (overlaid && glyph.x() == 210 && glyph.y() == 107) {
                            final float width = font.getStringWidth("3") * 0.01f;
                            draw(stream, font, "3", 190, 107);
                            draw(stream, font, " ", 190 + width - 0.8f, 105.5f);
                            draw(stream, font, "83,266", 190 + width - 0.1f, 107);
                            continue;
                        }
                        stream.beginText();
                        stream.setFont(font, 10);
                        stream.newLineAtOffset(glyph.x(), 400 - glyph.y());
                        stream.showText(glyph.text());
                        stream.endText();
                    }
                }
                document.save(bytes);
                final var source = DocumentSource.bytes(bytes.toByteArray(), "owned-shaded.pdf");
                final var original =
                        DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(PdfOptions.defaults()))))
                                .partition(source);
                assertTrue(original.elements().stream().noneMatch(element -> element.type() == ElementType.Table));
                final var options = PdfOptions.defaults().withTableDetection(mode).withTableCellCoordinates(true);
                final var actual = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options))))
                                           .partition(source);
                assertEquals(
                        1, actual.elements().stream().filter(element -> element.type() == ElementType.Table).count());
                assertEquals(List.of("outside"), actual.elements()
                                                         .stream()
                                                         .filter(element -> element.type() != ElementType.Table)
                                                         .map(element -> element.text())
                                                         .toList());
                final var table = actual.elements()
                                          .stream()
                                          .filter(element -> element.type() == ElementType.Table)
                                          .findFirst()
                                          .orElseThrow();
                assertEquals("shaded_row_text_heuristic", table.metadata().get("pdf_table_method"));
                assertTrue(table.metadata().get("text_as_html").toString().contains("2026"));
                assertTrue(table.metadata().containsKey("pdf_table_cells"));
                if (scripts) {
                    assertTrue(table.text().contains("label (2)"));
                    assertEquals("painted_row_bands_inline_script_runs_v1",
                            ((Map<?, ?>)table.metadata().get("pdf_table_evidence")).get("policy"));
                }
                if (overlaid) {
                    assertTrue(table.text().contains("383,266"));
                assertEquals(1, ((Map<?, ?>)table.metadata().get("pdf_table_evidence")).get("overlaid_digit_padding_glyphs"));
                final var preserved =
                        DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options.withTableDetection(
                                                PdfOptions.TableDetection.RULED_AND_SHADED_ROWS)))))
                                .partition(source);
                assertTrue(preserved.elements().stream().noneMatch(element -> element.type() == ElementType.Table));
                assertTrue(preserved.elements().stream().anyMatch(element -> element.text().contains("3 83,266")));
                }
            }
        }
    }

    /**
     * Actual tall cell paints coalesce into supported rows; isolated blocks never supply row evidence.
     * @throws Exception if owned PDF creation or public extraction fails
     */
    @Test
    void tallCellPaintIsValidatedAfterRowCoalescing() throws Exception {
        for (final boolean complete : List.of(false, true)) {
            try (final var document = new PDDocument(); final var bytes = new ByteArrayOutputStream()) {
                final var page = new PDPage(new PDRectangle(400, 400));
                document.addPage(page);
                final var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                try (final var stream = new PDPageContentStream(document, page)) {
                    stream.setNonStrokingColor(new Color(238, 238, 238));
                    for (final int top : List.of(100, 160, 220, 280)) {
                        if (complete) {
                            stream.addRect(20, 400 - top - 25, 160, 25);
                            stream.fill();
                            stream.addRect(180, 400 - top - 25, 55, 25);
                            stream.fill();
                        }
                        stream.addRect(238, 400 - top - 25, 62, 25);
                        stream.fill();
                    }
                    stream.setStrokingColor(Color.BLACK);
                    for (final var rule : rules()) {
                        final float y = rule.y1() == 90 ? 90 : 125 + (rule.y1() - 110) * 3;
                        stream.moveTo(rule.x1(), 400 - y);
                        stream.lineTo(rule.x2(), 400 - y);
                        stream.stroke();
                    }
                    stream.setNonStrokingColor(Color.BLACK);
                    for (final var glyph : glyphs()) {
                        final float y = glyph.text().equals("outside") ? 350
                                        : glyph.y() >= 107             ? 107 + (glyph.y() - 107) * 3
                                                                       : glyph.y();
                        draw(stream, font, glyph.text(), glyph.x(), y);
                    }
                }
                document.save(bytes);
                final var processor = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(
                        PdfOptions.defaults().withTableDetection(PdfOptions.TableDetection.RULED_AND_SHADED_ROWS)))));
                final var result = processor.partition(DocumentSource.bytes(bytes.toByteArray(), "tall-cells.pdf"));
                final var tables = result.elements().stream().filter(e -> e.type() == ElementType.Table).toList();
                assertEquals(complete ? 1 : 0, tables.size());
                if (complete) {
                    assertEquals(7, tables.getFirst().text().split("100", -1).length - 1);
                    assertEquals(List.of("outside"), result.elements()
                                                             .stream()
                                                             .filter(e -> e.type() != ElementType.Table)
                                                             .map(e -> e.text())
                                                             .toList());
                } else {
                    assertTrue(result.elements().stream().anyMatch(e -> e.text().contains("label")));
                }
            }
        }
    }

    /**
     * Optional padding projection preserves membership/areas and cannot bypass original character budgets.
     * @throws Exception if owned source projection checks fail
     */
    @Test
    void compactPaddingKeepsOwnershipAndOriginalCharacterLimits() throws Exception {
        final var source = new ArrayList<>(glyphs());
        source.set(4, new PdfPageText.Glyph("3", 190, 107, 6, 6, 3, 0));
        source.add(new PdfPageText.Glyph(" ", 195, 105.5f, 2, 6, 3, 0));
        source.add(new PdfPageText.Glyph("8", 195.9f, 107, 6, 6, 3, 0));
        source.add(new PdfPageText.Glyph("3,266", 201.9f, 107, 24, 6, 3, 0));
        assertTrue(detector(TableLimits.defaults(), bands())
                        .detect(source, rules(), List.of(), new PdfTableWork())
                        .isEmpty());
        final var geometry = new PdfPageGeometry(new PDPage(new PDRectangle(400, 400)));
        final var compact = new ShadedRowsPdfTableDetector(TableLimits.defaults(), geometry, bands(), true);
        final var table = compact.detect(source, rules(), List.of(), new PdfTableWork()).getFirst();
        assertEquals("383,266", table.rows().get(2).get(1).text());
        assertEquals(1, table.omittedPaddingGlyphs());
        assertTrue(table.members().contains(source.size() - 3));
        assertEquals(detector(TableLimits.defaults(), bands())
                             .detect(glyphs(), rules(), List.of(), new PdfTableWork())
                             .getFirst()
                             .frame(),
                table.frame());
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class,
                        ()
                                -> new ShadedRowsPdfTableDetector(
                                        new TableLimits(30, 10, 1000, 7), geometry, bands(), true)
                                        .detect(source, rules(), List.of(), new PdfTableWork()))
                        .code());
    }

    /**
     * Writes one owned native run with explicit original baseline coordinates.
     * @param stream owned PDF content
     * @param font owned source font
     * @param value original text
     * @param x original displayed left
     * @param baseline original displayed baseline
     * @throws Exception if source encoding fails
     */
    private static void draw(final PDPageContentStream stream, final PDType1Font font, final String value,
            final float x, final float baseline) throws Exception {
        stream.beginText();
        stream.setFont(font, 10);
        stream.newLineAtOffset(x, 400 - baseline);
        stream.showText(value);
        stream.endText();
    }

    /**
     * Creates one detector in the original upright displayed frame.
     * @param limits explicit caller output bounds
     * @param bands original source paint
     * @return hidden detector with owned page geometry
     * @throws Exception if geometry initialization fails
     */
    private static ShadedRowsPdfTableDetector detector(final TableLimits limits, final List<FilledPdfRowBand> bands)
            throws Exception {
        return new ShadedRowsPdfTableDetector(
                limits, new PdfPageGeometry(new PDPage(new PDRectangle(400, 400))), bands);
    }

    /**
     * Creates repeated original source paint.
     * @return four original alternating same-color row rectangles
     */
    private static List<FilledPdfRowBand> bands() {
        return List.of(100, 120, 140, 160)
                .stream()
                .map(y -> new FilledPdfRowBand(new PdfLayoutItem.Bounds(20, y, 300, y + 10), 0xeeeeee))
                .toList();
    }

    /**
     * Creates independent header and subtotal paint.
     * @return one original header support and three independent numeric subtotal rules
     */
    private static List<PdfPageGraphics.Segment> rules() {
        final var result = new ArrayList<PdfPageGraphics.Segment>();
        result.add(new PdfPageGraphics.Segment(180, 90, 300, 90));
        for (final int y : List.of(110, 150, 170)) {
            result.add(new PdfPageGraphics.Segment(180, y, 235, y));
            result.add(new PdfPageGraphics.Segment(240, y, 300, y));
        }
        return result;
    }

    /**
     * Creates bounded original source positions.
     * @return original native labels/values and one unrelated source position
     */
    private static List<PdfPageText.Glyph> glyphs() {
        final var result = new ArrayList<PdfPageText.Glyph>();
        result.add(new PdfPageText.Glyph("2026", 210, 82, 20, 6, 3, 0));
        result.add(new PdfPageText.Glyph("2025", 270, 82, 20, 6, 3, 0));
        result.add(new PdfPageText.Glyph("section", 24, 97, 32, 6, 3, 0));
        for (int y = 107; y <= 167; y += 10) {
            result.add(new PdfPageText.Glyph("label", 24, y, 25, 6, 3, 0));
            result.add(new PdfPageText.Glyph("100", 210, y, 18, 6, 3, 0));
            result.add(new PdfPageText.Glyph("200", 270, y, 18, 6, 3, 0));
        }
        result.add(new PdfPageText.Glyph("outside", 20, 250, 35, 6, 3, 0));
        return result;
    }

    /**
     * Creates four connected original source edges.
     * @param left original left
     * @param top original top
     * @param right original right
     * @param bottom original bottom
     * @return closed rectangle without rounded or disconnected edges
     */
    private static List<PdfPageGraphics.Segment> rectangle(
            final float left, final float top, final float right, final float bottom) {
        return List.of(new PdfPageGraphics.Segment(left, top, right, top),
                new PdfPageGraphics.Segment(right, top, right, bottom),
                new PdfPageGraphics.Segment(right, bottom, left, bottom),
                new PdfPageGraphics.Segment(left, bottom, left, top));
    }
}
