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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end declared-header controls for native, word and separately refined cell ownership. */
class PdfProviderContinuationTest {
    /** Creates owned-source integration controls. */
    PdfProviderContinuationTest() {
    }

    /**
     * All three routes preserve complete page output and actual element identities in a three-page chain.
     * @throws Exception if authoring or extraction fails
     */
    @Test
    void explicitProviderChainsRetainEveryPageAndOldPolicies() throws Exception {
        for (final var route : Route.values()) {
            final var source = source(route == Route.NATIVE);
            for (final boolean cells : List.of(false, true)) {
                final var baseline = processor(route, PdfOptions.TableContinuation.NONE, cells, "plain")
                                             .partition(source)
                                             .elements();
                final var old = processor(route, PdfOptions.TableContinuation.REPEATED_HEADER, cells, "plain")
                                        .partition(source)
                                        .elements();
                assertEquals(baseline, old);
                final var linked =
                        processor(route, PdfOptions.TableContinuation.REPEATED_HEADER_WITH_PROVIDERS, cells, "plain")
                                .partition(source)
                                .elements();
                assertEquals(
                        linked, processor(route, PdfOptions.TableContinuation.REPEATED_HEADER_LAYOUT_WITH_PROVIDERS,
                                        cells, "plain")
                                        .partition(source)
                                        .elements());
                assertEquals(3, linked.size());
                assertFalse(linked.getFirst().metadata().containsKey("pdf_table_continuation"));
                for (int page = 0; page < linked.size(); page++) {
                    final var table = linked.get(page);
                    assertEquals(ElementType.Table, table.type());
                    assertEquals(baseline.get(page).elementId(), table.elementId());
                    assertEquals(baseline.get(page).text(), table.text());
                    final var metadata = new LinkedHashMap<>(table.metadata());
                    metadata.remove("pdf_table_continuation");
                    assertEquals(baseline.get(page).metadata(), metadata);
                    assertEquals(cells, metadata.containsKey("pdf_table_cells"));
                    if (page > 0) {
                        final var link = (Map<?, ?>) table.metadata().get("pdf_table_continuation");
                        assertNotNull(link, route + " page " + page);
                        assertEquals(linked.getFirst().elementId(), link.get("root_element_id"));
                        assertEquals(linked.get(page - 1).elementId(), link.get("previous_element_id"));
                        assertEquals(page, link.get("part_index"));
                        assertEquals(page, link.get("previous_page_number"));
                    }
                }
            }
        }
    }

    /**
     * Unsupported declarations or geometry preserve extraction without creating a speculative chain.
     * @throws Exception if authoring or extraction fails
     */
    @Test
    void unknownStackedMergedJaggedAndDifferentProvenanceDoNotLink() throws Exception {
        for (final var route : Route.values()) {
            for (final var variant : List.of("unknown", "stacked", "merged", "jagged", "provenance")) {
                final var result =
                        processor(route, PdfOptions.TableContinuation.REPEATED_HEADER_WITH_PROVIDERS, false, variant)
                                .partition(source(route == Route.NATIVE));
                assertEquals(3, result.elements().size());
                assertTrue(result.elements().stream().noneMatch(
                                   element -> element.metadata().containsKey("pdf_table_continuation")),
                        route + variant);
            }
        }
    }

    /**
     * Corrected OCR frames remain excluded even when callbacks declare matching repeated headers.
     * @throws Exception if authoring or extraction fails
     */
    @Test
    void correctedWordAndCellOcrNeverSupplyContinuationEvidence() throws Exception {
        for (final var route : List.of(Route.WORDS, Route.CELLS)) {
            final var result =
                    processor(route, PdfOptions.TableContinuation.REPEATED_HEADER_WITH_PROVIDERS, true, "orientation")
                            .partition(source(false));
            assertEquals(3, result.elements().size());
            assertTrue(
                    result.elements().stream().allMatch(element -> element.metadata().containsKey("pdf_table_cells")));
            assertTrue(result.elements().stream().noneMatch(
                    element -> element.metadata().containsKey("pdf_table_continuation")));
        }
    }

