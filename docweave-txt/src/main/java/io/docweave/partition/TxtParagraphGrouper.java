package io.docweave.partition;

import io.docweave.core.ElementType;

import java.io.IOException;

/** Document-local line grouping; the downstream size stage owns classification and emission. */
final class TxtParagraphGrouper {
    /** Document-level partition options controlling paragraph grouping and classification. */
    private final PartitionOptions options;
    /** Downstream size and emission stage receiving grouped paragraphs. */
    private final TxtPartitionSizer output;
    /** Buffer accumulating normalized lines for the current paragraph. */
    private final StringBuilder paragraph = new StringBuilder();
    /** Whether the paragraph currently in {@link #paragraph} started as a list item. */
    private boolean listParagraph;
    /** Normalized text of the previous non-skipped line. */
    private String previous = "";

    /**
     * Creates a grouper that forwards completed paragraphs to the given size stage.
     * @param options partition options governing paragraph boundaries
     * @param output downstream size and emission stage
     */
    TxtParagraphGrouper(final PartitionOptions options, final TxtPartitionSizer output) {
        this.options = options;
        this.output = output;
    }

    /**
     * Scans lines from the given text and groups them into paragraphs according to configured rules.
     * @param text decoded document text
     * @throws IOException if downstream sizing, emission or interruption fails
     */
    void partition(final String text) throws IOException {
        final var lines = new TxtLineCursor(text);
        for (String line; (line = lines.next()) != null;) {
            ElementCollector.checkInterrupted();
            final boolean blank = TextSupport.blank(line);
            final String normalized = TextSupport.normalize(line);
            // Ignore isolated single-character bullet glyphs that carry no item text.
            if (TextSupport.points(normalized, 0, normalized.length()) == 1 && "•●▪◦‣⁃*+-".contains(normalized)) {
                continue;
            }
            final boolean automatic = options.paragraphMode() == PartitionOptions.ParagraphMode.AUTO;
            final boolean listItem =
                    (options.classifyText() || automatic) && TextSupport.classify(normalized) == ElementType.ListItem;
            final boolean indented = !line.isEmpty() && TextSupport.space(line.codePointAt(0));
            // Split or join lines according to the active paragraph mode, list continuation indentation, and AUTO
            // heading/sentence boundaries.
            if (blank || options.paragraphMode() == PartitionOptions.ParagraphMode.LINE_BREAKS) {
                flush();
                if (!blank) {
                    paragraph.append(normalized);
                    flush();
                }
            } else if (listItem) {
                flush();
                paragraph.append(normalized);
                listParagraph = true;
            } else {
                if (listParagraph && !indented) {
                    flush();
                }
                if (automatic && !listParagraph && !paragraph.isEmpty()
                        && (heading(previous) || heading(normalized)
                                || sentenceEnd(previous) && !indented && sentenceStart(normalized))) {
                    flush();
                }
                if (!paragraph.isEmpty()) {
                    paragraph.append(' ');
                }
                paragraph.append(normalized);
            }
            previous = normalized;
        }
        flush();
        output.finish();
    }

    /**
     * Forwards any buffered paragraph text to the downstream sizer and resets paragraph state.
     * @throws IOException if downstream acceptance or emission fails
     */
    private void flush() throws IOException {
        if (!paragraph.isEmpty()) {
            output.accept(paragraph.toString());
        }
        paragraph.setLength(0);
        listParagraph = false;
    }

    /**
     * Checks whether a normalized line matches the short title-case heading heuristic.
     * @param value normalized single-line text
     * @return {@code true} if the line qualifies as a standalone heading
     * @throws IOException if interruption occurs during code-point or punctuation checks
     */
    private static boolean heading(final String value) throws IOException {
        if (value.isEmpty() || TextSupport.points(value, 0, value.length()) > 80 || sentenceEnd(value)) {
            return false;
        }
        final String[] words = value.split(" ");
        if (words.length > 8) {
            return false;
        }
        for (final String word : words) {
            final int first = word.codePointAt(0);
            if (!Character.isUpperCase(first) && !Character.isTitleCase(first)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Checks whether a normalized line ends with sentence-terminal punctuation before optional closing quotes/brackets.
     * @param value normalized line text
     * @return {@code true} if the line ends a sentence
     * @throws IOException if the calling thread is interrupted while scanning
     */
    private static boolean sentenceEnd(final String value) throws IOException {
        // Scan backward past trailing closing quotes or brackets to check for terminal sentence punctuation.
        for (int end = value.length(); end > 0;) {
            if ((end & 1023) == 0) {
                ElementCollector.checkInterrupted();
            }
            final int point = value.codePointBefore(end);
            if (".?!。！？".indexOf(point) >= 0) {
                return true;
            }
            if ("\"'”’)]}".indexOf(point) < 0) {
                return false;
            }
            end -= Character.charCount(point);
        }
        return false;
    }

    /**
     * Checks whether a normalized line begins with a non-lowercase letter.
     * @param value normalized line text
     * @return {@code true} if the first code point can start a new sentence
     */
    private static boolean sentenceStart(final String value) {
        if (value.isEmpty()) {
            return false;
        }
        final int first = value.codePointAt(0);
        return Character.isLetter(first) && !Character.isLowerCase(first);
    }
}
