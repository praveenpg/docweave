package dev.docweave.partition;

import org.junit.jupiter.api.Test;

import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class Utf8DigestTest {
    private byte[] expected(final String value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private void compare(final Utf8Digest utf8, final List<String> parts) throws Exception {
        utf8.reset();
        for (final String part : parts) {
            utf8.append(part);
        }
        assertArrayEquals(expected(String.join("", parts)), utf8.finish());
    }

    @Test
    void tinyBuffersPreservePairsReplacementAndEmptyComponentsAcrossAllBoundaries() throws Exception {
        for (final int characters : List.of(2, 3, 7, 256)) {
            for (final int bytes : List.of(4, 5, 17, 1024)) {
                final var utf8 = new Utf8Digest(MessageDigest.getInstance("SHA-256"), characters, bytes);
                for (final var parts : List.of(List.of("", ""), List.of("a", "b"), List.of("\ud83e", "", "\uddf5"),
                             List.of("\ud800", "\ud800", "\udc00", "\udc00"),
                             List.of("漢".repeat(100), "🧵".repeat(100)),
                             List.of("a".repeat(characters - 1) + "\ud83e", "\uddf5tail"), List.of("tail\ud800"))) {
                    compare(utf8, parts);
                }
            }
        }
    }

    @Test
    void seededRawUtf16PartitionsMatchWholeStringReplacementEncoding() throws Exception {
        final var random = new Random(134);
        final var utf8 = new Utf8Digest(MessageDigest.getInstance("SHA-256"), 7, 5);
        for (int trial = 0; trial < 1000; trial++) {
            final var text = new StringBuilder();
            final int length = random.nextInt(300);
            for (int i = 0; i < length; i++) {
                text.append((char) random.nextInt(65536));
            }
            final var parts = new java.util.ArrayList<String>();
            for (int start = 0; start < text.length();) {
                final int end = Math.min(text.length(), start + 1 + random.nextInt(20));
                parts.add(text.substring(start, end));
                start = end;
            }
            compare(utf8, parts);
        }
        // Exhaustive lone UTF-16 code units protect malformed replacement as well as BMP encoding.
        for (int point = 0; point < 65536; point++) {
            compare(utf8, List.of(Character.toString(point)));
        }
    }

    @Test
    void interruptedTraversalPreservesFlagAndResetDiscardsPartialMessage() throws Exception {
        final var utf8 = new Utf8Digest(MessageDigest.getInstance("SHA-256"), 32, 17);
        final int[] visited = {0};
        final CharSequence text = new CharSequence() {
            public int length() {
                return 10000;
            }
            public char charAt(final int index) {
                if (++visited[0] == 512) {
                    Thread.currentThread().interrupt();
                }
                return 'a';
            }
            public CharSequence subSequence(int start, final int end) {
                throw new AssertionError();
            }
        };
        try {
            utf8.append("prefix");
            assertThrows(InterruptedIOException.class, () -> utf8.append(text));
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(visited[0] <= 544);
        } finally {
            utf8.reset();
            Thread.interrupted();
        }
        compare(utf8, List.of("clean", "🧵"));
    }

    @Test
    void sourceFailureAndFinalSurrogateDoNotLeakIntoLaterMessages() throws Exception {
        final var utf8 = new Utf8Digest(MessageDigest.getInstance("SHA-256"), 2, 4);
        final CharSequence broken = new CharSequence() {
            public int length() {
                return 20;
            }
            public char charAt(final int index) {
                if (index == 8) {
                    throw new IllegalStateException("source failed");
                }
                return 'a';
            }
            public CharSequence subSequence(int start, final int end) {
                throw new AssertionError();
            }
        };
        try {
            assertThrows(IllegalStateException.class, () -> utf8.append(broken));
        } finally {
            utf8.reset();
        }
        compare(utf8, List.of("\ud800"));
        compare(utf8, List.of(""));
        compare(utf8, List.of("after failure"));
    }
}
