package io.docweave.partition;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Explicit optional strategy supplying complete table topology in a borrowed raster's pixel frame.
 * Implementations may use learned models or caller-owned geometry and must be thread-safe, bounded
 * and cancellation-aware. They never modify, flush or retain the supplied raster. Construction and
 * discovery do not imply model download/execution. Geometry is distinct from OCR: text ownership
 * uses actual PdfWordOcrProvider words in the same source frame.
 */
@FunctionalInterface
public interface PdfTableLayoutProvider {
    /**
     * Prefers closed observed raster-rule grids while retaining disjoint tables from another provider.
     * Both sets are completely validated before overlap selection. A secondary table whose interior
     * overlaps a ruled table is discarded whole; its raw anchors still consume the combined budget.
     * Secondary failures are propagated even when ruled tables exist. No model is loaded or run here.
     * Ruled anchors describe interiors inset five pixels from observed rule centerlines, matching
     * the supported rule-thickness bound; this excludes rule pixels from subsequent cell OCR.
     * The returned provider never modifies, releases or retains its input raster. It does not own or
     * close {@code secondary}; applications retain responsibility for managed/native provider lifetime.
     * Concurrent use requires a thread-safe secondary provider. No new total native deadline is imposed;
     * secondary call limits remain its own, with interruption checked before and after the call.
     * @param secondary explicit fallback or model strategy, also used for disjoint tables on ruled pages
     * @param limits per-table dimensions and cell policy
     * @param options borrowed pixel and combined raw-anchor budgets; other OCR selections are not consumed here
     * @return hidden call-confined geometry composition with immutable configuration
     * @throws NullPointerException if an argument is null
     */
    static PdfTableLayoutProvider preferRuled(
            final PdfTableLayoutProvider secondary, final TableLimits limits, final PdfOcrOptions options) {
        return preferRuled(secondary, limits, options, RuledProfile.STANDARD);
    }

    /**
     * Prefers observed closed grids using an explicit fixed pixel-geometry profile.
     * The same validation, raw-anchor/work budgets, five-pixel cell inset and non-owning secondary
     * lifecycle as the three-argument factory apply. Compact geometry admits smaller cells and
     * shorter observed runs; it does not repair missing strokes, recognize colored rules, adjust DPI
     * or establish OCR accuracy. Source-specific profile selection is never performed automatically.
     * @param secondary caller-owned fallback or model strategy
     * @param limits structured dimension and prospective-slot ceilings
     * @param options borrowed-pixel and combined raw-anchor policy
     * @param profile explicit immutable rule geometry
     * @return hidden thread-safe composition when the secondary is thread-safe
     * @throws NullPointerException if an argument is null
     */
    static PdfTableLayoutProvider preferRuled(final PdfTableLayoutProvider secondary, final TableLimits limits,
            final PdfOcrOptions options, final RuledProfile profile) {
        return new RuledFirstPdfTableLayout(secondary, limits, options, profile);
    }

    /**
     * Creates an explicit observed-rule-only strategy without a model, OCR engine or native runtime.
     * Construction reads no pixels. Each call borrows the source unchanged and returns complete
     * closed-grid interiors under the same validation and budgets as rule-preferred composition.
     * Cells are inset five pixels from rule centerlines even for compact geometry; small interiors
     * may contain insufficient text for useful OCR. No text recognition or confidence is implied.
     * @param limits structured dimensions and prospective-slot policy
     * @param options borrowed-pixel and raw-anchor ceilings; OCR selections are not consumed here
     * @param profile explicit immutable pixel geometry, independent of render DPI
     * @return hidden bounded rule-only provider
     * @throws NullPointerException if an argument is null
     */
    static PdfTableLayoutProvider ruled(
            final TableLimits limits, final PdfOcrOptions options, final RuledProfile profile) {
        return preferRuled(image -> List.of(), limits, options, profile);
    }

    /** Fixed geometry values for closed dark axis-aligned raster grids; never an automatic classifier. */
    enum RuledProfile {
        /** Historical64-pixel runs and minimum32x24-pixel atomic cells. */
        STANDARD(64, 32, 24),
        /** Explicit32-pixel runs and minimum16x12-pixel atomic cells, preserving short-edge evidence. */
        COMPACT(32, 16, 12);

        /** Minimum uninterrupted observed dark-pixel run. */
        private final int minimumRunPixels;
        /** Minimum atomic column interval in borrowed-raster pixels. */
        private final int minimumColumnPixels;
        /** Minimum atomic row interval in borrowed-raster pixels. */
        private final int minimumRowPixels;

        /**
         * Captures fixed immutable geometry without exposing mutable algorithm state.
         * @param run minimum uninterrupted run
         * @param column minimum column interval
         * @param row minimum row interval
         */
        RuledProfile(final int run, final int column, final int row) {
            minimumRunPixels = run;
            minimumColumnPixels = column;
            minimumRowPixels = row;
        }

        /**
         * Returns the minimum uninterrupted dark run in either orientation.
         * @return fixed positive pixel count
         */
        public int minimumRunPixels() {
            return minimumRunPixels;
        }

