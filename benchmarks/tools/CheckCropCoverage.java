package io.docweave.validation;

import io.docweave.partition.DocumentSource;
import io.docweave.partition.PdfPageCropOptions;
import io.docweave.partition.PdfPageCropRenderer;
import io.docweave.partition.PdfPageCropRequest;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import tools.jackson.databind.json.JsonMapper;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Checks {@code BOX_4X} crop coverage on an owned page against hand-derived sixteenth-sample expectations and,
 * optionally, against an independent renderer image box-averaged to the same grid; evaluation only.
 *
 * <p>The owned 100x60 page holds four one-unit black squares whose left edges sit at fractional offsets 0, 0.25,
 * 0.5 and 0.75 within a 72 DPI pixel, and one 0.3-unit-wide vertical bar. With four by four sampling the expected
 * luminance of a pixel is 255 times the fraction of its sixteen sample centres outside the black shape.
 */
public final class CheckCropCoverage {
    /** Left edges of the four squares, in displayed units. */
    private static final double[] LEFTS = {10, 20.25, 30.5, 40.75};

    /** Bar left edge and width. */
    private static final double[] BAR = {60.4, 0.3};

    /** Prevents construction. */
    private CheckCropCoverage() {
    }

    /**
     * Writes the owned page, crops it and compares coverage.
     *
     * @param args fresh owned-PDF path, fresh report path and optionally an independent 288 DPI grayscale PNG of
     *         that PDF's first page
     * @throws Exception if authoring, rendering or decoding fails
     */
    public static void main(final String[] args) throws Exception {
        final byte[] pdf = page();
        Files.write(Path.of(args[0]), pdf, StandardOpenOption.CREATE_NEW);
        final var crop = PdfPageCropRenderer
                                 .create(PdfPageCropOptions.defaults().withDpi(72).withSampling(
                                         PdfPageCropOptions.Sampling.BOX_4X))
                                 .render(DocumentSource.bytes(pdf, "coverage.pdf"),
                                         List.of(new PdfPageCropRequest(1, 0, 0, 100, 60)))
                                 .getFirst();
        final BufferedImage image = ImageIO.read(new ByteArrayInputStream(crop.png()));
        final BufferedImage independent = args.length > 2 ? ImageIO.read(Path.of(args[2]).toFile()) : null;
        int analyticMax = 0;
        int independentMax = 0;
        final var pixels = new ArrayList<Map<String, Object>>();
        for (int y = 9; y <= 11; y++) {
            for (int x = 8; x <= 63; x++) {
                final int expected = (int) Math.round(255 * (1 - coverage(x, y)));
                final int actual = image.getRGB(x, y) & 0xff;
                analyticMax = Math.max(analyticMax, Math.abs(expected - actual));
                Integer other = null;
                if (independent != null) {
                    int sum = 0;
                    for (int dy = 0; dy < 4; dy++) {
                        for (int dx = 0; dx < 4; dx++) {
                            sum += independent.getRGB(x * 4 + dx, y * 4 + dy) & 0xff;
                        }
                    }
                    other = Math.round(sum / 16f);
                    independentMax = Math.max(independentMax, Math.abs(other - actual));
                }
                if (expected != 255 || actual != 255) {
                    final var row = new LinkedHashMap<String, Object>();
                    row.put("x", x);
                    row.put("y", y);
                    row.put("expected", expected);
                    row.put("box4x", actual);
                    if (other != null) {
                        row.put("independent", other);
                    }
                    pixels.add(row);
                }
            }
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put("scope", "Owned fractional-coverage crop check; BOX_4X at 72 DPI on WHITE; evaluation only.");
        report.put("java_version", System.getProperty("java.version"));
        report.put("crop_origin", List.of(crop.pixelLeft(), crop.pixelTop(), crop.width(), crop.height()));
        report.put("max_abs_difference_analytic", analyticMax);
        report.put("max_abs_difference_independent", independent == null ? "NOT_RUN" : independentMax);
        report.put("pixels", pixels);
        Files.writeString(Path.of(args[1]),
                JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardOpenOption.CREATE_NEW);
        System.out.println("analytic max=" + analyticMax
                           + " independent max=" + (independent == null ? "NOT_RUN" : independentMax));
    }

    /**
     * Returns the fraction of the sixteen sample centres of a pixel inside any black shape.
     *
     * @param x pixel column
     * @param y pixel row
     * @return covered fraction in sixteenths
     */
    private static double coverage(final int x, final int y) {
        int inside = 0;
        for (int sy = 0; sy < 4; sy++) {
            for (int sx = 0; sx < 4; sx++) {
                final double px = x + (sx + 0.5) / 4;
                final double py = y + (sy + 0.5) / 4;
                boolean covered = false;
                for (final double left : LEFTS) {
                    covered |= px >= left && px < left + 1 && py >= 10 && py < 11;
                }
                covered |= px >= BAR[0] && px < BAR[0] + BAR[1] && py >= 10 && py < 11;
                inside += covered ? 1 : 0;
            }
        }
        return inside / 16.0;
    }

    /**
     * Authors the owned page; displayed y 10-11 is PDF y 49-50 on a 60-unit page.
     *
     * @return PDF bytes
     * @throws Exception if authoring fails
     */
    private static byte[] page() throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(100, 60));
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                content.setNonStrokingColor(Color.BLACK);
                for (final double left : LEFTS) {
                    content.addRect((float) left, 49, 1, 1);
                }
                content.addRect((float) BAR[0], 49, (float) BAR[1], 1);
                content.fill();
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
