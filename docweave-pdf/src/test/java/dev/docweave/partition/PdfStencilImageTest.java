package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDFormContentStream;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPatternContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDPattern;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.pattern.PDTilingPattern;
import org.apache.pdfbox.util.Matrix;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;

/** Public image-payload regression for source stencil paint, transparency and unchanged extraction budgets. */
class PdfStencilImageTest {
    /** Creates one owned regression-test instance without shared mutable PDF state. */
    PdfStencilImageTest() {
    }

    /**
     * Solid fill belongs to each placement, including inherited form paint and both standard decode polarities.
     * @throws Exception if owned source construction, extraction or payload decoding fails
     */
    @Test
    void solidStencilsKeepPerOccurrencePaintTransparentPixelsAndReferenceGeometry() throws Exception {
        for (final boolean inverse : List.of(false, true)) {
            final byte[] pdf = repeated(inverse);
            final var references = images(pdf, options(false, 100, 10_000));
            final var payloads = images(pdf, options(true, 100, 10_000));
            // Expected colors are tied to original placement coordinates, independent of geometric output ordering.
            final var colors = Map.of(new Position(20, 170), Color.RED, new Position(60, 170), Color.BLUE,
                    new Position(100, 170), Color.RED, new Position(20, 110), Color.GREEN);
            final var seen = new HashSet<Position>();
            assertEquals(4, payloads.size());
            assertEquals(4, references.size());
            for (int index = 0; index < colors.size(); index++) {
                final var image = payloads.get(index);
                final var position = position(image);
                assertTrue(seen.add(position));
                assertTrue(colors.containsKey(position));
                final var pixels = pixels(image);
                try {
                    assertEquals(2, pixels.getWidth());
                    assertEquals(1, pixels.getHeight());
                    assertEquals(colors.get(position).getRGB(), pixels.getRGB(inverse ? 1 : 0, 0));
                    assertEquals(0, pixels.getRGB(inverse ? 0 : 1, 0) >>> 24);
                } finally {
                    pixels.flush();
                }
                assertEquals(true, image.metadata().get("image_stencil"));
                assertEquals("stencil_solid_fill_v1", image.metadata().get("image_pixel_policy"));
                final var originalMetadata = new LinkedHashMap<>(image.metadata());
                for (final String key :
                        List.of("image_base64", "image_mime_type", "image_stencil", "image_pixel_policy")) {
                    originalMetadata.remove(key);
                }
                assertEquals(references.get(index).metadata(), originalMetadata);
            }
            assertEquals(colors.keySet(), seen);
        }
    }

