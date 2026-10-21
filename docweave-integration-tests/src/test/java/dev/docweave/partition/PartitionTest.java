package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.chunking.ChunkOptions;
import dev.docweave.chunking.Chunker;
import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
import dev.docweave.json.ElementJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PartitionTest {
    private final DocWeave processor = DocWeave.create();

    private PartitionResult html(final String content) throws Exception {
        return processor.partition(
                DocumentSource.text(content), PartitionOptions.builder().format(DocumentFormat.HTML).build());
    }

    private List<String> texts(final PartitionResult result) {
        return result.elements().stream().map(Element::text).toList();
    }

    private List<ElementType> types(final PartitionResult result) {
        return result.elements().stream().map(Element::type).toList();
    }

    @Test
    void txtGroupsWrappedParagraphsAndClassifiesSimpleContent() throws Exception {
        final var result = processor.partition(
                DocumentSource.text("Report\r\n\r\nThis sentence wraps\r\nonto a second line.\r\n\r\n• First item\r\n  "
                                            + "continues here\r\n• Second item\r\n\r\n1234",
                        "report.txt"));
        assertEquals(List.of("Report", "This sentence wraps onto a second line.", "First item continues here",
                             "Second item", "1234"),
                texts(result));
        assertEquals(List.of(ElementType.Title, ElementType.NarrativeText, ElementType.ListItem, ElementType.ListItem,
                             ElementType.UncategorizedText),
                types(result));
        assertEquals(DocumentFormat.TXT, result.format());
        assertEquals("UTF-8", result.encoding());
        assertFalse(result.elapsed().isNegative());
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void lineModeAndDisabledClassificationAreExplicit() throws Exception {
        final var options = PartitionOptions.builder()
                                    .paragraphMode(PartitionOptions.ParagraphMode.LINE_BREAKS)
                                    .classifyText(false)
                                    .build();
        final var result = processor.partition(DocumentSource.text("Heading\nBody text\n\nOther text"), options);
        assertEquals(List.of("Heading", "Body text", "Other text"), texts(result));
        assertTrue(result.elements().stream().allMatch(element -> element.type() == ElementType.UncategorizedText));
    }

    @Test
    void emptyDocumentsAndBareBulletsProduceNoElements() throws Exception {
        assertEquals(List.of(), processor.partition(DocumentSource.text("\n\u00a0\t\n")).elements());
        assertEquals(List.of(), processor.partition(DocumentSource.text("•\n\n-")).elements());
    }

    @Test
    void headingHierarchyUsesStableIdsAndNoFabricatedPages() throws Exception {
        final var source = DocumentSource.text(
                "<h1>Report</h1><p>Body.</p><h3>Details</h3><p>More.</p><h2>Next</h2><p>End.</p>", "report.html");
        final var result = processor.partition(source);
        assertEquals(result.elements(), processor.partition(source).elements());
        final var items = result.elements();
        assertEquals(items.getFirst().elementId(), items.get(1).metadata().get("parent_id"));
        assertEquals(items.getFirst().elementId(), items.get(2).metadata().get("parent_id"));
        assertEquals(items.get(2).elementId(), items.get(3).metadata().get("parent_id"));
        assertEquals(items.getFirst().elementId(), items.get(4).metadata().get("parent_id"));
        assertEquals(2, items.get(2).metadata().get("category_depth"));
        assertTrue(items.stream().allMatch(element -> element.elementId().matches("[a-f0-9]{32}")));
        assertTrue(items.stream().noneMatch(element -> element.metadata().containsKey("page_number")));
        assertEquals("text/html", items.getFirst().metadata().get("filetype"));
    }

    @Test
    void containersPreserveOrderWithoutDuplicatingNestedText() throws Exception {
        final var result = html(
                "<div>Before <em>inline</em><p>Paragraph <b>one</b>.</p>After<section><p>Nested.</p></section></div>");
        assertEquals(List.of("Before inline", "Paragraph one.", "After", "Nested."), texts(result));
    }

    @Test
    void listsKeepNestedParentsAndDepth() throws Exception {
        final var result = html("<h1>Heading</h1><ul><li><p>Parent item</p><ul><li>Nested "
                                + "item</li></ul>Tail</li><li>Sibling</li></ul>");
        assertEquals(List.of("Heading", "Parent item", "Nested item", "Tail", "Sibling"), texts(result));
        final var parent = result.elements().get(1);
        final var nested = result.elements().get(2);
        assertEquals(parent.elementId(), nested.metadata().get("parent_id"));
        assertEquals(1, nested.metadata().get("category_depth"));
        assertEquals(0, parent.metadata().get("category_depth"));
    }

    @Test
    void unicodeLinkOffsetsFollowNormalizedTextAndResolveRelativeUrls() throws Exception {
        final String content = "<p>🧵<a href='/first'> first</a> and <a href='/second'>second</a>.</p>";
        final var result = processor.partition(DocumentSource.text(content),
                PartitionOptions.builder().baseUri(URI.create("https://example.com/base/")).build());
        final var item = result.elements().getFirst();
        assertEquals("🧵 first and second.", item.text());
        assertEquals(List.of("first", "second"), item.metadata().get("link_texts"));
        assertEquals(
                List.of("https://example.com/first", "https://example.com/second"), item.metadata().get("link_urls"));
        assertEquals(List.of(2, 12), item.metadata().get("link_start_indexes"));
        final var links = (List<?>) item.metadata().get("links");
        assertEquals(2, ((Map<?, ?>) links.getFirst()).get("start_index"));
    }

    @Test
    void tableIsAtomicAndCarriesSafeStructuredHtml() throws Exception {
        final var result = html("<h1>Heading</h1><table "
                                + "onclick='bad()'><caption>Results</caption><thead><tr><th>Region</th><th>Total</th></"
                                + "tr></thead><tbody><tr><td rowspan='2'>West &amp; "
                                + "East</td><td>100</td></tr></tbody></table><p>After.</p>");
        assertEquals(
                List.of(ElementType.Title, ElementType.FigureCaption, ElementType.Table, ElementType.NarrativeText),
                types(result));
        final var table = result.elements().get(2);
        assertEquals("Region\tTotal\nWest & East\t100", table.text());
        assertEquals("<table><tr><th>Region</th><th>Total</th></tr><tr><td rowspan=\"2\">West &amp; "
                             + "East</td><td>100</td></tr></table>",
                table.metadata().get("text_as_html"));
        assertEquals(result.elements().getFirst().elementId(), table.metadata().get("parent_id"));
    }

    @Test
    void nestedTablesFlattenWithoutDuplicateRowsAndWarn() throws Exception {
        final var result =
                html("<table><tr><td>Outer<table><tr><td>Inner</td></tr></table></td><td>Other</td></tr></table>");
        assertEquals(List.of("Outer Inner\tOther"), texts(result));
        assertTrue(result.warnings().stream().anyMatch(
                warning -> warning.code() == PartitionResult.WarningCode.NESTED_TABLE_FLATTENED));
    }

    @Test
    void tableLinksUseOffsetsInTheSerializedTableText() throws Exception {
        final var result = html("<table><tr><td>🧵 <a href='/x'>x</a></td><td><a href='/y'>y</a></td></tr></table>");
        final var table = result.elements().getFirst();
        assertEquals("🧵 x\ty", table.text());
        assertEquals(List.of(2, 4), table.metadata().get("link_start_indexes"));
        assertEquals(List.of("/x", "/y"), table.metadata().get("link_urls"));
    }

    @Test
    void stripsNonContentAndCanSkipHeadersAndFooters() throws Exception {
        final String input = "<header>Site</header><script>bad()</script><style>body{}</style><div "
                             + "hidden>Hidden</div><p aria-hidden='true'>Also "
                             + "hidden</p><template>Template</template><p>Visible.</p><footer>Copyright</footer>";
        final var result = html(input);
        assertEquals(List.of("Site", "Visible.", "Copyright"), texts(result));
        assertEquals(List.of(ElementType.Header, ElementType.NarrativeText, ElementType.Footer), types(result));
        final var skipped = processor.partition(DocumentSource.text(input),
                PartitionOptions.builder().format(DocumentFormat.HTML).skipHeadersAndFooters(true).build());
        assertEquals(List.of("Visible."), texts(skipped));
    }

    @Test
    void codeBlocksKeepWhitespaceAndChunkWithoutNormalization() throws Exception {
        final var result = html("<pre><code>  first\n    second 🧵</code></pre>");
        assertEquals(List.of("  first\n    second 🧵"), texts(result));
        assertEquals(ElementType.CodeSnippet, result.elements().getFirst().type());
        assertEquals(result.elements().getFirst().text(),
                Chunker.create().basic(result.elements(), ChunkOptions.defaults()).getFirst().text());
    }

    @Test
    void htmlEntitiesAndMalformedInputAreRecovered() throws Exception {
        final var result = html("<p><b>Broken &amp; readable.</p>");
        assertEquals(List.of("Broken & readable."), texts(result));
        assertTrue(result.warnings().stream().anyMatch(
                warning -> warning.code() == PartitionResult.WarningCode.HTML_RECOVERED));
    }

    @Test
    void sniffingOverridesMisleadingSupportedExtensionAndExplicitFormatWins() throws Exception {
        final var source = DocumentSource.text("<h1>Heading</h1>", "misnamed.txt");
        final var result = processor.partition(source);
        assertEquals(DocumentFormat.HTML, result.format());
        assertEquals(PartitionResult.WarningCode.FORMAT_MISMATCH, result.warnings().getFirst().code());
        final var explicit = processor.partition(
                source, PartitionOptions.builder().format(DocumentFormat.TXT).classifyText(false).build());
        assertEquals(List.of("<h1>Heading</h1>"), texts(explicit));
        assertEquals(DocumentFormat.TXT, explicit.format());
    }

    @Test
    void decodesBomAndExplicitCharset(final @TempDir Path directory) throws Exception {
        final byte[] encoded = "Hello 🧵.".getBytes(StandardCharsets.UTF_16LE);
        final byte[] bytes = new byte[encoded.length + 2];
        bytes[0] = (byte) 0xff;
        bytes[1] = (byte) 0xfe;
        System.arraycopy(encoded, 0, bytes, 2, encoded.length);
        final var file = directory.resolve("utf16.txt");
        Files.write(file, bytes);
        final var result = processor.partition(file);
        assertEquals(List.of("Hello 🧵."), texts(result));
        assertEquals("UTF-16LE", result.encoding());
        assertEquals(bytes.length, result.inputBytes());
        assertEquals("utf16.txt", result.elements().getFirst().metadata().get("filename"));
        final var generic = processor.partition(
                DocumentSource.of(file), PartitionOptions.builder().charset(StandardCharsets.UTF_16).build());
        assertEquals(result.elements(), generic.elements());
        final var latin =
                processor.partition(DocumentSource.bytes("Café.".getBytes(StandardCharsets.ISO_8859_1), "latin.txt"),
                        PartitionOptions.builder().charset(StandardCharsets.ISO_8859_1).build());
        assertEquals(List.of("Café."), texts(latin));
    }

    @Test
    void sniffsHtmlWithExplicitUtf16AndReadsHtmlMetaCharset() throws Exception {
        final var utf16 =
                processor.partition(DocumentSource.bytes("<h1>Heading</h1>".getBytes(StandardCharsets.UTF_16LE), null),
                        PartitionOptions.builder().charset(StandardCharsets.UTF_16LE).build());
        assertEquals(DocumentFormat.HTML, utf16.format());
        assertEquals(List.of("Heading"), texts(utf16));
        final var legacy = "<html><head><meta charset='windows-1252'></head><body><p>Café €.</p></body></html>";
        final var result = processor.partition(
                DocumentSource.bytes(legacy.getBytes(Charset.forName("windows-1252")), "legacy.html"));
        assertEquals(List.of("Café €."), texts(result));
        assertEquals("windows-1252", result.encoding());
        final var commented = "<!-- <meta charset='not-real'> --><html><head><meta "
                              + "charset='UTF-8'></head><body><p>Valid 🧵.</p></body></html>";
        final var clean = processor.partition(DocumentSource.bytes(commented.getBytes(StandardCharsets.UTF_8), null));
        assertEquals(List.of("Valid 🧵."), texts(clean));
        assertEquals(DocumentFormat.HTML, clean.format());
    }

    @Test
    void invalidEncodingNeverSilentlyReplacesCharacters() {
        assertCode(PartitionException.Code.INVALID_ENCODING,
                () -> processor.partition(DocumentSource.bytes(new byte[] {(byte) 0xc3, 0x28}, "bad.txt")));
        assertCode(PartitionException.Code.INVALID_ENCODING, () -> processor.partition(DocumentSource.text("\ud800")));
        assertCode(PartitionException.Code.INVALID_ENCODING,
                ()
                        -> processor.partition(DocumentSource.bytes(
                                "<html><head><meta charset='not-real'></head></html>".getBytes(StandardCharsets.UTF_8),
                                "bad.html")));
    }

    @Test
    void byteSnapshotsAreImmutableAndAlreadyDecodedTextIsNotReinterpreted() throws Exception {
        final byte[] bytes = "Original.".getBytes(StandardCharsets.UTF_8);
        final var source = new DocumentSource.Bytes(bytes, "input.txt");
        bytes[0] = 'X';
        source.content()[0] = 'Y';
        assertEquals(List.of("Original."), texts(processor.partition(source)));
        assertEquals(List.of("🧵."), texts(processor.partition(DocumentSource.text("🧵."),
                                             PartitionOptions.builder().charset(StandardCharsets.ISO_8859_1).build())));
    }

    @Test
    void rejectsBinaryFormatsAndUnsupportedExtensions() {
        assertCode(PartitionException.Code.MALFORMED_DOCUMENT,
                ()
                        -> processor.partition(
                                DocumentSource.bytes("%PDF-1.7".getBytes(StandardCharsets.US_ASCII), "fake.txt")));
        for (final DocumentSource input : List.of(DocumentSource.bytes(new byte[] {0x50, 0x4b, 3, 4}, "zip.txt"),
                     DocumentSource.text("not supported", "data.json"), DocumentSource.text("abc\u0000def"))) {
            assertCode(PartitionException.Code.UNSUPPORTED_FORMAT, () -> processor.partition(input));
        }
        assertEquals(java.util.Set.of(DocumentFormat.values()), processor.supportedFormats());
    }

    @Test
    void resourceLimitsFailWithTypedCodes(final @TempDir Path directory) throws Exception {
        final var maxBytes = PartitionOptions.builder().maxInputBytes(3).build();
        assertCode(PartitionException.Code.INPUT_TOO_LARGE,
                () -> processor.partition(DocumentSource.text("🧵"), maxBytes));
        assertCode(PartitionException.Code.INPUT_TOO_LARGE,
                () -> processor.partition(DocumentSource.bytes(new byte[4], "input.txt"), maxBytes));
        final var file = directory.resolve("large.txt");
        Files.writeString(file, "abcd");
        assertCode(
                PartitionException.Code.INPUT_TOO_LARGE, () -> processor.partition(DocumentSource.of(file), maxBytes));
        assertCode(PartitionException.Code.ELEMENT_LIMIT,
                ()
                        -> processor.partition(
                                DocumentSource.text("A\n\nB"), PartitionOptions.builder().maxElements(1).build()));
        assertCode(PartitionException.Code.HTML_DEPTH_LIMIT,
                ()
                        -> processor.partition(DocumentSource.text("<div><section><p>Nested</p></section></div>"),
                                PartitionOptions.builder().maxHtmlDepth(2).build()));
    }

    @Test
    void interruptionsArePropagatedWithoutClearingTheFlag() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> processor.partition(DocumentSource.text("Text")));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void partitionOutputRoundTripsAndChunks() throws Exception {
        final var result = html("<h1>Heading</h1><p>Body text.</p>");
        final var json = ElementJson.create();
        assertEquals(result.elements(), json.read(json.write(result.elements())));
        final var chunks = Chunker.create().byTitle(result.elements(), ChunkOptions.defaults());
        assertEquals("Heading\n\nBody text.", chunks.getFirst().text());
        assertEquals(
                result.elements(), json.decodeOriginals((String) chunks.getFirst().metadata().get("orig_elements")));
    }

    @Test
    void optionsRejectInvalidLimitsAndRelativeBaseUris() {
        assertThrows(IllegalArgumentException.class, () -> PartitionOptions.builder().maxInputBytes(0).build());
        assertThrows(IllegalArgumentException.class, () -> PartitionOptions.builder().maxElements(0).build());
        assertThrows(IllegalArgumentException.class, () -> PartitionOptions.builder().maxHtmlDepth(257).build());
        assertThrows(IllegalArgumentException.class,
                () -> PartitionOptions.builder().baseUri(URI.create("/relative")).build());
    }

    private void assertCode(
            final PartitionException.Code code, final org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(PartitionException.class, action).code());
    }
}
