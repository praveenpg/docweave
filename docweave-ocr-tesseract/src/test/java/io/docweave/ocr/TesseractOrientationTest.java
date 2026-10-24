package io.docweave.ocr;

import io.docweave.partition.PartitionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class TesseractOrientationTest {
    @TempDir Path temporary;

    private static String output(final int orientation, final int correction, final String score) {
        return "Page number: 0\nOrientation in degrees: " + orientation + "\nRotate: " + correction
                + "\nOrientation confidence: " + score + "\nScript: Latin\nScript confidence: 3.5\n";
    }

    @Test
    void strictProtocolAcceptsQuarterTurnsAndAbstainsBelowExplicitRawScoreGate() throws Exception {
        final var options = TesseractOrientationOptions.defaults();
        for (final int angle : List.of(0, 90, 180, 270)) {
            final var result =
                    TesseractOsd.parse(output(angle, (360 - angle) % 360, "18.0").getBytes(StandardCharsets.UTF_8),
                            options, new OcrDeadline(Duration.ofSeconds(1)));
            assertTrue(result.accepted());
            assertEquals((360 - angle) % 360, result.clockwiseDegrees());
            assertEquals(18, result.rawScore());
        }
        final var low = TesseractOsd.parse(output(90, 270, "4.0").getBytes(StandardCharsets.UTF_8), options,
                new OcrDeadline(Duration.ofSeconds(1)));
        assertFalse(low.accepted());
        assertEquals(0, low.clockwiseDegrees());
        assertEquals(4, low.rawScore());
    }

    @Test
    void malformedDuplicateContradictoryAndNonfiniteOutputFailsWithoutLeakingContent() {
        final var valid = output(90, 270, "18.0");
        for (final var text : List.of(output(45, 315, "18.0"), output(90, 90, "18.0"), output(90, 270, "NaN"),
                     output(90, 270, "Infinity"), output(90, 270, "-1"), valid + "Rotate: 270\n",
                     valid.replace("Page number: 0", "Page number: 1"),
                     valid.replace("Script confidence: 3.5", "Script confidence: NaN"),
                     valid.replace("Script: Latin\n", ""), valid + "private document content\n")) {
            final var failure = assertThrows(PartitionException.class,
                    ()
                            -> TesseractOsd.parse(text.getBytes(StandardCharsets.UTF_8),
                                    TesseractOrientationOptions.defaults(), new OcrDeadline(Duration.ofSeconds(1))));
            assertEquals(PartitionException.Code.OCR_FAILURE, failure.code());
            assertFalse(failure.getMessage().contains("private document content"));
        }
        assertThrows(IOException.class,
                ()
                        -> TesseractOsd.parse(new byte[] {(byte) 0xc0, (byte) 0xaf},
                                TesseractOrientationOptions.defaults(), new OcrDeadline(Duration.ofSeconds(1))));
    }

    @Test
    void managedCommandsDecisionsAndTemporaryInputCleanupUseRealOwnedSubprocesses() throws Exception {
        final var image = image();
        image.setRGB(0, 0, 0xff123456);
        final var accepted = provider("osd", options()).estimate(image);
        assertTrue(accepted.accepted());
        assertEquals(90, accepted.clockwiseDegrees());
        assertFalse(provider("osd-low", options()).estimate(image).accepted());
        assertFalse(provider("osd-too-few", options()).estimate(image).accepted());
        assertEquals(0xff123456, image.getRGB(0, 0));
        assertCleaned();
        assertTrue(TesseractOrientation.class.isInterface());
        assertFalse(
                java.lang.reflect.Modifier.isPublic(TesseractOrientation.create(options()).getClass().getModifiers()));
    }

    @Test
    void processAndPipeFailuresStayTypedAndPrivate() {
        for (final var mode : List.of("exit", "malformed", "badutf8")) {
            final var failure =
                    assertThrows(PartitionException.class, () -> provider(mode, options()).estimate(image()));
            assertEquals(PartitionException.Code.OCR_FAILURE, failure.code());
            assertFalse(failure.getMessage().contains("private diagnostics"));
        }
        for (final var mode : List.of("floodout", "flooderr")) {
            assertEquals(PartitionException.Code.OCR_LIMIT,
                    assertThrows(PartitionException.class, () -> provider(mode, options()).estimate(image())).code());
        }
    }

    @Test
    void timeoutAndActiveInterruptionReleaseAdmissionAndRemoveEncodedInput() throws Exception {
        assertEquals(PartitionException.Code.OCR_TIMEOUT,
                assertThrows(PartitionException.class,
                        () -> provider("hang", options().withTimeout(Duration.ofMillis(150))).estimate(image()))
                        .code());
        // A separate directory gives the owned first-hang child an unambiguous first admitted call.
        temporary = Files.createDirectory(temporary.resolve("interrupt"));
        final var estimator = provider("osd-first-hang", options());
        final var outcome = new AtomicReference<Throwable>();
        final var preserved = new AtomicBoolean();
        final var caller = Thread.ofVirtual().start(() -> {
            try {
                estimator.estimate(image());
            } catch (final Throwable failure) {
                outcome.set(failure);
                preserved.set(Thread.currentThread().isInterrupted());
            }
        });
        final long until = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!hasMarker() && System.nanoTime() < until) {
            Thread.sleep(10);
        }
        assertTrue(hasMarker(), "Owned child must publish completed input path before interruption");
        caller.interrupt();
        assertTrue(caller.join(Duration.ofSeconds(3)));
        assertInstanceOf(InterruptedIOException.class, outcome.get());
        assertTrue(preserved.get());
        assertTrue(estimator.estimate(image()).accepted());
        assertCleaned();
    }

    @Test
    void preentryCancellationAndRasterCeilingDoNotLaunchProcesses() throws Exception {
        final var estimator = provider("osd", options());
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> estimator.estimate(image()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertFalse(hasMarker());
        final var base = options();
        final var capped = new TesseractOrientationOptions(base.executable(), base.tessdataDirectory(), base.timeout(),
                1, 1, base.maxPngBytes(), base.maxOutputBytes(), base.minimumOrientationScore());
        assertEquals(PartitionException.Code.OCR_LIMIT,
                assertThrows(PartitionException.class, () -> provider("osd", capped).estimate(image())).code());
        assertFalse(hasMarker());
    }

    private TesseractOrientation provider(final String mode, final TesseractOrientationOptions options) {
        final var executable = Path.of(System.getProperty("java.home"), "bin",
                                           System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
                                       .toString();
        return new DefaultTesseractOrientation(
                options, List.of(executable, "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"),
                                 OcrChildProcess.class.getName(), mode, temporary.toString()));
    }

    private static TesseractOrientationOptions options() {
        final var base = TesseractOrientationOptions.defaults();
        return new TesseractOrientationOptions(Path.of("unused-executable"), null, Duration.ofSeconds(10), 1,
                base.maxImagePixels(), base.maxPngBytes(), base.maxOutputBytes(), base.minimumOrientationScore());
    }

    private static BufferedImage image() {
        return new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
    }

    private boolean hasMarker() throws IOException {
        try (final var paths = Files.list(temporary)) {
            return paths.anyMatch(path -> path.getFileName().toString().startsWith("call-"));
        }
    }

    private void assertCleaned() throws IOException {
        try (final var paths = Files.list(temporary)) {
            for (final var marker : paths.filter(path -> path.getFileName().toString().startsWith("call-")).toList()) {
                assertFalse(Files.exists(Path.of(Files.readString(marker))), "Owned encoded PNG must be removed");
            }
        }
    }
}
