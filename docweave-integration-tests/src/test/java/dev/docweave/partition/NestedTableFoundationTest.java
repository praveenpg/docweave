package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.chunking.ChunkOptions;
import dev.docweave.chunking.Chunker;
import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
import dev.docweave.json.ElementJson;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NestedTableFoundationTest {
    private Element table(final boolean html) {
        final String text = "😀 Child table content ".repeat(10);
        final var nested = List.of(Map.of("index", 0, "path", List.of(Map.of("row", 0, "column", 0, "table_index", 0)),
                "start_index", 0, "end_index", 2, "text", "😀", "text_as_html", "<table><tr><td>😀</td></tr></table>"));
        final var metadata = new java.util.LinkedHashMap<String, Object>();
        metadata.put("nested_tables_version", 1);
        metadata.put("nested_tables", nested);
        if (html) {
            metadata.put("text_as_html", "<table><tr><td>" + text + "</td></tr></table>");
        }
        return new Element("source-table", ElementType.Table, text, metadata);
    }

    @Test
    void unimplementedBuiltInAdaptersExplicitlyRejectPreservation() throws Exception {
        final var registry = PartitionerRegistry.builder().build();
        final var processor = DocWeave.create(registry);
        for (final var format : processor.supportedFormats()) {
            if (registry.find(format).orElseThrow().supportsNestedTablePreservation()) {
                continue;
            }
            final var source = switch (format) {
                case PDF -> DocumentSource.bytes(new byte[0], "input.pdf");
                case DOCX -> DocumentSource.of(Path.of("benchmarks/review/nested-tables/nested.docx"));
                case XML -> DocumentSource.text("<root>text</root>");
                case HTML -> DocumentSource.text("<p>text</p>");
                default -> DocumentSource.text("text");
            };
            final var options = PartitionOptions.builder()
                                        .format(format)
                                        .nestedTableOptions(NestedTableOptions.preserveMetadata())
                                        .build();
            assertEquals(PartitionException.Code.UNSUPPORTED_OPTION,
                    assertThrows(PartitionException.class, () -> processor.partition(source, options)).code(),
                    format.name());
            final var context = (PartitionContext) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {PartitionContext.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("options")) {
                            return options;
                        }
                        throw new AssertionError("Parser accessed content before rejecting unsupported policy");
                    });
            assertEquals(PartitionException.Code.UNSUPPORTED_OPTION,
                    assertThrows(PartitionException.class, () -> registry.find(format).orElseThrow().partition(context))
                            .code());
        }
    }

    @Test
    void rewrittenTablesDropNestedRangesButPreserveOriginalSnapshots() {
        final var codec = ElementJson.create();
        for (final boolean html : new boolean[] {false, true}) {
            final var source = table(html);
            assertEquals(List.of(source), codec.read(codec.write(List.of(source))));
            for (final int limit : new int[] {500, 80, 20}) {
                final var chunks =
                        Chunker.create().basic(List.of(source), ChunkOptions.builder().maxCharacters(limit).build());
                assertFalse(chunks.isEmpty());
                for (final var chunk : chunks) {
                    assertFalse(chunk.metadata().containsKey("nested_tables"));
                    assertFalse(chunk.metadata().containsKey("nested_tables_version"));
                    final var originals = codec.decodeOriginals((String) chunk.metadata().get("orig_elements"));
                    assertEquals(source, originals.getFirst());
                }
            }
        }
    }

    @Test
    void mixedCompositesAndDisabledProvenanceNeverLeakNestedOffsets() {
        final var source = table(true);
        final var prose = new Element("prose", ElementType.NarrativeText, "Before", Map.of());
        final var mixed = Chunker.create().basic(
                List.of(prose, source), ChunkOptions.builder().maxCharacters(1000).isolateTable(false).build());
        assertEquals(ElementType.CompositeElement, mixed.getFirst().type());
        for (final var chunk : mixed) {
            assertFalse(chunk.metadata().containsKey("nested_tables"));
        }
        final var noOriginals = Chunker.create().basic(
                List.of(source), ChunkOptions.builder().maxCharacters(80).includeOriginalElements(false).build());
        for (final var chunk : noOriginals) {
            assertFalse(chunk.metadata().containsKey("nested_tables"));
            assertFalse(chunk.metadata().containsKey("nested_tables_version"));
            assertFalse(chunk.metadata().containsKey("orig_elements"));
        }
    }

    @Test
    void exactTablePassThroughKeepsNestedMetadataWithoutCopying() {
        final var source = table(true);
        final var chunks = Chunker.create().basic(List.of(source),
                ChunkOptions.builder().maxCharacters(1).includeOriginalElements(false).skipTableChunking(true).build());
        assertEquals(1, chunks.size());
        assertSame(source, chunks.getFirst());
    }
}
