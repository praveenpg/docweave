package dev.docweave.partition;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TsvModuleTest {
    @Test
    void standaloneModuleDiscoversOnlyItsOwnFormatAndParses() throws Exception {
        final var processor = dev.docweave.DocWeave.create();
        assertEquals(Set.of(DocumentFormat.TSV), processor.supportedFormats());
        final var source = DocumentSource.text("name\tvalue\na\tb", "example.tsv");
        final var result = processor.partition(source);
        assertEquals(DocumentFormat.TSV, result.format());
        assertFalse(result.elements().isEmpty());
        assertFalse(java.lang.reflect.Modifier.isPublic(
                PartitionerRegistry.create().find(DocumentFormat.TSV).orElseThrow().getClass().getModifiers()));
    }

    @Test
    void unrelatedParsersAndJsonAreAbsentFromClasspath() {
        for (final String type : java.util.List.of("org.apache.poi.xwpf.usermodel.XWPFDocument", "org.jsoup.Jsoup",
                     "org.commonmark.parser.Parser", "tools.jackson.databind.json.JsonMapper")) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(type));
        }
    }
}
