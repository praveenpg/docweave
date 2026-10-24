package io.docweave.partition;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownModuleTest {
    @Test
    void standaloneModuleDiscoversOnlyItsOwnFormatAndParses() throws Exception {
        final var processor = io.docweave.DocWeave.create();
        assertEquals(Set.of(DocumentFormat.MARKDOWN), processor.supportedFormats());
        final var source = DocumentSource.text("Example text.", "example.markdown");
        final var result = processor.partition(source);
        assertEquals(DocumentFormat.MARKDOWN, result.format());
        assertFalse(result.elements().isEmpty());
        assertFalse(java.lang.reflect.Modifier.isPublic(
                PartitionerRegistry.create().find(DocumentFormat.MARKDOWN).orElseThrow().getClass().getModifiers()));
    }

    @Test
    void unrelatedParsersAndJsonAreAbsentFromClasspath() {
        for (final String type : java.util.List.of("org.apache.poi.xwpf.usermodel.XWPFDocument", "org.jsoup.Jsoup",
                     "org.apache.commons.csv.CSVFormat", "tools.jackson.databind.json.JsonMapper")) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(type));
        }
    }

    @Test
    void domModeRequiresExplicitHtmlModule() {
        final var options = PartitionOptions.builder()
                                    .markdownOptions(new MarkdownOptions(
                                            true, 128, MarkdownOptions.RawHtml.EXTRACT_DOM, TableLimits.defaults()))
                                    .build();
        final var failure = assertThrows(PartitionException.class,
                ()
                        -> io.docweave.DocWeave.create().partition(
                                DocumentSource.text("# Heading", "example.md"), options));
        assertEquals(PartitionException.Code.UNSUPPORTED_FORMAT, failure.code());
        assertTrue(failure.getMessage().contains("docweave-html"));
    }
}
