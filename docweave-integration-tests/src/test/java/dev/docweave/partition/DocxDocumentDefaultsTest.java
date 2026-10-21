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

class DocxDocumentDefaultsTest {
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
        numbering.addNum(id(0), id(2));
        return doc;
    }

    private CTPPrGeneral defaults(final XWPFDocument doc, final Integer num, Integer depth, final Integer outline) {
        final var root = doc.getStyles().getCtStyles();
        final var defaults = root.isSetDocDefaults() ? root.getDocDefaults() : root.addNewDocDefaults();
        final var wrapper = defaults.isSetPPrDefault() ? defaults.getPPrDefault() : defaults.addNewPPrDefault();
        final var properties = wrapper.isSetPPr() ? wrapper.getPPr() : wrapper.addNewPPr();
        if (num != null || depth != null) {
            final var number = properties.addNewNumPr();
            if (num != null) {
                number.addNewNumId().setVal(id(num));
            }
            if (depth != null) {
                number.addNewIlvl().setVal(id(depth));
            }
        }
        if (outline != null) {
            properties.addNewOutlineLvl().setVal(id(outline));
        }
        return properties;
    }

    private void style(final XWPFDocument doc, final String name, final Integer num, Integer depth,
            final Integer outline, final boolean implicit) {
        final var style = CTStyle.Factory.newInstance();
        style.setStyleId(name);
        style.setType(STStyleType.PARAGRAPH);
        if (implicit) {
            style.setDefault(true);
        }
        final var properties = style.addNewPPr();
        if (num != null || depth != null) {
            final var number = properties.addNewNumPr();
            if (num != null) {
                number.addNewNumId().setVal(id(num));
            }
            if (depth != null) {
                number.addNewIlvl().setVal(id(depth));
            }
        }
        if (outline != null) {
            properties.addNewOutlineLvl().setVal(id(outline));
        }
        doc.getStyles().addStyle(new XWPFStyle(style));
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
        return DocWeave.create().partition(DocumentSource.bytes(bytes(doc), "document-defaults.docx"));
    }

    @Test
    void documentNumberingAndLevelApplyWithoutNamedStyle() throws Exception {
        try (final var doc = fixture()) {
            defaults(doc, 1, 1, null);
            paragraph(doc, "First", null);
            paragraph(doc, "Second", null);
            final var result = parse(doc);
            assertEquals(List.of("1.1", "1.2"),
                    result.elements().stream().map(e -> e.metadata().get("docx_list_label")).toList());
            final var element = result.elements().getFirst();
            assertEquals(ElementType.ListItem, element.type());
            assertEquals(List.of("num_id", "list_level"), element.metadata().get("docx_document_defaults"));
            assertFalse(element.metadata().containsKey("docx_style"));
        }
    }

    @Test
    void documentOutlineIsLowestPrecedence() throws Exception {
        try (final var doc = fixture()) {
            defaults(doc, null, null, 1);
            style(doc, "Styled", null, null, 2, false);
            paragraph(doc, "Default heading", null);
            paragraph(doc, "Styled heading", "Styled");
            paragraph(doc, "Direct body", null).getCTP().addNewPPr().addNewOutlineLvl().setVal(id(9));
            final var result = parse(doc);
            assertEquals(ElementType.Title, result.elements().getFirst().type());
            assertEquals(1, result.elements().getFirst().metadata().get("category_depth"));
            assertEquals(
                    List.of("outline_level"), result.elements().getFirst().metadata().get("docx_document_defaults"));
            assertEquals(2, result.elements().get(1).metadata().get("category_depth"));
            assertFalse(result.elements().get(1).metadata().containsKey("docx_document_defaults"));
            assertEquals(ElementType.NarrativeText, result.elements().getLast().type());
        }
    }

    @Test
    void partialPropertiesMergeAndDirectValuesSuppressDefaults() throws Exception {
        try (final var doc = fixture()) {
            defaults(doc, 1, 1, null);
            style(doc, "Styled", 2, null, null, false);
            paragraph(doc, "Style number and default level", "Styled");
            final var direct = paragraph(doc, "Direct level", "Styled");
            direct.getCTP().getPPr().addNewNumPr().addNewIlvl().setVal(id(0));
            paragraph(doc, "Suppressed", null).setNumID(id(0));
            final var result = parse(doc);
            assertEquals("2", result.elements().getFirst().metadata().get("docx_num_id"));
            assertEquals("1.1", result.elements().getFirst().metadata().get("docx_list_label"));
            assertEquals(List.of("list_level"), result.elements().getFirst().metadata().get("docx_document_defaults"));
            assertEquals("1.", result.elements().get(1).metadata().get("docx_list_label"));
            assertFalse(result.elements().get(1).metadata().containsKey("docx_document_defaults"));
            assertEquals(ElementType.NarrativeText, result.elements().getLast().type());
        }
    }

    @Test
    void namedDefaultStyleWinsOverDocumentDefaultProperties() throws Exception {
        try (final var doc = fixture()) {
            defaults(doc, 1, 1, 2);
            style(doc, "Implicit", 2, 0, 9, true);
            paragraph(doc, "Named default list", null);
            final var element = parse(doc).elements().getFirst();
            assertEquals(ElementType.ListItem, element.type());
            assertEquals("2", element.metadata().get("docx_num_id"));
            assertEquals("1.", element.metadata().get("docx_list_label"));
            assertEquals("default", element.metadata().get("docx_style_source"));
            assertFalse(element.metadata().containsKey("docx_document_defaults"));
        }
    }

    @Test
    void styleAssociationReplacesDocumentDefaultLevelOnly() throws Exception {
        try (final var doc = fixture()) {
            defaults(doc, 1, 1, null);
            style(doc, "Associated", null, null, null, false);
            doc.getNumbering().getAbstractNum(id(0)).getCTAbstractNum().getLvlArray(0).addNewPStyle().setVal(
                    "Associated");
            paragraph(doc, "Associated", "Associated");
            final var element = parse(doc).elements().getFirst();
            assertEquals("1.", element.metadata().get("docx_list_label"));
            assertEquals(List.of("num_id"), element.metadata().get("docx_document_defaults"));
        }
    }

    @Test
    void missingExplicitStyleStillUsesDocumentBaseProperties() throws Exception {
        try (final var doc = fixture()) {
            defaults(doc, 1, null, null);
            paragraph(doc, "Missing reference", "Absent");
            final var element = parse(doc).elements().getFirst();
            assertEquals("Absent", element.metadata().get("docx_style"));
            assertEquals("1.", element.metadata().get("docx_list_label"));
            assertEquals(List.of("num_id"), element.metadata().get("docx_document_defaults"));
        }
    }

    @Test
    void incompleteOrRunOnlyDefaultsDoNotMutateStyleParts() throws Exception {
        try (final var doc = fixture()) {
            final var root = doc.getStyles().getCtStyles();
            final var defaults = root.addNewDocDefaults();
            defaults.addNewRPrDefault().addNewRPr().addNewB();
            defaults.addNewPPrDefault();
            final var paragraph = paragraph(doc, "Plain", null);
            final String before = root.xmlText();
            final var policy = new DocxParagraphPolicyResolver(doc).resolve(paragraph);
            assertEquals(0, policy.heading());
            assertNull(policy.numId());
            assertTrue(policy.documentDefaults().isEmpty());
            assertEquals(before, root.xmlText());
        }
    }

    @Test
    void invalidEffectiveLevelFailsButOverriddenLevelIsIgnored() throws Exception {
        for (final boolean override : List.of(false, true)) {
            try (final var doc = fixture()) {
                defaults(doc, 1, 9, null);
                final var paragraph = paragraph(doc, "Level", null);
                if (override) {
                    paragraph.getCTP().addNewPPr().addNewNumPr().addNewIlvl().setVal(id(0));
                }
                if (override) {
                    assertEquals("1.", parse(doc).elements().getFirst().metadata().get("docx_list_label"));
                } else {
                    assertEquals(PartitionException.Code.MALFORMED_DOCUMENT,
                            assertThrows(PartitionException.class, () -> parse(doc)).code());
                }
            }
        }
    }

    @Test
    void tableCountersAndJsonProvenanceRetainDefaultSources() throws Exception {
        try (final var doc = fixture()) {
            defaults(doc, 1, null, null);
            paragraph(doc, "Before", null);
            doc.createTable(1, 1).getRow(0).getCell(0).getParagraphs().getFirst().createRun().setText("Inside");
            paragraph(doc, "After", null);
            final var result = parse(doc);
            assertEquals("3.", result.elements().getLast().metadata().get("docx_list_label"));
            final var item = (java.util.Map<?, ?>) ((List<?>) result.elements().get(1).metadata().get("docx_list_items")).getFirst();
            assertEquals("2.", item.get("docx_list_label"));
            assertEquals(List.of("num_id"), item.get("docx_document_defaults"));
            final var codec = ElementJson.create();
            assertEquals(result.elements(), codec.read(codec.write(result.elements())));
            final var chunk =
                    Chunker.create().basic(List.of(result.elements().getFirst()), ChunkOptions.defaults()).getFirst();
            final var original = codec.decodeOriginals((String) chunk.metadata().get("orig_elements")).getFirst();
            assertEquals(List.of("num_id"), original.metadata().get("docx_document_defaults"));
        }
    }

    @Test
    void concurrentDocumentsDoNotShareDefaultPoliciesOrCounters() throws Exception {
        try (final var doc = fixture()) {
            defaults(doc, 1, null, null);
            paragraph(doc, "Item", null);
            final var source = bytes(doc);
            final var result = BatchProcessor.create().mapOrdered(
                    java.util.stream.IntStream.range(0, 10).boxed().toList(), 3,
                    ignored -> DocWeave.create().partition(DocumentSource.bytes(source, "document-defaults.docx")));
            assertTrue(result.stream().allMatch(
                    r -> r.elements().getFirst().metadata().get("docx_list_label").equals("1.")));
        }
    }
}
