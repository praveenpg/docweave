package io.docweave.json;

import io.docweave.DocWeave;
import io.docweave.core.Element;
import io.docweave.core.ElementType;
import io.docweave.partition.DocumentSource;
import io.docweave.partition.PartitionOptions;
import io.docweave.partition.PartitionResult;
import io.docweave.partition.PartitionerRegistry;
import io.docweave.partition.PdfBudget;
import io.docweave.partition.PdfOptions;
import io.docweave.partition.PdfPageRange;
import io.docweave.partition.PdfPartitioner;
import io.docweave.partition.PdfRangeAssembler;
import io.docweave.partition.PdfRangeResult;
import io.docweave.partition.PdfTableBoundary;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Range results survive encoding exactly, and assembling decoded results equals the whole document. */
class PdfRangeResultJsonTest {
    /** Codec under test. */
    private final PdfRangeResultJson codec = PdfRangeResultJson.create();

    /** Creates the test. */
    PdfRangeResultJsonTest() {
    }

    /**
     * Every split of a document with a long table chain round-trips and assembles to whole-document output,
     * including the links across the split.
     * @throws Exception if partitioning fails
     */
    @Test
    void decodedRangesAssembleToTheWholeDocument() throws Exception {
        final var options = PdfOptions.defaults().withTableContinuation(PdfOptions.TableContinuation.REPEATED_HEADER);
        final var partitioner = PdfPartitioner.create(options);
        final byte[] pdf = chain();
        final var whole = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options))))
                                  .partition(DocumentSource.bytes(pdf, "chain.pdf"));
        assertTrue(
                whole.elements().stream().filter(e -> e.metadata().containsKey("pdf_table_continuation")).count() >= 5);
        final var defaults = PartitionOptions.builder().build();
        for (int split = 1; split < 8; split++) {
            final var ranges = new ArrayList<PdfRangeResult>();
            for (final var range : List.of(new PdfPageRange(1, split), new PdfPageRange(split + 1, 8))) {
                final var original = partitioner.partitionRange(
                        DocumentSource.bytes(pdf, "chain.pdf"), defaults, range, PdfBudget.unlimited());
                final var bytes = new ByteArrayOutputStream();
                codec.write(original, bytes);
                final var decoded = codec.read(new ByteArrayInputStream(bytes.toByteArray()));
                assertEquals(original, decoded);
                assertEquals(String.valueOf(original), String.valueOf(decoded));
                assertArrayEquals(bytes.toByteArray(), codec.write(decoded));
                ranges.add(decoded);
            }
            final var assembled = PdfRangeAssembler.create().assemble(ranges);
            assertEquals(whole.elements(), assembled.elements(), "split " + split);
            assertEquals(String.valueOf(whole.elements()), String.valueOf(assembled.elements()));
            assertEquals(whole.warnings(), assembled.warnings());
        }
    }

    /**
     * Every supported metadata value type, tag-like keys, nulls and boundary evidence keep their exact types.
     */
    @Test
    void metadataTypesAndEvidenceRoundTripExactly() {
        final var metadata = new LinkedHashMap<String, Object>();
        metadata.put("filetype", "application/pdf");
        metadata.put("int", 7);
        metadata.put("long", 7L);
        metadata.put("short", (short) 7);
        metadata.put("byte", (byte) 7);
        metadata.put("float", 0.1f);
        metadata.put("double", -0.0);
        metadata.put("exp", 1e21);
        metadata.put("big_integer", new BigInteger("123456789012345678901234567890"));
        metadata.put("big_decimal", new BigDecimal("1E+3"));
        metadata.put("bool", true);
        metadata.put("unicode", "é😀 \"");
        metadata.put("tag_like", Map.of("$num", "L:1"));
        metadata.put("wrapped", Map.of("$map", Map.of("a", 1)));
        metadata.put("nested", Arrays.asList(1, null, List.of(), Map.of()));
        final var element = new Element("a".repeat(32), ElementType.Table, "Item", metadata);
        final var boundary = new PdfTableBoundary(3, element.elementId(),
                new PdfTableBoundary.Evidence(500, 800,
                        Map.of("pdf_page_rotation", 90, "pdf_crop_box", List.of(5.0, 0.0, 495.0, 800.0)), "ruled",
                        List.of(40.0, 220.0, 400.0), List.of("Item", "Qty"), 40, 760),
                true, false);
        final var range = new PdfRangeResult(PdfRangeResult.FORMAT_VERSION, PdfRangeResult.currentLibraryVersion(),
                "0".repeat(64), 10, null, "f".repeat(64), 5, new PdfPageRange(3, 3),
                List.of(new PartitionResult.Warning(PartitionResult.WarningCode.PDF_EXTRACTION_LIMITATION, "w")),
                PdfRangeResult.ID_REFERENCE_FIELDS,
                List.of(new PdfRangeResult.Page(3, List.of(element), List.of(),
                        Map.of(PdfBudget.Kind.ELEMENTS, 1L, PdfBudget.Kind.IMAGE_BYTES, 99L))),
                boundary, boundary);
        final var decoded = codec.read(codec.write(range));
        assertEquals(range, decoded);
        final var decodedMetadata = decoded.pages().getFirst().elements().getFirst().metadata();
        metadata.forEach((key, value) -> {
            if (!(value instanceof Map<?, ?>) && !(value instanceof List<?>)) {
                assertEquals(value.getClass(), decodedMetadata.get(key).getClass(), key);
            }
        });
        assertEquals(List.copyOf(metadata.keySet()), List.copyOf(decodedMetadata.keySet()));
        assertEquals(boundary.evidence(), decoded.firstPageTable().evidence());
        assertThrows(IllegalArgumentException.class, () -> codec.read("{\"format\":\"other\"}".getBytes()));
    }

    /**
     * NaN, both infinities and negative zero survive in boundary evidence (and negative zero in metadata, which rejects
     * non-finite numbers itself); assembling decoded results gives the same output as the originals.
     * @throws Exception if partitioning fails
     */
    @Test
    void nonFiniteDoublesRoundTripAndAssembleIdentically() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> new Element("x".repeat(32), ElementType.Title, "t", Map.of("nan", Double.NaN)));
        final var options = PdfOptions.defaults().withTableContinuation(PdfOptions.TableContinuation.REPEATED_HEADER);
        final var partitioner = PdfPartitioner.create(options);
        final byte[] pdf = chain();
        final var defaults = PartitionOptions.builder().build();
        final var head = partitioner.partitionRange(
                DocumentSource.bytes(pdf, "chain.pdf"), defaults, new PdfPageRange(1, 3), PdfBudget.unlimited());
        final var tail = partitioner.partitionRange(
                DocumentSource.bytes(pdf, "chain.pdf"), defaults, new PdfPageRange(4, 8), PdfBudget.unlimited());
        final var last = head.lastPageTable();
        final var evidence = last.evidence();
        final var transforms = new LinkedHashMap<String, Object>(evidence.transforms());
        transforms.put("nan", Double.NaN);
        transforms.put("inf", List.of(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.0));
        final var odd = new PdfTableBoundary(last.pageNumber(), last.elementId(),
                new PdfTableBoundary.Evidence(Double.NaN, evidence.height(), transforms, evidence.source(),
                        List.of(Double.NEGATIVE_INFINITY, -0.0, Double.POSITIVE_INFINITY), evidence.header(), -0.0,
                        Double.POSITIVE_INFINITY),
                last.entryClear(), last.exitClear());
        final var withZero = new ArrayList<PdfRangeResult.Page>(head.pages());
        final var firstPage = withZero.getFirst();
        final var elements = new ArrayList<>(firstPage.elements());
        final var metadata = new LinkedHashMap<>(elements.getFirst().metadata());
        metadata.put("negative_zero", -0.0);
        elements.set(0, new Element(elements.getFirst().elementId(), elements.getFirst().type(),
                                elements.getFirst().text(), metadata));
        withZero.set(
                0, new PdfRangeResult.Page(firstPage.pageNumber(), elements, firstPage.warnings(), firstPage.usage()));
        final var modified = new PdfRangeResult(head.formatVersion(), head.libraryVersion(), head.sourceSha256(),
                head.sourceBytes(), head.filename(), head.optionsDigest(), head.documentPages(), head.coverage(),
                head.documentWarnings(), head.idReferenceFields(), withZero, head.firstPageTable(), odd);
        final var decoded = codec.read(codec.write(modified));
        assertEquals(modified.pages(), decoded.pages(), "pages");
        assertEquals(modified.lastPageTable().evidence().transforms(), decoded.lastPageTable().evidence().transforms(),
                "transforms");
        assertEquals(modified.lastPageTable().evidence(), decoded.lastPageTable().evidence(), "evidence");
        assertEquals(modified, decoded);
        assertTrue(Double.isNaN(decoded.lastPageTable().evidence().width()));
        assertEquals(
                Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(decoded.lastPageTable().evidence().top()));
        assertEquals(Double.doubleToRawLongBits(-0.0),
                Double.doubleToRawLongBits(
                        (Double) decoded.pages().getFirst().elements().getFirst().metadata().get("negative_zero")));
        assertEquals(Double.POSITIVE_INFINITY, decoded.lastPageTable().evidence().columns().getLast());
        final var expected = PdfRangeAssembler.create().assemble(List.of(modified, tail));
        final var actual = PdfRangeAssembler.create().assemble(List.of(decoded, codec.read(codec.write(tail))));
        assertEquals(expected.elements(), actual.elements());
        assertEquals(String.valueOf(expected.elements()), String.valueOf(actual.elements()));
    }

    /**
     * Reads beyond the configured encoded size fail with IllegalArgumentException without consuming the input.
     * @throws Exception if encoding fails
     */
    @Test
    void readsAreBounded() throws Exception {
        final var partitioner = PdfPartitioner.create(PdfOptions.defaults());
        final var range = partitioner.partitionRange(DocumentSource.bytes(chain(), "chain.pdf"),
                PartitionOptions.builder().build(), new PdfPageRange(1, 2), PdfBudget.unlimited());
        final byte[] encoded = codec.write(range);
        assertEquals(range, PdfRangeResultJson.create(encoded.length).read(encoded));
        final var small = PdfRangeResultJson.create(encoded.length - 1);
        assertThrows(IllegalArgumentException.class, () -> small.read(encoded));
        assertThrows(IllegalArgumentException.class, () -> small.read(new ByteArrayInputStream(encoded)));
        final long[] served = new long[1];
        final var endless = new java.io.InputStream() {
            @Override
            public int read() {
                served[0]++;
                return 'a';
            }

            @Override
            public int read(final byte[] bytes, final int offset, final int length) {
                if (served[0] == 0) {
                    bytes[offset] = '"';
                    served[0]++;
                    return 1;
                }
                java.util.Arrays.fill(bytes, offset, offset + length, (byte) 'a');
                served[0] += length;
                return length;
            }
        };
        assertThrows(IllegalArgumentException.class, () -> PdfRangeResultJson.create(100_000).read(endless));
        assertTrue(served[0] < 200_000, "read " + served[0] + " bytes");
        assertEquals(PdfRangeResultJson.DEFAULT_MAX_ENCODED_BYTES, 256L * 1024 * 1024);
        assertThrows(IllegalArgumentException.class, () -> PdfRangeResultJson.create(0));
    }

    /**
     * Malformed stored evidence fails with IllegalArgumentException, from the codec and from the value type.
     * @throws Exception if partitioning fails
     */
    @Test
    void malformedEvidenceIsRejected() throws Exception {
        @SuppressWarnings({"unchecked", "rawtypes"}) final List<Double> columns = (List) List.of(1.0, "x");
        assertThrows(IllegalArgumentException.class,
                () -> new PdfTableBoundary.Evidence(1, 1, Map.of(), "s", columns, List.of("a", "b"), 0, 1));
        @SuppressWarnings({"unchecked", "rawtypes"}) final List<String> header = (List) List.of("a", 2);
        assertThrows(IllegalArgumentException.class,
                () -> new PdfTableBoundary.Evidence(1, 1, Map.of(), "s", List.of(0.0, 1.0), header, 0, 1));
        final var options = PdfOptions.defaults().withTableContinuation(PdfOptions.TableContinuation.REPEATED_HEADER);
        final var range = PdfPartitioner.create(options).partitionRange(DocumentSource.bytes(chain(), "chain.pdf"),
                PartitionOptions.builder().build(), new PdfPageRange(1, 2), PdfBudget.unlimited());
        assertNotNull(range.lastPageTable());
        final String json = new String(codec.write(range), java.nio.charset.StandardCharsets.UTF_8);
        final int columnsAt = json.lastIndexOf("\"columns\":[");
        final String broken = json.substring(0, columnsAt) + "\"columns\":[\"x\","
                              + json.substring(columnsAt + "\"columns\":[".length());
        assertThrows(IllegalArgumentException.class,
                () -> codec.read(broken.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    /**
     * Integer fields reject fractions and values outside their type instead of truncating them, so a malformed result
     * cannot pass as another version, page or coverage.
     * @throws Exception if partitioning fails
     */
    @Test
    void integerFieldsRejectFractionsAndOverflow() throws Exception {
        final var options = PdfOptions.defaults().withTableContinuation(PdfOptions.TableContinuation.REPEATED_HEADER);
        final var range = PdfPartitioner.create(options).partitionRange(DocumentSource.bytes(chain(), "chain.pdf"),
                PartitionOptions.builder().build(), new PdfPageRange(1, 2), PdfBudget.unlimited());
        assertNotNull(range.lastPageTable());
        final var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        final byte[] encoded = codec.write(range);
        final java.util.function.Function<java.util.function.Consumer<tools.jackson.databind.node.ObjectNode>, byte[]>
                edited = edit -> {
                    final var root = (tools.jackson.databind.node.ObjectNode) mapper.readTree(encoded);
                    edit.accept(root);
                    return mapper.writeValueAsBytes(root);
                };
        assertEquals(range, codec.read(edited.apply(root -> root.put("formatVersion", range.formatVersion()))));
        final var malformed = new ArrayList<byte[]>();
        for (final String field : List.of("formatVersion", "sourceBytes", "documentPages", "first", "last")) {
            malformed.add(edited.apply(root -> root.put(field, 1.9)));
            malformed.add(edited.apply(root -> root.put(field, 1.0)));
            malformed.add(edited.apply(root -> root.put(field, 4_294_967_297L)));
            malformed.add(edited.apply(root -> root.put(field, "1")));
        }
        final java.util.function.Function<tools.jackson.databind.node.ObjectNode, tools.jackson.databind.node.ObjectNode>
                page = root -> (tools.jackson.databind.node.ObjectNode) root.get("pages").get(0);
        malformed.add(edited.apply(root -> page.apply(root).put("pageNumber", 1.9)));
        malformed.add(edited.apply(root -> page.apply(root).put("pageNumber", 4_294_967_297L)));
        malformed.add(edited.apply(root -> page.apply(root).putObject("usage").put("ELEMENTS", 1.5)));
        malformed.add(edited.apply(
                root -> page.apply(root).putObject("usage").put("ELEMENTS", new BigInteger("9223372036854775808"))));
        malformed.add(edited.apply(
                root -> ((tools.jackson.databind.node.ObjectNode) root.get("lastPageTable")).put("pageNumber", 2.5)));
        malformed.add(edited.apply(root -> ((tools.jackson.databind.node.ObjectNode) root.get("lastPageTable"))
                                                   .put("pageNumber", 4_294_967_298L)));
        for (final byte[] json : malformed) {
            assertThrows(IllegalArgumentException.class, () -> codec.read(json),
                    () -> new String(json, java.nio.charset.StandardCharsets.UTF_8).substring(0, 160));
        }
        final var usage = edited.apply(
                root -> page.apply(root).putObject("usage").put("ELEMENTS", new BigInteger("9223372036854775807")));
        assertEquals(Long.MAX_VALUE, codec.read(usage).pages().get(0).usage().get(PdfBudget.Kind.ELEMENTS));
    }

    /**
     * Evidence compares by value after a store changed number types.
     */
    @Test
    void evidenceNormalizesNumberTypes() {
        final var original = new PdfTableBoundary.Evidence(500, 800, Map.of("pdf_page_rotation", 90), "ruled",
                List.of(40.0, 400.0), List.of("Item", "Qty"), 40, 760);
        @SuppressWarnings({"unchecked", "rawtypes"})
        final List<Double> columns = (List) List.of(new BigDecimal("40"), 400L);
        final var stored = new PdfTableBoundary.Evidence(500, 800, Map.of("pdf_page_rotation", new BigDecimal("90.0")),
                "ruled", columns, List.of("Item", "Qty"), 40, 760);
        assertEquals(original, stored);
        assertEquals(Double.class, stored.transforms().get("pdf_page_rotation").getClass());
    }

    /**
     * Authors eight pages: a table chain across pages 1 to 6, a narrative page and a second short chain.
     * @return PDF bytes
     * @throws IOException if authoring fails
     */
    private static byte[] chain() throws IOException {
        final int[][] specs = {{400, 760}, {40, 760}, {40, 760}, {40, 760}, {40, 760}, {40, 400}, {0, 0}, {400, 760}};
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            for (int index = 0; index < specs.length; index++) {
                final var page = new PDPage(new PDRectangle(500, 800));
                document.addPage(page);
                try (final var content = new PDPageContentStream(document, page)) {
                    if (specs[index][1] == 0) {
                        text(content, "Narrative page " + (index + 1), 40, 400);
                        continue;
                    }
                    final int top = 800 - specs[index][0];
                    final int split = top - 40;
                    final int bottom = 800 - specs[index][1];
                    for (final int y : List.of(top, split, bottom)) {
                        content.moveTo(40, y);
                        content.lineTo(400, y);
                    }
                    for (final int x : List.of(40, 220, 400)) {
                        content.moveTo(x, bottom);
                        content.lineTo(x, top);
                    }
                    content.stroke();
                    text(content, "Item", 45, top - 25);
                    text(content, "Qty", 225, top - 25);
                    text(content, "Alpha " + index, 45, split - 25);
                    text(content, String.valueOf(10 + index), 225, split - 25);
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    /**
     * Shows one Helvetica run.
     * @param content page stream
     * @param text visible text
     * @param x left
     * @param y baseline
     * @throws IOException if drawing fails
     */
    private static void text(final PDPageContentStream content, final String text, final float x, final float y)
            throws IOException {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        content.newLineAtOffset(x, y);
        content.showText(text);
        content.endText();
    }
}
