package dev.docweave.ocr;

import dev.docweave.partition.PdfOcrProvider;
import dev.docweave.partition.PdfWordOcrProvider;

import java.util.List;
import java.util.Objects;

/**
 * Explicit optional Tesseract 5 adapter. Uses one direct subprocess per admitted image, LSTM engine
 * and explicit call-local page/cell segmentation (automatic page mode by default). TSV words are joined with spaces
 * within each Tesseract line; confidence values are not exposed as calibrated extraction accuracy.
 * Instances are thread-safe. Separate instances have separate concurrency limits. No process starts
 * during construction and no ServiceLoader registration silently enables OCR.
 */
public interface TesseractOcr extends PdfOcrProvider {
    /**
     * Creates a reusable provider with a private process admission limit.
     * @param options executable, language and resource policies
     * @return hidden managed implementation
     * @throws NullPointerException if options is null
     */
    static TesseractOcr create(final TesseractOptions options) {
        return new DefaultTesseractOcr(Objects.requireNonNull(options, "options"));
    }

    /**
     * Creates a provider with explicit Tesseract5 internal binarization for page and cell calls.
     * Thresholding remains independent of segmentation. Borrowed pixels and geometry are unchanged;
     * all existing admission, deadline, temporary-file and output budgets remain authoritative.
     * No engine probe, model download or process starts during construction.
     * @param options trusted executable, language and resource policies
     * @param thresholding fixed engine default or explicit binarization algorithm
     * @return hidden thread-safe provider with its own admission limit
     * @throws NullPointerException if either argument is null
     */
    static TesseractOcr create(final TesseractOptions options, final TesseractThresholding thresholding) {
        return new DefaultTesseractOcr(
                Objects.requireNonNull(options, "options"), Objects.requireNonNull(thresholding, "thresholding"));
    }

    /**
     * Creates the same managed adapter exposed through its optional actual-word geometry capability.
     * Each detailed request runs one process and retains source word/line encounter order; ordinary
     * recognition keeps historical line-only allocation. No token positions are inferred from strings.
     * @param options trusted executable, language, segmentation and resource policy
     * @return hidden thread-safe word-capable provider with its own admission bound
     * @throws NullPointerException if options is null
     */
    static PdfWordOcrProvider createWithWords(final TesseractOptions options) {
        return new DefaultTesseractOcr(Objects.requireNonNull(options, "options"));
    }

    /**
     * Creates the word-capable provider with the same explicit internal binarization policy.
     * One detailed request still executes one managed process and retains actual TSV word positions.
     * @param options trusted executable, language, segmentation and resource policies
     * @param thresholding fixed engine default or explicit binarization algorithm
     * @return hidden word-capable provider with independent admission state
     * @throws NullPointerException if either argument is null
     */
    static PdfWordOcrProvider createWithWords(
            final TesseractOptions options, final TesseractThresholding thresholding) {
        return new DefaultTesseractOcr(
                Objects.requireNonNull(options, "options"), Objects.requireNonNull(thresholding, "thresholding"));
    }

    /**
     * Creates a provider with an explicit resolution for every supplied page or cell raster.
     * The caller must use the actual render or scan resolution, including any earlier resampling.
     * This passes trusted numeric {@code --dpi} metadata to Tesseract without resizing pixels.
     * Adaptive threshold windows and recognition may change; improved accuracy is not guaranteed.
     * Use separate providers when raster resolutions differ. Existing factories omit this override.
     * @param options trusted executable, language, segmentation and resource policies
     * @param thresholding fixed engine binarization policy
     * @param sourceDpi actual supplied raster resolution,70 through2400 dots per inch
     * @return hidden thread-safe provider retaining existing resource and coordinate contracts
     * @throws NullPointerException if options or thresholding is null
     * @throws IllegalArgumentException if sourceDpi is outside70 through2400
     */
    static TesseractOcr create(
            final TesseractOptions options, final TesseractThresholding thresholding, final int sourceDpi) {
        return resolved(options, thresholding, sourceDpi);
    }

