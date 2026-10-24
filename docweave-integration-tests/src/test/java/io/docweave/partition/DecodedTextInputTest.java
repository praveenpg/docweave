package io.docweave.partition;

import io.docweave.DocWeave;
import org.junit.jupiter.api.Test;

import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class DecodedTextInputTest {
    @Test
    void utf8MeasurementMatchesRealEncodingAcrossUnicodeAndExactBudgets() throws Exception {
        final var random = new Random(719);
        for (int trial = 0; trial < 200; trial++) {
            final var value = new StringBuilder("a");
            for (int i = 0; i < 100; i++) {
                int point;
                do {
                    point = random.nextInt(0x110000);
                } while (point >= 0xd800 && point <= 0xdfff);
                value.appendCodePoint(point);
            }
            final String text = value.toString();
            final int bytes = text.getBytes(StandardCharsets.UTF_8).length;
            assertEquals(bytes, DecodedTextInput.measure(text, bytes));
            assertEquals(PartitionException.Code.INPUT_TOO_LARGE,
                    assertThrows(PartitionException.class, () -> DecodedTextInput.measure(text, bytes - 1)).code());
        }
        assertEquals(0, DecodedTextInput.measure("", 1));
        assertEquals(3, DecodedTextInput.measure("\ufeff", 3));
    }

    @Test
    void malformedSurrogatesAndLimitErrorPrecedenceAreRetained() {
        for (final String value : List.of("\ud800", "a\udc00", "\ud800x", "éé\ud800")) {
            assertEquals(PartitionException.Code.INVALID_ENCODING,
                    assertThrows(PartitionException.class, () -> DecodedTextInput.measure(value, value.length()))
                            .code());
        }
        assertEquals(PartitionException.Code.INPUT_TOO_LARGE,
                assertThrows(PartitionException.class, () -> DecodedTextInput.measure("ab\ud800", 2)).code());
    }

    @Test
    void previewIsBoundedAndMatchesHistoricalBytesAtSurrogateAndBomBoundaries() throws Exception {
        for (final String prefix : List.of("", "\ufeff", "é", "漢")) {
            for (int boundary = 8190; boundary < 8200; boundary++) {
                final String text = prefix + "a".repeat(boundary) + "🧵".repeat(9000);
                final byte[] full = text.getBytes(StandardCharsets.UTF_8);
                assertArrayEquals(Arrays.copyOf(full, Math.min(8196, full.length)), DecodedTextInput.preview(text));
            }
        }
    }

    @Test
    void loaderReusesOriginalTextAndRetainsBomFootprintAndCharsetPolicy() throws Exception {
        final String text = "already decoded café 🧵".repeat(1000);
        final var options = PartitionOptions.builder().charset(StandardCharsets.UTF_16LE).build();
        final var loaded = new SourceLoader().load(DocumentSource.text(text, "input.txt"), options);
        assertSame(text, loaded.text());
        assertEquals(text.getBytes(StandardCharsets.UTF_8).length, loaded.bytes());
        assertEquals("UTF-8", loaded.encoding());
        final var withBom = new SourceLoader().load(DocumentSource.text("\ufeff" + text, "input.txt"), options);
        assertEquals(text, withBom.text());
        assertEquals(loaded.bytes() + 3, withBom.bytes());
        assertNull(loaded.binary());
    }

    @Test
    void textAndUtf8BytesProduceTheSameResultsForEveryTextFormat() throws Exception {
        final var engine = DocWeave.create();
        final String[][] cases = {{"sample.txt", "Title\n\nCafé 🧵."}, {"sample.html", "<h1>T</h1><p>Café 🧵.</p>"},
                {"sample.md", "# Title\n\nCafé 🧵."}, {"sample.csv", "name,value\nCafé,🧵"},
                {"sample.tsv", "name\tvalue\nCafé\t🧵"}, {"sample.xml", "<?xml version='1.0'?><r>Café 🧵.</r>"}};
        for (final var item : cases) {
            for (final String bom : List.of("", "\ufeff")) {
                final String text = bom + item[1];
                final var actual = engine.partition(DocumentSource.text(text, item[0]));
                final var expected =
                        engine.partition(DocumentSource.bytes(text.getBytes(StandardCharsets.UTF_8), item[0]));
                assertEquals(expected.elements(), actual.elements());
                assertEquals(expected.format(), actual.format());
                assertEquals(expected.encoding(), actual.encoding());
                assertEquals(expected.inputBytes(), actual.inputBytes());
                assertEquals(expected.warnings(), actual.warnings());
            }
        }
    }

    @Test
    void routingWarningsBinaryRejectionAndLateControlsRemainUnchanged() throws Exception {
        final var engine = DocWeave.create();
        final var mismatch = engine.partition(DocumentSource.text("<p>text</p>", "sample.txt"));
        assertEquals(DocumentFormat.HTML, mismatch.format());
        assertEquals(PartitionResult.WarningCode.FORMAT_MISMATCH, mismatch.warnings().getFirst().code());
        for (final String text : List.of("%PDF-1.7", "PK\u0003\u0004", "a".repeat(20000) + "\0")) {
            assertEquals(PartitionException.Code.UNSUPPORTED_FORMAT,
                    assertThrows(PartitionException.class, () -> engine.partition(DocumentSource.text(text))).code());
        }
        assertEquals(PartitionException.Code.UNSUPPORTED_FORMAT,
                assertThrows(
                        PartitionException.class, () -> engine.partition(DocumentSource.text("text", "input.docx")))
                        .code());
    }

    @Test
    void measurementObservesInterruptionDuringTraversalAndPreservesTheFlag() throws Exception {
        final CharSequence text = new CharSequence() {
            public int length() {
                return 10000;
            }
            public char charAt(final int index) {
                if (index == 1500) {
                    Thread.currentThread().interrupt();
                }
                return 'a';
            }
            public CharSequence subSequence(final int start, final int end) {
                throw new AssertionError("No copy needed");
            }
        };
        try {
            assertThrows(InterruptedIOException.class, () -> DecodedTextInput.measure(text, 10000));
            assertTrue(Thread.currentThread().isInterrupted());
            assertThrows(InterruptedIOException.class, () -> DecodedTextInput.measure("", 1));
            assertThrows(InterruptedIOException.class, () -> DecodedTextInput.preview(""));
        } finally {
            Thread.interrupted();
        }
        assertEquals(4, DecodedTextInput.measure("🧵", 4));
    }
}
