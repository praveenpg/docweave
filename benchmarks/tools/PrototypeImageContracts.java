package dev.docweave.validation;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;
import tools.jackson.databind.json.JsonMapper;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares candidate image-appearance contracts on owned fixtures; evaluation only, no runtime API.
 *
 * <p>Prototypes: {@code ISOLATED} paints only the target image invocation by suppressing every other painting
 * operator (the earlier prototype); {@code ISOLATED_MASK_AWARE} suppresses other painting only outside soft-mask
 * group rendering, because mask groups define coverage rather than page paint; {@code VISIBLE} marks pixels of the
 * complete page that change when only the target is suppressed. The base fixtures are the eight owned fixtures of
 * {@link PrototypeImageAppearance}, reused unchanged; expectations are hand-derived and every sample is reported.
 */
public final class PrototypeImageContracts {
    /** Prevents construction. */
    private PrototypeImageContracts() {
    }

    /** Rendering prototypes. */
    enum Prototype {
        /** Earlier full suppression of non-target painting. */
        ISOLATED,
        /** Suppression outside soft-mask group rendering only. */
        ISOLATED_MASK_AWARE
    }

    /**
     * Runs the prototypes on the requested fixture set.
     *
     * @param args fixture set ({@code BASE}) and fresh report path
     * @throws Exception if authoring or rendering fails
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 2 || !List.of("BASE", "GROUPS", "BLEND").contains(args[0])) {
            throw new IllegalArgumentException("BASE|GROUPS|BLEND report");
        }
        if (!args[0].equals("BASE")) {
            groups(Path.of(args[1]), args[0].equals("GROUPS") ? List.of(Group.NESTED, Group.KNOCKOUT, Group.BACKDROP)
                                                              : List.of(Group.MULTIPLY_BLUE, Group.SAME_COLOUR,
                                                                        Group.HALF_WHITE_OVERPAINT));
            return;
        }
        final var fixtureMethod =
                PrototypeImageAppearance.class.getDeclaredMethod("fixture", PrototypeImageAppearance.Fixture.class);
        fixtureMethod.setAccessible(true);
        final var rows = new ArrayList<Map<String, Object>>();
        final var failures = new LinkedHashMap<String, Integer>();
        for (final var fixture : PrototypeImageAppearance.Fixture.values()) {
            final int target = fixture == PrototypeImageAppearance.Fixture.REPEATED ? 1 : 0;
            try (final var document = (PDDocument) fixtureMethod.invoke(null, fixture)) {
                final var row = new LinkedHashMap<String, Object>();
                row.put("fixture", fixture.name());
                final int[] xs = {50, 70, 140};
                for (final var prototype : Prototype.values()) {
                    final var image = render(document, target, prototype);
                    final int[] alpha = new int[3];
                    for (int index = 0; index < 3; index++) {
                        alpha[index] = image.getRGB(xs[index], 60) >>> 24;
                    }
                    final int[] expected = expectedIsolated(fixture);
                    final boolean pass = within(alpha, expected);
                    failures.merge(prototype.name(), pass ? 0 : 1, Integer::sum);
                    row.put(prototype.name().toLowerCase() + "_alpha", List.of(alpha[0], alpha[1], alpha[2]));
                    row.put(prototype.name().toLowerCase() + "_pass", pass);
                }
                row.put("isolated_expected", List.of(expectedIsolated(fixture)[0], expectedIsolated(fixture)[1],
                                                     expectedIsolated(fixture)[2]));
                rows.add(row);
                System.out.println(row);
            }
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put("scope", "Owned-fixture isolated-contribution prototypes (full versus mask-aware suppression); "
                                    + "PDFBox rendering at 72 DPI; evaluation only, no runtime API.");
        report.put("java_version", System.getProperty("java.version"));
        report.put("failures", failures);
        report.put("fixtures", rows);
        Files.writeString(Path.of(args[1]),
                JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Returns the hand-derived isolated alpha expectations of the base fixtures at x 50, 70 and 140.
     *
     * @param fixture fixture
     * @return expected alpha values
     */
    static int[] expectedIsolated(final PrototypeImageAppearance.Fixture fixture) {
        return switch (fixture) {
            case PLAIN, OVERPAINT, MULTIPLY -> new int[] {255, 255, 0};
            case CLIP, SOFT_MASK -> new int[] {255, 0, 0};
            case ALPHA, GROUP_ALPHA -> new int[] {128, 128, 0};
            case REPEATED -> new int[] {0, 0, 255};
        };
    }

