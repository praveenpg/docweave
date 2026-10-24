package io.docweave.chunking;

import io.docweave.DocWeave;
import io.docweave.batch.BatchProcessor;
import io.docweave.core.Element;
import io.docweave.core.ElementType;
import io.docweave.json.ElementJson;
import io.docweave.partition.DocumentSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class TableIsolationTest {
    private final Chunker chunker = Chunker.create();

    private Element text(final String value) {
        return new Element("text-" + value, ElementType.NarrativeText, value, Map.of());
    }

    private Element table(final String value) {
        return new Element("table-" + value, ElementType.Table, value,
                Map.of("text_as_html", "<table><tr><td>" + value + "</td></tr></table>"));
    }

    private List<String> texts(final List<Element> chunks) {
        return chunks.stream().map(Element::text).toList();
    }

    private ChunkOptions mixed() {
        return ChunkOptions.builder().isolateTable(false).build();
    }

    @Test
    void defaultIsolationIsPreservedAndOptOutCombinesNeighboringText() {
        final var input = List.of(text("Before."), table("Cell"), text("After."));
        final var isolated = chunker.basic(input, ChunkOptions.defaults());
        assertEquals(List.of(ElementType.CompositeElement, ElementType.Table, ElementType.CompositeElement),
                isolated.stream().map(Element::type).toList());
        final var combined = chunker.basic(input, mixed());
        assertEquals(List.of("Before.\n\nCell\n\nAfter."), texts(combined));
        assertEquals(ElementType.CompositeElement, combined.getFirst().type());
        assertFalse(combined.getFirst().metadata().containsKey("text_as_html"));
        assertEquals(input,
                ElementJson.create().decodeOriginals((String) combined.getFirst().metadata().get("orig_elements")));
    }

    @Test
    void multipleTablesAndTableChunksCanShareAComposite() {
        final var part =
                new Element("part", ElementType.TableChunk, "Second", Map.of("table_id", "previous", "chunk_index", 1));
        final var result = chunker.basic(List.of(table("First"), part), mixed());
        assertEquals(List.of("First\n\nSecond"), texts(result));
        assertEquals(ElementType.CompositeElement, result.getFirst().type());
        for (final String key : List.of("table_id", "chunk_index", "text_as_html")) {
            assertFalse(result.getFirst().metadata().containsKey(key));
        }
    }

    @Test
    void titleCombiningHonorsIsolationAndStrictSectionBoundaries() {
        final var heading = new Element("heading", ElementType.Title, "Heading", Map.of());
        final var input = List.of(table("Cell"), heading, text("Body."));
        assertEquals(List.of("Cell\n\nHeading\n\nBody."), texts(chunker.byTitle(input, mixed())));
        final var strict = ChunkOptions.builder().isolateTable(false).combineTextUnderCharacters(0).build();
        final var result = chunker.byTitle(input, strict);
        assertEquals(List.of("Cell", "Heading\n\nBody."), texts(result));
        assertEquals(ElementType.Table, result.getFirst().type());
    }

    @Test
    void pageBoundariesRemainSignificantWithCombiningDisabled() {
        final var first = new Element("first", ElementType.Table, "Cell", Map.of("page_number", 1));
        final var second = new Element("second", ElementType.NarrativeText, "Body.", Map.of("page_number", 2));
        final var options = ChunkOptions.builder()
                                    .isolateTable(false)
                                    .multipageSections(false)
                                    .combineTextUnderCharacters(0)
                                    .build();
        assertEquals(List.of("Cell", "Body."), texts(chunker.byTitle(List.of(first, second), options)));
    }

    @Test
    void sizingLimitsStillApplyToMixedGroupsAndSingleTablesStayStructural() {
        final var options = ChunkOptions.builder().maxCharacters(8).isolateTable(false).build();
        final var result = chunker.basic(
                List.of(text("abc"), new Element("table", ElementType.Table, "def", Map.of()), text("ghij")), options);
        assertEquals(List.of("abc\n\ndef", "ghij"), texts(result));
        assertTrue(result.stream().allMatch(element -> element.text().codePointCount(0, element.text().length()) <= 8));
        final var oversized = table("abcdef".repeat(20));
        final var alone =
                chunker.basic(List.of(oversized), ChunkOptions.builder().maxCharacters(70).isolateTable(false).build());
        assertTrue(alone.size() > 1);
        assertTrue(alone.stream().allMatch(element
                -> element.type() == ElementType.TableChunk
                           && ((String) element.metadata().get("text_as_html")).length() <= 70));
    }

    @Test
    void mixedMetadataRetainsGeneralFieldsButKeepsTableGeometryOnlyInProvenance() {
        final var source = new Element("source", ElementType.Table, "Cell",
                Map.of("table_as_cells", List.of(Map.of("text", "Cell")), "table_extraction_method", "native",
                        "table_row_count", 1, "text_as_html", "<table><tr><td>Cell</td></tr></table>", "coordinates",
                        Map.of("system", "PointSpace"), "filename", "report.docx", "languages", List.of("eng")));
        final var result = chunker.basic(List.of(source, text("After.")), mixed()).getFirst();
        assertEquals("report.docx", result.metadata().get("filename"));
        assertEquals(List.of("eng"), result.metadata().get("languages"));
        for (final String key : List.of(
                     "table_as_cells", "table_extraction_method", "table_row_count", "text_as_html", "coordinates")) {
            assertFalse(result.metadata().containsKey(key));
        }
        assertEquals(source,
                ElementJson.create().decodeOriginals((String) result.metadata().get("orig_elements")).getFirst());
        final var without =
                chunker.basic(List.of(source, text("After.")),
                               ChunkOptions.builder().isolateTable(false).includeOriginalElements(false).build())
                        .getFirst();
        assertFalse(without.metadata().containsKey("orig_elements"));
    }

    @Test
    void mixedTableTextUsesStoredTextRatherThanReplacingProseWithCanonicalHtmlText() {
        final var source = new Element("source", ElementType.Table, "Stored\ttext",
                Map.of("text_as_html", "<table><tr><td>Different</td></tr></table>"));
        assertEquals(
                List.of("Before.\n\nStored text"), texts(chunker.basic(List.of(text("Before."), source), mixed())));
        assertEquals("Different", chunker.basic(List.of(source), mixed()).getFirst().text());
    }

    @Test
    void overlapIsClearedWhenATableJoinsAnAlreadyPrefixedNarrativeGroup() {
        final var options = ChunkOptions.builder()
                                    .maxCharacters(15)
                                    .newAfterCharacters(8)
                                    .overlap(2)
                                    .overlapAll(true)
                                    .isolateTable(false)
                                    .build();
        final var input = List.of(text("abcdefghi"), text("a"), new Element("table", ElementType.Table, "t", Map.of()),
                text("longertext"));
        assertEquals(List.of("abcdefghi", "a\n\nt", "longertext"), texts(chunker.basic(input, options)));
    }

    @Test
    void tableBearingGroupsDoNotExportOverlapAndNormalNarrativeStillDoes() {
        final var options =
                ChunkOptions.builder().maxCharacters(12).overlap(2).overlapAll(true).isolateTable(false).build();
        final var input = List.of(text("abcdef"), text("ghijkl"),
                new Element("table", ElementType.Table, "tab", Map.of()), text("xyz"), text("longertext"));
        assertEquals(
                List.of("abcdef", "ef\n\nghijkl", "tab\n\nxyz", "longertext"), texts(chunker.basic(input, options)));
    }

    @Test
    void skipConflictIsRejectedAndOldConstructorsKeepIsolationEnabled() {
        assertThrows(IllegalArgumentException.class,
                () -> ChunkOptions.builder().isolateTable(false).skipTableChunking(true).build());
        final var d = ChunkOptions.defaults();
        assertTrue(d.isolateTable());
        assertEquals(d, new ChunkOptions(500, 500, 0, false, true, 500, true, true, false));
        assertEquals(d, new ChunkOptions(500, 500, 0, false, true, 500, true, true, false, List.of("\n", " ")));
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkOptions(500, 500, 0, false, true, 500, true, true, true, List.of("\n", " "), false));
        final var builder = ChunkOptions.builder();
        final var prior = builder.build();
        builder.isolateTable(false);
        assertTrue(prior.isolateTable());
    }

    @Test
    void nativeHtmlPipelineAndConcurrentReuseRetainOriginals() throws Exception {
        final var input =
                DocWeave.create()
                        .partition(DocumentSource.text(
                                "<p>Before.</p><table><tr><td>Cell</td></tr></table><p>After.</p>", "report.html"))
                        .elements();
        final var options = mixed();
        final var results = BatchProcessor.create().mapOrdered(
                IntStream.range(0, 20).boxed().toList(), 4, index -> chunker.basic(input, options));
        final var json = ElementJson.create();
        for (final var result : results) {
            assertEquals(List.of("Before.\n\nCell\n\nAfter."), texts(result));
            assertEquals(input, json.decodeOriginals((String) result.getFirst().metadata().get("orig_elements")));
            assertEquals(result, json.read(json.write(result)));
        }
    }

    @Test
    void combiningClearsOverlapBothIntoAndOutOfNewMixedGroups() {
        final var options = ChunkOptions.builder()
                                    .maxCharacters(30)
                                    .newAfterCharacters(0)
                                    .combineTextUnderCharacters(2)
                                    .overlap(2)
                                    .overlapAll(true)
                                    .isolateTable(false)
                                    .build();
        final var input = List.of(new Element("table", ElementType.Table, "t", Map.of()), text("abcdef"), text("x"));
        assertEquals(List.of("t\n\nabcdef", "x"), texts(chunker.byTitle(input, options)));
        final var prefixed = List.of(text("longer"), text("a"), new Element("table", ElementType.Table, "t", Map.of()));
        final var larger = ChunkOptions.builder()
                                   .maxCharacters(30)
                                   .newAfterCharacters(0)
                                   .combineTextUnderCharacters(6)
                                   .overlap(2)
                                   .overlapAll(true)
                                   .isolateTable(false)
                                   .build();
        assertEquals(List.of("longer", "a\n\nt"), texts(chunker.byTitle(prefixed, larger)));
    }
}
