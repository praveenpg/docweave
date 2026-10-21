package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.core.ElementType;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Owned native, scanned, mixed and blank pages exercise real preset routing and ownership. */
class PdfStrategyTest {
    /** Creates independent strategy controls. */
    PdfStrategyTest() {
    }

    /**
     * Native-only routing never invokes a supplied engine or model and preserves historical output.
     * @throws Exception if owned PDF encoding or partitioning fails
     */
    @Test
    void fastNeverInvokesProvidersAndMatchesNativeDefaults() throws Exception {
        final var source = fixture(0);
        final var fast = PdfPartitioner.builder()
                                 .strategy(PdfStrategy.FAST)
                                 .ocrProvider(image -> { throw new AssertionError("FAST invoked OCR"); })
                                 .tableLayoutProvider(image -> { throw new AssertionError("FAST invoked layout"); })
                                 .build();
        assertSameExtraction(processor(PdfPartitioner.create(PdfOptions.defaults())).partition(source),
                processor(fast).partition(source));
    }

    /**
     * AUTO without optional dependencies retains native extraction and diagnostics.
     * @throws Exception if owned PDF encoding or partitioning fails
     */
    @Test
    void autoWithoutProviderMatchesExistingDefaults() throws Exception {
        final var source = fixture(0);
        assertSameExtraction(processor(PdfPartitioner.create(PdfOptions.defaults())).partition(source),
                processor(PdfPartitioner.builder().build()).partition(source));
    }

    /**
     * AUTO selects scan/blank pages, preserving native and mixed-page text instead of duplicating it.
     * @throws Exception if owned PDF encoding or partitioning fails
     */
    @Test
    void autoRecognizesOnlyTextlessPages() throws Exception {
        final var calls = new AtomicInteger();
        final var pdf = PdfPartitioner.builder()
                                .ocrOptions(PdfOcrOptions.defaults().withSelection(PdfOcrOptions.Selection.ALL_PAGES))
                                .ocrProvider(image -> {
                                    assertEquals(150, image.getWidth());
                                    assertEquals(300, image.getHeight());
                                    calls.incrementAndGet();
                                    return List.of(line());
                                })
                                .build();
        final var result = processor(pdf).partition(fixture(0));
        assertEquals(2, calls.get());
        assertEquals(2, result.elements().stream().filter(value -> value.text().equals("NATIVE")).count());
        assertEquals(2, result.elements().stream().filter(value -> value.text().equals("OCR")).count());
        assertEquals(2, result.elements().stream().filter(value -> value.type() == ElementType.Image).count());
    }

    /**
     * Forced full-page OCR replaces searchable native text on every page and retains source images once.
     * @throws Exception if owned PDF encoding or partitioning fails
     */
    @Test
    void ocrOnlyRecognizesEveryPageWithoutNativeDuplicates() throws Exception {
        final var calls = new AtomicInteger();
        final var pdf = PdfPartitioner.builder()
                                .strategy(PdfStrategy.OCR_ONLY)
                                .ocrProvider(image -> {
                                    calls.incrementAndGet();
                                    return List.of(line());
                                })
                                .build();
        final var result = processor(pdf).partition(fixture(0));
        assertEquals(4, calls.get());
        assertEquals(4, result.elements().stream().filter(value -> value.text().equals("OCR")).count());
        assertTrue(result.elements()
                        .stream()
                        .filter(value -> value.text().equals("OCR"))
                        .allMatch(value -> "all_pages_v1".equals(value.metadata().get("pdf_ocr_selection"))));
        assertTrue(result.elements().stream().noneMatch(value -> value.text().contains("NATIVE")));
        assertEquals(2, result.elements().stream().filter(value -> value.type() == ElementType.Image).count());
        assertTrue(result.warnings().stream().anyMatch(value -> value.message().contains("Every page uses OCR")));
        assertTrue(result.warnings().stream().noneMatch(value -> value.message().contains("No OCR")));
    }

    /**
     * High-resolution routing recognizes native pages too, using actual words and complete table geometry.
     * @throws Exception if owned PDF encoding or partitioning fails
     */
    @Test
    void hiResUsesWordLayoutOnEveryPageAt300Dpi() throws Exception {
        final var calls = new AtomicInteger();
        final var layouts = new AtomicInteger();
        final var result = processor(highResolution(null, calls, layouts, 300, 600)).partition(fixture(0));
        assertEquals(4, calls.get());
        assertEquals(4, layouts.get());
        assertEquals(4, result.elements().stream().filter(value -> value.type() == ElementType.Table).count());
        assertTrue(result.elements()
                        .stream()
                        .filter(value -> value.type() == ElementType.Table)
                        .allMatch(value -> value.text().equals("OCR")));
        assertEquals(2, result.elements().stream().filter(value -> value.type() == ElementType.Image).count());
        assertTrue(result.elements().stream().noneMatch(value -> value.text().contains("NATIVE")));
    }

