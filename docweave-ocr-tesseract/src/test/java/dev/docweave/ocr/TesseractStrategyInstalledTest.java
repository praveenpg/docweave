package dev.docweave.ocr;

import dev.docweave.DocWeave;
import dev.docweave.core.ElementType;
import dev.docweave.partition.DocumentSource;
import dev.docweave.partition.PartitionerRegistry;
import dev.docweave.partition.PdfPartitioner;
import dev.docweave.partition.PdfStrategy;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit installed-engine validation of presets on an owned PDF with searchable native text. */
class TesseractStrategyInstalledTest {
    /** Creates a control without installing or discovering engines/models. */
    TesseractStrategyInstalledTest() {
    }

    /**
     * Verifies real forced recognition, word-layout composition and native-preferred provider avoidance.
     * @throws Exception if explicitly requested local assets, recognition or fresh receipt writing fail
     */
    @Test
    void explicitlyConfiguredPresetsRecognizeNativePageWithoutDuplicates() throws Exception {
        final String executable = System.getProperty("docweave.tesseract.executable");
        final String data = System.getProperty("docweave.tesseract.tessdata");
        final String report = System.getProperty("docweave.tesseract.report");
        if (executable == null && data == null && report == null) {
            return;
        }
        assertNotNull(executable);
        assertNotNull(data);
        assertNotNull(report);
        final var options = new TesseractOptions(Path.of(executable), Path.of(data), List.of("eng"),
                Duration.ofSeconds(30), 1, 16_000_000, 32_000_000, 4_000_000, 1000, 100_000);
        final var provider = TesseractOcr.createWithWords(options);
        final var source = source();
        final var observations = new Properties();
        for (final var strategy : PdfStrategy.values()) {
            final var ocrCalls = new AtomicInteger();
            final var layoutCalls = new AtomicInteger();
            final var builder = PdfPartitioner.builder().strategy(strategy).ocrProvider(
                    (dev.docweave.partition.PdfWordOcrProvider) (image, kind) -> {
                        ocrCalls.incrementAndGet();
                        return provider.recognizeWords(image, kind);
                    });
            if (strategy == PdfStrategy.HI_RES) {
                builder.tableLayoutProvider(image -> {
                    layoutCalls.incrementAndGet();
                    return List.of();
                });
            }
            final var result = DocWeave.create(PartitionerRegistry.of(List.of(builder.build()))).partition(source);
            final var text = result.elements().stream().filter(element -> element.type() != ElementType.Image).toList();
            assertEquals(List.of("Invoice total 12345"), text.stream().map(element -> element.text()).toList());
            final boolean forced = strategy == PdfStrategy.HI_RES || strategy == PdfStrategy.OCR_ONLY;
            assertEquals(forced ? 1 : 0, ocrCalls.get());
            assertEquals(strategy == PdfStrategy.HI_RES ? 1 : 0, layoutCalls.get());
            assertEquals(forced, "ocr".equals(text.getFirst().metadata().get("pdf_text_method")));
            if (forced) {
                assertEquals("all_pages_v1", text.getFirst().metadata().get("pdf_ocr_selection"));
            }
            observations.setProperty(strategy + ".ocr_calls", Integer.toString(ocrCalls.get()));
            observations.setProperty(strategy + ".layout_calls", Integer.toString(layoutCalls.get()));
            observations.setProperty(strategy + ".exact_owned_text_without_duplicates", "true");
        }
        observations.setProperty("java", Runtime.version().toString());
        observations.setProperty("executable_sha256", hash(Path.of(executable)));
        observations.setProperty("eng_sha256", hash(Path.of(data, "eng.traineddata")));
        try (final var output =
                        Files.newOutputStream(Path.of(report + ".strategies.xml"), StandardOpenOption.CREATE_NEW)) {
            observations.storeToXML(output, "Owned native-page preset recognition; not a corpus accuracy measurement");
        }
    }

    /**
     * Encodes independently known native text, so forced OCR must render rather than reuse its strings.
     * @return owned immutable PDF source
     * @throws Exception if PDF encoding fails
     */
    private static DocumentSource source() throws Exception {
        try (final var document = new PDDocument(); final var bytes = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(500, 180));
            document.addPage(page);
            try (final var content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 24);
                content.newLineAtOffset(30, 100);
                content.showText("Invoice total 12345");
                content.endText();
            }
            document.save(bytes);
            return DocumentSource.bytes(bytes.toByteArray(), "owned-native-preset.pdf");
        }
    }

    /**
     * Binds explicitly supplied local asset bytes without recording operational paths.
     * @param path original local executable or trained model
     * @return lowercase SHA-256 identity
     * @throws Exception if the asset cannot be read or hashed
     */
    private static String hash(final Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
