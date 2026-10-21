package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.core.Element;
import dev.docweave.json.ElementJson;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Native reference checks preserve string/grouping differences rather than asserting broad parity. */
class TextReferenceTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ElementJson json = ElementJson.create();
    private final DocWeave processor = DocWeave.create();

    private List<Element> reference(final String name) throws Exception {
        try (final var stream = getClass().getResourceAsStream("/text-native-reference.json")) {
            final var saved = mapper.readValue(stream, Map.class);
            final var provenance = (Map<?, ?>) saved.get("reference");
            assertEquals("347fbbfd3e84e228017f125e5c5dbdc326ef7de2", provenance.get("upstream_commit"));
            assertEquals("3.13.16", provenance.get("python"));
            assertEquals(true, ((Map<?, ?>) provenance.get("model")).get("installed_package_verified"));
            final var selected = ((List<?>) saved.get("cases")).stream().map(value -> (Map<?, ?>) value)
                    .filter(value -> name.equals(value.get("name"))).findFirst().orElseThrow();
            final var path = Path.of("benchmarks/fixtures", (String) selected.get("input"));
            final var hash =
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
            assertEquals(selected.get("input_sha256"), hash);
            return json.read(mapper.writeValueAsString(selected.get("upstream_output")));
        }
    }

    private List<Element> candidate(final String filename, final PartitionOptions options) throws Exception {
        final var result = processor.partition(DocumentSource.of(Path.of("benchmarks/fixtures", filename)), options);
        assertTrue(result.warnings().isEmpty());
        return result.elements();
    }

    private List<String> texts(final List<Element> elements) {
        return elements.stream().map(Element::text).toList();
    }

    private List<String> cells(final Element table) {
        return Jsoup.parse((String) table.metadata().get("text_as_html"))
                .select("td, th")
                .stream()
                .map(org.jsoup.nodes.Element::wholeText)
                .toList();
    }

    @Test
    void baselineTextMatchesCategoriesAndUnicodeTextExactly() throws Exception {
        final var upstream = reference("plain-default");
        final var actual = candidate("plain.txt", PartitionOptions.defaults());
        assertEquals(texts(upstream), texts(actual));
        assertEquals(upstream.stream().map(Element::type).toList(), actual.stream().map(Element::type).toList());
        assertEquals(3, actual.size());
        assertTrue(actual.getLast().text().contains("🧵 and 𐐀"));
        for (final var element : actual) {
            assertEquals("plain.txt", element.metadata().get("filename"));
            assertEquals("text/plain", element.metadata().get("filetype"));
        }
        assertEquals(actual, json.read(json.write(actual)));
    }

    @Test
    void groupingComparisonsKeepWrappedLinesListsAndClassifierDifferencesExplicit() throws Exception {
        final var upstream = reference("text-auto");
        assertEquals(List.of("Report", "This sentence wraps", "onto a second line.", "Another sentence ends.",
                             "First item continues here", "Second item", "Last paragraph."),
                texts(upstream));
        final var noGrouper = reference("text-no-grouper");
        assertEquals(List.of("Report", "This sentence wraps", "onto a second line.", "Another sentence ends.",
                             "First item", "continues here", "Second item", "Last paragraph."),
                texts(noGrouper));
        final var defaults = candidate("text-policies.txt", PartitionOptions.defaults());
        assertEquals(List.of("Report This sentence wraps onto a second line. Another sentence ends.",
                             "First item continues here", "Second item", "Last paragraph."),
                texts(defaults));
        final var automatic = candidate("text-policies.txt",
                PartitionOptions.builder().paragraphMode(PartitionOptions.ParagraphMode.AUTO).build());
        assertEquals(List.of("Report", "This sentence wraps onto a second line.", "Another sentence ends.",
                             "First item continues here", "Second item", "Last paragraph."),
                texts(automatic));
        final var lines = candidate("text-policies.txt",
                PartitionOptions.builder().paragraphMode(PartitionOptions.ParagraphMode.LINE_BREAKS).build());
        assertEquals(texts(noGrouper), texts(lines));
        assertNotEquals(noGrouper.stream().map(Element::type).toList(), lines.stream().map(Element::type).toList());
    }

    @Test
    void suppliedUpstreamSizeKeywordsDoNotSplitThisInputWhileJavaEnforcesItsMaximum() throws Exception {
        final var baseline = reference("plain-default");
        final var upstream = reference("text-size-keywords");
        assertEquals(texts(baseline), texts(upstream));
        assertTrue(upstream.stream().anyMatch(e -> e.text().codePointCount(0, e.text().length()) > 30));
        final var actual =
                candidate("plain.txt", PartitionOptions.builder().txtOptions(new TxtOptions(10, 30)).build());
        assertEquals(9, actual.size());
        assertTrue(actual.stream().allMatch(e -> e.text().codePointCount(0, e.text().length()) <= 30));
        final String original =
                Files.readString(Path.of("benchmarks/fixtures/plain.txt")).replaceAll("\\s+", " ").strip();
        assertEquals(original, String.join(" ", texts(actual)));
    }

    @Test
    void delimitedReferencesDocumentHeaderNumericInferenceAndQuotedControlCharacterDifferences() throws Exception {
        for (final String kind : List.of("csv", "tsv")) {
            for (final boolean header : List.of(false, true)) {
                final var upstream = reference(kind + "-header-" + header).getFirst();
                final var options =
                        PartitionOptions.builder()
                                .delimitedOptions(new DelimitedOptions(
                                        header ? DelimitedOptions.Header.FIRST_ROW : DelimitedOptions.Header.NONE, true,
                                        TableLimits.defaults()))
                                .build();
                final var actual = candidate("quoted." + kind, options).getFirst();
                assertEquals(upstream.type(), actual.type());
                final var sourceCells = cells(upstream);
                final var actualCells = cells(actual);
                assertEquals(kind.equals("csv") ? 12 : 9, actualCells.size());
                assertEquals(sourceCells.size(), actualCells.size());
                final int width = kind.equals("csv") ? 4 : 3;
                for (int i = 0; i < actualCells.size(); i++) {
                    if (header && (i == width + 1 || i == width * 2 + 1)) {
                        continue;
                    }
                    assertEquals(sourceCells.get(i).replace("\\n", "\n").replace("\\t", "\t"), actualCells.get(i));
                }
                assertEquals(header ? "123" : "00123", sourceCells.get(width + 1));
                assertEquals(header ? "456" : "00456", sourceCells.get(width * 2 + 1));
                assertEquals("00123", actualCells.get(width + 1));
                assertEquals("00456", actualCells.get(width * 2 + 1));
                assertTrue(sourceCells.stream().anyMatch(cell -> cell.contains("\\n")));
                assertTrue(actualCells.stream().anyMatch(cell -> cell.contains("\n")));
                final var sourceHtml = Jsoup.parse((String) upstream.metadata().get("text_as_html"));
                final var actualHtml = Jsoup.parse((String) actual.metadata().get("text_as_html"));
                assertEquals(3, sourceHtml.select("tr").size());
                assertEquals(3, actualHtml.select("tr").size());
                assertEquals(0, sourceHtml.select("th").size());
                assertEquals(header ? width : 0, actualHtml.select("th").size());
                assertNotEquals(upstream.text(), actual.text());
                assertEquals("quoted." + kind, actual.metadata().get("filename"));
                assertEquals("text/" + kind, upstream.metadata().get("filetype"));
                assertEquals(kind.equals("csv") ? "text/csv" : "text/tab-separated-values",
                        actual.metadata().get("filetype"));
                assertEquals(List.of(actual), json.read(json.write(List.of(actual))));
            }
        }
    }
}
