package dev.docweave.partition;

/**
 * Reusable native PDF service: positioned text, closed axis-aligned ruled tables with rectangular merged cells and
 * raster-image placements. Aligned numeric tables and native paragraph/two-column layout are explicit opt-in
 * heuristics. General borderless
 * layout, semantic headings, annotations and rendered reading order are not implemented. Register a configured instance
 * through {@link PartitionerRegistry.Builder#register(Partitioner)}. Implementations keep parser state confined to each
 * call and may be shared by virtual-thread batches. Quarter-turn pages/text share a crop-relative displayed
 * coordinate frame; arbitrary native baseline angles require explicit ORIENTED_LINES.
 */
public interface PdfPartitioner extends Partitioner {
    /**
     * Creates a convenience strategy builder, initially AUTO with native defaults and no providers.
     * Optional providers remain caller-owned; configuration performs no rendering, engine execution,
     * discovery or downloads. Existing factories and their historical defaults remain unchanged.
     * @return caller-owned mutable builder exposing only its configuration interface
     */
    static Builder builder() {
        return new DefaultPdfPartitionerBuilder();
    }

    /** Mutable, non-thread-safe preset configuration; built services snapshot its state. */
    interface Builder {
        /**
         * Selects DocWeave routing, independently of optional engine/model installation.
         * @param strategy nonnull preset; initial value AUTO
         * @return this builder
         * @throws NullPointerException if strategy is null
         */
        Builder strategy(PdfStrategy strategy);

        /**
         * Sets native/source-image policies and document budgets for every preset.
         * Native text/table grouping policies are unused by full-page OCR presets.
         * @param options nonnull native and source-image options
         * @return this builder
         * @throws NullPointerException if options is null
         */
        Builder options(PdfOptions options);

        /**
         * Sets OCR budgets, resolution, reading order and optional table recognition.
         * Presets own selection: AUTO uses TEXTLESS_PAGES, OCR_ONLY/HI_RES use ALL_PAGES.
         * HI_RES also selects PROVIDER_LAYOUT; all other OCR fields are preserved.
         * Omission uses defaults, except HI_RES renders at300DPI. FAST ignores OCR configuration.
         * @param options nonnull immutable OCR policy
         * @return this builder
         * @throws NullPointerException if options is null
         */
        Builder ocrOptions(PdfOcrOptions options);

        /**
         * Supplies a reusable, thread-safe recognizer without transferring its lifecycle.
         * FAST never invokes it. AUTO may omit it; OCR_ONLY and HI_RES require it.
         * @param provider nonnull line provider, or actual-word provider for word/layout tables
         * @return this builder
         * @throws NullPointerException if provider is null
         */
        Builder ocrProvider(PdfOcrProvider provider);

        /**
         * Supplies caller-owned complete source-pixel table geometry for explicit provider modes.
         * No model is inferred from the classpath or downloaded. FAST ignores this provider.
         * Other presets require a matching provider-layout OCR table policy to avoid ignored settings.
         * @param provider nonnull thread-safe layout service
         * @return this builder
         * @throws NullPointerException if provider is null
         */
        Builder tableLayoutProvider(PdfTableLayoutProvider provider);

        /**
         * Validates capabilities and creates a reusable hidden service without executing providers.
         * Missing required dependencies and inconsistent word/layout policies fail immediately.
         * Native/renderer/input/output limits and ordinary provider-failure propagation remain active.
         * @return configured service independent of later builder mutations
         * @throws IllegalArgumentException if required providers/capabilities are absent or inconsistent
         */
        PdfPartitioner build();
    }

    /**
     * Copies a built-in native-only strategy with explicit source-authored paragraph grouping.
     * Complete page-local P references own retained native glyphs outside source tables. Cross-page P elements
     * fall back by default; an explicit page-fragment policy groups consecutive-page fragments independently.
     * Unsupported, ambiguous, form-backed or noncolumn regions retain ordinary native fallback.
     * Existing source-tagged tables take precedence; output page order stays geometric, without a
     * general logical article-order guarantee. Password configuration and native policies are retained.
     * OCR and provider-layout composition are not supported by this initial source-paragraph policy.
     * Custom implementations may override this optional method; its default rejects configuration.
     * @param paragraphs nonnull limits and word-wrap policy
     * @return reusable hidden copy without I/O or provider execution
     * @throws NullPointerException if paragraphs is null
     * @throws IllegalArgumentException if OCR/provider geometry or run-based table detection is selected
     * @throws UnsupportedOperationException if a custom implementation does not support this policy
     */
    default PdfPartitioner withTaggedParagraphs(final PdfTaggedParagraphOptions paragraphs) {
        java.util.Objects.requireNonNull(paragraphs, "paragraphs");
        throw new UnsupportedOperationException("Custom PDF partitioner does not support tagged paragraphs");
    }

