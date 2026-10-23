package dev.docweave.ocr;

import dev.docweave.partition.PdfOcrProvider;
import dev.docweave.partition.PdfWordOcrProvider;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Finds one printed vertical column rule in page pixels for
 * {@link TesseractOptions.LineSplitting#WIDE_GAPS_AND_PRINTED_RULE} and
 * {@link TesseractOptions.LineSplitting#WIDE_GAPS_AND_GUARDED_RULE_COLUMNS}, and supplies the guarded policy's
 * two-sided text check and column-by-column line order.
 *
 * <p>The detector compares each pixel with the pixels two columns to either side, so a faint thin rule is found
 * without a global ink threshold. It never reads recognized text, and the borrowed raster is neither modified nor
 * retained.
 */
final class TesseractPrintedRule {
    /** Minimum luminance by which a rule pixel is darker than the mean of its two-column neighbours. */
    private static final int CONTRAST = 8;

    /** Largest run of non-evidence rows bridged inside one vertical run. */
    private static final int BRIDGE = 2;

    /** Minimum run length in median recognized word heights. */
    private static final int LENGTH = 8;

    /** Utility class. */
    private TesseractPrintedRule() {
    }

    /**
     * One accepted rule.
     *
     * @param x rule column in raster pixels
     * @param top first row of the run
     * @param bottom last row of the run
     */
    record Rule(int x, int top, int bottom) {}

    /**
     * Tests whether a found rule separates text: at least three Tesseract lines overlapping the run vertically have
     * a word ending at or left of the rule, and at least three have a word starting at or right of it.
     *
     * @param rule found rule
     * @param lines recognized words per Tesseract line
     * @return whether the guarded policy may use the rule
     */
    static boolean separatesText(final Rule rule, final List<List<PdfWordOcrProvider.Word>> lines) {
        int left = 0;
        int right = 0;
        for (final var words : lines) {
            if (words.isEmpty()) {
                continue;
            }
            final double top = words.stream().mapToDouble(PdfWordOcrProvider.Word::top).min().orElseThrow();
            final double bottom = words.stream().mapToDouble(PdfWordOcrProvider.Word::bottom).max().orElseThrow();
            if (bottom < rule.top() || top > rule.bottom()) {
                continue;
            }
            left += words.stream().anyMatch(word -> word.right() <= rule.x()) ? 1 : 0;
            right += words.stream().anyMatch(word -> word.left() >= rule.x()) ? 1 : 0;
        }
        return left >= 3 && right >= 3;
    }

    /**
     * Returns the column-by-column emission order of returned lines around an accepted rule.
     * Lines before the first line centred within the run keep their place; lines centred within the run follow,
     * left of the rule first and then right of it; all other later lines follow last. Relative order is kept within
     * each group.
     *
     * @param lines returned lines in parsed order
     * @param rule accepted rule
     * @return permutation of line indices
     */
    static List<Integer> columnOrder(final List<PdfOcrProvider.Line> lines, final Rule rule) {
        final int[] group = new int[lines.size()];
        int first = -1;
        for (int index = 0; index < lines.size(); index++) {
            final var line = lines.get(index);
            final double centre = (line.top() + line.bottom()) / 2;
            if (centre >= rule.top() && centre <= rule.bottom()) {
                group[index] = (line.left() + line.right()) / 2 < rule.x() ? 1 : 2;
                first = first < 0 ? index : first;
            }
        }
        final int start = first;
        final var order = new ArrayList<Integer>(lines.size());
        for (int index = 0; index < lines.size(); index++) {
            order.add(index);
        }
        order.sort(Comparator.comparingInt((Integer index)
                                                   -> start < 0 || index < start ? 0
                                                      : group[index] == 0        ? 3
                                                                                 : group[index]));
        return List.copyOf(order);
    }

    /**
     * Finds the single accepted rule, if any.
     *
     * @param image borrowed page raster in the recognition frame
     * @param lines recognized words per Tesseract line
     * @param deadline call deadline, checked once per scanned column
     * @return the accepted rule, or null when there are no words or no run is long enough
     * @throws IOException if the call is interrupted or times out
     */
    static Rule find(final BufferedImage image, final List<List<PdfWordOcrProvider.Word>> lines,
            final OcrDeadline deadline) throws IOException {
        final double[] heights =
                lines.stream().flatMap(List::stream).mapToDouble(word -> word.bottom() - word.top()).sorted().toArray();
        if (heights.length == 0) {
            return null;
        }
        final double height = heights[heights.length / 2];
        final double left = lines.stream().flatMap(List::stream).mapToDouble(word -> word.left()).min().orElseThrow();
        final double right = lines.stream().flatMap(List::stream).mapToDouble(word -> word.right()).max().orElseThrow();
        final int width = image.getWidth();
        final int rows = image.getHeight();
        final int first = Math.max(2, (int) Math.ceil(left + 0.1 * (right - left)));
        final int last = Math.min(width - 3, (int) Math.floor(left + 0.9 * (right - left)));
        if (first > last) {
            return null;
        }
        final byte[] luminance = luminance(image, first - 2, last + 2, deadline);
        final int span = last - first + 5;
        int bestX = -1;
        int bestRun = 0;
        int bestTop = 0;
        int bestBottom = 0;
        for (int x = first; x <= last; x++) {
            deadline.remaining();
            final int column = x - first + 2;
            int start = -1;
            int gap = 0;
            for (int row = 0; row < rows; row++) {
                final int offset = row * span + column;
                final int sides =
                        (Byte.toUnsignedInt(luminance[offset - 2]) + Byte.toUnsignedInt(luminance[offset + 2]));
                if (sides / 2.0 - Byte.toUnsignedInt(luminance[offset]) >= CONTRAST) {
                    if (start < 0) {
                        start = row;
                    }
                    gap = 0;
                    if (row - start + 1 > bestRun) {
                        bestRun = row - start + 1;
                        bestX = x;
                        bestTop = start;
                        bestBottom = row;
                    }
                } else if (start >= 0 && ++gap > BRIDGE) {
                    start = -1;
                    gap = 0;
                }
            }
        }
        return bestX >= 0 && bestRun >= LENGTH * height ? new Rule(bestX, bestTop, bestBottom) : null;
    }

    /**
     * Returns the word index at which a line splits at the rule, or -1.
     *
     * @param words one Tesseract line's words in recognition order
     * @param rule accepted rule, or null
     * @return index of the first word right of the rule, or -1 when the line does not split
     */
    static int split(final List<PdfWordOcrProvider.Word> words, final Rule rule) {
        if (rule == null || words.size() < 2) {
            return -1;
        }
        final double top = words.stream().mapToDouble(PdfWordOcrProvider.Word::top).min().orElseThrow();
        final double bottom = words.stream().mapToDouble(PdfWordOcrProvider.Word::bottom).max().orElseThrow();
        final double x = rule.x();
        if (bottom < rule.top() || top > rule.bottom() || words.stream().anyMatch(w -> w.left() < x && w.right() > x)) {
            return -1;
        }
        for (int index = 1; index < words.size(); index++) {
            if (words.get(index - 1).right() <= x && words.get(index).left() >= x) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Copies integer luminance of a column band, row-major.
     *
     * @param image borrowed raster
     * @param from first column, inclusive
     * @param to last column, inclusive
     * @param deadline call deadline, checked once per row
     * @return luminance bytes of width {@code to - from + 1}
     * @throws IOException if the call is interrupted or times out
     */
    private static byte[] luminance(final BufferedImage image, final int from, final int to, final OcrDeadline deadline)
            throws IOException {
        final int span = to - from + 1;
        final byte[] result = new byte[span * image.getHeight()];
        final int[] row = new int[span];
        for (int y = 0; y < image.getHeight(); y++) {
            deadline.remaining();
            image.getRGB(from, y, span, 1, row, 0, span);
            for (int x = 0; x < span; x++) {
                final int rgb = row[x];
                result[y * span + x] =
                        (byte) ((299 * ((rgb >> 16) & 255) + 587 * ((rgb >> 8) & 255) + 114 * (rgb & 255)) / 1000);
            }
        }
        return result;
    }
}
