package dev.docweave.partition;

import dev.docweave.core.ElementType;

import java.io.InterruptedIOException;

/** Shared Unicode whitespace normalization and deliberately simple TXT category heuristics. */
final class TextSupport {
    /** Prevents instantiation of this static utility class. */
    private TextSupport() {
    }

    /**
     * Checks whether {@code point} is a Unicode whitespace code point (including {@code NBSP} and {@code U+0085}).
     *
     * @param point Unicode code point to test
     * @return {@code true} if {@code point} is whitespace
     */
    static boolean space(final int point) {
        return Character.isWhitespace(point) || Character.isSpaceChar(point) || point == 0x85;
    }

    /**
     * Collapses contiguous whitespace runs in {@code value} to single ASCII spaces and trims leading/trailing space.
     *
     * @param value character sequence to normalize
     * @return normalized string
     * @throws InterruptedIOException if the thread is interrupted
     */
    static String normalize(final CharSequence value) throws InterruptedIOException {
        ElementCollector.checkInterrupted();
        final var text = new StringBuilder();
        boolean pending = false;
        int visited = 0;
        for (int offset = 0; offset < value.length();) {
            if ((visited++ & 1023) == 0) {
                ElementCollector.checkInterrupted();
            }
            final int point = Character.codePointAt(value, offset);
            offset += Character.charCount(point);
            if (space(point)) {
                pending = !text.isEmpty();
            } else {
                if (pending) {
                    text.append(' ');
                }
                text.appendCodePoint(point);
                pending = false;
            }
        }
        ElementCollector.checkInterrupted();
        return text.toString();
    }

    /**
     * Checks whether {@code value} is empty or consists solely of Unicode whitespace code points.
     *
     * @param value character sequence to inspect
     * @return {@code true} if {@code value} contains no non-whitespace code points
     * @throws InterruptedIOException if the thread is interrupted
     */
    static boolean blank(final CharSequence value) throws InterruptedIOException {
        ElementCollector.checkInterrupted();
        int visited = 0;
        for (int offset = 0; offset < value.length();) {
            if ((visited++ & 1023) == 0) {
                ElementCollector.checkInterrupted();
            }
            final int point = Character.codePointAt(value, offset);
            offset += Character.charCount(point);
            if (!space(point)) {
                ElementCollector.checkInterrupted();
                return false;
            }
        }
        ElementCollector.checkInterrupted();
        return true;
    }

    /**
     * Counts Unicode points without clearing interruption or allocating a point array.
     *
     * @param value character sequence to measure
     * @param start inclusive UTF-16 start index
     * @param end exclusive UTF-16 end index
     * @return Unicode code-point count in {@code value[start..end)}
     * @throws InterruptedIOException if the thread is interrupted
     */
    static int points(final CharSequence value, final int start, int end) throws InterruptedIOException {
        ElementCollector.checkInterrupted();
        int count = 0;
        for (int offset = start; offset < end; count++) {
            if ((count & 1023) == 0) {
                ElementCollector.checkInterrupted();
            }
            final char first = value.charAt(offset++);
            if (Character.isHighSurrogate(first) && offset < end && Character.isLowSurrogate(value.charAt(offset))) {
                offset++;
            }
        }
        ElementCollector.checkInterrupted();
        return count;
    }

    /**
     * Advances within a known point count, preserving surrogate pairs and polling long fragments.
     *
     * @param value source string
     * @param start starting UTF-16 index
     * @param count number of Unicode code points to advance
     * @return resulting UTF-16 index after advancing {@code count} code points
     * @throws InterruptedIOException if the thread is interrupted
     */
    static int advance(final String value, final int start, int count) throws InterruptedIOException {
        ElementCollector.checkInterrupted();
        int offset = start;
        for (int visited = 0; visited < count; visited++) {
            if ((visited & 1023) == 0) {
                ElementCollector.checkInterrupted();
            }
            offset += Character.charCount(value.codePointAt(offset));
        }
        ElementCollector.checkInterrupted();
        return offset;
    }

    /**
     * Rejects binary controls after strict decoding; whitespace controls remain valid text.
     *
     * @param value decoded text to validate
     * @throws java.io.IOException if binary control characters are found or the thread is interrupted
     */
    static void validateControls(final CharSequence value) throws java.io.IOException {
        ElementCollector.checkInterrupted();
        int visited = 0;
        for (int offset = 0; offset < value.length();) {
            if ((visited++ & 1023) == 0) {
                ElementCollector.checkInterrupted();
            }
            final int point = Character.codePointAt(value, offset);
            offset += Character.charCount(point);
            if (point == 0 || point < 32 && !space(point)) {
                ElementCollector.checkInterrupted();
                throw new PartitionException(
                        PartitionException.Code.UNSUPPORTED_FORMAT, "Input contains binary control characters");
            }
        }
        ElementCollector.checkInterrupted();
    }

    /**
     * Strips a leading bullet marker and its following whitespace from {@code value} if present.
     *
     * @param value normalized text string
     * @return text with leading bullet prefix removed, or {@code value} unchanged
     * @throws InterruptedIOException if the thread is interrupted
     */
    static String cleanBullet(final String value) throws InterruptedIOException {
        ElementCollector.checkInterrupted();
        final int start = TextListMarker.bulletContentStart(value);
        final String result = start >= 0 ? value.substring(start) : value;
        ElementCollector.checkInterrupted();
        return result;
    }

    /**
     * Preserves {@code String.split(" ")} counting, including leading/interior empty words and trimmed tails.
     *
     * @param value normalized text string
     * @param limit maximum allowed space-separated words
     * @return {@code true} if {@code value} has at most {@code limit} words
     */
    private static boolean atMostWords(final String value, final int limit) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == ' ') {
            end--;
        }
        if (end == 0) {
            return true;
        }
        int words = 1;
        for (int index = 0; index < end; index++) {
            if (value.charAt(index) == ' ' && ++words > limit) {
                return false;
            }
        }
        return true;
    }

    /**
     * Classifies normalized paragraph text into {@link ElementType#ListItem}, {@link ElementType#UncategorizedText},
     * {@link ElementType#Title}, or {@link ElementType#NarrativeText}.
     *
     * @param value normalized paragraph text
     * @return classified {@link ElementType}
     * @throws InterruptedIOException if the thread is interrupted
     */
    static ElementType classify(final String value) throws InterruptedIOException {
        ElementCollector.checkInterrupted();
        final boolean list = TextListMarker.isList(value);
        ElementCollector.checkInterrupted();
        if (list) {
            return ElementType.ListItem;
        }
        boolean letter = false;
        int visited = 0;
        for (int offset = 0; offset < value.length();) {
            if ((visited++ & 1023) == 0) {
                ElementCollector.checkInterrupted();
            }
            final int point = value.codePointAt(offset);
            offset += Character.charCount(point);
            if (Character.isLetter(point)) {
                letter = true;
                break;
            }
        }
        ElementCollector.checkInterrupted();
        if (!letter) {
            return ElementType.UncategorizedText;
        }
        // Word splitting is only needed for the bounded title candidate.
        final boolean shortText = points(value, 0, value.length()) <= 100;
        final boolean shortWords = shortText && atMostWords(value, 12);
        final boolean sentence = value.endsWith(".") || value.endsWith("!") || value.endsWith("?")
                                 || value.endsWith("。") || value.endsWith("！") || value.endsWith("？");
        if (shortWords && !sentence) {
            return ElementType.Title;
        }
        return ElementType.NarrativeText;
    }
}
