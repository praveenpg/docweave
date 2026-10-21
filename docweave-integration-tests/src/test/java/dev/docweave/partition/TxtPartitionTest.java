package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.batch.BatchProcessor;
import dev.docweave.chunking.ChunkOptions;
import dev.docweave.chunking.Chunker;
import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
import dev.docweave.json.ElementJson;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class TxtPartitionTest {
    private final DocWeave processor = DocWeave.create();

    private PartitionOptions options(final PartitionOptions.ParagraphMode mode, final int min, final int max) {
        return PartitionOptions.builder().paragraphMode(mode).txtOptions(new TxtOptions(min, max)).build();
    }

    private PartitionResult parse(final String source, final PartitionOptions options) throws Exception {
        return processor.partition(DocumentSource.text(source, "report.txt"), options);
    }

    private List<String> texts(final PartitionResult result) {
        return result.elements().stream().map(Element::text).toList();
    }

    @Test
    void automaticGroupingSeparatesHeadingsAndSentencesButJoinsSoftWraps() throws Exception {
        final var result = parse("Report\nThis sentence wraps\nonto a second line.\nAnother sentence ends.\nNext Short "
                                         + "Heading\nMore prose follows.",
                options(PartitionOptions.ParagraphMode.AUTO, 0, 1000));
        assertEquals(List.of("Report", "This sentence wraps onto a second line.", "Another sentence ends.",
                             "Next Short Heading", "More prose follows."),
                texts(result));
        assertEquals(List.of(ElementType.Title, ElementType.NarrativeText, ElementType.NarrativeText, ElementType.Title,
                             ElementType.NarrativeText),
                result.elements().stream().map(Element::type).toList());
    }

    @Test
    void automaticListsKeepIndentedContinuationAndDoNotAbsorbFollowingProse() throws Exception {
        final var result =
                parse("TASKS\n• First item\n  Continues here.\n• Second item\nFollowing prose.\n\nLast paragraph.",
                        options(PartitionOptions.ParagraphMode.AUTO, 100, 200));
        assertEquals(List.of("TASKS", "First item Continues here.", "Second item", "Following prose. Last paragraph."),
                texts(result));
        assertEquals(ElementType.ListItem, result.elements().get(1).type());
        assertEquals(ElementType.ListItem, result.elements().get(2).type());
    }

    @Test
    void automaticGroupingHandlesUnicodeBreaksAndSentenceClosers() throws Exception {
        final var result = parse(
                "First sentence!”\u0085Second sentence.\u2028lowercase continuation\u2029\u2029日本語。\n次の文。",
                options(PartitionOptions.ParagraphMode.AUTO, 0, 1000));
        assertEquals(List.of("First sentence!”", "Second sentence. lowercase continuation", "日本語。", "次の文。"),
                texts(result));
    }

    @Test
    void defaultAndLineModesRetainTheirHistoricalBoundaries() throws Exception {
        final String source = "Report\nA sentence.\nAnother sentence.\n\nFinal.";
        assertEquals(List.of("Report A sentence. Another sentence.", "Final."),
                texts(parse(source, PartitionOptions.defaults())));
        assertEquals(List.of("Report", "A sentence.", "Another sentence.", "Final."),
                texts(parse(source, options(PartitionOptions.ParagraphMode.LINE_BREAKS, 0, 1000))));
    }

    @Test
    void maximumPrefersWordBoundariesAndKeepsOriginalCategoryAcrossFragments() throws Exception {
        final var result = parse("This sentence is longer.", options(PartitionOptions.ParagraphMode.BLANK_LINES, 0, 9));
        assertEquals(List.of("This", "sentence", "is", "longer."), texts(result));
        assertTrue(result.elements().stream().allMatch(element -> element.type() == ElementType.NarrativeText));
        final var list = parse("• abcdefghijk", options(PartitionOptions.ParagraphMode.BLANK_LINES, 0, 4));
        assertEquals(List.of("abcd", "efgh", "ijk"), texts(list));
        assertTrue(list.elements().stream().allMatch(element -> element.type() == ElementType.ListItem));
    }

    @Test
    void maximumSplitsUnbrokenUnicodeAtCodePointBoundaries() throws Exception {
        final var result = parse("🧵🧵🧵ab", options(PartitionOptions.ParagraphMode.BLANK_LINES, 0, 2));
        assertEquals(List.of("🧵🧵", "🧵a", "b"), texts(result));
        assertEquals(
                List.of("🧵", "a"), texts(parse("🧵 a", options(PartitionOptions.ParagraphMode.BLANK_LINES, 0, 1))));
    }

    @Test
    void minimumMergesWholeProseParagraphsWithoutDroppingShortRemainders() throws Exception {
        final var result =
                parse("One.\n\nTwo.\n\nThree.\n\nLast.", options(PartitionOptions.ParagraphMode.BLANK_LINES, 9, 12));
        assertEquals(List.of("One. Two.", "Three. Last."), texts(result));
        final var noRoom =
                parse("One.\n\nToo long to fit.", options(PartitionOptions.ParagraphMode.BLANK_LINES, 8, 16));
        assertEquals(List.of("One.", "Too long to fit."), texts(noRoom));
    }

    @Test
    void titlesAndListsRemainMinimumMergeBarriersEvenWithoutClassification() throws Exception {
        final String source = "One.\n\nHeading\n\nTwo.\n\n• Item\n\nThree.";
        final var classified = parse(source, options(PartitionOptions.ParagraphMode.BLANK_LINES, 50, 100));
        assertEquals(List.of("One.", "Heading", "Two.", "Item", "Three."), texts(classified));
        final var unclassified = parse(
                source, PartitionOptions.builder().classifyText(false).txtOptions(new TxtOptions(50, 100)).build());
        assertEquals(List.of("One.", "Heading", "Two.", "• Item", "Three."), texts(unclassified));
        assertTrue(
                unclassified.elements().stream().allMatch(element -> element.type() == ElementType.UncategorizedText));
    }

    @Test
    void automaticGroupingWorksWithClassificationDisabledAndRetainsMarkers() throws Exception {
        final var result = parse(
                "Report\nBody sentence.\n• First\n• Second", PartitionOptions.builder()
                                                                     .paragraphMode(PartitionOptions.ParagraphMode.AUTO)
                                                                     .classifyText(false)
                                                                     .build());
        assertEquals(List.of("Report", "Body sentence.", "• First", "• Second"), texts(result));
        assertTrue(result.elements().stream().allMatch(element -> element.type() == ElementType.UncategorizedText));
    }

    @Test
    void sizingPreservesAllNonWhitespaceAcrossSeededUnicodeInputs() throws Exception {
        final var random = new Random(7641);
        final String[] alphabet = {"🧵", "a", "日", "é", " ", " ", "x"};
        for (int trial = 0; trial < 100; trial++) {
            final var input = new StringBuilder();
            for (int i = 0; i < 100; i++) {
                input.append(alphabet[random.nextInt(alphabet.length)]);
            }
            final int max = 1 + random.nextInt(20);
            final var result = parse(input.toString(),
                    PartitionOptions.builder().classifyText(false).txtOptions(new TxtOptions(0, max)).build());
            assertEquals(input.toString().replace(" ", ""), String.join("", texts(result)).replace(" ", ""));
            for (final var element : result.elements()) {
                assertFalse(element.text().isBlank());
                assertTrue(element.text().codePointCount(0, element.text().length()) <= max);
                assertFalse(Character.isLowSurrogate(element.text().charAt(0)));
                assertFalse(Character.isHighSurrogate(element.text().charAt(element.text().length() - 1)));
            }
        }
    }

    @Test
    void sizeOptionsValidateAndOldConstructorKeepsDefaults() {
        assertThrows(IllegalArgumentException.class, () -> new TxtOptions(-1, 10));
        assertThrows(IllegalArgumentException.class, () -> new TxtOptions(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new TxtOptions(11, 10));
        assertThrows(NullPointerException.class, () -> PartitionOptions.builder().txtOptions(null).build());
        final var d = PartitionOptions.defaults();
        final var old = new PartitionOptions(d.format(), d.charset(), d.maxInputBytes(), d.maxElements(),
                d.maxHtmlDepth(), d.skipHeadersAndFooters(), d.paragraphMode(), d.classifyText(), d.baseUri(),
                d.markdownOptions(), d.delimitedOptions(), d.xmlOptions(), d.docxOptions());
        assertEquals(d, old);
        final var builder = PartitionOptions.builder();
        final var first = builder.txtOptions(new TxtOptions(1, 10)).build();
        builder.txtOptions(new TxtOptions(2, 20));
        assertEquals(new TxtOptions(1, 10), first.txtOptions());
    }

    @Test
    void splitOutputUsesCentralLimitsIdsAndJsonChunkProvenance() throws Exception {
        final var source = DocumentSource.text("abcdefghijkl", "report.txt");
        final var options = options(PartitionOptions.ParagraphMode.BLANK_LINES, 0, 3);
        final var result = processor.partition(source, options);
        assertEquals(result.elements(), processor.partition(source, options).elements());
        assertEquals(4, result.elements().stream().map(Element::elementId).distinct().count());
        final var json = ElementJson.create();
        assertEquals(result.elements(), json.read(json.write(result.elements())));
        final var chunk = Chunker.create().basic(result.elements(), ChunkOptions.defaults()).getFirst();
        assertEquals(result.elements(), json.decodeOriginals((String) chunk.metadata().get("orig_elements")));
        final var limited = PartitionOptions.builder().maxElements(2).txtOptions(new TxtOptions(0, 3)).build();
        assertEquals(PartitionException.Code.ELEMENT_LIMIT,
                assertThrows(PartitionException.class, () -> processor.partition(source, limited)).code());
    }

    @Test
    void concurrentGroupingAndSizingRemainDocumentConfined() throws Exception {
        final var options = options(PartitionOptions.ParagraphMode.AUTO, 20, 40);
        final var results = BatchProcessor.create().mapOrdered(IntStream.range(0, 25).boxed().toList(), 5,
                index -> parse("Report\nFirst sentence " + index + ".\nNext sentence.", options));
        for (int i = 0; i < results.size(); i++) {
            assertEquals(List.of("Report", "First sentence " + i + ". Next sentence."), texts(results.get(i)));
        }
    }

    @Test
    void shortFinalTextIsRetainedAndSizingIsTxtOnly() throws Exception {
        assertEquals(List.of("One. Two.", "End."),
                texts(parse("One.\nTwo.\nEnd.", options(PartitionOptions.ParagraphMode.LINE_BREAKS, 8, 20))));
        assertTrue(parse(" \n\n", options(PartitionOptions.ParagraphMode.AUTO, 5, 10)).elements().isEmpty());
        final var html = processor.partition(DocumentSource.text("<p>This remains whole.</p>", "input.html"),
                PartitionOptions.builder().txtOptions(new TxtOptions(0, 2)).build());
        assertEquals(List.of("This remains whole."), texts(html));
    }
}