    /**
     * Inline image masks use the same occurrence paint and alpha contract as reusable image XObjects.
     * @throws Exception if owned source construction, extraction or payload decoding fails
     */
    @Test
    void inlineStencilRetainsSolidBlueAndTransparentPixels() throws Exception {
        try (final var document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(100, 100));
            document.addPage(page);
            final String commands = "q 20 0 0 10 20 40 cm 0 0 1 rg BI /W 2 /H 1 /BPC 1 /IM true ID @ EI Q\n";
            page.setContents(
                    new PDStream(document, new ByteArrayInputStream(commands.getBytes(StandardCharsets.US_ASCII))));
            final var actual = images(save(document), options(true, 100, 10_000));
            assertEquals(1, actual.size());
            final var pixels = pixels(actual.getFirst());
            try {
                assertEquals(Color.BLUE.getRGB(), pixels.getRGB(0, 0));
                assertEquals(0, pixels.getRGB(1, 0) >>> 24);
            } finally {
                pixels.flush();
            }
            assertEquals("stencil_solid_fill_v1", actual.getFirst().metadata().get("image_pixel_policy"));
        }
    }

    /**
     * Pattern paint remains an explicitly labelled source mask rather than an invented flat-color rendering.
     * @throws Exception if owned source construction, extraction or payload decoding fails
     */
    @Test
    void patternStencilKeepsMaskSamplesWithoutAttemptingPatternColorConversion() throws Exception {
        try (final var document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(100, 100));
            page.setResources(new PDResources());
            document.addPage(page);
            final var pattern = new PDTilingPattern();
            pattern.setPaintType(PDTilingPattern.PAINT_COLORED);
            pattern.setTilingType(PDTilingPattern.TILING_CONSTANT_SPACING);
            pattern.setBBox(new PDRectangle(2, 2));
            pattern.setXStep(2);
            pattern.setYStep(2);
            pattern.setResources(new PDResources());
            try (final var content = new PDPatternContentStream(pattern)) {
                content.setNonStrokingColor(Color.GREEN);
                content.addRect(0, 0, 1, 1);
                content.fill();
            }
            final var name = COSName.getPDFName("OwnedPattern");
            page.getResources().put(name, pattern);
            try (final var content = new PDPageContentStream(document, page)) {
                content.setNonStrokingColor(new PDColor(name, new PDPattern(page.getResources())));
                content.drawImage(mask(document, false), 20, 20, 20, 10);
            }
            final byte[] source = save(document);
            final var actual = images(source, options(true, 100, 10_000));
            assertEquals(1, actual.size());
            assertEquals("stencil_mask_samples_v1", actual.getFirst().metadata().get("image_pixel_policy"));
            assertEquals(true, actual.getFirst().metadata().get("image_stencil"));
            final var pixels = pixels(actual.getFirst());
            try {
                assertEquals(0xff000000, pixels.getRGB(0, 0));
                assertEquals(0xffffffff, pixels.getRGB(1, 0));
            } finally {
                pixels.flush();
            }
            assertFalse(images(source, options(false, 100, 10_000)).getFirst().metadata().containsKey("image_stencil"));
        }
    }

    /**
     * Encoded-byte charging remains cumulative across differently painted occurrences of one source mask.
     * @throws Exception if owned source construction, extraction or payload decoding fails
     */
    @Test
    void stencilPayloadsHonorPixelPreflightAndExactCumulativeByteCeilings() throws Exception {
        final byte[] source = repeated(false);
        final var payloads = images(source, options(true, 100, 10_000));
        final int total = payloads.stream().mapToInt(e -> decoded(e).length).sum();
        assertEquals(4, images(source, options(true, 2, total)).size());
        assertEquals(PartitionException.Code.IMAGE_LIMIT,
                assertThrows(PartitionException.class, () -> images(source, options(true, 2, total - 1))).code());
        assertEquals(PartitionException.Code.IMAGE_LIMIT,
                assertThrows(PartitionException.class, () -> images(source, options(true, 1, total))).code());
        assertEquals(4, images(source, options(false, 1, 1)).size());
    }

    /**
     * Ordinary masked RGBA images keep their intrinsic colors/alpha despite a different active solid paint.
     * @throws Exception if owned source construction, extraction or payload decoding fails
     */
    @Test
    void ordinaryRgbaImageIsNeverRecoloredAsAStencil() throws Exception {
        try (final var document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(100, 100));
            document.addPage(page);
            final var original = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
            original.setRGB(0, 0, 0x8000ff00);
            original.setRGB(1, 0, 0);
            try {
                try (final var content = new PDPageContentStream(document, page)) {
                    content.setNonStrokingColor(Color.RED);
                    content.drawImage(LosslessFactory.createFromImage(document, original), 20, 20, 20, 10);
                }
            } finally {
                original.flush();
            }
            final var actual = images(save(document), options(true, 100, 10_000)).getFirst();
            assertFalse(actual.metadata().containsKey("image_stencil"));
            assertFalse(actual.metadata().containsKey("image_pixel_policy"));
            final var pixels = pixels(actual);
            try {
                assertEquals(0x8000ff00, pixels.getRGB(0, 0));
                assertEquals(0, pixels.getRGB(1, 0) >>> 24);
            } finally {
                pixels.flush();
            }
        }
    }

    /**
     * An independently authored displayed placement origin, not a label selected from image content.
     * @param left source left in displayed PDF coordinates
     * @param top source top in displayed PDF coordinates
     */
    private record Position(double left, double top) {}

    /**
     * Reads the public displayed image origin for association with fixed fixture labels.
     * @param image emitted image placement
     * @return displayed top-left origin
     */
    private static Position position(final Element image) {
        final var coordinates = (Map<?, ?>) image.metadata().get("coordinates");
        assertEquals("PDFUserSpace", coordinates.get("system"));
        final var first = (List<?>) ((List<?>) coordinates.get("points")).getFirst();
        return new Position(((Number) first.getFirst()).doubleValue(), ((Number) first.get(1)).doubleValue());
    }

    /**
     * Builds four occurrences of one stencil with explicit, restored and inherited form colors.
     * @param inverse whether the two mask samples have inverted PDF decode values
     * @return owned complete document bytes
     * @throws Exception if owned fixture construction fails
     */
    private static byte[] repeated(final boolean inverse) throws Exception {
        try (final var document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(200, 200));
            document.addPage(page);
            final var mask = mask(document, inverse);
            final var form = new PDFormXObject(document);
            form.setResources(new PDResources());
            form.setBBox(new PDRectangle(20, 10));
            try (final var content = new PDFormContentStream(form)) {
                content.drawImage(mask, 0, 0, 20, 10);
            }
            try (final var content = new PDPageContentStream(document, page)) {
                content.setNonStrokingColor(Color.RED);
                content.drawImage(mask, 20, 20, 20, 10);
                content.saveGraphicsState();
                content.setNonStrokingColor(Color.BLUE);
                content.drawImage(mask, 60, 20, 20, 10);
                content.restoreGraphicsState();
                content.drawImage(mask, 100, 20, 20, 10);
                content.setNonStrokingColor(Color.GREEN);
                content.transform(Matrix.getTranslateInstance(20, 80));
                content.drawForm(form);
            }
            return save(document);
        }
    }

    /**
     * Creates two adjacent one-bit samples; decode polarity alone selects the painted sample.
     * @param document fixture owner
     * @param inverse whether one rather than zero denotes paint
     * @return reusable owned stencil
     * @throws Exception if fixture stream construction fails
     */
    private static PDImageXObject mask(final PDDocument document, final boolean inverse) throws Exception {
        final var mask = new PDImageXObject(document);
        mask.setWidth(2);
        mask.setHeight(1);
        mask.setBitsPerComponent(1);
        mask.setStencil(true);
        final var decode = new COSArray();
        decode.add(inverse ? COSInteger.ONE : COSInteger.ZERO);
        decode.add(inverse ? COSInteger.ZERO : COSInteger.ONE);
        mask.setDecode(decode);
        try (final var output = mask.getCOSObject().createOutputStream()) {
            output.write(0x40);
        }
        return mask;
    }

    /**
     * Selects bounded image payloads without changing other native extraction policies.
     * @param payloads whether to decode/encode images
     * @param pixels maximum source pixels per occurrence
     * @param bytes document-wide PNG byte ceiling
     * @return immutable native options
     */
    private static PdfOptions options(final boolean payloads, final long pixels, final int bytes) {
        return new PdfOptions(10, 10_000, 10_000, pixels, bytes, payloads, TableLimits.defaults());
    }

    /**
     * Extracts complete public image occurrences from an owned source without OCR or provider callbacks.
     * @param source complete PDF bytes
     * @param options explicit native limits and payload policy
     * @return complete image occurrences in public output order
     * @throws Exception if extraction fails
     */
    private static List<Element> images(final byte[] source, final PdfOptions options) throws Exception {
        return DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options))))
                .partition(DocumentSource.bytes(source, "owned-stencils.pdf"))
                .elements()
                .stream()
                .filter(e -> e.type() == ElementType.Image)
                .toList();
    }

    /**
     * Reads one actual encoded payload, without generating expected pixels from the decoder under test.
     * @param image emitted public Image element
     * @return decoded PNG bytes
     */
    private static byte[] decoded(final Element image) {
        return Base64.getDecoder().decode((String) image.metadata().get("image_base64"));
    }

    /**
     * Decodes an emitted PNG for analytic per-pixel expectations.
     * @param image emitted Image element
     * @return caller-owned pixels
     * @throws Exception if payload decoding fails
     */
    private static BufferedImage pixels(final Element image) throws Exception {
        assertEquals("image/png", image.metadata().get("image_mime_type"));
        final var pixels = ImageIO.read(new ByteArrayInputStream(decoded(image)));
        assertNotNull(pixels);
        return pixels;
    }

    /**
     * Serializes the complete owned PDF without copying pages or changing source geometry.
     * @param document owned fixture
     * @return complete source bytes
     * @throws Exception if fixture serialization fails
     */
    private static byte[] save(final PDDocument document) throws Exception {
        final var output = new ByteArrayOutputStream();
        document.save(output);
        return output.toByteArray();
    }
}