    /**
     * Matching evidence never bridges source-text routes or providers, even when geometry and labels agree.
     * @throws Exception if source-frame validation fails
     */
    @Test
    void signatureFamiliesAndHeaderLimitsRemainConservative() throws Exception {
        final var page = new PDPage(new PDRectangle(500, 800));
        final var frame = new PdfTableCells(new PdfPageGeometry(page), 1, 1);
        final var first = layout(0, "plain");
        final var second = layout(1, "plain");
        final var values = List.of("Item", "Qty", "Alpha", "10");
        final var original =
                frame.providerSignature(first, first.anchors(), values, "native_provider", new PdfTableWork());
        assertNotNull(original);
        for (final String family : List.of("word_provider", "cell_provider", "native_rules")) {
            final var candidate = frame.providerSignature(second, second.anchors(), values, family, new PdfTableWork());
            assertNotNull(candidate);
            assertFalse(candidate.follows(original));
        }
        for (final var header : List.of(List.of("2024", "2025", "Alpha", "10"), List.of("Item", "Item", "Alpha", "10"),
                     List.of("", "Qty", "Alpha", "10"), List.of("Item", "Qty", "", ""),
                     List.of("x".repeat(513), "Qty", "Alpha", "10"))) {
            assertNull(frame.providerSignature(first, first.anchors(), header, "native_provider", new PdfTableWork()));
        }
    }

    /**
     * Explicit two-row prefixes link across every supported route without changing old policies or source output.
     * @throws Exception if owned authoring or extraction fails
     */
    @Test
    void declaredHeaderPrefixesLinkOnlyUnderTheirExplicitPolicy() throws Exception {
        for (final var route : Route.values()) {
            final var source = source(route == Route.NATIVE, "prefix");
            for (final boolean cells : List.of(false, true)) {
                final var baseline = processor(route, PdfOptions.TableContinuation.NONE, cells, "prefix")
                                             .partition(source)
                                             .elements();
                assertEquals(baseline,
                        processor(route, PdfOptions.TableContinuation.REPEATED_HEADER_WITH_PROVIDERS, cells, "prefix")
                                .partition(source)
                                .elements());
                final var linked = processor(
                        route, PdfOptions.TableContinuation.REPEATED_HEADER_PREFIX_WITH_PROVIDERS, cells, "prefix")
                                           .partition(source)
                                           .elements();
                assertEquals(
                        linked, processor(route, PdfOptions.TableContinuation.REPEATED_HEADER_LAYOUT_WITH_PROVIDERS,
                                        cells, "prefix")
                                        .partition(source)
                                        .elements());
                assertEquals(3, linked.size());
                for (int page = 0; page < 3; page++) {
                    assertEquals(baseline.get(page).elementId(), linked.get(page).elementId());
                    assertEquals(baseline.get(page).text(), linked.get(page).text());
                    final var metadata = new LinkedHashMap<>(linked.get(page).metadata());
                    final var continuation = (Map<?, ?>) metadata.remove("pdf_table_continuation");
                    assertEquals(baseline.get(page).metadata(), metadata);
                    if (page == 0) {
                        assertNull(continuation);
                    } else {
                        assertNotNull(continuation, route + " page " + page);
                        assertEquals(linked.getFirst().elementId(), continuation.get("root_element_id"));
                        assertEquals(linked.get(page - 1).elementId(), continuation.get("previous_element_id"));
                        assertEquals(page, continuation.get("part_index"));
                    }
                }
            }
        }
    }

