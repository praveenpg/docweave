package io.docweave.partition;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HtmlModuleTest {
    @Test
    void standaloneModuleDiscoversOnlyItsOwnFormatAndParses() throws Exception {
        final var processor = io.docweave.DocWeave.create();
        assertEquals(Set.of(DocumentFormat.HTML), processor.supportedFormats());
        final var source = DocumentSource.text("<p>Example text.</p>", "example.html");
        final var result = processor.partition(source);
        assertEquals(DocumentFormat.HTML, result.format());
        assertFalse(result.elements().isEmpty());
        assertFalse(java.lang.reflect.Modifier.isPublic(
                PartitionerRegistry.create().find(DocumentFormat.HTML).orElseThrow().getClass().getModifiers()));
    }

    @Test
    void standalonePreservationUsesOnlyHtmlAndCore() throws Exception {
        final var processor = io.docweave.DocWeave.create();
        assertTrue(java.util.ServiceLoader.load(HtmlExtraction.class)
                        .findFirst()
                        .orElseThrow()
                        .supportsNestedTablePreservation());
        assertTrue(
                PartitionerRegistry.create().find(DocumentFormat.HTML).orElseThrow().supportsNestedTablePreservation());
        final var result = processor.partition(
                DocumentSource.text(
                        "<table><tr><td>Outer<table><tr><td>Child</td></tr></table></td></tr></table>", "nested.html"),
                PartitionOptions.builder().nestedTableOptions(NestedTableOptions.preserveMetadata()).build());
        assertEquals(1, ((java.util.List<?>) result.elements().getFirst().metadata().get("nested_tables")).size());
    }

    @Test
    void missingEmptyAndNonemptySpanPoliciesRemainCompatibleForRootsAndSnapshots() throws Exception {
        final var processor = io.docweave.DocWeave.create();
        for (final String value :
                java.util.List.of("missing", "", " ", "+2", "02", "٢", "-1", "0", "1.5", "2147483648", "3")) {
            final String attributes = value.equals("missing") ? "" : " colspan='" + value + "' rowspan='" + value + "'";
            final var source = DocumentSource.text("<table><tr><td" + attributes + ">Root<table><tr><td" + attributes
                                                           + ">Child</td></tr></table></td></tr></table>",
                    "spans.html");
            final var baseline = processor.partition(source);
            final var actual = processor.partition(source,
                    PartitionOptions.builder().nestedTableOptions(NestedTableOptions.preserveMetadata()).build());
            final var table = actual.elements().getFirst();
            final var metadata = new java.util.LinkedHashMap<>(table.metadata());
            metadata.remove("nested_tables");
            metadata.remove("nested_tables_version");
            assertEquals(baseline.elements().getFirst(),
                    new io.docweave.core.Element(table.elementId(), table.type(), table.text(), metadata));
            assertEquals(baseline.warnings(), actual.warnings());
            final var child = (java.util.Map<?, ?>) ((java.util.List<?>) table.metadata().get("nested_tables")).getFirst();
            final boolean positive = java.util.Set.of("+2", "02", "٢", "3").contains(value);
            for (final String html : java.util.List.of(
                         (String) table.metadata().get("text_as_html"), (String) child.get("text_as_html"))) {
                assertEquals(positive, html.contains("colspan="), value);
                assertEquals(positive, html.contains("rowspan="), value);
                if (positive) {
                    assertTrue(html.contains("colspan=\"" + value + "\""), value);
                }
            }
        }
    }

    @Test
    void unrelatedParsersAndJsonAreAbsentFromClasspath() {
        for (final String type :
                java.util.List.of("org.apache.poi.xwpf.usermodel.XWPFDocument", "org.commonmark.parser.Parser",
                        "org.apache.commons.csv.CSVFormat", "tools.jackson.databind.json.JsonMapper")) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(type));
        }
    }
}
