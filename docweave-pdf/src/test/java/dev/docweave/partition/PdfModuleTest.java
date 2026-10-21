package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.core.ElementType;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.*;

class PdfModuleTest {
    private final DocWeave processor = DocWeave.create();

    @Test
    void standaloneDiscoveryAndDependencyIsolation() {
        assertEquals(Set.of(DocumentFormat.PDF), processor.supportedFormats());
        assertFalse(java.lang.reflect.Modifier.isPublic(
                PartitionerRegistry.create().find(DocumentFormat.PDF).orElseThrow().getClass().getModifiers()));
        for (final String type :
                List.of("org.apache.poi.xwpf.usermodel.XWPFDocument", "org.jsoup.Jsoup", "org.commonmark.parser.Parser",
                        "org.apache.commons.csv.CSVFormat", "tools.jackson.databind.json.JsonMapper")) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(type));
        }
    }

    @Test
    void binaryRoutingPageCoordinatesAndFileEquivalence(final @TempDir Path directory) throws Exception {
        final byte[] bytes = fixture(false, false, false);
        final var result = processor.partition(DocumentSource.bytes(bytes, "input.pdf"));
        assertEquals(DocumentFormat.PDF, result.format());
        assertEquals("binary", result.encoding());
        assertEquals(bytes.length, result.inputBytes());
        assertEquals(List.of("First page.", "Second page."), result.elements().stream().map(e -> e.text()).toList());
        assertEquals(1, result.elements().getFirst().metadata().get("page_number"));
        assertEquals(2, result.elements().getLast().metadata().get("page_number"));
        final Map<?, ?> coordinates = (Map<?, ?>) result.elements().getFirst().metadata().get("coordinates");
        assertEquals("PDFUserSpace", coordinates.get("system"));
        final List<?> firstPoint = (List<?>) ((List<?>) coordinates.get("points")).getFirst();
        assertEquals(40, ((Number) firstPoint.getFirst()).floatValue(), 0.01);
        final Path file = directory.resolve("input.pdf");
        Files.write(file, bytes);
        assertEquals(result.elements(), processor.partition(file).elements());
        assertEquals(PartitionResult.WarningCode.FORMAT_MISMATCH,
                processor.partition(DocumentSource.bytes(bytes, "fake.txt")).warnings().getFirst().code());
        assertEquals(result.elements().stream().map(e -> e.text()).toList(),
                processor.partition(DocumentSource.bytes(bytes, null)).elements().stream().map(e -> e.text()).toList());
        assertCode(PartitionException.Code.UNSUPPORTED_FORMAT,
                () -> processor.partition(DocumentSource.text("%PDF-1.7", "text.pdf")));
    }

    @Test
    void completeGridOwnsTextOnceAndEscapesHtmlWithoutInventingHeaders() throws Exception {
        final var result = processor.partition(DocumentSource.bytes(fixture(true, false, false), "grid.pdf"));
        final var tables = result.elements().stream().filter(e -> e.type() == ElementType.Table).toList();
        assertEquals(1, tables.size());
        assertEquals("A&B <C> D E", tables.getFirst().text());
        assertEquals(
                "<table><tbody><tr><td>A&amp;B</td><td>&lt;C&gt;</td></tr><tr><td>D</td><td>E</td></tr></tbody></table>",
                tables.getFirst().metadata().get("text_as_html"));
        assertEquals(List.of("First page.", "A&B <C> D E", "Second page."),
                result.elements().stream().map(e -> e.text()).toList());
    }

    @Test
    void incompleteGridRetainsAllTextAndReportsNativeLimitations() throws Exception {
        final var result = processor.partition(DocumentSource.bytes(fixture(true, false, true), "partial.pdf"));
        assertTrue(result.elements().stream().noneMatch(e -> e.type() == ElementType.Table));
        final String text = String.join(" ", result.elements().stream().map(e -> e.text()).toList());
        for (final String value : List.of("A&B", "<C>", "D", "E")) {
            assertTrue(text.contains(value));
        }
        assertTrue(result.warnings().stream().anyMatch(
                w -> w.code() == PartitionResult.WarningCode.PDF_EXTRACTION_LIMITATION));
    }

    @Test
    void imagePlacementsAreOccurrencesAndPayloadsAreOptIn() throws Exception {
        final byte[] bytes = fixture(false, true, false);
        final var references = processor.partition(DocumentSource.bytes(bytes, "images.pdf"));
        final var images = references.elements().stream().filter(e -> e.type() == ElementType.Image).toList();
        assertEquals(2, images.size());
        assertNotEquals(images.getFirst().elementId(), images.getLast().elementId());
        assertEquals(3, images.getFirst().metadata().get("image_width"));
        assertEquals(2, images.getFirst().metadata().get("image_height"));
        assertFalse(images.getFirst().metadata().containsKey("image_base64"));
        assertEquals("", images.getFirst().text());
        final var extracted = configured(new PdfOptions(10, 10000, 10000, 100, 10000, true, TableLimits.defaults()))
                                      .partition(DocumentSource.bytes(bytes, "images.pdf"));
        final var image =
                extracted.elements().stream().filter(e -> e.type() == ElementType.Image).findFirst().orElseThrow();
        assertEquals("image/png", image.metadata().get("image_mime_type"));
        final var decoded = ImageIO.read(
                new ByteArrayInputStream(Base64.getDecoder().decode((String) image.metadata().get("image_base64"))));
        assertEquals(3, decoded.getWidth());
        assertEquals(2, decoded.getHeight());
        assertEquals(0xffff0000, decoded.getRGB(1, 1));
    }

    @Test
    void formImageTransformAndCumulativePayloadBudgetAreHonored() throws Exception {
        try (final var document = new PDDocument()) {
            final var page = new PDPage(new PDRectangle(400, 400));
            document.addPage(page);
            final var form = new org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject(document);
            form.setResources(new org.apache.pdfbox.pdmodel.PDResources());
            form.setBBox(new PDRectangle(20, 20));
            try (final var stream = new org.apache.pdfbox.pdmodel.PDFormContentStream(form)) {
                stream.drawImage(
                        LosslessFactory.createFromImage(document, new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)),
                        0, 0, 20, 20);
            }
            try (final var stream = new PDPageContentStream(document, page)) {
                stream.saveGraphicsState();
                stream.transform(org.apache.pdfbox.util.Matrix.getTranslateInstance(60, 100));
                stream.drawForm(form);
                stream.restoreGraphicsState();
            }
            final var result = processor.partition(DocumentSource.bytes(save(document), "form.pdf"));
            assertEquals(1, result.elements().size());
            assertEquals(ElementType.Image, result.elements().getFirst().type());
            final var coordinates = (Map<?, ?>) result.elements().getFirst().metadata().get("coordinates");
            assertEquals(
                    List.of(List.of(60.0, 280.0), List.of(80.0, 280.0), List.of(80.0, 300.0), List.of(60.0, 300.0)),
                    coordinates.get("points"));
        }
        final var source = DocumentSource.bytes(fixture(false, true, false), "twice.pdf");
        final var payloads = configured(new PdfOptions(10, 10000, 10000, 100, 10000, true, TableLimits.defaults()))
                                     .partition(source)
                                     .elements()
                                     .stream()
                                     .filter(e -> e.type() == ElementType.Image)
                                     .toList();
        final int bytes =
                Base64.getDecoder().decode((String) payloads.getFirst().metadata().get("image_base64")).length;
        assertCode(PartitionException.Code.IMAGE_LIMIT,
                ()
                        -> configured(
                                new PdfOptions(10, 10000, 10000, 100, bytes * 2 - 1, true, TableLimits.defaults()))
                                .partition(source));
    }

    @Test
    void imageOnlyPagesAreExplicitlyNotOcr() throws Exception {
        try (final var document = new PDDocument()) {
            final var page = new PDPage();
            document.addPage(page);
            try (final var stream = new PDPageContentStream(document, page)) {
                stream.drawImage(
                        LosslessFactory.createFromImage(document, new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)),
                        20, 20);
            }
            final var result = processor.partition(DocumentSource.bytes(save(document), "scan.pdf"));
            assertEquals(List.of(ElementType.Image), result.elements().stream().map(e -> e.type()).toList());
            assertTrue(result.warnings().stream().anyMatch(w -> w.message().contains("OCR was not performed")));
        }
    }

    @Test
    void pageGlyphOperatorElementAndInputLimitsFailWithTypedCodes() throws Exception {
        final var source = DocumentSource.bytes(fixture(false, false, false), "limit.pdf");
        assertCode(PartitionException.Code.INPUT_TOO_LARGE,
                ()
                        -> configured(new PdfOptions(1, 10000, 10000, 100, 10000, false, TableLimits.defaults()))
                                .partition(source));
        assertCode(PartitionException.Code.INPUT_TOO_LARGE,
                ()
                        -> configured(new PdfOptions(10, 1, 10000, 100, 10000, false, TableLimits.defaults()))
                                .partition(source));
        assertCode(PartitionException.Code.INPUT_TOO_LARGE,
                ()
                        -> configured(new PdfOptions(10, 10000, 1, 100, 10000, false, TableLimits.defaults()))
                                .partition(source));
        assertCode(PartitionException.Code.ELEMENT_LIMIT,
                () -> processor.partition(source, PartitionOptions.builder().maxElements(1).build()));
        assertCode(PartitionException.Code.INPUT_TOO_LARGE,
                () -> processor.partition(source, PartitionOptions.builder().maxInputBytes(10).build()));
    }

    @Test
    void imageAndTableLimitsPropagateThroughPdfboxCallbacks() throws Exception {
        final var images = DocumentSource.bytes(fixture(false, true, false), "image.pdf");
        assertCode(PartitionException.Code.IMAGE_LIMIT,
                ()
                        -> configured(new PdfOptions(10, 10000, 10000, 1, 10000, true, TableLimits.defaults()))
                                .partition(images));
        assertCode(PartitionException.Code.IMAGE_LIMIT,
                ()
                        -> configured(new PdfOptions(10, 10000, 10000, 100, 1, true, TableLimits.defaults()))
                                .partition(images));
        final var table = DocumentSource.bytes(fixture(true, false, false), "table.pdf");
        assertCode(PartitionException.Code.TABLE_LIMIT,
                ()
                        -> configured(
                                new PdfOptions(10, 10000, 10000, 100, 10000, false, new TableLimits(1, 2, 2, 100)))
                                .partition(table));
        assertCode(PartitionException.Code.TABLE_LIMIT,
                ()
                        -> configured(new PdfOptions(10, 10000, 10000, 100, 10000, false, new TableLimits(2, 2, 4, 1)))
                                .partition(table));
    }

    @Test
    void malformedAndEncryptedInputsFailExplicitly() throws Exception {
        assertCode(PartitionException.Code.MALFORMED_DOCUMENT,
                ()
                        -> processor.partition(DocumentSource.bytes(
                                "%PDF-1.7\ninvalid".getBytes(java.nio.charset.StandardCharsets.US_ASCII), "bad.pdf")));
        try (final var document = new PDDocument()) {
            document.addPage(new PDPage());
            document.protect(new StandardProtectionPolicy("owner", "secret", new AccessPermission()));
            assertCode(PartitionException.Code.UNSUPPORTED_OPTION,
                    () -> processor.partition(DocumentSource.bytes(save(document), "locked.pdf")));
        }
        try (final var document = new PDDocument()) {
            document.addPage(new PDPage());
            final var permissions = new AccessPermission();
            permissions.setCanExtractContent(false);
            document.protect(new StandardProtectionPolicy("owner", "", permissions));
            assertCode(PartitionException.Code.UNSUPPORTED_OPTION,
                    () -> processor.partition(DocumentSource.bytes(save(document), "restricted.pdf")));
        }
    }

    @Test
    void unsupportedAnglesAndNestedPolicyFailExplicitly() throws Exception {
        try (final var document = new PDDocument()) {
            final var page = new PDPage();
            page.setRotation(45);
            document.addPage(page);
            assertCode(PartitionException.Code.UNSUPPORTED_OPTION,
                    () -> processor.partition(DocumentSource.bytes(save(document), "rotated.pdf")));
        }
        assertCode(PartitionException.Code.UNSUPPORTED_OPTION,
                ()
                        -> processor.partition(DocumentSource.bytes(fixture(false, false, false), "nested.pdf"),
                                PartitionOptions.builder()
                                        .nestedTableOptions(new NestedTableOptions(
                                                NestedTableOptions.Mode.PRESERVE_METADATA, 256, 16, 1024 * 1024))
                                        .build()));
    }

    @Test
    void sharedStrategyIsDeterministicUnderVirtualThreadsAndCancellationPreservesFlag() throws Exception {
        final var source = DocumentSource.bytes(fixture(true, true, false), "shared.pdf");
        final var expected = processor.partition(source).elements();
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var tasks = java.util.stream.IntStream.range(0, 24)
                                      .mapToObj(index -> executor.submit(() -> processor.partition(source).elements()))
                                      .toList();
            for (final var task : tasks) {
                assertEquals(expected, task.get());
            }
        }
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.io.InterruptedIOException.class, () -> processor.partition(source));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals(expected, processor.partition(source).elements());
    }

    private static DocWeave configured(final PdfOptions options) {
        return DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options))));
    }

    private static byte[] fixture(final boolean table, final boolean images, final boolean incomplete)
            throws Exception {
        try (final var document = new PDDocument()) {
            final var first = new PDPage(new PDRectangle(400, 400));
            document.addPage(first);
            try (final var stream = new PDPageContentStream(document, first)) {
                text(stream, 40, 360, "First page.");
                if (table) {
                    for (final int y : new int[] {300, 260, 220}) {
                        stream.moveTo(40, y);
                        stream.lineTo(incomplete && y == 260 ? 145 : 260, y);
                    }
                    for (final int x : new int[] {40, 150, 260}) {
                        stream.moveTo(x, 220);
                        stream.lineTo(x, 300);
                    }
                    stream.stroke();
                    text(stream, 50, 280, "A&B");
                    text(stream, 160, 280, "<C>");
                    text(stream, 50, 240, "D");
                    text(stream, 160, 240, "E");
                }
                if (images) {
                    final var pixels = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
                    for (int y = 0; y < 2; y++) {
                        for (int x = 0; x < 3; x++) {
                            pixels.setRGB(x, y, 0xffff0000);
                        }
                    }
                    final var image = LosslessFactory.createFromImage(document, pixels);
                    stream.drawImage(image, 40, 40, 30, 20);
                    stream.drawImage(image, 100, 40, 30, 20);
                }
            }
            final var second = new PDPage(new PDRectangle(400, 400));
            document.addPage(second);
            try (final var stream = new PDPageContentStream(document, second)) {
                text(stream, 40, 360, "Second page.");
            }
            return save(document);
        }
    }

    private static void text(final PDPageContentStream stream, final float x, final float y, final String value)
            throws Exception {
        stream.beginText();
        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        stream.newLineAtOffset(x, y);
        stream.showText(value);
        stream.endText();
    }

    private static byte[] save(final PDDocument document) throws Exception {
        final var output = new ByteArrayOutputStream();
        document.save(output);
        return output.toByteArray();
    }

    private static void assertCode(
            final PartitionException.Code expected, final org.junit.jupiter.api.function.Executable work) {
        assertEquals(expected, assertThrows(PartitionException.class, work).code());
    }
}
