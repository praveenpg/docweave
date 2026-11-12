package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.core.Element;
import io.docweave.core.ElementType;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** #76: standalone PNG, JPEG and TIFF partitioning with a fake OCR provider (no external engine). */
class ImagePartitionTest {
    /** Records each page it is given and answers one line naming the page's size and top-left marker colour. */
    private static final class Recorder implements PdfOcrProvider {
        final List<BufferedImage> pages = new ArrayList<>();

        @Override
        public List<Line> recognize(final BufferedImage image) {
            pages.add(image);
            final boolean marked = (image.getRGB(2, 2) & 0xff0000) >> 16 > 200 && (image.getRGB(2, 2) & 0xff) < 80;
            return List.of(new Line("page " + pages.size() + " " + image.getWidth() + "x" + image.getHeight()
                                            + (marked ? " marked" : ""),
                    1, 2, 30, 12));
        }
    }

    private static DocWeave engine(final PdfOcrProvider ocr, final ImageOptions options) {
        final var registry = PartitionerRegistry.builder();
        ImagePartitioner.createAll(options, ocr).forEach(registry::register);
        return DocWeave.create(registry.build());
    }

    private static byte[] fixture(final String name) throws IOException {
        try (final var in = ImagePartitionTest.class.getResourceAsStream("image/" + name)) {
            return in.readAllBytes();
        }
    }

