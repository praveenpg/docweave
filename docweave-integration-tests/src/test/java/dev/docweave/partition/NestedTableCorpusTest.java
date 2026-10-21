package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
import dev.docweave.json.ElementJson;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.xmlbeans.XmlObject;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Model-generated owned corpus: source hierarchy is the oracle, rather than a parser-produced golden. */
class NestedTableCorpusTest {
    private static final String LABEL = "😀 Repeat <&>";
    private static final String URL = "https://example.com/item";
    private static final List<DocumentFormat> FORMATS =
            List.of(DocumentFormat.HTML, DocumentFormat.MARKDOWN, DocumentFormat.DOCX);
    private final DocWeave processor = DocWeave.create();

    private record Cell(int span, List<Table> children) {}

    private record Table(List<List<Cell>> rows) {}

    private record Expected(Table table, Integer parent, List<Map<String, Object>> path, int tokenStart) {}

    private Table generated(final Random random, final int depth) {
        if (depth > 0 && random.nextInt(5) == 0) {
            return new Table(List.of());
        }
        final var rows = new ArrayList<List<Cell>>();
        for (int r = 0, count = 1 + random.nextInt(2); r < count; r++) {
            final var cells = new ArrayList<Cell>();
            for (int c = 0, countCells = 1 + random.nextInt(2); c < countCells; c++) {
                final var children = new ArrayList<Table>();
                if (depth < 3) {
                    for (int i = 0, countChildren = depth == 0 ? 1 + random.nextInt(2) : random.nextInt(3);
                            i < countChildren; i++) {
                        children.add(generated(random, depth + 1));
                    }
                }
                cells.add(new Cell(1 + random.nextInt(2), List.copyOf(children)));
            }
            rows.add(List.copyOf(cells));
        }
        return new Table(List.copyOf(rows));
    }

    private List<Expected> expected(final Table table) {
        final var result = new ArrayList<Expected>();
        descend(table, null, List.of(), result, new int[] {0});
        return result;
    }

    private void descend(final Table table, final Integer parent, final List<Map<String, Object>> path,
            final List<Expected> result, final int[] cursor) {
        for (int r = 0; r < table.rows().size(); r++) {
            int column = 0;
            for (final var cell : table.rows().get(r)) {
                cursor[0] += 3; // The model's LABEL has three semantic tokens.
                for (int ordinal = 0; ordinal < cell.children().size(); ordinal++) {
                    final var child = cell.children().get(ordinal);
                    final var childPath = new ArrayList<>(path);
                    childPath.add(Map.of("row", r, "column", column, "table_index", ordinal));
                    final int index = result.size();
                    result.add(new Expected(child, parent, List.copyOf(childPath), cursor[0]));
                    descend(child, index, childPath, result, cursor);
                    cursor[0]++; // Between
                }
                cursor[0]++; // Tail
                column += cell.span();
            }
        }
    }

    /** Same semantic token order for all formats; row/cell/paragraph separator spelling is intentionally ignored. */
    private String words(final Table table) {
        final var tokens = new ArrayList<String>();
        for (final var row : table.rows()) {
            for (final var cell : row) {
                tokens.add(LABEL);
                for (final var child : cell.children()) {
                    final String content = words(child);
                    if (!content.isEmpty()) {
                        tokens.add(content);
                    }
                    tokens.add("Between");
                }
                tokens.add("Tail");
            }
        }
        return String.join(" ", tokens);
    }

    private int cells(final Table table) {
        return table.rows()
                .stream()
                .flatMap(List::stream)
                .mapToInt(cell -> 1 + cell.children().stream().mapToInt(this::cells).sum())
                .sum();
    }

    private String markup(final Table table, final boolean docx, final String relationship) {
        final var output = new StringBuilder(docx ? "<w:tbl>" : "<table>");
        for (final var row : table.rows()) {
            output.append(docx ? "<w:tr>" : "<tr>");
            for (final var cell : row) {
                if (docx) {
                    output.append("<w:tc><w:tcPr><w:gridSpan w:val='")
                            .append(cell.span())
                            .append("'/></w:tcPr>")
                            .append("<w:p><w:hyperlink r:id='")
                            .append(relationship)
                            .append("'><w:r><w:t>")
                            .append(escape(LABEL))
                            .append("</w:t></w:r></w:hyperlink></w:p>");
                } else {
                    output.append("<td colspan='")
                            .append(cell.span())
                            .append("'><a href='")
                            .append(URL)
                            .append("'>")
                            .append(escape(LABEL))
                            .append("</a>");
                }
                for (final var child : cell.children()) {
                    output.append(markup(child, docx, relationship));
                    output.append(docx ? "<w:p><w:r><w:t>Between</w:t></w:r></w:p>" : "<p>Between</p>");
                }
                output.append(docx ? "<w:p><w:r><w:t>Tail</w:t></w:r></w:p></w:tc>" : "<p>Tail</p></td>");
            }
            output.append(docx ? "</w:tr>" : "</tr>");
        }
        return output.append(docx ? "</w:tbl>" : "</table>").toString();
    }

