package dev.docweave.partition;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TxtModuleTest {
    @Test
    void standaloneModuleDiscoversOnlyItsOwnFormatAndParses() throws Exception {
        final var processor = dev.docweave.DocWeave.create();
        assertEquals(Set.of(DocumentFormat.TXT), processor.supportedFormats());
        final var source = DocumentSource.text("Example text.", "example.txt");
        final var result = processor.partition(source);
        assertEquals(DocumentFormat.TXT, result.format());
        assertFalse(result.elements().isEmpty());
        assertFalse(java.lang.reflect.Modifier.isPublic(
                PartitionerRegistry.create().find(DocumentFormat.TXT).orElseThrow().getClass().getModifiers()));
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
