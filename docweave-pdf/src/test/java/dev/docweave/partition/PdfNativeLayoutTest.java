package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.core.ElementType;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Owned source controls for native provider layout, source ownership and explicit failure boundaries. */
class PdfNativeLayoutTest {
    @Test
    void declaredHeaderMetadataPreservesNativeTextHtmlAndOptionalGeometry() throws Exception {
        for (final boolean coordinates : List.of(false, true)) {
            dev.docweave.core.Element plain = null;
            for (final int headers : new int[] {0, 2}) {
                final var original = layout();
                final var declared = new PdfTableLayoutProvider.Table(original.rows(), original.columns(),
                        original.bounds(), original.anchors(), original.provenance(), headers);
                final var provider = PdfPartitioner.createWithNativeLayout(
                        PdfOptions.defaults().withTableCellCoordinates(coordinates),
                        new PdfNativeLayoutOptions(72, 40_000, 16), image -> List.of(declared));
                final var table = run(provider, source(0, false))
                                          .elements()
                                          .stream()
                                          .filter(element -> element.type() == ElementType.Table)
                                          .findFirst()
                                          .orElseThrow();
                if (headers == 0) {
                    plain = table;
                    assertFalse(table.metadata().containsKey("pdf_table_header_rows"));
                    assertFalse(table.metadata().containsKey("pdf_table_header_policy"));
                } else {
                    assertEquals(2, table.metadata().get("pdf_table_header_rows"));
                    assertEquals("provider_declared_prefix_v1", table.metadata().get("pdf_table_header_policy"));
                    assertEquals(plain.text(), table.text());
                    assertEquals(plain.metadata().get("text_as_html"), table.metadata().get("text_as_html"));
                    assertEquals(plain.metadata().get("pdf_table_cells"), table.metadata().get("pdf_table_cells"));
                }
            }
        }
    }

    @Test
    void formerTableConstructorAndHeaderBoundsRemainExplicit() {
        final var original = layout();
        assertEquals(0, original.headerRows());
        assertEquals(original, new PdfTableLayoutProvider.Table(original.rows(), original.columns(), original.bounds(),
                                       original.anchors(), original.provenance(), 0));
        for (final int headers : new int[] {-1, original.rows() + 1}) {
            assertThrows(IllegalArgumentException.class,
                    ()
                            -> new PdfTableLayoutProvider.Table(original.rows(), original.columns(), original.bounds(),
                                    original.anchors(), original.provenance(), headers));
        }
    }

    @Test
    void mergedSectionWrappedLabelSubtotalAndImagesRetainSourceOwnership() throws Exception {
        final var calls = new AtomicInteger();
        final var partitioner =
                PdfPartitioner.createWithNativeLayout(PdfOptions.defaults().withTableCellCoordinates(true),
                        new PdfNativeLayoutOptions(72, 40_000, 16), image -> {
                            assertEquals(200, image.getWidth());
                            calls.incrementAndGet();
                            return List.of(layout());
                        });
        final var result = run(partitioner, source(0, true));
        assertEquals(1, calls.get(), "Textless image page must not run native layout");
        final var tables = result.elements().stream().filter(e -> e.type() == ElementType.Table).toList();
        assertEquals(1, tables.size());
        final var table = tables.getFirst();
        assertEquals("Section Wrapped label (001) Total $ 2", table.text());
        assertEquals("<table><tbody><tr><td colspan=\"2\">Section</td></tr>"
                             + "<tr><td>Wrapped label</td><td>(001)</td></tr>"
                             + "<tr><td>Total</td><td>$ 2</td></tr></tbody></table>",
                table.metadata().get("text_as_html"));
        assertEquals("provider_layout_native_glyphs_v1", table.metadata().get("pdf_table_method"));
        assertEquals("native", table.metadata().get("pdf_text_method"));
        assertTrue(table.metadata().containsKey("pdf_table_cells"));
        assertEquals(2, result.elements().stream().filter(e -> e.type() == ElementType.Image).count());
        assertEquals(
                List.of("Footer"), result.elements()
                                           .stream()
                                           .filter(e -> e.type() != ElementType.Table && e.type() != ElementType.Image)
                                           .map(e -> e.text())
                                           .toList());
        assertTrue(result.warnings().stream().anyMatch(
                w -> w.message().contains("page 2") && w.message().contains("OCR was not performed")));
    }

