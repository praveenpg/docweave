package io.docweave.partition;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

import java.io.IOException;
import java.io.InterruptedIOException;

/** Stateless orchestration; PDFBox documents and all extracted geometry are confined to a call. */
final class DefaultPdfPartitioner implements PdfPartitioner {
    /** Immutable strategy policy. */
    private final PdfOptions options;

    /** Optional scanned-page policy; null on the native-only path. */
    private final PdfOcrOptions ocrOptions;
    /** Optional caller-owned thread-safe recognition strategy. */
    private final PdfOcrProvider ocrProvider;

    /** Optional caller-owned complete table layout strategy; never discovered implicitly. */
    private final PdfTableLayoutProvider tableLayout;

    /** Optional caller-owned quarter-turn estimator; never discovered implicitly. */
    private final PdfOrientationEstimator orientation;

    /** Caller-owned anchor-cell recognizer; existing factories reuse the page recognizer. */
    private final PdfOcrProvider cellProvider;

    /** Optional immutable native render/anchor policy, independent of OCR selection. */
    private final PdfNativeLayoutOptions nativeLayoutOptions;
    /** Optional caller-owned native-text geometry strategy. */
    private final PdfTableLayoutProvider nativeLayout;

    /** Nonnull only for explicit source-tagged extraction; defaults never inspect source structure. */
    private final PdfNativeLayoutOptions.CellTextJoin taggedCellTextJoin;

    /** Explicit native-first precedence; false preserves provider-only factories. */
    private final boolean nativeTablesFirst;

    /** Optional caller-owned credential lookup; actual credentials remain confined to opening one document. */
    private final PdfPasswordProvider passwords;
    /** Nonnull only for explicit source-paragraph grouping on native-only strategies. */
    private final PdfTaggedParagraphOptions paragraphOptions;

    /** Explicit source table geometry requirement; historical factories require supported cell axes. */
    private final PdfTaggedTableGeometry taggedGeometry;

    /**
     * Captures validated limits.
     * @param options immutable PDF policy
     */
    DefaultPdfPartitioner(final PdfOptions options) {
        this(options, null, null);
    }

    /**
     * Captures explicit OCR dependencies without starting processes or rendering pages.
     * @param options native policy
     * @param ocrOptions optional OCR policy
     * @param ocrProvider optional provider
     */
    DefaultPdfPartitioner(final PdfOptions options, final PdfOcrOptions ocrOptions, final PdfOcrProvider ocrProvider) {
        this(options, ocrOptions, ocrProvider, null);
    }

    /**
     * Captures independently supplied word-recognition and table-layout strategies.
     * @param options native extraction policy
     * @param ocrOptions optional OCR policy
     * @param ocrProvider optional synchronous word/line provider
     * @param tableLayout optional complete geometry provider
     */
    DefaultPdfPartitioner(final PdfOptions options, final PdfOcrOptions ocrOptions, final PdfOcrProvider ocrProvider,
            final PdfTableLayoutProvider tableLayout) {
        this(options, ocrOptions, ocrProvider, tableLayout, null);
    }

    /**
     * Captures independent OCR/layout/orientation strategies without rendering or external execution.
     * @param options native extraction policy
     * @param ocrOptions optional OCR policy
     * @param ocrProvider optional caller-owned line/word recognizer
     * @param tableLayout optional complete geometry strategy
     * @param orientation optional caller-owned quarter-turn estimator
     */
    DefaultPdfPartitioner(final PdfOptions options, final PdfOcrOptions ocrOptions, final PdfOcrProvider ocrProvider,
            final PdfTableLayoutProvider tableLayout, final PdfOrientationEstimator orientation) {
        this(options, ocrOptions, ocrProvider, tableLayout, orientation, ocrProvider);
    }

    /**
     * Captures separate actual-page-word and cell-line strategies without assuming shared engine capabilities.
     * @param options native extraction policy
     * @param ocrOptions optional OCR policy
     * @param ocrProvider optional caller-owned PAGE recognizer
     * @param tableLayout optional complete geometry strategy
     * @param orientation optional bounded quarter-turn estimator
     * @param cellProvider optional caller-owned TABLE_CELL line recognizer
     */
    DefaultPdfPartitioner(final PdfOptions options, final PdfOcrOptions ocrOptions, final PdfOcrProvider ocrProvider,
            final PdfTableLayoutProvider tableLayout, final PdfOrientationEstimator orientation,
            final PdfOcrProvider cellProvider) {
        this(options, ocrOptions, ocrProvider, tableLayout, orientation, cellProvider, null, null);
    }

    /**
     * Captures independent native or OCR strategies without resource execution or lifecycle transfer.
     * @param options native policy
     * @param ocrOptions optional OCR policy
     * @param ocrProvider optional PAGE recognizer
     * @param tableLayout optional OCR geometry strategy
     * @param orientation optional OCR orientation strategy
     * @param cellProvider optional CELL recognizer
     * @param nativeLayoutOptions optional explicit native render policy
     * @param nativeLayout optional native geometry strategy
     */
    DefaultPdfPartitioner(final PdfOptions options, final PdfOcrOptions ocrOptions, final PdfOcrProvider ocrProvider,
            final PdfTableLayoutProvider tableLayout, final PdfOrientationEstimator orientation,
            final PdfOcrProvider cellProvider, final PdfNativeLayoutOptions nativeLayoutOptions,
            final PdfTableLayoutProvider nativeLayout) {
        this(options, ocrOptions, ocrProvider, tableLayout, orientation, cellProvider, nativeLayoutOptions,
                nativeLayout, false);
    }

