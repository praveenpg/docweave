package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.core.ElementType;
import org.apache.pdfbox.Loader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Owned native/model composition controls independent of learned weights and external source labels. */
class PdfNativeFirstTest {
    @Test
    void keepsNativeTopologyAndDisjointModelTextWithImagesExactlyOnce() throws Exception {
        final var calls = new AtomicInteger();
        final var result = run(6, image -> {
            calls.incrementAndGet();
            return List.of(table(30, 20, 300, 100), table(40, 100, 260, 180));
        });
        assertEquals(1, calls.get(), "Native success must not skip provider validation");
        final var tables = result.elements().stream().filter(e -> e.type() == ElementType.Table).toList();
        assertEquals(2, tables.size());
        assertEquals("Wrapped native line. Continuation of the line.", tables.getFirst().text());
        assertEquals("provider_layout_native_glyphs_v1", tables.getFirst().metadata().get("pdf_table_method"));
        assertEquals("A B C D", tables.getLast().text());
        assertEquals("complete_ruled_grid", tables.getLast().metadata().get("pdf_table_method"));
        assertEquals("<table><tbody><tr><td>A</td><td>B</td></tr><tr><td>C</td><td>D</td></tr></tbody></table>",
                tables.getLast().metadata().get("text_as_html"));
        assertTrue(tables.stream().allMatch(
                e -> e.metadata().get("pdf_table_selection_policy").equals("native_first_disjoint_v1")));
        assertEquals(1, result.elements().stream().filter(e -> e.type() == ElementType.Image).count());
        assertEquals(3, result.elements().size(), "Owned table text must not be emitted again as ordinary text");
    }

    @Test
    void partialOverlapRejectsWholeModelCandidateAndRetainsUnclaimedOrdinaryText() throws Exception {
        final var result = run(16, image -> List.of(table(10, 30, 100, 120)));
        assertEquals(1, result.elements().stream().filter(e -> e.type() == ElementType.Table).count());
        assertEquals(List.of("Wrapped native line.", "Continuation of the line."),
                result.elements()
                        .stream()
                        .filter(e -> e.type() != ElementType.Table && e.type() != ElementType.Image)
                        .map(e -> e.text())
                        .toList());
    }

    @Test
    void validatesInvalidProviderAndRetainsFailureCauseEvenWithNativeTables() throws Exception {
        final var cause = new IOException("owned provider failure");
        final var failure = assertThrows(PartitionException.class, () -> run(16, image -> { throw cause; }));
        assertEquals(PartitionException.Code.OCR_FAILURE, failure.code());
        assertSame(cause, failure.getCause());
        final var box = new PdfTableLayoutProvider.Box(40, 100, 260, 180);
        final var invalid = new PdfTableLayoutProvider.Table(
                2, 1, box, List.of(new PdfTableLayoutProvider.Anchor(0, 0, 1, 1, box)), "owned_invalid_overlap");
        assertEquals(PartitionException.Code.OCR_FAILURE,
                assertThrows(PartitionException.class, () -> run(16, image -> List.of(invalid))).code());
    }