    /**
     * Copies this built-in strategy with an explicit caller-owned password lookup.
     * Native/OCR/layout/orientation/tagged-table policies and their provider lifetimes are retained.
     * Credentials are requested only when opening or encrypted extraction permissions require them;
     * all ordinary permission, input, page, traversal and output checks still apply.
     * No lookup or parsing occurs during configuration, and the original service remains unchanged.
     * Custom implementations may override this optional method; its default rejects configuration.
     * @param passwords nonnull thread-safe provider returning fresh transferred character arrays
     * @return reusable hidden service with an independent immutable configuration
     * @throws NullPointerException if passwords is null
     * @throws UnsupportedOperationException if a custom implementation does not support this policy
     */
    default PdfPartitioner withPasswords(final PdfPasswordProvider passwords) {
        java.util.Objects.requireNonNull(passwords, "passwords");
        throw new UnsupportedOperationException("Custom PDF partitioner does not support password configuration");
    }

    /**
     * Copies an explicitly source-tagged strategy with a caller-selected geometry requirement.
     * Logical-envelope mode retains blank source slots without assigning them cell rectangles.
     * The explicit cell-envelope variant keeps unclaimed gap text as separate ordinary output;
     * every nonblank glyph center within a real cell text envelope still requires source ownership.
     * Existing factories retain supported-axis validation unless this method is called.
     * @param geometry nonnull source table geometry policy
     * @return immutable reusable strategy
     * @throws NullPointerException if geometry is null
     * @throws IllegalArgumentException if source tables are disabled or logical mode requests cell coordinates
     * @throws UnsupportedOperationException if a third-party implementation does not support this extension
     */
    default PdfPartitioner withTaggedTableGeometry(final PdfTaggedTableGeometry geometry) {
        java.util.Objects.requireNonNull(geometry, "geometry");
        throw new UnsupportedOperationException("Source table geometry selection is unsupported");
    }

    /**
     * Prefers complete source-tagged single-page tables, then applies the selected glyph-owning native fallback.
     * Explicit TR/TH/TD roles and supported direct/class spans supply topology; page MCIDs own retained native glyphs.
     * Explicit same-page Caption references outside rows are validated separately when their displayed text
     * is disjoint from the table. They remain available to native fallback without being copied into cells.
     * This does not generate captions or classify their text as FigureCaption elements.
     * Missing, ambiguous, unlocatable-empty-cell, form-backed, nested or cross-page tag tables retain source text for
     * fallback. Cell geometry describes native text envelopes, not painted borders. No model/OCR/rendering is required.
     * Existing extraction, source/table/work budgets and cancellation apply; defaults/factories are unchanged.
     * @param options nonnull source/native policy, excluding run-based aligned modes
     * @return reusable hidden source-first service using historical space-separated cell lines
     * @throws NullPointerException if options is null
     * @throws IllegalArgumentException if a run-based fallback mode is selected
     */
    static PdfPartitioner createWithTaggedTables(final PdfOptions options) {
        return createWithTaggedTables(options, PdfNativeLayoutOptions.CellTextJoin.SPACE_SEPARATED);
    }

    /**
     * Selects explicit source-tagged table extraction and native cell text interpretation.
     * Source roles remain authoritative; general alternate-text substitution and tagged reading order are not inferred.
     * The explicit source-soft-hyphen policy recognizes only single-reference discretionary glyph hints.
     * Rejected tables commit no glyph claims; native fallback excludes accepted tagged envelopes before ownership.
     * @param options nonnull native extraction and table budgets
     * @param join nonnull native line-joining policy, including explicit dates and source discretionary wraps
     * @return hidden reusable source-first partitioner without model or OCR dependencies
     * @throws NullPointerException if an argument is null
     * @throws IllegalArgumentException if the native policy is not glyph-owning
     */
    static PdfPartitioner createWithTaggedTables(
            final PdfOptions options, final PdfNativeLayoutOptions.CellTextJoin join) {
        requireGlyphOwningNativePolicy(java.util.Objects.requireNonNull(options, "options"));
        return new DefaultPdfPartitioner(options, null, null, null, null, null, null, null, false,
                java.util.Objects.requireNonNull(join, "join"));
    }

