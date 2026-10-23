package dev.docweave.ocr;

import dev.docweave.partition.PartitionException;
import dev.docweave.partition.PdfOcrProvider;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Owned raster and TSV controls for the explicit printed-rule line splitting; no engine process is started. */
class TesseractPrintedRuleTest {
    /** TSV schema header shared by every owned input. */
    private static final String HEADER =
            "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n";

    /** Creates independent controls. */
    TesseractPrintedRuleTest() {
    }

    /**
     * A faint rule splits a line whose words abut it, which WIDE_GAPS keeps whole because the gap is ordinary.
     * Text and word order stay unchanged, and each part gets its own box.
     * @throws Exception if parsing fails
     */
    @Test
    void faintRuleSplitsALineThatWideGapsKeepsWhole() throws Exception {
        final String tsv = HEADER + word(1, 30, "links", 440, 55) + word(1, 30, "rechts", 505, 55);
        final var page = page(500, 20, 420, 235);
        assertEquals(List.of("links rechts"), texts(parse(tsv, gaps(), page)));
        final var ruled = parse(tsv, ruled(), page);
        assertEquals(List.of("links", "rechts"), texts(ruled));
        assertEquals(495, ruled.getFirst().right());
        assertEquals(505, ruled.get(1).left());
    }

    /**
     * A word crossing the rule blocks only its own line, and a line wholly outside the run is untouched.
     * @throws Exception if parsing fails
     */
    @Test
    void crossingWordsAndLinesOutsideTheRunAreNotSplit() throws Exception {
        final String tsv = HEADER + word(1, 30, "a", 440, 55) + word(1, 30, "b", 505, 55) + word(2, 60, "quer", 470, 60)
                           + word(2, 60, "x", 540, 10) + word(3, 450, "unten", 440, 55) + word(3, 450, "fort", 505, 40);
        assertEquals(List.of("a", "b", "quer x", "unten fort"), texts(parse(tsv, ruled(), page(500, 20, 420, 235))));
    }

    /**
     * A run shorter than eight median word heights, a blank page, a too-light line and table-cell calls without
     * a page raster all keep exactly the WIDE_GAPS output.
     * @throws Exception if parsing fails
     */
    @Test
    void shortFaintAbsentAndCellRulesKeepWideGapOutput() throws Exception {
        final String tsv = HEADER + word(1, 30, "links", 440, 55) + word(1, 30, "rechts", 505, 55);
        final var expected = parse(tsv, gaps(), null);
        assertEquals(expected, parse(tsv, ruled(), page(500, 20, 98, 235)));
        assertEquals(expected, parse(tsv, ruled(), page(500, 20, 420, 255)));
        assertEquals(expected, parse(tsv, ruled(), page(500, 20, 420, 248)));
        assertEquals(expected, parse(tsv, ruled(), null));
        assertEquals(List.of("links rechts"), texts(parse(tsv, TesseractOptions.defaults(), page(500, 20, 420, 235))));
    }

    /**
     * The detailed word view follows the same segments, keeping each word in its own part.
     * @throws Exception if parsing fails
     */
    @Test
    void detailedWordsFollowTheRuleSegments() throws Exception {
        final var policy = ruled();
        final var detailed =
                TesseractTsv.parseWords((HEADER + word(1, 30, "links", 440, 55) + word(1, 30, "rechts", 505, 55))
                                                .getBytes(StandardCharsets.UTF_8),
                        1000, 500, policy, new OcrDeadline(policy.timeout()), page(500, 20, 420, 235));
        assertEquals(2, detailed.size());
        assertEquals(List.of("rechts"), detailed.get(1).words().stream().map(w -> w.text()).toList());
    }

    /**
     * Rule splits count against maxLines, and an interrupted call fails with the existing typed interruption.
     */
    @Test
    void splitsRespectMaxLinesAndInterruption() {
        final var limited = new TesseractOptions(Path.of("tesseract"), null, List.of("deu"), Duration.ofSeconds(5), 1,
                1_000_000, 1_000_000, 1_000_000, 1, 1_000)
                                    .withLineSplitting(TesseractOptions.LineSplitting.WIDE_GAPS_AND_PRINTED_RULE);
        final String tsv = HEADER + word(1, 30, "links", 440, 55) + word(1, 30, "rechts", 505, 55);
        final var failure = assertThrows(PartitionException.class, () -> parse(tsv, limited, page(500, 20, 420, 235)));
        assertEquals(PartitionException.Code.OCR_LIMIT, failure.code());
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> parse(tsv, ruled(), page(500, 20, 420, 235)));
        } finally {
            Thread.interrupted();
        }
        assertEquals(TesseractOptions.LineSplitting.TESSERACT_LINES, TesseractOptions.defaults().lineSplitting());
    }

    /**
     * Builds the explicit wide-gap policy.
     * @return selected options
     */
    private static TesseractOptions gaps() {
        return TesseractOptions.defaults().withLineSplitting(TesseractOptions.LineSplitting.WIDE_GAPS);
    }

    /**
     * Builds the explicit printed-rule policy.
     * @return selected options
     */
    private static TesseractOptions ruled() {
        return TesseractOptions.defaults().withLineSplitting(TesseractOptions.LineSplitting.WIDE_GAPS_AND_PRINTED_RULE);
    }

    /**
     * Draws a white 1000x500 page with a one-pixel gray vertical line.
     * @param x line column
     * @param top first row
     * @param bottom last row
     * @param gray line luminance; 255 draws nothing
     * @return owned page raster
     */
    private static BufferedImage page(final int x, final int top, final int bottom, final int gray) {
        final var image = new BufferedImage(1000, 500, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 500; y++) {
            for (int column = 0; column < 1000; column++) {
                image.setRGB(column, y, 0xffffff);
            }
        }
        for (int y = top; y <= bottom; y++) {
            image.setRGB(x, y, gray << 16 | gray << 8 | gray);
        }
        return image;
    }

    /**
     * Parses owned TSV against a 1000x500 raster.
     * @param input complete TSV
     * @param policy parser options
     * @param page page raster, or null for a cell call
     * @return returned lines
     * @throws Exception if parsing fails
     */
    private static List<PdfOcrProvider.Line> parse(
            final String input, final TesseractOptions policy, final BufferedImage page) throws Exception {
        return TesseractTsv.parse(
                input.getBytes(StandardCharsets.UTF_8), 1000, 500, policy, new OcrDeadline(policy.timeout()), page);
    }

    /**
     * Projects line text.
     * @param lines returned lines
     * @return texts in order
     */
    private static List<String> texts(final List<PdfOcrProvider.Line> lines) {
        return lines.stream().map(PdfOcrProvider.Line::text).toList();
    }

    /**
     * Builds one level-5 word row of height 10.
     * @param line Tesseract line number
     * @param top word top
     * @param text recognized word
     * @param left horizontal pixel edge
     * @param width word width
     * @return TSV row
     */
    private static String word(final int line, final int top, final String text, final int left, final int width) {
        return "5\t1\t1\t1\t" + line + "\t1\t" + left + "\t" + top + "\t" + width + "\t10\t90\t" + text + "\n";
    }
}
