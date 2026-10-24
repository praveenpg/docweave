package io.docweave.chunking;

import io.docweave.DocWeave;
import io.docweave.core.Element;
import io.docweave.core.ElementType;
import io.docweave.json.ElementJson;
import io.docweave.partition.DocumentSource;
import io.docweave.partition.PartitionerRegistry;
import io.docweave.partition.PdfNativeLayoutOptions;
import io.docweave.partition.PdfOcrOptions;
import io.docweave.partition.PdfOcrProvider;
import io.docweave.partition.PdfOptions;
import io.docweave.partition.PdfPartitioner;
import io.docweave.partition.PdfTableLayoutProvider;
import io.docweave.partition.PdfWordOcrProvider;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Cross-module controls for declared provider headers, source provenance and bounded structural chunks. */
class PdfProviderHeaderChunkTest {
    /** Creates owned PDF-to-chunk integration controls. */
    PdfProviderHeaderChunkTest() {
    }

    /** Explicit independent source-text routes. */
    private enum Route {
        /** Native source positions. */ NATIVE,
        /** Actual page OCR tokens. */ WORDS,
        /** Authoritative per-cell OCR text. */ CELLS
    }

    /**
     * Every provider route retains its declaration through HTML-normalizing and header-repeating chunks.
     * @throws Exception if owned source authoring or extraction fails
     */
    @Test
    void declaredHeadersReachChunksAcrossNativeWordAndCellRoutes() throws Exception {
        for (final var route : Route.values()) {
            final var source = extract(route, 1);
            assertEquals(1, source.metadata().get("table_leading_header_rows"));
            assertEquals(0, Jsoup.parse((String) source.metadata().get("text_as_html")).select("th").size());
            final var chunks = Chunker.create().basic(
                    List.of(source), ChunkOptions.builder().maxCharacters(180).includeOriginalElements(true).build());
            assertTrue(chunks.size() > 1);
            for (int index = 0; index < chunks.size(); index++) {
                final var chunk = chunks.get(index);
                assertEquals(index == 0 ? 0 : 1, chunk.metadata().get("num_carried_over_header_rows"));
                assertEquals(1, chunk.metadata().get("table_leading_header_rows"));
                assertFalse(chunk.metadata().containsKey("pdf_table_header_rows"));
                assertFalse(chunk.metadata().containsKey("pdf_table_header_policy"));
                assertTrue(chunk.text().startsWith("Item Qty"));
                assertEquals(index == 0 ? 0 : 2,
                        Jsoup.parse((String) chunk.metadata().get("text_as_html")).select("th").size());
                assertTrue(((String) chunk.metadata().get("text_as_html")).length() <= 180);
                final var original =
                        ElementJson.create().decodeOriginals((String) chunk.metadata().get("orig_elements"));
                assertEquals(List.of(source), original);
            }
            for (int row = 1; row < 7; row++) {
                final String body = "Row" + row + "BodyValue";
                assertEquals(1, chunks.stream().filter(chunk -> chunk.text().contains(body)).count());
            }
            final var whole =
                    Chunker.create().basic(List.of(source), ChunkOptions.builder().maxCharacters(4000).build());
            assertEquals(1, whole.size());
            assertEquals(1, whole.getFirst().metadata().get("pdf_table_header_rows"));
            assertEquals(1, whole.getFirst().metadata().get("table_leading_header_rows"));
            assertEquals(0, Jsoup.parse((String) whole.getFirst().metadata().get("text_as_html")).select("th").size());
        }
    }

    /**
     * Unknown declarations and explicitly disabled repetition never invent repeated headers.
     * @throws Exception if owned extraction fails
     */
    @Test
    void unknownHeadersAndDisabledRepetitionStayExplicit() throws Exception {
        for (final var route : Route.values()) {
            final var unknown = extract(route, 0);
            assertFalse(unknown.metadata().containsKey("table_leading_header_rows"));
            final var chunks =
                    Chunker.create().basic(List.of(unknown), ChunkOptions.builder().maxCharacters(180).build());
            assertTrue(chunks.size() > 1);
            assertTrue(
                    chunks.stream().allMatch(chunk -> chunk.metadata().get("num_carried_over_header_rows").equals(0)));
            final var declared = extract(route, 1);
            final var noRepeat = Chunker.create().basic(
                    List.of(declared), ChunkOptions.builder().maxCharacters(180).repeatTableHeaders(false).build());
            assertTrue(noRepeat.stream().allMatch(
                    chunk -> chunk.metadata().get("num_carried_over_header_rows").equals(0)));
        }
    }