    /**
     * Prefix signatures retain row/cell boundaries and all existing source/text/geometry safeguards.
     * @throws Exception if source-frame validation fails
     */
    @Test
    void prefixSignaturesDoNotFlattenBoundariesOrRelaxEvidenceLimits() throws Exception {
        final var geometry = new PdfPageGeometry(new PDPage(new PDRectangle(500, 800)));
        final var frame = new PdfTableCells(geometry, 1, 1, true);
        final var first = layout(0, "prefix");
        final var second = layout(1, "prefix");
        final var values = List.of("Item Code", "Period", "X", "2026", "Alpha", "10");
        final var original =
                frame.providerSignature(first, first.anchors(), values, "native_provider", new PdfTableWork());
        assertNotNull(original);
        assertNull(new PdfTableCells(geometry, 1, 1)
                        .providerSignature(first, first.anchors(), values, "native_provider", new PdfTableWork()));
        assertTrue(frame.providerSignature(second, second.anchors(), values, "native_provider", new PdfTableWork())
                        .follows(original));
        final var collision = frame.providerSignature(second, second.anchors(),
                List.of("Item", "Period", "Code X", "2026", "Alpha", "10"), "native_provider", new PdfTableWork());
        assertNotNull(collision);
        assertFalse(collision.follows(original), "Collapsed concatenations must not erase cell boundaries");
        final var changedYear = frame.providerSignature(second, second.anchors(),
                List.of("Item Code", "Period", "X", "2025", "Alpha", "10"), "native_provider", new PdfTableWork());
        assertNotNull(changedYear);
        assertFalse(changedYear.follows(original));
        for (final var rejected :
                List.of(List.of("", "Period", "X", "2026", "Alpha", "10"), List.of("1", "2", "3", "4", "Alpha", "10"),
                        List.of("\u00a0", "Period", "X", "2026", "Alpha", "10"),
                        List.of("x".repeat(513), "Period", "X", "2026", "Alpha", "10"),
                        List.of("Item", "Period", "Code", "2026", "", ""))) {
            assertNull(
                    frame.providerSignature(first, first.anchors(), rejected, "native_provider", new PdfTableWork()));
        }
        final var merged = new ArrayList<>(first.anchors());
        merged.set(0, new PdfTableLayoutProvider.Anchor(0, 0, 1, 2, new PdfTableLayoutProvider.Box(40, 400, 400, 440)));
        merged.remove(1);
        final var mergedTable = new PdfTableLayoutProvider.Table(3, 2, first.bounds(), merged, "owned", 2);
        assertNull(frame.providerSignature(mergedTable, merged, List.of("Quarter", "Code", "2026", "Alpha", "10"),
                "native_provider", new PdfTableWork()));
        final var jagged = new ArrayList<>(first.anchors());
        jagged.set(2, new PdfTableLayoutProvider.Anchor(1, 0, 1, 1, new PdfTableLayoutProvider.Box(40, 440, 225, 480)));
        jagged.set(
                3, new PdfTableLayoutProvider.Anchor(1, 1, 1, 1, new PdfTableLayoutProvider.Box(225, 440, 400, 480)));
        final var jaggedTable = new PdfTableLayoutProvider.Table(3, 2, first.bounds(), jagged, "owned", 2);
        assertNull(frame.providerSignature(jaggedTable, jagged, values, "native_provider", new PdfTableWork()));
        final var transforms = Map.<String, Object>of();
        for (final int count : List.of(2, 3, 4)) {
            final var rows = java.util.stream.IntStream.range(0, count)
                                     .mapToObj(row -> List.of("Item " + row, "Period " + row))
                                     .toList();
            assertNotNull(PdfTableSignature.createPrefix(
                    500, 800, transforms, "native_provider", List.of(40.0, 220.0, 400.0), rows, 40, 760));
        }
        assertNull(PdfTableSignature.createPrefix(500, 800, transforms, "native_provider", List.of(40.0, 220.0, 400.0),
                java.util.Collections.nCopies(5, List.of("Item", "Period")), 40, 760));
        for (final int columns : List.of(8, 9)) {
            final var boundaries =
                    java.util.stream.IntStream.rangeClosed(0, columns).mapToObj(column -> 40.0 + column * 20).toList();
            final var labels =
                    java.util.stream.IntStream.range(0, columns).mapToObj(column -> "Header " + column).toList();
            final var signature = PdfTableSignature.createPrefix(500, 800, transforms, "native_provider", boundaries,
                    java.util.Collections.nCopies(4, labels), 40, 760);
            assertEquals(columns == 8, signature != null, "At most32 original header cells may be retained");
        }
    }

    /**
     * The extended policy retains all unknown/merged/jagged/provenance and corrected-frame fallbacks.
     * @throws Exception if owned PDF authoring or extraction fails
     */
    @Test
    void prefixPolicyKeepsUnsupportedAndCorrectedFramesUnlinked() throws Exception {
        for (final var route : Route.values()) {
            for (final String variant : List.of("unknown", "stacked", "merged", "jagged", "provenance")) {
                final var result = processor(
                        route, PdfOptions.TableContinuation.REPEATED_HEADER_PREFIX_WITH_PROVIDERS, true, variant)
                                           .partition(source(route == Route.NATIVE));
                assertEquals(3, result.elements().size());
                assertTrue(result.elements().stream().noneMatch(
                                   element -> element.metadata().containsKey("pdf_table_continuation")),
                        route + variant);
            }
        }
        for (final var route : List.of(Route.WORDS, Route.CELLS)) {
            final var result = processor(
                    route, PdfOptions.TableContinuation.REPEATED_HEADER_PREFIX_WITH_PROVIDERS, true, "orientation")
                                       .partition(source(false));
            assertEquals(3, result.elements().size());
            assertTrue(result.elements().stream().noneMatch(
                    element -> element.metadata().containsKey("pdf_table_continuation")));
        }
    }

