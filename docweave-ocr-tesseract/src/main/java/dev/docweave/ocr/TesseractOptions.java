package dev.docweave.ocr;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Immutable external Tesseract 5 policy. No executable or model is downloaded by this library.
 * Limits apply per recognition call except maxConcurrentProcesses, which is shared by one provider instance.
 * The executable and model directory are trusted caller configuration, never document-supplied values.
 * @param executable executable path or bare command resolved through the operating-system search path
 * @param tessdataDirectory optional model/config directory; null retains Tesseract's installed defaults
 * @param languages nonempty ordered model identifiers, such as eng or script/Latin
 * @param timeout maximum queue and process-I/O waiting time; encoding/parser work checks the same deadline
 *         cooperatively
 * @param maxConcurrentProcesses maximum admitted calls, including their image encoding and TSV parsing
 * @param maxImagePixels maximum borrowed input pixels before encoding
 * @param maxPngBytes maximum temporary PNG bytes
 * @param maxOutputBytes maximum stdout TSV bytes; stderr has a separate fixed 64 KiB ceiling
 * @param maxLines maximum recognized lines per call
 * @param maxCharacters maximum joined output UTF-16 units per call
 * @param cellSegmentation explicit table-cell segmentation, independent of page layout
 * @param pageSegmentation explicit page layout assumption; automatic mode remains the default
 * @param cellBorders explicit edge-rule removal for TABLE_CELL inputs; RETAIN preserves source pixels
 * @param lineSplitting explicit output-line grouping; TESSERACT_LINES preserves the engine's line identities
 */
