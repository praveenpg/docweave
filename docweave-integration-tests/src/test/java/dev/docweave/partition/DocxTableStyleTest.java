package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.chunking.ChunkOptions;
import dev.docweave.chunking.Chunker;
import dev.docweave.core.Element;
import dev.docweave.json.ElementJson;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.xmlbeans.XmlObject;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DocxTableStyleTest {
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

    private void append(final XmlObject target, final String xml) throws Exception {
        final var source = XmlObject.Factory.parse("<root xmlns:w='" + DocxBodyView.WORD + "'>" + xml + "</root>");
        try (final var from = source.newCursor(); final var to = target.newCursor()) {
            from.toFirstChild();
            to.toEndToken();
            from.copyXmlContents(to);
        }
    }

    private void style(final XWPFDocument doc, final String name, final String base, final boolean implicit,
            final String xml) throws Exception {
        final var style = CTStyle.Factory.newInstance();
        style.setStyleId(name);
        style.setType(STStyleType.TABLE);
        if (base != null) {
            style.addNewBasedOn().setVal(base);
        }
        if (implicit) {
            style.setDefault(true);
        }
        append(style, xml);
        doc.getStyles().addStyle(new XWPFStyle(style));
    }

    private String number(final int num, final int level) {
        return "<w:pPr><w:numPr><w:ilvl w:val='" + level + "'/><w:numId w:val='" + num + "'/></w:numPr></w:pPr>";
    }

    private XWPFTable table(final XWPFDocument doc, final String style, final String... text) {
        final var table = doc.createTable(text.length, 1);
        if (style != null) {
            table.setStyleID(style);
        }
        for (int row = 0; row < text.length; row++) {
            table.getRow(row).getCell(0).getParagraphs().getFirst().createRun().setText(text[row]);
        }
        return table;
    }

    private PartitionResult parse(final XWPFDocument doc) throws Exception {
        final var output = new ByteArrayOutputStream();
        doc.write(output);
        return DocWeave.create().partition(DocumentSource.bytes(output.toByteArray(), "table-styles.docx"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(final Element table) {
        return (List<Map<String, Object>>) table.metadata().get("docx_list_items");
    }

    @Test
    void inheritedTablePropertiesAndWholeTableOverridesApply() throws Exception {
        try (final var doc = fixture()) {
            style(doc, "Base", null, false, number(1, 1));
            style(doc, "Derived", "Base", false,
                    "<w:tblStylePr w:type='wholeTable'><w:pPr><w:numPr><w:ilvl w:val='0'/></w:numPr></w:pPr></w:tblStylePr>");
            table(doc, "Derived", "First", "Second");
            final var result = parse(doc);
            assertEquals(List.of("1.", "2."),
                    items(result.elements().getFirst()).stream().map(i -> i.get("docx_list_label")).toList());
            assertEquals("Derived", result.elements().getFirst().metadata().get("docx_table_style"));
            assertTrue(result.warnings().isEmpty());
        }
    }

    @Test
    void paragraphAndDirectPropertiesOverrideTableStyle() throws Exception {
        try (final var doc = fixture()) {
            style(doc, "Table", null, false, number(1, 0));
            final var paragraphStyle = CTStyle.Factory.newInstance();
            paragraphStyle.setStyleId("Paragraph");
            paragraphStyle.setType(STStyleType.PARAGRAPH);
            append(paragraphStyle, number(2, 1));
            doc.getStyles().addStyle(new XWPFStyle(paragraphStyle));
            final var table = table(doc, "Table", "Styled", "Suppressed", "Table base");
            table.getRow(0).getCell(0).getParagraphs().getFirst().setStyle("Paragraph");
            table.getRow(1).getCell(0).getParagraphs().getFirst().setNumID(id(0));
            final var element = parse(doc).elements().getFirst();
            assertEquals(List.of("1.1", "1."), items(element).stream().map(i -> i.get("docx_list_label")).toList());
            assertEquals("2", items(element).getFirst().get("docx_num_id"));
            assertFalse(items(element).getFirst().containsKey("docx_table_style_properties"));
        }
    }

    @Test
    void wholeTableOutlineDoesNotReplaceInheritedGeneralOutlineOrNumbering() throws Exception {
        try (final var doc = fixture()) {
            style(doc, "WholeBase", null, false,
                    "<w:pPr><w:numPr><w:numId w:val='1'/></w:numPr>"
                            + "<w:outlineLvl w:val='2'/></w:pPr>" + condition("wholeTable", 6));
            style(doc, "WholeChild", "WholeBase", false, condition("wholeTable", 4));
            grid(doc, "WholeChild", 2, 1);
            final var result = parse(doc);
            assertEquals(List.of(2, 2), outlines(result.elements().getFirst()));
            assertEquals(List.of("1.", "2."),
                    items(result.elements().getFirst()).stream().map(item -> item.get("docx_list_label")).toList());
            assertTrue(result.warnings().isEmpty());
        }
    }

    @Test
    void wholeTableOutlineAloneDoesNotInventSemanticRanges() throws Exception {
        try (final var doc = fixture()) {
            style(doc, "OnlyWhole", null, false, condition("wholeTable", 4));
            grid(doc, "OnlyWhole", 1, 1);
            final var result = parse(doc);
            assertEquals("0,0", result.elements().getFirst().text());
            assertFalse(result.elements().getFirst().metadata().containsKey("docx_table_style_paragraphs"));
            assertTrue(result.warnings().isEmpty());
        }
    }

    @Test
    void directBodyExceptionRequiresAnActivePositionalHeading() throws Exception {
        for (final boolean enabled : List.of(false, true)) {
            try (final var doc = fixture()) {
                style(doc, "BodyException", null, false, baseOutline(2) + condition("firstRow", 4));
                final var table = grid(doc, "BodyException", 2, 1);
                append(table.getCTTbl().getTblPr(), "<w:tblLook w:firstRow='" + enabled + "'/>");
                for (var row : table.getRows()) {
                    row.getCell(0).getParagraphs().getFirst().getCTP().addNewPPr().addNewOutlineLvl().setVal(id(9));
                }
                final var element = parse(doc).elements().getFirst();
                if (enabled) {
                    assertEquals(List.of(4), outlines(element));
                } else {
                    assertFalse(element.metadata().containsKey("docx_table_style_paragraphs"));
                }
                // A general table heading alone must not displace direct body text.
                assertFalse(element.metadata().containsKey("docx_list_items"));
            }
        }
    }

    @Test
    void tableLayerOverridesDocumentDefaultsButNeverLeaksOutsideTable() throws Exception {
        try (final var doc = fixture()) {
            final var number =
                    doc.getStyles().getCtStyles().addNewDocDefaults().addNewPPrDefault().addNewPPr().addNewNumPr();
            number.addNewNumId().setVal(id(2));
            style(doc, "Table", null, false, number(1, 0));
            doc.createParagraph().createRun().setText("Body");
            table(doc, "Table", "Cell");
            final var result = parse(doc);
            assertEquals("2", result.elements().getFirst().metadata().get("docx_num_id"));
            assertEquals("1", items(result.elements().getLast()).getFirst().get("docx_num_id"));
            assertFalse(items(result.elements().getLast()).getFirst().containsKey("docx_document_defaults"));
        }
    }

    @Test
    void declaredTableDefaultAppliesOnlyToTablesWithoutStyleReferences() throws Exception {
        try (final var doc = fixture()) {
            style(doc, "DefaultTable", null, true, number(1, 0));
            table(doc, null, "Implicit");
            table(doc, "Missing", "Unresolved");
            final var result = parse(doc);
            assertEquals("default", result.elements().getFirst().metadata().get("docx_table_style_source"));
            assertEquals("1.", items(result.elements().getFirst()).getFirst().get("docx_list_label"));
            assertEquals(true, result.elements().getLast().metadata().get("docx_table_style_unresolved"));
            assertFalse(result.elements().getLast().metadata().containsKey("docx_list_items"));
            assertEquals(1, result.warnings().size());
            assertEquals(PartitionResult.WarningCode.DOCX_CONTENT_OMITTED, result.warnings().getFirst().code());
            assertEquals("DOCX table-style chain could not be resolved; paragraph style properties were omitted",
                    result.warnings().getFirst().message());
        }
    }

    @Test
    void disabledPositionalConditionsAreNotAppliedGlobally() throws Exception {
        try (final var doc = fixture()) {
            style(doc, "Conditional", null, false,
                    number(1, 0) + "<w:tblStylePr w:type='firstRow'>" + number(2, 1) + "</w:tblStylePr>");
            table(doc, "Conditional", "First", "Second");
            final var result = parse(doc);
            assertEquals(List.of("1.", "2."),
                    items(result.elements().getFirst()).stream().map(i -> i.get("docx_list_label")).toList());
            assertFalse(result.elements().getFirst().metadata().containsKey("docx_table_style_conditions_omitted"));
            assertTrue(result.warnings().isEmpty());
        }
    }

    @Test
    void outlineRangesAndUnicodeOffsetsSurviveJsonAndProvenance() throws Exception {
        try (final var doc = fixture()) {
            style(doc, "Outline", null, false, "<w:pPr><w:outlineLvl w:val='1'/></w:pPr>");
            table(doc, "Outline", "😀 first", "Next");
            final var element = parse(doc).elements().getFirst();
            final var ranges = (List<?>) element.metadata().get("docx_table_style_paragraphs");
            assertEquals(7, ((Map<?, ?>) ranges.getFirst()).get("end_index"));
            assertEquals(8, ((Map<?, ?>) ranges.getLast()).get("start_index"));
            assertEquals(1, ((Map<?, ?>) ranges.getFirst()).get("outline_level"));
            final var codec = ElementJson.create();
            assertEquals(List.of(element), codec.read(codec.write(List.of(element))));
            final var chunk = Chunker.create().basic(List.of(element), ChunkOptions.defaults()).getFirst();
            assertFalse(chunk.metadata().containsKey("docx_table_style_paragraphs"));
            assertEquals(ranges, codec.decodeOriginals((String) chunk.metadata().get("orig_elements"))
                                         .getFirst()
                                         .metadata()
                                         .get("docx_table_style_paragraphs"));
        }
    }

    @Test
    void cyclicChainsKeepTextAndReportUnresolvedStyles() throws Exception {
        try (final var doc = fixture()) {
            style(doc, "A", "B", false, number(1, 0));
            style(doc, "B", "A", false, "");
            table(doc, "A", "Retained");
            final var result = parse(doc);
            assertEquals("Retained", result.elements().getFirst().text());
            assertEquals(true, result.elements().getFirst().metadata().get("docx_table_style_unresolved"));
            assertFalse(result.elements().getFirst().metadata().containsKey("docx_list_items"));
        }
    }

    @Test
    void nestedTablesResolveTheirOwnStyleWithoutOuterStyleLeakage() throws Exception {
        for (final boolean styled : List.of(false, true)) {
            try (final var doc = fixture()) {
                style(doc, "Outer", null, false, number(1, 0));
                style(doc, "Inner", null, false, number(2, 1));
                final var outer = table(doc, "Outer", "Outer text");
                append(outer.getRow(0).getCell(0).getCTTc(),
                        "<w:tbl><w:tblPr>" + (styled ? "<w:tblStyle w:val='Inner'/>" : "")
                                + "</w:tblPr><w:tr><w:tc><w:p><w:r><w:t>Inner text</w:t></w:r></w:p></w:tc></w:tr></w:tbl>");
                final var element = parse(doc).elements().getFirst();
                assertTrue(element.text().contains("Outer text"));
                assertTrue(element.text().contains("Inner text"));
                assertEquals(styled ? 2 : 1, items(element).size());
                if (styled) {
                    assertEquals("Inner", items(element).getLast().get("docx_table_style"));
                    assertEquals("1.1", items(element).getLast().get("docx_list_label"));
                }
            }
        }
    }

    @Test
    void paragraphStyleCannotBeUsedAsATableStyle() throws Exception {
        try (final var doc = fixture()) {
            final var wrong = CTStyle.Factory.newInstance();
            wrong.setStyleId("Wrong");
            wrong.setType(STStyleType.PARAGRAPH);
            append(wrong, number(1, 0));
            doc.getStyles().addStyle(new XWPFStyle(wrong));
            table(doc, "Wrong", "Kept");
            final var result = parse(doc);
            assertEquals("Kept", result.elements().getFirst().text());
            assertEquals(true, result.elements().getFirst().metadata().get("docx_table_style_unresolved"));
            assertFalse(result.elements().getFirst().metadata().containsKey("docx_list_items"));
            assertEquals(1, result.warnings().size());
        }
    }

    private String baseOutline(final int outline) {
        return "<w:pPr><w:outlineLvl w:val='" + outline + "'/></w:pPr>";
    }

    private String condition(final String type, final int outline) {
        return "<w:tblStylePr w:type='" + type + "'><w:pPr><w:outlineLvl w:val='" + outline
                + "'/></w:pPr></w:tblStylePr>";
    }

    private XWPFTable grid(final XWPFDocument doc, final String style, final int rows, final int columns) {
        final var table = doc.createTable(rows, columns);
        table.setStyleID(style);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                table.getRow(r).getCell(c).getParagraphs().getFirst().createRun().setText(r + "," + c);
            }
        }
        return table;
    }

    @SuppressWarnings("unchecked")
    private List<Integer> outlines(final Element table) {
        return ((List<Map<String, Object>>) table.metadata().get("docx_table_style_paragraphs"))
                .stream()
                .map(range -> (Integer) range.get("outline_level"))
                .toList();
    }

    @Test
    void conditionalRegionPrecedenceUsesEdgesCornersAndBands() throws Exception {
        try (final var doc = fixture()) {
            style(doc, "Regions", null, false,
                    baseOutline(8) + condition("band1Vert", 0) + condition("band2Vert", 1) + condition("band1Horz", 2)
                            + condition("band2Horz", 3) + condition("firstRow", 4) + condition("lastRow", 5)
                            + condition("firstCol", 6) + condition("lastCol", 7) + condition("nwCell", 0)
                            + condition("neCell", 1) + condition("swCell", 2) + condition("seCell", 3));
            final var table = grid(doc, "Regions", 3, 3);
            append(table.getCTTbl().getTblPr(), "<w:tblStyleRowBandSize w:val='1'/><w:tblStyleColBandSize w:val='1'/>"
                                                        + "<w:tblLook w:val='01E0'/>");
            final var result = parse(doc);
            assertEquals(List.of(0, 4, 1, 6, 2, 7, 2, 5, 3), outlines(result.elements().getFirst()));
            final var element = result.elements().getFirst();
            final var codec = ElementJson.create();
            assertEquals(List.of(element), codec.read(codec.write(List.of(element))));
            final var chunk = Chunker.create().basic(List.of(element), ChunkOptions.defaults()).getFirst();
            final var original = codec.decodeOriginals((String) chunk.metadata().get("orig_elements")).getFirst();
            assertEquals(element.metadata().get("docx_table_style_paragraphs"),
                    original.metadata().get("docx_table_style_paragraphs"));
            assertFalse(chunk.metadata().containsKey("docx_table_style_paragraphs"));
            assertTrue(result.warnings().isEmpty());
        }
    }

    @Test
    void namedLookFlagsOverridePackedMaskAndGateStoredCnf() throws Exception {
        try (final var doc = fixture()) {
            style(doc, "Flags", null, false, baseOutline(8) + condition("firstRow", 1) + condition("lastCol", 2));
            final var table = grid(doc, "Flags", 2, 2);
            append(table.getCTTbl().getTblPr(), "<w:tblLook w:val='0120' w:firstRow='false'/>");
            final var cell = table.getRow(0).getCell(0);
            append(cell.getCTTc().addNewTcPr(), "<w:cnfStyle w:firstRow='true'/>");
            assertEquals(List.of(8, 2, 8, 2), outlines(parse(doc).elements().getFirst()));
        }
    }

    @Test
    void inheritedRowPropertiesWinWhileMissingPropertiesFallThroughToColumn() throws Exception {
        for (final boolean last : List.of(false, true)) {
            try (final var doc = fixture()) {
                String row = last ? "lastRow" : "firstRow", column = last ? "lastCol" : "firstCol";
                style(doc, "OverlapBase", null, false, condition(row, 4));
                style(doc, "OverlapChild", "OverlapBase", false,
                        "<w:tblStylePr w:type='" + column + "'><w:pPr><w:numPr><w:numId w:val='1'/>"
                                + "</w:numPr><w:outlineLvl w:val='5'/></w:pPr></w:tblStylePr>");
                final var table = grid(doc, "OverlapChild", 2, 2);
                append(table.getCTTbl().getTblPr(),
                        "<w:tblLook w:" + row + "='true' w:" + column.replace("Col", "Column") + "='true'/>");
                final var element = parse(doc).elements().getFirst();
                final var ranges = (List<?>) element.metadata().get("docx_table_style_paragraphs");
                final String anchor = last ? "1,1" : "0,0";
                final int offset = element.text().indexOf(anchor);
            final var overlap = ranges.stream().map(value -> (Map<?, ?>) value)
                    .filter(value -> value.get("start_index").equals(offset)).findFirst().orElseThrow();
            assertEquals(4, overlap.get("outline_level"));
            assertTrue(items(element).stream().anyMatch(
                    item -> item.get("start_index").equals(offset) && item.get("docx_num_id").equals("1")));
            }
        }
    }

    @Test
    void bandSizesAndDisablingFlagsSelectLogicalStripes() throws Exception {
        for (final boolean horizontal : List.of(true, false)) {
            try (final var doc = fixture()) {
                style(doc, "Bands", null, false,
                        baseOutline(8) + condition("band1Vert", 0) + condition("band2Vert", 1)
                                + condition("band1Horz", 2) + condition("band2Horz", 3));
                final var table = grid(doc, "Bands", 5, 5);
                append(table.getCTTbl().getTblPr(),
                        "<w:tblStyleRowBandSize w:val='2'/><w:tblStyleColBandSize w:val='2'/>"
                                + "<w:tblLook w:noHBand='" + !horizontal + "'/>");
                final var values = outlines(parse(doc).elements().getFirst());
                if (horizontal) {
                    assertEquals(List.of(2, 2, 3, 3, 2),
                            List.of(values.get(0), values.get(5), values.get(10), values.get(15), values.get(20)));
                } else {
                    assertEquals(List.of(0, 0, 1, 1, 0), values.subList(0, 5));
                }
            }
        }
    }

    @Test
    void inheritedBandSettingsAndZeroBandSizeAreRespected() throws Exception {
        try (final var doc = fixture()) {
            style(doc, "BaseBands", null, false,
                    "<w:tblPr><w:tblStyleRowBandSize w:val='2'/></w:tblPr>" + baseOutline(8) + condition("band1Horz", 1)
                            + condition("band2Horz", 2));
            style(doc, "DerivedBands", "BaseBands", false, "");
            grid(doc, "DerivedBands", 3, 1);
            final var disabled = grid(doc, "DerivedBands", 2, 1);
            append(disabled.getCTTbl().getTblPr(), "<w:tblStyleRowBandSize w:val='0'/>");
            final var result = parse(doc);
            assertEquals(List.of(1, 1, 2), outlines(result.elements().getFirst()));
            assertEquals(List.of(8, 8), outlines(result.elements().getLast()));
        }
    }

    @Test
    void absentBandSizesDoNotActivateSemanticStripesEvenWithStoredCnf() throws Exception {
        try (final var doc = fixture()) {
            style(doc, "AbsentSizes", null, false,
                    "<w:pPr><w:outlineLvl w:val='9'/></w:pPr>" + condition("band1Horz", 1) + condition("band2Horz", 2)
                            + condition("band1Vert", 3) + condition("band2Vert", 4));
            final var table = grid(doc, "AbsentSizes", 2, 2);
            append(table.getCTTbl().getTblPr(), "<w:tblLook w:noHBand='false' w:noVBand='false'/>");
            append(table.getRow(0).getCell(0).getCTTc().addNewTcPr(),
                    "<w:cnfStyle w:oddHBand='true' w:oddVBand='true'/>");
            assertEquals(List.of(9, 9, 9, 9), outlines(parse(doc).elements().getFirst()));
        }
    }

    @Test
    void explicitSizeEnablesOnlyItsAxisAndKeepsOtherAxisInactive() throws Exception {
        for (final boolean horizontal : List.of(true, false)) {
            try (final var doc = fixture()) {
                style(doc, "OneAxis", null, false,
                        "<w:pPr><w:outlineLvl w:val='9'/></w:pPr>" + condition("band1Horz", 1)
                                + condition("band2Horz", 2) + condition("band1Vert", 3) + condition("band2Vert", 4));
                final var table = grid(doc, "OneAxis", 2, 2);
                append(table.getCTTbl().getTblPr(),
                        horizontal ? "<w:tblStyleRowBandSize w:val='1'/>" : "<w:tblStyleColBandSize w:val='1'/>");
                assertEquals(horizontal ? List.of(1, 1, 2, 2) : List.of(3, 4, 3, 4),
                        outlines(parse(doc).elements().getFirst()));
            }
        }
    }

    @Test
    void onlyEnabledInheritedEdgeBranchesReserveStripePositions() throws Exception {
        for (final String edge : List.of("firstRow", "lastRow", "firstCol", "lastCol")) {
            for (final boolean branch : List.of(false, true)) {
                for (final boolean enabled : List.of(false, true)) {
                    try (final var doc = fixture()) {
                        final boolean horizontal = edge.endsWith("Row");
                        final String first = horizontal ? "band1Horz" : "band1Vert";
                        final String second = horizontal ? "band2Horz" : "band2Vert";
                        style(doc, "EdgeBase", null, false,
                                condition(first, 0) + condition(second, 1) + (branch ? condition(edge, 7) : ""));
                        style(doc, "EdgeChild", "EdgeBase", false, "");
                        final var table = grid(doc, "EdgeChild", horizontal ? 3 : 1, horizontal ? 1 : 3);
                        final String look = edge.endsWith("Col") ? edge.replace("Col", "Column") : edge;
                        append(table.getCTTbl().getTblPr(),
                                "<w:tblStyleRowBandSize w:val='1'/>"
                                        + "<w:tblStyleColBandSize w:val='1'/><w:tblLook w:" + look + "='" + enabled
                                        + "' w:noHBand='" + !horizontal + "' w:noVBand='" + horizontal + "'/>");
                        final var expected = branch && enabled
                                                     ? edge.startsWith("first") ? List.of(7, 0, 1) : List.of(0, 1, 7)
                                                     : List.of(0, 1, 0);
                        assertEquals(expected, outlines(parse(doc).elements().getFirst()),
                                edge + " branch=" + branch + " enabled=" + enabled);
                    }
                }
            }
        }
    }

    @Test
    void cnfMasksAndAttributesOverlayRowCellThenParagraph() throws Exception {
        try (final var doc = fixture()) {
            style(doc, "Cnf", null, false,
                    baseOutline(8) + condition("firstRow", 1) + condition("lastRow", 2) + condition("firstCol", 3));
            final var table = grid(doc, "Cnf", 2, 1);
            append(table.getCTTbl().getTblPr(), "<w:tblLook w:firstRow='true' w:lastRow='true' w:firstColumn='true'/>");
            append(table.getRow(0).getCtRow().addNewTrPr(), "<w:cnfStyle w:val='010000000000'/>");
            append(table.getRow(0).getCell(0).getCTTc().addNewTcPr(),
                    "<w:cnfStyle w:lastRow='false' w:firstRow='true'/>");
            final var paragraph = table.getRow(0).getCell(0).getParagraphs().getFirst();
            append(paragraph.getCTP().addNewPPr(), "<w:cnfStyle w:firstRow='false' w:firstColumn='true'/>");
            assertEquals(3, outlines(parse(doc).elements().getFirst()).getFirst());
        }
    }

    @Test
    void rowPropertyExceptionsOverrideTableLook() throws Exception {
        try (final var doc = fixture()) {
            style(doc, "Exception", null, false, baseOutline(8) + condition("firstRow", 1));
            final var table = grid(doc, "Exception", 2, 1);
            append(table.getCTTbl().getTblPr(), "<w:tblLook w:firstRow='false'/>");
            append(table.getRow(1).getCtRow(), "<w:tblPrEx><w:tblLook w:firstRow='true'/></w:tblPrEx>");
            append(table.getRow(1).getCtRow().addNewTrPr(), "<w:cnfStyle w:firstRow='true'/>");
            assertEquals(List.of(8, 1), outlines(parse(doc).elements().getFirst()));
        }
    }

    @Test
    void mergedFootprintsAndGridGapsDriveRegionMembership() throws Exception {
        try (final var doc = fixture()) {
            style(doc, "Merged", null, false, baseOutline(8) + condition("lastCol", 2) + condition("lastRow", 3));
            final var table = grid(doc, "Merged", 2, 2);
            append(table.getCTTbl().getTblPr(), "<w:tblLook w:lastColumn='true' w:lastRow='true'/>");
            final var top = table.getRow(0).getCell(0).getCTTc().addNewTcPr();
            top.addNewVMerge().setVal(STMerge.RESTART);
            table.getRow(1).getCell(0).getCTTc().addNewTcPr().addNewVMerge();
            final var element = parse(doc).elements().getFirst();
            // The vertically merged first cell reaches the last row; both stored text runs keep that region.
            // The last unmerged cell belongs to both edges; row precedence wins there too.
            assertEquals(List.of(3, 3, 2, 3), outlines(element));
        }
        try (final var doc = fixture()) {
            style(doc, "Gap", null, false, baseOutline(8) + condition("firstCol", 1) + condition("lastCol", 2));
            final var table = grid(doc, "Gap", 1, 1);
            append(table.getCTTbl().getTblPr(), "<w:tblLook w:firstColumn='true' w:lastColumn='true'/>");
            table.getRow(0).getCtRow().addNewTrPr().addNewGridBefore().setVal(id(1));
            assertEquals(List.of(2), outlines(parse(doc).elements().getFirst()));
        }
    }

    @Test
    void malformedLookCnfAndBandValuesFailWithTypedDiagnostics() throws Exception {
        for (final String invalid : List.of("<w:tblLook w:val='xyz'/>", "<w:tblLook w:firstRow='maybe'/>",
                     "<w:tblStyleRowBandSize w:val='-1'/>")) {
            try (final var doc = fixture()) {
                style(doc, "Invalid", null, false, condition("firstRow", 1));
                final var table = grid(doc, "Invalid", 1, 1);
                append(table.getCTTbl().getTblPr(), invalid);
                assertEquals(PartitionException.Code.MALFORMED_DOCUMENT,
                        assertThrows(PartitionException.class, () -> parse(doc)).code());
            }
        }
        try (final var doc = fixture()) {
            style(doc, "Invalid", null, false, condition("firstRow", 1));
            final var table = grid(doc, "Invalid", 1, 1);
            append(table.getRow(0).getCell(0).getCTTc().addNewTcPr(), "<w:cnfStyle w:val='01'/>");
            assertEquals(PartitionException.Code.MALFORMED_DOCUMENT,
                    assertThrows(PartitionException.class, () -> parse(doc)).code());
        }
    }

    @Test
    void conditionalNumberingAndDirectSuppressionKeepCounterIdentity() throws Exception {
        for (final boolean suppress : List.of(false, true)) {
            try (final var doc = fixture()) {
                style(doc, "Lists", null, false,
                        number(1, 0) + "<w:tblStylePr w:type='firstRow'>" + number(2, 1) + "</w:tblStylePr>");
                final var table = grid(doc, "Lists", 3, 1);
                append(table.getCTTbl().getTblPr(), "<w:tblLook w:firstRow='true'/>");
                if (suppress) {
                    table.getRow(0).getCell(0).getParagraphs().getFirst().setNumID(id(0));
                }
                final var element = parse(doc).elements().getFirst();
                assertEquals(suppress ? List.of("1.", "2.") : List.of("1.1", "1.", "2."),
                        items(element).stream().map(item -> item.get("docx_list_label")).toList());
            }
        }
    }

    @Test
    void conditionalPropertiesInheritIndividuallyAboveWholeTableLayers() throws Exception {
        try (final var doc = fixture()) {
            style(doc, "BaseRegions", null, false,
                    "<w:tblStylePr w:type='firstRow'>" + number(2, 1) + "</w:tblStylePr>");
            style(doc, "ChildRegions", "BaseRegions", false,
                    number(1, 0)
                            + "<w:tblStylePr w:type='firstRow'><w:pPr><w:numPr><w:ilvl w:val='0'/></w:numPr></w:pPr></w:tblStylePr>");
            final var table = grid(doc, "ChildRegions", 2, 1);
            append(table.getCTTbl().getTblPr(), "<w:tblLook w:firstRow='true'/>");
            final var result = items(parse(doc).elements().getFirst());
            assertEquals(List.of("2", "1"), result.stream().map(item -> item.get("docx_num_id")).toList());
            assertEquals(List.of("1.", "1."), result.stream().map(item -> item.get("docx_list_label")).toList());
        }
    }

    @Test
    void horizontalMergeAndGridSpanReachTheLogicalLastColumn() throws Exception {
        for (final boolean legacy : List.of(false, true)) {
            try (final var doc = fixture()) {
                style(doc, "Span", null, false, baseOutline(8) + condition("lastCol", 2));
                final var table = grid(doc, "Span", 1, legacy ? 2 : 1);
                append(table.getCTTbl().getTblPr(), "<w:tblLook w:lastColumn='true'/>");
                if (legacy) {
                    table.getRow(0).getCell(0).getCTTc().addNewTcPr().addNewHMerge().setVal(STMerge.RESTART);
                    table.getRow(0).getCell(1).getCTTc().addNewTcPr().addNewHMerge();
                } else {
                    table.getRow(0).getCell(0).getCTTc().addNewTcPr().addNewGridSpan().setVal(id(2));
                }
                assertEquals(legacy ? List.of(2, 2) : List.of(2), outlines(parse(doc).elements().getFirst()));
            }
        }
    }

    @Test
    void unrecognizedSemanticConditionsStillProduceExplicitDiagnostics() throws Exception {
        try (final var doc = fixture()) {
            style(doc, "Future", null, false, baseOutline(8) + condition("futureRegion", 2));
            grid(doc, "Future", 1, 1);
            final var result = parse(doc);
            assertEquals(List.of(8), outlines(result.elements().getFirst()));
            assertEquals(true, result.elements().getFirst().metadata().get("docx_table_style_conditions_omitted"));
            assertEquals(1, result.warnings().size());
        }
    }

    @Test
    void styleResolutionPreservesInterruption() throws Exception {
        try (final var doc = fixture()) {
            final var table = table(doc, null, "Cell");
            Thread.currentThread().interrupt();
            try {
                assertThrows(
                        java.io.InterruptedIOException.class, () -> new DocxTableParagraphStyles(doc).resolve(table));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        }
    }
}