    /**
     * Declared merged prefixes link across all routes without relaxing earlier policies or changing source tables.
     * @throws Exception if owned source authoring or extraction fails
     */
    @Test
    void mergedHeaderLayoutsLinkOnlyUnderTheirExplicitPolicy() throws Exception {
        for (final String variant : List.of("merged-prefix", "row-merged-prefix")) {
            for (final var route : Route.values()) {
                final var source = source(route == Route.NATIVE, variant);
                for (final boolean cells : List.of(false, true)) {
                    final var baseline = processor(route, PdfOptions.TableContinuation.NONE, cells, variant)
                                                 .partition(source)
                                                 .elements();
                    assertEquals(3, baseline.size());
                    assertEquals(baseline,
                            processor(route, PdfOptions.TableContinuation.REPEATED_HEADER_PREFIX_WITH_PROVIDERS, cells,
                                    variant)
                                    .partition(source)
                                    .elements());
                    final var linked = processor(
                            route, PdfOptions.TableContinuation.REPEATED_HEADER_LAYOUT_WITH_PROVIDERS, cells, variant)
                                               .partition(source)
                                               .elements();
                    assertEquals(3, linked.size());
                    for (int page = 0; page < 3; page++) {
                        assertEquals(baseline.get(page).elementId(), linked.get(page).elementId());
                        assertEquals(baseline.get(page).text(), linked.get(page).text());
                        final var metadata = new LinkedHashMap<>(linked.get(page).metadata());
                    final var link = (Map<?, ?>) metadata.remove("pdf_table_continuation");
                    assertEquals(baseline.get(page).metadata(), metadata);
                    if (page == 0) {
                        assertNull(link);
                    } else {
                        assertNotNull(link, route + " page " + page);
                        assertEquals(linked.getFirst().elementId(), link.get("root_element_id"));
                        assertEquals(linked.get(page - 1).elementId(), link.get("previous_element_id"));
                        assertEquals(page, link.get("part_index"));
                    }
                    }
                }
            }
        }
    }

