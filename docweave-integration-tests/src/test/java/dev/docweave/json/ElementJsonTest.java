package dev.docweave.json;

import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ElementJsonTest {
    private final ElementJson json = ElementJson.create();

    @Test
    void preservesNestedExtensionDataAndWireNames() {
        final String input = """
                [{"type":"Title","element_id":"abc","text":"Report 🧵",
                  "metadata":{"vendor":{"scores":[1,2],"optional":null},"page_number":3}}]
                """;
        final var elements = json.read(input);
        assertEquals(elements, json.read(json.write(elements)));
        assertTrue(json.write(elements).contains("\"element_id\""));
        assertFalse(json.write(elements).contains("elementId"));
    }

    @Test
    void metadataSnapshotIsDeeplyImmutable() {
        final var scores = new ArrayList<>(List.of(1, 2));
        final var nested = new HashMap<String, Object>();
        nested.put("scores", scores);
        final var element = new Element("id", ElementType.Title, "Heading", Map.of("vendor", nested));
        scores.add(3);
        nested.clear();
        assertTrue(json.write(List.of(element)).contains("[1,2]"));
        final var saved = (Map<?, ?>) element.metadata().get("vendor");
        assertThrows(UnsupportedOperationException.class, () -> ((List<?>) saved.get("scores")).clear());
    }

    @Test
    void omitsAbsentEmptyAndDebugMetadata() {
        final var metadata = new HashMap<String, Object>();
        metadata.put("filename", null);
        metadata.put("languages", List.of());
        metadata.put("data_source", Map.of());
        metadata.put("detection_origin", "test");
        final var result = json.read(json.write(List.of(new Element("id", ElementType.Title, "Heading", metadata))));
        assertEquals(Map.of(), result.getFirst().metadata());
    }

    @Test
    void compressedOriginalsRoundTrip() {
        final var elements = List.of(new Element("a", ElementType.NarrativeText, "Hello 🧵", Map.of()));
        assertEquals(elements, json.decodeOriginals(json.encodeOriginals(elements)));
        assertThrows(IllegalArgumentException.class, () -> json.decodeOriginals("invalid"));
    }

    @Test
    void refusesProvenanceThatItsDecoderCannotRead() {
        final var oversized = new Element("large", ElementType.Table, "x".repeat(16 * 1024 * 1024), Map.of());
        assertThrows(IllegalArgumentException.class, () -> json.encodeOriginals(List.of(oversized)));
    }

    @Test
    void rejectsUnsupportedTypesMalformedFieldsAndTrailingContent() {
        for (final String input : List.of("{}", "[{}]", "[null]",
                     "[{\"type\":\"Checkbox\",\"element_id\":\"id\",\"text\":\"\",\"metadata\":{}}]",
                     "[{\"type\":\"Title\",\"element_id\":\"id\",\"text\":5,\"metadata\":{}}]", "[] []")) {
            assertThrows(RuntimeException.class, () -> json.read(input), input);
        }
    }

    @Test
    void rejectsNonJsonMetadataValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new Element("id", ElementType.Title, "", Map.of("bad", Double.NaN)));
        assertThrows(IllegalArgumentException.class,
                () -> new Element("id", ElementType.Title, "", Map.of("bad", new StringBuilder())));
    }
}