    /**
     * Prefers supported source-tagged tables, then accepted native tables, then disjoint provider layouts.
     * All provider candidates are still invoked/validated and charged to the combined raw-anchor budget.
     * Provider failures propagate even when source tags succeed. Source images and unclaimed text emit once.
     * The provider remains caller-owned; construction performs no I/O, rendering or inference.
     * @param options source/native policy with glyph-owning fallback
     * @param layoutOptions rendering, combined-anchor and explicit native cell text policy
     * @param layout caller-owned thread-safe source-pixel table geometry service
     * @return reusable hidden tagged/native/provider composition
     * @throws NullPointerException if an argument is null
     * @throws IllegalArgumentException if a run-based native mode is selected
     */
    static PdfPartitioner createWithTaggedTablesFirst(
            final PdfOptions options, final PdfNativeLayoutOptions layoutOptions, final PdfTableLayoutProvider layout) {
        java.util.Objects.requireNonNull(options, "options");
        java.util.Objects.requireNonNull(layoutOptions, "layoutOptions");
        java.util.Objects.requireNonNull(layout, "layout");
        requireGlyphOwningNativePolicy(options);
        return new DefaultPdfPartitioner(
                options, null, null, null, null, null, layoutOptions, layout, true, layoutOptions.cellTextJoin());
    }

    /**
     * Keeps exact glyph ownership available to source-first/native-first composition.
     * @param options already nonnull native policy
     * @throws IllegalArgumentException if run-based fallback would prevent exact source ownership
     */
    private static void requireGlyphOwningNativePolicy(final PdfOptions options) {
        if (options.tableDetection().alignedText()) {
            throw new IllegalArgumentException("Native-first composition requires a glyph-owning native table policy");
        }
    }

    /**
     * Creates explicit native-first composition with model/provider tables admitted only in disjoint regions.
     * Accepted native tables and exact detector-unclaimed glyphs are staged first. Every provider
     * candidate is validated before overlap selection; any positive interior overlap rejects that
     * whole provider table, preserving ordinary unclaimed text. Touching outer edges are allowed.
     * The provider still runs on native-text pages with accepted native tables, and its failures
     * propagate. Accepted native anchors plus all raw provider anchors share maxTableAnchors;
     * source/table/output limits and one native/provider work counter remain active.
     * Closed rules, double-border, horizontal-rule, partial-rule and segmented-row native policies are supported.
     * Run-based aligned/multiline policies are rejected at construction because this strategy requires
     * exact glyph ownership. Existing factories continue supporting those policies unchanged.
     * Textless pages retain images/warnings without provider calls or OCR. Construction performs no
     * I/O and does not own/close the provider. Quarter turns/crop/UserUnit use the displayed frame.
     * @param options native table/text/image policy, excluding run-based aligned modes
     * @param layoutOptions explicit render and combined anchor budget
     * @param layout caller-owned thread-safe complete geometry strategy
     * @return reusable hidden composition strategy; no default selection change
     * @throws NullPointerException if an argument is null
     * @throws IllegalArgumentException if a run-based native table mode is selected
     */
    static PdfPartitioner createWithNativeTablesFirst(
            final PdfOptions options, final PdfNativeLayoutOptions layoutOptions, final PdfTableLayoutProvider layout) {
        java.util.Objects.requireNonNull(options, "options");
        java.util.Objects.requireNonNull(layoutOptions, "layoutOptions");
        java.util.Objects.requireNonNull(layout, "layout");
        requireGlyphOwningNativePolicy(options);
        return new DefaultPdfPartitioner(options, null, null, null, null, null, layoutOptions, layout, true);
    }

