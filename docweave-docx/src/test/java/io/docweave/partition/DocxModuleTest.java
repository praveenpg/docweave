package io.docweave.partition;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DocxModuleTest {
    @Test
    void standaloneModuleDiscoversOnlyItsOwnFormatAndParses() throws Exception {
        final var processor = io.docweave.DocWeave.create();
        assertEquals(Set.of(DocumentFormat.DOCX), processor.supportedFormats());
        byte[] bytes;
        try (final var document = new org.apache.poi.xwpf.usermodel.XWPFDocument();
                final var output = new java.io.ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Example text.");
            document.write(output);
            bytes = output.toByteArray();
        }
        final var source = DocumentSource.bytes(bytes, "example.docx");
        final var result = processor.partition(source);
        assertEquals(DocumentFormat.DOCX, result.format());
        assertFalse(result.elements().isEmpty());
        assertFalse(java.lang.reflect.Modifier.isPublic(
                PartitionerRegistry.create().find(DocumentFormat.DOCX).orElseThrow().getClass().getModifiers()));
    }

    @Test
    void standaloneDocxPreservesChildrenWithoutHtmlOrJsonDependencies() throws Exception {
        final var processor = io.docweave.DocWeave.create();
        assertTrue(
                PartitionerRegistry.create().find(DocumentFormat.DOCX).orElseThrow().supportsNestedTablePreservation());
        byte[] bytes;
        try (final var document = new org.apache.poi.xwpf.usermodel.XWPFDocument();
                final var output = new java.io.ByteArrayOutputStream()) {
            final var cell = document.createTable(1, 1).getRow(0).getCell(0);
            cell.setText("Root");
            final var xml = org.apache.xmlbeans.XmlObject.Factory.parse(
                    "<root xmlns:w='" + DocxBodyView.WORD
                    + "'><w:tbl><w:tr><w:tc><w:p><w:r><w:t>Child</w:t></w:r></w:p></w:tc></w:tr></w:tbl></root>");
            try (final var source = xml.newCursor(); final var target = cell.getCTTc().newCursor()) {
                source.toFirstChild();
                target.toEndToken();
                source.copyXmlContents(target);
            }
            document.write(output);
            bytes = output.toByteArray();
        }
        final var table = processor
                                  .partition(DocumentSource.bytes(bytes, "nested.docx"),
                                          PartitionOptions.builder()
                                                  .nestedTableOptions(NestedTableOptions.preserveMetadata())
                                                  .build())
                                  .elements()
                                  .getFirst();
        assertEquals(1, ((java.util.List<?>) table.metadata().get("nested_tables")).size());
    }

    @Test
    void unrelatedParsersAndJsonAreAbsentFromClasspath() {
        for (final String type : java.util.List.of("org.jsoup.Jsoup", "org.commonmark.parser.Parser",
                     "org.apache.commons.csv.CSVFormat", "tools.jackson.databind.json.JsonMapper")) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(type));
        }
    }
}
