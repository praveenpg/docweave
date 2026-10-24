package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.core.Element;
import io.docweave.core.ElementType;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDFormContentStream;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.RenderDestination;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Owned controls for explicit rendered page-region crops. Expected pixels are analytical: authored
 * opaque colors, a white backdrop and the PDF alpha/clip/paint-order rules. Tolerances are declared
 * once below, before any sample, and interior samples avoid antialiased boundary pixels.
 */
class PdfPageCropRendererTest {
    /** Allowed absolute per-channel difference from an analytical opaque or blended color. */
    private static final int CHANNEL_TOLERANCE = 3;

    /** Frozen SHA-256 of the 8x8 opaque red source PNG reported by the existing image contract packet. */
    private static final String FROZEN_SOURCE_PNG = "fc413db821e509581923499bea1ad6eaf402a408990859ea51c25765a6736b27";

    /** Red, green, blue and yellow quadrants, listed top-left, top-right, bottom-left, bottom-right. */
    private static final List<Color> QUADRANTS = List.of(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW);

    /** Creates independent controls. */
    PdfPageCropRendererTest() {
    }

    /**
     * Reproduces the three frozen image-contract controls through the new public service at 72 DPI.
     * Group alpha 0 yields background, alpha 0.5 blends red over white, and the half-width clip admits
     * only the left half; the original source PNG payloads remain the frozen opaque red raster.
     * @throws Exception if authoring, extraction or rendering fails
     */
    @Test
    void frozenControlsShowGroupOpacityAndRectangularClipWhileSourcePixelsStayRed() throws Exception {
        final byte[] source = contractControl();
        final var before = images(source);
        final var crops = renderer(PdfPageCropOptions.defaults().withDpi(72))
                                  .render(DocumentSource.bytes(source, "contract.pdf"),
                                          List.of(new PdfPageCropRequest(1, 40, 40, 80, 80),
                                                  new PdfPageCropRequest(2, 40, 40, 80, 80),
                                                  new PdfPageCropRequest(3, 40, 40, 80, 80)));
        assertEquals(3, crops.size());
        for (final var crop : crops) {
            assertEquals(40, crop.width());
            assertEquals(40, crop.height());
            assertEquals(40, crop.pixelLeft());
            assertEquals(40, crop.pixelTop());
            assertEquals(200, crop.pageWidthPixels());
            assertEquals(120, crop.pageHeightPixels());
            assertEquals(
                    List.of(40.0, 40.0, 80.0, 80.0), List.of(crop.left(), crop.top(), crop.right(), crop.bottom()));
            assertFalse(crop.clipped());
            assertEquals("image/png", crop.mimeType());
            assertEquals(PdfPageCrop.POLICY, crop.policy());
        }
        assertUniform(decode(crops.get(0)), 1, 1, 39, 39, Color.WHITE);
        assertUniform(decode(crops.get(1)), 1, 1, 39, 39, new Color(255, 128, 128));
        final var clipped = decode(crops.get(2));
        assertUniform(clipped, 1, 1, 19, 39, Color.RED);
        assertUniform(clipped, 21, 1, 39, 39, Color.WHITE);
        assertEquals(before, images(source));
        assertEquals(3, before.size());
        for (final var image : before) {
            assertEquals(FROZEN_SOURCE_PNG, sha256(sourcePng(image)));
        }
    }