    /**
     * Creates the same explicit-resolution provider through its actual-word geometry interface.
     * One request still runs one process; returned boxes remain in the unchanged borrowed raster.
     * @param options trusted executable, language, segmentation and resource policies
     * @param thresholding fixed engine binarization policy
     * @param sourceDpi actual supplied raster resolution,70 through2400 dots per inch
     * @return hidden thread-safe word-capable provider with its own admission bound
     * @throws NullPointerException if options or thresholding is null
     * @throws IllegalArgumentException if sourceDpi is outside70 through2400
     * @see #create(TesseractOptions, TesseractThresholding, int)
     */
    static PdfWordOcrProvider createWithWords(
            final TesseractOptions options, final TesseractThresholding thresholding, final int sourceDpi) {
        return resolved(options, thresholding, sourceDpi);
    }

    /**
     * Creates an explicit-resolution provider with opt-in same-size page preprocessing.
     * LOCAL_WHITEPOINT can improve uneven illumination but worsen other pages and erase legitimate
     * gray/color information. It is never automatic and does not apply to TABLE_CELL requests.
     * Preparation shares existing pixel, admission and total timeout limits; source pixels and output
     * coordinate frames remain intact. Construction does not execute or download an engine/model.
     * @param options trusted executable, language and resource policies
     * @param thresholding independent internal engine thresholding
     * @param sourceDpi actual raster resolution,70 through2400
     * @param preprocessing explicit page preparation, NONE for historical behavior
     * @return hidden thread-safe managed provider
     * @throws NullPointerException if a policy is null
     * @throws IllegalArgumentException if sourceDpi is unsupported
     */
    static TesseractOcr create(final TesseractOptions options, final TesseractThresholding thresholding,
            final int sourceDpi, final TesseractPreprocessing preprocessing) {
        return prepared(options, thresholding, sourceDpi, preprocessing);
    }

    /**
     * Exposes the same explicit page preparation through actual OCR word geometry.
     * Word/line boxes stay in the original raster frame; one request still runs one managed process.
     * TABLE_CELL preparation and all historical factories remain unchanged.
     * @param options trusted executable, language and resource policies
     * @param thresholding independent engine thresholding
     * @param sourceDpi actual raster resolution,70 through2400
     * @param preprocessing explicit page preparation
     * @return hidden thread-safe actual-word provider
     * @throws NullPointerException if a policy is null
     * @throws IllegalArgumentException if sourceDpi is unsupported
     * @see #create(TesseractOptions, TesseractThresholding, int, TesseractPreprocessing)
     */
    static PdfWordOcrProvider createWithWords(final TesseractOptions options, final TesseractThresholding thresholding,
            final int sourceDpi, final TesseractPreprocessing preprocessing) {
        return prepared(options, thresholding, sourceDpi, preprocessing);
    }

    /**
     * Validates immutable explicit preparation/resolution before constructing a lazy provider.
     * @param options trusted resource policy
     * @param thresholding explicit engine thresholding
     * @param sourceDpi supported actual resolution
     * @param preprocessing explicit same-size page preparation
     * @return hidden managed implementation with one shared admission limit
     * @throws NullPointerException if a policy is null
     * @throws IllegalArgumentException if resolution is unsupported
     */
    private static DefaultTesseractOcr prepared(final TesseractOptions options,
            final TesseractThresholding thresholding, final int sourceDpi, final TesseractPreprocessing preprocessing) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(thresholding, "thresholding");
        Objects.requireNonNull(preprocessing, "preprocessing");
        if (sourceDpi < 70 || sourceDpi > 2400) {
            throw new IllegalArgumentException("Tesseract source DPI must be between70 and2400");
        }
        return new DefaultTesseractOcr(
                options, thresholding, sourceDpi, preprocessing, List.of(options.executable().toString()));
    }

    /**
     * Validates caller resolution before creating a lazy managed provider.
     * @param options nonnull resource policy
     * @param thresholding nonnull engine policy
     * @param sourceDpi explicit supported resolution; zero is reserved for historical factories
     * @return hidden provider without probing an executable or model
     * @throws NullPointerException if a policy is null
     * @throws IllegalArgumentException if resolution is unsupported
     */
    private static DefaultTesseractOcr resolved(
            final TesseractOptions options, final TesseractThresholding thresholding, final int sourceDpi) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(thresholding, "thresholding");
        if (sourceDpi < 70 || sourceDpi > 2400) {
            throw new IllegalArgumentException("Tesseract source DPI must be between70 and2400");
        }
        return new DefaultTesseractOcr(options, thresholding, sourceDpi, List.of(options.executable().toString()));
    }
}
