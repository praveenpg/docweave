package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
import dev.docweave.json.ElementJson;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DocxNumberingStyleTest {
    private BigInteger id(final int value) {
        return BigInteger.valueOf(value);
    }

    private XWPFDocument fixture() {
        final var doc = new XWPFDocument();
        doc.createStyles();
        final var abstractNum = CTAbstractNum.Factory.newInstance();
        abstractNum.setAbstractNumId(id(0));
        for (int depth = 0; depth < 2; depth++) {
            final var level = abstractNum.addNewLvl();
            level.setIlvl(id(depth));
            level.addNewStart().setVal(id(1));
            level.addNewNumFmt().setVal(STNumberFormat.DECIMAL);
            level.addNewLvlText().setVal(depth == 0 ? "%1." : "%1.%2");
        }
        final var numbering = doc.createNumbering();
        numbering.addAbstractNum(new XWPFAbstractNum(abstractNum));
        numbering.addNum(id(0), id(1));
        style(doc, "NumberStyle", STStyleType.NUMBERING, 1, null);
        alias(doc, 1, 2, "NumberStyle");
        return doc;
    }

    private void alias(final XWPFDocument doc, final int abstractId, final int numId, final String style) {
        final var definition = CTAbstractNum.Factory.newInstance();
        definition.setAbstractNumId(id(abstractId));
        definition.addNewNumStyleLink().setVal(style);
        doc.getNumbering().addAbstractNum(new XWPFAbstractNum(definition));
        doc.getNumbering().addNum(id(abstractId), id(numId));
    }

    private CTStyle style(final XWPFDocument doc, final String name, final STStyleType.Enum type, final Integer num,
            final String base) {
        final var style = CTStyle.Factory.newInstance();
        style.setStyleId(name);
        style.setType(type);
        if (base != null) {
            style.addNewBasedOn().setVal(base);
        }
        if (num != null) {
            style.addNewPPr().addNewNumPr().addNewNumId().setVal(id(num));
        }
        doc.getStyles().addStyle(new XWPFStyle(style));
        return style;
    }

    private XWPFParagraph paragraph(
            final XWPFDocument doc, final String text, final String style, final Integer num, final Integer level) {
        final var paragraph = doc.createParagraph();
        paragraph.createRun().setText(text);
        if (style != null) {
            paragraph.setStyle(style);
        }
        if (num != null) {
            paragraph.setNumID(id(num));
        }
        if (level != null) {
            final var properties =
                    paragraph.getCTP().isSetPPr() ? paragraph.getCTP().getPPr() : paragraph.getCTP().addNewPPr();
            final var number = properties.isSetNumPr() ? properties.getNumPr() : properties.addNewNumPr();
            number.addNewIlvl().setVal(id(level));
        }
        return paragraph;
    }

    private PartitionResult parse(final XWPFDocument doc) throws Exception {
        final var output = new ByteArrayOutputStream();
        doc.write(output);
        return DocWeave.create().partition(DocumentSource.bytes(output.toByteArray(), "numbering-style.docx"));
    }

    private List<Object> labels(final PartitionResult result) {
        return result.elements().stream().map(e -> e.metadata().get("docx_list_label")).toList();
    }

    @Test
    void linkedDefinitionsRenderAndKeepInstanceCountersIndependent() throws Exception {
        try (final var doc = fixture()) {
            paragraph(doc, "Alias one", null, 2, 0);
            paragraph(doc, "Alias two", null, 2, 0);
            paragraph(doc, "Underlying one", null, 1, 0);
            final var result = parse(doc);
            assertEquals(List.of("1.", "2.", "1."), labels(result));
            assertEquals(List.of("2", "2", "1"),
                    result.elements().stream().map(e -> e.metadata().get("docx_num_id")).toList());
            assertTrue(result.warnings().isEmpty());
        }
    }

    @Test
    void simpleDecimalInstancesShareButLinkedAliasesRemainIndependent() throws Exception {
        try (final var doc = fixture()) {
            doc.getNumbering().getAbstractNum(id(0)).getCTAbstractNum().removeLvl(1);
            doc.getNumbering().addNum(id(0), id(3));
            paragraph(doc, "First instance", null, 1, 0);
            paragraph(doc, "Second instance", null, 3, 0);
            paragraph(doc, "Linked alias", null, 2, 0);
            paragraph(doc, "First continued", null, 1, 0);
            assertEquals(List.of("1.", "2.", "1.", "3."), labels(parse(doc)));
        }
    }

    @Test
    void explicitRestartSeparatesInstanceWithoutResettingSharedSequence() throws Exception {
        try (final var doc = fixture()) {
            doc.getNumbering().getAbstractNum(id(0)).getCTAbstractNum().removeLvl(1);
            doc.getNumbering().addNum(id(0), id(3));
            final var override = doc.getNumbering().getNum(id(3)).getCTNum().addNewLvlOverride();
            override.setIlvl(id(0));
            override.addNewStartOverride().setVal(id(7));
            for (final int number : List.of(1, 3, 1, 3)) {
                paragraph(doc, "Item " + number, null, number, 0);
            }
            assertEquals(List.of("1.", "7.", "2.", "8."), labels(parse(doc)));
        }
    }

    @Test
    void multilevelAndOtherFormatsRetainExistingInstancePolicy() throws Exception {
        for (final boolean multilevel : List.of(true, false)) {
            try (final var doc = fixture()) {
                if (!multilevel) {
                    final var definition = doc.getNumbering().getAbstractNum(id(0)).getCTAbstractNum();
                    definition.removeLvl(1);
                    definition.getLvlArray(0).getNumFmt().setVal(STNumberFormat.UPPER_LETTER);
                }
                doc.getNumbering().addNum(id(0), id(3));
                paragraph(doc, "First", null, 1, 0);
                paragraph(doc, "Other", null, 3, 0);
                assertEquals(multilevel ? List.of("1.", "1.") : List.of("A.", "A."), labels(parse(doc)));
            }
        }
    }

    @Test
    void multihopAndBasedOnStylesHonorOuterStartOverride() throws Exception {
        try (final var doc = fixture()) {
            style(doc, "InheritedNumber", STStyleType.NUMBERING, null, "NumberStyle");
            doc.getNumbering().getAbstractNum(id(1)).getCTAbstractNum().getNumStyleLink().setVal("InheritedNumber");
            style(doc, "AliasStyle", STStyleType.NUMBERING, 2, null);
            alias(doc, 2, 3, "AliasStyle");
            final var inner = doc.getNumbering().getNum(id(1)).getCTNum().addNewLvlOverride();
            inner.setIlvl(id(0));
            inner.addNewStartOverride().setVal(id(3));
            final var outer = doc.getNumbering().getNum(id(3)).getCTNum().addNewLvlOverride();
            outer.setIlvl(id(0));
            outer.addNewStartOverride().setVal(id(5));
            paragraph(doc, "Five", null, 3, 0);
            paragraph(doc, "Six", null, 3, 0);
            assertEquals(List.of("5.", "6."), labels(parse(doc)));
        }
    }

    @Test
    void outerLevelDefinitionOverridesLinkedDefinition() throws Exception {
        try (final var doc = fixture()) {
            final var override = doc.getNumbering().getNum(id(2)).getCTNum().addNewLvlOverride();
            override.setIlvl(id(0));
            final var level = override.addNewLvl();
            level.setIlvl(id(0));
            level.addNewStart().setVal(id(4));
            level.addNewNumFmt().setVal(STNumberFormat.UPPER_LETTER);
            level.addNewLvlText().setVal("%1)");
            paragraph(doc, "Fourth", null, 2, 0);
            assertEquals(List.of("D)"), labels(parse(doc)));
        }
    }

    @Test
    void associatedParagraphStyleSelectsLevelAndDirectLevelWins() throws Exception {
        try (final var doc = fixture()) {
            final var style = style(doc, "ItemStyle", STStyleType.PARAGRAPH, 2, null);
            style.getPPr().getNumPr().addNewIlvl().setVal(id(0));
            doc.getNumbering().getAbstractNum(id(0)).getCTAbstractNum().getLvlArray(1).addNewPStyle().setVal(
                    "ItemStyle");
            paragraph(doc, "Style-selected child", "ItemStyle", null, null);
            paragraph(doc, "Direct parent", "ItemStyle", null, 0);
            final var result = parse(doc);
            assertEquals(List.of("1.1", "1."), labels(result));
            assertEquals(
                    List.of(1, 0), result.elements().stream().map(e -> e.metadata().get("category_depth")).toList());
        }
    }

    @Test
    void inheritedParagraphStyleAssociationAndZeroSuppression() throws Exception {
        try (final var doc = fixture()) {
            style(doc, "BaseItem", STStyleType.PARAGRAPH, 2, null);
            style(doc, "DerivedItem", STStyleType.PARAGRAPH, null, "BaseItem");
            doc.getNumbering().getAbstractNum(id(0)).getCTAbstractNum().getLvlArray(1).addNewPStyle().setVal(
                    "BaseItem");
            paragraph(doc, "Inherited", "DerivedItem", null, null);
            paragraph(doc, "Suppressed", "DerivedItem", 0, null);
            final var result = parse(doc);
            assertEquals("1.1", result.elements().getFirst().metadata().get("docx_list_label"));
            assertEquals(ElementType.NarrativeText, result.elements().getLast().type());
            assertFalse(result.elements().getLast().metadata().containsKey("docx_list_label"));
        }
    }

    @Test
    void numberedHeadingsRetainTitleTypeAndLabels() throws Exception {
        try (final var doc = fixture()) {
            style(doc, "Heading1", STStyleType.PARAGRAPH, 2, null);
            paragraph(doc, "First heading", "Heading1", null, null);
            paragraph(doc, "Second heading", "Heading1", null, null);
            final var result = parse(doc);
            assertEquals(List.of("1.", "2."), labels(result));
            assertTrue(result.elements().stream().allMatch(e -> e.type() == ElementType.Title));
            assertEquals(result.elements(), ElementJson.create().read(ElementJson.create().write(result.elements())));
        }
    }

    @Test
    void linkedListsContinueAcrossTableCells() throws Exception {
        try (final var doc = fixture()) {
            paragraph(doc, "Before", null, 2, 0);
            final var inCell = doc.createTable(1, 1).getRow(0).getCell(0).getParagraphs().getFirst();
            inCell.createRun().setText("Inside");
            inCell.setNumID(id(2));
            paragraph(doc, "After", null, 2, 0);
            final var result = parse(doc);
            assertEquals("1.", result.elements().getFirst().metadata().get("docx_list_label"));
            assertEquals("3.", result.elements().getLast().metadata().get("docx_list_label"));
            final var items = (List<?>) result.elements().get(1).metadata().get("docx_list_items");
            assertEquals("2.", ((java.util.Map<?, ?>) items.getFirst()).get("docx_list_label"));
        }
    }

    @Test
    void missingWrongTypeAndCyclicLinksRetainTextWithDiagnostics() throws Exception {
        for (final String target : List.of("Absent", "WrongType", "Loop")) {
            try (final var doc = fixture()) {
                style(doc, "WrongType", STStyleType.PARAGRAPH, 1, null);
                style(doc, "Loop", STStyleType.NUMBERING, 2, null);
                doc.getNumbering().getAbstractNum(id(1)).getCTAbstractNum().getNumStyleLink().setVal(target);
                paragraph(doc, "Kept text", null, 2, 0);
                final var result = parse(doc);
                final var element = result.elements().getFirst();
                assertEquals("Kept text", element.text());
                assertEquals(ElementType.ListItem, element.type());
                assertEquals(true, element.metadata().get("docx_numbering_unresolved"));
                assertFalse(element.metadata().containsKey("docx_list_label"));
                assertEquals(1, result.warnings().size());
            }
        }
    }

    @Test
    void numberingStyleBasedOnCycleTerminates() throws Exception {
        try (final var doc = fixture()) {
            style(doc, "A", STStyleType.NUMBERING, null, "B");
            style(doc, "B", STStyleType.NUMBERING, null, "A");
            doc.getNumbering().getAbstractNum(id(1)).getCTAbstractNum().getNumStyleLink().setVal("A");
            paragraph(doc, "Cycle", null, 2, 0);
            assertEquals(true, parse(doc).elements().getFirst().metadata().get("docx_numbering_unresolved"));
        }
    }

    @Test
    void descriptiveStyleLinkDoesNotRedirectDefinitions() throws Exception {
        try (final var doc = fixture()) {
            doc.getNumbering().getAbstractNum(id(0)).getCTAbstractNum().addNewStyleLink().setVal("AbsentDescription");
            paragraph(doc, "Still numbered", null, 2, 0);
            assertEquals(List.of("1."), labels(parse(doc)));
        }
    }

    @Test
    void excessiveAcyclicLinkChainsAreBounded() throws Exception {
        try (final var doc = fixture()) {
            doc.getNumbering().getAbstractNum(id(1)).getCTAbstractNum().getNumStyleLink().setVal("Hop0");
            for (int hop = 0; hop < 129; hop++) {
                style(doc, "Hop" + hop, STStyleType.NUMBERING, hop + 3, null);
                alias(doc, hop + 2, hop + 3, hop == 128 ? "NumberStyle" : "Hop" + (hop + 1));
            }
            assertNull(new DocxNumberingDefinitions(doc).level(id(2), 0));
        }
    }

    @Test
    void definitionLookupHonorsInterruption() throws Exception {
        try (final var doc = fixture()) {
            final var resolver = new DocxNumberingDefinitions(doc);
            Thread.currentThread().interrupt();
            try {
                assertThrows(java.io.InterruptedIOException.class, () -> resolver.level(id(2), 0));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
            assertNotNull(resolver.level(id(2), 0));
        }
    }
}
