package io.docweave.ocr;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Explicit installed Tesseract5 OSD process/score policy; no model or executable is downloaded.
 * Uses only osd.traineddata with legacy engine0 and orientation-only segmentation0.
 * @param executable trusted executable path or bare command resolved by the operating system
 * @param tessdataDirectory installed model directory, or null for installed defaults
 * @param timeout total per-estimate admission/encoding/process/parsing budget, at most one day
 * @param maxConcurrentProcesses maximum admitted estimates from1 through64, including encoding/parsing
 * @param maxImagePixels borrowed raster ceiling before encoding
 * @param maxPngBytes encoded temporary PNG byte ceiling
 * @param maxOutputBytes orientation stdout ceiling, from1 through65536; stderr has a fixed64KiB ceiling
 * @param minimumOrientationScore finite nonnegative raw OSD score threshold, not a probability
 */
public record TesseractOrientationOptions(Path executable, Path tessdataDirectory, Duration timeout,
        int maxConcurrentProcesses, long maxImagePixels, long maxPngBytes, int maxOutputBytes,
        double minimumOrientationScore) {
    /**
     * Validates immutable explicit process and decision policies without external execution.
     * @throws NullPointerException if executable or timeout is null
     * @throws IllegalArgumentException if a limit, deadline, path or score is invalid
     */
    public TesseractOrientationOptions {
        Objects.requireNonNull(executable, "executable");
        Objects.requireNonNull(timeout, "timeout");
        if (executable.toString().isBlank() || timeout.isNegative() || timeout.isZero()
                || timeout.compareTo(Duration.ofDays(1)) > 0 || maxConcurrentProcesses < 1
                || maxConcurrentProcesses > 64 || maxImagePixels < 1 || maxPngBytes < 1 || maxOutputBytes < 1
                || maxOutputBytes > 65536 || !Double.isFinite(minimumOrientationScore) || minimumOrientationScore < 0) {
            throw new IllegalArgumentException("Invalid Tesseract orientation options");
        }
    }

    /**
     * Returns30-second/single-call/16M-pixel/32MiB-PNG/16KiB-output defaults and raw score threshold15.
     * The threshold follows upstream's reasonably-confident guidance, not a calibrated accuracy guarantee.
     * @return explicit local configuration; no model probing or execution occurs
     */
    public static TesseractOrientationOptions defaults() {
        return new TesseractOrientationOptions(
                Path.of("tesseract"), null, Duration.ofSeconds(30), 1, 16_000_000, 32L * 1024 * 1024, 16 * 1024, 15);
    }

    /**
     * Copies this policy with an explicit raw-score decision threshold.
     * @param score finite nonnegative OSD score threshold
     * @return immutable validated copy
     */
    public TesseractOrientationOptions withMinimumScore(final double score) {
        return new TesseractOrientationOptions(executable, tessdataDirectory, timeout, maxConcurrentProcesses,
                maxImagePixels, maxPngBytes, maxOutputBytes, score);
    }

    /**
     * Copies the total per-estimate budget.
     * @param duration positive deadline no longer than one day
     * @return immutable validated copy
     */
    public TesseractOrientationOptions withTimeout(final Duration duration) {
        return new TesseractOrientationOptions(executable, tessdataDirectory, duration, maxConcurrentProcesses,
                maxImagePixels, maxPngBytes, maxOutputBytes, minimumOrientationScore);
    }
}
