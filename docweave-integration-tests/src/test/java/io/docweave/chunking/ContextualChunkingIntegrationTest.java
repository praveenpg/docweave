package io.docweave.chunking;

import io.docweave.DocWeave;
import io.docweave.core.ElementType;
import io.docweave.json.ElementJson;
import io.docweave.partition.DocumentSource;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Actual partition/chunk/enrich composition across native formats, without model/network dependencies. */
class ContextualChunkingIntegrationTest {
    /** Creates owned multi-format source controls. */
    ContextualChunkingIntegrationTest() {
    }

    /**
     * Every native source format uses the same post-chunk enrichment and keeps serialized originals intact.
     * @throws Exception if source authoring, partitioning, chunking or enrichment fails
     */
    @Test
    void nativeFormatsAndBothChunkStrategiesShareOneContextContract() throws Exception {
        final var sources = new LinkedHashMap<String, byte[]>();
        sources.put("report.txt", utf8("Acme 2026 report\n\nRevenue increased fifteen percent."));
        sources.put("report.html", utf8("<h1>Acme 2026 report</h1><p>Revenue increased fifteen percent.</p>"));
        sources.put("report.md", utf8("# Acme 2026 report\n\nRevenue increased fifteen percent."));
        sources.put("report.xml",
                utf8("<report><title>Acme 2026 report</title><p>Revenue increased fifteen percent.</p></report>"));
        sources.put("report.csv", utf8("Metric,Value\nRevenue,15"));
        sources.put("report.tsv", utf8("Metric\tValue\nRevenue\t15"));
        sources.put("report.docx", docx());
        sources.put("report.pdf", pdf());
        final var codec = ElementJson.create();
        final var options = ChunkOptions.builder().maxCharacters(1000).combineTextUnderCharacters(0).build();
        for (final var source : sources.entrySet()) {
            final var partitioned =
                    DocWeave.create().partition(DocumentSource.bytes(source.getValue(), source.getKey()));
            assertFalse(partitioned.elements().isEmpty(), source.getKey());
            for (final var strategy : Chunker.Strategy.values()) {
                final var chunks = Chunker.create().chunk(partitioned.elements(), strategy, options);
                assertFalse(chunks.isEmpty(), source.getKey() + "/" + strategy);
                final String before = codec.write(chunks);
                final var calls = new AtomicInteger();
                final var service = ContextualChunker.create(request -> {
                    calls.incrementAndGet();
                    assertEquals("Acme annual report for 2026", request.documentText());
                    assertEquals(chunks.get(request.chunkIndex()).text(), request.chunkText());
                    return new ChunkContextProvider.Context(
                            "Acme 2026 document context", "owned-test-provider", "fixture-v1");
                });
                final var result =
                        service.enrich("Acme annual report for 2026", chunks, ContextualChunkOptions.defaults());
                assertEquals(chunks.size(), calls.get());
                assertEquals(before, codec.write(chunks));
                assertEquals(before, codec.write(result.stream().map(ContextualChunk::original).toList()));
                assertEquals(chunks, codec.read(before));
                for (int index = 0; index < chunks.size(); index++) {
                    assertSame(chunks.get(index), result.get(index).original());
                    assertEquals("Acme 2026 document context\n\n" + chunks.get(index).text(),
                            result.get(index).embeddingText());
                }
            }
        }
    }

    /**
     * Structured table HTML/cells and fenced code spacing remain original source data after enrichment.
     * @throws Exception if partitioning, chunking or enrichment fails
     */
    @Test
    void tableAndCodeChunksRetainTheirOriginalPayloadAndProvenance() throws Exception {
        final var markdown = "# Acme report\n\n```java\nif (ready) {\n    run();\n}\n```\n";
        final var html = "<table><tr><th>Metric</th><th>Value</th></tr><tr><td>Revenue</td><td>15</td></tr></table>";
        final var elements = new java.util.ArrayList<>(
                DocWeave.create().partition(DocumentSource.bytes(utf8(markdown), "code.md")).elements());
        elements.addAll(DocWeave.create().partition(DocumentSource.bytes(utf8(html), "table.html")).elements());
        assertTrue(elements.stream().anyMatch(e -> e.type() == ElementType.CodeSnippet));
        assertTrue(elements.stream().anyMatch(e -> e.type() == ElementType.Table));
        final var chunks = Chunker.create().basic(elements, ChunkOptions.builder().maxCharacters(1000).build());
        final var result =
                ContextualChunker
                        .create(request -> new ChunkContextProvider.Context("Explicit test context", "fixture", "v1"))
                        .enrich("Owned table and code example", chunks, ContextualChunkOptions.defaults());
        assertTrue(chunks.stream().anyMatch(e -> e.metadata().containsKey("text_as_html")));
        assertTrue(chunks.stream().anyMatch(e -> e.text().contains("    run();")));
        for (int index = 0; index < chunks.size(); index++) {
            assertSame(chunks.get(index), result.get(index).original());
            assertTrue(chunks.get(index).metadata().containsKey("orig_elements"));
            assertEquals(chunks.get(index).metadata(), result.get(index).original().metadata());
        }
    }

    /**
     * Encodes independently authored source text without relying on platform default charset.
     * @param text original source text
     * @return UTF8 source bytes
     */
    private static byte[] utf8(final String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Authors a native DOCX source with two independent source paragraphs.
     * @return complete owned package bytes
     * @throws Exception if package authoring fails
     */
    private static byte[] docx() throws Exception {
        try (final var document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("Acme 2026 report");
            document.createParagraph().createRun().setText("Revenue increased fifteen percent.");
            final var bytes = new ByteArrayOutputStream();
            document.write(bytes);
            return bytes.toByteArray();
        }
    }

    /**
     * Authors a native PDF source with exact source text, independent of OCR or model fixtures.
     * @return complete owned document bytes
     * @throws Exception if PDF authoring fails
     */
    private static byte[] pdf() throws Exception {
        try (final var document = new PDDocument()) {
            final var page = new PDPage();
            document.addPage(page);
            try (final var stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(40, 700);
                stream.showText("Acme 2026 report");
                stream.newLineAtOffset(0, -24);
                stream.showText("Revenue increased fifteen percent.");
                stream.endText();
            }
            final var bytes = new ByteArrayOutputStream();
            document.save(bytes);
            return bytes.toByteArray();
        }
    }
}
