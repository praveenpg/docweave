package dev.docweave.ocr;

import dev.docweave.partition.PartitionException;
import dev.docweave.partition.PdfOcrProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Real JVM subprocess checks for page-only preparation, original geometry, factories and managed cleanup. */
class TesseractPreprocessingTest {
    /** Creates independent owned controls. */
    TesseractPreprocessingTest() {
    }

    /**
     * Actual encoded PNGs change only for explicit page requests; cell requests and source pixels stay unchanged.
     * @param directory private lifecycle markers
     * @throws Exception if process execution or cleanup inspection fails
     */
    @Test
    void preparationIsPageOnlyAndRetainsWordsGeometryAndCleanup(final @TempDir Path directory) throws Exception {
        final var image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        try {
            for (int y = 0; y < 100; y++) {
                for (int x = 0; x < 100; x++) {
                    image.setRGB(x, y, 0x808080);
                }
            }
            final var original = image.getRGB(0, 0, 100, 100, null, 0, 100);
            final var policy = options(Duration.ofSeconds(10));
            final var provider =
                    provider(policy, TesseractPreprocessing.LOCAL_WHITEPOINT, "page-whitepoint", directory);
            final var page = provider.recognizeWords(image, PdfOcrProvider.InputKind.PAGE);
            assertEquals("input-white", page.getLast().line().text());
            assertEquals(10, page.getFirst().words().getFirst().left());
            assertEquals(55, page.getFirst().line().right());
            assertEquals(
                    "input-original", provider.recognize(image, PdfOcrProvider.InputKind.TABLE_CELL).getLast().text());
            assertEquals("input-original", provider(policy, TesseractPreprocessing.NONE, "page-whitepoint", directory)
                                                   .recognize(image)
                                                   .getLast()
                                                   .text());
            assertArrayEquals(original, image.getRGB(0, 0, 100, 100, null, 0, 100));
            try (final var files = Files.list(directory)) {
                for (final var marker : files.toList()) {
                    final var input = Path.of(Files.readString(marker));
                    assertFalse(Files.exists(input));
                    assertFalse(Files.exists(input.getParent()));
                }
            }
        } finally {
            image.flush();
        }
    }

    /** Factory validation is lazy, implementations remain hidden, and normalization requires actual resolution. */
    @Test
    void explicitFactoriesRetainValidationAndHiddenImplementations() {
        final var options = options(Duration.ofSeconds(10));
        for (final int dpi : List.of(70, 300, 2400)) {
            assertFalse(java.lang.reflect.Modifier.isPublic(TesseractOcr
                            .create(options, TesseractThresholding.ENGINE_DEFAULT, dpi,
                                    TesseractPreprocessing.LOCAL_WHITEPOINT)
                            .getClass()
                            .getModifiers()));
            assertFalse(java.lang.reflect.Modifier.isPublic(TesseractOcr
                            .createWithWords(options, TesseractThresholding.ENGINE_DEFAULT, dpi,
                                    TesseractPreprocessing.LOCAL_WHITEPOINT)
                            .getClass()
                            .getModifiers()));
        }
        for (final int dpi : List.of(0, 69, 2401)) {
            assertThrows(IllegalArgumentException.class,
                    ()
                            -> TesseractOcr.create(options, TesseractThresholding.ENGINE_DEFAULT, dpi,
                                    TesseractPreprocessing.LOCAL_WHITEPOINT));
        }
        assertThrows(NullPointerException.class,
                () -> TesseractOcr.create(options, TesseractThresholding.ENGINE_DEFAULT, 300, null));
        assertThrows(IllegalArgumentException.class,
                ()
                        -> new DefaultTesseractOcr(options, TesseractThresholding.ENGINE_DEFAULT, 0,
                                TesseractPreprocessing.LOCAL_WHITEPOINT, List.of("missing")));
    }

    /**
     * Preparation cannot bypass typed failure or retain temporary images when process execution fails.
     * @param directory private lifecycle markers
     * @throws Exception if cleanup inspection fails
     */
    @Test
    void processFailureKeepsCleanupAndProviderReuse(final @TempDir Path directory) throws Exception {
        final var image = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        try {
            final var provider = provider(
                    options(Duration.ofSeconds(10)), TesseractPreprocessing.LOCAL_WHITEPOINT, "exit", directory);
            for (int call = 0; call < 2; call++) {
                assertEquals(PartitionException.Code.OCR_FAILURE,
                        assertThrows(PartitionException.class, () -> provider.recognize(image)).code());
            }
            try (final var files = Files.list(directory)) {
                for (final var marker : files.toList()) {
                    final var input = Path.of(Files.readString(marker));
                    assertFalse(Files.exists(input));
                    assertFalse(Files.exists(input.getParent()));
                }
            }
        } finally {
            image.flush();
        }
    }

    /**
     * Provides a nonexistent engine for lazy factory controls.
     * @param timeout bounded managed request duration
     * @return immutable owned resource policy
     */
    private static TesseractOptions options(final Duration timeout) {
        return new TesseractOptions(Path.of("missing-owned-engine"), null, List.of("eng", "deu"), timeout, 1, 100_000,
                100_000, 100_000, 10, 100);
    }

    /**
     * Executes a real owned JVM child through unchanged process, encoding and parsing infrastructure.
     * @param options resource limits
     * @param preprocessing explicit page policy
     * @param mode owned child behavior
     * @param directory lifecycle marker directory
     * @return hidden managed provider
     */
    private static DefaultTesseractOcr provider(final TesseractOptions options,
            final TesseractPreprocessing preprocessing, final String mode, final Path directory) {
        final var java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        return new DefaultTesseractOcr(options, TesseractThresholding.ENGINE_DEFAULT, 300, preprocessing,
                List.of(java.toString(), "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"),
                        OcrChildProcess.class.getName(), mode, directory.toString()));
    }
}
