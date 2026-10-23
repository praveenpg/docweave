package dev.docweave.validation;

import dev.docweave.partition.DocumentSource;
import dev.docweave.partition.PdfPageCrop;
import dev.docweave.partition.PdfPageCropOptions;
import dev.docweave.partition.PdfPageCropRenderer;
import dev.docweave.partition.PdfPageCropRequest;
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
 * Probes rendered-page crop geometry against independently derived expectations; evaluation only.
 *
 * <p>Owned one-page documents paint a solid red marker at a known user-space rectangle inside an offset crop box,
 * under each probe's page properties. Expected displayed rectangles follow the documented contract (crop-box relative,
 * top-left origin, clockwise page rotation applied, {@code pixelsPerUnit = dpi / 72 x UserUnit}, outward
 * floor/ceiling rounding) and are computed here without consulting the implementation. Checks: reported pixel
 * extents, reported edges and scale, an inset crop that must be entirely marker colour, and a one-pixel ring outside
 * the marker that must be background. Any deviation is listed.
 */
public final class CheckCropGeometry {
    /** Crop-box left in user space. */
    private static final double CROP_LEFT = 20;
    /** Crop-box bottom in user space. */
    private static final double CROP_BOTTOM = 30;
    /** Crop-box width. */
    private static final double WIDTH = 240;
    /** Crop-box height. */
    private static final double HEIGHT = 150;
    /** Marker rectangle in user space: left, bottom, right, top. */
    private static final double[] MARKER = {100, 60, 140, 100};

    /** Prevents construction. */
    private CheckCropGeometry() {
    }