    /**
     * Captures explicit precedence without executing or closing caller strategies.
     * @param options native extraction policy
     * @param ocrOptions optional recognition policy
     * @param ocrProvider optional PAGE recognizer
     * @param tableLayout optional OCR geometry provider
     * @param orientation optional orientation estimator
     * @param cellProvider optional CELL recognizer
     * @param nativeLayoutOptions optional native render/anchor policy
     * @param nativeLayout optional native geometry provider
     * @param nativeTablesFirst whether exact accepted native tables take overlap precedence
     */
    DefaultPdfPartitioner(final PdfOptions options, final PdfOcrOptions ocrOptions, final PdfOcrProvider ocrProvider,
            final PdfTableLayoutProvider tableLayout, final PdfOrientationEstimator orientation,
            final PdfOcrProvider cellProvider, final PdfNativeLayoutOptions nativeLayoutOptions,
            final PdfTableLayoutProvider nativeLayout, final boolean nativeTablesFirst) {
        this(options, ocrOptions, ocrProvider, tableLayout, orientation, cellProvider, nativeLayoutOptions,
                nativeLayout, nativeTablesFirst, null);
    }

    /**
     * Captures source-tag precedence without modifying existing factory defaults.
     * @param options source/native budgets
     * @param ocrOptions optional OCR policy
     * @param ocrProvider optional PAGE recognizer
     * @param tableLayout optional OCR layout service
     * @param orientation optional OCR orientation service
     * @param cellProvider optional CELL recognizer
     * @param nativeLayoutOptions optional native render/anchor policy
     * @param nativeLayout optional native layout service
     * @param nativeTablesFirst whether native tables precede models
     * @param taggedCellTextJoin nonnull enables source-tagged tables with this native text policy
     */
    DefaultPdfPartitioner(final PdfOptions options, final PdfOcrOptions ocrOptions, final PdfOcrProvider ocrProvider,
            final PdfTableLayoutProvider tableLayout, final PdfOrientationEstimator orientation,
            final PdfOcrProvider cellProvider, final PdfNativeLayoutOptions nativeLayoutOptions,
            final PdfTableLayoutProvider nativeLayout, final boolean nativeTablesFirst,
            final PdfNativeLayoutOptions.CellTextJoin taggedCellTextJoin) {
        this(options, ocrOptions, ocrProvider, tableLayout, orientation, cellProvider, nativeLayoutOptions,
                nativeLayout, nativeTablesFirst, taggedCellTextJoin, null);
    }

    /**
     * Captures complete immutable extraction dependencies and optional explicit credential lookup.
     * @param options native extraction limits
     * @param ocrOptions optional scanned-page policy
     * @param ocrProvider optional caller-owned PAGE recognizer
     * @param tableLayout optional OCR geometry provider
     * @param orientation optional orientation estimator
     * @param cellProvider optional caller-owned CELL recognizer
     * @param nativeLayoutOptions optional native render/anchor policy
     * @param nativeLayout optional native geometry provider
     * @param nativeTablesFirst whether native tables precede provider candidates
     * @param taggedCellTextJoin optional source-tagged cell text policy
     * @param passwords optional caller-owned credential provider
     */
    private DefaultPdfPartitioner(final PdfOptions options, final PdfOcrOptions ocrOptions,
            final PdfOcrProvider ocrProvider, final PdfTableLayoutProvider tableLayout,
            final PdfOrientationEstimator orientation, final PdfOcrProvider cellProvider,
            final PdfNativeLayoutOptions nativeLayoutOptions, final PdfTableLayoutProvider nativeLayout,
            final boolean nativeTablesFirst, final PdfNativeLayoutOptions.CellTextJoin taggedCellTextJoin,
            final PdfPasswordProvider passwords) {
        this(options, ocrOptions, ocrProvider, tableLayout, orientation, cellProvider, nativeLayoutOptions,
                nativeLayout, nativeTablesFirst, taggedCellTextJoin, passwords, null,
                PdfTaggedTableGeometry.SUPPORTED_CELL_AXES);
    }

    /**
     * Captures all native-only/source/provider dependencies without executing them.
     * @param options native policy
     * @param ocrOptions optional OCR policy
     * @param ocrProvider optional PAGE recognizer
     * @param tableLayout optional OCR table provider
     * @param orientation optional orientation strategy
     * @param cellProvider optional CELL recognizer
     * @param nativeLayoutOptions optional native provider policy
     * @param nativeLayout optional native geometry provider
     * @param nativeTablesFirst native-first composition flag
     * @param taggedCellTextJoin optional source-table policy
     * @param passwords optional caller-owned credential provider
     * @param paragraphOptions optional source-paragraph policy
     * @param taggedGeometry explicit source table geometry requirement
     */
    private DefaultPdfPartitioner(final PdfOptions options, final PdfOcrOptions ocrOptions,
            final PdfOcrProvider ocrProvider, final PdfTableLayoutProvider tableLayout,
            final PdfOrientationEstimator orientation, final PdfOcrProvider cellProvider,
            final PdfNativeLayoutOptions nativeLayoutOptions, final PdfTableLayoutProvider nativeLayout,
            final boolean nativeTablesFirst, final PdfNativeLayoutOptions.CellTextJoin taggedCellTextJoin,
            final PdfPasswordProvider passwords, final PdfTaggedParagraphOptions paragraphOptions,
            final PdfTaggedTableGeometry taggedGeometry) {
        this.taggedGeometry = taggedGeometry;
        this.paragraphOptions = paragraphOptions;
        this.passwords = passwords;
        this.taggedCellTextJoin = taggedCellTextJoin;
        this.nativeTablesFirst = nativeTablesFirst;
        this.nativeLayoutOptions = nativeLayoutOptions;
        this.nativeLayout = nativeLayout;
        this.options = options;
        this.ocrOptions = ocrOptions;
        this.ocrProvider = ocrProvider;
        this.tableLayout = tableLayout;
        this.orientation = orientation;
        this.cellProvider = cellProvider;
    }

    @Override
    public PdfPartitioner withPasswords(final PdfPasswordProvider passwords) {
        return new DefaultPdfPartitioner(options, ocrOptions, ocrProvider, tableLayout, orientation, cellProvider,
                nativeLayoutOptions, nativeLayout, nativeTablesFirst, taggedCellTextJoin,
                java.util.Objects.requireNonNull(passwords, "passwords"), paragraphOptions, taggedGeometry);
    }

