package io.docweave.partition;

import io.docweave.DocWeave;
import org.junit.jupiter.api.Test;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Deterministic interruption during reads avoids timing-sensitive cancellation tests. */
class TextScanCancellationTest {
    @FunctionalInterface
    private interface Scan {
        void run(final CharSequence value) throws Exception;
    }

    private static final class InterruptingText implements CharSequence {
        private final String value;
        private final int trigger;
        private int reads;

        InterruptingText(final String value, final int trigger) {
            this.value = value;
            this.trigger = trigger;
        }

        public int length() {
            return value.length();
        }

        public char charAt(final int index) {
            if (++reads == trigger) {
                Thread.currentThread().interrupt();
            }
            return value.charAt(index);
        }

        public CharSequence subSequence(final int start, final int end) {
            return value.subSequence(start, end);
        }
    }

    private void interruptedDuringScan(final String value, final Scan scan) {
        final var text = new InterruptingText(value, 2048);
        try {
            assertThrows(InterruptedIOException.class, () -> scan.run(text));
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(text.reads <= 4096, "Scan should stop before reading the full input");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void unicodeScansStopDuringTraversalWithoutClearingTheFlag() throws Exception {
        interruptedDuringScan(" \u2003".repeat(10000), TextSupport::normalize);
        interruptedDuringScan(" \u2003".repeat(10000), TextSupport::blank);
        interruptedDuringScan("🧵".repeat(10000), value -> TextSupport.points(value, 0, value.length()));
        interruptedDuringScan("a".repeat(20000), TextSupport::validateControls);
        assertEquals("🧵 café", TextSupport.normalize(" \u2003🧵\u0085café  "));
        assertEquals(3, TextSupport.points("🧵ab", 0, 4));
        assertEquals(3, TextSupport.advance("🧵ab", 0, 2));
    }

    @Test
    void longSingleLineIsInterruptibleAndFirstLineIsLazy() throws Exception {
        interruptedDuringScan("a".repeat(20000), value -> new TxtLineCursor(value).next());
        final var source = new InterruptingText("first\r\n"
                                                        + "z".repeat(20000),
                100);
        assertEquals("first", new TxtLineCursor(source).next());
        assertTrue(source.reads < 100);
        assertFalse(Thread.currentThread().isInterrupted());
    }

    @Test
    void lineCursorMatchesHistoricalRegexIncludingTrailingEmptyLines() throws Exception {
        final var inputs = new ArrayList<>(List.of("", "\r\n", "\r\r\n", "a\n", "🧵\u2028b\u0085\u2029"));
        final var random = new Random(417);
        final String[] units = {"a", "🧵", "\r", "\n", "\r\n", "\u000b", "\f", "\u0085", "\u2028", "\u2029"};
        for (int trial = 0; trial < 100; trial++) {
            final var text = new StringBuilder();
            for (int i = 0; i < 100; i++) {
                text.append(units[random.nextInt(units.length)]);
            }
            inputs.add(text.toString());
        }
        for (final String input : inputs) {
            final var cursor = new TxtLineCursor(input);
            final var lines = new ArrayList<String>();
            for (String line; (line = cursor.next()) != null;) {
                lines.add(line);
            }
            assertEquals(Arrays.asList(input.split("\\R", -1)), lines);
            assertNull(cursor.next());
        }
    }

    @Test
    void emptyAndExhaustedScansStillObserveInterruption() throws Exception {
        final var cursor = new TxtLineCursor("");
        assertEquals("", cursor.next());
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, cursor::next);
            assertThrows(InterruptedIOException.class, () -> TextSupport.normalize(""));
            assertThrows(InterruptedIOException.class, () -> TextSupport.blank(""));
            assertThrows(InterruptedIOException.class, () -> TextSupport.points("", 0, 0));
            assertThrows(InterruptedIOException.class, () -> TextSupport.advance("", 0, 0));
            assertThrows(InterruptedIOException.class, () -> TextSupport.validateControls(""));
            assertThrows(InterruptedIOException.class, () -> TextSupport.classify(""));
            assertThrows(InterruptedIOException.class, () -> TextSupport.cleanBullet(""));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals("reusable", TextSupport.normalize(" reusable "));
    }

    @Test
    void controlValidationAndPointRangesRetainTheirBoundaries() throws Exception {
        TextSupport.validateControls("🧵\t\n\r\f\u000b\u0085\u2028\u2029\u001f");
        for (final String value : List.of("\0", "text\u0001", "🧵\u0007")) {
            final var error = assertThrows(PartitionException.class, () -> TextSupport.validateControls(value));
            assertEquals(PartitionException.Code.UNSUPPORTED_FORMAT, error.code());
        }
        assertEquals(1, TextSupport.points("🧵", 0, 1));
        assertEquals(1, TextSupport.points("🧵", 1, 2));
        assertEquals(1, TextSupport.points("🧵", 0, 2));
    }

    @Test
    void allTxtBreaksAndLongUnicodeRetainPublicPartitionBehavior() throws Exception {
        final var processor = DocWeave.create();
        final var options = PartitionOptions.builder()
                                    .paragraphMode(PartitionOptions.ParagraphMode.LINE_BREAKS)
                                    .txtOptions(new TxtOptions(0, 2))
                                    .build();
        final var result = processor.partition(
                DocumentSource.text("🧵🧵🧵\r\na\u000bb\fc\u0085d\u2028e\u2029", "lines.txt"), options);
        assertEquals(List.of("🧵🧵", "🧵", "a", "b", "c", "d", "e"),
                result.elements().stream().map(element -> element.text()).toList());
    }
}