    /**
     * Explicit OCR options override preset resolution without changing full-page ownership.
     * @throws Exception if owned PDF encoding or partitioning fails
     */
    @Test
    void explicitResolutionOverridesHiResDefault() throws Exception {
        final var calls = new AtomicInteger();
        final var layouts = new AtomicInteger();
        processor(highResolution(PdfOcrOptions.defaults().withDpi(72), calls, layouts, 72, 144)).partition(fixture(0));
        assertEquals(4, calls.get());
        assertEquals(4, layouts.get());
    }

    /** Missing engines, incompatible capabilities and orphan configuration fail before any extraction. */
    @Test
    void missingOrIncompatibleProvidersFailAtBuild() {
        final PdfOcrProvider lines = image -> List.of(line());
        final PdfWordOcrProvider words = (image, kind) -> words();
        assertThrows(
                IllegalArgumentException.class, () -> PdfPartitioner.builder().strategy(PdfStrategy.OCR_ONLY).build());
        assertThrows(
                IllegalArgumentException.class, () -> PdfPartitioner.builder().strategy(PdfStrategy.HI_RES).build());
        assertThrows(IllegalArgumentException.class,
                ()
                        -> PdfPartitioner.builder()
                                .strategy(PdfStrategy.HI_RES)
                                .ocrProvider(lines)
                                .tableLayoutProvider(image -> List.of(table()))
                                .build());
        assertThrows(IllegalArgumentException.class,
                () -> PdfPartitioner.builder().strategy(PdfStrategy.HI_RES).ocrProvider(words).build());
        assertThrows(IllegalArgumentException.class,
                ()
                        -> PdfPartitioner.builder()
                                .ocrProvider(lines)
                                .tableLayoutProvider(image -> List.of(table()))
                                .build());
        assertThrows(IllegalArgumentException.class,
                () -> PdfPartitioner.builder().ocrOptions(PdfOcrOptions.defaults()).build());
        assertThrows(IllegalArgumentException.class,
                ()
                        -> PdfPartitioner.builder()
                                .ocrProvider(lines)
                                .ocrOptions(PdfOcrOptions.defaults().withTableDetection(
                                        PdfOcrOptions.TableDetection.ALIGNED_WORDS))
                                .build());
        assertThrows(NullPointerException.class, () -> PdfPartitioner.builder().strategy(null));
        assertThrows(NullPointerException.class, () -> PdfPartitioner.builder().options(null));
        assertThrows(NullPointerException.class, () -> PdfPartitioner.builder().ocrOptions(null));
        assertThrows(NullPointerException.class, () -> PdfPartitioner.builder().ocrProvider(null));
        assertThrows(NullPointerException.class, () -> PdfPartitioner.builder().tableLayoutProvider(null));
    }

    /**
     * Built services snapshot routing and dependencies, so later builder changes cannot alter them.
     * @throws Exception if owned PDF encoding or partitioning fails
     */
    @Test
    void builtServicesSnapshotMutableBuilderState() throws Exception {
        final var builder =
                PdfPartitioner.builder().strategy(PdfStrategy.OCR_ONLY).ocrProvider(image -> List.of(line()));
        final var ocr = builder.build();
        final var nativeOnly = builder.strategy(PdfStrategy.FAST).build();
        assertTrue(processor(ocr)
                        .partition(fixture(0))
                        .elements()
                        .stream()
                        .noneMatch(value -> value.text().equals("NATIVE")));
        assertEquals(2, processor(nativeOnly)
                                .partition(fixture(0))
                                .elements()
                                .stream()
                                .filter(value -> value.text().equals("NATIVE"))
                                .count());
    }

    /**
     * Pixel preflight remains active before engine invocation even when native text is present.
     * @throws Exception if owned PDF encoding fails
     */
    @Test
    void forcedOcrHonorsRenderBudgetBeforeProviders() throws Exception {
        final var calls = new AtomicInteger();
        final var pdf = PdfPartitioner.builder()
                                .strategy(PdfStrategy.OCR_ONLY)
                                .ocrOptions(new PdfOcrOptions(72, 1, 10, 100))
                                .ocrProvider(image -> {
                                    calls.incrementAndGet();
                                    return List.of(line());
                                })
                                .build();
        assertEquals(PartitionException.Code.OCR_LIMIT,
                assertThrows(PartitionException.class, () -> processor(pdf).partition(fixture(0))).code());
        assertEquals(0, calls.get());
    }

