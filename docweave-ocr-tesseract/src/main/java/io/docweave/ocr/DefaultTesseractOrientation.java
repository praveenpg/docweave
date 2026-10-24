package io.docweave.ocr;

import io.docweave.partition.PartitionException;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Managed explicit OSD subprocesses sharing bounded image, deadline and pipe lifecycle helpers. */
final class DefaultTesseractOrientation implements TesseractOrientation {
    /** Immutable installed-engine and decision policy. */
    private final TesseractOrientationOptions options;
    /** Trusted direct executable and optional fixed test/launcher arguments. */
    private final List<String> commandPrefix;
    /** Per-instance bound includes encoding, process waiting and parsing. */
    private final Semaphore admission;

    /**
     * Captures an explicit executable without launching it.
     * @param options validated installed-engine/resource/score policy
     */
    DefaultTesseractOrientation(final TesseractOrientationOptions options) {
        this(options, List.of(options.executable().toString()));
    }

    /**
     * Captures trusted fixed leading arguments for managed subprocess controls.
     * @param options immutable per-estimate limits
     * @param commandPrefix trusted executable/leading arguments, not document content
     */
    DefaultTesseractOrientation(final TesseractOrientationOptions options, final List<String> commandPrefix) {
        this.options = options;
        this.commandPrefix = List.copyOf(commandPrefix);
        admission = new Semaphore(options.maxConcurrentProcesses(), true);
    }

    /**
     * Runs orientation-only segmentation with installed osd.traineddata under one total deadline.
     * Known exit1/empty-stdout/too-few-characters abstains; all other nonzero exits fail explicitly.
     * @param image borrowed caller-owned raster, never modified or retained
     * @return explicit accepted or unchanged-page decision, with uncalibrated raw score
     * @throws IOException if encoding, engine/protocol, limits or cancellation fail
     */
    @Override
    public Estimate estimate(final BufferedImage image) throws IOException {
        Objects.requireNonNull(image, "image");
        final var deadline = new OcrDeadline(options.timeout());
        deadline.remaining();
        if ((long) image.getWidth() * image.getHeight() > options.maxImagePixels()) {
            throw new PartitionException(
                    PartitionException.Code.OCR_LIMIT, "Tesseract orientation exceeds maxImagePixels");
        }
        try {
            if (!admission.tryAcquire(deadline.remaining(), TimeUnit.NANOSECONDS)) {
                throw new PartitionException(
                        PartitionException.Code.OCR_TIMEOUT, "Tesseract orientation admission timed out");
            }
        } catch (final InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Tesseract orientation admission interrupted");
        }
        try (final var input = new TesseractImage()) {
            final var file = input.encode(image, options.maxPngBytes(), deadline);
            final var command = new ArrayList<>(commandPrefix);
            command.addAll(
                    List.of(file.toAbsolutePath().toString(), "stdout", "-l", "osd", "--oem", "0", "--psm", "0"));
            if (options.tessdataDirectory() != null) {
                command.addAll(List.of("--tessdata-dir", options.tessdataDirectory().toAbsolutePath().toString()));
            }
            final var result = TesseractProcess.observe(command, options.maxOutputBytes(), deadline);
            deadline.remaining();
            if (result.status() == 1 && result.stdout().length == 0
                    && new String(result.stderr(), StandardCharsets.UTF_8)
                            .contains("Too few characters. Skipping this page")) {
                return new Estimate(0, false, 0, TesseractOsd.provenance(options));
            }
            if (result.status() != 0) {
                throw new IOException("Tesseract orientation exited with status " + result.status());
            }
            return TesseractOsd.parse(result.stdout(), options, deadline);
        } catch (final PartitionException | InterruptedIOException failure) {
            throw failure;
        } catch (final IOException | RuntimeException failure) {
            for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof PartitionException typed) {
                    throw typed;
                }
                if (cause instanceof InterruptedIOException interrupted) {
                    throw interrupted;
                }
            }
            throw new PartitionException(PartitionException.Code.OCR_FAILURE, "Tesseract orientation failed", failure);
        } finally {
            admission.release();
        }
    }
}
