package io.docweave.partition;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CsvModuleTest {
    @Test
    void standaloneModuleDiscoversOnlyItsOwnFormatAndParses() throws Exception {
        final var processor = io.docweave.DocWeave.create();
        assertEquals(Set.of(DocumentFormat.CSV), processor.supportedFormats());
        final var source = DocumentSource.text("name,value\na,b", "example.csv");
        final var result = processor.partition(source);
        assertEquals(DocumentFormat.CSV, result.format());
        assertFalse(result.elements().isEmpty());
        assertFalse(java.lang.reflect.Modifier.isPublic(
                PartitionerRegistry.create().find(DocumentFormat.CSV).orElseThrow().getClass().getModifiers()));
    }

    @Test
    void unrelatedParsersAndJsonAreAbsentFromClasspath() {
        for (final String type : java.util.List.of("org.apache.poi.xwpf.usermodel.XWPFDocument", "org.jsoup.Jsoup",
                     "org.commonmark.parser.Parser", "tools.jackson.databind.json.JsonMapper")) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(type));
        }
    }
}
