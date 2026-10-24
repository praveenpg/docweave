package io.docweave.ocr;

import io.docweave.partition.PartitionException;
import io.docweave.partition.PdfWordOcrProvider;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Reusable external OCR strategy; only admission state is shared between calls. */
final class DefaultTesseractOcr implements TesseractOcr, PdfWordOcrProvider {
    /** Immutable caller policy. */
    private final TesseractOptions options;
    /** Immutable explicit engine binarization, independent of each call's segmentation. */
    private final TesseractThresholding thresholding;
    /** Explicit raster resolution, or zero to preserve engine resolution handling. */
    private final int sourceDpi;
    /** Explicit page-only raster preparation; historical factories retain NONE. */
    private final TesseractPreprocessing preprocessing;
    /** Trusted executable prefix; production contains only the configured executable. */
    private final List<String> commandPrefix;
    /** Fair per-instance admission bound covering encoding, execution and parsing. */
    private final Semaphore admission;

    /**
     * Creates an explicit provider without probing the executable.
     * @param options validated policy
     */
    DefaultTesseractOcr(final TesseractOptions options) {
        this(options, TesseractThresholding.ENGINE_DEFAULT);
    }

    /**
     * Captures explicit engine binarization without probing or launching the executable.
     * @param options validated caller policy
     * @param thresholding trusted fixed internal image policy
     */
    DefaultTesseractOcr(final TesseractOptions options, final TesseractThresholding thresholding) {
        this(options, thresholding, List.of(options.executable().toString()));
    }

    /**
     * Supplies an internal executable prefix for owned Java subprocess controls.
     * @param options validated policy
     * @param commandPrefix trusted executable and fixed leading arguments
     */
    DefaultTesseractOcr(final TesseractOptions options, final List<String> commandPrefix) {
        this(options, TesseractThresholding.ENGINE_DEFAULT, commandPrefix);
    }

    /**
     * Supplies the same explicit image policy and trusted executable prefix for owned subprocess controls.
     * @param options validated caller policy
     * @param thresholding fixed binarization choice
     * @param commandPrefix trusted executable and leading arguments
     */
    DefaultTesseractOcr(final TesseractOptions options, final TesseractThresholding thresholding,
            final List<String> commandPrefix) {
        this(options, thresholding, 0, commandPrefix);
    }

    /**
     * Captures resolution without resampling borrowed pixels or changing their coordinate frame.
     * @param options validated caller policy
     * @param thresholding fixed binarization choice
     * @param sourceDpi zero for historical behavior, otherwise70 through2400 dots per inch
     * @param commandPrefix trusted executable and leading arguments
     * @throws IllegalArgumentException if resolution is outside the supported range
     */
    DefaultTesseractOcr(final TesseractOptions options, final TesseractThresholding thresholding, final int sourceDpi,
            final List<String> commandPrefix) {
        this(options, thresholding, sourceDpi, TesseractPreprocessing.NONE, commandPrefix);
    }

    /**
     * Captures independent explicit page preparation without engine execution or extra admission state.
     * @param options validated caller resource policy
     * @param thresholding fixed internal engine thresholding
     * @param sourceDpi zero for historical behavior, otherwise actual raster resolution
     * @param preprocessing nonnull page preparation; normalization requires explicit resolution
     * @param commandPrefix trusted executable and leading arguments
     * @throws IllegalArgumentException if resolution is unsupported or missing for normalization
     * @throws NullPointerException if a policy is null
     */
    DefaultTesseractOcr(final TesseractOptions options, final TesseractThresholding thresholding, final int sourceDpi,
            final TesseractPreprocessing preprocessing, final List<String> commandPrefix) {
        this.preprocessing = Objects.requireNonNull(preprocessing, "preprocessing");
        if (preprocessing != TesseractPreprocessing.NONE && sourceDpi == 0) {
            throw new IllegalArgumentException("Page preprocessing requires explicit source DPI");
        }
        if (sourceDpi != 0 && (sourceDpi < 70 || sourceDpi > 2400)) {
            throw new IllegalArgumentException("Tesseract source DPI must be between70 and2400");
        }
        this.sourceDpi = sourceDpi;
        this.thresholding = Objects.requireNonNull(thresholding, "thresholding");
        this.options = options;
        this.commandPrefix = List.copyOf(commandPrefix);
        admission = new Semaphore(options.maxConcurrentProcesses(), true);
    }