    /**
     * A spatially distinct source inside an ordinary form, nested transparency group and clip. Correct
     * quadrant placement distinguishes positioning from a plausible uniform patch; the clipped strip is white.
     * @throws Exception if authoring or rendering fails
     */
    @Test
    void multicolorSourceInFormNestedGroupAndClipKeepsQuadrantPositions() throws Exception {
        final byte[] source;
        try (final var document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(200, 120));
            document.addPage(page);
            final var image = quadrants(document);
            final var inner = group(document, 1f, content -> content.drawImage(image, 40, 40, 40, 40));
            final var outer = group(document, 1f, content -> content.drawForm(inner));
            final var form = new PDFormXObject(document);
            form.setResources(new PDResources());
            form.setBBox(new PDRectangle(200, 120));
            try (final var content = new PDFormContentStream(form)) {
                content.addRect(40, 40, 30, 40);
                content.clip();
                content.drawForm(outer);
            }
            try (final var content = new PDPageContentStream(document, page)) {
                content.drawForm(form);
            }
            source = save(document);
        }
        final var crop =
                renderer(PdfPageCropOptions.defaults().withDpi(72))
                        .render(DocumentSource.bytes(source, null), List.of(new PdfPageCropRequest(1, 40, 40, 80, 80)))
                        .getFirst();
        final var pixels = decode(crop);
        assertUniform(pixels, 1, 1, 19, 19, Color.RED);
        assertUniform(pixels, 21, 1, 29, 19, Color.GREEN);
        assertUniform(pixels, 1, 21, 19, 39, Color.BLUE);
        assertUniform(pixels, 21, 21, 29, 39, Color.YELLOW);
        assertUniform(pixels, 31, 1, 39, 39, Color.WHITE);
        assertEquals(1, images(source).size());
    }

    /**
     * Later page paint covers part of an image; the crop reports final page content, not source visibility.
     * Three requests in mixed page order share two pages: the total-render budget admits exactly two page
     * rasters, proving one render per distinct page, and results follow request order.
     * @throws Exception if authoring or rendering fails
     */
    @Test
    void laterOverpaintAndSharedPageRequestsUseOneRenderPerPage() throws Exception {
        final byte[] source;
        try (final var document = new PDDocument()) {
            final var image = quadrants(document);
            for (int index = 0; index < 2; index++) {
                final var page = new PDPage(new PDRectangle(200, 120));
                document.addPage(page);
                try (final var content = new PDPageContentStream(document, page)) {
                    content.drawImage(image, 40, 40, 40, 40);
                    if (index == 0) {
                        content.setNonStrokingColor(Color.BLACK);
                        content.addRect(60, 40, 20, 20);
                        content.fill();
                    }
                }
            }
            source = save(document);
        }
        final var requests = List.of(new PdfPageCropRequest(2, 40, 40, 80, 80),
                new PdfPageCropRequest(1, 40, 40, 80, 80), new PdfPageCropRequest(1, 100, 10, 140, 30));
        final var twoPages = new PdfPageCropOptions(
                72, PdfPageCropOptions.Background.WHITE, 1 << 20, 3, 2, 24_000, 48_000, 24_000, 1 << 20);
        final var crops = renderer(twoPages).render(DocumentSource.bytes(source, null), requests);
        assertEquals(requests, crops.stream().map(PdfPageCrop::request).toList());
        assertUniform(decode(crops.get(0)), 21, 21, 39, 39, Color.YELLOW);
        final var covered = decode(crops.get(1));
        assertUniform(covered, 1, 1, 19, 19, Color.RED);
        assertUniform(covered, 21, 21, 39, 39, Color.BLACK);
        assertUniform(decode(crops.get(2)), 0, 0, 40, 20, Color.WHITE);
        final var oneLess = new PdfPageCropOptions(
                72, PdfPageCropOptions.Background.WHITE, 1 << 20, 3, 2, 24_000, 47_999, 24_000, 1 << 20);
        assertCode(PartitionException.Code.IMAGE_LIMIT,
                () -> renderer(oneLess).render(DocumentSource.bytes(source, null), requests));
        // Both images remain source occurrences; neither is filtered by final-page appearance.
        assertEquals(2, images(source).size());
    }

    /**
     * Nonzero crop origin and a clockwise quarter turn: the partitioner's displayed image box, used directly
     * as a request, selects the rotated quadrants in their displayed positions.
     * @throws Exception if authoring, extraction or rendering fails
     */
    @Test
    void displayedImageCoordinatesSelectRotatedCropOriginRegion() throws Exception {
        final byte[] source;
        try (final var document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(300, 300));
            page.setCropBox(new PDRectangle(30, 50, 200, 120));
            page.setRotation(90);
            document.addPage(page);
            final var image = quadrants(document);
            try (final var content = new PDPageContentStream(document, page)) {
                content.drawImage(image, 70, 80, 40, 40);
            }
            source = save(document);
        }
        final var box = box(images(source).getFirst());
        assertEquals(List.of(30.0, 40.0, 70.0, 80.0), box);
        final var crop =
                renderer(PdfPageCropOptions.defaults().withDpi(72))
                        .render(DocumentSource.bytes(source, null),
                                List.of(new PdfPageCropRequest(1, box.get(0), box.get(1), box.get(2), box.get(3))))
                        .getFirst();
        assertEquals(90, crop.pageRotation());
        assertEquals(120, crop.pageWidthPixels());
        assertEquals(200, crop.pageHeightPixels());
        final var pixels = decode(crop);
        assertUniform(pixels, 1, 1, 19, 19, Color.BLUE);
        assertUniform(pixels, 21, 1, 39, 19, Color.RED);
        assertUniform(pixels, 1, 21, 19, 39, Color.YELLOW);
        assertUniform(pixels, 21, 21, 39, 39, Color.GREEN);
    }

    /**
     * UserUnit scales the physical raster; user-space and point requests select the same pixels.
     * @throws Exception if authoring, extraction or rendering fails
     */
    @Test
    void userUnitScalesRasterAndPointRequestsMatchUserSpaceRequests() throws Exception {
        final byte[] source;
        try (final var document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(100, 60));
            page.getCOSObject().setFloat(COSName.USER_UNIT, 2f);
            document.addPage(page);
            final var image = red(document);
            try (final var content = new PDPageContentStream(document, page)) {
                content.drawImage(image, 10, 10, 20, 20);
            }
            source = save(document);
        }
        assertEquals(List.of(10.0, 30.0, 30.0, 50.0), box(images(source).getFirst()));
        final var crops =
                renderer(PdfPageCropOptions.defaults().withDpi(72))
                        .render(DocumentSource.bytes(source, null),
                                List.of(new PdfPageCropRequest(1, 10, 30, 30, 50),
                                        new PdfPageCropRequest(1, 20, 60, 60, 100, PdfPageCropRequest.Units.POINTS)));
        for (final var crop : crops) {
            assertEquals(200, crop.pageWidthPixels());
            assertEquals(120, crop.pageHeightPixels());
            assertEquals(2.0, crop.pixelsPerUnit());
            assertEquals(2.0, crop.userUnit());
            assertEquals(
                    List.of(20, 60, 40, 40), List.of(crop.pixelLeft(), crop.pixelTop(), crop.width(), crop.height()));
            assertUniform(decode(crop), 1, 1, 39, 39, Color.RED);
        }
        assertEquals(List.of(20.0, 60.0, 60.0, 100.0),
                List.of(crops.get(1).left(), crops.get(1).top(), crops.get(1).right(), crops.get(1).bottom()));
        assertArrayEquals(crops.get(0).png(), crops.get(1).png());
    }

    /**
     * Fractional pixel scales round edges outward and report the actual rendered extent.
     * Partial requests are intersected with the raster; requests without pixels or pages are typed failures.
     * @throws Exception if authoring or rendering fails
     */
    @Test
    void fractionalPartialAndOutsideRequestsHaveExplicitExtents() throws Exception {
        final byte[] source = contractControl();
        final var renderer = renderer(PdfPageCropOptions.defaults());
        final var crops = renderer.render(DocumentSource.bytes(source, null),
                List.of(new PdfPageCropRequest(3, 40, 40, 80, 80), new PdfPageCropRequest(3, -10, -10, 20, 20)));
        final var fractional = crops.get(0);
        final double scale = (float) (150 / 72.0);
        // PDFRenderer floors float products: 200 x 2.0833333f = 416.66666 and 120 x 2.0833333f = 249.99998.
        assertEquals(416, fractional.pageWidthPixels());
        assertEquals(249, fractional.pageHeightPixels());
        assertEquals((int) Math.floor(120f * (float) (150 / 72.0)), fractional.pageHeightPixels());
        assertEquals(List.of(83, 83, 84, 84),
                List.of(fractional.pixelLeft(), fractional.pixelTop(), fractional.width(), fractional.height()));
        assertEquals(83 / scale, fractional.left(), 1e-9);
        assertEquals(167 / scale, fractional.right(), 1e-9);
        assertTrue(fractional.left() <= 40 && fractional.right() >= 80);
        assertFalse(fractional.clipped());
        final var partial = crops.get(1);
        assertTrue(partial.clipped());
        assertEquals(List.of(0, 0), List.of(partial.pixelLeft(), partial.pixelTop()));
        assertEquals(List.of(0.0, 0.0), List.of(partial.left(), partial.top()));
        assertEquals(42, partial.width());
        assertCode(PartitionException.Code.UNSUPPORTED_OPTION,
                ()
                        -> renderer.render(DocumentSource.bytes(source, null),
                                List.of(new PdfPageCropRequest(1, 300, 10, 400, 20))));
        assertCode(PartitionException.Code.UNSUPPORTED_OPTION,
                ()
                        -> renderer.render(
                                DocumentSource.bytes(source, null), List.of(new PdfPageCropRequest(4, 0, 0, 1, 1))));
    }

    /**
     * Malformed requests and options fail before I/O; text, oversized and malformed sources are typed.
     * @throws Exception if authoring fails
     */
    @Test
    void malformedRequestsOptionsAndSourcesAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new PdfPageCropRequest(0, 0, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new PdfPageCropRequest(1, Double.NaN, 0, 1, 1));
        assertThrows(
                IllegalArgumentException.class, () -> new PdfPageCropRequest(1, 0, 0, Double.POSITIVE_INFINITY, 1));
        assertThrows(IllegalArgumentException.class, () -> new PdfPageCropRequest(1, 2, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new PdfPageCropRequest(1, 0, 1, 1, 1));
        assertThrows(NullPointerException.class, () -> new PdfPageCropRequest(1, 0, 0, 1, 1, null));
        assertThrows(IllegalArgumentException.class, () -> PdfPageCropOptions.defaults().withDpi(35));
        assertThrows(IllegalArgumentException.class, () -> PdfPageCropOptions.defaults().withDpi(601));
        assertThrows(IllegalArgumentException.class,
                () -> new PdfPageCropOptions(72, PdfPageCropOptions.Background.WHITE, 1, 1, 1, 10, 10, 11, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new PdfPageCropOptions(72, PdfPageCropOptions.Background.WHITE, 1, 1, 1, 10, 9, 1, 1));
        assertThrows(NullPointerException.class, () -> PdfPageCropRenderer.create(null));
        final var renderer = renderer(PdfPageCropOptions.defaults());
        final var request = List.of(new PdfPageCropRequest(1, 0, 0, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> renderer.render(DocumentSource.bytes(new byte[1], null), List.of()));
        assertThrows(NullPointerException.class, () -> renderer.render(null, request));
        final var withNull = new ArrayList<PdfPageCropRequest>();
        withNull.add(null);
        assertThrows(
                NullPointerException.class, () -> renderer.render(DocumentSource.bytes(new byte[1], null), withNull));
        assertCode(PartitionException.Code.UNSUPPORTED_FORMAT,
                () -> renderer.render(DocumentSource.text("%PDF-1.7"), request));
        assertCode(PartitionException.Code.MALFORMED_DOCUMENT,
                () -> renderer.render(DocumentSource.bytes("not a pdf".getBytes(), null), request));
        final var small = new PdfPageCropOptions(72, PdfPageCropOptions.Background.WHITE, 16, 4, 4, 100, 100, 100, 100);
        assertCode(PartitionException.Code.INPUT_TOO_LARGE,
                () -> renderer(small).render(DocumentSource.bytes(contractControl(), null), request));
    }

    /**
     * Request, page, page-pixel, crop-pixel and encoded-byte budgets fail with IMAGE_LIMIT. An enormous page at
     * 600 DPI is rejected from geometry before PDFBox would allocate its raster.
     * @throws Exception if authoring fails
     */
    @Test
    void budgetsAreCheckedBeforeRasterAllocationOrDuringBoundedEncoding() throws Exception {
        final var source = DocumentSource.bytes(contractControl(), null);
        final var one = new PdfPageCropRequest(1, 40, 40, 80, 80);
        final var three =
                List.of(one, new PdfPageCropRequest(2, 40, 40, 80, 80), new PdfPageCropRequest(3, 0, 0, 1, 1));
        final var white = PdfPageCropOptions.Background.WHITE;
        assertCode(PartitionException.Code.IMAGE_LIMIT,
                ()
                        -> renderer(new PdfPageCropOptions(72, white, 1 << 20, 2, 4, 24_000, 96_000, 24_000, 1 << 20))
                                .render(source, three));
        assertCode(PartitionException.Code.IMAGE_LIMIT,
                ()
                        -> renderer(new PdfPageCropOptions(72, white, 1 << 20, 4, 2, 24_000, 96_000, 24_000, 1 << 20))
                                .render(source, three));
        assertCode(PartitionException.Code.IMAGE_LIMIT,
                ()
                        -> renderer(new PdfPageCropOptions(72, white, 1 << 20, 4, 4, 23_999, 96_000, 1_600, 1 << 20))
                                .render(source, List.of(one)));
        assertCode(PartitionException.Code.IMAGE_LIMIT,
                ()
                        -> renderer(new PdfPageCropOptions(72, white, 1 << 20, 4, 4, 24_000, 96_000, 1_599, 1 << 20))
                                .render(source, List.of(one)));
        assertCode(PartitionException.Code.IMAGE_LIMIT,
                ()
                        -> renderer(new PdfPageCropOptions(72, white, 1 << 20, 4, 4, 24_000, 96_000, 1_600, 16))
                                .render(source, List.of(one)));
        final byte[] huge;
        try (final var document = new PDDocument()) {
            document.addPage(new PDPage(new PDRectangle(14_400, 14_400)));
            huge = save(document);
        }
        final long started = System.nanoTime();
        assertCode(PartitionException.Code.IMAGE_LIMIT,
                ()
                        -> renderer(PdfPageCropOptions.defaults().withDpi(600))
                                .render(DocumentSource.bytes(huge, null),
                                        List.of(new PdfPageCropRequest(1, 0, 0, 10, 10))));
        assertTrue(System.nanoTime() - started < 5_000_000_000L);
    }

    /**
     * Interruption before work fails with the shared cancellation exception and leaves the flag set;
     * file sources are snapshotted per call, so a later file change is observed by the next call only.
     * @throws Exception if authoring or rendering fails
     */
    @Test
    void cancellationAndFileSnapshotsAreCallConfined() throws Exception {
        final Path file = Files.createTempFile("docweave-page-crop", ".pdf");
        try {
            Files.write(file, contractControl());
            final var renderer = renderer(PdfPageCropOptions.defaults().withDpi(72));
            final var request = List.of(new PdfPageCropRequest(1, 40, 40, 80, 80));
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedIOException.class, () -> renderer.render(DocumentSource.of(file), request));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
            final var first = renderer.render(DocumentSource.of(file), request).getFirst();
            assertUniform(decode(first), 1, 1, 39, 39, Color.WHITE);
            try (final var document = new PDDocument()) {
                final var page = new PDPage(new PDRectangle(200, 120));
                document.addPage(page);
                try (final var content = new PDPageContentStream(document, page)) {
                    content.drawImage(red(document), 40, 40, 40, 40);
                }
                document.save(file.toFile());
            }
            assertUniform(
                    decode(renderer.render(DocumentSource.of(file), request).getFirst()), 1, 1, 39, 39, Color.RED);
            assertUniform(decode(first), 1, 1, 39, 39, Color.WHITE);
            final Path descriptors = Path.of("/proc/self/fd");
            if (Files.isDirectory(descriptors)) {
                // Successful and failing calls must both release their file handles.
                final long open = count(descriptors);
                final var outside = List.of(new PdfPageCropRequest(1, 300, 0, 400, 10));
                for (int attempt = 0; attempt < 20; attempt++) {
                    renderer.render(DocumentSource.of(file), request);
                    assertCode(PartitionException.Code.UNSUPPORTED_OPTION,
                            () -> renderer.render(DocumentSource.of(file), outside));
                }
                assertTrue(count(descriptors) <= open + 2);
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /**
     * Counts directory entries.
     * @param directory directory to list
     * @return entry count
     * @throws Exception if listing fails
     */
    private static long count(final Path directory) throws Exception {
        try (final var entries = Files.list(directory)) {
            return entries.count();
        }
    }

    /**
     * Crops own their PNG bytes, compare by content, decode at their declared size and are deterministic.
     * Transparent backgrounds keep alpha: an alpha-0 group stays transparent and alpha 0.5 is half-covered red.
     * @throws Exception if authoring or rendering fails
     */
    @Test
    void cropsAreDeeplyImmutableDeterministicAndSupportTransparentBackground() throws Exception {
        final byte[] source = contractControl();
        final var renderer = renderer(PdfPageCropOptions.defaults().withDpi(72));
        final var request = List.of(new PdfPageCropRequest(2, 40, 40, 80, 80));
        final var crop = renderer.render(DocumentSource.bytes(source, null), request).getFirst();
        final var again = renderer.render(DocumentSource.bytes(source, null), request).getFirst();
        assertEquals(crop, again);
        assertEquals(crop.hashCode(), again.hashCode());
        final byte[] leaked = crop.png();
        leaked[0] ^= 1;
        assertEquals(crop.pngSize(), crop.png().length);
        assertNotEquals(leaked[0], crop.png()[0]);
        final byte[] supplied = crop.png();
        final var copy = new PdfPageCrop(crop.request(), supplied, crop.width(), crop.height(), crop.pixelLeft(),
                crop.pixelTop(), crop.pageWidthPixels(), crop.pageHeightPixels(), crop.left(), crop.top(), crop.right(),
                crop.bottom(), crop.clipped(), crop.pixelsPerUnit(), crop.dpi(), crop.pageRotation(), crop.userUnit(),
                crop.background(), crop.sampling(), crop.renderer());
        supplied[0] ^= 1;
        assertEquals(crop, copy);
        assertFalse(copy.toString().contains("[B@"));
        final var crops = renderer.render(DocumentSource.bytes(source, null), request);
        assertThrows(UnsupportedOperationException.class, () -> crops.add(crop));
        assertTrue(crop.renderer().startsWith("Apache PDFBox "));
        final var transparent = renderer(
                PdfPageCropOptions.defaults().withDpi(72).withBackground(PdfPageCropOptions.Background.TRANSPARENT))
                                        .render(DocumentSource.bytes(source, null),
                                                List.of(new PdfPageCropRequest(1, 40, 40, 80, 80),
                                                        new PdfPageCropRequest(2, 40, 40, 80, 80)));
        final var hidden = decode(transparent.get(0));
        final var half = decode(transparent.get(1));
        assertTrue(hidden.getColorModel().hasAlpha());
        for (int y = 1; y < 39; y++) {
            for (int x = 1; x < 39; x++) {
                assertEquals(0, hidden.getRGB(x, y) >>> 24);
                final int pixel = half.getRGB(x, y);
                assertEquals(128, pixel >>> 24, CHANNEL_TOLERANCE);
                assertEquals(255, (pixel >> 16) & 255, CHANNEL_TOLERANCE);
                assertEquals(0, (pixel >> 8) & 255, CHANNEL_TOLERANCE);
            }
        }
    }

    /**
     * Edges round exactly outward at scale one (72 DPI): any positive-width overlap with a pixel column or
     * row includes it, exact pixel boundaries add nothing, and coordinates many ULPs from an integer are
     * never snapped inward. Expected intervals are analytical floor/ceiling values on both axes.
     * @throws Exception if authoring or rendering fails
     */
    @Test
    void outwardRoundingKeepsEveryIntersectedPixelOnBothAxes() throws Exception {
        final byte[] source;
        try (final var document = new PDDocument()) {
            document.addPage(new PDPage(new PDRectangle(200, 120)));
            source = save(document);
        }
        final var renderer = renderer(PdfPageCropOptions.defaults().withDpi(72));
        final double[][] ranges = {{40, 40.0000001, 40, 41}, {39.9999999, 41, 39, 41}, {40, 41.0000001, 40, 42},
                {40, 80, 40, 80}, {40.2, 40.3, 40, 41}, {Math.nextDown(40.0), 40, 39, 40},
                {40, Math.nextUp(40.0), 40, 41}};
        final var requests = new ArrayList<PdfPageCropRequest>();
        for (final double[] range : ranges) {
            requests.add(new PdfPageCropRequest(1, range[0], 10, range[1], 11));
            requests.add(new PdfPageCropRequest(1, 10, range[0], 11, range[1]));
        }
        final var crops = renderer.render(DocumentSource.bytes(source, null), requests);
        for (int index = 0; index < ranges.length; index++) {
            final double[] range = ranges[index];
            final var horizontal = crops.get(2 * index);
            final var vertical = crops.get(2 * index + 1);
            assertEquals(List.of((int) range[2], 10, (int) (range[3] - range[2]), 1),
                    List.of(horizontal.pixelLeft(), horizontal.pixelTop(), horizontal.width(), horizontal.height()));
            assertEquals(List.of(10, (int) range[2], 1, (int) (range[3] - range[2])),
                    List.of(vertical.pixelLeft(), vertical.pixelTop(), vertical.width(), vertical.height()));
            assertEquals(List.of(range[2], range[3]), List.of(horizontal.left(), horizontal.right()));
            assertEquals(List.of(range[2], range[3]), List.of(vertical.top(), vertical.bottom()));
            assertTrue(horizontal.left() <= range[0] && horizontal.right() >= range[1]);
            assertFalse(horizontal.clipped() || vertical.clipped());
        }
        final var edges = renderer.render(
                DocumentSource.bytes(source, null), List.of(new PdfPageCropRequest(1, -0.0000001, 10, 1, 11),
                                                            new PdfPageCropRequest(1, 199.9999999, 10, 200.0000001, 11),
                                                            new PdfPageCropRequest(1, 10, 119.9999999, 11, 120)));
        assertEquals(
                List.of(0, 1, true), List.of(edges.get(0).pixelLeft(), edges.get(0).width(), edges.get(0).clipped()));
        assertEquals(
                List.of(199, 1, true), List.of(edges.get(1).pixelLeft(), edges.get(1).width(), edges.get(1).clipped()));
        assertEquals(List.of(119, 1, false),
                List.of(edges.get(2).pixelTop(), edges.get(2).height(), edges.get(2).clipped()));
        // A positive region entirely beyond the actual raster still has no rendered pixel.
        assertCode(PartitionException.Code.UNSUPPORTED_OPTION,
                ()
                        -> renderer.render(DocumentSource.bytes(source, null),
                                List.of(new PdfPageCropRequest(1, 200, 10, 200.0000001, 11))));
        // At 150 DPI the float renderer scale makes 40..80 cover pixels 83..167, unchanged by exact rounding.
        final var fractional = renderer(PdfPageCropOptions.defaults())
                                       .render(DocumentSource.bytes(source, null),
                                               List.of(new PdfPageCropRequest(1, 40, 40, 80, 80),
                                                       new PdfPageCropRequest(1, 40.31, 40, 40.32, 80)))
                                       .getFirst();
        assertEquals(List.of(83, 84), List.of(fractional.pixelLeft(), fractional.width()));
    }

    /**
     * Instruments the package-private renderer factory: each planned page gets its own renderer, rendered
     * exactly once in ascending page order, while results keep the caller's request order.
     * @throws Exception if authoring or rendering fails
     */
    @Test
    void eachPlannedPageUsesOneConfinedRendererAndOneRenderCall() throws Exception {
        final var renders = new ArrayList<List<Integer>>();
        final var service =
                new DefaultPdfPageCropRenderer(PdfPageCropOptions.defaults().withDpi(72), null, document -> {
                    final var pages = new ArrayList<Integer>();
                    renders.add(pages);
                    return new PDFRenderer(document) {
                        @Override
                        public BufferedImage renderImage(final int index, final float scale, final ImageType type,
                                final RenderDestination destination) throws IOException {
                            pages.add(index);
                            return super.renderImage(index, scale, type, destination);
                        }
                    };
                });
        final var requests = List.of(new PdfPageCropRequest(2, 40, 40, 80, 80),
                new PdfPageCropRequest(1, 40, 40, 80, 80), new PdfPageCropRequest(1, 0, 0, 10, 10),
                new PdfPageCropRequest(3, 40, 40, 80, 80), new PdfPageCropRequest(2, 0, 0, 10, 10));
        final var crops = service.render(DocumentSource.bytes(contractControl(), null), requests);
        assertEquals(List.of(List.of(0), List.of(1), List.of(2)), renders);
        assertEquals(requests, crops.stream().map(PdfPageCrop::request).toList());
        assertUniform(decode(crops.get(1)), 1, 1, 39, 39, Color.WHITE);
        assertUniform(decode(crops.get(0)), 1, 1, 39, 39, new Color(255, 128, 128));
        final var clipped = decode(crops.get(3));
        assertUniform(clipped, 1, 1, 19, 39, Color.RED);
        assertUniform(clipped, 21, 1, 39, 39, Color.WHITE);
    }

    /**
     * A valid 1000-unit page with UserUnit 50 at 72 DPI needs a 50000x50000 raster, above PDFRenderer's
     * integer ceiling. Even with caller budgets admitting it, the service rejects it from geometry as a
     * resource limit before allocation instead of reporting a malformed document; ordinary pages still render
     * under the same large custom budgets.
     * @throws Exception if authoring or rendering fails
     */
    @Test
    void rendererRasterCeilingIsATypedPreflightLimit() throws Exception {
        final byte[] source;
        try (final var document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(1000, 1000));
            page.getCOSObject().setFloat(COSName.USER_UNIT, 50f);
            document.addPage(page);
            source = save(document);
        }
        final var large = new PdfPageCropOptions(
                72, PdfPageCropOptions.Background.WHITE, 1 << 20, 4, 4, 2_500_000_000L, 2_500_000_000L, 100, 1 << 20);
        final var failure = assertThrows(PartitionException.class,
                ()
                        -> PdfPageCropRenderer.create(large).render(DocumentSource.bytes(source, null),
                                List.of(new PdfPageCropRequest(1, 0, 0, 0.01, 0.01))));
        assertEquals(PartitionException.Code.IMAGE_LIMIT, failure.code());
        assertTrue(failure.getMessage().contains("maximum raster size"));
        final var ordinary = PdfPageCropRenderer.create(large)
                                     .render(DocumentSource.bytes(contractControl(), null),
                                             List.of(new PdfPageCropRequest(3, 40, 40, 50, 50)))
                                     .getFirst();
        assertUniform(decode(ordinary), 0, 0, 10, 10, Color.RED);
    }

    /**
     * Explicit 4x box sampling keeps the output grid but gives hard Java2D clip edges fractional coverage.
     * A clip edge at x = 60.25 covers exactly one of the four sub-columns of output column 60, so that
     * column is analytically 25% red over white (255,191,191), while the default sampler leaves it white.
     * A curved clip gains intermediate edge values only under box sampling. The frozen alpha/clip controls,
     * pixel rectangles and transparent alpha keep their analytical values, and the 16x raster is charged.
     * @throws Exception if authoring or rendering fails
     */
    @Test
    void boxSamplingAntialiasesClipEdgesAndKeepsAnalyticalControls() throws Exception {
        final byte[] source;
        try (final var document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(200, 120));
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                content.saveGraphicsState();
                content.addRect(40, 40, 20.25f, 40);
                content.clip();
                content.setNonStrokingColor(Color.RED);
                content.addRect(0, 0, 200, 120);
                content.fill();
                content.restoreGraphicsState();
                content.saveGraphicsState();
                final float k = 0.5523f * 20;
                content.moveTo(150, 80);
                content.curveTo(150 + k, 80, 170, 60 + k, 170, 60);
                content.curveTo(170, 60 - k, 150 + k, 40, 150, 40);
                content.curveTo(150 - k, 40, 130, 60 - k, 130, 60);
                content.curveTo(130, 60 + k, 150 - k, 80, 150, 80);
                content.closePath();
                content.clip();
                content.setNonStrokingColor(Color.BLUE);
                content.addRect(0, 0, 200, 120);
                content.fill();
                content.restoreGraphicsState();
            }
            source = save(document);
        }
        final var requests =
                List.of(new PdfPageCropRequest(1, 40, 40, 80, 80), new PdfPageCropRequest(1, 120, 30, 180, 90));
        final var plain = renderer(PdfPageCropOptions.defaults().withDpi(72))
                                  .render(DocumentSource.bytes(source, null), requests);
        final var boxed =
                renderer(PdfPageCropOptions.defaults().withDpi(72).withSampling(PdfPageCropOptions.Sampling.BOX_4X))
                        .render(DocumentSource.bytes(source, null), requests);
        for (int index = 0; index < 2; index++) {
            assertEquals(PdfPageCropOptions.Sampling.RENDERER, plain.get(index).sampling());
            assertEquals(PdfPageCropOptions.Sampling.BOX_4X, boxed.get(index).sampling());
            assertEquals(List.of(plain.get(index).pixelLeft(), plain.get(index).pixelTop(), plain.get(index).width(),
                                 plain.get(index).height(), plain.get(index).pageWidthPixels()),
                    List.of(boxed.get(index).pixelLeft(), boxed.get(index).pixelTop(), boxed.get(index).width(),
                            boxed.get(index).height(), boxed.get(index).pageWidthPixels()));
        }
        final var hard = decode(plain.get(0));
        final var soft = decode(boxed.get(0));
        assertUniform(hard, 1, 1, 19, 39, Color.RED);
        assertUniform(soft, 1, 1, 19, 39, Color.RED);
        assertUniform(hard, 20, 1, 21, 39, Color.WHITE);
        assertUniform(soft, 20, 1, 21, 39, new Color(255, 191, 191));
        assertUniform(soft, 22, 1, 39, 39, Color.WHITE);
        assertTrue(intermediate(decode(boxed.get(1))) > 0);
        assertEquals(0, intermediate(decode(plain.get(1))));
        final var contract =
                renderer(PdfPageCropOptions.defaults().withDpi(72).withSampling(PdfPageCropOptions.Sampling.BOX_4X))
                        .render(DocumentSource.bytes(contractControl(), null),
                                List.of(new PdfPageCropRequest(1, 40, 40, 80, 80),
                                        new PdfPageCropRequest(2, 40, 40, 80, 80),
                                        new PdfPageCropRequest(3, 40, 40, 80, 80)));
        assertUniform(decode(contract.get(0)), 1, 1, 39, 39, Color.WHITE);
        assertUniform(decode(contract.get(1)), 1, 1, 39, 39, new Color(255, 128, 128));
        assertUniform(decode(contract.get(2)), 1, 1, 19, 39, Color.RED);
        assertUniform(decode(contract.get(2)), 21, 1, 39, 39, Color.WHITE);
        final var transparent = renderer(PdfPageCropOptions.defaults()
                                                 .withDpi(72)
                                                 .withBackground(PdfPageCropOptions.Background.TRANSPARENT)
                                                 .withSampling(PdfPageCropOptions.Sampling.BOX_4X))
                                        .render(DocumentSource.bytes(contractControl(), null),
                                                List.of(new PdfPageCropRequest(1, 40, 40, 80, 80),
                                                        new PdfPageCropRequest(2, 40, 40, 80, 80)));
        final int hidden = decode(transparent.get(0)).getRGB(20, 20);
        final int half = decode(transparent.get(1)).getRGB(20, 20);
        assertEquals(0, hidden >>> 24);
        assertEquals(128, half >>> 24, CHANNEL_TOLERANCE);
        assertEquals(255, (half >> 16) & 255, CHANNEL_TOLERANCE);
        final var exact = new PdfPageCropOptions(72, PdfPageCropOptions.Background.WHITE, 1 << 20, 4, 4, 384_000,
                384_000, 1_600, 1 << 20, PdfPageCropOptions.Sampling.BOX_4X);
        final var request = List.of(new PdfPageCropRequest(1, 40, 40, 80, 80));
        assertEquals(1, renderer(exact).render(DocumentSource.bytes(source, null), request).size());
        assertCode(PartitionException.Code.IMAGE_LIMIT,
                ()
                        -> renderer(new PdfPageCropOptions(72, PdfPageCropOptions.Background.WHITE, 1 << 20, 4, 4,
                                            383_999, 384_000, 1_600, 1 << 20, PdfPageCropOptions.Sampling.BOX_4X))
                                .render(DocumentSource.bytes(source, null), request));
        assertEquals(PdfPageCropOptions.Sampling.RENDERER, PdfPageCropOptions.defaults().sampling());
        assertThrows(NullPointerException.class, () -> PdfPageCropOptions.defaults().withSampling(null));
    }

    /**
     * Missing samples beyond sub-pixel pages use the chosen backdrop rather than repeated source colors.
     * Expected coverage is analytical: a half-by-half page covers 4/16 samples, a half-width or half-height
     * page covers 8/16, and a quarter-by-quarter page covers 1/16.
     * @throws Exception if source authoring, rendering or PNG decoding fails
     */
    @Test
    void boxSamplingPadsSubpixelPagesWithTheSelectedBackground() throws Exception {
        final var cases = List.of(new SmallPage(.5f, .5f, 1, 1, 0xffffbfbf, 0x40ff0000),
                new SmallPage(.5f, 2, 1, 2, 0xffff8080, 0x80ff0000),
                new SmallPage(2, .5f, 2, 1, 0xffff8080, 0x80ff0000),
                new SmallPage(.25f, .25f, 1, 1, 0xffffefef, 0x10ff0000));
        for (final var selected : cases) {
            final var source = smallPage(selected.width(), selected.height(), 0, 1, true);
            for (final var background : PdfPageCropOptions.Background.values()) {
                final var policy = PdfPageCropOptions.defaults()
                                           .withDpi(72)
                                           .withBackground(background)
                                           .withSampling(PdfPageCropOptions.Sampling.BOX_4X);
                final var crop =
                        renderer(policy)
                                .render(DocumentSource.bytes(source, null),
                                        List.of(new PdfPageCropRequest(1, 0, 0, selected.width(), selected.height())))
                                .getFirst();
                assertEquals(selected.pixelWidth(), crop.width());
                assertEquals(selected.pixelHeight(), crop.height());
                final int expected = background == PdfPageCropOptions.Background.WHITE ? selected.whiteArgb()
                                                                                       : selected.transparentArgb();
                final var pixels = decode(crop);
                for (int y = 0; y < pixels.getHeight(); y++) {
                    for (int x = 0; x < pixels.getWidth(); x++) {
                        assertEquals(expected, pixels.getRGB(x, y));
                    }
                }
            }
        }
    }

    /**
     * Rotation and physical-point requests preserve the same half-covered sample grid on a nonunit page.
     * @throws Exception if source authoring, rendering or decoding fails
     */
    @Test
    void boxSamplingPadsRotatedSubpixelPagesInBothCoordinateUnits() throws Exception {
        for (final int rotation : List.of(0, 90, 180, 270)) {
            final var source = smallPage(.25f, 1, rotation, 2, true);
            final boolean quarter = rotation == 90 || rotation == 270;
            final double right = quarter ? 1 : .25;
            final double bottom = quarter ? .25 : 1;
            final var requests = List.of(new PdfPageCropRequest(1, 0, 0, right, bottom),
                    new PdfPageCropRequest(1, 0, 0, right * 2, bottom * 2, PdfPageCropRequest.Units.POINTS));
            for (final var background : PdfPageCropOptions.Background.values()) {
                final var policy = PdfPageCropOptions.defaults()
                                           .withDpi(72)
                                           .withBackground(background)
                                           .withSampling(PdfPageCropOptions.Sampling.BOX_4X);
                final var crops = renderer(policy).render(DocumentSource.bytes(source, null), requests);
                assertArrayEquals(crops.get(0).png(), crops.get(1).png());
                assertEquals(quarter ? 2 : 1, crops.get(0).width());
                assertEquals(quarter ? 1 : 2, crops.get(0).height());
                assertEquals(rotation, crops.get(0).pageRotation());
                final var pixels = decode(crops.get(0));
                final int expected = background == PdfPageCropOptions.Background.WHITE ? 0xffff8080 : 0x80ff0000;
                for (int y = 0; y < pixels.getHeight(); y++) {
                    for (int x = 0; x < pixels.getWidth(); x++) {
                        assertEquals(expected, pixels.getRGB(x, y));
                    }
                }
            }
        }
    }

    /**
     * The minimum-one-sample renderer extent supports even very narrow blank pages without an unchecked read.
     * @throws Exception if source authoring, rendering or decoding fails
     */
    @Test
    void boxSamplingKeepsVeryNarrowBlankPagesAtTheirBackground() throws Exception {
        for (final var dimensions : List.of(new float[] {.01f, 10}, new float[] {10, .01f})) {
            final var source = smallPage(dimensions[0], dimensions[1], 0, 1, false);
            for (final var background : PdfPageCropOptions.Background.values()) {
                final var policy = PdfPageCropOptions.defaults().withDpi(72).withBackground(background);
                final var requests = List.of(new PdfPageCropRequest(1, 0, 0, dimensions[0], dimensions[1]));
                final var original = renderer(policy).render(DocumentSource.bytes(source, null), requests).getFirst();
                final var sampled = renderer(policy.withSampling(PdfPageCropOptions.Sampling.BOX_4X))
                                            .render(DocumentSource.bytes(source, null), requests)
                                            .getFirst();
                assertEquals(original.width(), sampled.width());
                assertEquals(original.height(), sampled.height());
                final var pixels = decode(sampled);
                final int expected = background == PdfPageCropOptions.Background.WHITE ? 0xffffffff : 0;
                for (int y = 0; y < pixels.getHeight(); y++) {
                    for (int x = 0; x < pixels.getWidth(); x++) {
                        assertEquals(expected, pixels.getRGB(x, y));
                    }
                }
            }
        }
    }

    /**
     * Padding uses no larger page allocation: the exact four rendered pixels pass, and three fail in preflight.
     * @throws Exception if source authoring, rendering or decoding fails
     */
    @Test
    void boxSamplingPaddingStillChargesOnlyTheActualPageRaster() throws Exception {
        final var source = DocumentSource.bytes(smallPage(.5f, .5f, 0, 1, false), null);
        final var requests = List.of(new PdfPageCropRequest(1, 0, 0, .5, .5));
        final var exact = new PdfPageCropOptions(72, PdfPageCropOptions.Background.WHITE, 1 << 20, 1, 1, 4, 4, 1,
                1 << 20, PdfPageCropOptions.Sampling.BOX_4X);
        final var crop = renderer(exact).render(source, requests).getFirst();
        assertEquals(1, crop.width());
        assertEquals(1, crop.height());
        assertEquals(0xffffffff, decode(crop).getRGB(0, 0));
        final var insufficient = new PdfPageCropOptions(72, PdfPageCropOptions.Background.WHITE, 1 << 20, 1, 1, 3, 4, 1,
                1 << 20, PdfPageCropOptions.Sampling.BOX_4X);
        assertCode(PartitionException.Code.IMAGE_LIMIT, () -> renderer(insufficient).render(source, requests));
    }

    /**
     * One analytically specified page coverage case.
     * @param width source page width
     * @param height source page height
     * @param pixelWidth expected output width
     * @param pixelHeight expected output height
     * @param whiteArgb expected sample over white
     * @param transparentArgb expected sample over transparency
     */
    private record
            SmallPage(float width, float height, int pixelWidth, int pixelHeight, int whiteArgb, int transparentArgb) {}

    /**
     * Authors one small page without deriving expected samples from the implementation.
     * @param width unscaled crop width
     * @param height unscaled crop height
     * @param rotation declared quarter turn
     * @param userUnit physical points per source unit
     * @param paint whether the complete source page is opaque red rather than blank
     * @return complete PDF bytes
     * @throws Exception if authoring or serialization fails
     */
    private static byte[] smallPage(final float width, final float height, final int rotation, final float userUnit,
            final boolean paint) throws Exception {
        try (final var document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(width, height));
            page.setRotation(rotation);
            page.setUserUnit(userUnit);
            document.addPage(page);
            if (paint) {
                try (final var content = new PDPageContentStream(document, page)) {
                    content.setNonStrokingColor(Color.RED);
                    content.addRect(0, 0, width, height);
                    content.fill();
                }
            }
            return save(document);
        }
    }

    /**
     * Counts pixels that are neither pure white nor a pure primary, i.e. fractional edge coverage.
     * @param image decoded crop
     * @return number of blended pixels beyond the declared tolerance
     */
    private static int intermediate(final BufferedImage image) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                final int pixel = image.getRGB(x, y);
                final int red = (pixel >> 16) & 255;
                final int green = (pixel >> 8) & 255;
                final int blue = pixel & 255;
                final boolean pure =
                        List.of(red, green, blue)
                                .stream()
                                .allMatch(value -> value <= CHANNEL_TOLERANCE || value >= 255 - CHANNEL_TOLERANCE);
                if (!pure) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Encrypted sources use the same explicit password policy as the partitioner.
     * @throws Exception if authoring or rendering fails
     */
    @Test
    void encryptedSourcesRequireExplicitPasswordLookup() throws Exception {
        final byte[] source;
        try (final var document = new PDDocument()) {
            document.addPage(new PDPage(new PDRectangle(200, 120)));
            document.protect(new StandardProtectionPolicy("owner", "user", new AccessPermission()));
            source = save(document);
        }
        final var request = List.of(new PdfPageCropRequest(1, 0, 0, 10, 10));
        final var renderer = renderer(PdfPageCropOptions.defaults().withDpi(72));
        assertCode(PartitionException.Code.UNSUPPORTED_OPTION,
                () -> renderer.render(DocumentSource.bytes(source, null), request));
        assertThrows(NullPointerException.class, () -> renderer.withPasswords(null));
        final var crop = renderer.withPasswords(filename -> "user".toCharArray())
                                 .render(DocumentSource.bytes(source, null), request)
                                 .getFirst();
        assertUniform(decode(crop), 0, 0, 10, 10, Color.WHITE);
    }

    /**
     * Authors the frozen three-page image contract: an 8x8 opaque red raster at x40 y40 w40 h40 inside an
     * isolated group painted at alpha 0, then 0.5, then directly under a clip admitting its left 20 units.
     * @return complete PDF bytes
     * @throws Exception if authoring fails
     */
    private static byte[] contractControl() throws Exception {
        try (final var document = new PDDocument()) {
            final var image = red(document);
            for (int index = 0; index < 3; index++) {
                final var page = new PDPage(new PDRectangle(200, 120));
                document.addPage(page);
                try (final var paint = new PDPageContentStream(document, page)) {
                    if (index < 2) {
                        final var group = group(document, 1f, content -> content.drawImage(image, 40, 40, 40, 40));
                        final var state = new PDExtendedGraphicsState();
                        state.setNonStrokingAlphaConstant(index == 0 ? 0f : .5f);
                        paint.setGraphicsStateParameters(state);
                        paint.drawForm(group);
                    } else {
                        paint.addRect(40, 40, 20, 40);
                        paint.clip();
                        paint.drawImage(image, 40, 40, 40, 40);
                    }
                }
            }
            return save(document);
        }
    }

    /** Authoring callback that may throw PDFBox I/O failures. */
    @FunctionalInterface
    private interface Paint {
        /**
         * Paints owned form content.
         * @param content open form stream
         * @throws Exception if authoring fails
         */
        void accept(PDFormContentStream content) throws Exception;
    }

    /**
     * Creates an isolated transparency group with its own constant alpha state.
     * @param document owner
     * @param alpha nonstroking alpha applied inside the group
     * @param paint group content
     * @return authored group
     * @throws Exception if authoring fails
     */
    private static PDTransparencyGroup group(final PDDocument document, final float alpha, final Paint paint)
            throws Exception {
        final var group = new PDTransparencyGroup(document);
        group.setResources(new PDResources());
        group.setBBox(new PDRectangle(300, 300));
        final var attributes = new COSDictionary();
        attributes.setItem(COSName.S, COSName.TRANSPARENCY);
        attributes.setBoolean(COSName.I, true);
        group.getCOSObject().setItem(COSName.GROUP, attributes);
        try (final var content = new PDFormContentStream(group)) {
            if (alpha != 1f) {
                final var state = new PDExtendedGraphicsState();
                state.setNonStrokingAlphaConstant(alpha);
                content.setGraphicsStateParameters(state);
            }
            paint.accept(content);
        }
        return group;
    }

    /**
     * Creates the frozen 8x8 opaque red raster.
     * @param document owner
     * @return lossless image
     * @throws Exception if encoding fails
     */
    private static PDImageXObject red(final PDDocument document) throws Exception {
        final var raster = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                raster.setRGB(x, y, Color.RED.getRGB());
            }
        }
        return LosslessFactory.createFromImage(document, raster);
    }

    /**
     * Creates an 8x8 raster with four distinct 4x4 quadrants in {@link #QUADRANTS} order.
     * @param document owner
     * @return lossless image
     * @throws Exception if encoding fails
     */
    private static PDImageXObject quadrants(final PDDocument document) throws Exception {
        final var raster = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                raster.setRGB(x, y, QUADRANTS.get((y / 4) * 2 + x / 4).getRGB());
            }
        }
        return LosslessFactory.createFromImage(document, raster);
    }

    /**
     * Saves an owned document.
     * @param document authored document
     * @return complete bytes
     * @throws Exception if encoding fails
     */
    private static byte[] save(final PDDocument document) throws Exception {
        final var output = new ByteArrayOutputStream();
        document.save(output);
        return output.toByteArray();
    }

    /**
     * Creates the public crop service.
     * @param options crop policy
     * @return hidden implementation
     */
    private static PdfPageCropRenderer renderer(final PdfPageCropOptions options) {
        return PdfPageCropRenderer.create(options);
    }

    /**
     * Runs public source-image extraction with clip and group metadata.
     * @param source complete bytes
     * @return image occurrences in painted order
     * @throws Exception if partitioning fails
     */
    private static List<Element> images(final byte[] source) throws Exception {
        final var options = new PdfOptions(10, 10_000, 10_000, 100, 10_000, true, TableLimits.defaults())
                                    .withImageClipBounds(true)
                                    .withImageGroupContext(true);
        return DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options))))
                .partition(DocumentSource.bytes(source, "owned-page-crop.pdf"))
                .elements()
                .stream()
                .filter(element -> element.type() == ElementType.Image)
                .toList();
    }

    /**
     * Reads an image's displayed axis-aligned box.
     * @param image emitted image element
     * @return left, top, right and bottom in PDFUserSpace
     */
    private static List<Double> box(final Element image) {
        final var coordinates = (Map<?, ?>) image.metadata().get("coordinates");
        assertEquals("PDFUserSpace", coordinates.get("system"));
        final var points = (List<?>) coordinates.get("points");
        final var topLeft = (List<?>) points.get(0);
        final var bottomRight = (List<?>) points.get(2);
        return List.of(((Number) topLeft.get(0)).doubleValue(), ((Number) topLeft.get(1)).doubleValue(),
                ((Number) bottomRight.get(0)).doubleValue(), ((Number) bottomRight.get(1)).doubleValue());
    }

    /**
     * Decodes a source image payload.
     * @param image emitted image element
     * @return original PNG bytes
     */
    private static byte[] sourcePng(final Element image) {
        return Base64.getDecoder().decode(image.metadata().get("image_base64").toString());
    }

    /**
     * Decodes a crop and checks its declared dimensions.
     * @param crop rendered crop
     * @return decoded raster
     * @throws Exception if decoding fails
     */
    private static BufferedImage decode(final PdfPageCrop crop) throws Exception {
        final var image = ImageIO.read(new ByteArrayInputStream(crop.png()));
        assertNotNull(image);
        assertEquals(crop.width(), image.getWidth());
        assertEquals(crop.height(), image.getHeight());
        return image;
    }

    /**
     * Requires every pixel in a half-open rectangle to match a color within the declared tolerance.
     * @param image decoded crop
     * @param left inclusive column
     * @param top inclusive row
     * @param right exclusive column
     * @param bottom exclusive row
     * @param expected analytical color
     */
    private static void assertUniform(final BufferedImage image, final int left, final int top, final int right,
            final int bottom, final Color expected) {
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                final int pixel = image.getRGB(x, y);
                final int[] actual = {(pixel >> 16) & 255, (pixel >> 8) & 255, pixel & 255};
                final int[] wanted = {expected.getRed(), expected.getGreen(), expected.getBlue()};
                for (int channel = 0; channel < 3; channel++) {
                    assertTrue(Math.abs(actual[channel] - wanted[channel]) <= CHANNEL_TOLERANCE,
                            "pixel " + x + "," + y + " was " + Integer.toHexString(pixel) + ", expected " + expected);
                }
            }
        }
    }

    /**
     * Requires a typed processing failure.
     * @param code expected category
     * @param call failing operation
     */
    private static void assertCode(
            final PartitionException.Code code, final org.junit.jupiter.api.function.Executable call) {
        assertEquals(code, assertThrows(PartitionException.class, call).code());
    }

    /**
     * Hashes bytes.
     * @param bytes content
     * @return lowercase SHA-256
     * @throws Exception if unavailable
     */
    private static String sha256(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
