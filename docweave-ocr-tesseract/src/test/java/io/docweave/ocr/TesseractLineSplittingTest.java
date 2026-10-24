package io.docweave.ocr;

import io.docweave.partition.PartitionException;
import io.docweave.partition.PdfOcrProvider;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Owned TSV controls for the explicit wide-gap line-splitting policy; no engine process is started. */
class TesseractLineSplittingTest {
    /** TSV schema header shared by every owned input. */
    private static final String HEADER =
            "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext\n";

    /** Creates independent parser controls. */
    TesseractLineSplittingTest() {
    }

    /**
     * The default keeps one line per Tesseract line identity even across a page-wide gap, as before.
     * @throws Exception if parsing fails
     */
    @Test
    void defaultKeepsEngineLineIdentities() throws Exception {
        final var lines =
                parse(HEADER + word(1, "falten.", 0, 30) + word(1, ";", 900, 10), TesseractOptions.defaults());
        assertEquals(List.of("falten. ;"), lines.stream().map(PdfOcrProvider.Line::text).toList());
        assertEquals(0, lines.getFirst().left());
        assertEquals(910, lines.getFirst().right());
    }

    /**
     * A gap wider than three times the tallest word starts a new line with its own box; text and order stay.
     * @throws Exception if parsing fails
     */
    @Test
    void wideGapSplitsIntoConsecutiveLinesWithOwnBoxes() throws Exception {
        final var lines =
                parse(HEADER + word(1, "falten.", 0, 30) + word(1, ";", 900, 10) + word(2, "next", 0, 20), gaps());
        assertEquals(List.of("falten.", ";", "next"), lines.stream().map(PdfOcrProvider.Line::text).toList());
        assertEquals(30, lines.getFirst().right());
        assertEquals(900, lines.get(1).left());
        assertEquals(910, lines.get(1).right());
    }

    /**
     * Ordinary word spacing, an exactly-threshold gap and a right-to-left step never split a line.
     * @throws Exception if parsing fails
     */
    @Test
    void ordinaryThresholdAndBackwardStepsDoNotSplit() throws Exception {
        final String ordinary = word(1, "ein", 0, 20) + word(1, "Wort", 25, 30);
        final String threshold = word(2, "a", 0, 10) + word(2, "b", 40, 10);
        final String backward = word(3, "x", 500, 10) + word(3, "y", 0, 10);
        final var lines = parse(HEADER + ordinary + threshold + backward, gaps());
        assertEquals(List.of("ein Wort", "a b", "x y"), lines.stream().map(PdfOcrProvider.Line::text).toList());
    }

    /**
     * Two words separated by a column gutter become two lines; three words with two gutters become three.
     * @throws Exception if parsing fails
     */
    @Test
    void columnGuttersSplitEveryWideStep() throws Exception {
        final var lines = parse(HEADER + word(1, "left", 0, 40) + word(1, "column", 45, 60) + word(1, "right", 400, 50)
                                        + word(1, "far", 800, 30),
                gaps());
        assertEquals(List.of("left column", "right", "far"), lines.stream().map(PdfOcrProvider.Line::text).toList());
    }

    /**
     * The detailed word view splits identically, and each segment's words stay inside its line.
     * @throws Exception if parsing fails
     */
    @Test
    void detailedWordsFollowTheSameSegments() throws Exception {
        final var policy = gaps();
        final var detailed = TesseractTsv.parseWords(
                (HEADER + word(1, "falten.", 0, 30) + word(1, ";", 900, 10)).getBytes(StandardCharsets.UTF_8), 1000,
                100, policy, new OcrDeadline(policy.timeout()));
        assertEquals(2, detailed.size());
        assertEquals("falten.", detailed.getFirst().line().text());
        assertEquals(List.of(";"), detailed.get(1).words().stream().map(w -> w.text()).toList());
    }

    /**
     * Additional split lines still count against maxLines and fail with the existing typed OCR limit.
     */
    @Test
    void splitLinesRespectMaxLines() {
        final var limited = new TesseractOptions(Path.of("tesseract"), null, List.of("eng"), Duration.ofSeconds(5), 1,
                1_000_000, 1_000_000, 1_000_000, 1, 1_000)
                                    .withLineSplitting(TesseractOptions.LineSplitting.WIDE_GAPS);
        final var failure = assertThrows(
                PartitionException.class, () -> parse(HEADER + word(1, "a", 0, 10) + word(1, "b", 900, 10), limited));
        assertEquals(PartitionException.Code.OCR_LIMIT, failure.code());
    }

    /**
     * Defaults and retained constructors keep engine lines; copy methods preserve the selected policy.
     */
    @Test
    void defaultsConstructorsAndCopiesPreserveTheSelection() {
        assertEquals(TesseractOptions.LineSplitting.TESSERACT_LINES, TesseractOptions.defaults().lineSplitting());
        final var selected = gaps();
        assertEquals(TesseractOptions.LineSplitting.WIDE_GAPS,
                selected.withPageSegmentation(TesseractOptions.PageSegmentation.SINGLE_COLUMN)
                        .withCellSegmentation(TesseractOptions.CellSegmentation.SINGLE_BLOCK)
                        .withCellBorders(TesseractOptions.CellBorders.REMOVE_EDGE_RULES)
                        .lineSplitting());
        assertThrows(NullPointerException.class, () -> TesseractOptions.defaults().withLineSplitting(null));
    }

    /**
     * Builds the explicit wide-gap policy.
     * @return selected options
     */
    private static TesseractOptions gaps() {
        return TesseractOptions.defaults().withLineSplitting(TesseractOptions.LineSplitting.WIDE_GAPS);
    }

    /**
     * Parses owned TSV against a 1000x100 raster.
     * @param input complete TSV
     * @param policy parser options
     * @return returned lines
     * @throws Exception if parsing fails
     */
    private static List<PdfOcrProvider.Line> parse(final String input, final TesseractOptions policy) throws Exception {
        return TesseractTsv.parse(
                input.getBytes(StandardCharsets.UTF_8), 1000, 100, policy, new OcrDeadline(policy.timeout()));
    }

    /**
     * Builds one level-5 word row of height 10 at top 10.
     * @param line Tesseract line number
     * @param text recognized word
     * @param left horizontal pixel edge
     * @param width word width
     * @return TSV row
     */
    private static String word(final int line, final String text, final int left, final int width) {
        return "5\t1\t1\t1\t" + line + "\t1\t" + left + "\t10\t" + width + "\t10\t90\t" + text + "\n";
    }
}