    /**
     * Creates native-text extraction with explicit provider table geometry on native-text pages.
     * The provider borrows a full-page RGB raster; actual native glyphs supply cell text. This
     * replaces rule/aligned table heuristics on those pages and leaves unclaimed text and all raster
     * placements intact. Empty provider output retains ordinary native text. Textless pages retain
     * images and diagnostics without invoking this provider or performing OCR. Declared page and native baseline
     * quarter turns share the rendered displayed frame; arbitrary-angle native text remains unsupported and fails
     * explicitly. Construction performs no rendering, inference, download or provider lifecycle transfer. Applications
     * close managed providers after all concurrent extraction calls finish. Complete topology, source ownership,
     * table/work/render/output budgets and interruption checks apply. Shared provider validation uses OCR_FAILURE for
     * invalid geometry/provider I/O, even though this strategy runs no OCR. Provider deadlines remain the provider's
     * responsibility. Provider tables contribute separate declared-header continuation evidence only with
     * {@link PdfOptions.TableContinuation#REPEATED_HEADER_WITH_PROVIDERS}, or bounded unmerged prefixes through
     * {@link PdfOptions.TableContinuation#REPEATED_HEADER_PREFIX_WITH_PROVIDERS}, or exact declared merged prefixes
     * through {@link PdfOptions.TableContinuation#REPEATED_HEADER_LAYOUT_WITH_PROVIDERS}; they never cross-link with
     * ruled tables.
     * @param options nonnull native text/image/table budgets; table detection is replaced on native pages
     * @param layoutOptions nonnull explicit rendering and aggregate raw-anchor policy
     * @param layout nonnull thread-safe complete source-pixel topology strategy
     * @return reusable hidden implementation with call-confined source state
     * @throws NullPointerException if an argument is null
     */
    static PdfPartitioner createWithNativeLayout(
            final PdfOptions options, final PdfNativeLayoutOptions layoutOptions, final PdfTableLayoutProvider layout) {
        return new DefaultPdfPartitioner(java.util.Objects.requireNonNull(options, "options"), null, null, null, null,
                null, java.util.Objects.requireNonNull(layoutOptions, "layoutOptions"),
                java.util.Objects.requireNonNull(layout, "layout"));
    }

    /**
     * Creates provider-layout cell refinement with independently selected PAGE-word and CELL-line engines.
     * One PAGE call retains actual word boxes for ordinary source remainders; accepted anchor crops are
     * recognized only by {@code cells} in TABLE_CELL purpose. Cell recognition needs no word capability.
     * Providers may use different installed models, languages or segmentation policies. All raw returned
     * output shares the existing per-page line/character budgets, crop preflight and table limits.
     * Blank-cell fallback and source-image ownership are unchanged. External resources remain caller-owned;
     * construction does not render, discover models, execute providers or transfer their lifecycle.
     * @param options nonnull native/image/table policy
     * @param ocrOptions nonnull OCR policy selecting PROVIDER_LAYOUT_CELLS
     * @param words nonnull thread-safe actual PAGE-word strategy
     * @param layout nonnull thread-safe complete source-pixel topology strategy
     * @param cells nonnull thread-safe padded-cell line strategy, borrowing each supplied raster
     * @return hidden reusable partitioner with independent recognition dependencies
     * @throws NullPointerException if an argument is null
     * @throws IllegalArgumentException if the table policy does not select provider-layout cell refinement
     */
    static PdfPartitioner createWithCellOcr(final PdfOptions options, final PdfOcrOptions ocrOptions,
            final PdfWordOcrProvider words, final PdfTableLayoutProvider layout, final PdfOcrProvider cells) {
        requireCellPolicy(ocrOptions);
        return new DefaultPdfPartitioner(java.util.Objects.requireNonNull(options, "options"), ocrOptions,
                java.util.Objects.requireNonNull(words, "words"), java.util.Objects.requireNonNull(layout, "layout"),
                null, java.util.Objects.requireNonNull(cells, "cells"));
    }

