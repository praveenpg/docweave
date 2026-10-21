package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
import dev.docweave.json.ElementJson;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Native XML comparisons assert intentional ordered/security behavior without importing Python. */
class XmlReferenceTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ElementJson json = ElementJson.create();
    private final DocWeave processor = DocWeave.create();

    private List<Element> reference(final String name) throws Exception {
        try (final var stream = getClass().getResourceAsStream("/xml-native-reference.json")) {
            final var saved = mapper.readValue(stream, Map.class);
            final var provenance = (Map<?, ?>) saved.get("reference");
            assertEquals("347fbbfd3e84e228017f125e5c5dbdc326ef7de2", provenance.get("upstream_commit"));
            assertEquals("3.13.16", provenance.get("python"));
            assertEquals(true, ((Map<?, ?>) provenance.get("model")).get("installed_package_verified"));
            final var selected = ((List<?>) saved.get("cases")).stream().map(value -> (Map<?, ?>) value)
                    .filter(value -> name.equals(value.get("name"))).findFirst().orElseThrow();
            final var path = Path.of("benchmarks/fixtures", (String) selected.get("input"));
            assertEquals(selected.get("input_sha256"),
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
            return json.read(mapper.writeValueAsString(selected.get("upstream_output")));
        }
    }

    private List<Element> candidate(final String filename, final XmlOptions options) throws Exception {
        final var result = processor.partition(DocumentSource.of(Path.of("benchmarks/fixtures", filename)),
                PartitionOptions.builder().xmlOptions(options).build());
        assertTrue(result.warnings().isEmpty());
        return result.elements();
    }

    private List<String> texts(final List<Element> elements) {
        return elements.stream().map(Element::text).toList();
    }

    @Test
    void structuredXmlRetainsNamespaceAttributesCdataAndSourceOrderInsteadOfUpstreamCategories() throws Exception {
        final var upstream = reference("structured-default");
        final var actual = candidate("structured.xml", XmlOptions.defaults());
        assertEquals(
                List.of("Quarterly 🧵 report", "important", "Before ", "  int value = 1;\n    next();", "Café & tea"),
                texts(upstream));
        assertEquals(List.of("Quarterly 🧵 report", "Before", "important", "after.", "  int value = 1;\n    next();",
                             "Café & tea"),
                texts(actual));
        assertTrue(upstream.stream().allMatch(e -> e.type() == ElementType.Title));
        assertTrue(actual.stream().allMatch(e -> e.type() == ElementType.UncategorizedText));
        for (final var pair : List.of(List.of(0, 0), List.of(1, 2), List.of(3, 4), List.of(4, 5))) {
            assertEquals(upstream.get(pair.getFirst()).text(), actual.get(pair.getLast()).text());
        }
        assertEquals(List.of("{urn:docweave:report}report", "{urn:docweave:report}paragraph",
                             "{urn:docweave:report}emphasis"),
                actual.get(2).metadata().get("xml_path"));
        assertEquals(Map.of("id", "007"), actual.getLast().metadata().get("xml_attributes"));
        assertEquals("{urn:docweave:report}item", actual.getLast().metadata().get("xml_tag"));
        assertFalse(upstream.getLast().metadata().containsKey("xml_path"));
        assertEquals(actual, json.read(json.write(actual)));
        assertTrue(actual.stream().allMatch(e
                -> "structured.xml".equals(e.metadata().get("filename"))
                           && "application/xml".equals(e.metadata().get("filetype"))));
    }

    @Test
    void mixedContentKeepsParentChildTailOrderAndWhitespacePolicyRemainsExplicit() throws Exception {
        final var upstream = reference("mixed-order");
        final var actual = candidate("mixed-order.xml", XmlOptions.defaults());
        assertEquals(List.of("inside 🧵", "Before "), texts(upstream));
        assertEquals(List.of("Before", "inside 🧵", "after.", "End."), texts(actual));
        assertEquals(List.of("root", "child"), actual.get(1).metadata().get("xml_path"));
        assertEquals(Map.of("role", "emphasis"), actual.get(1).metadata().get("xml_attributes"));
        assertEquals(Map.of("id", "001"), actual.getLast().metadata().get("xml_attributes"));
        final var preserved = candidate("mixed-order.xml", new XmlOptions(256, 100_000, true, false));
        assertEquals(List.of("Before ", "inside 🧵", " after.", " End."), texts(preserved));
        assertTrue(preserved.stream().noneMatch(e -> e.metadata().containsKey("xml_attributes")));
    }

    @Test
    void rawTagAndXpathReferencesAreRecordedWithoutPretendingJavaOffersThoseModes() throws Exception {
        final var raw = reference("structured-keep-tags");
        assertEquals(1, raw.size());
        assertEquals(ElementType.UncategorizedText, raw.getFirst().type());
        assertEquals(Files.readString(Path.of("benchmarks/fixtures/structured.xml")), raw.getFirst().text());
        final var filtered = reference("structured-xpath");
        assertEquals(List.of("Café & tea"), texts(filtered));
        final var actual = candidate("structured.xml", XmlOptions.defaults());
        assertEquals(filtered.getFirst().text(), actual.getLast().text());
        assertTrue(actual.size() > filtered.size(), "Java default streams the entire document, not an XPath subset");
        assertTrue(actual.stream().noneMatch(e -> e.text().contains("<report")),
                "Java text extraction does not return raw tags");
    }

    @Test
    void boundedInternalEntityFixtureRetainsJavasMandatoryDtdRejection() throws Exception {
        assertTrue(
                reference("internal-entity-policy").isEmpty(), "This pinned non-resolving upstream run emits no text");
        final var source = DocumentSource.of(Path.of("benchmarks/fixtures/entity-policy.xml"));
        final var failure = assertThrows(PartitionException.class, () -> processor.partition(source));
        assertEquals(PartitionException.Code.UNSAFE_XML, failure.code());
        assertFalse(failure.getMessage().contains("bounded local text"));
    }
}