    /**
     * Forced OCR keeps aggregate raw-output limits rather than silently falling back to native text.
     * @throws Exception if owned PDF encoding fails
     */
    @Test
    void forcedOcrHonorsRawOutputBudget() throws Exception {
        final var pdf = PdfPartitioner.builder()
                                .strategy(PdfStrategy.OCR_ONLY)
                                .ocrOptions(new PdfOcrOptions(72, 100_000, 1, 100))
                                .ocrProvider(image -> List.of(line(), line()))
                                .build();
        assertEquals(PartitionException.Code.OCR_LIMIT,
                assertThrows(PartitionException.class, () -> processor(pdf).partition(fixture(0))).code());
    }

    /**
     * Provider causes and interruption stay observable on native pages selected for forced OCR.
     * @throws Exception if owned PDF encoding fails
     */
    @Test
    void failuresAndCancellationNeverBecomeNativeFallback() throws Exception {
        final var cause = new IOException("owned provider failure");
        final var failing =
                PdfPartitioner.builder().strategy(PdfStrategy.OCR_ONLY).ocrProvider(image -> { throw cause; }).build();
        final var failure = assertThrows(PartitionException.class, () -> processor(failing).partition(fixture(0)));
        assertEquals(PartitionException.Code.OCR_FAILURE, failure.code());
        assertSame(cause, failure.getCause());
        final var cancelling = PdfPartitioner.builder()
                                       .strategy(PdfStrategy.OCR_ONLY)
                                       .ocrProvider(image -> {
                                           Thread.currentThread().interrupt();
                                           return List.of(line());
                                       })
                                       .build();
        try {
            assertThrows(InterruptedIOException.class, () -> processor(cancelling).partition(fixture(0)));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    /** Resolution copies preserve every other immutable policy and validate the existing range. */
    @Test
    void resolutionCopiesPreserveBudgetsAndPolicies() {
        final var original = new PdfOcrOptions(72, 12345, 12, 345, PdfOcrOptions.Selection.IMAGE_REGIONS, 7,
                PdfOcrOptions.TableDetection.RULED, 23, PdfOcrOptions.ReadingOrder.GEOMETRIC, 4);
        assertEquals(original, original.withDpi(300).withDpi(72));
        assertEquals(300, original.withDpi(300).dpi());
        assertThrows(IllegalArgumentException.class, () -> original.withDpi(35));
        assertThrows(IllegalArgumentException.class, () -> original.withDpi(601));
    }

    /**
     * An empty recognition result never restores a searchable layer or fabricates table text.
     * @throws Exception if owned PDF encoding or partitioning fails
     */
    @Test
    void emptyForcedRecognitionRetainsOnlySourceImages() throws Exception {
        final var pdf = PdfPartitioner.builder().strategy(PdfStrategy.OCR_ONLY).ocrProvider(image -> List.of()).build();
        final var result = processor(pdf).partition(fixture(0));
        assertEquals(2, result.elements().size());
        assertTrue(result.elements().stream().allMatch(value -> value.type() == ElementType.Image));
    }

    /**
     * Layout failure on an otherwise readable native page remains an explicit provider failure.
     * @throws Exception if owned PDF encoding fails
     */
    @Test
    void hiResDoesNotFallBackWhenLayoutFails() throws Exception {
        final var cause = new IOException("owned layout failure");
        final var pdf = PdfPartitioner.builder()
                                .strategy(PdfStrategy.HI_RES)
                                .ocrProvider((PdfWordOcrProvider) (image, kind) -> words())
                                .tableLayoutProvider(image -> { throw cause; })
                                .build();
        final var failure = assertThrows(PartitionException.class, () -> processor(pdf).partition(fixture(0)));
        assertEquals(PartitionException.Code.OCR_FAILURE, failure.code());
        assertSame(cause, failure.getCause());
    }

    /**
     * Declared page rotation controls the actual full-page OCR raster, including native pages.
     * @throws Exception if owned PDF encoding or partitioning fails
     */
    @Test
    void forcedOcrRespectsDeclaredQuarterTurns() throws Exception {
        final var calls = new AtomicInteger();
        final var pdf = PdfPartitioner.builder()
                                .strategy(PdfStrategy.OCR_ONLY)
                                .ocrProvider(image -> {
                                    assertEquals(300, image.getWidth());
                                    assertEquals(150, image.getHeight());
                                    calls.incrementAndGet();
                                    return List.of(line());
                                })
                                .build();
        processor(pdf).partition(fixture(90));
        assertEquals(4, calls.get());
    }

    /**
     * One built OCR-only strategy remains reusable across virtual-thread document calls.
     * @throws Exception if owned PDF encoding, extraction or task joining fails
     */
    @Test
    void builtStrategySupportsConcurrentDocuments() throws Exception {
        final var pdf =
                PdfPartitioner.builder().strategy(PdfStrategy.OCR_ONLY).ocrProvider(image -> List.of(line())).build();
        final var processor = processor(pdf);
        final var source = fixture(0);
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var tasks = java.util.stream.IntStream.range(0, 4)
                                      .mapToObj(index -> executor.submit(() -> processor.partition(source)))
                                      .toList();
            final var expected = tasks.getFirst().get();
            for (final var task : tasks) {
                assertSameExtraction(expected, task.get());
            }
        }
    }

    /**
     * Compares every deterministic output field; elapsed time measures separate calls and must differ freely.
     * @param expected baseline extraction
     * @param actual independently executed extraction
     */
    private static void assertSameExtraction(final PartitionResult expected, final PartitionResult actual) {
        assertEquals(expected.elements(), actual.elements());
        assertEquals(expected.warnings(), actual.warnings());
        assertEquals(expected.format(), actual.format());
        assertEquals(expected.encoding(), actual.encoding());
        assertEquals(expected.inputBytes(), actual.inputBytes());
    }

    /**
     * Builds the same word/layout contract at either default or explicitly overridden raster dimensions.
     * @param options optional OCR policy
     * @param calls PAGE recognition counter
     * @param layouts table geometry counter
     * @param width expected actual raster width
     * @param height expected actual raster height
     * @return reusable high-resolution preset
     */
    private static PdfPartitioner highResolution(final PdfOcrOptions options, final AtomicInteger calls,
            final AtomicInteger layouts, final int width, final int height) {
        final var builder = PdfPartitioner.builder()
                                    .strategy(PdfStrategy.HI_RES)
                                    .ocrProvider((PdfWordOcrProvider) (image, kind) -> {
                                        assertEquals(PdfOcrProvider.InputKind.PAGE, kind);
                                        assertEquals(width, image.getWidth());
                                        assertEquals(height, image.getHeight());
                                        calls.incrementAndGet();
                                        return words();
                                    })
                                    .tableLayoutProvider(image -> {
                                        assertEquals(width, image.getWidth());
                                        assertEquals(height, image.getHeight());
                                        layouts.incrementAndGet();
                                        return List.of(table());
                                    });
        return (options == null ? builder : builder.ocrOptions(options)).build();
    }

    /**
     * Supplies one independently declared source-pixel table cell enclosing the owned word.
     * @return complete single-anchor topology
     */
    private static PdfTableLayoutProvider.Table table() {
        final var box = new PdfTableLayoutProvider.Box(1, 1, 20, 20);
        return new PdfTableLayoutProvider.Table(
                1, 1, box, List.of(new PdfTableLayoutProvider.Anchor(0, 0, 1, 1, box)), "owned-preset");
    }

    /**
     * Supplies actual owned word geometry, without deriving positions from a line string.
     * @return one word and its matching source line
     */
    private static List<PdfWordOcrProvider.WordLine> words() {
        return List.of(
                new PdfWordOcrProvider.WordLine(line(), List.of(new PdfWordOcrProvider.Word("OCR", 5, 5, 10, 10))));
    }

    /**
     * Supplies a fixed small line valid in all tested raster frames.
     * @return owned provider text and pixel bounds
     */
    private static PdfOcrProvider.Line line() {
        return new PdfOcrProvider.Line("OCR", 5, 5, 10, 10);
    }

    /**
     * Installs only the selected PDF strategy without changing service discovery.
     * @param pdf built strategy
     * @return document processor
     */
    private static DocWeave processor(final PdfPartitioner pdf) {
        return DocWeave.create(PartitionerRegistry.of(List.of(pdf)));
    }

    /**
     * Encodes native, scanned, mixed and blank pages without invoking OCR or using external documents.
     * @param rotation explicit declared page quarter turn
     * @return immutable in-memory owned PDF source
     * @throws Exception if PDF encoding fails
     */
    private static DocumentSource fixture(final int rotation) throws Exception {
        try (final var document = new PDDocument(); final var bytes = new ByteArrayOutputStream()) {
            final var raster = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
            try {
                final var image = LosslessFactory.createFromImage(document, raster);
                for (int index = 0; index < 4; index++) {
                    final var page = new PDPage(new PDRectangle(72, 144));
                    page.setRotation(rotation);
                    document.addPage(page);
                    try (final var content = new PDPageContentStream(document, page)) {
                        if (index == 0 || index == 2) {
                            content.beginText();
                            content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 8);
                            content.newLineAtOffset(5, 100);
                            content.showText("NATIVE");
                            content.endText();
                        }
                        if (index == 1 || index == 2) {
                            content.drawImage(image, 5, 5, 30, 30);
                        }
                    }
                }
                document.save(bytes);
                return DocumentSource.bytes(bytes.toByteArray(), "presets.pdf");
            } finally {
                raster.flush();
            }
        }
    }
}
