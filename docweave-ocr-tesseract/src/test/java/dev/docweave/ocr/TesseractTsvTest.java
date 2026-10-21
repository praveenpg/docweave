package dev.docweave.ocr;

import dev.docweave.partition.PartitionException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class TesseractTsvTest {
    private static final String HEADER =
            "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n";

    @Test
    void groupingUsesHierarchyAndPreservesEncounterOrder() throws Exception {
        final var input = HEADER + word(2, "later", 30) + word(1, "first", 10) + word(2, "second", 60);
        final var lines = parse(input);
        assertEquals(java.util.List.of("later second", "first"), lines.stream().map(line -> line.text()).toList());
        assertEquals(30, lines.getFirst().left());
        assertEquals(70, lines.getFirst().right());
        assertTrue(parse(HEADER).isEmpty());
        assertEquals("日本語", parse(HEADER + word(1, "日本語", 10)).getFirst().text());
    }

    @Test
    void malformedRowsEncodingAndOverflowingBoundsFail() {
        for (final var row : java.util.List.of("invalid", "5\t1", word(1, "outside", Integer.MAX_VALUE),
                     word(1, "negative", -1), word(1, "x", 10).replace("5\t1\t", "5\t2\t"),
                     word(1, "x", 10).replace("\t10\t10\t10\t10\t", "\tNaN\t10\t10\t10\t"))) {
            assertThrows(IOException.class, () -> parse(HEADER + row));
        }
        assertThrows(IOException.class,
                ()
                        -> TesseractTsv.parse(new byte[] {(byte) 0xc0}, 100, 100, TesseractOptions.defaults(),
                                new OcrDeadline(java.time.Duration.ofSeconds(1))));
    }

    @Test
    void outputBudgetsIncludeJoinedSpacesAndDistinctLines() {
        final var base = TesseractOptions.defaults();
        final var policy = new TesseractOptions(
                base.executable(), null, base.languages(), base.timeout(), 1, 10000, 10000, 10000, 1, 3);
        for (final var input : java.util.List.of(HEADER + word(1, "abcd", 0),
                     HEADER + word(1, "ab", 0) + word(1, "c", 10), HEADER + word(1, "a", 0) + word(2, "b", 10))) {
            assertEquals(PartitionException.Code.OCR_LIMIT,
                    assertThrows(PartitionException.class,
                            ()
                                    -> TesseractTsv.parse(input.getBytes(StandardCharsets.UTF_8), 100, 100, policy,
                                            new OcrDeadline(policy.timeout())))
                            .code());
        }
    }

    @Test
    void detailedOutputRetainsActualWordBoxesAndOriginalLineProjection() throws Exception {
        final var input = HEADER + word(2, "later", 30) + word(1, "first", 10) + word(2, "second", 60);
        final var policy = TesseractOptions.defaults();
        final var detailed = TesseractTsv.parseWords(
                input.getBytes(StandardCharsets.UTF_8), 100, 100, policy, new OcrDeadline(policy.timeout()));
        assertEquals(parse(input), detailed.stream().map(line -> line.line()).toList());
        assertEquals(java.util.List.of("later", "second"),
                detailed.getFirst().words().stream().map(word -> word.text()).toList());
        assertEquals(30, detailed.getFirst().words().getFirst().left());
        assertEquals(40, detailed.getFirst().words().getFirst().right());
        assertEquals(60, detailed.getFirst().words().getLast().left());
        assertThrows(UnsupportedOperationException.class, () -> detailed.clear());
        assertThrows(UnsupportedOperationException.class, () -> detailed.getFirst().words().clear());
        assertThrows(IOException.class,
                ()
                        -> TesseractTsv.parseWords(
                                new byte[] {(byte) 0xc0}, 100, 100, policy, new OcrDeadline(policy.timeout())));
        final var limited = new TesseractOptions(
                policy.executable(), null, policy.languages(), policy.timeout(), 1, 10000, 10000, 10000, 1, 3);
        assertEquals(PartitionException.Code.OCR_LIMIT,
                assertThrows(PartitionException.class,
                        ()
                                -> TesseractTsv.parseWords(input.getBytes(StandardCharsets.UTF_8), 100, 100, limited,
                                        new OcrDeadline(limited.timeout())))
                        .code());
    }

    private static java.util.List<dev.docweave.partition.PdfOcrProvider.Line> parse(final String input)
            throws IOException {
        final var policy = TesseractOptions.defaults();
        return TesseractTsv.parse(
                input.getBytes(StandardCharsets.UTF_8), 100, 100, policy, new OcrDeadline(policy.timeout()));
    }

    private static String word(final int line, final String text, final int left) {
        return "5\t1\t1\t1\t" + line + "\t1\t" + left + "\t10\t10\t10\t90\t" + text + "\n";
    }
}
