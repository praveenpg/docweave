package io.docweave.validation;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDFormContentStream;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.blend.BlendMode;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;
import tools.jackson.databind.json.JsonMapper;

import java.awt.Color;
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
 * Prototypes two candidate output contracts for one image occurrence's appearance on owned fixtures; evaluation only.
 *
 * <p>{@code ISOLATED} renders only the target image invocation, with its full graphics state, onto a transparent
 * backdrop by suppressing every other painting operator. {@code VISIBLE} marks the pixels of the complete page that
 * change when only the target invocation is suppressed. Expected values are hand-derived from each fixture's
 * construction; the report records every sample and whether it is within eight levels of the expectation.
 */
public final class PrototypeImageAppearance {
    /** Prevents construction. */
    private PrototypeImageAppearance() {
    }

    /** Owned fixture variants. */
    enum Fixture {
        /** Plain placement. */
        PLAIN,
        /** Rectangular clip to the left half. */
        CLIP,
        /** Non-stroking alpha 0.5. */
        ALPHA,
        /** Later opaque overpaint. */
        OVERPAINT,
        /** Image inside a transparency group drawn with alpha 0.5. */
        GROUP_ALPHA,
        /** The same image drawn twice; the second occurrence is the target. */
        REPEATED,
        /** Multiply blend mode. */
        MULTIPLY,
        /** Luminosity soft mask: white left half, black right half. */
        SOFT_MASK
    }