    /**
     * Runs one probe family.
     *
     * @param args probe ({@code ROTATION}) and fresh report
     * @throws Exception if authoring or rendering fails
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 2 || !List.of("ROTATION", "USER_UNIT", "SAMPLING", "BUDGET").contains(args[0])) {
            throw new IllegalArgumentException("ROTATION|USER_UNIT|SAMPLING|BUDGET report");
        }
        if (args[0].equals("BUDGET")) {
            budget(Path.of(args[1]));
            return;
        }
        if (args[0].equals("SAMPLING")) {
            sampling(Path.of(args[1]));
            return;
        }
        if (args[0].equals("USER_UNIT")) {
            userUnit(Path.of(args[1]));
            return;
        }
        final var rows = new ArrayList<Map<String, Object>>();
        int deviations = 0;
        for (final int dpi : List.of(72, 150, 211)) {
            for (final int rotation : List.of(0, 90, 180, 270)) {
                final byte[] pdf = page(rotation, 1);
                final double[] displayed = displayed(rotation);
                final double ppu = dpi / 72.0;
                final var renderer =
                        PdfPageCropRenderer.create(new PdfPageCropOptions(dpi, PdfPageCropOptions.Background.WHITE,
                                10 * 1024 * 1024, 64, 16, 16_000_000, 64_000_000, 16_000_000, 32 * 1024 * 1024));
                final double inset = 2;
                final var crops = renderer.render(DocumentSource.bytes(pdf, "geometry.pdf"),
                        List.of(new PdfPageCropRequest(1, displayed[0], displayed[1], displayed[2], displayed[3]),
                                new PdfPageCropRequest(1, displayed[0] + inset, displayed[1] + inset,
                                        displayed[2] - inset, displayed[3] - inset),
                                new PdfPageCropRequest(
                                        1, displayed[0] - 6, displayed[1] - 6, displayed[2] + 6, displayed[3] + 6)));
                final var issues = new ArrayList<String>();
                final PdfPageCrop exact = crops.get(0);
                final int[] expected = {(int) Math.floor(displayed[0] * ppu), (int) Math.floor(displayed[1] * ppu),
                        (int) Math.ceil(displayed[2] * ppu), (int) Math.ceil(displayed[3] * ppu)};
                if (exact.pixelLeft() != expected[0] || exact.pixelTop() != expected[1]
                        || exact.pixelLeft() + exact.width() != expected[2]
                        || exact.pixelTop() + exact.height() != expected[3]) {
                    issues.add("pixel extents "
                               + List.of(exact.pixelLeft(), exact.pixelTop(), exact.pixelLeft() + exact.width(),
                                       exact.pixelTop() + exact.height())
                               + " expected " + List.of(expected[0], expected[1], expected[2], expected[3]));
                }
                if (Math.abs(exact.left() - expected[0] / ppu) > 1e-9
                        || Math.abs(exact.bottom() - expected[3] / ppu) > 1e-9) {
                    issues.add("reported edges differ from rounded pixel edges");
                }
                if (Math.abs(exact.pixelsPerUnit() - ppu) > 1e-12 || exact.pageRotation() != rotation
                        || exact.clipped()) {
                    issues.add("scale/rotation/clipped " + exact.pixelsPerUnit() + "/" + exact.pageRotation() + "/"
                               + exact.clipped());
                }
                final boolean rotated = rotation == 90 || rotation == 270;
                final int pageWidth = (int) Math.floor((rotated ? HEIGHT : WIDTH) * ppu);
                final int pageHeight = (int) Math.floor((rotated ? WIDTH : HEIGHT) * ppu);
                if (Math.abs(exact.pageWidthPixels() - pageWidth) > 1
                        || Math.abs(exact.pageHeightPixels() - pageHeight) > 1) {
                    issues.add("page raster " + exact.pageWidthPixels() + "x" + exact.pageHeightPixels()
                               + " expected about " + pageWidth + "x" + pageHeight);
                }
                final var insetImage = decode(crops.get(1));
                int nonRed = 0;
                for (int y = 0; y < insetImage.getHeight(); y++) {
                    for (int x = 0; x < insetImage.getWidth(); x++) {
                        nonRed += red(insetImage.getRGB(x, y)) ? 0 : 1;
                    }
                }
                if (nonRed > 0) {
                    issues.add("inset crop has " + nonRed + " non-marker pixels");
                }
                final var outer = decode(crops.get(2));
                final PdfPageCrop outerCrop = crops.get(2);
                int ringRed = 0;
                int ringPixels = 0;
                for (int y = 0; y < outer.getHeight(); y++) {
                    for (int x = 0; x < outer.getWidth(); x++) {
                        final double ux = (outerCrop.pixelLeft() + x + 0.5) / ppu;
                        final double uy = (outerCrop.pixelTop() + y + 0.5) / ppu;
                        final boolean ring = (ux < displayed[0] - 1 || ux > displayed[2] + 1 || uy < displayed[1] - 1
                                              || uy > displayed[3] + 1);
                        if (ring) {
                            ringPixels++;
                            ringRed += white(outer.getRGB(x, y)) ? 0 : 1;
                        }
                    }
                }
                if (ringRed > 0) {
                    issues.add("background ring has " + ringRed + " non-white pixels of " + ringPixels);
                }
                deviations += issues.size();
                final var row = new LinkedHashMap<String, Object>();
                row.put("dpi", dpi);
                row.put("rotation", rotation);
                row.put("expected_displayed", List.of(displayed[0], displayed[1], displayed[2], displayed[3]));
                row.put("expected_pixels", List.of(expected[0], expected[1], expected[2], expected[3]));
                row.put("issues", issues);
                rows.add(row);
                System.out.println(row);
            }
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put("probe", args[0]);
        report.put("scope", "Owned rotated/offset crop-box pages; expectations derived from the documented contract.");
        report.put("java_version", System.getProperty("java.version"));
        report.put("deviations", deviations);
        report.put("rows", rows);
        Files.writeString(Path.of(args[1]),
                JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Probes UserUnit pages with USER_SPACE and POINTS requests.
     *
     * <p>Expected pixel edges are the exact decimal products of each edge with the renderer's single-precision scale
     * {@code (float) (dpi x UserUnit / 72)} (POINTS edges divided by UserUnit), floored for left/top and ceiled for
     * right/bottom, as documented; the scale itself is computed here, not read from the result.
     *
     * @param output fresh report
     * @throws Exception if authoring or rendering fails
     */
    private static void userUnit(final Path output) throws Exception {
        final var rows = new ArrayList<Map<String, Object>>();
        int deviations = 0;
        for (final float unit : List.of(2.0f, 0.5f)) {
            for (final int dpi : List.of(72, 150)) {
                final byte[] pdf = page(0, unit);
                final double[] displayed = displayed(0);
                final float scale = (float) (dpi * (double) unit / 72.0);
                final var renderer =
                        PdfPageCropRenderer.create(new PdfPageCropOptions(dpi, PdfPageCropOptions.Background.WHITE,
                                10 * 1024 * 1024, 64, 16, 16_000_000, 64_000_000, 16_000_000, 32 * 1024 * 1024));
                final var requests = new ArrayList<PdfPageCropRequest>();
                for (final var units : PdfPageCropRequest.Units.values()) {
                    final double factor = units == PdfPageCropRequest.Units.POINTS ? unit : 1;
                    requests.add(new PdfPageCropRequest(1, displayed[0] * factor, displayed[1] * factor,
                            displayed[2] * factor, displayed[3] * factor, units));
                    requests.add(new PdfPageCropRequest(1, (displayed[0] + 2) * factor, (displayed[1] + 2) * factor,
                            (displayed[2] - 2) * factor, (displayed[3] - 2) * factor, units));
                }
                final var crops = renderer.render(DocumentSource.bytes(pdf, "unit.pdf"), requests);
                final var issues = new ArrayList<String>();
                for (int index = 0; index < crops.size(); index++) {
                    final var crop = crops.get(index);
                    final var request = crop.request();
                    final double factor = request.units() == PdfPageCropRequest.Units.POINTS ? unit : 1;
                    final int[] expected = {edge(request.left(), scale, factor, java.math.RoundingMode.FLOOR),
                            edge(request.top(), scale, factor, java.math.RoundingMode.FLOOR),
                            edge(request.right(), scale, factor, java.math.RoundingMode.CEILING),
                            edge(request.bottom(), scale, factor, java.math.RoundingMode.CEILING)};
                    final int[] actual = {crop.pixelLeft(), crop.pixelTop(), crop.pixelLeft() + crop.width(),
                            crop.pixelTop() + crop.height()};
                    if (!java.util.Arrays.equals(expected, actual)) {
                        issues.add(request.units() + " request " + index + " pixels "
                                   + java.util.Arrays.toString(actual) + " expected "
                                   + java.util.Arrays.toString(expected));
                    }
                    if (Math.abs(crop.userUnit() - unit) > 1e-6 || Math.abs(crop.pixelsPerUnit() - scale) > 1e-6) {
                        issues.add("reported unit/scale " + crop.userUnit() + "/" + crop.pixelsPerUnit());
                    }
                    if (index % 2 == 1) {
                        final var raster = decode(crop);
                        int other = 0;
                        for (int y = 0; y < raster.getHeight(); y++) {
                            for (int x = 0; x < raster.getWidth(); x++) {
                                other += red(raster.getRGB(x, y)) ? 0 : 1;
                            }
                        }
                        if (other > 0) {
                            issues.add(request.units() + " inset crop has " + other + " non-marker pixels");
                        }
                    }
                }
                final int pageWidth = (int) Math.max(Math.floor(WIDTH * scale), 1);
                final int pageHeight = (int) Math.max(Math.floor(HEIGHT * scale), 1);
                if (crops.getFirst().pageWidthPixels() != pageWidth
                        || crops.getFirst().pageHeightPixels() != pageHeight) {
                    issues.add("page raster " + crops.getFirst().pageWidthPixels() + "x"
                               + crops.getFirst().pageHeightPixels() + " expected " + pageWidth + "x" + pageHeight);
                }
                deviations += issues.size();
                final var row = new LinkedHashMap<String, Object>();
                row.put("user_unit", unit);
                row.put("dpi", dpi);
                row.put("renderer_scale", scale);
                row.put("issues", issues);
                rows.add(row);
                System.out.println(row);
            }
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put("probe", "USER_UNIT");
        report.put(
                "scope", "Owned UserUnit pages; USER_SPACE and POINTS requests; documented renderer-scale rounding.");
        report.put("java_version", System.getProperty("java.version"));
        report.put("deviations", deviations);
        report.put("rows", rows);
        Files.writeString(output,
                JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Rounds one request edge exactly with the renderer scale.
     *
     * @param value request edge
     * @param scale renderer single-precision scale
     * @param unit divisor for POINTS (UserUnit) or 1
     * @param mode rounding direction
     * @return pixel boundary
     */
    private static int edge(
            final double value, final float scale, final double unit, final java.math.RoundingMode mode) {
        final var product = new java.math.BigDecimal(value).multiply(new java.math.BigDecimal(scale));
        return (unit == 1 ? product.setScale(0, mode) : product.divide(new java.math.BigDecimal(unit), 0, mode))
                .intValueExact();
    }

    /**
     * Probes transparent 4x4 box sampling at a pixel half covered by an opaque red marker.
     *
     * <p>The marker's left edge lies at displayed x 10.5 at 72 DPI, an exact boundary of the 4x render grid, so output
     * pixel 10 averages two covered and two uncovered sample columns. Expected: pixel 10 alpha 127-128 with
     * unpremultiplied red at least 247 and green/blue at most 8; pixel 11 opaque red; pixel 9 alpha 0.
     *
     * @param output fresh report
     * @throws Exception if authoring or rendering fails
     */
    private static void sampling(final Path output) throws Exception {
        final byte[] pdf;
        try (var document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(200, 200));
            document.addPage(page);
            try (var content = new PDPageContentStream(document, page)) {
                content.setNonStrokingColor(Color.RED);
                content.addRect(10.5f, 50, 19.5f, 30);
                content.fill();
            }
            final var bytes = new ByteArrayOutputStream();
            document.save(bytes);
            pdf = bytes.toByteArray();
        }
        final var rows = new ArrayList<Map<String, Object>>();
        int deviations = 0;
        for (final var sampling : PdfPageCropOptions.Sampling.values()) {
            final var renderer =
                    PdfPageCropRenderer.create(new PdfPageCropOptions(72, PdfPageCropOptions.Background.TRANSPARENT,
                            10 * 1024 * 1024, 64, 16, 16_000_000, 64_000_000, 16_000_000, 32 * 1024 * 1024, sampling));
            final var crop = renderer.render(DocumentSource.bytes(pdf, "sampling.pdf"),
                                             List.of(new PdfPageCropRequest(1, 5, 125, 35, 145)))
                                     .getFirst();
            final var raster = decode(crop);
            final int y = 135 - crop.pixelTop();
            final var samples = new LinkedHashMap<String, Object>();
            final var issues = new ArrayList<String>();
            for (final int x : List.of(9, 10, 11)) {
                final int argb = raster.getRGB(x - crop.pixelLeft(), y);
                final int alpha = argb >>> 24;
                final int red = (argb >> 16) & 255;
                final int green = (argb >> 8) & 255;
                final int blue = argb & 255;
                samples.put("x" + x, List.of(alpha, red, green, blue));
                if (sampling == PdfPageCropOptions.Sampling.BOX_4X) {
                    if (x == 9 && alpha != 0) {
                        issues.add("pixel 9 alpha " + alpha);
                    }
                    if (x == 10 && (alpha < 127 || alpha > 128 || red < 247 || green > 8 || blue > 8)) {
                        issues.add("pixel 10 " + List.of(alpha, red, green, blue));
                    }
                    if (x == 11 && (alpha < 247 || red < 247 || green > 8 || blue > 8)) {
                        issues.add("pixel 11 " + List.of(alpha, red, green, blue));
                    }
                }
            }
            deviations += issues.size();
            final var row = new LinkedHashMap<String, Object>();
            row.put("sampling", sampling.name());
            row.put("argb_samples", samples);
            row.put("issues", issues);
            rows.add(row);
            System.out.println(row);
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put("probe", "SAMPLING");
        report.put("scope", "Owned half-covered pixel; transparent background; renderer and 4x box sampling.");
        report.put("java_version", System.getProperty("java.version"));
        report.put("deviations", deviations);
        report.put("rows", rows);
        Files.writeString(output,
                JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Probes documented budget and typed-error boundaries on owned pages (240x150 displayed units at 72 DPI).
     *
     * @param output fresh report
     * @throws Exception if authoring fails
     */
    private static void budget(final Path output) throws Exception {
        final byte[] one = page(0, 1);
        final byte[] two;
        try (var document = new PDDocument()) {
            document.addPage(new PDPage(new PDRectangle(240, 150)));
            document.addPage(new PDPage(new PDRectangle(240, 150)));
            final var bytes = new ByteArrayOutputStream();
            document.save(bytes);
            two = bytes.toByteArray();
        }
        final var inside = new PdfPageCropRequest(1, 80, 80, 120, 120);
        final var rows = new ArrayList<Map<String, Object>>();
        int deviations = 0;
        record Case(String name, byte[] pdf, PdfPageCropOptions options, List<PdfPageCropRequest> requests,
                String expected) {}
        final var cases = List.of(new Case("crop exactly at maxCropPixels", one,
                                          options(16, 36_000, 1_600, 1_000_000, PdfPageCropOptions.Sampling.RENDERER),
                                          List.of(inside), "OK"),
                new Case("crop one pixel over maxCropPixels", one,
                        options(16, 36_000, 1_599, 1_000_000, PdfPageCropOptions.Sampling.RENDERER), List.of(inside),
                        "IMAGE_LIMIT"),
                new Case("request outside the page", one,
                        options(16, 36_000, 1_600, 1_000_000, PdfPageCropOptions.Sampling.RENDERER),
                        List.of(new PdfPageCropRequest(1, 300, 300, 310, 310)), "UNSUPPORTED_OPTION"),
                new Case("missing page", one,
                        options(16, 36_000, 1_600, 1_000_000, PdfPageCropOptions.Sampling.RENDERER),
                        List.of(new PdfPageCropRequest(2, 1, 1, 2, 2)), "UNSUPPORTED_OPTION"),
                new Case("page exactly at maxPagePixels", one,
                        options(16, 36_000, 1_600, 1_000_000, PdfPageCropOptions.Sampling.RENDERER), List.of(inside),
                        "OK"),
                new Case("page one pixel over maxPagePixels", one,
                        options(16, 35_999, 1_600, 1_000_000, PdfPageCropOptions.Sampling.RENDERER), List.of(inside),
                        "IMAGE_LIMIT"),
                new Case("supersampled page at maxPagePixels", one,
                        options(16, 576_000, 1_600, 1_000_000, PdfPageCropOptions.Sampling.BOX_4X), List.of(inside),
                        "OK"),
                new Case("supersampled page one pixel over", one,
                        options(16, 575_999, 1_600, 1_000_000, PdfPageCropOptions.Sampling.BOX_4X), List.of(inside),
                        "IMAGE_LIMIT"),
                new Case("more distinct pages than maxPages", two,
                        new PdfPageCropOptions(72, PdfPageCropOptions.Background.WHITE, 10 * 1024 * 1024, 64, 1, 36_000,
                                72_000, 1_600, 1_000_000),
                        List.of(new PdfPageCropRequest(1, 1, 1, 2, 2), new PdfPageCropRequest(2, 1, 1, 2, 2)),
                        "IMAGE_LIMIT"),
                new Case("more requests than maxRequests", one,
                        new PdfPageCropOptions(72, PdfPageCropOptions.Background.WHITE, 10 * 1024 * 1024, 2, 16, 36_000,
                                72_000, 1_600, 1_000_000),
                        List.of(inside, inside, inside), "IMAGE_LIMIT"),
                new Case("encoded bytes over maxEncodedBytes", one,
                        options(16, 36_000, 1_600, 10, PdfPageCropOptions.Sampling.RENDERER), List.of(inside),
                        "IMAGE_LIMIT"));
        for (final var test : cases) {
            String outcome;
            try {
                final var crops = PdfPageCropRenderer.create(test.options())
                                          .render(DocumentSource.bytes(test.pdf(), "budget.pdf"), test.requests());
                outcome = crops.size() == test.requests().size() ? "OK" : "PARTIAL";
            } catch (final dev.docweave.partition.PartitionException failure) {
                outcome = failure.code().name();
            } catch (final java.io.IOException failure) {
                outcome = "UNTYPED_IO";
            }
            final var issues = outcome.equals(test.expected())
                                       ? List.<String>of()
                                       : List.of("outcome " + outcome + " expected " + test.expected());
            deviations += issues.size();
            final var row = new LinkedHashMap<String, Object>();
            row.put("case", test.name());
            row.put("outcome", outcome);
            row.put("expected", test.expected());
            row.put("issues", issues);
            rows.add(row);
            System.out.println(row);
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put("probe", "BUDGET");
        report.put("scope", "Owned pages; documented crop budgets and typed failures; expectations from the contract.");
        report.put("java_version", System.getProperty("java.version"));
        report.put("deviations", deviations);
        report.put("rows", rows);
        Files.writeString(output,
                JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Builds 72 DPI options with explicit budgets.
     *
     * @param pages maximum pages
     * @param pagePixels maximum page-raster pixels (total twice that)
     * @param cropPixels maximum crop pixels
     * @param encoded maximum encoded bytes
     * @param sampling sampling policy
     * @return options
     */
    private static PdfPageCropOptions options(final int pages, final long pagePixels, final long cropPixels,
            final int encoded, final PdfPageCropOptions.Sampling sampling) {
        return new PdfPageCropOptions(72, PdfPageCropOptions.Background.WHITE, 10 * 1024 * 1024, 64, pages, pagePixels,
                2 * pagePixels, cropPixels, encoded, sampling);
    }

    /**
     * Derives the marker's displayed rectangle independently of the implementation.
     *
     * @param rotation clockwise page rotation
     * @return displayed left, top, right, bottom in unscaled user-space units
     */
    static double[] displayed(final int rotation) {
        // Crop-box relative, top-left origin before rotation.
        final double left = MARKER[0] - CROP_LEFT;
        final double right = MARKER[2] - CROP_LEFT;
        final double top = HEIGHT - (MARKER[3] - CROP_BOTTOM);
        final double bottom = HEIGHT - (MARKER[1] - CROP_BOTTOM);
        return switch (rotation) {
            case 0 -> new double[] {left, top, right, bottom};
            case 90 -> new double[] {HEIGHT - bottom, left, HEIGHT - top, right};
            case 180 -> new double[] {WIDTH - right, HEIGHT - bottom, WIDTH - left, HEIGHT - top};
            case 270 -> new double[] {top, WIDTH - right, bottom, WIDTH - left};
            default -> throw new IllegalArgumentException("rotation");
        };
    }

    /**
     * Authors the owned page.
     *
     * @param rotation page rotation
     * @param userUnit page UserUnit
     * @return PDF bytes
     * @throws Exception if authoring fails
     */
    static byte[] page(final int rotation, final float userUnit) throws Exception {
        try (var document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(300, 200));
            page.setCropBox(new PDRectangle((float) CROP_LEFT, (float) CROP_BOTTOM, (float) WIDTH, (float) HEIGHT));
            page.setRotation(rotation);
            if (userUnit != 1) {
                page.getCOSObject().setFloat(org.apache.pdfbox.cos.COSName.getPDFName("UserUnit"), userUnit);
            }
            document.addPage(page);
            try (var content = new PDPageContentStream(document, page)) {
                content.setNonStrokingColor(Color.RED);
                content.addRect((float) MARKER[0], (float) MARKER[1], (float) (MARKER[2] - MARKER[0]),
                        (float) (MARKER[3] - MARKER[1]));
                content.fill();
            }
            final var output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        }
    }

    /**
     * Decodes a crop.
     *
     * @param crop crop
     * @return raster
     * @throws Exception if decoding fails
     */
    static BufferedImage decode(final PdfPageCrop crop) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(crop.png()));
    }

    /**
     * Tests the marker colour within eight levels.
     *
     * @param rgb packed pixel
     * @return marker colour
     */
    static boolean red(final int rgb) {
        return ((rgb >> 16) & 255) >= 247 && ((rgb >> 8) & 255) <= 8 && (rgb & 255) <= 8;
    }

    /**
     * Tests the white background within eight levels.
     *
     * @param rgb packed pixel
     * @return background colour
     */
    static boolean white(final int rgb) {
        return ((rgb >> 16) & 255) >= 247 && ((rgb >> 8) & 255) >= 247 && (rgb & 255) >= 247;
    }
}
