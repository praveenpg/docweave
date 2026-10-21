package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.core.ElementType;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PdfProviderTableTest {
    @Test
    void wordAndCellOcrBothRetainDeclaredHeadersWithoutChangingHtmlOrOwnership() throws Exception {
        for (final boolean cells : List.of(false, true)) {
            dev.docweave.core.Element plain = null;
            for (final int headers : new int[] {0, 1}) {
                final var original = layout();
                final var declared = new PdfTableLayoutProvider.Table(original.rows(), original.columns(),
                        original.bounds(), original.anchors(), original.provenance(), headers);
                final PdfTableLayoutProvider provider = image -> List.of(declared);
                final var processor =
                        cells ? separateCells(cellOptions(),
                                        (image, kind)
                                                -> words(),
                                        image -> List.of(new PdfOcrProvider.Line("cell", 1, 1, 2, 2)), provider)
                              : processor(options(), TableLimits.defaults(), (image, kind) -> words(), provider);
                final var result = processor.partition(source());
                final var table = result.elements()
                                          .stream()
                                          .filter(element -> element.type() == ElementType.Table)
                                          .findFirst()
                                          .orElseThrow();
                assertEquals(List.of("before", "after"), ordinary(result));
                if (headers == 0) {
                    plain = table;
                    assertFalse(table.metadata().containsKey("pdf_table_header_rows"));
                } else {
                    assertEquals(1, table.metadata().get("pdf_table_header_rows"));
                    assertEquals("provider_declared_prefix_v1", table.metadata().get("pdf_table_header_policy"));
                    assertEquals(plain.text(), table.text());
                    assertEquals(plain.metadata().get("text_as_html"), table.metadata().get("text_as_html"));
                    assertEquals(plain.metadata().get("pdf_table_cells"), table.metadata().get("pdf_table_cells"));
                }
            }
        }
    }

    @Test
    void magnifiedCellLinesKeepPageWordsLayoutAndReportedGeometryInTheOriginalFrame() throws Exception {
        final var calls = new AtomicInteger();
        final PdfOcrProvider enlarged = new PdfOcrProvider() {
            @Override
            public List<Line> recognize(final java.awt.image.BufferedImage image) {
                throw new AssertionError("Cell purpose must be forwarded");
            }

            @Override
            public List<Line> recognize(final java.awt.image.BufferedImage image, final InputKind kind) {
                assertEquals(InputKind.TABLE_CELL, kind);
                if (calls.getAndIncrement() == 0) {
                    assertEquals(552, image.getWidth());
                    assertEquals(132, image.getHeight());
                }
                return List.of(new Line("cell", 36, 36, 120, 60));
            }
        };
        final var processor = separateCells(cellOptions(),
                (image, kind)
                        -> {
                    assertEquals(PdfOcrProvider.InputKind.PAGE, kind);
                    assertEquals(300, image.getWidth());
                    assertEquals(180, image.getHeight());
                    return words();
                },
                PdfOcrProvider.upscaled(enlarged, 3, cellOptions()),
                image -> {
                    assertEquals(300, image.getWidth());
                    return List.of(layout());
                });
        final var result = processor.partition(source());
        assertEquals(5, calls.get());
        assertEquals(List.of("before", "after"), ordinary(result));
        final var table =
                result.elements().stream().filter(e -> e.type() == ElementType.Table).findFirst().orElseThrow();
        assertEquals("cell cell cell cell cell", table.text());
        final var coordinates = (Map<?, ?>) table.metadata().get("coordinates");
        final var points = (List<?>) coordinates.get("points");
        assertEquals(List.of(20.0, 10.0), points.getFirst());
        assertEquals(List.of(180.0, 80.0), points.get(2));
    }

    @Test
    void explicitCellContextIncludesSourcePixelsClampsTableAndPreservesReportedAnchors() throws Exception {
        Object coordinates = null;
        for (final int context : List.of(0, 4)) {
            final var calls = new AtomicInteger();
            final var processor =
                    separateCells(cellOptions().withCellContextPixels(context), (image, kind) -> words(), image -> {
                        if (calls.getAndIncrement() == 0) {
                            assertEquals(184, image.getWidth(), "Horizontal context is clamped to table edges");
                            assertEquals(44 + context, image.getHeight(), "Only the interior bottom edge expands");
                            assertEquals(
                                    context == 0 ? Color.WHITE.getRGB() : Color.GREEN.getRGB(), image.getRGB(42, 33));
                            assertEquals(
                                    Color.WHITE.getRGB(), image.getRGB(10, 33), "Outside table pixels stay excluded");
                        }
                        return List.of(new PdfOcrProvider.Line("cell", 12, 12, 40, 20));
                    }, image -> List.of(layout()));
            final var result = processor.partition(contextSource());
            final var table =
                    result.elements().stream().filter(e -> e.type() == ElementType.Table).findFirst().orElseThrow();
            assertEquals(5, calls.get());
            assertEquals(List.of("before", "after"), ordinary(result));
            assertEquals("cell cell cell cell cell", table.text());
            if (context == 0) {
                coordinates = table.metadata().get("pdf_table_cells");
                assertFalse(table.metadata().containsKey("pdf_cell_ocr_context_pixels"));
            } else {
                assertEquals(coordinates, table.metadata().get("pdf_table_cells"));
                assertEquals(context, table.metadata().get("pdf_cell_ocr_context_pixels"));
                assertEquals("table_clamped_input_pixels_v1", table.metadata().get("pdf_cell_ocr_context_policy"));
            }
        }
    }

    @Test
    void expandedCellPreflightChargesContextBeforeAnyCellRecognition() throws Exception {
        final var table = new PdfTableLayoutProvider.Table(
                1, 1, box(0, 0, 300, 180), List.of(anchor(0, 0, 1, 1, 10, 10, 290, 160)), "owned-large-cell");
        final var calls = new AtomicInteger();
        final PdfOcrProvider cells = image -> {
            calls.incrementAndGet();
            return List.of(new PdfOcrProvider.Line("cell", 12, 12, 40, 20));
        };
        final var options = new PdfOcrOptions(72, 54_000, 100, 1000)
                                    .withTableDetection(PdfOcrOptions.TableDetection.PROVIDER_LAYOUT_CELLS);
        separateCells(options, (image, kind) -> words(), cells, image -> List.of(table)).partition(source());
        assertEquals(1, calls.get(), "Historical52896-pixel padded crop fits");
        calls.set(0);
        final var expanded = separateCells(
                options.withCellContextPixels(16), (image, kind) -> words(), cells, image -> List.of(table));
        assertEquals(PartitionException.Code.OCR_LIMIT,
                assertThrows(PartitionException.class, () -> expanded.partition(source())).code());
        assertEquals(0, calls.get(), "Expanded64800-pixel padded crop exceeds the same ceiling");
    }

    @Test
    void cellContextCopiesPreserveBudgetsAndFormerConstructorsRemainAnchorOnly() throws Exception {
        final var base = PdfOcrOptions.defaults();
        assertEquals(base, new PdfOcrOptions(base.dpi(), base.maxRenderPixels(), base.maxLines(), base.maxCharacters(),
                                   base.selection(), base.maxRegions(), base.tableDetection(), base.maxTableCells(),
                                   base.readingOrder()));
        final var selected = base.withCellContextPixels(4)
                                     .withSelection(PdfOcrOptions.Selection.IMAGE_REGIONS)
                                     .withReadingOrder(PdfOcrOptions.ReadingOrder.GEOMETRIC)
                                     .withTableDetection(PdfOcrOptions.TableDetection.PROVIDER_LAYOUT_CELLS);
        assertEquals(4, selected.cellContextPixels());
        assertEquals(base.maxRenderPixels(), selected.maxRenderPixels());
        assertEquals(base.maxCharacters(), selected.maxCharacters());
        assertThrows(IllegalArgumentException.class, () -> base.withCellContextPixels(-1));
        assertThrows(IllegalArgumentException.class, () -> base.withCellContextPixels(17));
        final var wordsOnly = processor(options().withCellContextPixels(4), TableLimits.defaults(),
                (image, kind) -> words(), image -> List.of(layout()))
                                      .partition(source());
        assertFalse(wordsOnly.elements().getFirst().metadata().containsKey("pdf_cell_ocr_context_pixels"));
    }

    /** Draws an interior context marker and an excluded marker outside the known provider table. */
    private static DocumentSource contextSource() throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(300, 180));
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                content.setNonStrokingColor(Color.GREEN);
                content.addRect(50, 148, 1, 1);
                content.fill();
                content.setNonStrokingColor(Color.RED);
                content.addRect(18, 148, 1, 1);
                content.fill();
            }
            document.save(output);
            return DocumentSource.bytes(output.toByteArray(), "context.pdf");
        }
    }

    @Test
    void separateCellLineStrategyRetainsActualPageWordsAndOutsideOwnership() throws Exception {
        final var pages = new AtomicInteger();
        final var cells = new AtomicInteger();
        final PdfWordOcrProvider pageWords = (image, kind) -> {
            assertEquals(PdfOcrProvider.InputKind.PAGE, kind);
            pages.incrementAndGet();
            return words();
        };
        final PdfOcrProvider cellLines = new PdfOcrProvider() {
            @Override
            public List<Line> recognize(final java.awt.image.BufferedImage image) {
                throw new AssertionError("Cell purpose must be retained");
            }

            @Override
            public List<Line> recognize(final java.awt.image.BufferedImage image, final InputKind kind) {
                assertEquals(InputKind.TABLE_CELL, kind);
                assertEquals(0xffffffff, image.getRGB(0, 0));
                final String text = List.of("New header", "A&B", "1", "", "2").get(cells.getAndIncrement());
                return text.isEmpty() ? List.of() : List.of(new Line(text, 12, 12, 40, 20));
            }
        };
        assertThrows(IllegalArgumentException.class,
                ()
                        -> PdfPartitioner.createWithCellOcr(
                                PdfOptions.defaults(), options(), pageWords, image -> List.of(layout()), cellLines));
        assertEquals(0, pages.get());
        assertEquals(0, cells.get());
        final var result =
                separateCells(cellOptions(), pageWords, cellLines, image -> List.of(layout())).partition(source());
        assertEquals(1, pages.get());
        assertEquals(5, cells.get());
        final var table =
                result.elements().stream().filter(e -> e.type() == ElementType.Table).findFirst().orElseThrow();
        assertEquals("New header A&B 1 2", table.text());
        assertTrue(table.metadata().get("text_as_html").toString().contains("<td></td>"));
        assertEquals(List.of("before", "after"), ordinary(result));
    }

    @Test
    void separateCellStrategySharesQuarterTurnFrameAndRestoresSourceBoundsOnce() throws Exception {
        for (final int correction : List.of(0, 90, 180, 270)) {
            final var cells = new AtomicInteger();
            final PdfWordOcrProvider pageWords = (image, kind) -> {
                assertEquals(PdfOcrProvider.InputKind.PAGE, kind);
                assertEquals(correction % 180 == 0 ? 300 : 180, image.getWidth());
                return words()
                        .stream()
                        .map(line
                                -> new PdfWordOcrProvider.WordLine(new PdfOcrProvider.Line(line.line().text(), 0, 0,
                                                                           image.getWidth(), image.getHeight()),
                                        line.words()))
                        .toList();
            };
            final PdfOcrProvider cellLines = image -> {
                cells.incrementAndGet();
                return List.of(new PdfOcrProvider.Line("cell", 12, 12, 40, 20));
            };
            final var service = PdfPartitioner.createOrientedWithCellOcr(
                    PdfOptions.defaults().withTableCellCoordinates(true), cellOptions().withCellContextPixels(4),
                    pageWords,
                    image
                    -> List.of(layout()),
                    cellLines,
                    image -> new PdfOrientationEstimator.Estimate(correction, true, 20, "owned-separate-engines"));
            final var result = DocWeave.create(PartitionerRegistry.of(List.of(service))).partition(source());
            final var table =
                    result.elements().stream().filter(e -> e.type() == ElementType.Table).findFirst().orElseThrow();
            assertEquals(5, cells.get());
            assertEquals("cell cell cell cell cell", table.text());
            assertEquals(correction, table.metadata().get("pdf_ocr_orientation_correction"));
            final var coordinates = (Map<?, ?>) table.metadata().get("coordinates");
            final var points = (List<?>) coordinates.get("points");
            final var expected = switch (correction) {
                case 90 -> List.of(10.0, 0.0, 80.0, 160.0);
                case 180 -> List.of(120.0, 100.0, 280.0, 170.0);
                case 270 -> List.of(220.0, 20.0, 290.0, 180.0);
                default -> List.of(20.0, 10.0, 180.0, 80.0);
            };
            final var first = (List<?>) points.getFirst();
            final var third = (List<?>) points.get(2);
            assertEquals(expected.get(0), ((Number) first.get(0)).doubleValue(), 1e-5);
            assertEquals(expected.get(1), ((Number) first.get(1)).doubleValue(), 1e-5);
            assertEquals(expected.get(2), ((Number) third.get(0)).doubleValue(), 1e-5);
            assertEquals(expected.get(3), ((Number) third.get(1)).doubleValue(), 1e-5);
        }
    }

    @Test
    void separateCellEngineRetainsSharedRawBudgetsPreflightAndFailureIdentity() throws Exception {
        final PdfWordOcrProvider pageWords = (image, kind) -> {
            assertEquals(PdfOcrProvider.InputKind.PAGE, kind);
            return words();
        };
        final var calls = new AtomicInteger();
        final PdfOcrProvider blankLine = image -> {
            calls.incrementAndGet();
            return List.of(new PdfOcrProvider.Line("", 0, 0, 1, 1));
        };
        final var small = new PdfOcrOptions(72, 100_000, 2, 1000)
                                  .withTableDetection(PdfOcrOptions.TableDetection.PROVIDER_LAYOUT_CELLS);
        assertEquals(PartitionException.Code.OCR_LIMIT,
                assertThrows(PartitionException.class,
                        ()
                                -> separateCells(small, pageWords, blankLine, image -> List.of(layout()))
                                        .partition(source()))
                        .code());
        assertEquals(1, calls.get(), "Two PAGE lines plus one raw blank CELL line share the ceiling");
        calls.set(0);
        final var whole = new PdfTableLayoutProvider.Table(
                1, 1, box(0, 0, 300, 180), List.of(anchor(0, 0, 1, 1, 0, 0, 300, 180)), "whole-page");
        final var pixels = new PdfOcrOptions(72, 54_000, 100, 1000)
                                   .withTableDetection(PdfOcrOptions.TableDetection.PROVIDER_LAYOUT_CELLS);
        assertEquals(PartitionException.Code.OCR_LIMIT,
                assertThrows(PartitionException.class,
                        () -> separateCells(pixels, pageWords, blankLine, image -> List.of(whole)).partition(source()))
                        .code());
        assertEquals(0, calls.get(), "Oversized padded crops fail before the separate cell engine");
        final var interrupted = new InterruptedIOException("owned cell cancellation");
        final var cancelled =
                separateCells(cellOptions(), pageWords, image -> { throw interrupted; }, image -> List.of(layout()));
        assertSame(interrupted, assertThrows(InterruptedIOException.class, () -> cancelled.partition(source())));
        final var io = new IOException("owned cell failure");
        final var failing = separateCells(cellOptions(), pageWords, image -> { throw io; }, image -> List.of(layout()));
        final var failure = assertThrows(PartitionException.class, () -> failing.partition(source()));
        assertEquals(PartitionException.Code.OCR_FAILURE, failure.code());
        assertSame(io, failure.getCause());
    }

    @Test
    void separateStrategiesCanBeSharedByVirtualThreadDocumentCalls() throws Exception {
        final var calls = new AtomicInteger();
        final var processor = separateCells(cellOptions(),
                (image, kind)
                        -> {
                    assertEquals(PdfOcrProvider.InputKind.PAGE, kind);
                    return words();
                },
                image
                -> {
                    calls.incrementAndGet();
                    return List.of(new PdfOcrProvider.Line("cell", 12, 12, 40, 20));
                },
                image -> List.of(layout()));
        final var input = source();
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var futures = new ArrayList<java.util.concurrent.Future<PartitionResult>>();
            for (int index = 0; index < 20; index++) {
                futures.add(executor.submit(() -> processor.partition(input)));
            }
            for (final var future : futures) {
                final var result = future.get();
                assertEquals("cell cell cell cell cell", result.elements()
                                                                 .stream()
                                                                 .filter(e -> e.type() == ElementType.Table)
                                                                 .findFirst()
                                                                 .orElseThrow()
                                                                 .text());
                assertEquals(List.of("before", "after"), ordinary(result));
            }
        }
        assertEquals(100, calls.get());
    }

    /** Selects explicit provider-layout anchor refinement for independent engine controls. */
    private static PdfOcrOptions cellOptions() {
        return options().withTableDetection(PdfOcrOptions.TableDetection.PROVIDER_LAYOUT_CELLS);
    }

    /** Registers independent borrowed-image strategies without executing them during construction. */
    private static DocWeave separateCells(final PdfOcrOptions options, final PdfWordOcrProvider words,
            final PdfOcrProvider cells, final PdfTableLayoutProvider layout) {
        return DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.createWithCellOcr(
                PdfOptions.defaults().withTableCellCoordinates(true), options, words, layout, cells))));
    }

    @Test
    void independentStrategiesPreserveMergedEmptyCellsAndUnownedWords() throws Exception {
        final var wordCalls = new AtomicInteger();
        final var layoutCalls = new AtomicInteger();
        final PdfWordOcrProvider words = (image, kind) -> {
            assertEquals(PdfOcrProvider.InputKind.PAGE, kind);
            wordCalls.incrementAndGet();
            return words();
        };
        final PdfTableLayoutProvider layout = image -> {
            layoutCalls.incrementAndGet();
            assertEquals(300, image.getWidth());
            return List.of(layout());
        };
        final var result = processor(options(), TableLimits.defaults(), words, layout).partition(source());
        assertEquals(1, wordCalls.get());
        assertEquals(1, layoutCalls.get());
        final var tables = result.elements().stream().filter(value -> value.type() == ElementType.Table).toList();
        assertEquals(1, tables.size());
        assertEquals("Header A&B more 1 2", tables.getFirst().text());
        assertEquals(
                "<table><tbody><tr><td colspan=\"2\">Header</td></tr><tr><td>A&amp;B more</td><td>1</td></tr><tr><td></td><td>2</td></tr></tbody></table>",
                tables.getFirst().metadata().get("text_as_html"));
        assertEquals("owned-layout-control", tables.getFirst().metadata().get("pdf_table_layout_provenance"));
        assertEquals(List.of("before", "after"), ordinary(result));
        final var cells = (Map<?, ?>) tables.getFirst().metadata().get("pdf_table_cells");
        assertEquals(3, cells.get("row_count"));
        assertEquals(2, cells.get("column_count"));
        assertEquals(5, ((List<?>) cells.get("cells")).size());
        assertEquals("provider_rectangles", cells.get("geometry"));
    }

    @Test
    void absentOrUnpopulatedLayoutsPreserveOriginalProviderLine() throws Exception {
        for (final PdfTableLayoutProvider strategy : List.<PdfTableLayoutProvider>of(image
                     -> List.of(),
                     image
                     -> List.of(new PdfTableLayoutProvider.Table(1, 1, box(200, 120, 260, 160),
                             List.of(anchor(0, 0, 1, 1, 200, 120, 260, 160)), "empty-control")))) {
            final var result = processor(options(), TableLimits.defaults(), (image, kind) -> words(), strategy)
                                       .partition(source());
            assertEquals(words().stream().map(value -> value.line().text()).toList(), ordinary(result));
            assertTrue(result.elements().stream().noneMatch(value -> value.type() == ElementType.Table));
        }
    }

    @Test
    void malformedGeometryTopologyAndSourceOrderAreRejectedBeforeWordOwnership() throws Exception {
        final var complete = layout();
        final var missing = new ArrayList<>(complete.anchors());
        missing.removeFirst();
        final var duplicate = new ArrayList<>(complete.anchors());
        duplicate.add(complete.anchors().getFirst());
        final var wrongOrder = List.of(anchor(0, 0, 1, 1, 100, 20, 180, 40), anchor(0, 1, 1, 1, 20, 20, 100, 40));
        for (final var invalid : List.of(new PdfTableLayoutProvider.Table(3, 2, complete.bounds(), missing, "missing"),
                     new PdfTableLayoutProvider.Table(3, 2, complete.bounds(), duplicate, "duplicate"),
                     new PdfTableLayoutProvider.Table(1, 2, box(20, 20, 180, 40), wrongOrder, "reversed"),
                     new PdfTableLayoutProvider.Table(
                             1, 1, box(20, 20, 320, 40), List.of(anchor(0, 0, 1, 1, 20, 20, 320, 40)), "outside"),
                     new PdfTableLayoutProvider.Table(1, 1, box(20, 20, 180, 40),
                             List.of(anchor(0, 0, 2, 1, 20, 20, 180, 40)), "span-outside"))) {
            assertCode(
                    PartitionException.Code.OCR_FAILURE, options(), TableLimits.defaults(), image -> List.of(invalid));
        }
        assertCode(PartitionException.Code.OCR_FAILURE, options(), TableLimits.defaults(),
                image -> List.of(complete, complete));
        assertCode(PartitionException.Code.OCR_FAILURE, options(), TableLimits.defaults(), image -> null);
        assertCode(PartitionException.Code.OCR_FAILURE, options(), TableLimits.defaults(),
                image -> { throw new IOException("controlled"); });
    }

    @Test
    void topologyAndTextBudgetsApplyBeforeFilteringAndWithoutSlotMatrixAllocation() throws Exception {
        for (final var limits : List.of(new TableLimits(2, 10, 100, 100), new TableLimits(10, 1, 100, 100),
                     new TableLimits(10, 10, 5, 100), new TableLimits(10, 10, 100, 2))) {
            assertCode(PartitionException.Code.TABLE_LIMIT, options(), limits, image -> List.of(layout()));
        }
        final var small = new PdfOcrOptions(72, 100_000, 100, 1000, PdfOcrOptions.Selection.TEXTLESS_PAGES, 10,
                PdfOcrOptions.TableDetection.PROVIDER_LAYOUT, 4);
        assertCode(PartitionException.Code.TABLE_LIMIT, small, TableLimits.defaults(), image -> List.of(layout()));
        final var giant = new PdfTableLayoutProvider.Table(Integer.MAX_VALUE, Integer.MAX_VALUE, box(20, 10, 180, 80),
                List.of(anchor(0, 0, Integer.MAX_VALUE, Integer.MAX_VALUE, 20, 10, 180, 80)), "large-control");
        assertCode(PartitionException.Code.TABLE_LIMIT, options(), TableLimits.defaults(), image -> List.of(giant));
        final var tooLittleText =
                new PdfOcrOptions(72, 100_000, 100, 2).withTableDetection(PdfOcrOptions.TableDetection.PROVIDER_LAYOUT);
        assertCode(
                PartitionException.Code.OCR_LIMIT, tooLittleText, TableLimits.defaults(), image -> List.of(layout()));
    }

    @Test
    void cancellationAndExplicitCapabilityPoliciesAreRetained() throws Exception {
        assertThrows(IllegalArgumentException.class,
                ()
                        -> PdfPartitioner.create(PdfOptions.defaults(), PdfOcrOptions.defaults(),
                                (image, kind) -> words(), image -> List.of(layout())));
        final var oldFactory = DocWeave.create(PartitionerRegistry.of(
                List.of(PdfPartitioner.create(PdfOptions.defaults(), options(), (PdfWordOcrProvider) (image, kind) -> {
                    throw new AssertionError("Missing layout must fail before OCR is invoked");
                }))));
        assertEquals(PartitionException.Code.UNSUPPORTED_OPTION,
                assertThrows(PartitionException.class, () -> oldFactory.partition(source())).code());
        final var expected = new InterruptedIOException("controlled interruption");
        final var processor =
                processor(options(), TableLimits.defaults(), (image, kind) -> words(), image -> { throw expected; });
        assertSame(expected, assertThrows(InterruptedIOException.class, () -> processor.partition(source())));
    }

    @Test
    void completeProviderCellsRetainDisplayedCoordinatesAcrossRenderResolutions() throws Exception {
        Object expected = null;
        for (final int dpi : List.of(72, 144)) {
            final double scale = dpi / 72.0;
            final PdfWordOcrProvider text = (image, kind)
                    -> words().stream()
                               .map(line -> {
                                   final var original = line.line();
                                   return new PdfWordOcrProvider.WordLine(
                                           new PdfOcrProvider.Line(original.text(), original.left() * scale,
                                                   original.top() * scale, original.right() * scale,
                                                   original.bottom() * scale),
                                           line.words()
                                                   .stream()
                                                   .map(word
                                                           -> new PdfWordOcrProvider.Word(word.text(),
                                                                   word.left() * scale, word.top() * scale,
                                                                   word.right() * scale, word.bottom() * scale))
                                                   .toList());
                               })
                               .toList();
            final PdfTableLayoutProvider geometry = image
                    -> List.of(new PdfTableLayoutProvider.Table(3, 2, scaled(layout().bounds(), scale),
                            layout().anchors()
                                    .stream()
                                    .map(anchor
                                            -> new PdfTableLayoutProvider.Anchor(anchor.row(), anchor.column(),
                                                    anchor.rowSpan(), anchor.columnSpan(),
                                                    scaled(anchor.bounds(), scale)))
                                    .toList(),
                            "scaled-control"));
            final var policy = new PdfOcrOptions(dpi, 1_000_000, 100, 1000)
                                       .withTableDetection(PdfOcrOptions.TableDetection.PROVIDER_LAYOUT);
            final var result = processor(policy, TableLimits.defaults(), text, geometry).partition(source());
            final var table = result.elements()
                                      .stream()
                                      .filter(value -> value.type() == ElementType.Table)
                                      .findFirst()
                                      .orElseThrow();
            if (expected == null) {
                expected = table.metadata().get("pdf_table_cells");
            }
            assertEquals(expected, table.metadata().get("pdf_table_cells"));
        }
    }

    private static PdfTableLayoutProvider.Box scaled(final PdfTableLayoutProvider.Box box, final double scale) {
        return new PdfTableLayoutProvider.Box(
                box.left() * scale, box.top() * scale, box.right() * scale, box.bottom() * scale);
    }

    private static void assertCode(final PartitionException.Code expected, final PdfOcrOptions ocr,
            final TableLimits limits, final PdfTableLayoutProvider provider) throws Exception {
        assertEquals(
                expected, assertThrows(PartitionException.class,
                                  () -> processor(ocr, limits, (image, kind) -> words(), provider).partition(source()))
                                  .code());
    }

    @Test
    void explicitCellRefinementReplacesTableTextAndPreservesOnlyOutsideSourceWords() throws Exception {
        final var calls = new AtomicInteger();
        final var pageCalls = new AtomicInteger();
        final var text = List.of("Refined", "A&B more", "1", "", "2");
        final PdfWordOcrProvider engine = (image, kind) -> {
            if (kind == PdfOcrProvider.InputKind.PAGE) {
                pageCalls.incrementAndGet();
                return words();
            }
            assertEquals(PdfOcrProvider.InputKind.TABLE_CELL, kind);
            assertEquals(0xffffffff, image.getRGB(0, 0), "Owned white margin");
            return cellWords(text.get(calls.getAndIncrement()));
        };
        final var policy = options().withTableDetection(PdfOcrOptions.TableDetection.PROVIDER_LAYOUT_CELLS);
        final var result =
                processor(policy, TableLimits.defaults(), engine, image -> List.of(layout())).partition(source());
        assertEquals(1, pageCalls.get());
        assertEquals(5, calls.get());
        final var table =
                result.elements().stream().filter(value -> value.type() == ElementType.Table).findFirst().orElseThrow();
        assertEquals("Refined A&B more 1 2", table.text());
        assertEquals("provider_layout_cells_v1", table.metadata().get("pdf_table_method"));
        assertTrue(result.warnings().stream().anyMatch(w -> w.message().contains("explicit provider topology")));
        assertFalse(result.warnings().stream().anyMatch(w -> w.message().contains("scanned tables are unsupported")));
        assertEquals(List.of("before", "after"), ordinary(result));
        assertTrue(table.metadata().get("text_as_html").toString().contains("<td></td>"));
    }

    @Test
    void blankRefinementPreservesAllPageWordsAndCellOnlyRecognitionCanPopulateLayout() throws Exception {
        final var policy = options().withTableDetection(PdfOcrOptions.TableDetection.PROVIDER_LAYOUT_CELLS);
        final PdfWordOcrProvider blank = (image, kind) -> kind == PdfOcrProvider.InputKind.PAGE ? words() : List.of();
        final var result =
                processor(policy, TableLimits.defaults(), blank, image -> List.of(layout())).partition(source());
        assertEquals(words().stream().map(value -> value.line().text()).toList(), ordinary(result));
        assertTrue(result.elements().stream().noneMatch(value -> value.type() == ElementType.Table));
        final PdfWordOcrProvider onlyCells =
                (image, kind) -> kind == PdfOcrProvider.InputKind.PAGE ? List.of() : cellWords("found");
        final var populated =
                processor(policy, TableLimits.defaults(), onlyCells, image -> List.of(layout())).partition(source());
        assertEquals("found found found found found", populated.elements().getFirst().text());
    }

    @Test
    void cellRasterPreflightFailsBeforeAnyCellCallAndAggregateLineBudgetsIncludePage() throws Exception {
        final var cells = new AtomicInteger();
        final PdfWordOcrProvider engine = (image, kind) -> {
            if (kind == PdfOcrProvider.InputKind.PAGE) {
                return words();
            }
            cells.incrementAndGet();
            return cellWords("text");
        };
        final var policy = new PdfOcrOptions(72, 54_000, 100, 1000)
                                   .withTableDetection(PdfOcrOptions.TableDetection.PROVIDER_LAYOUT_CELLS);
        final var whole = new PdfTableLayoutProvider.Table(
                1, 1, box(0, 0, 300, 180), List.of(anchor(0, 0, 1, 1, 0, 0, 300, 180)), "whole");
        assertEquals(PartitionException.Code.OCR_LIMIT,
                assertThrows(PartitionException.class,
                        ()
                                -> processor(policy, TableLimits.defaults(), engine, image -> List.of(whole))
                                        .partition(source()))
                        .code());
        assertEquals(0, cells.get());
        final var lines = new PdfOcrOptions(72, 100_000, 2, 1000)
                                  .withTableDetection(PdfOcrOptions.TableDetection.PROVIDER_LAYOUT_CELLS);
        assertEquals(PartitionException.Code.OCR_LIMIT,
                assertThrows(PartitionException.class,
                        ()
                                -> processor(lines, TableLimits.defaults(), engine, image -> List.of(layout()))
                                        .partition(source()))
                        .code());
        assertEquals(1, cells.get());
    }

    private static List<PdfWordOcrProvider.WordLine> cellWords(final String text) {
        return text.isEmpty() ? List.of()
                              : List.of(new PdfWordOcrProvider.WordLine(new PdfOcrProvider.Line(text, 12, 12, 40, 20),
                                        List.of(new PdfWordOcrProvider.Word(text, 12, 12, 40, 20))));
    }

    private static DocWeave processor(final PdfOcrOptions ocr, final TableLimits limits, final PdfWordOcrProvider words,
            final PdfTableLayoutProvider layout) {
        final var base = PdfOptions.defaults();
        final var options = new PdfOptions(base.maxPages(), base.maxGlyphsPerPage(), base.maxOperatorsPerPage(),
                base.maxImagePixels(), base.maxImageBytes(), base.includeImageBytes(), limits)
                                    .withTableCellCoordinates(true);
        return DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options, ocr, words, layout))));
    }

    private static PdfOcrOptions options() {
        return new PdfOcrOptions(72, 100_000, 100, 1000)
                .withTableDetection(PdfOcrOptions.TableDetection.PROVIDER_LAYOUT);
    }

    private static PdfTableLayoutProvider.Table layout() {
        final var anchors = new ArrayList<>(List.of(anchor(0, 0, 1, 2, 20, 10, 180, 30),
                anchor(1, 0, 1, 1, 20, 30, 100, 55), anchor(1, 1, 1, 1, 100, 30, 180, 55),
                anchor(2, 0, 1, 1, 20, 55, 100, 80), anchor(2, 1, 1, 1, 100, 55, 180, 80)));
        Collections.reverse(anchors);
        return new PdfTableLayoutProvider.Table(3, 2, box(20, 10, 180, 80), anchors, "owned-layout-control");
    }

    private static PdfTableLayoutProvider.Anchor anchor(final int row, final int column, final int rowSpan,
            final int columnSpan, final double left, final double top, final double right, final double bottom) {
        return new PdfTableLayoutProvider.Anchor(row, column, rowSpan, columnSpan, box(left, top, right, bottom));
    }

    private static PdfTableLayoutProvider.Box box(
            final double left, final double top, final double right, final double bottom) {
        return new PdfTableLayoutProvider.Box(left, top, right, bottom);
    }

    private static List<PdfWordOcrProvider.WordLine> words() {
        final var words = List.of(new PdfWordOcrProvider.Word("before", 30, 110, 60, 118),
                new PdfWordOcrProvider.Word("Header", 30, 20, 150, 28),
                new PdfWordOcrProvider.Word("A&B", 30, 40, 50, 48), new PdfWordOcrProvider.Word("1", 150, 40, 165, 48),
                new PdfWordOcrProvider.Word("2", 150, 60, 165, 68),
                new PdfWordOcrProvider.Word("after", 100, 110, 130, 118));
        return List.of(new PdfWordOcrProvider.WordLine(
                               new PdfOcrProvider.Line(
                                       String.join(" ", words.stream().map(PdfWordOcrProvider.Word::text).toList()), 0,
                                       0, 300, 180),
                               words),
                new PdfWordOcrProvider.WordLine(new PdfOcrProvider.Line("more", 30, 49, 70, 54),
                        List.of(new PdfWordOcrProvider.Word("more", 30, 49, 70, 54))));
    }

    private static List<String> ordinary(final PartitionResult result) {
        return result.elements()
                .stream()
                .filter(value -> value.type() != ElementType.Table)
                .map(value -> value.text())
                .toList();
    }

    private static DocumentSource source() throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage(new PDRectangle(300, 180)));
            document.save(output);
            return DocumentSource.bytes(output.toByteArray(), "layout.pdf");
        }
    }
}
