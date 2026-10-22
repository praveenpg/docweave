package dev.docweave.ocr;

import dev.docweave.partition.PartitionException;
import dev.docweave.partition.PdfOcrProvider;
import dev.docweave.partition.PdfWordOcrProvider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Strict bounded TSV conversion; recognition order comes from Tesseract rather than a geometric re-sort. */
final class TesseractTsv {
    /** Exact Tesseract 5 TSV schema. */
    private static final String HEADER =
            "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext";

    /** Utility class. */
    private TesseractTsv() {
    }

    /**
     * Validates UTF-8, word bounds and budgets before returning immutable line values.
     * @param bytes bounded stdout
     * @param width input raster width
     * @param height input raster height
     * @param options output budgets
     * @param deadline cooperative parsing deadline
     * @return immutable recognition-ordered lines with unioned word boxes
     * @throws IOException if TSV is invalid, over budget, cancelled or expired
     */
    static List<PdfOcrProvider.Line> parse(final byte[] bytes, final int width, final int height,
            final TesseractOptions options, final OcrDeadline deadline) throws IOException {
        return parseResult(bytes, width, height, options, deadline, false).lines();
    }

    /**
     * Retains actual TSV token boxes without invoking a second process or changing line text/order.
     * @param bytes bounded UTF-8 TSV
     * @param width input pixel width
     * @param height input pixel height
     * @param options existing line/character budgets
     * @param deadline shared admission/parse deadline
     * @return immutable detailed lines
     * @throws IOException if TSV, bounds, budgets, cancellation or deadline fail
     */
    static List<PdfWordOcrProvider.WordLine> parseWords(final byte[] bytes, final int width, final int height,
            final TesseractOptions options, final OcrDeadline deadline) throws IOException {
        return parseResult(bytes, width, height, options, deadline, true).words();
    }

    /**
     * Parses either view with identical validation/budgets and optional token allocation.
     * @param bytes bounded engine stdout
     * @param width input width
     * @param height input height
     * @param options output resource policy
     * @param deadline cooperative deadline
     * @param retainWords whether actual token boxes should be retained
     * @return immutable parsed output views
     * @throws IOException if validation, budgets, deadline or cancellation fails
     */
    private static Parsed parseResult(final byte[] bytes, final int width, final int height,
            final TesseractOptions options, final OcrDeadline deadline, final boolean retainWords) throws IOException {
        deadline.remaining();
        final var text = StandardCharsets.UTF_8.newDecoder()
                                 .onMalformedInput(CodingErrorAction.REPORT)
                                 .onUnmappableCharacter(CodingErrorAction.REPORT)
                                 .decode(ByteBuffer.wrap(bytes))
                                 .toString();
        final var lines = new LinkedHashMap<String, Words>();
        final boolean split = options.lineSplitting() == TesseractOptions.LineSplitting.WIDE_GAPS;
        int characters = 0;
        try (final var reader = new BufferedReader(new StringReader(text))) {
            if (!HEADER.equals(reader.readLine())) {
                throw new IOException("Invalid Tesseract TSV header");
            }
            for (String row; (row = reader.readLine()) != null;) {
                deadline.remaining();
                if (row.isEmpty()) {
                    continue;
                }
                final var cells = row.split("\t", 12);
                if (cells.length != 12) {
                    throw new IOException("Invalid Tesseract TSV row");
                }
                final int level = number(cells[0]);
                if (level < 1 || level > 5 || number(cells[1]) != 1) {
                    throw new IOException("Invalid Tesseract TSV page or level");
                }
                if (level != 5 || cells[11].isBlank()) {
                    continue;
                }
                if (number(cells[2]) < 1 || number(cells[3]) < 1 || number(cells[4]) < 1 || number(cells[5]) < 1) {
                    throw new IOException("Invalid Tesseract word hierarchy");
                }
                final int left = number(cells[6]);
                final int top = number(cells[7]);
                final int wordWidth = number(cells[8]);
                final int wordHeight = number(cells[9]);
                if (left < 0 || top < 0 || wordWidth < 1 || wordHeight < 1 || (long) left + wordWidth > width
                        || (long) top + wordHeight > height) {
                    throw new IOException("Tesseract word is outside the raster");
                }
                final String key = cells[1] + ":" + cells[2] + ":" + cells[3] + ":" + cells[4];
                var words = lines.get(key);
                final int separator = words == null ? 0 : 1;
                if (cells[11].length() > options.maxCharacters() - characters - separator) {
                    throw new PartitionException(PartitionException.Code.OCR_LIMIT, "Tesseract exceeds maxCharacters");
                }
                if (words == null) {
                    if (lines.size() >= options.maxLines()) {
                        throw new PartitionException(PartitionException.Code.OCR_LIMIT, "Tesseract exceeds maxLines");
                    }
                    words = new Words(retainWords || split);
                    lines.put(key, words);
                }
                words.add(cells[11], left, top, left + wordWidth, top + wordHeight);
                characters += cells[11].length() + separator;
            }
        }
        final var result = new ArrayList<PdfOcrProvider.Line>(lines.size());
        final var detailed = retainWords ? new ArrayList<PdfWordOcrProvider.WordLine>(lines.size()) : null;
        for (final var words : lines.values()) {
            deadline.remaining();
            if (split) {
                for (final var segment : segments(words.tokens, options.maxLines() - result.size(), deadline)) {
                    if (result.size() >= options.maxLines()) {
                        throw new PartitionException(PartitionException.Code.OCR_LIMIT, "Tesseract exceeds maxLines");
                    }
                    final var line = line(segment, deadline);
                    result.add(line);
                    if (detailed != null) {
                        detailed.add(new PdfWordOcrProvider.WordLine(line, segment));
                    }
                }
                continue;
            }
            final var line =
                    new PdfOcrProvider.Line(words.text.toString(), words.left, words.top, words.right, words.bottom);
            result.add(line);
            if (detailed != null) {
                detailed.add(new PdfWordOcrProvider.WordLine(line, words.tokens));
            }
        }
        return new Parsed(List.copyOf(result), detailed == null ? List.of() : List.copyOf(detailed));
    }