    /**
     * Equal expanded labels cannot hide changed spans, blank cells, body-crossing spans or missing column edges.
     * @throws Exception if source geometry or shared work fails
     */
    @Test
    void mergedHeaderSignaturesRequireExactTopologyAndCompleteEvidence() throws Exception {
        final var geometry = new PdfPageGeometry(new PDPage(new PDRectangle(500, 800)));
        final var frame = new PdfTableCells(geometry, 1, 1, true, true);
        final var first = layout(0, "merged-prefix");
        final var second = layout(1, "merged-prefix");
        final var values = values("merged-prefix");
        final var signature = frame.providerSignature(first, first.anchors(), values, "native", new PdfTableWork());
        assertNotNull(signature);
        assertTrue(frame.providerSignature(second, second.anchors(), values, "native", new PdfTableWork())
                        .follows(signature));
        final var differentProvider = new PdfTableLayoutProvider.Table(second.rows(), second.columns(), second.bounds(),
                second.anchors(), "another-provider", second.headerRows());
        assertFalse(
                frame.providerSignature(
                             differentProvider, differentProvider.anchors(), values, "native", new PdfTableWork())
                        .follows(signature));
        final var unmerged = layout(1, "prefix");
        final var collision = frame.providerSignature(unmerged, unmerged.anchors(),
                List.of("Quarter", "Quarter", "Code", "2026", "Alpha", "10"), "native", new PdfTableWork());
        assertNotNull(collision);
        assertFalse(collision.follows(signature), "Identical expanded labels do not establish identical spans");
        for (final String family : List.of("words", "cells", "native_rules")) {
            assertFalse(frame.providerSignature(second, second.anchors(), values, family, new PdfTableWork())
                            .follows(signature));
        }
        for (final var rejected : List.of(List.of("", "Code", "2026", "Alpha", "10"),
                     List.of("\u00a0", "Code", "2026", "Alpha", "10"), List.of("Quarter", "Code", "2026", "", ""),
                     List.of("Q".repeat(513), "Code", "2026", "Alpha", "10"))) {
            assertNull(frame.providerSignature(first, first.anchors(), rejected, "native", new PdfTableWork()));
        }
        final var changed = frame.providerSignature(second, second.anchors(),
                List.of("Quarter", "Code", "2025", "Alpha", "10"), "native", new PdfTableWork());
        assertNotNull(changed);
        assertFalse(changed.follows(signature));
        final var crossing = new ArrayList<>(first.anchors());
        crossing.set(
                0, new PdfTableLayoutProvider.Anchor(0, 0, 3, 2, new PdfTableLayoutProvider.Box(40, 400, 400, 760)));
        assertNull(frame.providerSignature(new PdfTableLayoutProvider.Table(3, 2, first.bounds(), crossing, "owned", 2),
                crossing, values, "native", new PdfTableWork()));
        final var jagged = new ArrayList<>(first.anchors());
        jagged.set(3, new PdfTableLayoutProvider.Anchor(2, 0, 1, 1, new PdfTableLayoutProvider.Box(40, 480, 225, 760)));
        assertNull(frame.providerSignature(new PdfTableLayoutProvider.Table(3, 2, first.bounds(), jagged, "owned", 2),
                jagged, values, "native", new PdfTableWork()));
        final var incomplete = new ArrayList<>(first.anchors());
        incomplete.remove(1);
        assertNull(
                frame.providerSignature(new PdfTableLayoutProvider.Table(3, 2, first.bounds(), incomplete, "owned", 2),
                        incomplete, List.of("Quarter", "2026", "Alpha", "10"), "native", new PdfTableWork()));
        final var noEdges = List.of(
                new PdfTableLayoutProvider.Anchor(0, 0, 1, 2, new PdfTableLayoutProvider.Box(40, 400, 400, 440)),
                new PdfTableLayoutProvider.Anchor(1, 0, 1, 2, new PdfTableLayoutProvider.Box(40, 440, 400, 480)),
                new PdfTableLayoutProvider.Anchor(2, 0, 1, 2, new PdfTableLayoutProvider.Box(40, 480, 400, 760)));
        assertNull(frame.providerSignature(new PdfTableLayoutProvider.Table(3, 2, first.bounds(), noEdges, "owned", 2),
                noEdges, List.of("Quarter", "Year", "Alpha"), "native", new PdfTableWork()));
    }

    /**
     * Expanded logical slots enforce the same fixed caps even when merged anchors reduce physical cell count.
     * @throws Exception if bounded source geometry cannot be processed
     */
    @Test
    void mergedHeaderLogicalSlotCapsIncludeCoveredPositions() throws Exception {
        final var frame =
                new PdfTableCells(new PdfPageGeometry(new PDPage(new PDRectangle(500, 800))), 1, 1, true, true);
        for (final int rows : List.of(2, 3, 4, 5)) {
            for (final int columns : List.of(2, 8, 9)) {
                final var anchors = new ArrayList<PdfTableLayoutProvider.Anchor>();
                final var labels = new ArrayList<String>();
                for (int row = 0; row <= rows; row++) {
                    for (int column = 0; column < columns;) {
                        final int span = row == 0 && column == 0 ? 2 : 1;
                        anchors.add(new PdfTableLayoutProvider.Anchor(row, column, 1, span,
                                new PdfTableLayoutProvider.Box(40 + column * 20, 400 + row * 20,
                                        40 + (column + span) * 20, row == rows ? 760 : 420 + row * 20)));
                        labels.add("Header " + row + " " + column);
                        column += span;
                    }
                }
                final var table = new PdfTableLayoutProvider.Table(rows + 1, columns,
                        new PdfTableLayoutProvider.Box(40, 400, 40 + columns * 20, 760), anchors, "owned", rows);
                assertEquals(rows <= 4 && rows * columns <= 32,
                        frame.providerSignature(table, anchors, labels, "native", new PdfTableWork()) != null,
                        rows + "x" + columns);
            }
        }
    }

    /** Independent text routes under test; cell text comes from a separate authoritative recognizer. */
    private enum Route {
        /** Original PDF glyph text. */ NATIVE,
        /** Page OCR tokens assigned to provider anchors. */ WORDS,
        /** Separate per-anchor OCR text. */ CELLS
    }

