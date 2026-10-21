package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.batch.BatchProcessor;
import dev.docweave.chunking.ChunkOptions;
import dev.docweave.chunking.Chunker;
import dev.docweave.core.ElementType;
import dev.docweave.json.ElementJson;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DocxDefaultStyleTest {
    private BigInteger id(final int value) {
        return BigInteger.valueOf(value);
    }

    private XWPFDocument fixture() {
        final var doc = new XWPFDocument();
        doc.createStyles();
        final var definition = CTAbstractNum.Factory.newInstance();
        definition.setAbstractNumId(id(0));
        for (int depth = 0; depth < 2; depth++) {
            final var level = definition.addNewLvl();
            level.setIlvl(id(depth));
            level.addNewStart().setVal(id(1));
            level.addNewNumFmt().setVal(STNumberFormat.DECIMAL);
            level.addNewLvlText().setVal(depth == 0 ? "%1." : "%1.%2");
        }
        final var numbering = doc.createNumbering();
        numbering.addAbstractNum(new XWPFAbstractNum(definition));
        numbering.addNum(id(0), id(1));
        return doc;
    }

    private CTStyle style(final XWPFDocument doc, final String name, final String flag, final STStyleType.Enum type,
            final Integer num, final String base) {
        final var style = CTStyle.Factory.newInstance();
        style.setStyleId(name);
        style.setType(type);
        if (flag != null) {
            style.setDefault(flag);
        }
        if (base != null) {
            style.addNewBasedOn().setVal(base);
        }
        if (num != null) {
            style.addNewPPr().addNewNumPr().addNewNumId().setVal(id(num));
        }
        doc.getStyles().addStyle(new XWPFStyle(style));
        // POI copies styles into the stored part; subsequent fixture edits must target that copy.
        final var stored = doc.getStyles().getCtStyles();
        return stored.getStyleArray(stored.sizeOfStyleArray() - 1);
    }

    private XWPFParagraph paragraph(final XWPFDocument doc, final String text, final String style) {
        final var paragraph = doc.createParagraph();
        paragraph.createRun().setText(text);
        if (style != null) {
            paragraph.setStyle(style);
        }
        return paragraph;
    }

    private byte[] bytes(final XWPFDocument doc) throws Exception {
        final var output = new ByteArrayOutputStream();
        doc.write(output);
        return output.toByteArray();
    }

    private PartitionResult parse(final XWPFDocument doc) throws Exception {
        return DocWeave.create().partition(DocumentSource.bytes(bytes(doc), "defaults.docx"));
    }

    @Test
    void defaultStyleInheritsNumberingAndAssociatedLevel() throws Exception {
        try (final var doc = fixture()) {
            style(doc, "Base", null, STStyleType.PARAGRAPH, 1, null);
            style(doc, "Implicit", "true", STStyleType.PARAGRAPH, null, "Base");
            doc.getNumbering().getAbstractNum(id(0)).getCTAbstractNum().getLvlArray(1).addNewPStyle().setVal(
                    "Implicit");
            paragraph(doc, "First child", null);
            paragraph(doc, "Next child", null);
            final var result = parse(doc);
            assertEquals(List.of("1.1", "1.2"),
                    result.elements().stream().map(e -> e.metadata().get("docx_list_label")).toList());
            for (final var element : result.elements()) {
                assertEquals(ElementType.ListItem, element.type());
                assertEquals(1, element.metadata().get("category_depth"));
                assertEquals("Implicit", element.metadata().get("docx_style"));
                assertEquals("default", element.metadata().get("docx_style_source"));
            }
        }
    }

    @Test
    void explicitStylesDoNotFallBackToDefaultNumbering() throws Exception {
        try (final var doc = fixture()) {
            style(doc, "Implicit", "1", STStyleType.PARAGRAPH, 1, null);
            style(doc, "Explicit", null, STStyleType.PARAGRAPH, null, null);
            paragraph(doc, "Explicit body", "Explicit");
            paragraph(doc, "Missing style", "Absent");
            paragraph(doc, "Implicit item", null);
            final var result = parse(doc);
            assertEquals(List.of(ElementType.NarrativeText, ElementType.NarrativeText, ElementType.ListItem),
                    result.elements().stream().map(e -> e.type()).toList());
            assertFalse(result.elements().getFirst().metadata().containsKey("docx_style_source"));
            assertEquals("Absent", result.elements().get(1).metadata().get("docx_style"));
        }
    }

    @Test
    void directNumberingSuppressionAndLevelWin() throws Exception {
        try (final var doc = fixture()) {
            style(doc, "Implicit", "on", STStyleType.PARAGRAPH, 1, null);
            doc.getNumbering().getAbstractNum(id(0)).getCTAbstractNum().getLvlArray(1).addNewPStyle().setVal(
                    "Implicit");
            paragraph(doc, "No numbering", null).setNumID(id(0));
            final var explicitLevel = paragraph(doc, "Parent level", null);
            explicitLevel.getCTP().addNewPPr().addNewNumPr().addNewIlvl().setVal(id(0));
            final var result = parse(doc);
            assertEquals(ElementType.NarrativeText, result.elements().getFirst().type());
            assertEquals("1.", result.elements().getLast().metadata().get("docx_list_label"));
            assertEquals(0, result.elements().getLast().metadata().get("category_depth"));
        }
    }

    @Test
    void defaultOutlineAndDirectBodyOutlinePreservePrecedence() throws Exception {
        try (final var doc = fixture()) {
            final var base = style(doc, "BaseOutline", null, STStyleType.PARAGRAPH, null, null);
            base.addNewPPr().addNewOutlineLvl().setVal(id(1));
            style(doc, "Implicit", "1", STStyleType.PARAGRAPH, null, "BaseOutline");
            paragraph(doc, "Default heading", null);
            paragraph(doc, "Direct body", null).getCTP().addNewPPr().addNewOutlineLvl().setVal(id(9));
            final var result = parse(doc);
            assertEquals(ElementType.Title, result.elements().getFirst().type());
            assertEquals(1, result.elements().getFirst().metadata().get("category_depth"));
            assertEquals(ElementType.NarrativeText, result.elements().getLast().type());
        }
    }

    @Test
    void falseAndOtherStyleTypesDoNotBecomeParagraphDefaults() throws Exception {
        for (final String flag : List.of("false", "0", "off")) {
            try (final var doc = fixture()) {
                style(doc, "Normal", flag, STStyleType.PARAGRAPH, 1, null);
                style(doc, "CharacterDefault", "1", STStyleType.CHARACTER, 1, null);
                style(doc, "TableDefault", "1", STStyleType.TABLE, 1, null);
                paragraph(doc, "Body", null);
                final var element = parse(doc).elements().getFirst();
                assertEquals(ElementType.NarrativeText, element.type());
                assertFalse(element.metadata().containsKey("docx_style"));
            }
        }
    }

    @Test
    void multipleDefaultsUseLastDeclaredParagraphStyle() throws Exception {
        try (final var doc = fixture()) {
            style(doc, "First", "1", STStyleType.PARAGRAPH, 1, null);
            style(doc, "Last", "true", STStyleType.PARAGRAPH, 0, null);
            paragraph(doc, "Body", null);
            final var element = parse(doc).elements().getFirst();
            assertEquals("Last", element.metadata().get("docx_style"));
            assertEquals(ElementType.NarrativeText, element.type());
        }
    }

    @Test
    void implicitTableListsShareCountersAndProvenanceSurvives() throws Exception {
        try (final var doc = fixture()) {
            style(doc, "Implicit", "1", STStyleType.PARAGRAPH, 1, null);
            paragraph(doc, "Before", null);
            doc.createTable(1, 1).getRow(0).getCell(0).getParagraphs().getFirst().createRun().setText("Inside");
            paragraph(doc, "After", null);
            final var result = parse(doc);
            assertEquals("1.", result.elements().getFirst().metadata().get("docx_list_label"));
            assertEquals("3.", result.elements().getLast().metadata().get("docx_list_label"));
            final var items = (List<?>) result.elements().get(1).metadata().get("docx_list_items");
            final var item = (java.util.Map<?, ?>) items.getFirst();
            assertEquals("2.", item.get("docx_list_label"));
            assertEquals("default", item.get("docx_style_source"));
            final var codec = ElementJson.create();
            assertEquals(result.elements(), codec.read(codec.write(result.elements())));
            final var chunks = Chunker.create().basic(List.of(result.elements().getFirst()), ChunkOptions.defaults());
            final var originals = codec.decodeOriginals((String) chunks.getFirst().metadata().get("orig_elements"));
            assertEquals("default", originals.getFirst().metadata().get("docx_style_source"));
        }
    }

    @Test
    void defaultStyleCyclesTerminateAndConcurrentDocumentsStayIndependent() throws Exception {
        try (final var doc = fixture()) {
            style(doc, "A", "true", STStyleType.PARAGRAPH, 1, "B");
            style(doc, "B", null, STStyleType.PARAGRAPH, null, "A");
            paragraph(doc, "Item", null);
            final var source = bytes(doc);
            final var results =
                    BatchProcessor.create().mapOrdered(java.util.stream.IntStream.range(0, 12).boxed().toList(), 3,
                            ignored -> DocWeave.create().partition(DocumentSource.bytes(source, "defaults.docx")));
            for (final var result : results) {
                assertEquals("1.", result.elements().getFirst().metadata().get("docx_list_label"));
            }
        }
    }
}
