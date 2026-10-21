package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.chunking.ChunkOptions;
import dev.docweave.chunking.Chunker;
import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
import dev.docweave.json.ElementJson;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.xmlbeans.XmlObject;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Stored DOCX structure tests, independent of desktop Word layout or rendering. */
class DocxNestedTableTest {
    private final DocWeave processor = DocWeave.create();
    private static final String CHILD =
            "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>Child</w:t></w:r></w:p></w:tc></w:tr></w:tbl>";

    @FunctionalInterface
    interface Writer {
        void write(XWPFDocument document) throws Exception;
    }

    private byte[] document(final Writer writer) throws Exception {
        try (final var document = new XWPFDocument(); final var bytes = new ByteArrayOutputStream()) {
            writer.write(document);
            document.write(bytes);
            return bytes.toByteArray();
        }
    }

    private void append(final XmlObject owner, final String xml) throws Exception {
        final var root = XmlObject.Factory.parse(
                "<root xmlns:w='" + DocxBodyView.WORD + "' xmlns:r='" + DocxParagraphText.REL + "'>" + xml + "</root>");
        try (final var source = root.newCursor(); final var target = owner.newCursor()) {
            source.toFirstChild();
            target.toEndToken();
            source.copyXmlContents(target);
        }
    }

    private PartitionResult parse(final byte[] bytes, final NestedTableOptions nested, final DocxOptions docx)
            throws Exception {
        return processor.partition(DocumentSource.bytes(bytes, "nested.docx"),
                PartitionOptions.builder().nestedTableOptions(nested).docxOptions(docx).build());
    }