    /**
     * Text-only fragments drop whole-source header claims while retaining them in original-element provenance.
     * @throws Exception if owned extraction fails
     */
    @Test
    void tinyTextFallbackRetainsSourceDeclarationsOnlyInProvenance() throws Exception {
        final var source = extract(Route.NATIVE, 1);
        final var tiny = Chunker.create().basic(
                List.of(source), ChunkOptions.builder().maxCharacters(20).includeOriginalElements(true).build());
        assertTrue(tiny.size() > 1);
        for (final var chunk : tiny) {
            assertFalse(chunk.metadata().containsKey("pdf_table_header_rows"));
            assertFalse(chunk.metadata().containsKey("pdf_table_header_policy"));
            assertFalse(chunk.metadata().containsKey("table_leading_header_rows"));
            assertEquals(0, chunk.metadata().get("num_carried_over_header_rows"));
            assertEquals(List.of(source),
                    ElementJson.create().decodeOriginals((String) chunk.metadata().get("orig_elements")));
        }
    }

    /**
     * Extracts one fixed seven-row table through the selected public provider factory.
     * @param route independently selected text source
     * @param headers explicit declared prefix, zero means unknown
     * @return complete extracted table with unchanged provider HTML
     * @throws Exception if authoring or extraction fails
     */
    private static Element extract(final Route route, final int headers) throws Exception {
        return extract(route, headers, false);
    }

    /**
     * Crossing header/body merges keep the source declaration without repeating the contained body text.
     * @throws Exception if source authoring or extraction fails
     */
    @Test
    void crossingHeaderBodySpansNeverDuplicateBodyThroughRepetition() throws Exception {
        for (final var route : Route.values()) {
            final var source = extract(route, 1, true);
            assertEquals(1, source.metadata().get("pdf_table_header_rows"));
            assertFalse(source.metadata().containsKey("table_leading_header_rows"));
            final var chunks =
                    Chunker.create().basic(List.of(source), ChunkOptions.builder().maxCharacters(180).build());
            assertTrue(chunks.size() > 1);
            assertTrue(
                    chunks.stream().allMatch(chunk -> chunk.metadata().get("num_carried_over_header_rows").equals(0)));
            for (int row = 1; row < 7; row++) {
                final String body = "Row" + row + "BodyValue";
                assertEquals(1, chunks.stream().filter(chunk -> chunk.text().contains(body)).count());
            }
        }
    }

