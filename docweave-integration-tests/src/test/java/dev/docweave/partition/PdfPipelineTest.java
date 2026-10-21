package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.chunking.ChunkOptions;
import dev.docweave.chunking.Chunker;
import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
import dev.docweave.json.ElementJson;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PdfPipelineTest {
    /**
     * Retains native cell lines in JSON and original provenance when structural chunks normalize cell whitespace.
     * @throws Exception if owned source creation, extraction or JSON round-tripping fails
     */
    @Test
    void nativeCellLineBreaksSurviveJsonAndChunkSourceProvenance() throws Exception {
        final byte[] bytes;
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(200, 200));
            document.addPage(page);
            try (final var stream = new PDPageContentStream(document, page)) {
                int baseline = 150;
                for (final String value : List.of("A & <B>", "Second line")) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                    stream.newLineAtOffset(30, baseline);
                    stream.showText(value);
                    stream.endText();
                    baseline -= 12;
                }
            }
            document.save(output);
            bytes = output.toByteArray();
        }
        final var bounds = new PdfTableLayoutProvider.Box(20, 30, 180, 90);
        final PdfTableLayoutProvider provider = image
                -> List.of(new PdfTableLayoutProvider.Table(1, 1, bounds,
                        List.of(new PdfTableLayoutProvider.Anchor(0, 0, 1, 1, bounds)), "owned-multiline"));
        final var partitioner = PdfPartitioner.createWithNativeLayout(
                PdfOptions.defaults().withTableCellCoordinates(true),
                new PdfNativeLayoutOptions(72, 40_000, 16, PdfNativeLayoutOptions.CellTextJoin.PRESERVE_LINE_BREAKS),
                provider);
        final var extracted = DocWeave.create(PartitionerRegistry.of(List.of(partitioner)))
                                      .partition(DocumentSource.bytes(bytes, "multiline.pdf"))
                                      .elements();
        assertEquals(1, extracted.size());
        final var table = extracted.getFirst();
        assertEquals("A & <B>\nSecond line", table.text());
        assertEquals("<table><tbody><tr><td>A &amp; &lt;B&gt;<br>Second line</td></tr></tbody></table>",
                table.metadata().get("text_as_html"));
        final var json = ElementJson.create();
        final var roundTrip = json.read(json.write(extracted));
        assertEquals(extracted, roundTrip);
        final var chunker = Chunker.create();
        assertEquals(roundTrip, chunker.basic(roundTrip, ChunkOptions.builder().skipTableChunking(true).build()));
        final var chunks = chunker.basic(roundTrip, ChunkOptions.builder().maxCharacters(4096).build());
        assertEquals(1, chunks.size());
        assertEquals("A & <B> Second line", chunks.getFirst().text());
        assertFalse(chunks.getFirst().metadata().containsKey("pdf_table_multiline_cells"));
        assertFalse(chunks.getFirst().metadata().containsKey("pdf_table_cell_text_policy"));
        assertEquals(roundTrip, json.decodeOriginals((String) chunks.getFirst().metadata().get("orig_elements")));
        final var textOnly = chunker.basic(roundTrip, ChunkOptions.builder().maxCharacters(8).build());
        for (final var chunk : textOnly) {
            assertFalse(chunk.metadata().containsKey("pdf_table_multiline_cells"));
            assertFalse(chunk.metadata().containsKey("pdf_table_cell_text_policy"));
            assertEquals(roundTrip, json.decodeOriginals((String) chunk.metadata().get("orig_elements")));
        }
    }

    @Test
    void nativePdfTableAndImageSurviveJsonAndStructuralChunking() throws Exception {
        final byte[] bytes;
        try (final var document = new PDDocument()) {
            final var page = new PDPage();
            document.addPage(page);
            try (final var stream = new PDPageContentStream(document, page)) {
                for (final int y : new int[] {700, 670, 640}) {
                    stream.moveTo(40, y);
                    stream.lineTo(240, y);
                }
                for (final int x : new int[] {40, 140, 240}) {
                    stream.moveTo(x, 640);
                    stream.lineTo(x, 700);
                }
                stream.stroke();
                final var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                for (int row = 0; row < 2; row++) {
                    for (int column = 0; column < 2; column++) {
                        stream.beginText();
                        stream.setFont(font, 10);
                        stream.newLineAtOffset(45 + 100 * column, 680 - 30 * row);
                        stream.showText("cell" + row + column);
                        stream.endText();
                    }
                }
                stream.drawImage(
                        LosslessFactory.createFromImage(document, new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)),
                        40, 600, 20, 20);
            }
            final var output = new ByteArrayOutputStream();
            document.save(output);
            bytes = output.toByteArray();
        }
        final var result = DocWeave.create().partition(DocumentSource.bytes(bytes, "pipeline.pdf"));
        assertEquals(
                List.of(ElementType.Table, ElementType.Image), result.elements().stream().map(e -> e.type()).toList());
        final var json = ElementJson.create();
        assertEquals(result.elements(), json.read(json.write(result.elements())));
        final var table = result.elements().getFirst();
        final var chunks = Chunker.create().basic(List.of(table), ChunkOptions.builder().maxCharacters(80).build());
        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(e -> e.type() == ElementType.TableChunk));
        assertTrue(chunks.stream().allMatch(e -> e.metadata().containsKey("text_as_html")));
        assertEquals("cell00 cell01 cell10 cell11", String.join(" ", chunks.stream().map(e -> e.text()).toList()));
    }

    @Test
    void mergedPdfCellsSurviveJsonAndSpanAwareTableChunking() throws Exception {
        final byte[] bytes;
        try (final var document = new PDDocument()) {
            final var page = new PDPage();
            document.addPage(page);
            try (final var stream = new PDPageContentStream(document, page)) {
                stream.moveTo(40, 700);
                stream.lineTo(240, 700);
                stream.moveTo(140, 670);
                stream.lineTo(240, 670);
                stream.moveTo(40, 640);
                stream.lineTo(240, 640);
                for (final int x : new int[] {40, 140, 240}) {
                    stream.moveTo(x, 640);
                    stream.lineTo(x, 700);
                }
                stream.stroke();
                final var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                final List<String> values = List.of("Tall", "One", "Two");
                for (int index = 0; index < values.size(); index++) {
                    stream.beginText();
                    stream.setFont(font, 10);
                    stream.newLineAtOffset(index == 0 ? 45 : 145, index == 2 ? 650 : 680);
                    stream.showText(values.get(index));
                    stream.endText();
                }
            }
            final var output = new ByteArrayOutputStream();
            document.save(output);
            bytes = output.toByteArray();
        }
        final var elements = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(
                                                     PdfOptions.defaults().withTableCellCoordinates(true)))))
                                     .partition(DocumentSource.bytes(bytes, "merged.pdf"))
                                     .elements();
        assertEquals(1, elements.size());
        assertEquals("Tall One Two", elements.getFirst().text());
        assertCellGeometrySurvivesJsonAndChunkProvenance(elements.getFirst());
        assertEquals(
                "<table><tbody><tr><td rowspan=\"2\">Tall</td><td>One</td></tr><tr><td>Two</td></tr></tbody></table>",
                elements.getFirst().metadata().get("text_as_html"));
        final var json = ElementJson.create();
        assertEquals(elements, json.read(json.write(elements)));
        final var chunks = Chunker.create().basic(elements, ChunkOptions.builder().maxCharacters(70).build());
        assertTrue(chunks.size() > 1);
        assertEquals("Tall One Two", String.join(" ", chunks.stream().map(e -> e.text()).toList()));
        assertTrue(chunks.stream().allMatch(
                e -> e.type() == ElementType.TableChunk && ((String) e.metadata().get("text_as_html")).length() <= 70));
    }

    @Test
    void optedInBorderlessPdfSurvivesJsonAndRealStructuralChunking() throws Exception {
        final byte[] bytes;
        try (final var document = new PDDocument()) {
            final var page = new PDPage();
            document.addPage(page);
            try (final var stream = new PDPageContentStream(document, page)) {
                final var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                final var rows = List.of(List.of("Item", "Qty"), List.of("Alpha", "10"), List.of("Beta", "20"));
                for (int row = 0; row < rows.size(); row++) {
                    for (int column = 0; column < 2; column++) {
                        stream.beginText();
                        stream.setFont(font, 12);
                        stream.newLineAtOffset(column == 0 ? 40 : 220, 700 - 20 * row);
                        stream.showText(rows.get(row).get(column));
                        stream.endText();
                    }
                }
            }
            final var output = new ByteArrayOutputStream();
            document.save(output);
            bytes = output.toByteArray();
        }
        final var options = PdfOptions.defaults().withTableDetection(PdfOptions.TableDetection.RULED_AND_ALIGNED_TEXT);
        final var processor = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options))));
        final var elements = processor.partition(DocumentSource.bytes(bytes, "aligned.pdf")).elements();
        assertEquals(1, elements.size());
        assertEquals(ElementType.Table, elements.getFirst().type());
        final var json = ElementJson.create();
        assertEquals(elements, json.read(json.write(elements)));
        final var chunks = Chunker.create().basic(elements, ChunkOptions.builder().maxCharacters(80).build());
        assertTrue(chunks.size() > 1);
        assertEquals("Item Qty Alpha 10 Beta 20", String.join(" ", chunks.stream().map(e -> e.text()).toList()));
        assertTrue(chunks.stream().allMatch(
                e -> e.type() == ElementType.TableChunk && ((String) e.metadata().get("text_as_html")).length() <= 80));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void scannedPdfTableSurvivesJsonAndStructuralChunkingWithoutDuplicateText(final boolean merged) throws Exception {
        final byte[] bytes;
        final var raster = new BufferedImage(400, 280, BufferedImage.TYPE_INT_RGB);
        final var drawing = raster.createGraphics();
        try {
            drawing.setColor(java.awt.Color.WHITE);
            drawing.fillRect(0, 0, 400, 280);
            drawing.setColor(java.awt.Color.BLACK);
            drawing.setStroke(new java.awt.BasicStroke(2));
            for (final int x : List.of(20, 200, 380)) {
                drawing.drawLine(x, 40, x, 240);
            }
            for (final int y : List.of(40, 140, 240)) {
                drawing.drawLine(merged && y == 140 ? 200 : 20, y, 380, y);
            }
        } finally {
            drawing.dispose();
        }
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(400, 280));
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                content.drawImage(LosslessFactory.createFromImage(document, raster), 0, 0, 400, 280);
            }
            document.save(output);
            bytes = output.toByteArray();
        } finally {
            raster.flush();
        }
        final var allValues = List.of("first-cell-content-0123456789", "second-cell-content-0123456789",
                "third-cell-content-0123456789", "fourth-cell-content-0123456789");
        final var values = merged ? allValues.subList(0, 3) : allValues;
        final var cell = new java.util.concurrent.atomic.AtomicInteger();
        final var processor = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(
                PdfOptions.defaults().withTableCellCoordinates(true),
                new PdfOcrOptions(72, 1_000_000, 100, 1000).withTableDetection(PdfOcrOptions.TableDetection.RULED),
                image
                -> image.getWidth() == 400
                           ? List.of()
                           : List.of(new PdfOcrProvider.Line(values.get(cell.getAndIncrement()), 15, 15, 60, 35))))));
        final var result = processor.partition(DocumentSource.bytes(bytes, "scanned-pipeline.pdf"));
        assertEquals(values.size(), cell.get());
        assertEquals(List.of(ElementType.Image, ElementType.Table),
                result.elements().stream().map(element -> element.type()).toList());
        final var json = ElementJson.create();
        assertEquals(result.elements(), json.read(json.write(result.elements())));
        final var table = result.elements().getLast();
        assertCellGeometrySurvivesJsonAndChunkProvenance(table);
        if (merged) {
            assertTrue(table.metadata().get("text_as_html").toString().contains("rowspan=\"2\""));
        }
        final var chunks = Chunker.create().basic(List.of(table), ChunkOptions.builder().maxCharacters(100).build());
        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(element -> element.type() == ElementType.TableChunk));
        assertTrue(chunks.stream().allMatch(element -> element.metadata().containsKey("text_as_html")));
        assertEquals(
                String.join(" ", values), String.join(" ", chunks.stream().map(element -> element.text()).toList()));
    }

    @Test
    void continuationLinksRoundTripButDoNotBecomeDanglingChunkReferences() throws Exception {
        final byte[] pdf;
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            for (final int top : List.of(400, 40)) {
                final var page = new PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(500, 800));
                document.addPage(page);
                try (final var content = new PDPageContentStream(document, page)) {
                    for (final int x : List.of(40, 220, 400)) {
                        content.moveTo(x, top == 400 ? 40 : 400);
                        content.lineTo(x, 800 - top);
                    }
                    for (final int y : List.of(800 - top, 760 - top, top == 400 ? 40 : 400)) {
                        content.moveTo(40, y);
                        content.lineTo(400, y);
                    }
                    content.stroke();
                    final var values = List.of("Item", "Qty", "Alpha", "10");
                    for (int index = 0; index < values.size(); index++) {
                        content.beginText();
                        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        content.newLineAtOffset(index % 2 == 0 ? 45 : 225, 800 - top - (index < 2 ? 25 : 65));
                        content.showText(values.get(index));
                        content.endText();
                    }
                }
            }
            document.save(output);
            pdf = output.toByteArray();
        }
        final var options = PdfOptions.defaults().withTableCellCoordinates(true).withTableContinuation(
                PdfOptions.TableContinuation.REPEATED_HEADER);
        final var elements = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options))))
                                     .partition(DocumentSource.bytes(pdf, "continued.pdf"))
                                     .elements();
        assertEquals(2, elements.size());
        final var root = elements.getFirst();
        final var continuation = elements.getLast();
        final var link = (java.util.Map<?, ?>) continuation.metadata().get("pdf_table_continuation");
        assertEquals(root.elementId(), link.get("root_element_id"));
        assertEquals(root.elementId(), link.get("previous_element_id"));
        final var json = ElementJson.create();
        assertEquals(elements, json.read(json.write(elements)));
        final var chunker = Chunker.create();
        assertEquals(elements, chunker.basic(elements, ChunkOptions.builder().skipTableChunking(true).build()));
        for (final int limit : List.of(4096, 70, 25)) {
            final var chunks =
                    chunker.basic(List.of(continuation), ChunkOptions.builder().maxCharacters(limit).build());
            assertFalse(chunks.isEmpty());
            for (final var chunk : chunks) {
                assertFalse(chunk.metadata().containsKey("pdf_table_continuation"));
                final var original = json.decodeOriginals((String) chunk.metadata().get("orig_elements")).getFirst();
                assertEquals(link, original.metadata().get("pdf_table_continuation"));
                assertEquals(continuation.elementId(), original.elementId());
            }
            final var plain = chunker.basic(List.of(continuation),
                    ChunkOptions.builder().maxCharacters(limit).includeOriginalElements(false).build());
            assertTrue(plain.stream().noneMatch(c -> c.metadata().containsKey("pdf_table_continuation")));
        }
    }

    /** Checks source geometry retention without assigning whole-table bounds to split fragments. */
    @Test
    void pageReadingIndexesRemainSourceOnlyThroughJsonAndTableChunking() throws Exception {
        final var table = new Element("ordered-source", ElementType.Table, "Alpha Beta Gamma Delta",
                java.util.Map.of("text_as_html",
                        "<table><tr><td>Alpha</td><td>Beta</td></tr><tr><td>Gamma</td><td>Delta</td></tr></table>",
                        "page_number", 1, "pdf_reading_order", "page_top_left_v1", "pdf_reading_index", 2));
        final var json = ElementJson.create();
        assertEquals(List.of(table), json.read(json.write(List.of(table))));
        final var chunker = Chunker.create();
        assertEquals(
                List.of(table), chunker.basic(List.of(table), ChunkOptions.builder().skipTableChunking(true).build()));
        for (final int limit : List.of(8, 4096)) {
            final var chunks = chunker.basic(List.of(table), ChunkOptions.builder().maxCharacters(limit).build());
            for (final var chunk : chunks) {
                assertFalse(chunk.metadata().containsKey("pdf_reading_index"));
                assertEquals(table, json.decodeOriginals((String) chunk.metadata().get("orig_elements")).getFirst());
            }
        }
    }

    @Test
    void marginClassificationSurvivesJsonAndRemainsSourceEvidenceWhenChunked() throws Exception {
        final var source =
                java.util.stream.IntStream.rangeClosed(1, 3)
                        .mapToObj(page
                                -> new Element("header-" + page, ElementType.Title, "Report heading",
                                        java.util.Map.of("page_number", page, "coordinates",
                                                java.util.Map.of("system", "PDFUserSpace", "layout_width", 600.0,
                                                        "layout_height", 1000.0, "points",
                                                        List.of(List.of(50.0, 20.0), List.of(200.0, 20.0),
                                                                List.of(200.0, 30.0), List.of(50.0, 30.0))))))
                        .toList();
        final var tagged = PdfMarginClassifier.create().classify(source, 3);
        final var json = ElementJson.create();
        assertEquals(tagged, json.read(json.write(tagged)));
        assertTrue(tagged.stream().allMatch(e -> e.type() == ElementType.Header));
        final var chunks = Chunker.create().basic(tagged, ChunkOptions.builder().maxCharacters(4096).build());
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().noneMatch(e -> e.metadata().containsKey("pdf_margin_classification")));
        final var originals =
                chunks.stream()
                        .flatMap(e -> json.decodeOriginals((String) e.metadata().get("orig_elements")).stream())
                        .toList();
        assertEquals(tagged, originals);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 90, 180, 270})
    void correctedOcrMapsToDisplayedCropFrameBeforeJsonAndChunking(final int rotation) throws Exception {
        final byte[] bytes;
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(100, 80));
            page.setCropBox(new org.apache.pdfbox.pdmodel.common.PDRectangle(10, 20, 20, 10));
            page.setRotation(rotation);
            page.setUserUnit(2);
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                content.drawImage(LosslessFactory.createFromImage(
                                          document, new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB)),
                        10, 20, 20, 10);
            }
            document.save(output);
            bytes = output.toByteArray();
        }
        final var options = new PdfOcrOptions(72, 1000, 10, 100);
        final var corrected =
                PdfOcrProvider.rotated(image -> List.of(new PdfOcrProvider.Line("corrected", 2, 3, 8, 6)), 90, options);
        final var result = DocWeave.create(PartitionerRegistry.of(List.of(
                                                   PdfPartitioner.create(PdfOptions.defaults(), options, corrected))))
                                   .partition(DocumentSource.bytes(bytes, "corrected.pdf"));
        assertEquals(1, result.elements().stream().filter(e -> e.type() == ElementType.Image).count());
        final var text =
                result.elements().stream().filter(e -> e.type() != ElementType.Image).findFirst().orElseThrow();
        assertEquals("corrected", text.text());
        final var coordinates = (java.util.Map<?, ?>)text.metadata().get("coordinates");
        final double height = rotation % 180 == 0 ? 10 : 20;
        assertEquals(List.of(List.of(3.0, height - 8), List.of(6.0, height - 8), List.of(6.0, height - 2),
                             List.of(3.0, height - 2)),
                coordinates.get("points"));
        assertEquals(2.0, ((Number) text.metadata().get("pdf_user_unit")).doubleValue());
        final var json = ElementJson.create();
        assertEquals(List.of(text), json.read(json.write(List.of(text))));
        final var chunk = Chunker.create().basic(List.of(text), ChunkOptions.builder().build()).getFirst();
        assertEquals(List.of(text), json.decodeOriginals((String) chunk.metadata().get("orig_elements")));
    }

    @Test
    void arbitraryAngleNativeGeometrySurvivesJsonUnitsAndSourceChunkProvenance() throws Exception {
        final byte[] bytes;
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage();
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.setTextMatrix(org.apache.pdfbox.util.Matrix.getRotateInstance(Math.PI / 6, 100, 200));
                content.showText("Angled native line");
                content.endText();
            }
            document.save(output);
            bytes = output.toByteArray();
        }
        final var options = PdfOptions.defaults().withTextLayout(PdfOptions.TextLayout.ORIENTED_LINES);
        final var elements = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options))))
                                     .partition(DocumentSource.bytes(bytes, "angled-pipeline.pdf"))
                                     .elements();
        assertEquals(1, elements.size());
        final var original = elements.getFirst();
        assertEquals("Angled native line", original.text());
        final var json = ElementJson.create();
        for (final var element : List.of(original, PdfCoordinateConverter.create().toPixels(original, 300))) {
            assertEquals(List.of(element), json.read(json.write(List.of(element))));
            assertEquals(330.0, element.metadata().get("pdf_text_rotation"));
            final var chunk = Chunker.create().basic(List.of(element), ChunkOptions.builder().build()).getFirst();
            assertFalse(chunk.metadata().containsKey("pdf_text_geometry"));
            final var restored = json.decodeOriginals((String) chunk.metadata().get("orig_elements"));
            assertEquals(json.decodeOriginals(json.encodeOriginals(List.of(element))), restored);
            assertEquals("projected_baseline_v1", restored.getFirst().metadata().get("pdf_text_geometry"));
        }
    }

    private static void assertCellGeometrySurvivesJsonAndChunkProvenance(final Element table) {
        final var json = ElementJson.create();
        final var converter = PdfCoordinateConverter.create();
        for (final var converted :
                List.of(converter.toPoints(table), converter.toMillimeters(table), converter.toPixels(table, 300))) {
            assertEquals(List.of(converted), json.read(json.write(List.of(converted))));
            assertEquals(table.elementId(), converted.elementId());
            assertEquals(table.text(), converted.text());
            final var convertedWhole =
                    Chunker.create()
                            .basic(List.of(converted), ChunkOptions.builder().maxCharacters(4096).build())
                            .getFirst();
            assertEquals(converted.metadata().get("pdf_table_cells"), convertedWhole.metadata().get("pdf_table_cells"));
            final var restored = converter.toUserSpace(convertedWhole);
            assertFalse(restored.metadata().containsKey("coordinates"));
            final var restoredGrid = (java.util.Map<?, ?>)restored.metadata().get("pdf_table_cells");
            for (final var cell : (List<?>) restoredGrid.get("cells")) {
                assertEquals("PDFUserSpace", ((java.util.Map<?, ?>)((java.util.Map<?, ?>)cell).get("coordinates")).get("system"));
            }
            final var fragments =
                    Chunker.create().basic(List.of(converted), ChunkOptions.builder().maxCharacters(25).build());
            for (final var fragment : fragments) {
                final var original = json.decodeOriginals((String) fragment.metadata().get("orig_elements"));
                assertEquals(json.decodeOriginals(json.encodeOriginals(List.of(converted))), original);
                assertEquals(converted.metadata().get("pdf_table_cells"),
                        original.getFirst().metadata().get("pdf_table_cells"));
            }
        }
        final var geometry = table.metadata().get("pdf_table_cells");
        assertNotNull(geometry);
        assertEquals(List.of(table), json.read(json.write(List.of(table))));
        final var chunker = Chunker.create();
        final var whole = chunker.basic(List.of(table), ChunkOptions.builder().maxCharacters(4096).build());
        assertEquals(geometry, whole.getFirst().metadata().get("pdf_table_cells"));
        final var passthrough = chunker.basic(List.of(table), ChunkOptions.builder().skipTableChunking(true).build());
        assertEquals(List.of(table), passthrough);
        for (final int limit : List.of(70, 25)) {
            final var chunks = chunker.basic(List.of(table), ChunkOptions.builder().maxCharacters(limit).build());
            assertFalse(chunks.isEmpty());
            if (limit >= 50) {
                assertTrue(chunks.size() > 1);
            }
            assertTrue(chunks.stream().allMatch(c -> c.type() == ElementType.TableChunk));
            for (final var chunk : chunks) {
                assertFalse(chunk.metadata().containsKey("pdf_table_cells"));
                final var original = json.decodeOriginals((String) chunk.metadata().get("orig_elements"));
                assertEquals(geometry, original.getFirst().metadata().get("pdf_table_cells"));
            }
            final var withoutProvenance = chunker.basic(
                    List.of(table), ChunkOptions.builder().maxCharacters(limit).includeOriginalElements(false).build());
            assertTrue(withoutProvenance.stream().allMatch(
                    c -> !c.metadata().containsKey("pdf_table_cells") && !c.metadata().containsKey("orig_elements")));
        }
    }
}