    /**
     * Builds fresh callback state per extraction, without inspecting source labels or prior results.
     * @param route fixed extraction route
     * @param continuation explicit linking policy
     * @param cells whether to serialize cell geometry
     * @param variant predeclared source-layout control
     * @return document processor for one owned extraction invocation
     */
    private static DocWeave processor(final Route route, final PdfOptions.TableContinuation continuation,
            final boolean cells, final String variant) {
        final var options = PdfOptions.defaults().withTableContinuation(continuation).withTableCellCoordinates(cells);
        final var calls = new AtomicInteger();
        final PdfTableLayoutProvider layout = image -> List.of(layout(calls.getAndIncrement(), variant));
        final PdfPartitioner partitioner;
        if (route == Route.NATIVE) {
            partitioner =
                    PdfPartitioner.createWithNativeLayout(options, new PdfNativeLayoutOptions(72, 500_000, 16), layout);
        } else {
            final var pageCalls = new AtomicInteger();
            final PdfWordOcrProvider words = (image, kind) -> words(pageCalls.getAndIncrement(), variant);
            final var ocr = new PdfOcrOptions(72, 500_000, 100, 10_000)
                                    .withTableDetection(route == Route.CELLS
                                                                ? PdfOcrOptions.TableDetection.PROVIDER_LAYOUT_CELLS
                                                                : PdfOcrOptions.TableDetection.PROVIDER_LAYOUT);
            if (route == Route.CELLS) {
                final var cellCalls = new AtomicInteger();
                final PdfOcrProvider cellProvider = image
                        -> List.of(new PdfOcrProvider.Line(
                                values(variant).get(cellCalls.getAndIncrement() % values(variant).size()), 15, 15, 20,
                                20));
                partitioner =
                        variant.equals("orientation")
                                ? PdfPartitioner.createOrientedWithCellOcr(options, ocr, words, layout, cellProvider,
                                          image -> new PdfOrientationEstimator.Estimate(180, true, 1, "owned"))
                                : PdfPartitioner.createWithCellOcr(options, ocr, words, layout, cellProvider);
            } else {
                partitioner = variant.equals("orientation")
                                      ? PdfPartitioner.createOriented(options, ocr, words, layout,
                                                image -> new PdfOrientationEstimator.Estimate(180, true, 1, "owned"))
                                      : PdfPartitioner.create(options, ocr, words, layout);
            }
        }
        return DocWeave.create(PartitionerRegistry.of(List.of(partitioner)));
    }

    /**
     * Supplies fixed complete source rectangles for three page-local tables, including negative declarations.
     * @param page zero-based page
     * @param variant fixed declaration/geometry variant
     * @return complete bounded provider layout
     */
    private static PdfTableLayoutProvider.Table layout(final int page, final String variant) {
        final int top = page == 0 ? 400 : 40;
        final int bottom = page == 2 ? 400 : 760;
        final var anchors = new ArrayList<PdfTableLayoutProvider.Anchor>();
        if (variant.equals("row-merged-prefix")) {
            anchors.add(new PdfTableLayoutProvider.Anchor(
                    0, 0, 2, 1, new PdfTableLayoutProvider.Box(40, top, 220, top + 80)));
            anchors.add(new PdfTableLayoutProvider.Anchor(
                    0, 1, 1, 1, new PdfTableLayoutProvider.Box(220, top, 400, top + 40)));
            anchors.add(new PdfTableLayoutProvider.Anchor(
                    1, 1, 1, 1, new PdfTableLayoutProvider.Box(220, top + 40, 400, top + 80)));
        } else if (variant.equals("merged") || variant.equals("merged-prefix")) {
            anchors.add(new PdfTableLayoutProvider.Anchor(
                    0, 0, 1, 2, new PdfTableLayoutProvider.Box(40, top, 400, top + 40)));
        } else {
            anchors.add(new PdfTableLayoutProvider.Anchor(
                    0, 0, 1, 1, new PdfTableLayoutProvider.Box(40, top, 220, top + 40)));
            anchors.add(new PdfTableLayoutProvider.Anchor(
                    0, 1, 1, 1, new PdfTableLayoutProvider.Box(220, top, 400, top + 40)));
        }
        if (variant.equals("prefix") || variant.equals("merged-prefix")) {
            anchors.add(new PdfTableLayoutProvider.Anchor(
                    1, 0, 1, 1, new PdfTableLayoutProvider.Box(40, top + 40, 220, top + 80)));
            anchors.add(new PdfTableLayoutProvider.Anchor(
                    1, 1, 1, 1, new PdfTableLayoutProvider.Box(220, top + 40, 400, top + 80)));
        }
        final int bodyRow = variant.endsWith("prefix") ? 2 : 1;
        final int bodyTop = top + bodyRow * 40;
        final int middle = variant.equals("jagged") ? 225 : 220;
        anchors.add(new PdfTableLayoutProvider.Anchor(
                bodyRow, 0, 1, 1, new PdfTableLayoutProvider.Box(40, bodyTop, middle, bottom)));
        anchors.add(new PdfTableLayoutProvider.Anchor(
                bodyRow, 1, 1, 1, new PdfTableLayoutProvider.Box(middle, bodyTop, 400, bottom)));
        return new PdfTableLayoutProvider.Table(bodyRow + 1, 2, new PdfTableLayoutProvider.Box(40, top, 400, bottom),
                anchors, variant.equals("provenance") ? "owned-" + page : "owned",
                variant.equals("unknown")                                   ? 0
                : (variant.equals("stacked") || variant.endsWith("prefix")) ? 2
                                                                            : 1);
    }

