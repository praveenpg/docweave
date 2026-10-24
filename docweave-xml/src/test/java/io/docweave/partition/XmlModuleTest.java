package io.docweave.partition;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class XmlModuleTest {
    @Test
    void standaloneModuleDiscoversOnlyItsOwnFormatAndParses() throws Exception {
        final var processor = io.docweave.DocWeave.create();
        assertEquals(Set.of(DocumentFormat.XML), processor.supportedFormats());
        final var source = DocumentSource.text("<r>Example text.</r>", "example.xml");
        final var result = processor.partition(source);
        assertEquals(DocumentFormat.XML, result.format());
        assertFalse(result.elements().isEmpty());
        assertFalse(java.lang.reflect.Modifier.isPublic(
                PartitionerRegistry.create().find(DocumentFormat.XML).orElseThrow().getClass().getModifiers()));
    }

    @Test
    void unrelatedParsersAndJsonAreAbsentFromClasspath() {
        for (final String type : java.util.List.of("org.apache.poi.xwpf.usermodel.XWPFDocument", "org.jsoup.Jsoup",
                     "org.commonmark.parser.Parser", "org.apache.commons.csv.CSVFormat",
                     "tools.jackson.databind.json.JsonMapper")) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(type));
        }
    }
}