    private String escape(final String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private byte[] docx(final Table model, final String label) throws Exception {
        try (final var document = new XWPFDocument(); final var output = new ByteArrayOutputStream()) {
            final String relationship = document.getPackagePart()
                                                .addExternalRelationship(URL, DocxParagraphText.REL + "/hyperlink")
                                                .getId();
            final var xml = XmlObject.Factory.parse(
                    "<root xmlns:w='" + DocxBodyView.WORD + "' xmlns:r='" + DocxParagraphText.REL + "'>"
                    + markup(model, true, relationship).replace(escape(LABEL), escape(label)) + "</root>");
            try (final var from = xml.newCursor(); final var to = document.getDocument().getBody().newCursor()) {
                from.toFirstChild();
                to.toEndToken();
                from.copyXmlContents(to);
            }
            document.write(output);
            return output.toByteArray();
        }
    }

    private DocumentSource source(final Table model, final DocumentFormat format) throws Exception {
        return source(model, format, LABEL);
    }

    private DocumentSource source(final Table model, final DocumentFormat format, final String label) throws Exception {
        return format == DocumentFormat.DOCX
                ? DocumentSource.bytes(docx(model, label), "corpus.docx")
                : DocumentSource.text((format == DocumentFormat.MARKDOWN ? "# Corpus\n\n" : "")
                                              + markup(model, false, "").replace(escape(LABEL), escape(label)),
                          format == DocumentFormat.MARKDOWN ? "corpus.md" : "corpus.html");
    }

    private PartitionOptions options(final DocumentFormat format, final NestedTableOptions nested) {
        return PartitionOptions.builder()
                .format(format)
                .nestedTableOptions(nested)
                .markdownOptions(
                        new MarkdownOptions(true, 128, MarkdownOptions.RawHtml.EXTRACT_DOM, TableLimits.defaults()))
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> descriptors(final Element table) {
        return (List<Map<String, Object>>) table.metadata().get("nested_tables");
    }

    private Element root(final PartitionResult result) {
        return result.elements()
                .stream()
                .filter(element -> element.type() == ElementType.Table)
                .findFirst()
                .orElseThrow();
    }

    private String normalized(final String text) {
        return text.replaceAll("\\s+", " ").strip();
    }

    private void links(final String text, final Object value, final int expectedCount) {
        final var links = (List<?>) value;
        assertEquals(expectedCount, links.size());
        int previous = -1;
        for (final var valueLink : links) {
            final var link = (Map<?, ?>) valueLink;
            final int offset = (Integer) link.get("start_index");
            assertTrue(offset > previous);
            assertTrue(text.startsWith(LABEL, text.offsetByCodePoints(0, offset)));
            assertEquals(LABEL, link.get("text"));
            assertEquals(URL, link.get("url"));
            previous = offset;
        }
    }

    @Test
    void seededModelsPreserveIndependentHierarchyGridsTokensLinksAndDefaultOutputAcrossThreeFormats() throws Exception {
        for (long seed = 0; seed < 24; seed++) {
            final var model = generated(new Random(0xD0C000L + seed), 0);
            final var expected = expected(model);
            for (final var format : FORMATS) {
                assertAll("seed=" + seed + ", format=" + format, () -> {
                    final var source = source(model, format);
                    final var baseline = processor.partition(source, options(format, NestedTableOptions.defaults()));
                    final var result =
                            processor.partition(source, options(format, NestedTableOptions.preserveMetadata()));
                    assertEquals(baseline.warnings(), result.warnings());
                    assertEquals(baseline.elements(), result.elements()
                                                              .stream()
                                                              .map(element -> {
                                                                  final var metadata =
                                                                          new LinkedHashMap<>(element.metadata());
                                                                  metadata.remove("nested_tables");
                                                                  metadata.remove("nested_tables_version");
                                                                  return new Element(element.elementId(),
                                                                          element.type(), element.text(), metadata);
                                                              })
                                                              .toList());
                    final var table = root(result);
                    assertEquals(words(model), normalized(table.text()));
                    links(table.text(), table.metadata().get("links"), cells(model));
                    final var actual = descriptors(table);
                    assertEquals(expected.size(), actual.size());
                    for (int index = 0; index < expected.size(); index++) {
                        final var oracle = expected.get(index);
                        final var child = actual.get(index);
                        assertEquals(index, child.get("index"));
                        assertEquals(oracle.parent(), child.get("parent_index"));
                        assertEquals(oracle.path(), child.get("path"));
                        final String text = (String) child.get("text");
                        assertEquals(words(oracle.table()), normalized(text));
                        final String interval = table.text().substring(
                                table.text().offsetByCodePoints(0, (Integer) child.get("start_index")),
                                table.text().offsetByCodePoints(0, (Integer) child.get("end_index")));
                        assertEquals(words(oracle.table()), normalized(interval));
                        final String prefix = normalized(table.text().substring(
                                0, table.text().offsetByCodePoints(0, (Integer) child.get("start_index"))));
                        assertEquals(oracle.tokenStart(), prefix.isEmpty() ? 0 : prefix.split(" ").length);

                        links(text, child.get("links"), cells(oracle.table()));
                        final var html = Jsoup.parseBodyFragment((String) child.get("text_as_html"));
                        assertEquals(1, html.select("table").size());
                        final var rows = html.select("tr");
                        assertEquals(oracle.table().rows().size(), rows.size());
                        for (int r = 0; r < rows.size(); r++) {
                            final var cells = rows.get(r).children();
                            assertEquals(oracle.table().rows().get(r).size(), cells.size());
                            for (int c = 0; c < cells.size(); c++) {
                                assertEquals(oracle.table().rows().get(r).get(c).span(),
                                        cells.get(c).hasAttr("colspan") ? Integer.parseInt(cells.get(c).attr("colspan"))
                                                                        : 1);
                            }
                        }
                    }
                    final var codec = ElementJson.create();
                    assertEquals(result.elements(), codec.read(codec.write(result.elements())));
                });
            }
        }
    }

    private Table siblings(final int count) {
        return new Table(List.of(List.of(new Cell(
                1, java.util.stream.IntStream.range(0, count).mapToObj(index -> new Table(List.of())).toList()))));
    }

    private Table chain(final int depth) {
        return new Table(List.of(List.of(new Cell(1, depth == 0 ? List.of() : List.of(chain(depth - 1))))));
    }

    @Test
    void defaultCountAndDepthLimitsAcceptExactBoundaryAndRejectOneMoreAcrossAllFormats() throws Exception {
        for (final var format : FORMATS) {
            for (final var model : List.of(siblings(256), chain(16))) {
                final var table = root(processor.partition(
                        source(model, format), options(format, NestedTableOptions.preserveMetadata())));
                assertEquals(expected(model).size(), descriptors(table).size());
            }
            for (final var model : List.of(siblings(257), chain(17))) {
                final var source = source(model, format);
                assertEquals(PartitionException.Code.TABLE_LIMIT,
                        assertThrows(PartitionException.class,
                                ()
                                        -> processor.partition(
                                                source, options(format, NestedTableOptions.preserveMetadata())))
                                .code());
            }
        }
    }

    @Test
    void cumulativePayloadAccountsForDeepRepeatedCopiesAtExactBoundary() throws Exception {
        final var model = chain(12);
        for (final var format : FORMATS) {
            final var source = source(model, format);
            final var table = root(processor.partition(source, options(format, NestedTableOptions.preserveMetadata())));
            final long amount = descriptors(table).stream().mapToLong(this::payload).sum();
            final var exact = new NestedTableOptions(NestedTableOptions.Mode.PRESERVE_METADATA, 256, 16, amount);
            assertEquals(table, root(processor.partition(source, options(format, exact))));
            assertEquals(PartitionException.Code.TABLE_LIMIT,
                    assertThrows(PartitionException.class,
                            ()
                                    -> processor.partition(source,
                                            options(format,
                                                    new NestedTableOptions(NestedTableOptions.Mode.PRESERVE_METADATA,
                                                            256, 16, amount - 1))))
                            .code());
        }
    }

    @Test
    void largeEscapedUnicodeSnapshotsExceedDefaultPayloadButFitAnExplicitLargerBudget() throws Exception {
        final var random = new Random(0xE5CA9E);
        final int[] alphabet = "😀 &<>abcdefgh0123456789".codePoints().toArray();
        final var label = new StringBuilder();
        for (int i = 0; i < 8000; i++) {
            label.appendCodePoint(alphabet[random.nextInt(alphabet.length)]);
        }
        // Incompressible fragments keep DOCX archive defenses independent of this metadata-budget case.
        final var model = chain(16);
        for (final var format : FORMATS) {
            final var source = source(model, format, label.toString());
            assertEquals(PartitionException.Code.TABLE_LIMIT,
                    assertThrows(PartitionException.class,
                            () -> processor.partition(source, options(format, NestedTableOptions.preserveMetadata())))
                            .code());
            final var raised =
                    new NestedTableOptions(NestedTableOptions.Mode.PRESERVE_METADATA, 256, 16, 16L * 1024 * 1024);
            final var table = root(processor.partition(source, options(format, raised)));
            assertEquals(16, descriptors(table).size());
            assertTrue(descriptors(table).stream().mapToLong(this::payload).sum() > 1024 * 1024);
            final var metadata = new LinkedHashMap<>(table.metadata());
            metadata.remove("nested_tables");
            metadata.remove("nested_tables_version");
            assertEquals(root(processor.partition(source, options(format, NestedTableOptions.defaults()))),
                    new Element(table.elementId(), table.type(), table.text(), metadata));
        }
    }

    private long payload(final Object value) {
        if (value instanceof final String text) {
            return text.codePointCount(0, text.length());
        }
        if (value instanceof final Map<?, ?> map) {
            return 1
                    + map.entrySet()
                              .stream()
                              .mapToLong(entry -> payload(entry.getKey()) + payload(entry.getValue()))
                              .sum();
        }
        if (value instanceof final List<?> list) {
            return 1 + list.stream().mapToLong(this::payload).sum();
        }
        return 1;
    }
}