    /**
     * Extracts a fixed grid with an optional independently authored crossing merge.
     * @param route selected source-text route
     * @param headers declared prefix
     * @param crossing whether one anchor spans header and first body row
     * @return complete extracted table
     * @throws Exception if source authoring or extraction fails
     */
    private static Element extract(final Route route, final int headers, final boolean crossing) throws Exception {
        final var words = words();
        final var original = layout(headers);
        final var anchors = new ArrayList<>(original.anchors());
        if (crossing) {
            anchors.remove(2);
            anchors.set(
                    0, new PdfTableLayoutProvider.Anchor(0, 0, 2, 1, new PdfTableLayoutProvider.Box(10, 10, 110, 50)));
        }
        final var geometry = new PdfTableLayoutProvider.Table(
                original.rows(), original.columns(), original.bounds(), anchors, original.provenance(), headers);
        final PdfTableLayoutProvider provider = image -> List.of(geometry);
        final PdfPartitioner partitioner;
        final var options = PdfOptions.defaults().withTableCellCoordinates(true);
        if (route == Route.NATIVE) {
            partitioner = PdfPartitioner.createWithNativeLayout(
                    options, new PdfNativeLayoutOptions(72, 40_000, 32), provider);
        } else {
            final PdfWordOcrProvider page = (image, kind) -> words;
            final var ocr = new PdfOcrOptions(72, 40_000, 100, 10_000)
                                    .withTableDetection(route == Route.CELLS
                                                                ? PdfOcrOptions.TableDetection.PROVIDER_LAYOUT_CELLS
                                                                : PdfOcrOptions.TableDetection.PROVIDER_LAYOUT);
            final var cells = new AtomicInteger();
            final var cellText =
                    anchors.stream()
                            .map(anchor -> {
                                final var box = anchor.bounds();
                                return String.join(" ", words.stream()
                                                                .filter(word -> {
                                                                    final var line = word.line();
                                                                    final double x = (line.left() + line.right()) / 2;
                                                                    final double y = (line.top() + line.bottom()) / 2;
                                                                    return x >= box.left() && x < box.right()
                                                                            && y >= box.top() && y < box.bottom();
                                                                })
                                                                .map(word -> word.line().text())
                                                                .toList());
                            })
                            .toList();
            final PdfOcrProvider recognizer =
                    image -> List.of(new PdfOcrProvider.Line(cellText.get(cells.getAndIncrement()), 1, 1, 2, 2));
            partitioner = route == Route.CELLS
                                  ? PdfPartitioner.createWithCellOcr(options, ocr, page, provider, recognizer)
                                  : PdfPartitioner.create(options, ocr, page, provider);
        }
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(200, 200));
            document.addPage(page);
            if (route == Route.NATIVE) {
                try (final var content = new PDPageContentStream(document, page)) {
                    for (final var word : words) {
                        content.beginText();
                        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 8);
                        content.newLineAtOffset((float) word.line().left(), (float) (200 - word.line().bottom()));
                        content.showText(word.line().text());
                        content.endText();
                    }
                }
            }
            document.save(output);
            final var result = DocWeave.create(PartitionerRegistry.of(List.of(partitioner)))
                                       .partition(DocumentSource.bytes(output.toByteArray(), "provider-header.pdf"));
            assertEquals(1, result.elements().stream().filter(element -> element.type() == ElementType.Table).count());
            return result.elements()
                    .stream()
                    .filter(element -> element.type() == ElementType.Table)
                    .findFirst()
                    .orElseThrow();
        }
    }

    /**
     * Authors seven complete rows of disjoint anchors without model predictions or reference-guided geometry.
     * @param headers declared top prefix
     * @return fixed two-column table
     */
    private static PdfTableLayoutProvider.Table layout(final int headers) {
        final var anchors = new ArrayList<PdfTableLayoutProvider.Anchor>();
        for (int row = 0; row < 7; row++) {
            anchors.add(new PdfTableLayoutProvider.Anchor(
                    row, 0, 1, 1, new PdfTableLayoutProvider.Box(10, 10 + row * 20, 110, 30 + row * 20)));
            anchors.add(new PdfTableLayoutProvider.Anchor(
                    row, 1, 1, 1, new PdfTableLayoutProvider.Box(110, 10 + row * 20, 190, 30 + row * 20)));
        }
        return new PdfTableLayoutProvider.Table(
                7, 2, new PdfTableLayoutProvider.Box(10, 10, 190, 150), anchors, "owned_headers", headers);
    }

    /**
     * Supplies fixed source words in row-major order, shared by native authoring and independent OCR controls.
     * @return immutable token lines
     */
    private static List<PdfWordOcrProvider.WordLine> words() {
        final var lines = new ArrayList<PdfWordOcrProvider.WordLine>();
        for (int row = 0; row < 7; row++) {
            for (int column = 0; column < 2; column++) {
                final String text = row == 0      ? (column == 0 ? "Item" : "Qty")
                                    : column == 0 ? "Row" + row + "BodyValue"
                                                  : Integer.toString(row * 10);
                final int x = column == 0 ? 15 : 115;
                final int y = 14 + row * 20;
                final var word = new PdfWordOcrProvider.Word(text, x, y, x + 70, y + 10);
                lines.add(new PdfWordOcrProvider.WordLine(
                        new PdfOcrProvider.Line(text, x, y, x + 70, y + 10), List.of(word)));
            }
        }
        return List.copyOf(lines);
    }
}
