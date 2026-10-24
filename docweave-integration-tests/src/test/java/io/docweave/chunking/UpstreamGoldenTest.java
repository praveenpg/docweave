package io.docweave.chunking;

import io.docweave.json.ElementJson;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpstreamGoldenTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ElementJson json = ElementJson.create();

    @TestFactory
    Stream<DynamicTest> matchesPinnedUpstreamChunkers() throws IOException {
        return Stream
                .of(tests("/chunking-goldens.json"), tests("/table-chunking-goldens.json"),
                        tests("/chunk-options-goldens.json"))
                .flatMap(stream -> stream);
    }

    private Stream<DynamicTest> tests(final String resource) throws IOException {
        try (final var stream = getClass().getResourceAsStream(resource)) {
            final var fixture = mapper.readValue(stream, Map.class);
            return ((List<?>) fixture.get("cases"))
                    .stream()
                    .map(value -> {
                final var test = (Map<?, ?>) value;
                return DynamicTest.dynamicTest((String) test.get("name"), () -> {
                    final var options = options((Map<?, ?>) test.get("options"));
                    final var input = json.read(mapper.writeValueAsString(test.get("input")));
                    final var expectedChunks = (List<?>) test.get("expected");
                    final var payloads = (List<?>) test.get("reference_original_payloads");
                    for (int i = 0; i < payloads.size(); i++) {
                        final var expectedMetadata = (Map<?, ?>) ((Map<?, ?>) expectedChunks.get(i)).get("metadata");
                        final var expectedOriginals =
                                json.read(mapper.writeValueAsString(expectedMetadata.get("orig_elements")));
                        assertEquals(expectedOriginals, json.decodeOriginals((String) payloads.get(i)),
                                "Decode actual upstream zlib payload");
                    }
                    final var strategy =
                            test.get("strategy").equals("basic") ? Chunker.Strategy.BASIC : Chunker.Strategy.BY_TITLE;
                    final var actual = Chunker.create().chunk(input, strategy, options);
                    final var output = (List<?>) mapper.readValue(json.write(actual), List.class);
                    final var normalized = output.stream()
                                                   .map(value2 -> {
                        final var element = new LinkedHashMap<Object, Object>((Map<?, ?>) value2);
                        element.put("element_id", "<generated>");
                        final var metadata = new LinkedHashMap<Object, Object>((Map<?, ?>) element.get("metadata"));
                        if (metadata.containsKey("table_id")) {
                            metadata.put("table_id", "<table>");
                        }
                        if (resource.equals("/table-chunking-goldens.json")) {
                            metadata.keySet().removeAll(List.of("table_row_count", "table_column_count",
                                    "table_header_rows", "table_leading_header_rows", "table_row_start",
                                    "table_row_end", "table_column_start", "table_column_end", "table_cell_fragments"));
                        }
                        if (metadata.get("orig_elements") instanceof final String payload) {
                            metadata.put("orig_elements",
                                    mapper.readValue(json.write(json.decodeOriginals(payload)), List.class));
                        }
                        element.put("metadata", metadata);
                        return element;
                                                   })
                                                   .toList();
                    if ("strict-html-budget".equals(test.get("comparison"))) {
                        final int limit = options.maxCharacters();
                        assertTrue(expectedChunks.stream().anyMatch(item -> {
                            final var metadata = (Map<?, ?>) ((Map<?, ?>) item).get("metadata");
                            return UnicodeText.length((String) metadata.get("text_as_html")) > limit;
                        }),
                                "Reference records upstream HTML-budget overflow");
                        assertTrue(actual.stream().allMatch(e
                                -> UnicodeText.length(e.text()) <= limit
                                           && UnicodeText.length((String) e.metadata().get("text_as_html")) <= limit));
                        final String expectedText = expectedChunks.stream().map(item -> (String) ((Map<?, ?>) item).get("text"))
                                .collect(java.util.stream.Collectors.joining()).replaceAll("\\s", "");
                        final String actualText = actual.stream()
                                                          .map(io.docweave.core.Element::text)
                                                          .collect(java.util.stream.Collectors.joining())
                                                          .replaceAll("\\s", "");
                        assertEquals(
                                expectedText, actualText, "Strict budget changes segmentation without losing content");
                        assertTrue(actual.size() > expectedChunks.size());
                        return;
                    }
                    if (test.containsKey("java_expected")) {
                        assertNotEquals(mapper.readTree(mapper.writeValueAsString(test.get("expected"))),
                                mapper.readTree(mapper.writeValueAsString(test.get("java_expected"))),
                                "Documented difference must retain distinct reference and Java policy outputs");
                        assertEquals(mapper.readTree(mapper.writeValueAsString(test.get("java_expected"))),
                                mapper.readTree(mapper.writeValueAsString(normalized)));
                        assertTrue(
                                actual.stream().allMatch(e -> UnicodeText.length(e.text()) <= options.maxCharacters()));
                        return;
                    }
                    assertEquals(mapper.readTree(mapper.writeValueAsString(test.get("expected"))),
                            mapper.readTree(mapper.writeValueAsString(normalized)));
                });
                    })
                    .toList()
                    .stream();
        }
    }

    private ChunkOptions options(final Map<?, ?> source) {
        final var builder = ChunkOptions.builder();
        source.forEach((key, value) -> {
            switch ((String) key) {
            case "max_characters" -> builder.maxCharacters(((Number) value).intValue());
            case "new_after_n_chars" -> builder.newAfterCharacters(((Number) value).intValue());
            case "overlap" -> builder.overlap(((Number) value).intValue());
            case "overlap_all" -> builder.overlapAll((Boolean) value);
            case "multipage_sections" -> builder.multipageSections((Boolean) value);
            case "combine_text_under_n_chars" -> builder.combineTextUnderCharacters(((Number) value).intValue());
            case "include_orig_elements" -> builder.includeOriginalElements((Boolean) value);
            case "repeat_table_headers" -> builder.repeatTableHeaders((Boolean) value);
            case "skip_table_chunking" -> builder.skipTableChunking((Boolean) value);
            case "isolate_table" -> builder.isolateTable((Boolean) value);
            case "text_splitting_separators" ->
                builder.textSplittingSeparators(((List<?>) value).stream().map(String.class ::cast).toList());
            default -> throw new IllegalArgumentException("Unsupported golden option: " + key);
            }
        });
        return builder.build();
    }
}