    /**
     * Runs both prototypes on every fixture and writes the report.
     *
     * @param args fresh report path
     * @throws Exception if authoring or rendering fails
     */
    public static void main(final String[] args) throws Exception {
        final var rows = new ArrayList<Map<String, Object>>();
        int failures = 0;
        for (final var fixture : Fixture.values()) {
            final int target = fixture == Fixture.REPEATED ? 1 : 0;
            try (final var document = fixture(fixture)) {
                final var isolated = render(document, target, true);
                final var full = render(document, -1, false);
                final var without = render(document, target, false);
                final int[] xs = {50, 70, 140};
                final int[] isolatedAlpha = new int[3];
                final int[] visibleAlpha = new int[3];
                for (int index = 0; index < 3; index++) {
                    isolatedAlpha[index] = isolated.getRGB(xs[index], 60) >>> 24;
                    visibleAlpha[index] =
                            (full.getRGB(xs[index], 60) & 0xffffff) != (without.getRGB(xs[index], 60) & 0xffffff) ? 255
                                                                                                                  : 0;
                }
                final int[] expectedIsolated = expectedIsolated(fixture);
                final int[] expectedVisible = expectedVisible(fixture);
                final boolean isolatedPass = within(isolatedAlpha, expectedIsolated);
                final boolean visiblePass = within(visibleAlpha, expectedVisible);
                failures += (isolatedPass ? 0 : 1) + (visiblePass ? 0 : 1);
                final var row = new LinkedHashMap<String, Object>();
                row.put("fixture", fixture.name());
                row.put("sample_x", List.of(50, 70, 140));
                row.put("isolated_alpha", List.of(isolatedAlpha[0], isolatedAlpha[1], isolatedAlpha[2]));
                row.put("isolated_expected", List.of(expectedIsolated[0], expectedIsolated[1], expectedIsolated[2]));
                row.put("isolated_pass", isolatedPass);
                row.put("visible_alpha", List.of(visibleAlpha[0], visibleAlpha[1], visibleAlpha[2]));
                row.put("visible_expected", List.of(expectedVisible[0], expectedVisible[1], expectedVisible[2]));
                row.put("visible_pass", visiblePass);
                rows.add(row);
                System.out.println(row);
            }
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put("scope", "Owned-fixture prototypes of isolated-contribution and final-visibility contracts; "
                                    + "PDFBox rendering at 72 DPI; evaluation only, no runtime API.");
        report.put("java_version", System.getProperty("java.version"));
        report.put("failures", failures);
        report.put("fixtures", rows);
        Files.writeString(Path.of(args[0]),
                JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Returns hand-derived isolated alpha expectations at x 50, 70 and 140.
     *
     * @param fixture fixture
     * @return expected alpha values
     */
    private static int[] expectedIsolated(final Fixture fixture) {
        return switch (fixture) {
            case PLAIN, OVERPAINT, MULTIPLY -> new int[] {255, 255, 0};
            case CLIP, SOFT_MASK -> new int[] {255, 0, 0};
            case ALPHA, GROUP_ALPHA -> new int[] {128, 128, 0};
            case REPEATED -> new int[] {0, 0, 255};
        };
    }

    /**
     * Returns hand-derived visibility expectations at x 50, 70 and 140.
     *
     * @param fixture fixture
     * @return expected visibility values
     */
    private static int[] expectedVisible(final Fixture fixture) {
        return switch (fixture) {
            case PLAIN, ALPHA, GROUP_ALPHA, MULTIPLY -> new int[] {255, 255, 0};
            case CLIP, SOFT_MASK -> new int[] {255, 0, 0};
            case OVERPAINT -> new int[] {0, 0, 0};
            case REPEATED -> new int[] {0, 0, 255};
        };
    }

    /**
     * Compares samples with expectations.
     *
     * @param actual actual values
     * @param expected expected values
     * @return whether every sample is within eight levels
     */
    private static boolean within(final int[] actual, final int[] expected) {
        for (int index = 0; index < actual.length; index++) {
            if (Math.abs(actual[index] - expected[index]) > 8) {
                return false;
            }
        }
        return true;
    }

    /**
     * Renders the first page at 72 DPI with optional suppression.
     *
     * @param document fixture
     * @param target zero-based image occurrence, or -1 for none
     * @param isolated true to paint only the target; false to paint everything except the target
     * @return ARGB raster for isolation, RGB on white otherwise
     * @throws IOException if rendering fails
     */
    private static BufferedImage render(final PDDocument document, final int target, final boolean isolated)
            throws IOException {
        final var renderer = new PDFRenderer(document) {
            @Override
            protected PageDrawer createPageDrawer(final PageDrawerParameters parameters) throws IOException {
                return new PageDrawer(parameters) {
                    /** Image occurrences met so far on this page. */
                    private int seen;

                    @Override
                    public void drawImage(final PDImage image) throws IOException {
                        final boolean isTarget = seen++ == target;
                        if (isolated ? isTarget : !isTarget) {
                            super.drawImage(image);
                        }
                    }

                    @Override
                    public void fillPath(final int windingRule) throws IOException {
                        if (!isolated) {
                            super.fillPath(windingRule);
                        } else {
                            getLinePath().reset();
                        }
                    }

                    @Override
                    public void strokePath() throws IOException {
                        if (!isolated) {
                            super.strokePath();
                        } else {
                            getLinePath().reset();
                        }
                    }

                    @Override
                    public void fillAndStrokePath(final int windingRule) throws IOException {
                        if (!isolated) {
                            super.fillAndStrokePath(windingRule);
                        } else {
                            getLinePath().reset();
                        }
                    }

                    @Override
                    public void shadingFill(final COSName name) throws IOException {
                        if (!isolated) {
                            super.shadingFill(name);
                        }
                    }
                };
            }
        };
        return renderer.renderImageWithDPI(0, 72, isolated ? ImageType.ARGB : ImageType.RGB);
    }

    /**
     * Authors one owned fixture.
     *
     * @param fixture variant
     * @return open document; caller closes it
     * @throws IOException if authoring fails
     */
    private static PDDocument fixture(final Fixture fixture) throws IOException {
        final var document = new PDDocument();
        final var page = new PDPage(new PDRectangle(200, 200));
        document.addPage(page);
        final var red = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 40; y++) {
            for (int x = 0; x < 40; x++) {
                red.setRGB(x, y, 0xff0000);
            }
        }
        final PDImageXObject image = LosslessFactory.createFromImage(document, red);
        try (final var content = new PDPageContentStream(document, page)) {
            switch (fixture) {
            case PLAIN -> content.drawImage(image, 40, 120, 40, 40);
            case CLIP -> {
                content.saveGraphicsState();
                content.addRect(40, 120, 20, 40);
                content.clip();
                content.drawImage(image, 40, 120, 40, 40);
                content.restoreGraphicsState();
            }
            case ALPHA -> {
                final var state = new PDExtendedGraphicsState();
                state.setNonStrokingAlphaConstant(0.5f);
                content.saveGraphicsState();
                content.setGraphicsStateParameters(state);
                content.drawImage(image, 40, 120, 40, 40);
                content.restoreGraphicsState();
            }
            case OVERPAINT -> {
                content.drawImage(image, 40, 120, 40, 40);
                content.setNonStrokingColor(Color.BLUE);
                content.addRect(30, 110, 60, 60);
                content.fill();
            }
            case GROUP_ALPHA -> {
                final var form = new PDFormXObject(document);
                form.setBBox(new PDRectangle(200, 200));
                form.setResources(new org.apache.pdfbox.pdmodel.PDResources());
                final var group = new COSDictionary();
                group.setItem(COSName.S, COSName.TRANSPARENCY);
                form.getCOSObject().setItem(COSName.GROUP, group);
                try (final var inner = new PDFormContentStream(form)) {
                    inner.drawImage(image, 40, 120, 40, 40);
                }
                final var state = new PDExtendedGraphicsState();
                state.setNonStrokingAlphaConstant(0.5f);
                content.saveGraphicsState();
                content.setGraphicsStateParameters(state);
                content.drawForm(form);
                content.restoreGraphicsState();
            }
            case REPEATED -> {
                content.drawImage(image, 40, 120, 40, 40);
                content.drawImage(image, 120, 120, 40, 40);
            }
            case MULTIPLY -> {
                final var state = new PDExtendedGraphicsState();
                state.setBlendMode(BlendMode.MULTIPLY);
                content.saveGraphicsState();
                content.setGraphicsStateParameters(state);
                content.drawImage(image, 40, 120, 40, 40);
                content.restoreGraphicsState();
            }
            case SOFT_MASK -> {
                final var mask = new PDFormXObject(document);
                mask.setBBox(new PDRectangle(200, 200));
                mask.setResources(new org.apache.pdfbox.pdmodel.PDResources());
                final var group = new COSDictionary();
                group.setItem(COSName.S, COSName.TRANSPARENCY);
                group.setItem(COSName.CS, COSName.DEVICERGB);
                mask.getCOSObject().setItem(COSName.GROUP, group);
                try (final var inner = new PDFormContentStream(mask)) {
                    inner.setNonStrokingColor(Color.WHITE);
                    inner.addRect(0, 0, 60, 200);
                    inner.fill();
                    inner.setNonStrokingColor(Color.BLACK);
                    inner.addRect(60, 0, 140, 200);
                    inner.fill();
                }
                final var softMask = new COSDictionary();
                softMask.setItem(COSName.TYPE, COSName.MASK);
                softMask.setItem(COSName.S, COSName.LUMINOSITY);
                softMask.setItem(COSName.G, mask.getCOSObject());
                final var state = new PDExtendedGraphicsState();
                state.getCOSObject().setItem(COSName.SMASK, softMask);
                content.saveGraphicsState();
                content.setGraphicsStateParameters(state);
                content.drawImage(image, 40, 120, 40, 40);
                content.restoreGraphicsState();
            }
            }
        } catch (final IOException | RuntimeException failure) {
            document.close();
            throw failure;
        }
        return document;
    }
}