    /**
     * Runs one bounded OCR call, without changing or retaining the borrowed image.
     * @param image nonnull caller-owned raster
     * @return immutable recognized lines
     * @throws IOException if admission, encoding, process execution or TSV parsing fails
     */
    @Override
    public List<Line> recognize(final BufferedImage image) throws IOException {
        return recognize(image, InputKind.PAGE);
    }

    /**
     * Selects a call-local cell segmentation argument while retaining shared admission and resource bounds.
     * @param image nonnull caller-owned raster
     * @param kind nonnull page or padded cell purpose
     * @return immutable recognized lines in the borrowed raster frame
     * @throws IOException if admission, encoding, execution or parsing fails
     */
    @Override
    public List<Line> recognize(final BufferedImage image, final InputKind kind) throws IOException {
        return recognizeResult(image, kind, false).lines();
    }

    @Override
    public List<WordLine> recognizeWords(final BufferedImage image, final InputKind kind) throws IOException {
        return recognizeResult(image, kind, true).words();
    }

    /**
     * Runs one process under shared admission, choosing only the requested immutable output view.
     * @param image borrowed raster
     * @param kind explicit segmentation purpose
     * @param retainWords whether actual token geometry is requested
     * @return parsed result without a second recognition call
     * @throws IOException if any existing recognition/resource/deadline contract fails
     */
    private Recognized recognizeResult(final BufferedImage image, final InputKind kind, final boolean retainWords)
            throws IOException {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(image, "image");
        final var deadline = new OcrDeadline(options.timeout());
        deadline.remaining();
        if ((long) image.getWidth() * image.getHeight() > options.maxImagePixels()) {
            throw new PartitionException(PartitionException.Code.OCR_LIMIT, "Tesseract input exceeds maxImagePixels");
        }
        try {
            if (!admission.tryAcquire(deadline.remaining(), TimeUnit.NANOSECONDS)) {
                throw new PartitionException(PartitionException.Code.OCR_TIMEOUT, "Tesseract admission timed out");
            }
        } catch (final InterruptedException failure) {
            Thread.currentThread().interrupt();
            final var interrupted = new InterruptedIOException("Tesseract admission interrupted");
            interrupted.initCause(failure);
            throw interrupted;
        }
        try (final var page = new TesseractPageImage(image,
                     kind == InputKind.PAGE && preprocessing == TesseractPreprocessing.LOCAL_WHITEPOINT,
                     options.maxImagePixels(), sourceDpi, deadline);
                final var prepared = new TesseractCellImage(page.image(),
                        kind == InputKind.TABLE_CELL
                                && options.cellBorders() == TesseractOptions.CellBorders.REMOVE_EDGE_RULES,
                        deadline);
                final var input = new TesseractImage()) {
            final var file = input.encode(prepared.image(), options.maxPngBytes(), deadline);
            final var command = new ArrayList<>(commandPrefix);
            command.add(file.toAbsolutePath().toString());
            command.add("stdout");
            command.addAll(List.of("-l", String.join("+", options.languages()), "--oem", "1", "--psm",
                    kind == InputKind.TABLE_CELL ? options.cellSegmentation().argument()
                                                 : options.pageSegmentation().argument()));
            if (options.tessdataDirectory() != null) {
                command.addAll(List.of("--tessdata-dir", options.tessdataDirectory().toAbsolutePath().toString()));
            }
            if (thresholding.argument() != null) {
                command.addAll(List.of("-c", "thresholding_method=" + thresholding.argument()));
            }
            if (sourceDpi != 0) {
                command.addAll(List.of("--dpi", Integer.toString(sourceDpi)));
            }
            command.addAll(List.of("-c", "tessedit_create_tsv=1"));
            final var bytes = TesseractProcess.run(command, options.maxOutputBytes(), deadline);
            // Only page calls may consult source pixels for a printed column rule; cells keep text-only grouping.
            final var source = kind == InputKind.PAGE ? image : null;
            if (retainWords) {
                final var words =
                        TesseractTsv.parseWords(bytes, image.getWidth(), image.getHeight(), options, deadline, source);
                return new Recognized(List.of(), words);
            }
            return new Recognized(
                    TesseractTsv.parse(bytes, image.getWidth(), image.getHeight(), options, deadline, source),
                    List.of());
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
            throw new PartitionException(PartitionException.Code.OCR_FAILURE, "Tesseract recognition failed", failure);
        } finally {
            admission.release();
        }
    }

    /**
     * Per-call selected output view; lists are immutable and never retain parser/process objects.
     * @param lines line-only output, empty for a detailed request
     * @param words detailed output, empty for a line-only request
     */
    private record Recognized(List<Line> lines, List<WordLine> words) {}
}
