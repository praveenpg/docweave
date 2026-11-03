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
     * Extracts pages without executing scripts, following links or evaluating forms.
     * @param context synchronous binary input and bounded emission sink
     * @throws IOException if parsing, permission checks, cancellation or extraction limits fail
     */
    @Override
    public void partition(final PartitionContext context) throws IOException {
        NestedTableSupport.check(this, context.options());
        try (final var documents = new PdfPageDocuments(context.binaryBuffer(), context.filename(), passwords);
                final var document = documents.open()) {
            validateDocument(document);
            // A page is the unit of PDF progress; completion follows all output for that page.
            final var progress = context.progress();
            progress.beginUnits("pages", document.getNumberOfPages());
            context.warn(PartitionResult.WarningCode.PDF_EXTRACTION_LIMITATION, limitation());
            final var tagged = readTaggedStructure(document, context);
            ElementCollector.checkInterrupted();
            final String paragraphDocumentIdentity =
                    paragraphOptions != null
                                    && paragraphOptions.pageBoundary()
                                               == PdfTaggedParagraphOptions.PageBoundary.PAGE_FRAGMENTS
                            ? PdfParagraphIdentity.document(context.binaryBuffer())
                            : null;
            ElementCollector.checkInterrupted();
            // These budgets and buffers belong to the whole call, not to individual pages.
            final var images = new PdfImageBudget(options);
            final var output = createOutput(context);
            final var indices = java.util.stream.IntStream.range(0, document.getNumberOfPages()).boxed().toList();
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
                                                final var pageOutput = output.pageBuffer();
                                                partitionPage(documents.page(), index, images, pageOutput, tagged,
                                                        paragraphDocumentIdentity);
                                                return pageOutput;
                                            }))
                                            .toList(),
                            java.util.concurrent.ForkJoinPool.commonPool());
                    final var completed = execution.await(task);
                    execution.check();
                    for (int offset = 0; offset < completed.size(); offset++) {
                        ElementCollector.checkInterrupted();
                        completed.get(offset).replay(output, start + offset + 1);
                        progress.unitCompleted(start + offset);
                    }
                }
            }
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
     * @param context warning sink and extraction settings
     * @return source tables and paragraphs, or an empty result when tags are not requested
     * @throws IOException if tag traversal, cancellation or a paragraph budget fails
     */
    private PdfTaggedStructure.Result readTaggedStructure(final PDDocument document, final PartitionContext context)
            throws IOException {
        final var tagged =
                taggedCellTextJoin == null && paragraphOptions == null
                        ? new PdfTaggedStructure.Result(java.util.List.of(), 0)
                        : PdfTaggedStructure.read(document, options.tableLimits(), taggedCellTextJoin != null,
                                  paragraphOptions != null,
                                  paragraphOptions != null
                                          && paragraphOptions.pageBoundary()
                                                     == PdfTaggedParagraphOptions.PageBoundary.PAGE_FRAGMENTS);
        if (tagged.rejected() > 0) {
            context.warn(PartitionResult.WarningCode.PDF_EXTRACTION_LIMITATION,
                    "Unsupported or inconsistent source-tagged tables retain native fallback");
        }
        if (tagged.rejectedParagraphs() > 0) {
            context.warn(PartitionResult.WarningCode.PDF_EXTRACTION_LIMITATION,
                    "Unsupported source paragraphs retain ordinary native fallback");
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
     * Creates the call-wide ordered output buffer and reports selected ordering limitations.
     * @param context emission and warning sink
     * @return output retaining cross-page table continuation state
     */
    private PdfPageOutput createOutput(final PartitionContext context) {
        final var output = new PdfPageOutput(context, options.tableContinuation(),
                ocrOptions == null ? PdfOcrOptions.ReadingOrder.SOURCE_ORDER : ocrOptions.readingOrder());
        if (output.continuations()) {
            context.warn(PartitionResult.WarningCode.PDF_EXTRACTION_LIMITATION,
                    "Table continuation links use repeated-header geometry heuristics, not semantic identity");
        }
        if (ocrOptions != null && ocrOptions.readingOrder() == PdfOcrOptions.ReadingOrder.GEOMETRIC) {
            context.warn(PartitionResult.WarningCode.PDF_EXTRACTION_LIMITATION,
                    "Page top-left native/OCR order is geometric, not logical article order");
        }
        return output;
    }

    /**
     * Processes one isolated page, choosing OCR ownership or native text before optional mixed-page OCR.
     * The caller replays its buffered output in page order before recording progress.
     * @param document opened PDF, confined to this partition call
     * @param index zero-based page index
     * @param images shared document image budget
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