    @Test
    void absentOrEmptyLayoutsPreserveNativeTextWithoutFabricatedTables() throws Exception {
        for (final var layouts : List.of(List.<PdfTableLayoutProvider.Table>of(),
                     List.of(new PdfTableLayoutProvider.Table(1, 1, new PdfTableLayoutProvider.Box(160, 5, 195, 30),
                             List.of(new PdfTableLayoutProvider.Anchor(
                                     0, 0, 1, 1, new PdfTableLayoutProvider.Box(160, 5, 195, 30))),
                             "owned_empty")))) {
            final var result = run(service(image -> layouts, TableLimits.defaults(), 40_000, 16), source(0, false));
            assertTrue(result.elements().stream().noneMatch(e -> e.type() == ElementType.Table));
            final String text = String.join(" ", result.elements().stream().map(e -> e.text()).toList());
            assertTrue(text.contains("Section"));
            assertTrue(text.contains("Wrapped"));
            assertTrue(text.contains("(001)"));
            assertTrue(text.contains("Footer"));
        }
    }

    @Test
    void providerFailuresRetainCauseAndInvalidTopologyDoesNotFallBack() throws Exception {
        final var cause = new IOException("owned provider failure");
        final var failure = assertThrows(PartitionException.class,
                () -> run(service(image -> { throw cause; }, TableLimits.defaults(), 40_000, 16), source(0, false)));
        assertEquals(PartitionException.Code.OCR_FAILURE, failure.code());
        assertSame(cause, failure.getCause());
        final var invalid = new PdfTableLayoutProvider.Table(
                2, 2, layout().bounds(), List.of(layout().anchors().getFirst()), "owned_invalid");
        assertEquals(PartitionException.Code.OCR_FAILURE,
                assertThrows(PartitionException.class,
                        ()
                                -> run(service(image -> List.of(invalid), TableLimits.defaults(), 40_000, 16),
                                        source(0, false)))
                        .code());
    }

