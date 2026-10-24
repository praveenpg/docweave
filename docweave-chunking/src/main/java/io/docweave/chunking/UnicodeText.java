package io.docweave.chunking;

/**
 * Unicode code-point counting, slicing, stripping, and whitespace normalization utilities for chunking.
 */
final class UnicodeText {
    /** Prevents instantiation of this static utility class. */
    private UnicodeText() {
    }

    /**
     * Returns the number of Unicode code points in {@code value}.
     *
     * @param value string to measure
     * @return Unicode code-point count
     */
    static int length(final String value) {
        return value.codePointCount(0, value.length());
    }

    /**
     * Extracts the substring corresponding to the half-open Unicode code-point interval {@code [start, end)}.
     *
     * @param value source string
     * @param start inclusive start code-point index
     * @param end exclusive end code-point index
     * @return substring covering {@code [start, end)}
     */
    static String slice(final String value, int start, int end) {
        return value.substring(value.offsetByCodePoints(0, start), value.offsetByCodePoints(0, end));
    }

    /**
     * Returns up to the last {@code count} Unicode code points of {@code value}.
     *
     * @param value source string
     * @param count maximum number of trailing code points to return
     * @return trailing slice of {@code value}
     */
    static String tail(final String value, final int count) {
        final int length = length(value);
        return slice(value, Math.max(0, length - count), length);
    }

    /**
     * Checks whether {@code point} is a whitespace code point according to Python {@code str.isspace()} semantics
     * (including {@code NBSP} and {@code U+0085} in addition to Java whitespace).
     *
     * @param point Unicode code point to test
     * @return {@code true} if {@code point} is treated as whitespace
     */
    // Python str.isspace includes NBSP and U+0085 in addition to Java whitespace.
    static boolean space(final int point) {
        return Character.isWhitespace(point) || Character.isSpaceChar(point) || point == 0x85;
    }

    /**
     * Strips leading and trailing Unicode whitespace from {@code value}.
     *
     * @param value string to strip
     * @return stripped string
     */
    static String strip(final String value) {
        return trim(value, true, true);
    }

    /**
     * Strips leading Unicode whitespace from {@code value}.
     *
     * @param value string to left-strip
     * @return string with leading whitespace removed
     */
    static String leftStrip(final String value) {
        return trim(value, true, false);
    }

    /**
     * Strips trailing Unicode whitespace from {@code value}.
     *
     * @param value string to right-strip
     * @return string with trailing whitespace removed
     */
    static String rightStrip(final String value) {
        return trim(value, false, true);
    }

    /**
     * Trims leading and/or trailing Unicode whitespace from {@code value}.
     *
     * @param value string to trim
     * @param left {@code true} to remove leading whitespace
     * @param right {@code true} to remove trailing whitespace
     * @return trimmed substring
     */
    private static String trim(final String value, final boolean left, final boolean right) {
        int start = 0, end = value.length();
        // Advance start and retreat end by full Unicode code points while Python-compatible whitespace is matched.
        while (left && start < end && space(value.codePointAt(start))) {
            if ((start & 1023) == 0) {
                ChunkCancellation.check();
            }
            start += Character.charCount(value.codePointAt(start));
        }
        while (right && end > start && space(value.codePointBefore(end))) {
            if ((end & 1023) == 0) {
                ChunkCancellation.check();
            }
            end -= Character.charCount(value.codePointBefore(end));
        }
        return value.substring(start, end);
    }

    /**
     * Collapses contiguous runs of Unicode whitespace into single ASCII spaces and trims leading/trailing whitespace.
     *
     * @param value input string to normalize
     * @return whitespace-normalized string
     */
    static String normalize(final String value) {
        final var result = new StringBuilder();
        boolean pendingSpace = false;
        ChunkCancellation.check();
        // Collapse interior whitespace runs to a single space while omitting leading and trailing whitespace.
        for (int offset = 0, visited = 0; offset < value.length(); visited++) {
            if ((visited & 1023) == 0) {
                ChunkCancellation.check();
            }
            final int point = value.codePointAt(offset);
            offset += Character.charCount(point);
            if (space(point)) {
                pendingSpace = !result.isEmpty();
            } else {
                if (pendingSpace) {
                    result.append(' ');
                }
                result.appendCodePoint(point);
                pendingSpace = false;
            }
        }
        return result.toString();
    }
}