public record TesseractOptions(Path executable, Path tessdataDirectory, List<String> languages, Duration timeout,
        int maxConcurrentProcesses, long maxImagePixels, long maxPngBytes, int maxOutputBytes, int maxLines,
        int maxCharacters, CellSegmentation cellSegmentation, PageSegmentation pageSegmentation,
        CellBorders cellBorders, LineSplitting lineSplitting) {
    /**
     * Defensively copies languages and validates bounded positive policies.
     * @throws NullPointerException if required values are null
     * @throws IllegalArgumentException if configuration is invalid; timeout must not exceed one day
     */
    public TesseractOptions {
        Objects.requireNonNull(executable, "executable");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(cellSegmentation, "cellSegmentation");
        Objects.requireNonNull(pageSegmentation, "pageSegmentation");
        Objects.requireNonNull(cellBorders, "cellBorders");
        Objects.requireNonNull(lineSplitting, "lineSplitting");
        languages = List.copyOf(languages);
        if (executable.toString().isBlank() || languages.isEmpty() || timeout.isNegative() || timeout.isZero()
                || timeout.compareTo(Duration.ofDays(1)) > 0 || maxConcurrentProcesses < 1 || maxImagePixels < 1
                || maxPngBytes < 1 || maxOutputBytes < 1 || maxLines < 1 || maxCharacters < 1) {
            throw new IllegalArgumentException("Invalid Tesseract options");
        }
        for (final var language : languages) {
            if (!language.matches("[A-Za-z0-9_]+(?:/[A-Za-z0-9_]+)?")) {
                throw new IllegalArgumentException("Invalid Tesseract language identifier");
            }
        }
    }

    /**
     * Preserves the cell-border constructor with the engine's own line grouping.
     * @param executable trusted executable path or bare command
     * @param tessdataDirectory optional installed model directory
     * @param languages ordered installed model identifiers
     * @param timeout total bounded recognition deadline
     * @param maxConcurrentProcesses provider-wide admitted calls
     * @param maxImagePixels borrowed raster pixel ceiling
     * @param maxPngBytes temporary PNG byte ceiling
     * @param maxOutputBytes TSV byte ceiling
     * @param maxLines returned line ceiling
     * @param maxCharacters returned UTF-16 unit ceiling
     * @param cellSegmentation explicit TABLE_CELL segmentation
     * @param pageSegmentation explicit PAGE segmentation
     * @param cellBorders explicit TABLE_CELL edge policy
     */
    public TesseractOptions(final Path executable, final Path tessdataDirectory, final List<String> languages,
            final Duration timeout, final int maxConcurrentProcesses, final long maxImagePixels, final long maxPngBytes,
            final int maxOutputBytes, final int maxLines, final int maxCharacters,
            final CellSegmentation cellSegmentation, final PageSegmentation pageSegmentation,
            final CellBorders cellBorders) {
        this(executable, tessdataDirectory, languages, timeout, maxConcurrentProcesses, maxImagePixels, maxPngBytes,
                maxOutputBytes, maxLines, maxCharacters, cellSegmentation, pageSegmentation, cellBorders,
                LineSplitting.TESSERACT_LINES);
    }

    /**
     * Preserves the page/cell segmentation constructor without changing input pixels.
     * @param executable trusted executable path or bare command
     * @param tessdataDirectory optional installed model directory
     * @param languages ordered installed model identifiers
     * @param timeout total bounded recognition deadline
     * @param maxConcurrentProcesses provider-wide admitted calls
     * @param maxImagePixels borrowed raster pixel ceiling
     * @param maxPngBytes temporary PNG byte ceiling
     * @param maxOutputBytes TSV byte ceiling
     * @param maxLines returned line ceiling
     * @param maxCharacters returned UTF-16 unit ceiling
     * @param cellSegmentation explicit TABLE_CELL segmentation
     * @param pageSegmentation explicit PAGE segmentation
     */
    public TesseractOptions(final Path executable, final Path tessdataDirectory, final List<String> languages,
            final Duration timeout, final int maxConcurrentProcesses, final long maxImagePixels, final long maxPngBytes,
            final int maxOutputBytes, final int maxLines, final int maxCharacters,
            final CellSegmentation cellSegmentation, final PageSegmentation pageSegmentation) {
        this(executable, tessdataDirectory, languages, timeout, maxConcurrentProcesses, maxImagePixels, maxPngBytes,
                maxOutputBytes, maxLines, maxCharacters, cellSegmentation, pageSegmentation, CellBorders.RETAIN);
    }

    /**
     * Preserves the original cell-policy constructor with automatic page segmentation.
     * @param executable trusted executable path or bare command
     * @param tessdataDirectory optional trusted model directory
     * @param languages ordered model identifiers
     * @param timeout bounded positive deadline
     * @param maxConcurrentProcesses provider-wide admitted call count
     * @param maxImagePixels borrowed input pixel ceiling
     * @param maxPngBytes encoded PNG byte ceiling
     * @param maxOutputBytes returned TSV byte ceiling
     * @param maxLines recognized line ceiling
     * @param maxCharacters returned UTF-16 ceiling
     * @param cellSegmentation explicit cell policy
     */
    public TesseractOptions(final Path executable, final Path tessdataDirectory, final List<String> languages,
            final Duration timeout, final int maxConcurrentProcesses, final long maxImagePixels, final long maxPngBytes,
            final int maxOutputBytes, final int maxLines, final int maxCharacters,
            final CellSegmentation cellSegmentation) {
        this(executable, tessdataDirectory, languages, timeout, maxConcurrentProcesses, maxImagePixels, maxPngBytes,
                maxOutputBytes, maxLines, maxCharacters, cellSegmentation, PageSegmentation.AUTO);
    }

    /**
     * Preserves the original constructor and automatic segmentation for all input kinds.
     * @param executable trusted executable path or bare command
     * @param tessdataDirectory optional trusted model directory
     * @param languages ordered model identifiers, defensively copied
     * @param timeout positive deadline, at most one day
     * @param maxConcurrentProcesses positive provider-wide admitted call count
     * @param maxImagePixels positive input pixel ceiling
     * @param maxPngBytes positive encoded PNG byte ceiling
     * @param maxOutputBytes positive TSV byte ceiling
     * @param maxLines positive returned line ceiling
     * @param maxCharacters positive returned UTF-16 unit ceiling
     */
    public TesseractOptions(final Path executable, final Path tessdataDirectory, final List<String> languages,
            final Duration timeout, final int maxConcurrentProcesses, final long maxImagePixels, final long maxPngBytes,
            final int maxOutputBytes, final int maxLines, final int maxCharacters) {
        this(executable, tessdataDirectory, languages, timeout, maxConcurrentProcesses, maxImagePixels, maxPngBytes,
                maxOutputBytes, maxLines, maxCharacters, CellSegmentation.AUTO);
    }

    /**
     * Copies this policy with an explicit cell layout assumption; page segmentation stays unchanged.
     * No mode guarantees improved recognition, and SINGLE_LINE may misrecognize blank or multiline cells.
     * @param segmentation nonnull caller-selected cell mode
     * @return new immutable policy retaining all other values
     * @throws NullPointerException if segmentation is null
     */
    public TesseractOptions withCellSegmentation(final CellSegmentation segmentation) {
        return new TesseractOptions(executable, tessdataDirectory, languages, timeout, maxConcurrentProcesses,
                maxImagePixels, maxPngBytes, maxOutputBytes, maxLines, maxCharacters, segmentation, pageSegmentation,
                cellBorders, lineSplitting);
    }

    /**
     * Copies this policy with an explicit page layout assumption, retaining the cell policy and budgets.
     * SINGLE_BLOCK assumes one uniform text block; SPARSE_TEXT may return arbitrary reading order.
     * These trusted modes do not estimate layout or guarantee improved recognition.
     * @param segmentation nonnull caller-selected PAGE mode
     * @return new immutable policy retaining all other values
     * @throws NullPointerException if segmentation is null
     */
    public TesseractOptions withPageSegmentation(final PageSegmentation segmentation) {
        return new TesseractOptions(executable, tessdataDirectory, languages, timeout, maxConcurrentProcesses,
                maxImagePixels, maxPngBytes, maxOutputBytes, maxLines, maxCharacters, cellSegmentation, segmentation,
                cellBorders, lineSplitting);
    }

    /**
     * Copies this policy with explicit cell-edge preprocessing; PAGE inputs remain unchanged.
     * Removal can erase meaningful edge rules/underlines and does not guarantee better OCR.
     * @param borders nonnull cell-edge pixel policy
     * @return validated immutable copy retaining segmentation and budgets
     */
    public TesseractOptions withCellBorders(final CellBorders borders) {
        return new TesseractOptions(executable, tessdataDirectory, languages, timeout, maxConcurrentProcesses,
                maxImagePixels, maxPngBytes, maxOutputBytes, maxLines, maxCharacters, cellSegmentation,
                pageSegmentation, borders, lineSplitting);
    }

    /**
     * Copies this policy with explicit output-line grouping; pixels, recognition and word text are unchanged.
     * WIDE_GAPS can separate a genuine tab-stop or table row into several lines; it does not reorder words.
     * @param splitting nonnull line grouping policy
     * @return validated immutable copy retaining every other value
     * @throws NullPointerException if splitting is null
     */
    public TesseractOptions withLineSplitting(final LineSplitting splitting) {
        return new TesseractOptions(executable, tessdataDirectory, languages, timeout, maxConcurrentProcesses,
                maxImagePixels, maxPngBytes, maxOutputBytes, maxLines, maxCharacters, cellSegmentation,
                pageSegmentation, cellBorders, splitting);
    }

    /** Explicit grouping of recognized words into returned lines; neither value changes recognition. */
    public enum LineSplitting {
        /** Returns one line per Tesseract block/paragraph/line identity, as before. */
        TESSERACT_LINES,
        /**
         * Also starts a new returned line where the horizontal gap between consecutive recognized words of one
         * Tesseract line exceeds three times that line's tallest word box. A large gap may be a column gutter
         * or intentional spacing within a logical row; the rule does not establish semantic line membership.
         * Words keep their recognition order and text; the extra lines still count against maxLines.
         */
        WIDE_GAPS
    }

    /** Explicit isolated-cell preprocessing; no image is modified in place and geometry stays unchanged. */
    public enum CellBorders {
        /** Preserves historical source pixels for every call. */ RETAIN,
        /** Whitens long dark rows/columns within24pixels of cell edges on cells at least64x64pixels. */
        REMOVE_EDGE_RULES
    }

    /** Explicit page layout assumptions; selection never uses document-supplied command arguments. */
    public enum PageSegmentation {
        /** Fully automatic layout without orientation/script detection, PSM3; historical default. */ AUTO("3"),
        /** One uniform block of text, PSM6; may be unsuitable for multiple columns or figures. */ SINGLE_BLOCK("6"),
        /** Finds sparse text without a logical reading-order guarantee, PSM11. */ SPARSE_TEXT("11"),
        /** Assumes one text column with variable text sizes, PSM4; unsuitable for unknown multicolumn layouts. */
        SINGLE_COLUMN("4");

        /** Fixed trusted process argument. */
        private final String argument;

        /**
         * Captures a fixed engine mode identifier.
         * @param argument trusted numeric PSM value
         */
        PageSegmentation(final String argument) {
            this.argument = argument;
        }

        /**
         * Returns the fixed engine argument.
         * @return trusted numeric mode
         */
        String argument() {
            return argument;
        }
    }

    /** Explicit Tesseract layout assumptions for isolated cells; never inferred from recognized text. */
    public enum CellSegmentation {
        /** Fully automatic page segmentation without orientation/script detection, PSM 3; original default. */
        AUTO("3"),
        /** One uniform text block, potentially multiline, PSM 6. */
        SINGLE_BLOCK("6"),
        /** Exactly one text line, PSM 7; unsuitable for unknown multiline/blank cells. */
        SINGLE_LINE("7");

        /** Fixed trusted command argument, never supplied by document content. */
        private final String argument;

        /**
         * Stores the engine's fixed mode identifier.
         * @param argument trusted numeric PSM argument
         */
        CellSegmentation(final String argument) {
            this.argument = argument;
        }

        /**
         * Returns the fixed process argument.
         * @return trusted numeric PSM argument
         */
        String argument() {
            return argument;
        }
    }

    /**
     * Returns English LSTM OCR with a 60-second deadline, two admitted calls, 16 million pixels,
     * 32 MiB PNG, 4 MiB TSV, 10,000 lines and one million UTF-16 units.
     * @return validated immutable defaults; executable/model availability is checked only when used
     */
    public static TesseractOptions defaults() {
        return new TesseractOptions(Path.of("tesseract"), null, List.of("eng"), Duration.ofSeconds(60), 2, 16_000_000,
                32L * 1024 * 1024, 4 * 1024 * 1024, 10_000, 1_000_000);
    }
}
