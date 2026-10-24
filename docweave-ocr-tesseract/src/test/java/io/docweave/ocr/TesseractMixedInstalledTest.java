package io.docweave.ocr;

import io.docweave.DocWeave;
import io.docweave.core.ElementType;
import io.docweave.partition.DocumentSource;
import io.docweave.partition.PartitionerRegistry;
import io.docweave.partition.PdfOcrOptions;
import io.docweave.partition.PdfOptions;
import io.docweave.partition.PdfPartitioner;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class TesseractMixedInstalledTest {
    @Test
    void realOcrRetainsNativeTextAndAvoidsDuplicatingOverlappingImagePlacements() throws Exception {
        final String executable = System.getProperty("docweave.tesseract.executable");
        final String data = System.getProperty("docweave.tesseract.tessdata");
        final String report = System.getProperty("docweave.tesseract.report");
        final String independent = System.getProperty("docweave.tesseract.mixed.pdf");
        if (executable == null && data == null && report == null && independent == null) {
            return;
        }
        assertNotNull(executable);
        assertNotNull(data);
        assertNotNull(report);
        final var base = TesseractOptions.defaults();
        final var policy = new TesseractOptions(Path.of(executable), Path.of(data), List.of("eng"), base.timeout(), 1,
                base.maxImagePixels(), base.maxPngBytes(), base.maxOutputBytes(), base.maxLines(),
                base.maxCharacters());
        final var provider = TesseractOcr.create(policy);
        final var processor =
                DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(PdfOptions.defaults(),
                        PdfOcrOptions.defaults().withSelection(PdfOcrOptions.Selection.IMAGE_REGIONS), provider))));
        final var raster = new BufferedImage(1400, 400, BufferedImage.TYPE_INT_RGB);
        final var graphics = raster.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 1400, 400);
            graphics.setColor(Color.BLACK);
            graphics.setFont(new Font("DejaVu Sans", Font.BOLD, 48));
            graphics.drawString("DocWeave OCR validation", 80, 130);
            graphics.drawString("Invoice total 12345", 80, 230);
        } finally {
            graphics.dispose();
        }
        final byte[] bytes;
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(672, 300));
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                final var image = LosslessFactory.createFromImage(document, raster);
                content.drawImage(image, 0, 40, 672, 192);
                content.drawImage(image, 0, 40, 672, 192);
                nativeText(content, "Native heading", 40, 280);
                nativeText(content, "Native label", 40, 70);
            }
            document.save(output);
            bytes = output.toByteArray();
        } finally {
            raster.flush();
        }
        final var source = DocumentSource.bytes(bytes, "owned-mixed.pdf");
        final var nativeResult = DocWeave.create().partition(source);
        final var result = processor.partition(source);
        final var ocr = result.elements()
                                .stream()
                                .filter(element -> element.metadata().containsKey("pdf_text_method"))
                                .toList();
        assertEquals(List.of("DocWeave OCR validation", "Invoice total 12345"),
                ocr.stream().map(element -> element.text()).toList());
        assertEquals(
                nativeResult.elements(), result.elements()
                                                 .stream()
                                                 .filter(element -> !element.metadata().containsKey("pdf_text_method"))
                                                 .toList());
        assertEquals(2, result.elements().stream().filter(element -> element.type() == ElementType.Image).count());
        assertTrue(ocr.stream().allMatch(element -> element.metadata().get("pdf_ocr_region_count").equals(1)));
        final var evidence = new Properties();
        evidence.setProperty("checked_at", Instant.now().toString());
        evidence.setProperty("scope",
                "owned mixed PDF and optional independent scan with an owned native heading; no accuracy claim");
        evidence.setProperty("owned_pdf_sha256", sha256(bytes));
        evidence.setProperty("executable_sha256", sha256(Files.readAllBytes(policy.executable())));
        evidence.setProperty(
                "eng_model_sha256", sha256(Files.readAllBytes(policy.tessdataDirectory().resolve("eng.traineddata"))));
        evidence.setProperty("owned_ocr_lines", "2");
        evidence.setProperty("owned_native_elements_preserved", "true");
        evidence.setProperty("owned_image_occurrences", "2");
        evidence.setProperty("owned_distinct_regions", "1");
        if (independent != null) {
            final byte[] original = Files.readAllBytes(Path.of(independent));
            assertEquals(9805, original.length);
            assertEquals("e21174353d5a330ed41bcc4e234943a6527c635dec2a4be1a4fe701827bd0b1c", sha256(original));
            assertEquals("2542e3e5d83b1b911b7c39824f0b223322ba5239",
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(
                            concat(("blob " + original.length + "\0").getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                    original))));
            final byte[] modified;
            try (final var document = Loader.loadPDF(original); final var output = new ByteArrayOutputStream()) {
                final var page = document.getPage(0);
                try (final var content = new PDPageContentStream(
                             document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    nativeText(content, "DocWeave owned heading", 100, page.getCropBox().getHeight() - 20);
                }
                document.save(output);
                modified = output.toByteArray();
            }
            final var externalSource = DocumentSource.bytes(modified, "independent-scan-with-owned-heading.pdf");
            final var externalNative = DocWeave.create().partition(externalSource);
            final var external = processor.partition(externalSource);
            final var externalOcr = external.elements()
                                            .stream()
                                            .filter(element -> element.metadata().containsKey("pdf_text_method"))
                                            .toList();
            assertEquals(externalNative.elements(),
                    external.elements()
                            .stream()
                            .filter(element -> !element.metadata().containsKey("pdf_text_method"))
                            .toList());
            assertFalse(externalOcr.isEmpty(), "Independent scan should exercise actual OCR emission");
            assertTrue(externalOcr.stream().noneMatch(element -> element.text().contains("DocWeave owned heading")));
            final String recognized = String.join("\n", externalOcr.stream().map(element -> element.text()).toList());
            evidence.setProperty("independent_original_sha256", sha256(original));
            evidence.setProperty("independent_modified_sha256", sha256(modified));
            evidence.setProperty("independent_ocr_line_count", Integer.toString(externalOcr.size()));
            evidence.setProperty("independent_ocr_utf16_units", Integer.toString(recognized.length()));
            evidence.setProperty("independent_ocr_text_sha256",
                    sha256(recognized.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            evidence.setProperty("independent_native_elements_preserved", "true");
            evidence.setProperty(
                    "independent_transcription_accuracy", "not measured; handwritten source, no approved oracle");
        }
        final var destination = Path.of(report + ".mixed.xml");
        try (final var output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) {
            evidence.storeToXML(output, "Real Tesseract mixed-page integration evidence");
        }
    }

    private static void nativeText(final PDPageContentStream content, final String text, final float x, final float y)
            throws Exception {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        content.newLineAtOffset(x, y);
        content.showText(text);
        content.endText();
    }

    private static String sha256(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static byte[] concat(final byte[] prefix, final byte[] bytes) {
        final var joined = java.util.Arrays.copyOf(prefix, prefix.length + bytes.length);
        System.arraycopy(bytes, 0, joined, prefix.length, bytes.length);
        return joined;
    }
}
