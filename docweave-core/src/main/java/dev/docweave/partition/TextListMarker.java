package dev.docweave.partition;

import java.io.InterruptedIOException;

/**
 * Allocation-free recognition of the existing bullet/numbered-list heuristics.
 * Whitespace is deliberately the regex default ASCII {@code \\s}, not general Unicode spacing;
 * decimal digits and letter markers are ASCII. Marker whitespace is greedy but must leave at
 * least one content character, matching the former DOTALL expression even for whitespace-only
 * tails. The returned bullet offset supports cleaning without a Matcher/group allocation.
 */
final class TextListMarker {
    /** Recognized leading bullet characters for plain-text list item detection. */
    private static final String BULLETS = "•●▪◦‣⁃*+-";

    /** Prevents instantiation of this static utility class. */
    private TextListMarker() {
    }

    /**
     * Checks whether {@code text} starts with a recognized bullet or numbered/lettered list marker.
     *
     * @param text normalized paragraph text to inspect
     * @return {@code true} if {@code text} matches list item marker heuristics
     * @throws InterruptedIOException if the thread is interrupted
     */
    static boolean isList(final String text) throws InterruptedIOException {
        return bulletContentStart(text) >= 0 || numbered(text);
    }

    /**
     * Returns the UTF-16 start index of list item content following a leading bullet marker, or {@code -1} if none.
     *
     * @param text normalized paragraph text to inspect
     * @return content start index after bullet and marker whitespace, or {@code -1}
     * @throws InterruptedIOException if the thread is interrupted
     */
    static int bulletContentStart(final String text) throws InterruptedIOException {
        ElementCollector.checkInterrupted();
        if (text.isEmpty() || BULLETS.indexOf(text.charAt(0)) < 0) {
            return -1;
        }
        return contentStart(text, 1);
    }

    /**
     * Checks whether {@code text} starts with an ASCII numeric or single-letter list marker followed by {@code '.'} or
     * {@code ')'}.
     *
     * @param text normalized paragraph text to inspect
     * @return {@code true} if {@code text} matches a numbered or lettered list marker
     * @throws InterruptedIOException if the thread is interrupted
     */
    private static boolean numbered(final String text) throws InterruptedIOException {
        if (text.isEmpty()) {
            return false;
        }
        int markerEnd;
        final char first = text.charAt(0);
        if (first >= '0' && first <= '9') {
            markerEnd = 1;
            while (markerEnd < text.length()) {
                if ((markerEnd & 1023) == 0) {
                    ElementCollector.checkInterrupted();
                }
                final char value = text.charAt(markerEnd);
                if (value < '0' || value > '9') {
                    break;
                }
                markerEnd++;
            }
        } else if (first >= 'a' && first <= 'z' || first >= 'A' && first <= 'Z') {
            markerEnd = 1;
        } else {
            return false;
        }
        if (markerEnd == text.length() || text.charAt(markerEnd) != '.' && text.charAt(markerEnd) != ')') {
            return false;
        }
        return contentStart(text, markerEnd + 1) >= 0;
    }

    /**
     * Scans required ASCII whitespace starting at {@code start} and returns the content start offset, or {@code -1}.
     *
     * @param text source text
     * @param start index immediately following the list marker punctuation
     * @return content start index, or {@code -1} if no whitespace or trailing content is present
     * @throws InterruptedIOException if the thread is interrupted
     */
    private static int contentStart(final String text, final int start) throws InterruptedIOException {
        if (start >= text.length() || !whitespace(text.charAt(start))) {
            return -1;
        }
        int end = start;
        while (end < text.length() && whitespace(text.charAt(end))) {
            if (((end - start) & 1023) == 0) {
                ElementCollector.checkInterrupted();
            }
            end++;
        }
        ElementCollector.checkInterrupted();
        if (end == text.length()) {
            return end - start >= 2 ? end - 1 : -1;
        }
        return end;
    }

    /**
     * Checks whether {@code value} is an ASCII regex {@code \s} whitespace character.
     *
     * @param value character to test
     * @return {@code true} if {@code value} is ASCII whitespace
     */
    private static boolean whitespace(final char value) {
        return value == ' ' || value == '\t' || value == '\n' || value == '\u000b' || value == '\f' || value == '\r';
    }
}