    @Test
    void combinedAnchorBudgetCountsRejectedOverlapsAndFailsBeforeRenderForNativeOverflow() throws Exception {
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class,
                        () -> run(5, image -> List.of(table(30, 20, 300, 100), table(40, 100, 260, 180))))
                        .code());
        final var calls = new AtomicInteger();
        assertEquals(PartitionException.Code.TABLE_LIMIT, assertThrows(PartitionException.class, () -> run(3, image -> {
            calls.incrementAndGet();
            return List.of();
        })).code());
        assertEquals(0, calls.get());
        final var noCellMetadata = PdfPartitioner.createWithNativeTablesFirst(
                PdfOptions.defaults(), new PdfNativeLayoutOptions(72, 120_000, 3), image -> List.of());
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class,
                        ()
                                -> DocWeave.create(PartitionerRegistry.of(List.of(noCellMetadata)))
                                        .partition(DocumentSource.bytes(
                                                PdfRotationTest.scene(0, 0, 0, 1, false), "owned.pdf")))
                        .code());
    }

    @Test
    void rejectsRunBasedModesAtConstructionWithoutChangingExistingFactories() {
        for (final var mode : List.of(PdfOptions.TableDetection.RULED_AND_ALIGNED_TEXT,
                     PdfOptions.TableDetection.RULED_AND_MULTILINE_ALIGNED_TEXT)) {
            final var options = PdfOptions.defaults().withTableDetection(mode);
            assertThrows(IllegalArgumentException.class,
                    ()
                            -> PdfPartitioner.createWithNativeTablesFirst(
                                    options, PdfNativeLayoutOptions.defaults(), image -> List.of()));
            assertNotNull(PdfPartitioner.create(options));
        }
    }

    @Test
    void explicitUnclaimedGlyphsInsideNativeEnvelopeAreNeverRemovedByGeometryGuessing() throws Exception {
        try (final var document = Loader.loadPDF(PdfRotationTest.scene(0, 0, 0, 1, false))) {
            final var geometry = new PdfPageGeometry(document.getPage(0));
            final var unclaimed = List.of(new PdfPageText.Glyph("Retained", 50, 120, 20, 6, 3, 0));
            final var preferred = new PdfLayoutItem(
                    ElementType.Table, "Owned", new PdfLayoutItem.Bounds(40, 100, 260, 180), Map.of());
            final var result = PdfNativeProviderTables.extract(document, 0, geometry, unclaimed, PdfOptions.defaults(),
                    new PdfNativeLayoutOptions(72, 120_000, 16),
                    image
                    -> List.of(table(40, 100, 260, 180)),
                    new PdfPageLayout.NativeStage(List.of(preferred), unclaimed, 1), new PdfTableWork());
            assertTrue(result.tables().isEmpty());
            assertEquals(unclaimed, result.remaining());
            assertSame(unclaimed.getFirst(), result.remaining().getFirst());
        }
    }

    @Test
    void inheritedQuarterTurnsRetainNativePrecedenceAndDisplayedModelCoordinates() throws Exception {
        for (final int angle : new int[] {90, 180, 270}) {
            final var processor = DocWeave.create(PartitionerRegistry.of(List.of(
                    PdfPartitioner.createWithNativeTablesFirst(PdfOptions.defaults().withTableCellCoordinates(true),
                            new PdfNativeLayoutOptions(72, 120_000, 16),
                            image -> List.of(table(30, 20, 300, 100), table(40, 100, 260, 180))))));
            final var result = processor.partition(
                    DocumentSource.bytes(PdfRotationTest.scene(angle, 50, 70, 2, true), "owned.pdf"));
            final var tables = result.elements().stream().filter(e -> e.type() == ElementType.Table).toList();
            assertEquals(List.of("Wrapped native line. Continuation of the line.", "A B C D"),
                    tables.stream().map(e -> e.text()).toList());
            assertEquals(2.0, tables.getLast().metadata().get("pdf_user_unit"));
            assertEquals(angle, tables.getFirst().metadata().get("pdf_page_rotation"));
        }
    }

    @Test
    void modelOnlyNativeTextAndTextlessImagePagesRetainExplicitSelection() throws Exception {
        final byte[] bytes;
        try (final var document = new org.apache.pdfbox.pdmodel.PDDocument()) {
            final var page =
                    new org.apache.pdfbox.pdmodel.PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(400, 300));
            document.addPage(page);
            try (final var stream = new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                                       org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),
                        12);
                stream.newLineAtOffset(50, 250);
                stream.showText("Model only");
                stream.endText();
            }
            final var scan =
                    new org.apache.pdfbox.pdmodel.PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(400, 300));
            document.addPage(scan);
            try (final var stream = new org.apache.pdfbox.pdmodel.PDPageContentStream(document, scan)) {
                stream.drawImage(
                        org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(document,
                                new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB)),
                        20, 20, 10, 10);
            }
            final var output = new java.io.ByteArrayOutputStream();
            document.save(output);
            bytes = output.toByteArray();
        }
        final var calls = new AtomicInteger();
        final var pdf = PdfPartitioner.createWithNativeTablesFirst(
                PdfOptions.defaults(), new PdfNativeLayoutOptions(72, 120_000, 16), image -> {
                    calls.incrementAndGet();
                    return List.of(table(30, 20, 300, 100));
                });
        final var result = DocWeave.create(PartitionerRegistry.of(List.of(pdf)))
                                   .partition(DocumentSource.bytes(bytes, "owned.pdf"));
        assertEquals(1, calls.get());
        assertEquals("Model only", result.elements().getFirst().text());
        assertEquals(1, result.elements().stream().filter(e -> e.type() == ElementType.Image).count());
        assertTrue(result.warnings().stream().anyMatch(
                w -> w.message().contains("page 2") && w.message().contains("OCR was not performed")));
    }

    @Test
    void nativeAndProviderWorkUseTheSamePageCounter() throws Exception {
        try (final var document = Loader.loadPDF(PdfRotationTest.scene(0, 0, 0, 1, false))) {
            final var work = new PdfTableWork();
            for (int used = 0; used < 2_000_000; used++) {
                work.step();
            }
            assertEquals(PartitionException.Code.TABLE_LIMIT,
                    assertThrows(PartitionException.class,
                            ()
                                    -> PdfNativeProviderTables.extract(document, 0,
                                            new PdfPageGeometry(document.getPage(0)), List.of(), PdfOptions.defaults(),
                                            new PdfNativeLayoutOptions(72, 120_000, 16),
                                            image
                                            -> List.of(table(30, 20, 300, 100)),
                                            new PdfPageLayout.NativeStage(List.of(), List.of(), 0), work))
                            .code());
        }
    }

    /**
     * Runs the native-first production factory on a constant native/image source.
     * @param anchors combined accepted-native/raw-provider budget
     * @param provider borrowed complete geometry dependency
     * @return original source result
     * @throws Exception if extraction fails
     */
    private static PartitionResult run(final int anchors, final PdfTableLayoutProvider provider) throws Exception {
        final var pdf = PdfPartitioner.createWithNativeTablesFirst(PdfOptions.defaults().withTableCellCoordinates(true),
                new PdfNativeLayoutOptions(72, 120_000, anchors), provider);
        return DocWeave.create(PartitionerRegistry.of(List.of(pdf)))
                .partition(DocumentSource.bytes(PdfRotationTest.scene(0, 0, 0, 1, false), "owned.pdf"));
    }

    /**
     * Supplies one complete one-cell model candidate, independent of source strings.
     * @param left source pixel left
     * @param top source pixel top
     * @param right source pixel right
     * @param bottom source pixel bottom
     * @return bounded complete model geometry
     */
    private static PdfTableLayoutProvider.Table table(
            final double left, final double top, final double right, final double bottom) {
        final var box = new PdfTableLayoutProvider.Box(left, top, right, bottom);
        return new PdfTableLayoutProvider.Table(
                1, 1, box, List.of(new PdfTableLayoutProvider.Anchor(0, 0, 1, 1, box)), "owned_native_first");
    }
}
