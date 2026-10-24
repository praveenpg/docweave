package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.core.Element;
import io.docweave.core.ElementType;
import io.docweave.json.ElementJson;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Compares reviewed native behaviors; differences remain explicit rather than normalized away. */
class DocxReferenceTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ElementJson json = ElementJson.create();

    private Map<?, ?> reference(final String filename) throws Exception {
        try (final var stream = getClass().getResourceAsStream("/docx-native-reference.json")) {
            final var document = mapper.readValue(stream, Map.class);
            final var provenance = (Map<?, ?>) document.get("reference");
            assertEquals("347fbbfd3e84e228017f125e5c5dbdc326ef7de2", provenance.get("upstream_commit"));
            assertEquals("3.13.16", provenance.get("python"));
            assertEquals(true, ((Map<?, ?>) provenance.get("model")).get("installed_package_verified"));
            final var selected = ((List<?>) document.get("cases")).stream().map(value -> (Map<?, ?>) value)
                    .filter(value -> filename.equals(value.get("input"))).findFirst().orElseThrow();
            final var digest = MessageDigest.getInstance("SHA-256").digest(
                    Files.readAllBytes(Path.of("benchmarks/fixtures", filename)));
            assertEquals(selected.get("input_sha256"), HexFormat.of().formatHex(digest),
                    "Reference must describe these exact input bytes");
            return selected;
        }
    }

    private List<Element> upstream(final Map<?, ?> reference) {
        return json.read(mapper.writeValueAsString(reference.get("upstream_output")));
    }

    private List<Element> candidate(final String filename) throws Exception {
        final var result = DocWeave.create().partition(Path.of("benchmarks/fixtures", filename));
        assertTrue(result.warnings().isEmpty());
        return result.elements();
    }

    private List<String> texts(final List<Element> elements) {
        return elements.stream().map(Element::text).toList();
    }

    private String normalized(final String text) {
        return text.replaceAll("\\s+", " ").strip();
    }

    private List<Integer> parents(final List<Element> elements) {
        final var ids = elements.stream().map(Element::elementId).toList();
        final var result = new ArrayList<Integer>();
        for (final var element : elements) {
            final Object parent = element.metadata().get("parent_id");
            final int index = parent == null ? -1 : ids.indexOf(parent);
            assertTrue(parent == null || index >= 0, "Parent must identify a source element");
            result.add(index);
        }
        return result;
    }

    private void assertSourceMetadata(final List<Element> elements, final String filename) {
        for (final var element : elements) {
            assertEquals(filename, element.metadata().get("filename"));
            assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    element.metadata().get("filetype"));
        }
    }

    @Test
    void structuredFixtureMatchesOrderLinksHierarchyAndCellContentWithExplicitDifferences() throws Exception {
        final var reference = upstream(reference("structured.docx"));
        final var actual = candidate("structured.docx");
        assertEquals(7, reference.size());
        assertEquals(List.of("Quarterly report", "🧵 See guide.", "Name\tId\nCafé & tea\t007", "Tasks", "Parent task",
                             "Child task", "Final paragraph."),
                texts(actual));
        for (final int index : List.of(0, 1, 3, 4, 5)) {
            assertEquals(reference.get(index).text(), actual.get(index).text());
            assertEquals(reference.get(index).type(), actual.get(index).type());
        }
        assertEquals(parents(reference), parents(actual), "Compare hierarchy by positions rather than generated IDs");
        assertEquals(reference.get(1).metadata().get("links"), actual.get(1).metadata().get("links"));
        assertEquals(List.of(6), actual.get(1).metadata().get("link_start_indexes"));
        assertEquals(normalized(reference.get(2).text()), normalized(actual.get(2).text()));
        assertNotEquals(reference.get(2).text(), actual.get(2).text(), "Java preserves row/cell separators");
        final var referenceTable = Jsoup.parse((String) reference.get(2).metadata().get("text_as_html"));
        final var actualTable = Jsoup.parse((String) actual.get(2).metadata().get("text_as_html"));
        assertEquals(referenceTable.select("td, th").eachText(), actualTable.select("td, th").eachText());
        assertEquals(0, referenceTable.select("th").size());
        assertEquals(2, actualTable.select("th").size(), "Java retains explicit header cells");
        assertEquals(reference.getLast().text(), actual.getLast().text());
        assertEquals(ElementType.UncategorizedText, reference.getLast().type());
        assertEquals(ElementType.NarrativeText, actual.getLast().type(), "Java body classification differs from NLP");
        assertSourceMetadata(reference, "structured.docx");
        assertSourceMetadata(actual, "structured.docx");
        assertEquals(actual, json.read(json.write(actual)));
    }

    @Test
    void contentFixtureDocumentsSupplementRevisionControlFieldAndMergeDifferences() throws Exception {
        final var reference = upstream(reference("docx-content.docx"));
        final var actual = candidate("docx-content.docx");
        assertEquals(List.of("Content report", "🧵 Body with references.",
                             "Selected revision: ", "Merged origin Top right Bottom right", "Illustration"),
                texts(reference));
        assertEquals(List.of(ElementType.UncategorizedText, ElementType.UncategorizedText, ElementType.NarrativeText,
                             ElementType.Table, ElementType.UncategorizedText),
                reference.stream().map(Element::type).toList());
        assertEquals(
                List.of("Content report", "🧵 Body with references.", "Selected revision: new", "Controlled section",
                        "Structured cell\tOther cell", "Merged origin\nContinuation text\tTop right\n\tBottom right",
                        "Illustration", "Thread icon", "Text box paragraph.", "2026-10-14", "Footnote explanation.",
                        "Endnote explanation.", "Review comment."),
                texts(actual));
        assertEquals(List.of(ElementType.Title, ElementType.NarrativeText, ElementType.NarrativeText, ElementType.Title,
                             ElementType.Table, ElementType.Table, ElementType.NarrativeText, ElementType.Image,
                             ElementType.NarrativeText, ElementType.NarrativeText, ElementType.NarrativeText,
                             ElementType.NarrativeText, ElementType.NarrativeText),
                actual.stream().map(Element::type).toList());
        for (final var pair : List.of(List.of(0, 0), List.of(1, 1), List.of(4, 6))) {
            assertEquals(reference.get(pair.getFirst()).text(), actual.get(pair.getLast()).text());
        }
        final String referenceText = String.join("\n", texts(reference));
        final String actualText = String.join("\n", texts(actual));
        for (final String additional : List.of("new", "Controlled section", "Structured cell", "Other cell",
                     "Continuation text", "Thread icon", "Text box paragraph.", "2026-10-14", "Footnote explanation.",
                     "Endnote explanation.", "Review comment.")) {
            assertFalse(
                    referenceText.contains(additional), "Observed omission in this pinned reference: " + additional);
            assertTrue(actualText.contains(additional), "Owned fixture content retained by Java: " + additional);
        }
        assertFalse(actualText.contains("old"), "Accepted revisions omit deleted text");
        final var referenceTable = Jsoup.parse((String) reference.get(3).metadata().get("text_as_html"));
        final var actualTable = Jsoup.parse((String) actual.get(5).metadata().get("text_as_html"));
        assertEquals("2", referenceTable.selectFirst("td[rowspan]").attr("rowspan"));
        assertEquals("2", actualTable.selectFirst("td[rowspan]").attr("rowspan"));
        assertFalse(referenceTable.text().contains("Continuation text"));
        assertTrue(actualTable.text().contains("Continuation text"));
        assertEquals("image/png", actual.get(7).metadata().get("image_mime_type"));
        assertSourceMetadata(reference, "docx-content.docx");
        assertSourceMetadata(actual, "docx-content.docx");
    }
}