    /**
     * Creates independent PAGE/CELL recognition with explicit whole-pipeline quarter-turn estimation.
     * The estimator runs before PAGE words, layout and cell crops. Each strategy receives its borrowed
     * corrected raster; emitted parent/cell geometry is restored once to the original displayed frame.
     * Source images remain unchanged. Correction does not infer semantic orientation or guarantee accuracy.
     * The ownership, aggregate budgets and lifecycle contract of {@link #createWithCellOcr} applies.
     * @param options nonnull native/image/table policy
     * @param ocrOptions nonnull OCR policy selecting PROVIDER_LAYOUT_CELLS
     * @param words nonnull thread-safe actual PAGE-word strategy
     * @param layout nonnull thread-safe complete source-pixel topology strategy
     * @param cells nonnull thread-safe TABLE_CELL line strategy
     * @param orientation nonnull thread-safe bounded quarter-turn estimator
     * @return hidden reusable oriented partitioner with independent recognition dependencies
     * @throws NullPointerException if an argument is null
     * @throws IllegalArgumentException if the table policy does not select provider-layout cell refinement
     */
    static PdfPartitioner createOrientedWithCellOcr(final PdfOptions options, final PdfOcrOptions ocrOptions,
            final PdfWordOcrProvider words, final PdfTableLayoutProvider layout, final PdfOcrProvider cells,
            final PdfOrientationEstimator orientation) {
        requireCellPolicy(ocrOptions);
        return new DefaultPdfPartitioner(java.util.Objects.requireNonNull(options, "options"), ocrOptions,
                java.util.Objects.requireNonNull(words, "words"), java.util.Objects.requireNonNull(layout, "layout"),
                java.util.Objects.requireNonNull(orientation, "orientation"),
                java.util.Objects.requireNonNull(cells, "cells"));
    }

    /**
     * Rejects configurations that would silently ignore a separately supplied cell dependency.
     * @param options nonnull explicit cell-refinement policy
     * @throws NullPointerException if options is null
     * @throws IllegalArgumentException if cell refinement is not selected
     */
    private static void requireCellPolicy(final PdfOcrOptions options) {
        java.util.Objects.requireNonNull(options, "ocrOptions");
        if (options.tableDetection() != PdfOcrOptions.TableDetection.PROVIDER_LAYOUT_CELLS) {
            throw new IllegalArgumentException("Separate cell OCR requires PROVIDER_LAYOUT_CELLS");
        }
    }

    /**
     * Creates a thread-safe service with explicit resource and image policies.
     * @param options nonnull immutable PDF policy
     * @return hidden native implementation
     * @throws NullPointerException if options is null
     */
    static PdfPartitioner create(final PdfOptions options) {
        return new DefaultPdfPartitioner(java.util.Objects.requireNonNull(options, "options"));
    }

    /**
     * Creates a service with explicit OCR page/region selection. By default, only pages without
     * native glyphs are recognized. IMAGE_REGIONS also recognizes masked image areas on mixed pages,
     * excluding native text by geometry. This conservative policy can omit ambiguous OCR lines.
     * ALL_PAGES instead recognizes every whole page and replaces native text/table emission, including
     * searchable text layers. Native glyph grouping/limits are unused on that path; graphics operator,
     * source-image, document, rendered-pixel and raw OCR output limits remain active.
     * Source image placements are retained. OCR lines remain ordinary unless an explicit ruled-grid
     * or actual-word table policy accepts ownership; provider-layout mode requires the four-argument factory.
     * Provider failures abort the document and retain their causes; no silent native fallback occurs.
     * @param options native extraction policies
     * @param ocrOptions rendering and returned-output budgets
     * @param provider caller-supplied thread-safe recognition strategy
     * @return hidden configured implementation
     * @throws NullPointerException if any argument is null
     */
    static PdfPartitioner create(
            final PdfOptions options, final PdfOcrOptions ocrOptions, final PdfOcrProvider provider) {
        return new DefaultPdfPartitioner(java.util.Objects.requireNonNull(options, "options"),
                java.util.Objects.requireNonNull(ocrOptions, "ocrOptions"),
                java.util.Objects.requireNonNull(provider, "provider"));
    }