    @Override
    public PdfPartitioner withTaggedParagraphs(final PdfTaggedParagraphOptions paragraphs) {
        java.util.Objects.requireNonNull(paragraphs, "paragraphs");
        if (ocrProvider != null || nativeLayout != null || options.tableDetection().alignedText()) {
            throw new IllegalArgumentException("Source paragraphs require a glyph-owning native-only strategy");
        }
        return new DefaultPdfPartitioner(options, ocrOptions, ocrProvider, tableLayout, orientation, cellProvider,
                nativeLayoutOptions, nativeLayout, nativeTablesFirst, taggedCellTextJoin, passwords, paragraphs,
                taggedGeometry);
    }

    @Override
    public PdfPartitioner withTaggedTableGeometry(final PdfTaggedTableGeometry geometry) {
        java.util.Objects.requireNonNull(geometry, "geometry");
        if (taggedCellTextJoin == null
                || geometry != PdfTaggedTableGeometry.SUPPORTED_CELL_AXES && options.includeTableCellCoordinates()) {
            throw new IllegalArgumentException(
                    "Logical source table geometry requires source tables and disabled cell coordinates");
        }
        return new DefaultPdfPartitioner(options, ocrOptions, ocrProvider, tableLayout, orientation, cellProvider,
                nativeLayoutOptions, nativeLayout, nativeTablesFirst, taggedCellTextJoin, passwords, paragraphOptions,
                geometry);
    }

    /**
     * Returns the strategy key.
     * @return PDF
     */
    @Override
    public DocumentFormat format() {
        return DocumentFormat.PDF;
    }

    /**
     * Extracts pages without executing scripts, following links or evaluating forms. The whole document is processed
     * as one page range covering every page, assembled by the same path as stored range results. Because that range
     * starts at page 1, its elements are emitted straight into the context with final IDs and assembly only verifies
     * that no reference needs rewriting.
     * @param context synchronous binary input and bounded emission sink
     * @throws IOException if parsing, permission checks, cancellation or extraction limits fail
     */
    @Override
    public void partition(final PartitionContext context) throws IOException {
        NestedTableSupport.check(this, context.options());
        final var assembly = new PdfRangeAssembly(PdfRangeAssembly.Output.emitted(context));
        final var pages = new PdfRangeCollector.Listener() {
            /** Whole-document coverage, known once the document is open. */
            private PdfPageRange coverage;
            /** Evidence of the last completed page. */
            private PdfTableBoundary last;

            @Override
            public void begin(final int documentPages, final java.util.List<PartitionResult.Warning> warnings)
                    throws IOException {
                coverage = documentPages == 0 ? null : new PdfPageRange(1, documentPages);
                if (coverage != null) {
                    assembly.begin(coverage, documentPages, warnings, null);
                } else {
                    for (final var warning : warnings) {
                        context.warn(warning.code(), warning.message());
                    }
                }
            }

            @Override
            public void page(final PdfRangeResult.Page page, final PdfTableBoundary boundary) throws IOException {
                assembly.page(page);
                last = boundary;
            }
        };
        extract(context.binaryBuffer(), context.filename(), context.options(), null, standardBudget(context.options()),
                context.progress(), pages, null, null, false, context);
        if (pages.coverage != null) {
            assembly.end(pages.coverage, pages.last);
            assembly.finish();
        }
    }

    /**
     * Partitions one contiguous page range from a source. The source is hashed once; that digest also serves as the
     * paragraph identity. Document-wide checks and the source-tag pass still cover the whole document.
     * @param source local PDF source
     * @param partitionOptions document options
     * @param pages one-based inclusive page range
     * @param budget caller's budget for this call
     * @param listener optional progress listener
     * @return immutable range result
     * @throws IOException if loading, parsing, limits or cancellation fail
     */
    @Override
    public PdfRangeResult partitionRange(final DocumentSource source, final PartitionOptions partitionOptions,
            final PdfPageRange pages, final PdfBudget budget, final PartitionProgressListener listener)
            throws IOException {
        java.util.Objects.requireNonNull(pages, "pages");
        java.util.Objects.requireNonNull(budget, "budget");
        final var progress = PartitionProgressTracker.of(listener);
        try {
            progress.enter(PartitionProgress.Phase.PREPARING);
            final var content = load(source, partitionOptions);
            final String sha = sha256(content.binary());
            progress.enter(PartitionProgress.Phase.EXTRACTING);
            final var collected = new RangePages();
            extract(java.nio.ByteBuffer.wrap(content.binary()).asReadOnlyBuffer(), content.filename(), partitionOptions,
                    pages, budget, progress, collected, null, sha, false, null);
            return rangeResult(progress, collected, content.warnings(), sha, content.bytes(), content.filename(),
                    optionsDigest(partitionOptions), pages);
        } finally {
            progress.close();
        }
    }

    @Override
    public PdfPreparedDocument prepare(final DocumentSource source, final PartitionOptions partitionOptions)
            throws IOException {
        final var content = load(source, partitionOptions);
        final String sha = sha256(content.binary());
        final var input = java.nio.ByteBuffer.wrap(content.binary()).asReadOnlyBuffer();
        final Inspection inspection = translated(() -> {
            try (final var documents = new PdfPageDocuments(input, content.filename(), passwords);
                    final var document = documents.open()) {
                validateDocument(document);
                return inspect(document, input, sha);
            }
        });
        return new DefaultPdfPreparedDocument(content.binary(), content.filename(), partitionOptions,
                optionsDigest(partitionOptions), sha, content.bytes(), content.warnings(), inspection);
    }

    @Override
    public PdfRangeResult partitionRange(final PdfPreparedDocument document, final PdfPageRange pages,
            final PdfBudget budget, final PartitionProgressListener listener) throws IOException {
        final var prepared = prepared(document, pages, budget);
        final var progress = PartitionProgressTracker.of(listener);
        try {
            progress.enter(PartitionProgress.Phase.PREPARING);
            progress.enter(PartitionProgress.Phase.EXTRACTING);
            final var collected = new RangePages();
            extract(prepared.input(), prepared.filename(), prepared.options(), pages, budget, progress, collected,
                    prepared.inspection(), prepared.sourceSha256(), false, null);
            return rangeResult(progress, collected, prepared.loaderWarnings(), prepared.sourceSha256(),
                    prepared.sourceBytes(), prepared.filename(), prepared.optionsDigest(), pages);
        } finally {
            progress.close();
        }
    }