    /**
     * Compares samples within eight levels.
     *
     * @param actual actual values
     * @param expected expected values
     * @return whether every sample matches
     */
    static boolean within(final int[] actual, final int[] expected) {
        for (int index = 0; index < actual.length; index++) {
            if (Math.abs(actual[index] - expected[index]) > 8) {
                return false;
            }
        }
        return true;
    }

    /**
     * Renders page one at 72 DPI on a transparent backdrop, painting only the target image occurrence.
     *
     * @param document fixture
     * @param target zero-based image occurrence
     * @param prototype suppression policy
     * @return ARGB raster
     * @throws IOException if rendering fails
     */
    static BufferedImage render(final PDDocument document, final int target, final Prototype prototype)
            throws IOException {
        final var renderer = new PDFRenderer(document) {
            @Override
            protected PageDrawer createPageDrawer(final PageDrawerParameters parameters) throws IOException {
                return new PageDrawer(parameters) {
                    /** Image occurrences met so far in page content. */
                    private int seen;
                    /** Depth of soft-mask group rendering. */
                    private int masks;

                    /**
                     * Tests whether non-target painting must be suppressed here.
                     *
                     * @return true outside mask rendering, or always for the earlier prototype
                     */
                    private boolean suppress() {
                        return prototype == Prototype.ISOLATED || masks == 0;
                    }

                    @Override
                    protected void processSoftMask(final PDTransparencyGroup group) throws IOException {
                        masks++;
                        try {
                            super.processSoftMask(group);
                        } finally {
                            masks--;
                        }
                    }

                    @Override
                    public void drawImage(final PDImage image) throws IOException {
                        if (!suppress()) {
                            super.drawImage(image);
                        } else if (seen++ == target) {
                            super.drawImage(image);
                        }
                    }

                    @Override
                    public void fillPath(final int windingRule) throws IOException {
                        if (suppress()) {
                            getLinePath().reset();
                        } else {
                            super.fillPath(windingRule);
                        }
                    }

                    @Override
                    public void strokePath() throws IOException {
                        if (suppress()) {
                            getLinePath().reset();
                        } else {
                            super.strokePath();
                        }
                    }

                    @Override
                    public void fillAndStrokePath(final int windingRule) throws IOException {
                        if (suppress()) {
                            getLinePath().reset();
                        } else {
                            super.fillAndStrokePath(windingRule);
                        }
                    }

                    @Override
                    public void shadingFill(final COSName name) throws IOException {
                        if (!suppress()) {
                            super.shadingFill(name);
                        }
                    }
                };
            }
        };
        return renderer.renderImageWithDPI(0, 72, ImageType.ARGB);
    }

    /** Round-2 group fixtures. */
    enum Group {
        /** Image in an inner alpha-0.5 group inside an outer alpha-0.5 group. */
        NESTED,
        /** Isolated knockout group: an earlier opaque blue fill under an opaque image. */
        KNOCKOUT,
        /** Wide image under a luminosity soft mask whose white backdrop applies outside the mask bounding box. */
        BACKDROP,
        /** Multiply-blended image over an opaque blue backdrop. */
        MULTIPLY_BLUE,
        /** Red image over an identical red backdrop: an invisible contribution. */
        SAME_COLOUR,
        /** Half-transparent white fill painted over the image afterwards. */
        HALF_WHITE_OVERPAINT
    }