    /**
     * Provides four actual tokens in independently specified source positions.
     * @param page zero-based page
     * @return immutable detailed lines reconstructing their actual words
     */
    private static List<PdfWordOcrProvider.WordLine> words(final int page) {
        return words(page, "plain");
    }

    /**
     * Selects the complete authored cell sequence for a fixed fixture variant.
     * @param variant predeclared source/header shape
     * @return immutable row-major header/body values
     */
    private static List<String> values(final String variant) {
        return variant.equals("row-merged-prefix") ? List.of("Item", "Period", "2026", "Alpha", "10")
        : variant.equals("merged-prefix")          ? List.of("Quarter", "Code", "2026", "Alpha", "10")
        : variant.equals("prefix")                 ? List.of("Item", "Period", "Code", "2026", "Alpha", "10")
                                                   : List.of("Item", "Qty", "Alpha", "10");
    }

    /**
     * Supplies actual tokens for every declared header/body cell of the owned source.
     * @param page zero-based physical page
     * @param variant fixed authored header shape
     * @return immutable positioned source words
     */
    private static List<PdfWordOcrProvider.WordLine> words(final int page, final String variant) {
        final int top = page == 0 ? 400 : 40;
        final var output = new ArrayList<PdfWordOcrProvider.WordLine>();
        final var values = values(variant);
        for (int index = 0; index < values.size(); index++) {
            final int slot = variant.equals("row-merged-prefix") && index > 1 ? index + 1
                             : variant.equals("merged-prefix") && index > 0   ? index + 1
                                                                              : index;
            final int x = slot % 2 == 0 ? 45 : 225;
            final int y = top + (slot / 2) * 40 + 15;
            final var word = new PdfWordOcrProvider.Word(values.get(index), x, y, x + 30, y + 12);
            output.add(new PdfWordOcrProvider.WordLine(
                    new PdfOcrProvider.Line(word.text(), x, y, x + 30, y + 12), List.of(word)));
        }
        return List.copyOf(output);
    }

    /**
     * Authors a three-page native source or blank OCR source without relying on model predictions.
     * @param nativeText whether to paint the independently positioned native labels/body
     * @return source bytes reused unchanged across policy comparisons
     * @throws Exception if the owned PDF cannot be authored
     */
    private static DocumentSource source(final boolean nativeText) throws Exception {
        return source(nativeText, "plain");
    }

    /**
     * Authors the complete fixed source with a single-row header or declared two-row prefix.
     * @param nativeText whether native glyphs supply text instead of owned OCR callbacks
     * @param variant fixed authored header shape
     * @return complete three-page owned PDF
     * @throws Exception if source authoring fails
     */
    private static DocumentSource source(final boolean nativeText, final String variant) throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            for (int page = 0; page < 3; page++) {
                final var source = new PDPage(new PDRectangle(500, 800));
                document.addPage(source);
                if (nativeText) {
                    try (final var content = new PDPageContentStream(document, source)) {
                        for (final var line : words(page, variant)) {
                            content.beginText();
                            content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                            content.newLineAtOffset((float) line.line().left(), (float) (800 - line.line().bottom()));
                            content.showText(line.line().text());
                            content.endText();
                        }
                    }
                }
            }
            document.save(output);
            return DocumentSource.bytes(output.toByteArray(), "provider-continuation.pdf");
        }
    }
}