    @Override
    public PdfRangeCount countRange(final PdfPreparedDocument document, final PdfPageRange pages,
            final PdfBudget budget, final PartitionProgressListener listener) throws IOException {
        final var prepared = prepared(document, pages, budget);
        final var progress = PartitionProgressTracker.of(listener);
        try {
            progress.enter(PartitionProgress.Phase.PREPARING);
            progress.enter(PartitionProgress.Phase.EXTRACTING);
            final var collected = new RangePages();
            extract(prepared.input(), prepared.filename(), prepared.options(), pages, budget, progress, collected,
                    prepared.inspection(), prepared.sourceSha256(), true, null);
            ElementCollector.checkInterrupted();
            progress.enter(PartitionProgress.Phase.ASSEMBLING);
            final var result = new PdfRangeCount(pages, collected.documentPages, collected.completed);
            progress.enter(PartitionProgress.Phase.DONE);
            return result;
        } finally {
            progress.close();
        }
    }

    /**
     * Accepts only documents prepared by an identically configured partitioner.
     * @param document candidate
     * @param pages nonnull range
     * @param budget nonnull budget
     * @return hidden prepared document
     * @throws IOException if interrupted
     */
    private DefaultPdfPreparedDocument prepared(
            final PdfPreparedDocument document, final PdfPageRange pages, final PdfBudget budget) throws IOException {
        java.util.Objects.requireNonNull(document, "document");
        java.util.Objects.requireNonNull(pages, "pages");
        java.util.Objects.requireNonNull(budget, "budget");
        if (!(document instanceof final DefaultPdfPreparedDocument prepared)
                || !prepared.optionsDigest().equals(optionsDigest(prepared.options()))) {
            throw new IllegalArgumentException("The PDF document was prepared by a differently configured partitioner");
        }
        ElementCollector.checkInterrupted();
        return prepared;
    }

    /**
     * Loads a local PDF source for a range call.
     * @param source local source
     * @param partitionOptions document options
     * @return loaded binary content
     * @throws IOException if loading fails or the content is not a PDF
     */
    private SourceLoader.Content load(final DocumentSource source, final PartitionOptions partitionOptions)
            throws IOException {
        java.util.Objects.requireNonNull(source, "source");
        java.util.Objects.requireNonNull(partitionOptions, "options");
        if (source instanceof DocumentSource.Remote) {
            throw new IllegalArgumentException("PDF page ranges need a local document source");
        }
        ElementCollector.checkInterrupted();
        final var content = new SourceLoader().load(source, partitionOptions);
        if (content.format() != DocumentFormat.PDF) {
            throw new PartitionException(
                    PartitionException.Code.UNSUPPORTED_FORMAT, "PDF page ranges need a PDF document");
        }
        NestedTableSupport.check(this, partitionOptions);
        return content;
    }

    /**
     * Builds a range result from collected pages and reports the final phases.
     * @param progress call progress
     * @param collected collected pages
     * @param loaderWarnings warnings from loading, before the document warnings
     * @param sha source digest
     * @param bytes source size
     * @param filename optional filename
     * @param digest options digest
     * @param pages covered pages
     * @return result
     * @throws IOException if interrupted
     */
    private static PdfRangeResult rangeResult(final PartitionProgressTracker progress, final RangePages collected,
            final java.util.List<PartitionResult.Warning> loaderWarnings, final String sha, final int bytes,
            final String filename, final String digest, final PdfPageRange pages) throws IOException {
        ElementCollector.checkInterrupted();
        progress.enter(PartitionProgress.Phase.ASSEMBLING);
        final var warnings = new java.util.ArrayList<>(loaderWarnings);
        warnings.addAll(collected.warnings);
        final var result = new PdfRangeResult(PdfRangeResult.FORMAT_VERSION, PdfRangeResult.currentLibraryVersion(),
                sha, bytes, filename, digest, collected.documentPages, pages, warnings,
                PdfRangeResult.ID_REFERENCE_FIELDS, collected.completed, collected.first, collected.last);
        progress.enter(PartitionProgress.Phase.DONE);
        return result;
    }

    /** Collects the pages of one range call. */
    private static final class RangePages implements PdfRangeCollector.Listener {
        /** Document page count. */
        private int documentPages;
        /** Document warnings from the partitioner. */
        private java.util.List<PartitionResult.Warning> warnings;
        /** Completed pages. */
        private final java.util.List<PdfRangeResult.Page> completed = new java.util.ArrayList<>();
        /** Evidence of the first page. */
        private PdfTableBoundary first;
        /** Evidence of the latest page. */
        private PdfTableBoundary last;

        @Override
        public void begin(final int count, final java.util.List<PartitionResult.Warning> documentWarnings) {
            documentPages = count;
            warnings = documentWarnings;
        }

        @Override
        public void page(final PdfRangeResult.Page page, final PdfTableBoundary boundary) {
            if (completed.isEmpty()) {
                first = boundary;
            }
            completed.add(page);
            last = boundary;
        }
    }

    /**
     * Results of the document-wide passes, computed once per call or once per prepared document.
     * @param documentPages physical page count
     * @param warnings document warnings from the partitioner, in order
     * @param tagged source tables and paragraphs
     * @param paragraphIdentity identity of cross-page paragraph fragments, or null
     */
    record Inspection(int documentPages, java.util.List<PartitionResult.Warning> warnings,
            PdfTaggedStructure.Result tagged, String paragraphIdentity) {}

