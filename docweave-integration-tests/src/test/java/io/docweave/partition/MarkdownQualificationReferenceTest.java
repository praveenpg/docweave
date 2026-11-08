package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.chunking.ChunkOptions;
import io.docweave.chunking.Chunker;
import io.docweave.core.Element;
import io.docweave.core.ElementType;
import io.docweave.json.ElementJson;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Owned Markdown corpus compared with the pinned Unstructured 0.27.37 release (issues #57-#60). Each test records
 * shared behavior and names every observed difference as a dialect/policy difference rather than hiding it. The
 * historical 347fbbfd markup references remain separate in {@link MarkupReferenceTest}.
 */
class MarkdownQualificationReferenceTest {
    private static final Path CORPUS = Path.of("benchmarks/fixtures/markdown");
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ElementJson json = ElementJson.create();
    private final DocWeave processor = DocWeave.create();

    private Map<?, ?> saved() throws Exception {
        try (final var stream = getClass().getResourceAsStream("/markdown-qualification-reference.json")) {
            return mapper.readValue(stream, Map.class);
        }
    }

    private static String sha256(final byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private List<Element> reference(final String filename, final String configuration) throws Exception {
        final var selected = ((List<?>) saved().get("cases"))
                                     .stream()
                                     .map(value -> (Map<?, ?>) value)
                                     .filter(value
                                             -> filename.equals(value.get("input"))
                                                     && configuration.equals(value.get("configuration")))
                                     .findFirst()
                                     .orElseThrow();
        assertEquals(selected.get("input_sha256"), sha256(Files.readAllBytes(CORPUS.resolve(filename))));
        return json.read(mapper.writeValueAsString(selected.get("upstream_output")));
    }

    private List<Element> reference(final String filename) throws Exception {
        return reference(filename, "default");
    }

    private PartitionResult candidate(final String filename, final MarkdownOptions.RawHtml mode,
            final Set<MarkdownOptions.Extension> extensions) throws Exception {
        return processor.partition(DocumentSource.of(CORPUS.resolve(filename)),
                PartitionOptions.builder()
                        .markdownOptions(new MarkdownOptions(true, 128, mode, TableLimits.defaults(), extensions))
                        .build());
    }

    private List<Element> ast(final String filename) throws Exception {
        return candidate(filename, MarkdownOptions.RawHtml.PRESERVE_TEXT, Set.of()).elements();
    }

    private static List<String> texts(final List<Element> elements) {
        return elements.stream().map(Element::text).toList();
    }

    private static List<ElementType> types(final List<Element> elements) {
        return elements.stream().map(Element::type).toList();
    }

    private static Object meta(final Element element, final String key) {
        return element.metadata().get(key);
    }

    /** Link labels/URLs and emphasis contents/tags agree; Java additionally reports code-point offsets. */
    private static void sameInlineMetadata(final Element reference, final Element actual) {
        for (final String key :
                List.of("link_texts", "link_urls", "emphasized_text_contents", "emphasized_text_tags")) {
            assertEquals(meta(reference, key), meta(actual, key), key + " of " + actual.text());
        }
        assertFalse(reference.metadata().containsKey("emphasized_text_start_indexes"));
    }

    @Test
    void referenceIsPinnedToTheReleaseAndCorpusSeparatelyFromHistoricalReferences() throws Exception {
        final var provenance = (Map<?, ?>) saved().get("reference");
        assertEquals("0.27.37", provenance.get("upstream_release"));
        assertEquals("18d3ee366925bbe0cd363eb8308d375cbe8e2a7e2bfbd98786e9d652be3f2996",
                provenance.get("installed_record_sha256"));
        assertTrue(provenance.get("python").toString().startsWith("3.13."));
        assertEquals("3.11.3", ((Map<?, ?>) provenance.get("dependencies")).get("Markdown"));
        assertEquals(Map.of("extensions", List.of("tables", "fenced_code")),
                ((Map<?, ?>) provenance.get("configurations")).get("default"));
        final var manifest = mapper.readValue(Files.readString(CORPUS.resolve("manifest.json")), Map.class);
        final var cases = (List<?>) saved().get("cases");
        for (final var entry : (List<?>) manifest.get("documents")) {
            final var document = (Map<?, ?>) entry;
            final String filename = (String) document.get("filename");
            assertEquals(document.get("sha256"), sha256(Files.readAllBytes(CORPUS.resolve(filename))));
            final var options = cases.stream()
                                        .map(value -> (Map<?, ?>) value)
                                        .filter(value -> filename.equals(value.get("input")))
                                        .map(value -> (Map<?, ?>) value.get("upstream_options"))
                                        .toList();
            assertFalse(options.isEmpty(), filename);
            options.forEach(value -> assertEquals("v1", value.get("html_parser_version")));
        }
        try (final var stream = getClass().getResourceAsStream("/markup-native-reference.json")) {
            assertEquals("347fbbfd3e84e228017f125e5c5dbdc326ef7de2",
                    ((Map<?, ?>) mapper.readValue(stream, Map.class).get("reference")).get("upstream_commit"));
        }
    }

    @Test
    void changelogAgreesOnTextTypesHierarchyLinksAndEmphasis() throws Exception {
        final var reference = reference("changelog.md");
        final var actual = ast("changelog.md");
        assertEquals(texts(reference), texts(actual));
        assertEquals(types(reference), types(actual));
        for (int i = 0; i < actual.size(); i++) {
            sameInlineMetadata(reference.get(i), actual.get(i));
            if (actual.get(i).type() == ElementType.ListItem) {
                // Java counts list depth from zero; this upstream run reports top-level items at one.
                assertEquals(
                        (Integer) meta(reference.get(i), "category_depth") - 1, meta(actual.get(i), "category_depth"));
            }
        }
        // Heading hierarchy agrees: compare parent positions, since element IDs differ.
        final var refIds = reference.stream().map(Element::elementId).toList();
        final var ids = actual.stream().map(Element::elementId).toList();
        for (int i = 0; i < actual.size(); i++) {
            assertEquals(
                    refIds.indexOf(meta(reference.get(i), "parent_id")), ids.indexOf(meta(actual.get(i), "parent_id")));
        }
    }

    @Test
    void readmeDiffersOnlyInBadgeGroupingAndCodeInsideListItems() throws Exception {
        final var reference = reference("readme.md");
        final var actual = ast("readme.md");
        final var expected = new ArrayList<>(texts(reference));
        // Python-Markdown emits each linked badge image separately; CommonMark keeps the badge paragraph together.
        assertEquals(List.of(ElementType.Image, ElementType.Image), types(reference.subList(1, 3)));
        expected.subList(1, 3).clear();
        expected.add(1, "Build Licence");
        // Python-Markdown's fenced_code does not apply inside list items; CommonMark keeps the code block literal.
        assertEquals("sh tar -xzf lantern-notes-1.4.0.tar.gz -C ~/apps", expected.get(7));
        expected.set(7, "tar -xzf lantern-notes-1.4.0.tar.gz -C ~/apps\n");
        expected.set(11, expected.get(11) + "\n");
        // Tables share ordered cell text; Java separates cells by tabs and rows by newlines.
        final var table = actual.get(18);
        assertEquals(expected.get(18), table.text().replaceAll("\\s+", " "));
        expected.set(18, table.text());
        assertEquals(expected, texts(actual));
        final var badges = actual.get(1);
        assertEquals(ElementType.NarrativeText, badges.type());
        assertEquals(List.of(meta(reference.get(1), "image_url"), meta(reference.get(2), "image_url")),
                meta(badges, "image_urls"));
        assertEquals(List.of("Build", "Licence"), meta(badges, "link_texts"));
        final var code = actual.get(7);
        assertEquals(ElementType.CodeSnippet, code.type());
        assertEquals("sh", meta(code, "code_language"));
        assertEquals(actual.get(6).elementId(), meta(code, "parent_id"), "code belongs to its list item");
        sameInlineMetadata(reference.get(3), actual.get(2));
        sameInlineMetadata(reference.get(4), actual.get(3));
        sameInlineMetadata(reference.get(21), actual.get(20));
        assertEquals(List.of("left", "left", "right"), meta(actual.get(18), "table_column_alignments"));
    }

    @Test
    void guideDiffersOnlyInCommonMarkHardBreaksCodeNewlinesAndLooseListContinuations() throws Exception {
        final var reference = reference("guide.md");
        final var actual = ast("guide.md");
        assertEquals(reference.size(), actual.size());
        for (int i = 0; i < actual.size(); i++) {
            final String upstream = reference.get(i).text();
            final String java = actual.get(i).text();
            if (i == 16) {
                assertEquals(upstream + "\n", java, "CommonMark retains the code block's final newline");
            } else if (i == 20) {
                // A trailing backslash is a CommonMark hard break; Python-Markdown keeps it literally.
                assertTrue(upstream.contains("hard break\\ line two"));
                assertEquals(upstream.replace("break\\ line", "break line"), java);
            } else {
                assertEquals(upstream, java);
            }
            sameInlineMetadata(reference.get(i), actual.get(i));
        }
        assertEquals(ElementType.NarrativeText, reference.get(7).type());
        assertEquals(ElementType.ListItem, actual.get(7).type());
        assertEquals(actual.get(6).elementId(), meta(actual.get(7), "parent_id"),
                "a loose-list continuation paragraph belongs to its item");
        assertEquals(actual.get(10).elementId(), meta(actual.get(11), "parent_id"));
        assertEquals("text", meta(actual.get(16), "code_language"));
        assertEquals(List.of(8, 25, 83), meta(actual.get(21), "link_start_indexes"));
    }

    @Test
    void edgeCasesRecordEmphasisSegmentationAndGfmTableRules() throws Exception {
        final var reference = reference("edge-cases.md");
        final var actual = ast("edge-cases.md");
        assertEquals(
                List.of("Edge cases", "Logo only", "Nested bold italic and outer inner tail and under score.",
                        "Code keeps *literal* markers and *escaped* text; snake_case_name stays.",
                        "A bold link and see the docs.", "Reference image chart inline.", "Item after an empty item",
                        "Item with code:", "print(\"in list\")\n", "Lazy continuation line joins the item.",
                        "| Key | a|b | Note | | --- | --- | --- | | not | a | table |",
                        "Key\ta|b\tNote\nx\tc|d\t\ny\tz\tw", "Unicode: café, naïve, 𝔘𝔫𝔦𝔠𝔬𝔡𝔢, 🧵 🧵 thread end."),
                texts(actual));
        // Both empty headings are dropped; an image-only heading stays a Java Title with its image reference.
        assertEquals(ElementType.Image, reference.get(1).type());
        assertEquals(List.of("logo.png"), meta(actual.get(1), "image_urls"));
        sameInlineMetadata(reference.get(2), actual.get(2));
        sameInlineMetadata(reference.get(3), actual.get(3));
        sameInlineMetadata(reference.getLast(), actual.getLast());
        assertEquals(List.of(33), meta(actual.getLast(), "emphasized_text_start_indexes"));
        // Upstream annotates each text segment, splitting emphasis at the nested link; Java reports maximal runs.
        assertEquals(List.of("bold link", "see", "the docs"), meta(reference.get(4), "emphasized_text_contents"));
        assertEquals(List.of("bold link", "see the docs"), meta(actual.get(4), "emphasized_text_contents"));
        // GFM splits cells at a pipe inside a code span, so a mismatched header is a paragraph; Python-Markdown
        // treats it as a table. Both drop the surplus "extra" cell of a ragged row.
        assertEquals("Key a|b Note not a table", reference.get(11).text());
        assertEquals("Key a\\|b Note x c\\|d y z w", reference.get(12).text());
        assertFalse(actual.get(11).text().contains("extra"));
        assertEquals(List.of("x", "w"), meta(actual.get(11), "emphasized_text_contents"));
        // Python-Markdown misreads the fenced code and lazy continuation inside the list as one paragraph.
        assertEquals("python print(\"in list\") - Lazy continuation line joins the item.", reference.get(10).text());
        assertEquals(actual.get(7).elementId(), meta(actual.get(8), "parent_id"));
    }

    @Test
    void mixedInlineHtmlDocumentsEachModeAgainstUpstreamDomExtraction() throws Exception {
        final var reference = reference("mixed-inline.md");
        final var preserve = candidate("mixed-inline.md", MarkdownOptions.RawHtml.PRESERVE_TEXT, Set.of());
        final var skip = candidate("mixed-inline.md", MarkdownOptions.RawHtml.SKIP, Set.of());
        final var dom = candidate("mixed-inline.md", MarkdownOptions.RawHtml.EXTRACT_DOM, Set.of());
        assertEquals(
                List.of(1, 0, 0), List.of(preserve.warnings().size(), skip.warnings().size(), dom.warnings().size()));
        for (final var result : List.of(preserve, skip, dom)) {
            assertEquals(types(reference), types(result.elements()), "every mode keeps the same block structure");
        }
        assertEquals("Visit <a href=\"https://example.org/raw\">the raw anchor</a> or the Markdown link.",
                preserve.elements().get(3).text());
        assertEquals(List.of("raw"), meta(preserve.elements().get(3), "emphasized_text_contents"));
        // SKIP removes tags only: inline text, including explicitly hidden spans, remains; raw anchors lose links.
        final var skipped = texts(skip.elements());
        assertEquals(texts(reference).subList(0, 4), skipped.subList(0, 4));
        assertEquals("Secret hidden words stay out; styled hidden words too.", skipped.get(4));
        assertEquals(List.of("Markdown link"), meta(skip.elements().get(3), "link_texts"));
        // DOM mode matches upstream links and text except the hidden attribute; CSS visibility is not evaluated.
        final var domTexts = texts(dom.elements());
        assertEquals("Secret stay out; styled hidden words too.", domTexts.get(4));
        assertEquals("Secret hidden words stay out; styled hidden words too.", reference.get(4).text());
        for (final int i : List.of(0, 1, 2, 3, 5, 6, 7)) {
            assertEquals(reference.get(i).text(), domTexts.get(i));
        }
        assertEquals(meta(reference.get(3), "link_urls"), meta(dom.elements().get(3), "link_urls"));
        assertEquals(List.of(6, 28), meta(dom.elements().get(3), "link_start_indexes"));
        assertNull(meta(dom.elements().get(5), "emphasized_text_contents"), "DOM mode emits no emphasis metadata");
        assertEquals(List.of("raw emphasis"), meta(reference.get(5), "emphasized_text_contents"));
        assertEquals("Column\tValue\nraw bold\titalic", domTexts.get(8));
    }

    @Test
    void mixedBlockHtmlKeepsOrderAndRecordsContentTradeoffsPerMode() throws Exception {
        final var reference = reference("mixed-blocks.md");
        final var preserve = candidate("mixed-blocks.md", MarkdownOptions.RawHtml.PRESERVE_TEXT, Set.of());
        final var skip = candidate("mixed-blocks.md", MarkdownOptions.RawHtml.SKIP, Set.of());
        final var dom = candidate("mixed-blocks.md", MarkdownOptions.RawHtml.EXTRACT_DOM, Set.of());
        // PRESERVE_TEXT keeps each raw block literally, in source order, including script/style/comment markup.
        final var preserved = texts(preserve.elements());
        assertEquals(19, preserved.size());
        assertTrue(preserved.get(2).startsWith("<div class=\"note\">"));
        assertEquals("<script>window.tracking = true;</script>", preserved.get(12));
        assertEquals(List.of(PartitionResult.WarningCode.RAW_HTML_PRESERVED),
                preserve.warnings().stream().map(PartitionResult.Warning::code).toList());
        // SKIP drops whole raw blocks, so their text is lost by policy; Markdown blocks keep order.
        assertEquals(List.of("Block HTML between Markdown", "Intro paragraph before the block.", "Section after block",
                             "Markdown list item", "Second item", "Markdown inside details after a blank line.",
                             "Paragraph after the unclosed wrapper.", "Final paragraph."),
                texts(skip.elements()));
        // EXTRACT_DOM keeps every upstream text in the same order and also the details content Python-Markdown drops.
        final var domTexts = texts(dom.elements());
        final var upstream = new ArrayList<>(texts(reference));
        upstream.set(11, "Unclosed wrapper starts here.");
        upstream.add(12, "Paragraph after the unclosed wrapper.");
        upstream.set(13, "  preformatted   text\n");
        upstream.addAll(11, List.of("More detail", "Markdown inside details after a blank line."));
        upstream.set(10, "Outer\tData\nA\tinner 1 inner 2");
        assertEquals(upstream, domTexts);
        assertFalse(String.join(" ", domTexts).contains("tracking"));
        assertFalse(String.join(" ", domTexts).contains("comment"));
        assertEquals(
                List.of(PartitionResult.WarningCode.HTML_RECOVERED, PartitionResult.WarningCode.NESTED_TABLE_FLATTENED),
                dom.warnings().stream().map(PartitionResult.Warning::code).toList());
        final var section = dom.elements().get(5);
        assertEquals("Section after block", section.text());
        assertEquals(section.elementId(), meta(dom.elements().get(6), "parent_id"));
        assertEquals(dom.elements().get(8).elementId(), meta(dom.elements().getLast(), "parent_id"));
    }

    @Test
    void extensionSyntaxMatchesUpstreamDefaultsAndOptInChangesOnlySelectedSyntax() throws Exception {
        final var reference = reference("extensions.md");
        assertEquals(texts(reference), texts(ast("extensions.md")));
        final var all = EnumSet.allOf(MarkdownOptions.Extension.class);
        final var opted = candidate("extensions.md", MarkdownOptions.RawHtml.PRESERVE_TEXT, all).elements();
        assertEquals(List.of("Extension syntax", "Open task", "Completed task", "Plain item",
                             "Text with struck words and ~single~ tildes.",
                             "Bare links: https://example.org/bare, www.example.org/www and team@example.org.",
                             "Footnote reference[1] in prose.", "The footnote body.", "Term",
                             "Definition of the term."),
                texts(opted));
        assertEquals(List.of(false, true),
                List.of(meta(opted.get(1), "task_list_item_checked"), meta(opted.get(2), "task_list_item_checked")));
        assertEquals(List.of("struck words"), meta(opted.get(4), "strikethrough_texts"));
        assertEquals(List.of("https://example.org/bare", "http://www.example.org/www", "mailto:team@example.org"),
                meta(opted.get(5), "link_urls"));
        assertEquals(opted, json.read(json.write(opted)), "task, strikethrough and link metadata round-trip");
        final var emphasized = ast("edge-cases.md");
        assertEquals(emphasized, json.read(json.write(emphasized)), "emphasis metadata round-trips");
        assertEquals(List.of("note"), meta(opted.get(6), "footnote_reference_labels"));
        assertEquals(1, meta(opted.get(7), "footnote_number"));
        assertEquals("term", meta(opted.get(8), "definition_list_role"));
        assertEquals(List.of(opted.get(8).elementId()), meta(opted.get(9), "definition_term_ids"));
        // DOM mode renders the same syntax: marker-free text and autolinks, without AST-only metadata. Footnotes are
        // rendered as an ordered list after the content, with commonmark-java's numbered reference and back-link.
        final var dom = candidate("extensions.md", MarkdownOptions.RawHtml.EXTRACT_DOM, all).elements();
        assertEquals(List.of("Extension syntax", "Open task", "Completed task", "Plain item",
                             "Text with struck words and ~single~ tildes.",
                             "Bare links: https://example.org/bare, www.example.org/www and team@example.org.",
                             "Footnote reference1 in prose.", "Term", "Definition of the term.", "The footnote body. ↩"),
                texts(dom));
        assertEquals(meta(opted.get(5), "link_urls"), meta(dom.get(5), "link_urls"));
        assertNull(meta(dom.get(1), "task_list_item_checked"));
        assertNull(meta(dom.get(4), "strikethrough_texts"));
        assertTrue(dom.stream().noneMatch(element -> element.metadata().keySet().stream().anyMatch(
                                                  key -> key.startsWith("footnote_") || key.startsWith("definition_"))));
        // Python-Markdown (footnotes, def_list) moves the note to the end and renders the reference as a bare number.
        // DocWeave AST mode keeps the note where it is written and marks the reference as [n]; the texts agree
        // otherwise, and EXTRACT_DOM matches upstream's order.
        final var extended = reference("extensions.md", "python-markdown-extensions");
        assertEquals(
                List.of("Footnote reference1 in prose.", "Term", "Definition of the term.", "The footnote body. ↩"),
                texts(extended).subList(6, 10));
        // The upstream run does not enable task lists or strikethrough; the footnote and definition rows agree.
        assertEquals(texts(extended).subList(6, 10), texts(dom).subList(6, 10));
    }

    @Test
    void frontMatterIsASetextHeadingByDefaultAndDocumentMetadataWhenSelected() throws Exception {
        final var actual = ast("front-matter.md");
        assertEquals(List.of("title: Site Report author: Field Team", "Site Report", "Body text."), texts(actual));
        assertEquals(ElementType.Title, actual.getFirst().type());
        assertEquals(1, meta(actual.getFirst(), "category_depth"));
        assertEquals(texts(reference("front-matter.md")), texts(actual));
        final var meta = reference("front-matter.md", "python-markdown-extensions");
        assertEquals(List.of("Site Report", "Body text."), texts(meta));
        // With FRONT_MATTER both modes agree with Python-Markdown's meta extension on text, and keep the values.
        for (final var mode : List.of(MarkdownOptions.RawHtml.PRESERVE_TEXT, MarkdownOptions.RawHtml.EXTRACT_DOM)) {
            final var selected = candidate("front-matter.md", mode, EnumSet.of(MarkdownOptions.Extension.FRONT_MATTER));
            assertEquals(texts(meta), texts(selected.elements()), mode.name());
            assertTrue(selected.warnings().isEmpty(), mode.name());
            for (final var element : selected.elements()) {
                assertEquals(Map.of("title", "Site Report", "author", "Field Team"), meta(element, "front_matter"));
            }
            assertEquals(selected.elements(), json.read(json.write(selected.elements())), "front matter round-trips");
        }
    }

    @Test
    void chunkingConcatenatesEmphasisLabelsAndDropsElementLocalOffsets() throws Exception {
        final var elements = ast("changelog.md");
        final var chunks = Chunker.create().byTitle(elements, ChunkOptions.defaults());
        final var labels = new ArrayList<Object>();
        for (final var chunk : chunks) {
            assertFalse(chunk.metadata().containsKey("emphasized_text_start_indexes"));
            final var contents = (List<?>) meta(chunk, "emphasized_text_contents");
            if (contents != null) {
                labels.addAll(contents);
                assertEquals(contents.size(), ((List<?>) meta(chunk, "emphasized_text_tags")).size());
            }
        }
        assertEquals(List.of("Keep a Changelog", "Breaking:"), labels);
        final var table = ast("edge-cases.md").get(11);
        assertEquals(ElementType.Table, table.type());
        final var tableChunk = Chunker.create().basic(List.of(table), ChunkOptions.defaults()).getFirst();
        assertEquals(List.of("x", "w"), meta(tableChunk, "emphasized_text_contents"));
        assertFalse(tableChunk.metadata().containsKey("emphasized_text_start_indexes"));
        assertEquals(table, json.decodeOriginals((String) tableChunk.metadata().get("orig_elements")).getFirst());
    }
}