    private PartitionResult parse(final byte[] bytes) throws Exception {
        return parse(bytes, NestedTableOptions.preserveMetadata(), DocxOptions.defaults());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> children(final Element table) {
        assertEquals(1, table.metadata().get("nested_tables_version"));
        return (List<Map<String, Object>>) table.metadata().get("nested_tables");
    }

    private String interval(final Element table, final Map<String, Object> child) {
        return table.text().substring(table.text().offsetByCodePoints(0, (Integer) child.get("start_index")),
                table.text().offsetByCodePoints(0, (Integer) child.get("end_index")));
    }

    private void sameDefault(final PartitionResult baseline, final PartitionResult actual) {
        assertEquals(baseline.warnings(), actual.warnings());
        assertEquals(baseline.elements(), actual.elements()
                                                  .stream()
                                                  .map(element -> {
                                                      final var metadata = new LinkedHashMap<>(element.metadata());
                                                      metadata.remove("nested_tables");
                                                      metadata.remove("nested_tables_version");
                                                      return new Element(element.elementId(), element.type(),
                                                              element.text(), metadata);
                                                  })
                                                  .toList());
    }

    @Test
    void pinnedFixturePreservesPreorderHierarchySpansAndLocalLinksWithExactRootCompatibility() throws Exception {
        final var source = DocumentSource.of(Path.of("benchmarks/review/nested-tables/nested.docx"));
        final var baseline = processor.partition(source);
        final var actual = processor.partition(
                source, PartitionOptions.builder().nestedTableOptions(NestedTableOptions.preserveMetadata()).build());
        sameDefault(baseline, actual);
        final var table = actual.elements().getFirst();
        final var children = children(table);
        assertEquals(3, children.size());
        assertEquals(List.of(0, 1, 2), children.stream().map(child -> child.get("index")).toList());
        assertEquals(0, children.get(1).get("parent_index"));
        assertFalse(children.get(2).containsKey("parent_index"));
        assertEquals(List.of(Map.of("row", 0, "column", 0, "table_index", 1)), children.get(2).get("path"));
        assertEquals("Child heading\t\nRepeated\nGrandchild\tRight", children.getFirst().get("text"));
        assertTrue(children.getFirst().get("text_as_html").toString().contains("colspan=\"2\""));
        assertEquals(List.of(Map.of("start_index", 15, "text", "Repeated", "url", "https://example.com/item")),
                children.getFirst().get("links"));
        for (final var child : children) {
            assertEquals(child.get("text"), interval(table, child));
        }
        assertEquals("Grandchild", children.get(1).get("text"));
        assertEquals(actual.elements(), ElementJson.create().read(ElementJson.create().write(actual.elements())));
    }

    @Test
    void verticalMergeMovesIntervalsButRetainsSourcePreorderAndOwnerCoordinates() throws Exception {
        final var bytes = document(doc -> {
            final var table = doc.createTable(2, 2);
            table.getRow(0).getCell(0).setText("Origin");
            table.getRow(0).getCell(0).getCTTc().addNewTcPr().addNewVMerge().setVal(STMerge.RESTART);
            append(table.getRow(0).getCell(1).getCTTc(), CHILD.replace("Child", "First"));
            final var continuation = table.getRow(1).getCell(0);
            continuation.getCTTc().addNewTcPr().addNewVMerge().setVal(STMerge.CONTINUE);
            append(continuation.getCTTc(), CHILD.replace("Child", "Second"));
        });
        final var result = parse(bytes);
        sameDefault(parse(bytes, NestedTableOptions.defaults(), DocxOptions.defaults()), result);
        final var table = result.elements().getFirst();
        final var children = children(table);
        assertEquals(List.of("First", "Second"), children.stream().map(child -> child.get("text")).toList());
        assertEquals(List.of(Map.of("row", 1, "column", 0, "table_index", 0, "owner_row", 0, "owner_column", 0)),
                children.getLast().get("path"));
        assertTrue((Integer) children.getLast().get("start_index") < (Integer) children.getFirst().get("start_index"));
        for (final var child : children) {
            assertEquals(child.get("text"), interval(table, child));
        }
    }

    @Test
    void horizontalMergeAndGridGapsRetainSourceLogicalCoordinates() throws Exception {
        final var bytes = document(doc -> {
            final var table = doc.createTable(1, 2);
            table.getRow(0).getCtRow().addNewTrPr().addNewGridBefore().setVal(BigInteger.TWO);
            final var first = table.getRow(0).getCell(0);
            first.setText("Origin");
            first.getCTTc().addNewTcPr().addNewHMerge().setVal(STMerge.RESTART);
            final var next = table.getRow(0).getCell(1);
            next.getCTTc().addNewTcPr().addNewHMerge().setVal(STMerge.CONTINUE);
            append(next.getCTTc(), CHILD);
        });
        final var result = parse(bytes);
        sameDefault(parse(bytes, NestedTableOptions.defaults(), DocxOptions.defaults()), result);
        final var table = result.elements().getFirst();
        final var child = children(table).getFirst();
        assertEquals(List.of(Map.of("row", 0, "column", 3, "table_index", 0, "owner_row", 0, "owner_column", 2)),
                child.get("path"));
        assertEquals("Child", interval(table, child));
    }

    @Test
    @SuppressWarnings("unchecked")
    void childListSnapshotsReuseCountersRatherThanConsumingThemAgain() throws Exception {
        final var bytes = document(doc -> {
            final var abstractNum = CTAbstractNum.Factory.newInstance();
            abstractNum.setAbstractNumId(BigInteger.ZERO);
            final var level = abstractNum.addNewLvl();
            level.setIlvl(BigInteger.ZERO);
            level.addNewStart().setVal(BigInteger.ONE);
            level.addNewNumFmt().setVal(STNumberFormat.DECIMAL);
            level.addNewLvlText().setVal("%1.");
            final var numbering = doc.createNumbering();
            numbering.addAbstractNum(new XWPFAbstractNum(abstractNum));
            numbering.addNum(BigInteger.ZERO, BigInteger.ONE);
            final var cell = doc.createTable(1, 1).getRow(0).getCell(0);
            final String item = "<w:p><w:pPr><w:numPr><w:ilvl w:val='0'/><w:numId w:val='1'/></w:numPr></w:pPr>"
                                + "<w:r><w:t>Item</w:t></w:r></w:p>";
            append(cell.getCTTc(), "<w:tbl><w:tr><w:tc>" + item + "</w:tc></w:tr></w:tbl>" + item);
            final var after = doc.createParagraph();
            after.setNumID(BigInteger.ONE);
            after.createRun().setText("After");
        });
        final var result = parse(bytes);
        sameDefault(parse(bytes, NestedTableOptions.defaults(), DocxOptions.defaults()), result);
        final var source =
                (Map<String, Object>) children(result.elements().getFirst()).getFirst().get("source_metadata");
        final var items = (List<Map<String, Object>>) source.get("docx_list_items");
        assertEquals("1", items.getFirst().get("docx_numbering_counter"));
        assertEquals(0, items.getFirst().get("start_index"));
        assertEquals("3", result.elements().getLast().metadata().get("docx_numbering_counter"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void captionsReferencesRevisionsFieldsAndSupplementAnchorsUseChildLocalCoordinates() throws Exception {
        final var bytes = document(doc -> {
            final var cell = doc.createTable(1, 1).getRow(0).getCell(0);
            cell.setText("😀 Root");
            append(cell.getCTTc(), "<w:tbl><w:tblPr><w:tblCaption w:val='Stored caption'/></w:tblPr><w:tr><w:tc><w:p>"
                                           + "<w:r><w:t>😀</w:t></w:r><w:r><w:footnoteReference w:id='5'/></w:r>"
                                           + "<w:ins w:author='Owner'><w:r><w:t>Added</w:t></w:r></w:ins>"
                                           + "<w:fldSimple w:instr='PAGE'><w:r><w:t>7</w:t></w:r></w:fldSimple>"
                                           + "<w:txbxContent><w:p><w:r><w:t>Box</w:t></w:r></w:p></w:txbxContent>"
                                           + "</w:p></w:tc></w:tr></w:tbl>");
        });
        final var result = parse(bytes);
        sameDefault(parse(bytes, NestedTableOptions.defaults(), DocxOptions.defaults()), result);
        final var child = children(result.elements().getFirst()).getFirst();
        assertEquals(List.of("Stored caption"), child.get("captions"));
        final var source = (Map<String, Object>) child.get("source_metadata");
        assertEquals(1, ((List<Map<String, Object>>) source.get("docx_references")).getFirst().get("start_index"));
        assertEquals(1, ((List<Map<String, Object>>) source.get("docx_revisions")).getFirst().get("start_index"));
        assertEquals(List.of("PAGE"), source.get("docx_field_instructions"));
        assertEquals(List.of(Map.of("kind", "textbox", "start_index", 7)), source.get("docx_supplement_anchors"));
        assertEquals("Box", result.elements().getLast().text());
    }

    @Test
    void emptyChildrenCountAndHaveDistinctZeroWidthIntervals() throws Exception {
        final var bytes = document(doc -> {
            final var cell = doc.createTable(1, 1).getRow(0).getCell(0);
            cell.setText("Root");
            append(cell.getCTTc(), "<w:tbl/><w:tbl/>" + CHILD);
        });
        final var result = parse(bytes);
        sameDefault(parse(bytes, NestedTableOptions.defaults(), DocxOptions.defaults()), result);
        final var table = result.elements().getFirst();
        final var children = children(table);
        assertEquals(3, children.size());
        assertEquals("", interval(table, children.getFirst()));
        assertEquals(children.getFirst().get("start_index"), children.get(1).get("start_index"));
        assertNotEquals(children.getFirst().get("path"), children.get(1).get("path"));
        assertLimit(bytes, new NestedTableOptions(NestedTableOptions.Mode.PRESERVE_METADATA, 2, 4, 10000),
                DocxOptions.defaults());
    }

    @Test
    void budgetsSpanRootTablesAndEnabledHeaderParts() throws Exception {
        final var bytes = document(doc -> {
            append(doc.createTable(1, 1).getRow(0).getCell(0).getCTTc(), CHILD);
            append(doc.createHeader(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT)
                            .createTable(1, 1)
                            .getRow(0)
                            .getCell(0)
                            .getCTTc(),
                    CHILD);
        });
        final var single = new NestedTableOptions(NestedTableOptions.Mode.PRESERVE_METADATA, 1, 4, 10000);
        assertEquals(1, parse(bytes, single, DocxOptions.defaults()).elements().size());
        final var defaults = DocxOptions.defaults();
        final var headers = new DocxOptions(defaults.maxArchiveEntries(), defaults.maxExpandedBytes(),
                defaults.maxEntryBytes(), defaults.maxXmlDepth(), defaults.maxXmlNodes(), true, defaults.tableLimits(),
                defaults.contentOptions());
        assertLimit(bytes, single, headers);
        assertEquals(2, parse(bytes, NestedTableOptions.preserveMetadata(), headers).elements().size());
        final var two = document(doc -> {
            for (int i = 0; i < 2; i++) {
                append(doc.createTable(1, 1).getRow(0).getCell(0).getCTTc(), CHILD);
            }
        });
        assertLimit(two, single, defaults);
    }

    @Test
    void depthPayloadAndNativeTableLimitsFailExplicitlyAndDoNotPoisonReuse() throws Exception {
        final var bytes = document(doc
                -> append(doc.createTable(1, 1).getRow(0).getCell(0).getCTTc(),
                        CHILD.replace("</w:tc>", CHILD + "</w:tc>")));
        assertLimit(bytes, new NestedTableOptions(NestedTableOptions.Mode.PRESERVE_METADATA, 10, 1, 10000),
                DocxOptions.defaults());
        assertLimit(bytes, new NestedTableOptions(NestedTableOptions.Mode.PRESERVE_METADATA, 10, 4, 100),
                DocxOptions.defaults());
        assertEquals(2, children(parse(bytes).elements().getFirst()).size());
        final var defaults = DocxOptions.defaults();
        final var tiny =
                new DocxOptions(defaults.maxArchiveEntries(), defaults.maxExpandedBytes(), defaults.maxEntryBytes(),
                        defaults.maxXmlDepth(), defaults.maxXmlNodes(), false, new TableLimits(10, 10, 100, 2));
        assertLimit(bytes, NestedTableOptions.preserveMetadata(), tiny);
    }

    @Test
    void chunkRewritesRetainDescriptorsOnlyInsideOriginalProvenance() throws Exception {
        final var bytes = document(doc -> append(doc.createTable(1, 1).getRow(0).getCell(0).getCTTc(), CHILD));
        final var source = parse(bytes).elements().getFirst();
        for (final int max : new int[] {1000, 12}) {
            for (final var chunk :
                    Chunker.create().basic(List.of(source), ChunkOptions.builder().maxCharacters(max).build())) {
                assertFalse(chunk.metadata().containsKey("nested_tables"));
                assertEquals(List.of(source),
                        ElementJson.create().decodeOriginals((String) chunk.metadata().get("orig_elements")));
            }
        }
        assertSame(source,
                Chunker.create()
                        .basic(List.of(source), ChunkOptions.builder().maxCharacters(1).skipTableChunking(true).build())
                        .getFirst());
    }

    @Test
    void exactPayloadAccountingIncludesSemanticSnapshotsAndRepeatedRoots() throws Exception {
        final var one = document(doc -> append(doc.createTable(1, 1).getRow(0).getCell(0).getCTTc(), CHILD));
        final var child = children(parse(one).elements().getFirst()).getFirst();
        final long amount = payload(child);
        final var exact = new NestedTableOptions(NestedTableOptions.Mode.PRESERVE_METADATA, 10, 4, amount);
        assertEquals(child, children(parse(one, exact, DocxOptions.defaults()).elements().getFirst()).getFirst());
        assertLimit(one, new NestedTableOptions(NestedTableOptions.Mode.PRESERVE_METADATA, 10, 4, amount - 1),
                DocxOptions.defaults());
        final var two = document(doc -> {
            for (int i = 0; i < 2; i++) {
                append(doc.createTable(1, 1).getRow(0).getCell(0).getCTTc(), CHILD);
            }
        });
        assertLimit(two, exact, DocxOptions.defaults());
        assertEquals(2, parse(two, new NestedTableOptions(NestedTableOptions.Mode.PRESERVE_METADATA, 10, 4, amount * 2),
                                DocxOptions.defaults())
                                .elements()
                                .size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void childStyleAndRevisionSelectionRemainIndependentOfTheOuterTable() throws Exception {
        final var bytes = document(doc -> {
            final var style = CTStyle.Factory.newInstance();
            style.setStyleId("Inner");
            style.setType(STStyleType.TABLE);
            append(style, "<w:pPr><w:outlineLvl w:val='2'/></w:pPr>");
            doc.createStyles().addStyle(new XWPFStyle(style));
            append(doc.createTable(1, 1).getRow(0).getCell(0).getCTTc(),
                    "<w:tbl><w:tblPr><w:tblStyle w:val='Inner'/></w:tblPr><w:tr><w:tc><w:p>"
                            + "<w:ins><w:r><w:t>New</w:t></w:r></w:ins><w:del><w:r><w:delText>Old</w:delText></w:r></w:del>"
                            + "</w:p></w:tc></w:tr></w:tbl>");
        });
        for (final var revision : DocxContentOptions.Revisions.values()) {
            final var policy = DocxOptions.defaults().withContentOptions(
                    new DocxContentOptions(true, true, true, DocxContentOptions.Images.REFERENCE, 10000, revision));
            final var result = parse(bytes, NestedTableOptions.preserveMetadata(), policy);
            sameDefault(parse(bytes, NestedTableOptions.defaults(), policy), result);
            final var child = children(result.elements().getFirst()).getFirst();
            assertEquals(switch (revision) {
                case ACCEPTED -> "New";
                case ORIGINAL -> "Old";
                case ALL -> "NewOld";
            }, child.get("text"));
            final var metadata = (Map<String, Object>) child.get("source_metadata");
            assertEquals("Inner", metadata.get("docx_table_style"));
            final var paragraphs = (List<Map<String, Object>>) metadata.get("docx_table_style_paragraphs");
            assertEquals(0, paragraphs.getFirst().get("start_index"));
            assertEquals(2, paragraphs.getFirst().get("outline_level"));
        }
    }

    @Test
    void noteAndTextBoxTablesShareTheDocumentBudgetAndDisabledPartsDoNotCount() throws Exception {
        final var bytes = document(doc -> {
            append(doc.createTable(1, 1).getRow(0).getCell(0).getCTTc(), CHILD);
            append(doc.createFootnote().getCTFtnEdn(), CHILD.replace("</w:tc>", CHILD + "</w:tc>"));
            append(doc.createParagraph().getCTP(),
                    "<w:txbxContent>" + CHILD.replace("</w:tc>", CHILD + "</w:tc>") + "</w:txbxContent>");
        });
        final var single = new NestedTableOptions(NestedTableOptions.Mode.PRESERVE_METADATA, 1, 4, 10000);
        assertLimit(bytes, single, DocxOptions.defaults());
        final var disabled = DocxOptions.defaults().withContentOptions(new DocxContentOptions(
                false, false, false, DocxContentOptions.Images.SKIP, 10000, DocxContentOptions.Revisions.ACCEPTED));
        assertEquals(1, parse(bytes, single, disabled).elements().size());
        assertEquals(
                3, parse(bytes).elements().stream().filter(element -> element.type() == ElementType.Table).count());
    }

    @Test
    void cancellationAndConcurrentReuseKeepBudgetsDocumentLocal() throws Exception {
        final var bytes = document(doc -> append(doc.createTable(1, 1).getRow(0).getCell(0).getCTTc(), CHILD));
        final var policy = new NestedTableOptions(NestedTableOptions.Mode.PRESERVE_METADATA, 1, 1, 10000);
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.io.InterruptedIOException.class, () -> parse(bytes, policy, DocxOptions.defaults()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        final var expected = parse(bytes, policy, DocxOptions.defaults()).elements();
        try (final var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            final var tasks =
                    java.util.stream.IntStream.range(0, 20)
                            .mapToObj(index
                                    -> executor.submit(() -> parse(bytes, policy, DocxOptions.defaults()).elements()))
                            .toList();
            for (final var task : tasks) {
                assertEquals(expected, task.get());
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void combinedHorizontalVerticalMergesKeepGrandchildPathsAndSupplementAnchorsAligned() throws Exception {
        final var bytes = document(doc -> {
            final var table = doc.createTable(2, 2);
            for (int r = 0; r < 2; r++) {
                for (int c = 0; c < 2; c++) {
                    final var properties = table.getRow(r).getCell(c).getCTTc().addNewTcPr();
                    properties.addNewHMerge().setVal(c == 0 ? STMerge.RESTART : STMerge.CONTINUE);
                    properties.addNewVMerge().setVal(r == 0 ? STMerge.RESTART : STMerge.CONTINUE);
                }
            }
            table.getRow(0).getCell(0).setText("😀 Origin");
            final String grandchild =
                    CHILD.replace("Child", "😀 Grand")
                            .replace("</w:p>",
                                    "<w:txbxContent><w:p><w:r><w:t>Box</w:t></w:r></w:p></w:txbxContent></w:p>");
            append(table.getRow(0).getCell(1).getCTTc(),
                    CHILD.replace("Child", "Repeat").replace("</w:tc>", grandchild + "</w:tc>"));
            append(table.getRow(1).getCell(1).getCTTc(), CHILD.replace("Child", "Repeat"));
        });
        final var result = parse(bytes);
        sameDefault(parse(bytes, NestedTableOptions.defaults(), DocxOptions.defaults()), result);
        final var table = result.elements().getFirst();
        final var children = children(table);
        assertEquals(3, children.size());
        final Map<String, Object> firstStep =
                Map.of("row", 0, "column", 1, "table_index", 0, "owner_row", 0, "owner_column", 0);
        assertEquals(List.of(firstStep), children.getFirst().get("path"));
        assertEquals(List.of(firstStep, Map.of("row", 0, "column", 0, "table_index", 0)), children.get(1).get("path"));
        assertEquals(0, children.get(1).get("parent_index"));
        assertEquals(List.of(Map.of("row", 1, "column", 1, "table_index", 0, "owner_row", 0, "owner_column", 0)),
                children.getLast().get("path"));
        for (final var child : children) {
            assertEquals(child.get("text"), interval(table, child));
        }
        final var grandchild = children.get(1);
        final var source = (Map<String, Object>) grandchild.get("source_metadata");
        final var anchor = ((List<Map<String, Object>>) source.get("docx_supplement_anchors")).getFirst();
        assertEquals(7, anchor.get("start_index"));
        assertEquals("Box", result.elements().getLast().text());
        assertEquals((Integer) grandchild.get("start_index") + (Integer) anchor.get("start_index"),
                result.elements().getLast().metadata().get("docx_anchor_text_offset"));
        assertEquals(table.elementId(), result.elements().getLast().metadata().get("parent_id"));
    }

    private long payload(final Object value) {
        if (value instanceof final String text) {
            return text.codePointCount(0, text.length());
        }
        if (value instanceof final Map<?, ?> map) {
            return 1
                    + map.entrySet()
                              .stream()
                              .mapToLong(entry -> payload(entry.getKey()) + payload(entry.getValue()))
                              .sum();
        }
        if (value instanceof final List<?> list) {
            return 1 + list.stream().mapToLong(this::payload).sum();
        }
        return 1;
    }

    private void assertLimit(final byte[] bytes, final NestedTableOptions options, final DocxOptions docx) {
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class, () -> parse(bytes, options, docx)).code());
    }
}