    /**
     * Runs the document-wide passes after the document checks: the limitation warning, the source-tag pass, the
     * paragraph identity and the output warnings, in the historical order.
     * @param document opened PDF
     * @param input source bytes
     * @param sha source digest if already computed, otherwise null
     * @return inspection
     * @throws IOException if tag traversal, cancellation or a paragraph budget fails
     */
    private Inspection inspect(final PDDocument document, final java.nio.ByteBuffer input, final String sha)
            throws IOException {
        final var warnings = new java.util.ArrayList<PartitionResult.Warning>();
        warnings.add(new PartitionResult.Warning(PartitionResult.WarningCode.PDF_EXTRACTION_LIMITATION, limitation()));
        final var tagged = readTaggedStructure(document, warnings);
        ElementCollector.checkInterrupted();
        final String paragraphIdentity =
                paragraphOptions != null
                                && paragraphOptions.pageBoundary()
                                           == PdfTaggedParagraphOptions.PageBoundary.PAGE_FRAGMENTS
                        ? sha != null ? sha : PdfParagraphIdentity.document(input)
                        : null;
        ElementCollector.checkInterrupted();
        if (options.tableContinuation() != PdfOptions.TableContinuation.NONE) {
            warnings.add(new PartitionResult.Warning(PartitionResult.WarningCode.PDF_EXTRACTION_LIMITATION,
                    "Table continuation links use repeated-header geometry heuristics, not semantic identity"));
        }
        if (ocrOptions != null && ocrOptions.readingOrder() == PdfOcrOptions.ReadingOrder.GEOMETRIC) {
            warnings.add(new PartitionResult.Warning(PartitionResult.WarningCode.PDF_EXTRACTION_LIMITATION,
                    "Page top-left native/OCR order is geometric, not logical article order"));
        }
        return new Inspection(document.getNumberOfPages(), java.util.List.copyOf(warnings), tagged, paragraphIdentity);
    }

    /**
     * Shared extraction of a page range: document checks and passes (unless prepared), then pages in batches of the
     * common pool's parallelism plus one, extracted concurrently and replayed in page order into the range collector.
     * @param input read-only PDF bytes
     * @param filename optional source filename
     * @param partitionOptions document options
     * @param requested pages to extract, or null for every page
     * @param budget call budget
     * @param progress progress sink; units are the range's pages
     * @param listener receives the document header, then each completed page in order
     * @param prepared document-wide results computed earlier, or null to compute them now
     * @param sha source digest if already computed, otherwise null
     * @param counting whether elements are charged and dropped instead of retained
     * @param direct whole-document context that receives elements with final IDs, or null for range results
     * @throws IOException if parsing, permission checks, cancellation or extraction limits fail
     */
    private void extract(final java.nio.ByteBuffer input, final String filename,
            final PartitionOptions partitionOptions, final PdfPageRange requested, final PdfBudget budget,
            final PartitionProgressReporter progress, final PdfRangeCollector.Listener listener,
            final Inspection prepared, final String sha, final boolean counting, final PartitionContext direct)
            throws IOException {
        translated(() -> {
            try (final var documents = new PdfPageDocuments(input, filename, passwords)) {
                final Inspection inspection;
                if (prepared == null) {
                    try (final var document = documents.open()) {
                        validateDocument(document);
                        begin(document.getNumberOfPages(), requested, progress);
                        inspection = inspect(document, input, sha);
                    }
                } else {
                    begin(prepared.documentPages(), requested, progress);
                    inspection = prepared;
                }
                final int first = requested == null ? 0 : requested.first() - 1;
                final int end = requested == null ? inspection.documentPages() : requested.last();
                // These budgets and buffers belong to the whole call, not to individual pages.
                final var usage = new PdfBudgetUsage(budget);
                final var output =
                        new PdfPageOutput(new PdfRangeCollector(filename, partitionOptions, usage, listener, direct),
                                options.tableContinuation(), readingOrder(), usage, counting);
                listener.begin(inspection.documentPages(), inspection.warnings());
                final var indices = java.util.stream.IntStream.range(first, end).boxed().toList();
                // Batches prevent the ordered writer from retaining an entire document of completed pages.
                final int batchSize = Math.max(1, java.util.concurrent.ForkJoinPool.getCommonPoolParallelism() + 1);
                try (final var execution = new PdfPageExecution()) {
                    for (int start = 0; start < indices.size(); start += batchSize) {
                        ElementCollector.checkInterrupted();
                        final var batch = indices.subList(start, Math.min(indices.size(), start + batchSize));
                        final var task = java.util.concurrent.CompletableFuture.supplyAsync(
                                ()
                                        -> batch.parallelStream()
                                                .map(index -> execution.run(() -> {
                                                    final var pageOutput = output.pageBuffer(index + 1);
                                                    partitionPage(documents.page(), index,
                                                            new PdfImageBudget(options, usage, index + 1), pageOutput,
                                                            inspection.tagged(), inspection.paragraphIdentity());
                                                    return pageOutput;
                                                }))
                                                .toList(),
                                java.util.concurrent.ForkJoinPool.commonPool());
                        final var completed = execution.await(task);
                        execution.check();
                        for (int offset = 0; offset < completed.size(); offset++) {
                            ElementCollector.checkInterrupted();
                            completed.get(offset).replay(output, first + start + offset + 1);
                            usage.pageCompleted(first + start + offset + 1);
                            progress.unitCompleted(start + offset);
                        }
                    }
                }
            }
            return null;
        });
    }

    /**
     * Checks the requested range against the document and declares the progress units.
     * @param documentPages physical page count
     * @param requested pages, or null for all
     * @param progress progress sink
     * @throws PartitionException if the range exceeds the document
     */
    private static void begin(final int documentPages, final PdfPageRange requested,
            final PartitionProgressReporter progress) throws PartitionException {
        if (requested != null && requested.last() > documentPages) {
            throw new PartitionException(
                    PartitionException.Code.UNSUPPORTED_OPTION, "PDF page range exceeds the document's pages");
        }
        // A page is the unit of PDF progress; completion follows all output for that page.
        progress.beginUnits("pages", requested == null ? documentPages : requested.pageCount());
    }

    /** Checked PDF work. */
    @FunctionalInterface
    private interface PdfWork<T> {
        /**
         * Runs the work.
         * @return result
         * @throws IOException if it fails
         */
        T run() throws IOException;
    }