    /** A page whose top-left corner carries a red marker, so OCR input orientation is observable. */
    private static BufferedImage marked(final int width, final int height) {
        final var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        final var g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.RED);
        g.fillRect(0, 0, 8, 8);
        g.dispose();
        return image;
    }

    private static byte[] encode(final BufferedImage image, final String format) throws IOException {
        final var out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, out));
        return out.toByteArray();
    }

    private static byte[] tiff(final String compression, final BufferedImage... pages) throws IOException {
        final var writer = ImageIO.getImageWritersByFormatName("tiff").next();
        final var out = new ByteArrayOutputStream();
        try (final var stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            final ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionType(compression);
            writer.prepareWriteSequence(null);
            for (final var page : pages) {
                writer.writeToSequence(new IIOImage(page, null,
                                               writer.getDefaultImageMetadata(
                                                       ImageTypeSpecifier.createFromRenderedImage(page), param)),
                        param);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static Object meta(final Element element, final String key) {
        return element.metadata().get(key);
    }

    @Test
    void pngPageBecomesProviderLinesInPixelSpace() throws Exception {
        final var ocr = new Recorder();
        final var result =
                engine(ocr, ImageOptions.defaults()).partition(DocumentSource.bytes(fixture("text.png"), "scan.png"));
        assertEquals(1, ocr.pages.size());
        final Element line = result.elements().getFirst();
        assertEquals(ElementType.UncategorizedText, line.type());
        assertEquals("page 1 1600x520", line.text());
        assertEquals(1, meta(line, "page_number"));
        assertEquals("image/png", meta(line, "filetype"));
        assertEquals("ocr", meta(line, "image_text_method"));
        assertEquals(Map.of("system", "PixelSpace", "layout_width", 1600.0, "layout_height", 520.0, "points",
                             List.of(List.of(1.0, 2.0), List.of(30.0, 2.0), List.of(30.0, 12.0), List.of(1.0, 12.0))),
                meta(line, "coordinates"));
        assertTrue(result.warnings().isEmpty(), result.warnings().toString());
    }

    @Test
    void jpegExifOrientationIsAppliedBeforeRecognition() throws Exception {
        final var stored = ImageFixtureGenerator.rotate(marked(300, 120), 270);
        for (final int orientation : List.of(6, 1)) {
            final var ocr = new Recorder();
            final var result =
                    engine(ocr, ImageOptions.defaults())
                            .partition(DocumentSource.bytes(
                                    ImageFixtureGenerator.exif(ImageFixtureGenerator.jpeg(stored), orientation),
                                    "photo.jpg"));
            final String text = result.elements().getFirst().text();
            if (orientation == 6) {
                assertEquals("page 1 300x120 marked", text, "upright page with the marker top-left");
                assertEquals(6, meta(result.elements().getFirst(), "image_orientation"));
            } else {
                assertEquals("page 1 120x300", text, "stored orientation is kept without EXIF rotation");
                assertFalse(result.elements().getFirst().metadata().containsKey("image_orientation"));
            }
        }
    }

    @Test
    void everyExifOrientationProducesTheSameUprightPage() {
        final int w = 7;
        final int h = 4;
        final var upright = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                upright.setRGB(x, y, x * 16 << 16 | y * 32 << 8 | (x + y) * 8);
            }
        }
        for (int orientation = 1; orientation <= 8; orientation++) {
            final boolean swap = orientation >= 5;
            final var stored = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) {
                    // Where a camera stores the upright pixel (x, y) for each EXIF value, written out independently.
                    final int[] at = switch (orientation) {
                        case 2 -> new int[] {w - 1 - x, y};
                        case 3 -> new int[] {w - 1 - x, h - 1 - y};
                        case 4 -> new int[] {x, h - 1 - y};
                        case 5 -> new int[] {y, x};
                        case 6 -> new int[] {y, w - 1 - x};
                        case 7 -> new int[] {h - 1 - y, w - 1 - x};
                        case 8 -> new int[] {h - 1 - y, x};
                        default -> new int[] {x, y};
                    };
                    stored.setRGB(at[0], at[1], upright.getRGB(x, y));
                }
            }
            final var restored = ImageOrientation.upright(stored, orientation);
            assertEquals(w, restored.getWidth(), "orientation " + orientation);
            assertEquals(h, restored.getHeight(), "orientation " + orientation);
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) {
                    assertEquals(upright.getRGB(x, y), restored.getRGB(x, y),
                            "orientation " + orientation + " at " + x + "," + y);
                }
            }
        }
    }

    @Test
    void tiffPagesApplyTheirOwnOrientationTag() throws Exception {
        final var pages = new BufferedImage[] {ImageFixtureGenerator.rotate(marked(90, 40), 270), marked(90, 40),
                ImageFixtureGenerator.rotate(marked(90, 40), 90)};
        final int[] orientations = {6, 1, 8};
        final var writer = ImageIO.getImageWritersByFormatName("tiff").next();
        final var out = new ByteArrayOutputStream();
        try (final var stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            final ImageWriteParam param = writer.getDefaultWriteParam();
            writer.prepareWriteSequence(null);
            for (int index = 0; index < pages.length; index++) {
                final var metadata =
                        writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(pages[index]), param);
                final var directory = javax.imageio.plugins.tiff.TIFFDirectory.createFromMetadata(metadata);
                directory.addTIFFField(new javax.imageio.plugins.tiff.TIFFField(
                        javax.imageio.plugins.tiff.BaselineTIFFTagSet.getInstance().getTag(274),
                        javax.imageio.plugins.tiff.TIFFTag.TIFF_SHORT, 1, new char[] {(char) orientations[index]}));
                writer.writeToSequence(new IIOImage(pages[index], null, directory.getAsMetadata()), param);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        final var result = engine(new Recorder(), ImageOptions.defaults())
                                   .partition(DocumentSource.bytes(out.toByteArray(), "oriented.tif"));
        assertEquals(List.of("page 1 90x40 marked", "page 2 90x40 marked", "page 3 90x40 marked"),
                result.elements().stream().map(Element::text).toList());
        assertEquals(6, meta(result.elements().get(0), "image_orientation"));
        assertFalse(result.elements().get(1).metadata().containsKey("image_orientation"));
        assertEquals(8, meta(result.elements().get(2), "image_orientation"));
    }

    @Test
    void hostileTiffDirectoriesFailBeforeAnyLargeAllocation() throws Exception {
        final var ocr = new Recorder();
        final var engine = engine(ocr, ImageOptions.defaults());
        // One 1 x 1 grey strip whose StripByteCounts declares about 2 GB: refused from the directory.
        final var huge = assertThrows(PartitionException.class,
                () -> engine.partition(DocumentSource.bytes(tinyTiff(0, 0x7fff_0000L, 0), "huge.tif")));
        assertEquals(PartitionException.Code.MALFORMED_DOCUMENT, huge.code(), huge.getMessage());
        // The JDK reader refuses the directory itself ("Data segment out of stream"); the partitioner's own extent
        // check is a second guard. Either way nothing is decoded or recognized.
        assertTrue(huge.getMessage().contains("declares data outside the input")
                           || String.valueOf(huge.getCause()).contains("out of stream"),
                huge + " " + huge.getCause());
        final var outside = assertThrows(PartitionException.class,
                () -> engine.partition(DocumentSource.bytes(tinyTiff(0, 1, 5_000), "outside.tif")));
        assertEquals(PartitionException.Code.MALFORMED_DOCUMENT, outside.code(), outside.getMessage());
        // The JDK reader refuses the directory itself ("Data segment out of stream"); the partitioner's own extent
        // check is a second guard. Either way nothing is decoded or recognized.
        assertTrue(outside.getMessage().contains("declares data outside the input")
                           || String.valueOf(outside.getCause()).contains("out of stream"),
                outside + " " + outside.getCause());
        // A directory chain that points back at itself is bounded by maxFrames or refused as malformed.
        final var loop = assertThrows(PartitionException.class,
                ()
                        -> engine(new Recorder(), ImageOptions.defaults().withMaxFrames(5))
                                .partition(DocumentSource.bytes(tinyTiff(8, 1, 0), "loop.tif")));
        assertTrue(loop.code() == PartitionException.Code.IMAGE_LIMIT
                           || loop.code() == PartitionException.Code.MALFORMED_DOCUMENT,
                loop.code() + " " + loop.getMessage());
        assertTrue(ocr.pages.isEmpty(), "no hostile page reaches OCR");
        final var wellFormed = engine(image -> List.of(), ImageOptions.defaults())
                                       .partition(DocumentSource.bytes(tinyTiff(0, 1, 0), "ok.tif"));
        assertEquals(List.of(PartitionResult.WarningCode.IMAGE_NO_TEXT),
                wellFormed.warnings().stream().map(PartitionResult.Warning::code).toList(),
                "the same file with a consistent strip decodes");
    }

    /**
     * A little-endian TIFF with one 1 x 1 8-bit grey page.
     * @param next offset of the next directory (8 = itself, 0 = none)
     * @param byteCount declared StripByteCounts
     * @param extraOffset added to the true strip offset
     */
    private static byte[] tinyTiff(final int next, final long byteCount, final int extraOffset) {
        final int entries = 9;
        final int dataOffset = 8 + 2 + entries * 12 + 4;
        final ByteBuffer out = ByteBuffer.allocate(dataOffset + 1).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        out.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8).putShort((short) entries);
        final int[][] fields = {{256, 3, 1}, {257, 3, 1}, {258, 3, 8}, {259, 3, 1}, {262, 3, 1},
                {273, 4, dataOffset + extraOffset}, {277, 3, 1}, {278, 3, 1}, {279, 4, (int) byteCount}};
        for (final int[] field : fields) {
            out.putShort((short) field[0]).putShort((short) field[1]).putInt(1);
            if (field[1] == 3) {
                out.putShort((short) field[2]).putShort((short) 0);
            } else {
                out.putInt(field[2]);
            }
        }
        out.putInt(next).put((byte) 0x80);
        return out.array();
    }

    @Test
    void exifAfterFillBytesIsStillRead() throws Exception {
        final byte[] jpeg = ImageFixtureGenerator.exif(ImageFixtureGenerator.jpeg(marked(30, 20)), 3);
        final byte[] filled = new byte[jpeg.length + 3];
        filled[0] = jpeg[0];
        filled[1] = jpeg[1];
        filled[2] = (byte) 0xff;
        filled[3] = (byte) 0xff;
        filled[4] = (byte) 0xff;
        System.arraycopy(jpeg, 2, filled, 5, jpeg.length - 2);
        assertEquals(3, ImageOrientation.jpeg(ByteBuffer.wrap(filled)));
        assertEquals(1, ImageOrientation.jpeg(ByteBuffer.wrap(ImageFixtureGenerator.jpeg(marked(30, 20)))));
    }

    @Test
    void multiPageTiffKeepsPageOrderAcrossCompressions() throws Exception {
        for (final String compression : List.of("LZW", "Deflate", "PackBits", "JPEG")) {
            final var ocr = new Recorder();
            final var result =
                    engine(ocr, ImageOptions.defaults())
                            .partition(DocumentSource.bytes(
                                    tiff(compression, marked(100, 50), marked(60, 90), marked(70, 30)), "pages.tiff"));
            assertEquals(List.of("page 1 100x50 marked", "page 2 60x90 marked", "page 3 70x30 marked"),
                    result.elements().stream().map(Element::text).toList(), compression);
            assertEquals(List.of(1, 2, 3), result.elements().stream().map(e -> meta(e, "page_number")).toList());
        }
        final var bilevel = engine(new Recorder(), ImageOptions.defaults())
                                    .partition(DocumentSource.bytes(fixture("pages-g4.tif"), "pages-g4.tif"));
        assertEquals(3, bilevel.elements().size(), "CCITT Group 4 bilevel pages");
    }

    @Test
    void pageAndPixelLimitsAreCheckedBeforeAnyPageIsRecognized() throws Exception {
        final byte[] pages = tiff("LZW", marked(100, 50), marked(100, 50), marked(100, 50));
        final var cases = Map.of(ImageOptions.defaults().withMaxFrames(2), "maxFrames",
                ImageOptions.defaults().withMaxPixels(4_999, 1_000_000), "maxFramePixels",
                ImageOptions.defaults().withMaxPixels(5_000, 14_999), "maxTotalPixels");
        for (final var entry : cases.entrySet()) {
            final var ocr = new Recorder();
            final var failure = assertThrows(PartitionException.class,
                    () -> engine(ocr, entry.getKey()).partition(DocumentSource.bytes(pages, "pages.tif")));
            assertEquals(PartitionException.Code.IMAGE_LIMIT, failure.code());
            assertTrue(failure.getMessage().contains(entry.getValue()), failure.getMessage());
            assertTrue(ocr.pages.isEmpty(), "no page is recognized before the limits pass");
        }
    }

    @Test
    void hugeDeclaredDimensionsAreRefusedFromTheHeader() throws Exception {
        final byte[] png = encode(marked(10, 10), "png");
        // IHDR width and height (bytes 16-23) declare 60000 x 60000 with a valid chunk CRC; nothing is decoded.
        final byte[] huge = png.clone();
        final ByteBuffer header = ByteBuffer.wrap(huge);
        header.putInt(16, 60_000).putInt(20, 60_000);
        final var crc = new java.util.zip.CRC32();
        crc.update(huge, 12, 17);
        header.putInt(29, (int) crc.getValue());
        final var ocr = new Recorder();
        final var failure = assertThrows(PartitionException.class,
                () -> engine(ocr, ImageOptions.defaults()).partition(DocumentSource.bytes(huge, "huge.png")));
        assertEquals(PartitionException.Code.IMAGE_LIMIT, failure.code(), failure.getMessage());
        assertTrue(failure.getMessage().contains("3600000000 pixels"), failure.getMessage());
        assertTrue(ocr.pages.isEmpty());
    }

    @Test
    void transparentPagesAreCompositedOnWhiteBeforeRecognition() throws Exception {
        final var page = new BufferedImage(60, 30, BufferedImage.TYPE_INT_ARGB);
        final var g = page.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(20, 10, 10, 10);
        g.dispose();
        final var ocr = new Recorder();
        engine(ocr, ImageOptions.defaults()).partition(DocumentSource.bytes(encode(page, "png"), "alpha.png"));
        final var seen = ocr.pages.getFirst();
        assertFalse(seen.getColorModel().hasAlpha());
        assertEquals(0xffffff, seen.getRGB(2, 2) & 0xffffff, "transparent background reads as white paper");
        assertEquals(0x000000, seen.getRGB(25, 15) & 0xffffff, "opaque ink is kept");
    }

    @Test
    void malformedTruncatedAndUnsupportedInputsFailPredictably() throws Exception {
        final byte[] png = fixture("text.png");
        final byte[] jpeg = fixture("text.jpg");
        final var engine = engine(new Recorder(), ImageOptions.defaults());
        final Map<String, byte[]> malformed = Map.of("truncated.png", Arrays.copyOf(png, png.length / 2),
                "truncated.jpg", Arrays.copyOf(jpeg, jpeg.length / 2), "not-an-image.png",
                "plain text".getBytes(java.nio.charset.StandardCharsets.US_ASCII), "empty-tiff.tif",
                new byte[] {0x49, 0x49, 0x2a, 0, 8, 0, 0, 0, 0, 0});
        for (final var entry : malformed.entrySet()) {
            final var failure = assertThrows(PartitionException.class,
                    () -> engine.partition(DocumentSource.bytes(entry.getValue(), entry.getKey())), entry.getKey());
            assertEquals(PartitionException.Code.MALFORMED_DOCUMENT, failure.code(), entry.getKey());
        }
        final var bigTiff = assertThrows(PartitionException.class,
                ()
                        -> engine.partition(DocumentSource.bytes(
                                new byte[] {0x49, 0x49, 0x2b, 0, 8, 0, 0, 0, 16, 0, 0, 0, 0, 0, 0, 0}, "big.tif")));
        assertEquals(PartitionException.Code.UNSUPPORTED_FORMAT, bigTiff.code());
    }

    @Test
    void withoutAnOcrProviderImagesAreRecognizedButRefusedClearly() {
        final var discovered = DocWeave.create(PartitionerRegistry.create());
        assertTrue(discovered.supportedFormats().containsAll(
                List.of(DocumentFormat.PNG, DocumentFormat.JPEG, DocumentFormat.TIFF)));
        final var failure = assertThrows(PartitionException.class,
                () -> discovered.partition(DocumentSource.bytes(fixture("text.png"), "scan.png")));
        assertEquals(PartitionException.Code.UNSUPPORTED_OPTION, failure.code());
        assertTrue(failure.getMessage().contains("OCR provider"), failure.getMessage());
    }

    @Test
    void aPageWithoutRecognizedTextIsReportedNotSilentlyEmpty() throws Exception {
        final var result = engine(image -> List.of(), ImageOptions.defaults())
                                   .partition(DocumentSource.bytes(fixture("blank.png"), "blank.png"));
        assertTrue(result.elements().isEmpty());
        assertEquals(List.of(PartitionResult.WarningCode.IMAGE_NO_TEXT),
                result.warnings().stream().map(PartitionResult.Warning::code).toList());
    }

    @Test
    void contentSignatureWinsOverTheFileNameAndExtensionsAreAFallback() throws Exception {
        final var ocr = new Recorder();
        final var result =
                engine(ocr, ImageOptions.defaults()).partition(DocumentSource.bytes(fixture("text.png"), "scan.pdf"));
        assertEquals("image/png", meta(result.elements().getFirst(), "filetype"));
        assertEquals(List.of(PartitionResult.WarningCode.FORMAT_MISMATCH),
                result.warnings().stream().map(PartitionResult.Warning::code).toList());
        final var jpegAsPng = engine(new Recorder(), ImageOptions.defaults())
                                      .partition(DocumentSource.bytes(fixture("text.jpg"), "photo.png"));
        assertEquals("image/jpeg", meta(jpegAsPng.elements().getFirst(), "filetype"));
        final var forced = assertThrows(PartitionException.class,
                ()
                        -> engine(new Recorder(), ImageOptions.defaults())
                                .partition(DocumentSource.text("not an image", "note.png"),
                                        PartitionOptions.builder().format(DocumentFormat.PNG).build()));
        assertEquals(PartitionException.Code.UNSUPPORTED_FORMAT, forced.code());
        assertTrue(forced.getMessage().contains("binary source"), forced.getMessage());
        // A text source named .png is handled as text, as before image support: not routed to an image partitioner.
        final var named = assertThrows(PartitionException.class,
                ()
                        -> engine(new Recorder(), ImageOptions.defaults())
                                .partition(DocumentSource.text("not an image", "note.png")));
        assertEquals(PartitionException.Code.UNSUPPORTED_FORMAT, named.code());
        assertTrue(named.getMessage().contains("Unsupported file extension"), named.getMessage());
        // Another binary format named .png is not routed to the image partitioner by its name.
        final var zip = assertThrows(PartitionException.class,
                ()
                        -> engine(new Recorder(), ImageOptions.defaults())
                                .partition(DocumentSource.bytes(new byte[] {0x50, 0x4b, 3, 4, 0, 0}, "archive.png")));
        assertFalse(zip.getMessage().contains("PNG"), zip.getMessage());
    }

    @Test
    void theInputIsReadInPlaceAndNeverModified() throws Exception {
        final byte[] bytes = fixture("pages-g4.tif");
        final byte[] original = bytes.clone();
        final var ocr = new Recorder();
        final Partitioner noCopy = new Partitioner() {
            private final Partitioner delegate =
                    ImagePartitioner.create(DocumentFormat.TIFF, ImageOptions.defaults(), ocr);

            @Override
            public DocumentFormat format() {
                return DocumentFormat.TIFF;
            }

            @Override
            public void partition(final PartitionContext context) throws IOException {
                delegate.partition(new NoCopyContext(context));
            }
        };
        DocWeave.create(PartitionerRegistry.builder().register(noCopy).build())
                .partition(DocumentSource.ownedBytes(bytes, "pages.tif"));
        assertEquals(3, ocr.pages.size());
        assertArrayEquals(original, bytes, "the input must not be modified");
        final ByteBuffer view = ByteBuffer.wrap(new byte[] {1, 2, 3}).asReadOnlyBuffer();
        try (final var stream = new ImageBufferInput(view)) {
            assertEquals(3, stream.length());
            stream.seek(2);
            assertEquals(3, stream.read());
            assertEquals(-1, stream.read());
        }
        assertEquals(0, view.position(), "the source buffer's position is unchanged");
    }

    @Test
    void interruptionStopsBeforeTheNextPageAndOcrFailuresAreTyped() throws Exception {
        final byte[] pages = tiff("LZW", marked(100, 50), marked(100, 50), marked(100, 50));
        final var seen = new ArrayList<Integer>();
        final PdfOcrProvider interrupting = image -> {
            seen.add(seen.size());
            Thread.currentThread().interrupt();
            return List.of();
        };
        try {
            assertThrows(InterruptedIOException.class,
                    ()
                            -> engine(interrupting, ImageOptions.defaults())
                                    .partition(DocumentSource.bytes(pages, "pages.tif")));
        } finally {
            Thread.interrupted();
        }
        assertEquals(List.of(0), seen, "no further page is decoded or recognized after interruption");
        final PdfOcrProvider failing = image -> {
            throw new UncheckedIOException(new IOException("engine crashed"));
        };
        final var failure = assertThrows(PartitionException.class,
                ()
                        -> engine(failing, ImageOptions.defaults())
                                .partition(DocumentSource.bytes(fixture("text.png"), "scan.png")));
        assertEquals(PartitionException.Code.OCR_FAILURE, failure.code());
        final PdfOcrProvider outside = image -> List.of(new PdfOcrProvider.Line("x", 0, 0, image.getWidth() + 5, 4));
        assertEquals(PartitionException.Code.OCR_FAILURE,
                assertThrows(PartitionException.class,
                        ()
                                -> engine(outside, ImageOptions.defaults())
                                        .partition(DocumentSource.bytes(fixture("text.png"), "scan.png")))
                        .code());
        final PdfOcrProvider chatty = image -> List.of(new PdfOcrProvider.Line("abcdef", 0, 0, 4, 4));
        assertEquals(PartitionException.Code.OCR_LIMIT,
                assertThrows(PartitionException.class,
                        ()
                                -> engine(chatty, new ImageOptions(1, 16_000_000, 16_000_000, 10, 5))
                                        .partition(DocumentSource.bytes(fixture("text.png"), "scan.png")))
                        .code());
    }

    /** Delegates to the library's context but fails if the whole input is requested as a copy. */
    private record NoCopyContext(PartitionContext delegate) implements PartitionContext {
        @Override
        public String text() {
            return delegate.text();
        }

        @Override
        public byte[] binaryContent() {
            throw new AssertionError("image partitioning must read binaryBuffer() instead of copying the input");
        }

        @Override
        public ByteBuffer binaryBuffer() {
            return delegate.binaryBuffer();
        }

        @Override
        public PartitionProgressReporter progress() {
            return delegate.progress();
        }

        @Override
        public String filename() {
            return delegate.filename();
        }

        @Override
        public DocumentFormat format() {
            return delegate.format();
        }

        @Override
        public PartitionOptions options() {
            return delegate.options();
        }

        @Override
        public int elementCount() {
            return delegate.elementCount();
        }

        @Override
        public Element elementAt(final int index) {
            return delegate.elementAt(index);
        }

        @Override
        public Element emit(final ElementType type, final String text, final Map<String, Object> metadata)
                throws IOException {
            return delegate.emit(type, text, metadata);
        }

        @Override
        public void warn(final PartitionResult.WarningCode code, final String message) {
            delegate.warn(code, message);
        }
    }
}
