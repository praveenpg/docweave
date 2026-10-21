package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.batch.BatchProcessor;
import dev.docweave.chunking.ChunkOptions;
import dev.docweave.chunking.Chunker;
import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
import dev.docweave.json.ElementJson;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class DocxPartitionTest {
    private final DocWeave processor = DocWeave.create();

    private byte[] document(final Consumer<XWPFDocument> write) throws Exception {
        try (final var document = new XWPFDocument(); final var output = new ByteArrayOutputStream()) {
            write.accept(document);
            document.write(output);
            return output.toByteArray();
        }
    }

    private XWPFParagraph paragraph(final XWPFDocument doc, final String text, final String style) {
        final var p = doc.createParagraph();
        if (style != null) {
            p.setStyle(style);
        }
        p.createRun().setText(text);
        return p;
    }

    private PartitionResult parse(final byte[] bytes) throws Exception {
        return processor.partition(DocumentSource.bytes(bytes, "report.docx"));
    }

    private List<String> texts(final PartitionResult result) {
        return result.elements().stream().map(Element::text).toList();
    }

    private DocxOptions budgets(final int entries, final long total, final int part, final int depth, final int nodes,
            final TableLimits tables) {
        return new DocxOptions(entries, total, part, depth, nodes, false, tables);
    }

    private void fails(final byte[] bytes, final DocxOptions options, final PartitionException.Code code) {
        assertEquals(code, assertThrows(PartitionException.class,
                                   ()
                                           -> processor.partition(DocumentSource.bytes(bytes, "report.docx"),
                                                   PartitionOptions.builder().docxOptions(options).build()))
                                   .code());
    }

    private Map<String, byte[]> entries(final byte[] bytes) throws Exception {
        final var result = new LinkedHashMap<String, byte[]>();
        try (final var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                result.put(entry.getName(), zip.readAllBytes());
            }
        }
        return result;
    }

    private byte[] zip(final Map<String, byte[]> entries) throws Exception {
        final var out = new ByteArrayOutputStream();
        try (final var zip = new ZipOutputStream(out)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private void style(final XWPFDocument doc, final String id, final String basedOn, final Integer outline) {
        final var styles = doc.getStyles() == null ? doc.createStyles() : doc.getStyles();
        final var ct = CTStyle.Factory.newInstance();
        ct.setStyleId(id);
        ct.addNewName().setVal(id);
        if (basedOn != null) {
            ct.addNewBasedOn().setVal(basedOn);
        }
        if (outline != null) {
            ct.addNewPPr().addNewOutlineLvl().setVal(BigInteger.valueOf(outline));
        }
        styles.addStyle(new XWPFStyle(ct));
    }

    private void list(final XWPFDocument doc, final String text, final int level) {
        final var p = paragraph(doc, text, null);
        p.setNumID(BigInteger.ONE);
        p.getCTP().getPPr().getNumPr().addNewIlvl().setVal(BigInteger.valueOf(level));
    }

    @Test
    void preservesBodyOrderAndHeadingParentageAroundTables() throws Exception {
        final var bytes = document(doc -> {
            paragraph(doc, "Report", "Heading1");
            paragraph(doc, "Before.", null);
            final var table = doc.createTable(1, 2);
            table.getRow(0).getCell(0).setText("one");
            table.getRow(0).getCell(1).setText("two");
            paragraph(doc, "Details", "Heading2");
            paragraph(doc, "After.", null);
        });
        final var result = parse(bytes);
        assertEquals(List.of("Report", "Before.", "one\ttwo", "Details", "After."), texts(result));
        assertEquals(List.of(ElementType.Title, ElementType.NarrativeText, ElementType.Table, ElementType.Title,
                             ElementType.NarrativeText),
                result.elements().stream().map(Element::type).toList());
        assertEquals(result.elements().getFirst().elementId(), result.elements().get(3).metadata().get("parent_id"));
        assertEquals(result.elements().get(3).elementId(), result.elements().getLast().metadata().get("parent_id"));
        assertTrue(result.elements().stream().noneMatch(e -> e.metadata().containsKey("page_number")));
        assertEquals("binary", result.encoding());
    }

    @Test
    void inheritedStylesAndDirectOutlineLevelsTakePrecedenceOverStyleNames() throws Exception {
        final var result = parse(document(doc -> {
            style(doc, "Section", null, 1);
            style(doc, "Custom", "Section", null);
            paragraph(doc, "Inherited", "Custom");
            final var p = paragraph(doc, "Direct body", "Heading1");
            p.getCTP().getPPr().addNewOutlineLvl().setVal(BigInteger.valueOf(9));
            style(doc, "Heading2", null, 3);
            paragraph(doc, "Outline priority", "Heading2");
        }));
        assertEquals(ElementType.Title, result.elements().getFirst().type());
        assertEquals(1, result.elements().getFirst().metadata().get("category_depth"));
        assertEquals(ElementType.NarrativeText, result.elements().get(1).type());
        assertEquals(3, result.elements().getLast().metadata().get("category_depth"));
    }

    @Test
    void listsKeepDepthAndParentAnchorsWithoutInventingNumberLabels() throws Exception {
        final var result = parse(document(doc -> {
            paragraph(doc, "Tasks", "Heading1");
            list(doc, "Parent", 0);
            list(doc, "Child", 1);
            list(doc, "Sibling", 0);
        }));
        assertEquals(List.of("Tasks", "Parent", "Child", "Sibling"), texts(result));
        assertEquals(ElementType.ListItem, result.elements().get(2).type());
        assertEquals(result.elements().get(1).elementId(), result.elements().get(2).metadata().get("parent_id"));
        assertEquals(result.elements().getFirst().elementId(), result.elements().getLast().metadata().get("parent_id"));
        assertEquals("1", result.elements().get(2).metadata().get("docx_num_id"));
    }

    @Test
    void hyperlinkOffsetsCountUnicodeAndNormalizeAcrossRuns() throws Exception {
        final var result = parse(document(doc -> {
            final var p = doc.createParagraph();
            p.createRun().setText("  🧵 ");
            p.createHyperlinkRun("https://example.com/guide").setText("  Guide  ");
            final var r = p.createRun();
            r.addTab();
            r.setText("again");
            r.addBreak();
            r.setText("end");
        }));
        assertEquals(List.of("🧵 Guide again end"), texts(result));
        assertEquals(List.of(2), result.elements().getFirst().metadata().get("link_start_indexes"));
        assertEquals(List.of("Guide"), result.elements().getFirst().metadata().get("link_texts"));
        assertEquals(List.of("https://example.com/guide"), result.elements().getFirst().metadata().get("link_urls"));
    }

    @Test
    void tableRowsHeadersEmptyCellsAndEscapingArePreserved() throws Exception {
        final var result = parse(document(doc -> {
            final var t = doc.createTable(2, 2);
            t.getRow(0).setRepeatHeader(true);
            t.getRow(0).getCell(0).setText("Name");
            t.getRow(0).getCell(1).setText("Id");
            t.getRow(1).getCell(0).setText("<tag> & 🧵");
        }));
        assertEquals(List.of("Name\tId\n<tag> & 🧵\t"), texts(result));
        final var m = result.elements().getFirst().metadata();
        assertEquals(2, m.get("table_row_count"));
        assertEquals(2, m.get("table_column_count"));
        assertEquals(1, m.get("table_header_rows"));
        assertTrue(m.get("text_as_html").toString().contains("<th>Name</th>"));
        assertTrue(m.get("text_as_html").toString().contains("&lt;tag&gt; &amp; 🧵"));
    }

    @Test
    void horizontalAndVerticalMergesProduceSpansAndLogicalTextPlaceholders() throws Exception {
        final var result = parse(document(doc -> {
            final var t = doc.createTable(2, 2);
            final var a = t.getRow(0).getCell(0);
            a.setText("Merged");
            final var pr = a.getCTTc().addNewTcPr();
            pr.addNewGridSpan().setVal(BigInteger.TWO);
            pr.addNewVMerge().setVal(STMerge.RESTART);
            t.getRow(0).removeCell(1);
            final var b = t.getRow(1).getCell(0);
            final var bp = b.getCTTc().addNewTcPr();
            bp.addNewGridSpan().setVal(BigInteger.TWO);
            bp.addNewVMerge().setVal(STMerge.CONTINUE);
            t.getRow(1).removeCell(1);
        }));
        assertEquals(List.of("Merged\t\n\t"), texts(result));
        assertEquals("<table><tr><td colspan=\"2\" rowspan=\"2\">Merged</td></tr><tr></tr></table>",
                result.elements().getFirst().metadata().get("text_as_html"));
    }

    @Test
    void cellParagraphsAndLinksPreserveCodePointOffsets() throws Exception {
        final var result = parse(document(doc -> {
            final var c = doc.createTable(1, 1).getRow(0).getCell(0);
            c.getParagraphs().getFirst().createRun().setText("🧵");
            c.addParagraph().createHyperlinkRun("https://example.com/").setText("linked");
        }));
        assertEquals(List.of("🧵\nlinked"), texts(result));
        assertEquals(List.of(2), result.elements().getFirst().metadata().get("link_start_indexes"));
        assertTrue(result.elements().getFirst().metadata().get("text_as_html").toString().contains("🧵<br>linked"));
    }

    @Test
    void nestedTablesFlattenInCellOrderAndReportTheirPolicy() throws Exception {
        final var result = parse(document(doc -> {
            final var c = doc.createTable(1, 1).getRow(0).getCell(0);
            c.getParagraphs().getFirst().createRun().setText("Before");
            final var ct = c.getCTTc().addNewTbl();
            ct.addNewTblPr();
            ct.addNewTblGrid().addNewGridCol();
            ct.addNewTr().addNewTc().addNewP().addNewR().addNewT().setStringValue("Nested");
            c.getCTTc().addNewP().addNewR().addNewT().setStringValue("After");
        }));
        assertEquals(List.of("Before\nNested\nAfter"), texts(result));
        assertTrue(result.warnings().stream().anyMatch(
                w -> w.code() == PartitionResult.WarningCode.NESTED_TABLE_FLATTENED));
    }

    @Test
    void styleNumberingAndCyclesRemainBounded() throws Exception {
        final var result = parse(document(doc -> {
            final var ctStyle = CTStyle.Factory.newInstance();
            ctStyle.setStyleId("Numbered");
            final var np = ctStyle.addNewPPr().addNewNumPr();
            np.addNewNumId().setVal(BigInteger.TWO);
            np.addNewIlvl().setVal(BigInteger.ONE);
            doc.createStyles().addStyle(new XWPFStyle(ctStyle));
            paragraph(doc, "Style list", "Numbered");
            style(doc, "CycleA", "CycleB", null);
            style(doc, "CycleB", "CycleA", null);
            paragraph(doc, "Cycle body", "CycleA");
        }));
        assertEquals(ElementType.ListItem, result.elements().getFirst().type());
        assertEquals("2", result.elements().getFirst().metadata().get("docx_num_id"));
        assertEquals(1, result.elements().getFirst().metadata().get("category_depth"));
        assertEquals(ElementType.NarrativeText, result.elements().getLast().type());
    }

    @Test
    void extractsFootnotesAndDiagnosesDrawingsWithNoExtractableContent() throws Exception {
        final var result = parse(document(doc -> {
            final var p = paragraph(doc, "Body", null);
            p.getRuns().getFirst().getCTR().addNewDrawing();
            doc.createFootnote().createParagraph().createRun().setText("Note");
        }));
        assertEquals(List.of("Body", "Note"), texts(result));
        assertEquals("footnote", result.elements().getLast().metadata().get("docx_part"));
        assertTrue(
                result.warnings().stream().anyMatch(w -> w.code() == PartitionResult.WarningCode.DOCX_CONTENT_OMITTED));
    }

    @Test
    void inlineContentControlsUseTheirTextRatherThanObjectRepresentation() throws Exception {
        final var result = parse(document(doc -> {
            final var p = doc.createParagraph();
            p.createRun().setText("Before ");
            p.getCTP().addNewSdt().addNewSdtContent().addNewR().addNewT().setStringValue("inside");
            p.createRun().setText(" after");
        }));
        assertEquals(List.of("Before inside after"), texts(result));
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void binaryPayloadIsDefensiveAndUnavailableAsText() throws Exception {
        final var bytes = document(doc -> paragraph(doc, "Body", null));
        final Partitioner inspector = new Partitioner() {
            public DocumentFormat format() {
                return DocumentFormat.DOCX;
            }
            public void partition(final PartitionContext context) throws java.io.IOException {
                assertThrows(IllegalStateException.class, context::text);
                final var first = context.binaryContent();
                first[0] = 0;
                assertEquals(0x50, context.binaryContent()[0]);
                context.emit(ElementType.NarrativeText, "Custom", Map.of());
            }
        };
        final var result = DocWeave.create(PartitionerRegistry.of(List.of(inspector)))
                                   .partition(DocumentSource.bytes(bytes, "report.docx"));
        assertEquals(List.of("Custom"), texts(result));
        assertEquals(PartitionException.Code.UNSUPPORTED_FORMAT,
                assertThrows(PartitionException.class,
                        ()
                                -> DocWeave.create(PartitionerRegistry.of(List.of()))
                                        .partition(DocumentSource.bytes(bytes, null)))
                        .code());
    }

    @Test
    void headerFooterPolicyEmitsStoredPartsOnceAndDoesNotAffectBodyHierarchy() throws Exception {
        final var bytes = document(doc -> {
            doc.createHeader(HeaderFooterType.DEFAULT).createParagraph().createRun().setText("Header");
            paragraph(doc, "Body", "Heading1");
            doc.createFooter(HeaderFooterType.DEFAULT).createParagraph().createRun().setText("Footer");
        });
        assertEquals(List.of("Body"), texts(parse(bytes)));
        final var defaults = DocxOptions.defaults();
        final var options = new DocxOptions(defaults.maxArchiveEntries(), defaults.maxExpandedBytes(),
                defaults.maxEntryBytes(), defaults.maxXmlDepth(), defaults.maxXmlNodes(), true, defaults.tableLimits());
        final var result = processor.partition(
                DocumentSource.bytes(bytes, "report.docx"), PartitionOptions.builder().docxOptions(options).build());
        assertEquals(List.of("Header", "Body", "Footer"), texts(result));
        assertEquals(List.of(ElementType.Header, ElementType.Title, ElementType.Footer),
                result.elements().stream().map(Element::type).toList());
        assertFalse(result.elements().get(1).metadata().containsKey("parent_id"));
    }

    @Test
    void packageDetectionWorksForFilesAnonymousBytesAndMisleadingNames(final @TempDir Path dir) throws Exception {
        final var bytes = document(doc -> paragraph(doc, "Detected", null));
        final Path file = dir.resolve("report.docx");
        Files.write(file, bytes);
        assertEquals(DocumentFormat.DOCX, processor.partition(DocumentSource.of(file)).format());
        assertEquals(DocumentFormat.DOCX, processor.partition(DocumentSource.bytes(bytes, null)).format());
        assertEquals(PartitionResult.WarningCode.FORMAT_MISMATCH,
                processor.partition(DocumentSource.bytes(bytes, "wrong.txt")).warnings().getFirst().code());
        assertEquals(PartitionException.Code.UNSUPPORTED_FORMAT,
                assertThrows(PartitionException.class,
                        () -> processor.partition(DocumentSource.text("not binary", "report.docx")))
                        .code());
        assertEquals(PartitionException.Code.UNSUPPORTED_FORMAT,
                assertThrows(PartitionException.class,
                        ()
                                -> processor.partition(DocumentSource.bytes(bytes, null),
                                        PartitionOptions.builder().format(DocumentFormat.TXT).build()))
                        .code());
    }

    @Test
    void compressedAndExpandedBudgetsApplyBeforeExtraction() throws Exception {
        final var bytes = document(doc -> paragraph(doc, "x".repeat(20_000), null));
        fails(bytes, budgets(1, 1_000_000, 100_000, 256, 100_000, TableLimits.defaults()),
                PartitionException.Code.ARCHIVE_LIMIT);
        fails(bytes, budgets(100, 10, 10, 256, 100_000, TableLimits.defaults()), PartitionException.Code.ARCHIVE_LIMIT);
        fails(bytes, budgets(100, 1_000_000, 100, 256, 100_000, TableLimits.defaults()),
                PartitionException.Code.ARCHIVE_LIMIT);
        assertEquals(PartitionException.Code.INPUT_TOO_LARGE,
                assertThrows(PartitionException.class,
                        ()
                                -> processor.partition(DocumentSource.bytes(bytes, null),
                                        PartitionOptions.builder().maxInputBytes(10).build()))
                        .code());
    }

    @Test
    void xmlAndTableBudgetsAreIndependent() throws Exception {
        final var bytes = document(doc -> {
            paragraph(doc, "Body", null);
            doc.createTable(2, 2).getRow(0).getCell(0).setText("🧵ab");
        });
        fails(bytes, budgets(100, 1_000_000, 100_000, 1, 100_000, TableLimits.defaults()),
                PartitionException.Code.ARCHIVE_LIMIT);
        fails(bytes, budgets(100, 1_000_000, 100_000, 256, 1, TableLimits.defaults()),
                PartitionException.Code.ARCHIVE_LIMIT);
        for (final var limits : List.of(new TableLimits(1, 10, 100, 100), new TableLimits(10, 1, 100, 100),
                     new TableLimits(10, 10, 3, 100), new TableLimits(10, 10, 100, 2))) {
            fails(bytes, budgets(100, 1_000_000, 100_000, 256, 100_000, limits), PartitionException.Code.TABLE_LIMIT);
        }
    }

    @Test
    void rejectsMacroPackagesOrdinaryZipsAndMalformedXml() throws Exception {
        final var original = document(doc -> paragraph(doc, "Body", null));
        var parts = entries(original);
        parts.put("word/vbaProject.bin", new byte[] {1});
        fails(zip(parts), DocxOptions.defaults(), PartitionException.Code.UNSUPPORTED_FORMAT);
        parts = entries(original);
        parts.put("word/document.xml", "<broken>".getBytes(StandardCharsets.UTF_8));
        fails(zip(parts), DocxOptions.defaults(), PartitionException.Code.MALFORMED_DOCUMENT);
        fails(zip(Map.of("other.txt", "text".getBytes(StandardCharsets.UTF_8))), DocxOptions.defaults(),
                PartitionException.Code.UNSUPPORTED_FORMAT);
        parts = entries(original);
        parts.put("[Content_Types].xml", new String(parts.get("[Content_Types].xml"), StandardCharsets.UTF_8)
                                                 .replace(DocxArchiveVerifier.MAIN_TYPE,
                                                         "application/vnd.ms-word.document.macroEnabled.main+xml")
                                                 .getBytes(StandardCharsets.UTF_8));
        fails(zip(parts), DocxOptions.defaults(), PartitionException.Code.UNSUPPORTED_FORMAT);
    }

    @Test
    void rejectsDtdExternalEntitiesAndXmlPartsWithNonXmlExtensions() throws Exception {
        final var original = document(doc -> paragraph(doc, "Body", null));
        for (final String dtd : List.of("<!DOCTYPE w:document>",
                     "<!DOCTYPE w:document [<!ENTITY e SYSTEM 'http://127.0.0.1:9/forbidden'>]>",
                     "<!DOCTYPE w:document [<!ENTITY e SYSTEM 'file:///missing'>]>")) {
            var parts = entries(original);
            String main = new String(parts.get("word/document.xml"), StandardCharsets.UTF_8);
            final int end = main.indexOf("?>");
            main = main.substring(0, end + 2) + dtd + main.substring(end + 2);
            parts.put("word/document.xml", main.getBytes(StandardCharsets.UTF_8));
            fails(zip(parts), DocxOptions.defaults(), PartitionException.Code.UNSAFE_XML);
        }
        var parts = entries(original);
        parts.put("custom/part.bin", "<!DOCTYPE r><r/>".getBytes(StandardCharsets.UTF_8));
        final String types =
                new String(parts.get("[Content_Types].xml"), StandardCharsets.UTF_8)
                        .replace("</Types>",
                                "<Override PartName='/custom/part.bin' ContentType='application/xml'/></Types>");
        parts.put("[Content_Types].xml", types.getBytes(StandardCharsets.UTF_8));
        fails(zip(parts), DocxOptions.defaults(), PartitionException.Code.UNSAFE_XML);
    }

    @Test
    void traversalNamesAndTruncatedArchivesFailExplicitly() throws Exception {
        var parts = entries(document(doc -> paragraph(doc, "Body", null)));
        parts.put("../escape", new byte[] {1});
        fails(zip(parts), DocxOptions.defaults(), PartitionException.Code.MALFORMED_DOCUMENT);
        fails(new byte[] {0x50, 0x4b, 3, 4, 0, 0}, DocxOptions.defaults(), PartitionException.Code.UNSUPPORTED_FORMAT);
    }

    @Test
    void jsonChunkingAndIdsPreserveSupportedElementsAndOriginals() throws Exception {
        final var bytes = document(doc -> {
            paragraph(doc, "Heading", "Heading1");
            paragraph(doc, "Body.", null);
        });
        final var elements = parse(bytes).elements();
        final var json = ElementJson.create();
        assertEquals(elements, parse(bytes).elements());
        assertEquals(elements, json.read(json.write(elements)));
        final var chunks = Chunker.create().byTitle(elements, ChunkOptions.defaults());
        assertEquals("Heading\n\nBody.", chunks.getFirst().text());
        assertEquals(elements, json.decodeOriginals((String) chunks.getFirst().metadata().get("orig_elements")));
    }

    @Test
    void reusableStrategyHandlesConcurrentDocumentsWithoutChangingPoiGlobals() throws Exception {
        final var defaults = org.apache.poi.openxml4j.util.ZipSecureFile.getMinInflateRatio();
        final var bytes = document(doc -> paragraph(doc, "Shared 🧵", "Heading1"));
        final var results =
                BatchProcessor.create().mapOrdered(IntStream.range(0, 20).boxed().toList(), 4, ignored -> parse(bytes));
        assertTrue(results.stream().allMatch(result -> texts(result).equals(List.of("Shared 🧵"))));
        assertEquals(defaults, org.apache.poi.openxml4j.util.ZipSecureFile.getMinInflateRatio());
    }

    @Test
    void independentlyConstructedOoxmlFixtureMatchesStoredBodyOrder() throws Exception {
        final var result = processor.partition(Path.of("benchmarks/fixtures/structured.docx"));
        assertEquals(List.of("Quarterly report", "🧵 See guide.", "Name\tId\nCafé & tea\t007", "Tasks", "Parent task",
                             "Child task", "Final paragraph."),
                texts(result));
        assertEquals(List.of(6), result.elements().get(1).metadata().get("link_start_indexes"));
        assertEquals(result.elements().get(4).elementId(), result.elements().get(5).metadata().get("parent_id"));
        assertEquals(result.elements().get(3).elementId(), result.elements().getLast().metadata().get("parent_id"));
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void optionValidationAndInterruptionAreExplicit() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> budgets(0, 100, 100, 1, 1, TableLimits.defaults()));
        assertThrows(IllegalArgumentException.class, () -> budgets(1, 100, 101, 1, 1, TableLimits.defaults()));
        assertThrows(IllegalArgumentException.class, () -> budgets(1, 100, 100, 257, 1, TableLimits.defaults()));
        assertThrows(NullPointerException.class, () -> PartitionOptions.builder().docxOptions(null).build());
        final var bytes = document(doc -> paragraph(doc, "Body", null));
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.io.InterruptedIOException.class, () -> parse(bytes));
        } finally {
            Thread.interrupted();
        }
    }
}