    /**
     * Creates explicit geometry/word table extraction without binding the PDF module to a model runtime.
     * PROVIDER_LAYOUT or PROVIDER_LAYOUT_CELLS must be selected. Providers borrow the same rendered/native-masked
     * raster; one PAGE word call supplies ordinary remainders; the CELLS mode additionally recognizes each anchor.
     * Layouts require complete disjoint topology and may include empty/merged/multiline cells.
     * Provider continuation links require explicit
     * {@link PdfOptions.TableContinuation#REPEATED_HEADER_WITH_PROVIDERS} and eligible declared headers;
     * nonzero OCR orientation corrections remain ineligible. Providers and their external resources remain
     * caller-owned.
     * @param options native/image/table limits
     * @param ocrOptions render/output policy selecting a provider-layout mode
     * @param words thread-safe actual-word recognition strategy
     * @param layout thread-safe complete source-pixel topology strategy
     * @return hidden reusable configured partitioner
     * @throws NullPointerException if an argument is null
     * @throws IllegalArgumentException if the table mode does not use an explicit layout
     */
    static PdfPartitioner create(final PdfOptions options, final PdfOcrOptions ocrOptions,
            final PdfWordOcrProvider words, final PdfTableLayoutProvider layout) {
        java.util.Objects.requireNonNull(ocrOptions, "ocrOptions");
        if (ocrOptions.tableDetection() != PdfOcrOptions.TableDetection.PROVIDER_LAYOUT
                && ocrOptions.tableDetection() != PdfOcrOptions.TableDetection.PROVIDER_LAYOUT_CELLS) {
            throw new IllegalArgumentException("Explicit table layout requires a provider-layout policy");
        }
        return new DefaultPdfPartitioner(java.util.Objects.requireNonNull(options, "options"), ocrOptions,
                java.util.Objects.requireNonNull(words, "words"), java.util.Objects.requireNonNull(layout, "layout"));
    }

    /**
     * Creates explicit quarter-turn estimation before ordinary PAGE OCR and ruled/aligned table stages.
     * Accepted corrections rotate one owned raster; text/cell coordinates return to the original page frame.
     * Estimator and recognizer remain caller-owned; no model/engine is discovered or downloaded.
     * @param options native policy
     * @param ocrOptions rendered OCR/output policy
     * @param provider thread-safe line/word recognition strategy
     * @param orientation thread-safe bounded orientation estimator
     * @return hidden reusable oriented partitioner
     * @throws NullPointerException if a required argument is null
     */
    static PdfPartitioner createOriented(final PdfOptions options, final PdfOcrOptions ocrOptions,
            final PdfOcrProvider provider, final PdfOrientationEstimator orientation) {
        return new DefaultPdfPartitioner(java.util.Objects.requireNonNull(options, "options"),
                java.util.Objects.requireNonNull(ocrOptions, "ocrOptions"),
                java.util.Objects.requireNonNull(provider, "provider"), null,
                java.util.Objects.requireNonNull(orientation, "orientation"));
    }

    /**
     * Creates explicit whole-page orientation before actual-word and complete-layout inference.
     * Both strategies borrow the same upright raster; source words, parents and cell rectangles are
     * restored to the original displayed crop frame once. Provider layouts remain validated upright.
     * Continuation inference is disabled on corrected OCR tables; native output is unchanged.
     * @param options native policy
     * @param ocrOptions render/output policy selecting PROVIDER_LAYOUT or PROVIDER_LAYOUT_CELLS
     * @param words thread-safe actual-word recognition strategy
     * @param layout thread-safe complete upright-raster geometry strategy
     * @param orientation thread-safe bounded orientation estimator
     * @return hidden reusable oriented layout partitioner
     * @throws NullPointerException if an argument is null
     * @throws IllegalArgumentException if the selected OCR table mode does not use explicit layouts
     */
    static PdfPartitioner createOriented(final PdfOptions options, final PdfOcrOptions ocrOptions,
            final PdfWordOcrProvider words, final PdfTableLayoutProvider layout,
            final PdfOrientationEstimator orientation) {
        java.util.Objects.requireNonNull(ocrOptions, "ocrOptions");
        if (ocrOptions.tableDetection() != PdfOcrOptions.TableDetection.PROVIDER_LAYOUT
                && ocrOptions.tableDetection() != PdfOcrOptions.TableDetection.PROVIDER_LAYOUT_CELLS) {
            throw new IllegalArgumentException("Explicit table layout requires a provider-layout policy");
        }
        return new DefaultPdfPartitioner(java.util.Objects.requireNonNull(options, "options"), ocrOptions,
                java.util.Objects.requireNonNull(words, "words"), java.util.Objects.requireNonNull(layout, "layout"),
                java.util.Objects.requireNonNull(orientation, "orientation"));
    }
}
