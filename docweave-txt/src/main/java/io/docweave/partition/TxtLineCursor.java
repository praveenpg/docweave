package io.docweave.partition;

import java.io.InterruptedIOException;

/**
 * Document-local lazy line scan with the same boundaries as regex {@code \\R} with limit -1.
 * CRLF is one delimiter; empty input and trailing delimiters retain an empty final line.
 * Scanning polls interruption every 1,024 UTF-16 units, before copying and at exhaustion.
 * This avoids materializing every line before paragraph processing can begin.
 */
final class TxtLineCursor {
    /** Source character sequence being scanned line by line. */
    private final CharSequence text;
    /** Current UTF-16 scan position within {@link #text}. */
    private int offset;
    /** Whether the final line has already been returned. */
    private boolean exhausted;

    /**
     * Creates a cursor positioned at the start of the given character sequence.
     * @param text source text to scan
     */
    TxtLineCursor(final CharSequence text) {
        this.text = text;
    }

    /**
     * Returns one line, or null after the final line; interruption never clears the thread flag.
     * @return next line without its line-break delimiter, or {@code null} when exhausted
     * @throws InterruptedIOException if the calling thread is interrupted while scanning
     */
    String next() throws InterruptedIOException {
        ElementCollector.checkInterrupted();
        if (exhausted) {
            return null;
        }
        final int start = offset;
        while (offset < text.length()) {
            if (((offset - start) & 1023) == 0) {
                ElementCollector.checkInterrupted();
            }
            final char point = text.charAt(offset);
            if (point == '\r' || point == '\n' || point == '\u000b' || point == '\f' || point == '\u0085'
                    || point == '\u2028' || point == '\u2029') {
                final int end = offset++;
                if (point == '\r' && offset < text.length() && text.charAt(offset) == '\n') {
                    offset++;
                }
                ElementCollector.checkInterrupted();
                return text.subSequence(start, end).toString();
            }
            offset++;
        }
        exhausted = true;
        ElementCollector.checkInterrupted();
        return text.subSequence(start, offset).toString();
    }
}