    /**
     * Runs PDF work and maps parser failures to the library's typed failures, while budget exceptions, assembly
     * invariant violations, typed failures and cancellation pass through unchanged.
     * @param work PDF work
     * @param <T> result type
     * @return result
     * @throws IOException if the work fails
     */
    private static <T> T translated(final PdfWork<T> work) throws IOException {
        try {
            return work.run();
        } catch (final PdfBudgetUsage.Failure failure) {
            throw failure.original();
        } catch (final PdfRangeAssembly.InvalidRange failure) {
            throw failure;
        } catch (final PdfTraversalFailure failure) {
            throw failure.checked();
        } catch (final PartitionException | InterruptedIOException failure) {
            throw failure;
        } catch (final InvalidPasswordException failure) {
            throw new PartitionException(
                    PartitionException.Code.UNSUPPORTED_OPTION, "PDF password is missing or incorrect", failure);
        } catch (final IOException | IllegalArgumentException failure) {
            throw new PartitionException(
                    PartitionException.Code.MALFORMED_DOCUMENT, "PDF could not be parsed or decoded", failure);
        }
    }

    /**
     * Computes the SHA-256 of the complete source.
     * @param bytes source bytes
     * @return lowercase hexadecimal digest
     */
    private static String sha256(final byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (final java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /**
     * Digests everything that selects extraction behaviour: the document options and this strategy's policies.
     * Providers contribute their class name (or only their presence for lambdas and other hidden classes), not their
     * configuration; callers that vary providers must distinguish them separately.
     * @param partitionOptions document options
     * @return lowercase hexadecimal SHA-256
     */
    private String optionsDigest(final PartitionOptions partitionOptions) {
        final String description = String.join("\n", "docweave-pdf-range-options-v1", String.valueOf(partitionOptions),
                String.valueOf(options), String.valueOf(ocrOptions), String.valueOf(nativeLayoutOptions),
                String.valueOf(nativeTablesFirst), String.valueOf(taggedCellTextJoin), String.valueOf(paragraphOptions),
                String.valueOf(taggedGeometry), String.valueOf(passwords != null), provider(ocrProvider),
                provider(tableLayout), provider(orientation), provider(cellProvider), provider(nativeLayout));
        return sha256(description.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Describes a provider without depending on unstable hidden-class names.
     * @param provider optional provider
     * @return none, present, or the class name
     */
    private static String provider(final Object provider) {
        if (provider == null) {
            return "none";
        }
        final var type = provider.getClass();
        return type.isHidden() || type.isSynthetic() || type.isAnonymousClass() ? "present" : type.getName();
    }

    /**
     * Checks extraction permission and document size before any page is processed.
     * @param document opened PDF
     * @throws PartitionException if extraction is prohibited or the page limit is exceeded
     * @throws InterruptedIOException if interrupted
     */
    private void validateDocument(final PDDocument document) throws IOException {
        ElementCollector.checkInterrupted();
        if (!document.getCurrentAccessPermission().canExtractContent()) {
            throw new PartitionException(
                    PartitionException.Code.UNSUPPORTED_OPTION, "PDF permissions prohibit content extraction");
        }
        if (document.getNumberOfPages() > options.maxPages()) {
            throw new PartitionException(PartitionException.Code.INPUT_TOO_LARGE, "PDF exceeds maxPages");
        }
    }

    /**
     * Reads document-wide source tags and validates paragraph budgets before emitting any page.
     * Unsupported tags retain their existing native fallback and warnings.
     * @param document opened PDF
     * @param warnings document warnings, in order
     * @return source tables and paragraphs, or an empty result when tags are not requested
     * @throws IOException if tag traversal, cancellation or a paragraph budget fails
     */
    private PdfTaggedStructure.Result readTaggedStructure(
            final PDDocument document, final java.util.List<PartitionResult.Warning> warnings) throws IOException {
        final var tagged =
                taggedCellTextJoin == null && paragraphOptions == null
                        ? new PdfTaggedStructure.Result(java.util.List.of(), 0)
                        : PdfTaggedStructure.read(document, options.tableLimits(), taggedCellTextJoin != null,
                                  paragraphOptions != null,
                                  paragraphOptions != null
                                          && paragraphOptions.pageBoundary()
                                                     == PdfTaggedParagraphOptions.PageBoundary.PAGE_FRAGMENTS);
        if (tagged.rejected() > 0) {
            warnings.add(new PartitionResult.Warning(PartitionResult.WarningCode.PDF_EXTRACTION_LIMITATION,
                    "Unsupported or inconsistent source-tagged tables retain native fallback"));
        }
        if (tagged.rejectedParagraphs() > 0) {
            warnings.add(new PartitionResult.Warning(PartitionResult.WarningCode.PDF_EXTRACTION_LIMITATION,
                    "Unsupported source paragraphs retain ordinary native fallback"));
        }
        if (paragraphOptions != null) {
            final var counts = new java.util.HashMap<Integer, Integer>();
            for (final var paragraph : tagged.paragraphs()) {
                ElementCollector.checkInterrupted();
                if (counts.merge(paragraph.page(), 1, Integer::sum) > paragraphOptions.maxParagraphsPerPage()) {
                    throw new PartitionException(
                            PartitionException.Code.LAYOUT_LIMIT, "Source paragraphs exceed maxParagraphsPerPage");
                }
            }
        }
        return tagged;
    }

    /**
     * Returns the reading order that decides page buffering.
     * @return selected OCR reading order, or source order without OCR options
     */
    private PdfOcrOptions.ReadingOrder readingOrder() {
        return ocrOptions == null ? PdfOcrOptions.ReadingOrder.SOURCE_ORDER : ocrOptions.readingOrder();
    }

    /**
     * Creates the historical document budgets for one call of this strategy.
     * @param partitionOptions document options supplying maxElements
     * @return new call-confined thread-safe budget
     */
    @Override
    public PdfBudget standardBudget(final PartitionOptions partitionOptions) {
        java.util.Objects.requireNonNull(partitionOptions, "partitionOptions");
        return new StandardPdfBudget(partitionOptions.maxElements(), options.maxImageBytes(),
                options.tableContinuation() != PdfOptions.TableContinuation.NONE
                        || readingOrder() == PdfOcrOptions.ReadingOrder.GEOMETRIC);
    }

    /**
     * Processes one isolated page, choosing OCR ownership or native text before optional mixed-page OCR.
     * The caller replays its buffered output in page order before recording progress.
     * @param document opened PDF, confined to this partition call
     * @param index zero-based page index
     * @param images page image extraction charging the shared call budget
     * @param output shared ordered page output
     * @param tagged document-wide source tags
     * @param paragraphDocumentIdentity optional identity for cross-page paragraph fragments
     * @throws IOException if page extraction, rendering, OCR, limits or cancellation fails
     */
    private void partitionPage(final PDDocument document, final int index, final PdfImageBudget images,
            final PdfPageOutput output, final PdfTaggedStructure.Result tagged, final String paragraphDocumentIdentity)
            throws IOException {
        ElementCollector.checkInterrupted();
        final var page = document.getPage(index);
        final var geometry = new PdfPageGeometry(page);
        final var graphics = new PdfPageGraphics(page, options, images, geometry);
        graphics.processPage(page);
        if (graphics.geometryDropped()) {
            output.warn(PartitionResult.WarningCode.PDF_EXTRACTION_LIMITATION,
                    "Page " + (index + 1) + " has more than " + PdfPageGraphics.MAX_RETAINED_SEGMENTS
                            + " line segments; it was read without rule-based table detection, and image clip bounds "
                            + "on it may be incomplete");
        }
        if (ocrProvider != null && ocrOptions.selection() == PdfOcrOptions.Selection.ALL_PAGES) {
            emitOcrPage(document, index, geometry, graphics, output);
            return;
        }
        final var text = new PdfPageText(options, taggedCellTextJoin != null || paragraphOptions != null);
        text.setStartPage(index + 1);
        text.setEndPage(index + 1);
        text.getText(document);
        final var angled = text.angledLines(geometry);
        final boolean nativeText =
                !angled.isEmpty() || text.glyphs().stream().anyMatch(glyph -> !glyph.text().isBlank());
        final boolean mixed = ocrProvider != null && nativeText
                              && ocrOptions.selection() == PdfOcrOptions.Selection.IMAGE_REGIONS
                              && !graphics.images().isEmpty();
        final boolean recognize = ocrProvider != null && (!nativeText || mixed);
        if (nativeLayout != null && nativeText) {
            emitProviderPage(document, index, text, geometry, graphics, output, tagged, angled);
        } else if ((taggedCellTextJoin != null || paragraphOptions != null) && nativeText) {
            emitTaggedPage(index, text, geometry, graphics, output, tagged, paragraphDocumentIdentity, angled);
        } else {
            new PdfPageLayout(output, index + 1, geometry, options, java.util.List.of(), graphics.rowBands())
                    .emit(text.glyphs(), graphics.segments(), graphics.images(), recognize, angled);
        }
        if (recognize) {
            PdfPageOcr.emit(document, index, geometry, output, ocrOptions, ocrProvider, options.tableLimits(),
                    options.includeTableCellCoordinates(), tableLayout, orientation, cellProvider,
                    mixed ? new PdfOcrRegions(graphics.images(), text.glyphs(), geometry, ocrOptions,
                                    angled.stream().map(PdfLayoutItem::box).toList())
                          : null);
        }
        output.finishPage(index + 1);
    }

    /**
     * Emits original images and OCR-owned text; searchable native text is deliberately not emitted again.
     * @param document opened PDF
     * @param index zero-based page index
     * @param geometry displayed page geometry
     * @param graphics extracted original images
     * @param output ordered emission sink
     * @throws IOException if recognition, layout or emission fails
     */
    private void emitOcrPage(final PDDocument document, final int index, final PdfPageGeometry geometry,
            final PdfPageGraphics graphics, final PdfPageOutput output) throws IOException {
        // OCR owns all text on this path, including searchable layers; emit only original images first.
        new PdfPageLayout(output, index + 1, geometry, options)
                .emit(java.util.List.of(), java.util.List.of(), graphics.images(), true);
        PdfPageOcr.emit(document, index, geometry, output, ocrOptions, ocrProvider, options.tableLimits(),
                options.includeTableCellCoordinates(), tableLayout, orientation, cellProvider, null);
        output.finishPage(index + 1);
    }

    /**
     * Selects native/provider table ownership and emits the remaining native text once.
     * Native-first selection reserves accepted tables before asking the provider for disjoint geometry.
     * @param document opened PDF
     * @param index zero-based page index
     * @param text extracted native glyphs
     * @param geometry displayed page geometry
     * @param graphics page images, rules and row bands
     * @param output ordered emission sink
     * @param tagged document-wide source tables
     * @param angled arbitrary-angle text items
     * @throws IOException if provider layout, table budgets or emission fails
     */
    private void emitProviderPage(final PDDocument document, final int index, final PdfPageText text,
            final PdfPageGeometry geometry, final PdfPageGraphics graphics, final PdfPageOutput output,
            final PdfTaggedStructure.Result tagged, final java.util.List<PdfLayoutItem> angled) throws IOException {
        if (!angled.isEmpty()) {
            throw new PartitionException(PartitionException.Code.UNSUPPORTED_OPTION,
                    "Native provider layout does not support arbitrary-angle text");
        }
        final PdfNativeProviderTables.Result tables;
        final var selected = new java.util.ArrayList<PdfLayoutItem>();
        if (nativeTablesFirst) {
            final var work = new PdfTableWork();
            final var taggedStage = taggedCellTextJoin == null
                                            ? new PdfTaggedTables.Result(java.util.List.of(), text.glyphs())
                                            : PdfTaggedTables.extract(tagged.tables(), index, text, geometry, options,
                                                      taggedCellTextJoin, work, taggedGeometry);
            selected.addAll(taggedStage.tables());
            final var reserved = taggedStage.tables().stream().map(PdfLayoutItem::box).toList();
            final var nativeStage =
                    new PdfPageLayout(output, index + 1, geometry, options, reserved, graphics.rowBands())
                            .stageTables(taggedStage.remaining(), graphics.segments(), graphics.images(), angled, work);
            selected.addAll(nativeStage.tables());
            final var preferred = new PdfPageLayout.NativeStage(
                    java.util.List.copyOf(selected), nativeStage.remaining(), work.nativeAnchors());
            tables = PdfNativeProviderTables.extract(document, index, geometry, nativeStage.remaining(), options,
                    nativeLayoutOptions, nativeLayout, preferred, work);
        } else {
            tables = PdfNativeProviderTables.extract(
                    document, index, geometry, text.glyphs(), options, nativeLayoutOptions, nativeLayout);
        }
        selected.addAll(tables.tables());
        final var staged =
                nativeTablesFirst ? selected.stream()
                                            .map(item -> {
                                                final var metadata = new java.util.LinkedHashMap<>(item.metadata());
                                                metadata.put("pdf_table_selection_policy",
                                                        taggedCellTextJoin == null ? "native_first_disjoint_v1"
                                                                                   : "tagged_native_first_disjoint_v1");
                                                return new PdfLayoutItem(item.type(), item.text(), item.box(), metadata,
                                                        item.signature());
                                            })
                                            .toList()
                                  : selected;
        // Prepared ownership must not be recognized again by native table heuristics.
        new PdfPageLayout(output, index + 1, geometry, options.withTableDetection(PdfOptions.TableDetection.RULED_ONLY))
                .emit(tables.remaining(), java.util.List.of(), graphics.images(), false, angled, staged);
    }

    /**
     * Gives supported source-tagged tables and paragraphs ownership before geometric fallback.
     * @param index zero-based page index
     * @param text extracted native glyphs
     * @param geometry displayed page geometry
     * @param graphics page images, rules and row bands
     * @param output ordered emission sink
     * @param tagged document-wide source tables and paragraphs
     * @param paragraphDocumentIdentity optional identity for cross-page paragraph fragments
     * @param angled arbitrary-angle text items
     * @throws IOException if tagged extraction, table budgets or emission fails
     */
    private void emitTaggedPage(final int index, final PdfPageText text, final PdfPageGeometry geometry,
            final PdfPageGraphics graphics, final PdfPageOutput output, final PdfTaggedStructure.Result tagged,
            final String paragraphDocumentIdentity, final java.util.List<PdfLayoutItem> angled) throws IOException {
        final var work = new PdfTableWork();
        final var tables = PdfTaggedTables.extract(tagged.tables(), index, text, geometry, options,
                taggedCellTextJoin == null ? PdfNativeLayoutOptions.CellTextJoin.SPACE_SEPARATED : taggedCellTextJoin,
                work, taggedGeometry);
        final var paragraphs =
                paragraphOptions == null
                        ? new PdfTaggedParagraphs.Result(java.util.List.of(), tables.remaining())
                        : PdfTaggedParagraphs.extract(tagged.paragraphs(), index, text, tables.remaining(), geometry,
                                  paragraphOptions, work, paragraphDocumentIdentity);
        final var prepared = new java.util.ArrayList<PdfLayoutItem>(tables.tables());
        prepared.addAll(paragraphs.paragraphs());
        new PdfPageLayout(output, index + 1, geometry, options, prepared.stream().map(PdfLayoutItem::box).toList(),
                graphics.rowBands())
                .emit(paragraphs.remaining(), graphics.segments(), graphics.images(), false, angled, prepared, work);
    }

    /**
     * Describes the selected compatibility boundary without promising general OCR/table fidelity.
     * @return diagnostic without document text
     */
    private String limitation() {
        if (paragraphOptions != null) {
            return "Source paragraphs require unique single-page native glyph ownership; unsupported content retains geometric native fallback without general logical page-order guarantees";
        }
        if (taggedCellTextJoin != null) {
            return "Source-tagged tables require complete single-page MCID ownership; unsupported tags use native fallback and no OCR is performed";
        }
        if (nativeTablesFirst) {
            return "Accepted native tables take precedence over overlapping provider tables; geometry is heuristic and no OCR is performed";
        }
        if (nativeLayout != null) {
            return "Native text uses explicit provider topology; layout accuracy is not guaranteed and no OCR is performed";
        }
        if (ocrProvider == null) {
            return "Native PDF extraction has no OCR or full table/layout fidelity guarantee";
        }
        if (ocrOptions.selection() == PdfOcrOptions.Selection.ALL_PAGES
                && ocrOptions.tableDetection() == PdfOcrOptions.TableDetection.NONE) {
            return "Every page uses OCR instead of native text; source images are retained and recognition/layout accuracy is not guaranteed";
        }
        if (ocrOptions.tableDetection() == PdfOcrOptions.TableDetection.PROVIDER_LAYOUT
                || ocrOptions.tableDetection() == PdfOcrOptions.TableDetection.PROVIDER_LAYOUT_CELLS) {
            return "OCR tables use explicit provider topology and actual words; model layout, text and semantic accuracy are not guaranteed";
        }
        if (ocrOptions.tableDetection() == PdfOcrOptions.TableDetection.ALIGNED_WORDS) {
            return "OCR word tables use uniform aligned numeric text heuristics; no merges, multiline cells or general scanned layout guarantee";
        }
        if (ocrOptions.tableDetection() == PdfOcrOptions.TableDetection.RULED) {
            return "OCR tables require closed dark axis-aligned rendered grids with rectangular cells; general scanned table/layout fidelity is unsupported";
        }
        return ocrOptions.selection() == PdfOcrOptions.Selection.TEXTLESS_PAGES
                ? "OCR is limited to pages without native text; mixed regions and scanned tables are unsupported"
                : "Mixed-page OCR uses image rectangles with native-text exclusion; scanned tables and semantic region detection are unsupported";
    }
}