    /**
     * Runs the mask-aware isolated and final-visibility prototypes on the selected group fixtures.
     *
     * @param output fresh report
     * @param fixtures fixtures to run
     * @throws Exception if authoring or rendering fails
     */
    private static void groups(final Path output, final List<Group> fixtures) throws Exception {
        final var rows = new ArrayList<Map<String, Object>>();
        final int[] xs = {50, 70, 140};
        int failures = 0;
        for (final var fixture : fixtures) {
            try (final var document = group(fixture)) {
                final var isolated = render(document, 0, Prototype.ISOLATED_MASK_AWARE);
                final var full = page(document, -1);
                final var without = page(document, 0);
                final int[] alpha = new int[3];
                final int[] visible = new int[3];
                for (int index = 0; index < 3; index++) {
                    alpha[index] = isolated.getRGB(xs[index], 60) >>> 24;
                    visible[index] =
                            (full.getRGB(xs[index], 60) & 0xffffff) != (without.getRGB(xs[index], 60) & 0xffffff) ? 255
                                                                                                                  : 0;
                }
                final int[] expectedAlpha = switch (fixture) {
                    case NESTED -> new int[] {64, 64, 0};
                    case KNOCKOUT, MULTIPLY_BLUE, SAME_COLOUR, HALF_WHITE_OVERPAINT -> new int[] {255, 255, 0};
                    case BACKDROP -> new int[] {255, 0, 255};
                };
                final int[] expectedVisible = switch (fixture) {
                    case NESTED, KNOCKOUT, MULTIPLY_BLUE, HALF_WHITE_OVERPAINT -> new int[] {255, 255, 0};
                    case BACKDROP -> new int[] {255, 0, 255};
                    case SAME_COLOUR -> new int[] {0, 0, 0};
                };
                final boolean alphaPass = within(alpha, expectedAlpha);
                final boolean visiblePass = within(visible, expectedVisible);
                failures += (alphaPass ? 0 : 1) + (visiblePass ? 0 : 1);
                final var row = new LinkedHashMap<String, Object>();
                row.put("fixture", fixture.name());
                row.put("mask_aware_isolated_alpha", List.of(alpha[0], alpha[1], alpha[2]));
                row.put("mask_aware_isolated_expected", List.of(expectedAlpha[0], expectedAlpha[1], expectedAlpha[2]));
                row.put("mask_aware_isolated_pass", alphaPass);
                row.put("visible", List.of(visible[0], visible[1], visible[2]));
                row.put("visible_expected", List.of(expectedVisible[0], expectedVisible[1], expectedVisible[2]));
                row.put("visible_pass", visiblePass);
                rows.add(row);
                System.out.println(row);
            }
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put("scope", "Owned nested, knockout and soft-mask backdrop fixtures; mask-aware isolated and "
                                    + "final-visibility prototypes; PDFBox rendering at 72 DPI; evaluation only.");
        report.put("java_version", System.getProperty("java.version"));
        report.put("failures", failures);
        report.put("fixtures", rows);
        Files.writeString(output,
                JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Renders the complete page on white, optionally without one image occurrence.
     *
     * @param document fixture
     * @param target image occurrence to suppress, or -1
     * @return RGB raster
     * @throws IOException if rendering fails
     */
    private static BufferedImage page(final PDDocument document, final int target) throws IOException {
        final var renderer = new PDFRenderer(document) {
            @Override
            protected PageDrawer createPageDrawer(final PageDrawerParameters parameters) throws IOException {
                return new PageDrawer(parameters) {
                    /** Image occurrences met so far in page content. */
                    private int seen;
                    /** Depth of soft-mask group rendering. */
                    private int masks;

                    @Override
                    protected void processSoftMask(final PDTransparencyGroup group) throws IOException {
                        masks++;
                        try {
                            super.processSoftMask(group);
                        } finally {
                            masks--;
                        }
                    }

                    @Override
                    public void drawImage(final PDImage image) throws IOException {
                        if (masks > 0 || seen++ != target) {
                            super.drawImage(image);
                        }
                    }
                };
            }
        };
        return renderer.renderImageWithDPI(0, 72, ImageType.RGB);
    }

    /**
     * Authors one group fixture on a 200x200 page; the red image occupies displayed rows 40-80.
     *
     * @param fixture variant
     * @return open document; caller closes it
     * @throws IOException if authoring fails
     */
    private static PDDocument group(final Group fixture) throws IOException {
        final var document = new PDDocument();
        final var page =
                new org.apache.pdfbox.pdmodel.PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(200, 200));
        document.addPage(page);
        final var red = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 40; y++) {
            for (int x = 0; x < 40; x++) {
                red.setRGB(x, y, 0xff0000);
            }
        }
        final var image = org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(document, red);
        try (final var content = new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)) {
            switch (fixture) {
            case NESTED -> {
                final var inner = form(document, false, false);
                try (final var stream = new org.apache.pdfbox.pdmodel.PDFormContentStream(inner)) {
                    stream.drawImage(image, 40, 120, 40, 40);
                }
                final var outer = form(document, false, false);
                try (final var stream = new org.apache.pdfbox.pdmodel.PDFormContentStream(outer)) {
                    stream.saveGraphicsState();
                    stream.setGraphicsStateParameters(alpha(0.5f));
                    stream.drawForm(inner);
                    stream.restoreGraphicsState();
                }
                content.saveGraphicsState();
                content.setGraphicsStateParameters(alpha(0.5f));
                content.drawForm(outer);
                content.restoreGraphicsState();
            }
            case KNOCKOUT -> {
                final var knockout = form(document, true, true);
                try (final var stream = new org.apache.pdfbox.pdmodel.PDFormContentStream(knockout)) {
                    stream.setNonStrokingColor(java.awt.Color.BLUE);
                    stream.addRect(30, 110, 60, 60);
                    stream.fill();
                    stream.drawImage(image, 40, 120, 40, 40);
                }
                content.drawForm(knockout);
            }
            case MULTIPLY_BLUE -> {
                content.setNonStrokingColor(java.awt.Color.BLUE);
                content.addRect(20, 100, 160, 80);
                content.fill();
                final var state = new org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState();
                state.setBlendMode(org.apache.pdfbox.pdmodel.graphics.blend.BlendMode.MULTIPLY);
                content.saveGraphicsState();
                content.setGraphicsStateParameters(state);
                content.drawImage(image, 40, 120, 40, 40);
                content.restoreGraphicsState();
            }
            case SAME_COLOUR -> {
                content.setNonStrokingColor(java.awt.Color.RED);
                content.addRect(20, 100, 160, 80);
                content.fill();
                content.drawImage(image, 40, 120, 40, 40);
            }
            case HALF_WHITE_OVERPAINT -> {
                content.drawImage(image, 40, 120, 40, 40);
                content.saveGraphicsState();
                content.setGraphicsStateParameters(alpha(0.5f));
                content.setNonStrokingColor(java.awt.Color.WHITE);
                content.addRect(30, 110, 60, 60);
                content.fill();
                content.restoreGraphicsState();
            }
            case BACKDROP -> {
                final var mask = form(document, false, false);
                mask.setBBox(new org.apache.pdfbox.pdmodel.common.PDRectangle(0, 0, 100, 200));
                mask.getCOSObject()
                        .getCOSDictionary(org.apache.pdfbox.cos.COSName.GROUP)
                        .setItem(org.apache.pdfbox.cos.COSName.CS, org.apache.pdfbox.cos.COSName.DEVICERGB);
                try (final var stream = new org.apache.pdfbox.pdmodel.PDFormContentStream(mask)) {
                    stream.setNonStrokingColor(java.awt.Color.WHITE);
                    stream.addRect(0, 0, 60, 200);
                    stream.fill();
                    stream.setNonStrokingColor(java.awt.Color.BLACK);
                    stream.addRect(60, 0, 40, 200);
                    stream.fill();
                }
                final var softMask = new org.apache.pdfbox.cos.COSDictionary();
                softMask.setItem(org.apache.pdfbox.cos.COSName.TYPE, org.apache.pdfbox.cos.COSName.MASK);
                softMask.setItem(org.apache.pdfbox.cos.COSName.S, org.apache.pdfbox.cos.COSName.LUMINOSITY);
                softMask.setItem(org.apache.pdfbox.cos.COSName.G, mask.getCOSObject());
                final var backdrop = new org.apache.pdfbox.cos.COSArray();
                for (int index = 0; index < 3; index++) {
                    backdrop.add(org.apache.pdfbox.cos.COSFloat.ONE);
                }
                softMask.setItem(org.apache.pdfbox.cos.COSName.BC, backdrop);
                final var state = new org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState();
                state.getCOSObject().setItem(org.apache.pdfbox.cos.COSName.SMASK, softMask);
                content.saveGraphicsState();
                content.setGraphicsStateParameters(state);
                content.drawImage(image, 40, 120, 120, 40);
                content.restoreGraphicsState();
            }
            }
        } catch (final IOException | RuntimeException failure) {
            document.close();
            throw failure;
        }
        return document;
    }

    /**
     * Creates a transparency-group form covering the page.
     *
     * @param document owner
     * @param isolated group isolation flag
     * @param knockout group knockout flag
     * @return empty form
     */
    private static org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject form(
            final PDDocument document, final boolean isolated, final boolean knockout) {
        final var form = new org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject(document);
        form.setBBox(new org.apache.pdfbox.pdmodel.common.PDRectangle(200, 200));
        form.setResources(new org.apache.pdfbox.pdmodel.PDResources());
        final var group = new org.apache.pdfbox.cos.COSDictionary();
        group.setItem(org.apache.pdfbox.cos.COSName.S, org.apache.pdfbox.cos.COSName.TRANSPARENCY);
        group.setBoolean(org.apache.pdfbox.cos.COSName.I, isolated);
        group.setBoolean(org.apache.pdfbox.cos.COSName.K, knockout);
        form.getCOSObject().setItem(org.apache.pdfbox.cos.COSName.GROUP, group);
        return form;
    }

    /**
     * Creates a non-stroking alpha state.
     *
     * @param value alpha
     * @return graphics state
     */
    private static org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState alpha(final float value) {
        final var state = new org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState();
        state.setNonStrokingAlphaConstant(value);
        return state;
    }
}