    @Test
    void renderAnchorSlotAndCellTextLimitsFailExplicitly() throws Exception {
        final var calls = new AtomicInteger();
        final PdfTableLayoutProvider provider = image -> {
            calls.incrementAndGet();
            return List.of(layout());
        };
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class,
                        () -> run(service(provider, TableLimits.defaults(), 1, 16), source(0, false)))
                        .code());
        assertEquals(0, calls.get(), "Render budget must fail before provider execution");
        for (final var limits :
                List.of(new TableLimits(2, 2, 6, 100), new TableLimits(3, 2, 5, 100), new TableLimits(3, 2, 6, 2))) {
            assertEquals(PartitionException.Code.TABLE_LIMIT,
                    assertThrows(PartitionException.class,
                            () -> run(service(provider, limits, 40_000, 16), source(0, false)))
                            .code());
        }
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class,
                        () -> run(service(provider, TableLimits.defaults(), 40_000, 1), source(0, false)))
                        .code());
    }

    @Test
    void cancellationFailsBeforeProviderExecution() throws Exception {
        final var calls = new AtomicInteger();
        final var service = service(image -> {
            calls.incrementAndGet();
            return List.of(layout());
        }, TableLimits.defaults(), 40_000, 16);
        final var bytes = source(0, false);
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.io.InterruptedIOException.class, () -> run(service, bytes));
        } finally {
            // Clear the intentional test interruption so later tests are unaffected.
            Thread.interrupted();
        }
        assertEquals(0, calls.get());
    }

    @Test
    void factoryAndRenderPolicyValidateWithoutProviderExecution() {
        assertThrows(IllegalArgumentException.class, () -> new PdfNativeLayoutOptions(35, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new PdfNativeLayoutOptions(72, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new PdfNativeLayoutOptions(72, 1, 0));
        assertThrows(NullPointerException.class,
                ()
                        -> PdfPartitioner.createWithNativeLayout(
                                PdfOptions.defaults(), PdfNativeLayoutOptions.defaults(), null));
        assertEquals(256, PdfNativeLayoutOptions.defaults().maxTableAnchors());
    }

    @Test
    void sharedServiceKeepsDocumentStateConfinedOnVirtualThreads() throws Exception {
        final var processor = service(image -> List.of(layout()), TableLimits.defaults(), 40_000, 16);
        final var bytes = source(0, false);
        try (final var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            final var tasks = new java.util.ArrayList<java.util.concurrent.Future<String>>();
            for (int index = 0; index < 8; index++) {
                tasks.add(executor.submit(()
                                                  -> run(processor, bytes)
                                                          .elements()
                                                          .stream()
                                                          .filter(e -> e.type() == ElementType.Table)
                                                          .findFirst()
                                                          .orElseThrow()
                                                          .text()));
            }
            for (final var task : tasks) {
                assertEquals(
                        "Section Wrapped label (001) Total $ 2", task.get(10, java.util.concurrent.TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void cropAndUserUnitRetainTheDisplayedNativeCoordinateFrame() throws Exception {
        final byte[] bytes;
        try (final var document = org.apache.pdfbox.Loader.loadPDF(source(0, false))) {
            document.getPage(0).setCropBox(new PDRectangle(5, 5, 190, 190));
            document.getPage(0).setUserUnit(2);
            final var output = new ByteArrayOutputStream();
            document.save(output);
            bytes = output.toByteArray();
        }
        final var result = run(service(image -> {
            assertEquals(190, image.getWidth());
            assertEquals(190, image.getHeight());
            final var box = new PdfTableLayoutProvider.Box(0, 0, 190, 190);
            return List.of(new PdfTableLayoutProvider.Table(
                    1, 1, box, List.of(new PdfTableLayoutProvider.Anchor(0, 0, 1, 1, box)), "owned_crop"));
        }, TableLimits.defaults(), 40_000, 16), bytes);
        final var table =
                result.elements().stream().filter(e -> e.type() == ElementType.Table).findFirst().orElseThrow();
        assertTrue(table.text().contains("Section"));
        assertTrue(table.text().contains("Footer"));
        assertEquals(190.0, ((java.util.Map<?, ?>)table.metadata().get("coordinates")).get("layout_width"));
        assertEquals(1, result.elements().stream().filter(e -> e.type() == ElementType.Image).count());
    }

    @Test
    void centralOutputBudgetAppliesToProviderTablesAndUnclaimedElements() throws Exception {
        final var partitioner = service(image -> List.of(layout()), TableLimits.defaults(), 40_000, 16);
        final var processor = DocWeave.create(PartitionerRegistry.of(List.of(partitioner)));
        assertEquals(PartitionException.Code.ELEMENT_LIMIT,
                assertThrows(PartitionException.class,
                        ()
                                -> processor.partition(DocumentSource.bytes(source(0, false), "owned.pdf"),
                                        PartitionOptions.builder().maxElements(1).build()))
                        .code());
    }

    @Test
    void declaredTurnsKeepProviderSpansOwnershipImagesAndCropFrame() throws Exception {
        for (final int rotation : new int[] {0, 90, 180, 270, -90, 450}) {
            final var calls = new AtomicInteger();
            final var processor =
                    PdfPartitioner.createWithNativeLayout(PdfOptions.defaults().withTableCellCoordinates(true),
                            new PdfNativeLayoutOptions(72, 120_000, 16), image -> {
                                assertEquals(400, image.getWidth());
                                assertEquals(300, image.getHeight());
                                // The independent raster transform places a red source image at this displayed pixel.
                                assertEquals(0xffff0000, image.getRGB(55, 250));
                                calls.incrementAndGet();
                                return List.of(new PdfTableLayoutProvider.Table(2, 2,
                                        new PdfTableLayoutProvider.Box(40, 100, 260, 180),
                                        List.of(new PdfTableLayoutProvider.Anchor(
                                                        0, 0, 1, 2, new PdfTableLayoutProvider.Box(40, 100, 260, 140)),
                                                new PdfTableLayoutProvider.Anchor(
                                                        1, 0, 1, 1, new PdfTableLayoutProvider.Box(40, 140, 150, 180)),
                                                new PdfTableLayoutProvider.Anchor(1, 1, 1, 1,
                                                        new PdfTableLayoutProvider.Box(150, 140, 260, 180))),
                                        "owned_turned"));
                            });
            final var result = run(processor, PdfRotationTest.scene(rotation, 50, 70, 2, true));
            assertEquals(1, calls.get());
            final var table =
                    result.elements().stream().filter(e -> e.type() == ElementType.Table).findFirst().orElseThrow();
            assertEquals("A B C D", table.text());
            assertEquals(
                    "<table><tbody><tr><td colspan=\"2\">A B</td></tr><tr><td>C</td><td>D</td></tr></tbody></table>",
                    table.metadata().get("text_as_html"));
            final var coordinates = (java.util.Map<?, ?>)table.metadata().get("coordinates");
            assertEquals(
                    List.of(List.of(40.0, 100.0), List.of(260.0, 100.0), List.of(260.0, 180.0), List.of(40.0, 180.0)),
                    coordinates.get("points"));
            final var cells = (java.util.Map<?, ?>)table.metadata().get("pdf_table_cells");
            assertEquals(3, ((List<?>) cells.get("cells")).size());
            final var firstCell = (java.util.Map<?, ?>)((List<?>)cells.get("cells")).getFirst();
            assertEquals(2, firstCell.get("column_span"));
            assertEquals(List.of(List.of(40.0, 100.0), List.of(260.0, 100.0), List.of(260.0, 140.0), List.of(40.0, 140.0)),
                    ((java.util.Map<?, ?>)firstCell.get("coordinates")).get("points"));
            assertEquals(2.0, table.metadata().get("pdf_user_unit"));
            assertEquals(Math.floorMod(rotation, 360),
                    ((Number) table.metadata().getOrDefault("pdf_page_rotation", 0)).intValue());
            assertEquals(List.of("Wrapped native line.", "Continuation of the line."),
                    result.elements()
                            .stream()
                            .filter(e -> e.type() != ElementType.Table && e.type() != ElementType.Image)
                            .map(e -> e.text())
                            .toList());
            assertEquals(1, result.elements().stream().filter(e -> e.type() == ElementType.Image).count());
        }
    }

    @Test
    void everyPageAndBaselineQuarterTurnPreservesNativeCellStrings() throws Exception {
        for (final int pageAngle : new int[] {0, 90, 180, 270}) {
            for (final int textAngle : new int[] {0, 90, 180, 270}) {
                final byte[] bytes;
                try (final var document = new PDDocument()) {
                    final var page = new PDPage(new PDRectangle(400, 400));
                    page.setRotation(pageAngle);
                    document.addPage(page);
                    try (final var stream = new PDPageContentStream(document, page)) {
                        stream.beginText();
                        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        stream.setTextMatrix(
                                org.apache.pdfbox.util.Matrix.getRotateInstance(Math.toRadians(textAngle), 200, 200));
                        stream.showText("Quarter turn");
                        stream.endText();
                    }
                    final var output = new ByteArrayOutputStream();
                    document.save(output);
                    bytes = output.toByteArray();
                }
                final var result = run(service(image -> {
                    final var box = new PdfTableLayoutProvider.Box(0, 0, 400, 400);
                    return List.of(new PdfTableLayoutProvider.Table(
                            1, 1, box, List.of(new PdfTableLayoutProvider.Anchor(0, 0, 1, 1, box)), "owned_all_turns"));
                }, TableLimits.defaults(), 160_000, 16), bytes);
                assertEquals(1, result.elements().size());
                assertEquals("Quarter turn", result.elements().getFirst().text());
            }
        }
    }

    /**
     * Creates an explicit72DPI service with caller-controlled table/render ceilings.
     * @param provider borrowed layout dependency
     * @param limits structured table ceilings
     * @param pixels render ceiling
     * @param anchors raw anchor ceiling
     * @return hidden production service
     */
    private static PdfPartitioner service(
            final PdfTableLayoutProvider provider, final TableLimits limits, final long pixels, final int anchors) {
        final var defaults = PdfOptions.defaults();
        final var options =
                new PdfOptions(defaults.maxPages(), defaults.maxGlyphsPerPage(), defaults.maxOperatorsPerPage(),
                        defaults.maxImagePixels(), defaults.maxImageBytes(), defaults.includeImageBytes(), limits);
        return PdfPartitioner.createWithNativeLayout(
                options, new PdfNativeLayoutOptions(72, pixels, anchors), provider);
    }

    /**
     * Executes the service using the ordinary source/sink contract.
     * @param partitioner configured strategy
     * @param bytes owned PDF
     * @return bounded source result
     * @throws Exception if parsing or extraction fails
     */
    private static PartitionResult run(final PdfPartitioner partitioner, final byte[] bytes) throws Exception {
        return DocWeave.create(PartitionerRegistry.of(List.of(partitioner)))
                .partition(DocumentSource.bytes(bytes, "owned.pdf"));
    }

    /**
     * Defines complete source-pixel section/body/subtotal topology independently of source text.
     * @return owned validated geometry
     */
    private static PdfTableLayoutProvider.Table layout() {
        return new PdfTableLayoutProvider.Table(3, 2, new PdfTableLayoutProvider.Box(5, 5, 150, 100),
                List.of(new PdfTableLayoutProvider.Anchor(0, 0, 1, 2, new PdfTableLayoutProvider.Box(5, 5, 150, 35)),
                        new PdfTableLayoutProvider.Anchor(1, 0, 1, 1, new PdfTableLayoutProvider.Box(5, 35, 100, 75)),
                        new PdfTableLayoutProvider.Anchor(1, 1, 1, 1, new PdfTableLayoutProvider.Box(100, 35, 150, 75)),
                        new PdfTableLayoutProvider.Anchor(2, 0, 1, 1, new PdfTableLayoutProvider.Box(5, 75, 100, 100)),
                        new PdfTableLayoutProvider.Anchor(
                                2, 1, 1, 1, new PdfTableLayoutProvider.Box(100, 75, 150, 100))),
                "owned_native_layout");
    }

    /**
     * Builds an owned text/image PDF with no closed grid and optional textless second page.
     * @param rotation declared first-page rotation
     * @param second whether to include a textless image page
     * @return complete PDF bytes
     * @throws Exception if owned source generation fails
     */
    private static byte[] source(final int rotation, final boolean second) throws Exception {
        try (final var document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(200, 200));
            page.setRotation(rotation);
            document.addPage(page);
            final var image =
                    LosslessFactory.createFromImage(document, new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB));
            try (final var stream = new PDPageContentStream(document, page)) {
                final var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                for (final var text : List.of(new String[] {"Section", "10", "180"},
                             new String[] {"Wrapped", "10", "150"}, new String[] {"label", "10", "138"},
                             new String[] {"(001)", "105", "150"}, new String[] {"Total", "10", "110"},
                             new String[] {"$ 2", "105", "110"}, new String[] {"Footer", "10", "80"})) {
                    stream.beginText();
                    stream.setFont(font, 8);
                    stream.newLineAtOffset(Float.parseFloat(text[1]), Float.parseFloat(text[2]));
                    stream.showText(text[0]);
                    stream.endText();
                }
                stream.moveTo(5, 125);
                stream.lineTo(150, 125);
                stream.stroke();
                stream.drawImage(image, 160, 10, 15, 15);
            }
            if (second) {
                final var scan = new PDPage(new PDRectangle(200, 200));
                document.addPage(scan);
                try (final var stream = new PDPageContentStream(document, scan)) {
                    stream.drawImage(image, 10, 10, 15, 15);
                }
            }
            final var output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        }
    }
}
