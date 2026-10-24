package io.docweave.ocr;

import io.docweave.partition.PartitionException;
import io.docweave.partition.PdfOcrProvider;
import io.docweave.partition.PdfWordOcrProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Actual JVM subprocess controls for resolution metadata, borrowed geometry and managed failures. */
class TesseractResolutionTest {
    /** Creates an independent JUnit control instance. */
    TesseractResolutionTest() {
    }

    /**
     * Explicit resolution is independent of concurrent page/cell requests and thresholding.
     * @param directory lifecycle marker directory
     * @throws Exception if a subprocess, assertion deadline or temporary-file inspection fails
     */
    @Test
    void resolutionArgumentsPreservePixelsWordsAndHistoricalDefaults(final @TempDir Path directory) throws Exception {
        final var image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        image.setRGB(17, 19, 0xff325678);
        final int[] original = image.getRGB(0, 0, 100, 100, null, 0, 100);
        for (final int dpi : new int[] {0, 70, 300, 2400}) {
            for (final var thresholding : TesseractThresholding.values()) {
                final var provider = provider("dpi", directory, options(), thresholding, dpi);
                final String expected = "dpi-" + (dpi == 0 ? "default" : Integer.toString(dpi)) + "-threshold-"
                                        + (thresholding.argument() == null ? "default" : thresholding.argument());
                try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    final var page = executor.submit(() -> provider.recognize(image, PdfOcrProvider.InputKind.PAGE));
                    final var cell =
                            executor.submit(() -> provider.recognizeWords(image, PdfOcrProvider.InputKind.TABLE_CELL));
                    final var lines = page.get(5, TimeUnit.SECONDS);
                    final var words = cell.get(5, TimeUnit.SECONDS);
                    assertEquals(expected, lines.getLast().text());
                    assertEquals(lines, words.stream().map(PdfWordOcrProvider.WordLine::line).toList());
                    assertEquals(List.of("Hello", "world"),
                            words.getFirst().words().stream().map(PdfWordOcrProvider.Word::text).toList());
                    assertEquals(10, words.getFirst().words().getFirst().left());
                    assertEquals(55, lines.getFirst().right());
                }
                assertArrayEquals(original, image.getRGB(0, 0, 100, 100, null, 0, 100));
                assertCleanup(directory);
            }
        }
    }

    /**
     * Invalid resolution fails before execution; public factories stay lazy and private.
     */
    @Test
    void factoriesRejectUnsupportedResolutionWithoutLaunchingProcesses() {
        for (final int dpi : new int[] {Integer.MIN_VALUE, -1, 0, 69, 2401, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class,
                    () -> TesseractOcr.create(options(), TesseractThresholding.SAUVOLA, dpi));
            assertThrows(IllegalArgumentException.class,
                    () -> TesseractOcr.createWithWords(options(), TesseractThresholding.SAUVOLA, dpi));
        }
        assertThrows(NullPointerException.class, () -> TesseractOcr.create(null, TesseractThresholding.OTSU, 300));
        assertThrows(NullPointerException.class, () -> TesseractOcr.create(options(), null, 300));
        assertThrows(
                NullPointerException.class, () -> TesseractOcr.createWithWords(null, TesseractThresholding.OTSU, 300));
        assertThrows(NullPointerException.class, () -> TesseractOcr.createWithWords(options(), null, 300));
        for (final int dpi : new int[] {70, 300, 2400}) {
            assertFalse(java.lang.reflect.Modifier.isPublic(
                    TesseractOcr.create(options(), TesseractThresholding.SAUVOLA, dpi).getClass().getModifiers()));
            assertFalse(java.lang.reflect.Modifier.isPublic(
                    TesseractOcr.createWithWords(options(), TesseractThresholding.SAUVOLA, dpi)
                            .getClass()
                            .getModifiers()));
        }
    }

    /**
     * Resolution cannot bypass process failure, timeout or output-character limits.
     * @param directory lifecycle marker directory
     * @throws Exception if temporary-file inspection fails
     */
    @Test
    void resolutionRetainsTypedFailuresLimitsAndCleanup(final @TempDir Path directory) throws Exception {
        final var image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        assertEquals(PartitionException.Code.OCR_FAILURE,
                assertThrows(PartitionException.class,
                        ()
                                -> provider("exit", directory, options(), TesseractThresholding.SAUVOLA, 300)
                                        .recognize(image))
                        .code());
        final var timeout = new TesseractOptions(Path.of("missing-owned-test-engine"), null, List.of("eng", "deu"),
                Duration.ofMillis(700), 1, 100_000, 100_000, 100_000, 10, 100);
        assertEquals(PartitionException.Code.OCR_TIMEOUT,
                assertThrows(PartitionException.class,
                        () -> provider("hang", directory, timeout, TesseractThresholding.SAUVOLA, 300).recognize(image))
                        .code());
        final var limited = new TesseractOptions(Path.of("missing-owned-test-engine"), null, List.of("eng", "deu"),
                Duration.ofSeconds(10), 1, 100_000, 100_000, 100_000, 10, 5);
        assertEquals(PartitionException.Code.OCR_LIMIT,
                assertThrows(PartitionException.class,
                        () -> provider("dpi", directory, limited, TesseractThresholding.SAUVOLA, 300).recognize(image))
                        .code());
        assertCleanup(directory);
    }

    /**
     * Supplies an unavailable executable so factory construction cannot accidentally probe an engine.
     * @return bounded owned-test policy
     */
    private static TesseractOptions options() {
        return new TesseractOptions(Path.of("missing-owned-test-engine"), null, List.of("eng", "deu"),
                Duration.ofSeconds(10), 2, 100_000, 100_000, 100_000, 10, 100);
    }

    /**
     * Substitutes an owned Java child while retaining the complete production recognition path.
     * @param mode child behavior
     * @param directory temporary lifecycle markers
     * @param options bounded request policy
     * @param thresholding fixed engine policy
     * @param dpi explicit resolution or zero for historical behavior
     * @return hidden managed word-capable provider
     */
    private static DefaultTesseractOcr provider(final String mode, final Path directory, final TesseractOptions options,
            final TesseractThresholding thresholding, final int dpi) {
        final var executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        return new DefaultTesseractOcr(options, thresholding, dpi,
                List.of(executable.toString(), "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"),
                        OcrChildProcess.class.getName(), mode, directory.toString()));
    }

    /**
     * Checks the actual encoded PNG and its private parent directory were removed after each call.
     * @param directory child lifecycle markers
     * @throws Exception if a marker cannot be read
     */
    private static void assertCleanup(final Path directory) throws Exception {
        try (final var paths = Files.list(directory)) {
            for (final var marker : paths.filter(path -> path.getFileName().toString().startsWith("call-")).toList()) {
                final var input = Path.of(Files.readString(marker));
                assertFalse(Files.exists(input));
                assertFalse(Files.exists(input.getParent()));
            }
        }
    }
}