    /**
     * Divides one Tesseract line where a horizontal gap between consecutive words, in recognition order, exceeds
     * three times the line's tallest word. Overlapping or right-to-left steps never split. Words are not reordered.
     * @param tokens one Tesseract line's recognized words
     * @param maxSegments remaining returned-line allowance, checked before retaining new fragments
     * @param deadline shared recognition deadline and interruption check
     * @return nonempty consecutive word segments
     * @throws IOException if cancelled, timed out or the returned-line budget is exhausted
     */
    private static List<List<PdfWordOcrProvider.Word>> segments(final List<PdfWordOcrProvider.Word> tokens,
            final int maxSegments, final OcrDeadline deadline) throws IOException {
        if (maxSegments < 1) {
            throw new PartitionException(PartitionException.Code.OCR_LIMIT, "Tesseract exceeds maxLines");
        }
        double height = 0;
        for (final var word : tokens) {
            deadline.remaining();
            height = Math.max(height, word.bottom() - word.top());
        }
        final var result = new ArrayList<List<PdfWordOcrProvider.Word>>();
        var current = new ArrayList<PdfWordOcrProvider.Word>();
        for (final var word : tokens) {
            deadline.remaining();
            if (!current.isEmpty() && word.left() - current.getLast().right() > 3 * height) {
                if (result.size() + 1 >= maxSegments) {
                    throw new PartitionException(PartitionException.Code.OCR_LIMIT, "Tesseract exceeds maxLines");
                }
                result.add(List.copyOf(current));
                current = new ArrayList<>();
            }
            current.add(word);
        }
        result.add(List.copyOf(current));
        return result;
    }

    /**
     * Builds one returned line from consecutive words with single-space separators and their enclosing box.
     * @param words nonempty segment
     * @param deadline shared recognition deadline and interruption check
     * @return immutable line value
     * @throws IOException if recognition is interrupted or times out
     */
    private static PdfOcrProvider.Line line(final List<PdfWordOcrProvider.Word> words, final OcrDeadline deadline)
            throws IOException {
        final var text = new StringBuilder();
        double left = Double.POSITIVE_INFINITY;
        double top = Double.POSITIVE_INFINITY;
        double right = Double.NEGATIVE_INFINITY;
        double bottom = Double.NEGATIVE_INFINITY;
        for (final var word : words) {
            deadline.remaining();
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append(word.text());
            left = Math.min(left, word.left());
            top = Math.min(top, word.top());
            right = Math.max(right, word.right());
            bottom = Math.max(bottom, word.bottom());
        }
        return new PdfOcrProvider.Line(text.toString(), left, top, right, bottom);
    }

    /**
     * Internal immutable parser views; token collection is absent for historical line-only calls.
     * @param lines original recognition-ordered lines
     * @param words detailed source lines, or empty when not requested
     */
    private record Parsed(List<PdfOcrProvider.Line> lines, List<PdfWordOcrProvider.WordLine> words) {}

    /**
     * Parses an integer without including potentially sensitive TSV in a NumberFormatException message.
     * @param value numeric field
     * @return parsed integer
     * @throws IOException if the field is invalid
     */
    private static int number(final String value) throws IOException {
        try {
            return Integer.parseInt(value);
        } catch (final NumberFormatException failure) {
            throw new IOException("Invalid numeric Tesseract TSV field");
        }
    }

    /** Mutable accumulator confined to one returned line. */
    private static final class Words {
        /** Optional actual token list, allocated only for the explicit detailed capability. */
        private final List<PdfWordOcrProvider.Word> tokens;

        /**
         * Creates an accumulator under the existing output-character bound.
         * @param retainWords whether actual token geometry is requested
         */
        Words(final boolean retainWords) {
            tokens = retainWords ? new ArrayList<>() : null;
        }

        /** Joined words with exactly one separating space. */
        private final StringBuilder text = new StringBuilder();
        /** Minimum horizontal edge. */
        private int left = Integer.MAX_VALUE;
        /** Minimum vertical edge. */
        private int top = Integer.MAX_VALUE;
        /** Maximum horizontal edge. */
        private int right;
        /** Maximum vertical edge. */
        private int bottom;

        /**
         * Appends one validated word and expands its enclosing rectangle.
         * @param word recognized text
         * @param x minimum horizontal edge
         * @param y minimum vertical edge
         * @param endX maximum horizontal edge
         * @param endY maximum vertical edge
         */
        void add(final String word, final int x, final int y, final int endX, final int endY) {
            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append(word);
            if (tokens != null) {
                tokens.add(new PdfWordOcrProvider.Word(word, x, y, endX, endY));
            }
            left = Math.min(left, x);
            top = Math.min(top, y);
            right = Math.max(right, endX);
            bottom = Math.max(bottom, endY);
        }
    }
}
