package io.docweave.partition;

import io.docweave.core.ElementType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Shared exact-token remainder ownership for explicit word-driven table strategies. */
final class PdfOcrTokens {
    /** Utility class. */
    private PdfOcrTokens() {
    }

    /**
     * Preserves original lines when no token is owned, otherwise emits contiguous unowned sequences.
     * @param detailed validated provider source lines
     * @param owned parallel source-token ownership flags
     * @param regions optional mixed native/image exclusions
     * @param work shared table traversal budget
     * @return ordinary pending elements in original provider encounter order
     * @throws IOException if cancellation or ownership work fails
     */
    static List<PdfOcrElement> remainders(final List<PdfWordOcrProvider.WordLine> detailed, final List<boolean[]> owned,
            final PdfOcrSelection regions, final PdfTableWork work) throws IOException {
        final var output = new ArrayList<PdfOcrElement>();
        for (int line = 0; line < detailed.size(); line++) {
            work.step();
            final var source = detailed.get(line);
            boolean any = false;
            for (final boolean value : owned.get(line)) {
                work.step();
                any |= value;
            }
            if (!any) {
                ordinary(output, source.line(), regions);
                continue;
            }
            final var remainder = new ArrayList<PdfWordOcrProvider.Word>();
            for (int word = 0; word < source.words().size(); word++) {
                work.step();
                if (owned.get(line)[word]) {
                    flush(output, remainder, regions);
                } else {
                    remainder.add(source.words().get(word));
                }
            }
            flush(output, remainder, regions);
        }
        return List.copyOf(output);
    }

    /**
     * Emits a contiguous unowned sequence before clearing its page-local accumulator.
     * @param output destination ordinary elements
     * @param words nonempty sequence or empty accumulator
     * @param regions optional mixed ownership policy
     * @throws IOException if region checks fail
     */
    private static void flush(final List<PdfOcrElement> output, final List<PdfWordOcrProvider.Word> words,
            final PdfOcrSelection regions) throws IOException {
        if (!words.isEmpty()) {
            final var envelope = bounds(words);
            ordinary(output,
                    new PdfOcrProvider.Line(
                            String.join(" ", words.stream().map(PdfWordOcrProvider.Word::text).toList()),
                            envelope.left(), envelope.top(), envelope.right(), envelope.bottom()),
                    regions);
            words.clear();
        }
    }

    /**
     * Emits a nonblank original line/remainder only after mixed-source exclusion checks.
     * @param output destination pending elements
     * @param line original or reconstructed unowned source text
     * @param regions optional mixed ownership policy
     * @throws IOException if exclusion work/cancellation fails
     */
    private static void ordinary(final List<PdfOcrElement> output, final PdfOcrProvider.Line line,
            final PdfOcrSelection regions) throws IOException {
        if (!line.text().isBlank() && (regions == null || regions.retain(line))) {
            output.add(new PdfOcrElement(ElementType.UncategorizedText, line.text(), line, Map.of()));
        }
    }

    /**
     * Returns an exact enclosing rectangle of actual words, without inferred empty-cell borders.
     * @param words nonempty actual source tokens
     * @return pixel envelope without text
     */
    static PdfOcrProvider.Line bounds(final List<PdfWordOcrProvider.Word> words) {
        return new PdfOcrProvider.Line("",
                words.stream().mapToDouble(PdfWordOcrProvider.Word::left).min().orElseThrow(),
                words.stream().mapToDouble(PdfWordOcrProvider.Word::top).min().orElseThrow(),
                words.stream().mapToDouble(PdfWordOcrProvider.Word::right).max().orElseThrow(),
                words.stream().mapToDouble(PdfWordOcrProvider.Word::bottom).max().orElseThrow());
    }
}
