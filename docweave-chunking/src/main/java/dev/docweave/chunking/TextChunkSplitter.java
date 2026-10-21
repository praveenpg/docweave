package dev.docweave.chunking;

import static dev.docweave.chunking.UnicodeText.*;

/** Immutable literal-boundary policy for narrative, code and text-only table fragments. */
final class TextChunkSplitter {
    /** Chunking options providing maximum characters, overlap, and separator strings. */
    private final ChunkOptions options;

    /**
     * Creates a text splitter configured with the given chunk options.
     *
     * @param options chunking options and separator preferences
     */
    TextChunkSplitter(final ChunkOptions options) {
        this.options = options;
    }

    /**
     * Splits {@code text} into a leading fragment that fits within {@link ChunkOptions#maxCharacters()} and a
     * remainder.
     *
     * @param text input text to split
     * @return {@link Split} containing the emitted fragment and remaining text (including overlap if applicable)
     */
    Split split(final String text) {
        final int count = length(text), max = options.maxCharacters(), overlap = options.overlap();
        if (count <= max) {
            return new Split(text, "");
        }
        // Try configured separators in priority order, scanning backward from the end of the max-character window for a
        // split point that makes progress beyond overlap.
        for (final String separator : options.textSplittingSeparators()) {
            ChunkCancellation.check();
            final boolean whitespace = separator.codePoints().allMatch(UnicodeText::space);
            final int limit = (int) Math.min(count, (long) max + length(separator));
            final String window = slice(text, 0, limit);
            int position = window.lastIndexOf(separator);
            while (position >= 0) {
                ChunkCancellation.check();
                final int end = whitespace ? position : position + separator.length();
                final int pointEnd = length(window.substring(0, end));
                if (pointEnd > overlap && pointEnd <= max) {
                    final String fragment = rightStrip(text.substring(0, end));
                    final String raw = leftStrip(text.substring(position + separator.length()));
                    final String remainder = overlap <= 1 ? raw : leftStrip(tail(fragment, overlap - 1)) + " " + raw;
                    if (!fragment.isEmpty() && length(remainder) < count) {
                        return new Split(fragment, leftStrip(remainder));
                    }
                }
                position = window.lastIndexOf(separator, position - 1);
            }
        }
        // Fall back to a hard code-point cut at maxCharacters when no configured separator yields a valid split.
        return new Split(rightStrip(slice(text, 0, max)), leftStrip(slice(text, max - overlap, count)));
    }

    /**
     * Result of a single text split step.
     *
     * @param fragment leading text fragment within the hard character budget
     * @param remainder remaining text to be processed in subsequent split steps, or empty when complete
     */
    record Split(String fragment, String remainder) {}
}