        /**
         * Returns the minimum interval between observed adjacent vertical centerlines.
         * @return fixed positive pixel count
         */
        public int minimumColumnPixels() {
            return minimumColumnPixels;
        }

        /**
         * Returns the minimum interval between observed adjacent horizontal centerlines.
         * @return fixed positive pixel count
         */
        public int minimumRowPixels() {
            return minimumRowPixels;
        }
    }

    /**
     * Locates disjoint complete rectangular-cell tables, including empty and merged anchors.
     * Returning an empty list preserves ordinary OCR. Unsupported/ambiguous predictions should be
     * rejected by the provider; invalid claimed topology aborts extraction as OCR_FAILURE.
     * Integration separately enforces raster containment, complete nonoverlapping slot ownership,
     * table/cell limits and mixed-page exclusions before claiming any words.
     * @param image borrowed nonnull rendered page or native-masked RGB raster
     * @return stable immutable nonnull disjoint tables; no source text
     * @throws IOException if inference, resource limits or cancellation prevent output
     */
    List<Table> locate(BufferedImage image) throws IOException;

    /**
     * Positive finite top-left source-pixel rectangle, without text or a coordinate transform.
     * @param left minimum horizontal edge
     * @param top minimum vertical edge
     * @param right maximum horizontal edge
     * @param bottom maximum vertical edge
     */
    record Box(double left, double top, double right, double bottom) {
        /**
         * Validates finite positive geometry; raster containment is checked by integration.
         * @throws IllegalArgumentException if bounds are negative, nonfinite or degenerate
         */
        public Box {
            if (!Double.isFinite(left) || !Double.isFinite(top) || !Double.isFinite(right) || !Double.isFinite(bottom)
                    || left < 0 || top < 0 || right <= left || bottom <= top) {
                throw new IllegalArgumentException("Invalid PDF layout rectangle");
            }
        }
    }

    /**
     * One rectangular anchor occupying all slots in its row/column spans exactly once.
     * @param row zero-based first row
     * @param column zero-based first column
     * @param rowSpan positive occupied row count
     * @param columnSpan positive occupied column count
     * @param bounds complete provider-defined anchor envelope in source pixels
     */
    record Anchor(int row, int column, int rowSpan, int columnSpan, Box bounds) {
        /**
         * Validates scalar shape; complete table membership is checked under integration budgets.
         * @throws NullPointerException if bounds is null
         * @throws IllegalArgumentException if an index/span is invalid
         */
        public Anchor {
            Objects.requireNonNull(bounds, "bounds");
            if (row < 0 || column < 0 || rowSpan < 1 || columnSpan < 1) {
                throw new IllegalArgumentException("Invalid PDF layout anchor");
            }
        }
    }

    /**
     * Complete provider table with explicit topology and bounded provenance identifier.
     * Anchors may arrive in any order; extraction emits them in row-major anchor order. All atomic
     * slots must be covered once and distinct anchor rectangles must have disjoint interiors.
     * Model scores are not represented as calibrated extraction probabilities.
     * Header evidence never changes atomic rows, anchor spans or HTML cell types. A zero count means
     * absent or unknown evidence, not proof that a table has no headers. Providers remain responsible
     * for their declaration; model roles are not calibrated semantic confidence.
     * @param rows positive atomic row count
     * @param columns positive atomic column count
     * @param bounds full enclosing source-pixel table envelope
     * @param anchors complete rectangular anchors, including empty positions; defensively copied
     * @param provenance nonblank policy/model identifier, at most256 UTF-16 units, without filesystem paths
     * @param headerRows provider-declared contiguous top header-row prefix, zero through rows
     */
    record Table(int rows, int columns, Box bounds, List<Anchor> anchors, String provenance, int headerRows) {
        /**
         * Preserves the original constructor with no declared header evidence.
         * @param rows positive atomic row count
         * @param columns positive atomic column count
         * @param bounds enclosing source-pixel table rectangle
         * @param anchors complete rectangular anchors; defensively copied
         * @param provenance bounded nonblank provider identifier
         * @throws NullPointerException if a component or anchor is null
         * @throws IllegalArgumentException if shape or provenance is invalid
         */
        public Table(final int rows, final int columns, final Box bounds, final List<Anchor> anchors,
                final String provenance) {
            this(rows, columns, bounds, anchors, provenance, 0);
        }

        /**
         * Snapshots a claimed layout without allocating a potentially unbounded slot matrix.
         * Full topology validation happens under the caller's structured-table and work budgets.
         * @throws NullPointerException if a component or anchor is null
         * @throws IllegalArgumentException if shape, header prefix or provenance is invalid
         */
        public Table {
            Objects.requireNonNull(bounds, "bounds");
            anchors = List.copyOf(anchors);
            Objects.requireNonNull(provenance, "provenance");
            if (rows < 1 || columns < 1 || headerRows < 0 || headerRows > rows || provenance.isBlank()
                    || provenance.length() > 256) {
                throw new IllegalArgumentException("Invalid PDF table layout");
            }
        }
    }
}
