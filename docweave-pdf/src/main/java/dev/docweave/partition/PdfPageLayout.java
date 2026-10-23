package dev.docweave.partition;

import dev.docweave.core.ElementType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Conservative geometry-to-elements policy; unsupported grids retain their text as ordinary text. */
final class PdfPageLayout {
    /** Source emission context. */
    private final PdfPageOutput context;
    /** One-based source page index. */
    private final int page;
    /** Crop dimensions used by all coordinates. */
    private final PdfPageGeometry geometry;
    /** Explicit limits. */
    private final PdfOptions options;

    /** Source-tagged envelopes excluded before any fallback table claims native glyphs. */
    private final List<PdfLayoutItem.Bounds> excluded;
    /** Optional original painted row evidence, separate from strict-grid rules. */
    private final List<FilledPdfRowBand> rowBands;

    /** Optional immutable cell-coordinate writer; null preserves historical output/allocation. */
    private final PdfTableCells cellMetadata;

    /** Optional continuation evidence frame, independent of emitted cell geometry. */
    private final PdfTableCells continuationFrame;

    /**
     * Creates a page-confined layout policy.
     * @param context output sink
     * @param page one-based index
     * @param geometry shared displayed crop/rotation frame
     * @param options immutable budgets
     */
    PdfPageLayout(
            final PdfPageOutput context, final int page, final PdfPageGeometry geometry, final PdfOptions options) {
        this(context, page, geometry, options, List.of());
    }

    /**
     * Reserves source-authored tagged regions before native fallback ownership.
     * @param context output sink
     * @param page one-based page
     * @param geometry original displayed frame
     * @param options native fallback policy
     * @param excluded accepted tagged text envelopes, never model/reference regions
     */
    PdfPageLayout(final PdfPageOutput context, final int page, final PdfPageGeometry geometry, final PdfOptions options,
            final List<PdfLayoutItem.Bounds> excluded) {
        this(context, page, geometry, options, excluded, List.of());
    }

    /**
     * Preserves explicit source row paint independently of native grid segments.
     * @param context output sink
     * @param page one-based original page
     * @param geometry displayed source frame
     * @param options explicit recognition policy
     * @param excluded previously owned source regions
     * @param rowBands original opaque pale rectangular paints
     */
    PdfPageLayout(final PdfPageOutput context, final int page, final PdfPageGeometry geometry, final PdfOptions options,
            final List<PdfLayoutItem.Bounds> excluded, final List<FilledPdfRowBand> rowBands) {
        this.rowBands = List.copyOf(rowBands);
        this.excluded = List.copyOf(excluded);
        this.context = context;
        this.page = page;
        this.geometry = geometry;
        this.options = options;
        final var frame = options.includeTableCellCoordinates() || context.continuations()
                                  ? new PdfTableCells(geometry, 1, 1)
                                  : null;
        cellMetadata = options.includeTableCellCoordinates() ? frame : null;
        continuationFrame = context.continuations() ? frame : null;
    }

    /**
     * Emits one page without duplicating table-owned glyphs as paragraph text.
     * @param glyphs sorted source glyphs
     * @param segments stroked source borders
     * @param images raster occurrences
     * @param ocrSelected whether the caller will recognize this page after native emission
     * @throws IOException if limits, cancellation or emission fails
     */
    void emit(final List<PdfPageText.Glyph> glyphs, final List<PdfPageGraphics.Segment> segments,
            final List<PdfPageGraphics.Image> images, final boolean ocrSelected) throws IOException {
        emit(glyphs, segments, images, ocrSelected, List.of());
    }

    /**
     * Emits a page with projected arbitrary-angle lines as conservative recognition barriers.
     * @param glyphs quarter-turn source glyphs
     * @param segments displayed painted borders
     * @param images displayed raster occurrences
     * @param ocrSelected whether OCR will follow native emission
     * @param angled projected arbitrary-angle source lines
     * @throws IOException if resource limits or cancellation prevent emission
     */
    void emit(final List<PdfPageText.Glyph> glyphs, final List<PdfPageGraphics.Segment> segments,
            final List<PdfPageGraphics.Image> images, final boolean ocrSelected, final List<PdfLayoutItem> angled)
            throws IOException {
        emit(glyphs, segments, images, ocrSelected, angled, List.of());
    }

    /**
     * Emits prevalidated provider tables alongside only their unclaimed native glyphs and source images.
     * @param glyphs unclaimed native positions
     * @param segments rules for the selected ordinary strategy; empty on the explicit provider path
     * @param images unchanged source raster placements
     * @param ocrSelected whether OCR will follow
     * @param angled projected ordinary angled lines
     * @param provided staged tables with completed native ownership
     * @throws IOException if resource, cancellation or emission checks fail
     */
    void emit(final List<PdfPageText.Glyph> glyphs, final List<PdfPageGraphics.Segment> segments,
            final List<PdfPageGraphics.Image> images, final boolean ocrSelected, final List<PdfLayoutItem> angled,
            final List<PdfLayoutItem> provided) throws IOException {
        emit(glyphs, segments, images, ocrSelected, angled, provided, new PdfTableWork());
    }

    /**
     * Shares source-tag/native geometry work during explicit tagged-first emission.
     * @param glyphs exact unclaimed original positions
     * @param segments original painted rules
     * @param images source raster placements
     * @param ocrSelected whether OCR follows native emission
     * @param angled ordinary arbitrary-angle lines
     * @param provided source-owned prepared tables
     * @param work shared source/native page counter
     * @throws IOException if resource/cancellation/emission limits fail
     */
    void emit(final List<PdfPageText.Glyph> glyphs, final List<PdfPageGraphics.Segment> segments,
            final List<PdfPageGraphics.Image> images, final boolean ocrSelected, final List<PdfLayoutItem> angled,
            final List<PdfLayoutItem> provided, final PdfTableWork work) throws IOException {
        final var items = new ArrayList<>(provided);

        final var nativeStage = stageTables(glyphs, segments, images, angled, work);
        items.addAll(nativeStage.tables());
        final var textGlyphs = nativeStage.remaining();
        final var ordinary = ordinaryLines(textGlyphs, work);
        if (angled.isEmpty() && (options.tableDetection().alignedText())) {
            addAlignedTables(items, ordinary, segments, images, work);
        } else {
            items.addAll(ordinary);
        }
        items.addAll(angled);
        for (final var image : images) {
            items.add(new PdfLayoutItem(ElementType.Image, "",
                    new PdfLayoutItem.Bounds(
                            image.x(), image.y(), image.x() + image.width(), image.y() + image.height()),
                    image.metadata()));
        }
        items.sort(Comparator.comparingDouble((PdfLayoutItem item) -> item.box().top())
                        .thenComparingDouble(item -> item.box().left()));
        final List<PdfLayoutItem> ordered;
        if (options.textLayout() == PdfOptions.TextLayout.LINES
                || options.textLayout() == PdfOptions.TextLayout.ORIENTED_LINES) {
            ordered = items;
        } else {
            context.warn(PartitionResult.WarningCode.PDF_EXTRACTION_LIMITATION,
                    "PDF paragraph and column layout use native geometric heuristics, without semantic order guarantees");
            final PdfReadingOrder readingOrder = new GeometricPdfReadingOrder(options.textLayout(),
                    PdfParagraphWraps.select(options.textLayout(), glyphs, geometry, work), options.sideStreams());
            ordered = readingOrder.apply(items);
        }
        final var captioned = PdfFigureCaptionDetection.select(options.figureCaptions()).apply(ordered, work);
        for (final var item : captioned) {
            final var metadata = new LinkedHashMap<>(item.metadata());
            metadata.put("page_number", page);
            if (options.unicodeNormalization() == PdfOptions.UnicodeNormalization.PRESENTATION_FORMS
                    && item.type() != ElementType.Image) {
                metadata.put("pdf_native_unicode_policy", "presentation_forms_nfkc_v1");
            }
            if (options.textRendering() != PdfOptions.TextRendering.ALL && item.type() != ElementType.Image) {
                metadata.put("pdf_native_text_rendering_policy", options.textRendering().provenance());
            }
            if (options.inlineScripts() == PdfOptions.InlineScripts.NEARBY_SMALL_GLYPHS
                    && item.type() != ElementType.Image) {
                metadata.put("pdf_native_inline_script_policy", "nearby_small_glyph_runs_v1");
            }
            geometry.describe(metadata);
            final var box = item.box();
            metadata.put("coordinates", Map.of("system", "PDFUserSpace", "layout_width", (double) geometry.width(),
                                                "layout_height", (double) geometry.height(), "points",
                                                List.of(List.of((double) box.left(), (double) box.top()),
                                                        List.of((double) box.right(), (double) box.top()),
                                                        List.of((double) box.right(), (double) box.bottom()),
                                                        List.of((double) box.left(), (double) box.bottom()))));
            context.emit(item.type(), item.text(), metadata, item.signature());
        }
        if (!ocrSelected && provided.isEmpty() && angled.isEmpty()
                && glyphs.stream().noneMatch(glyph -> !glyph.text().isBlank()) && !images.isEmpty()) {
            context.warn(PartitionResult.WarningCode.PDF_EXTRACTION_LIMITATION,
                    "PDF page " + page + " has images but no native text; OCR was not performed");
        }
    }

    /**
     * Exact accepted native tables and detector-unclaimed glyphs, before any ordinary-text regrouping.
     * @param tables staged native tables with optional continuation signatures
     * @param remaining original unclaimed glyph sequence; never inferred from table envelopes
     * @param anchors accepted native anchor count for combined page budgets
     */
    record NativeStage(List<PdfLayoutItem> tables, List<PdfPageText.Glyph> remaining, long anchors) {}

    /**
     * Stages glyph-owning native strategies without emitting tables or grouping remaining source runs.
     * Run-based aligned modes remain handled by the historical ordinary-layout path, not this composition.
     * @param glyphs original native positions
     * @param segments source rule evidence
     * @param images source image barriers
     * @param angled projected angled lines used by historical native ambiguity checks
     * @param work shared native/provider page work and accepted-anchor counter
     * @return immutable native tables and exact unclaimed positions
     * @throws IOException if table/work limits, cancellation or source geometry checks fail
     */
    NativeStage stageTables(final List<PdfPageText.Glyph> glyphs, final List<PdfPageGraphics.Segment> segments,
            final List<PdfPageGraphics.Image> images, final List<PdfLayoutItem> angled, final PdfTableWork work)
            throws IOException {
        final var items = new ArrayList<PdfLayoutItem>();
        final List<PdfPageGraphics.Segment> tableRules;
        if (options.tableDetection() == PdfOptions.TableDetection.RULED_AND_DOUBLE_BORDERS) {
            context.warn(PartitionResult.WarningCode.PDF_EXTRACTION_LIMITATION,
                    "Double-rule PDF table normalization is heuristic; narrow empty bands may be intentional cells");
            final var barriers = new ArrayList<PdfLayoutItem.Bounds>();
            for (final var glyph : glyphs) {
                work.step();
                barriers.add(geometry.textBounds(new PdfLayoutItem.Bounds(glyph.x(), glyph.y() - glyph.height(),
                                                         glyph.x() + glyph.width(), glyph.y()),
                        glyph.direction()));
            }
            for (final var image : images) {
                work.step();
                barriers.add(new PdfLayoutItem.Bounds(
                        image.x(), image.y(), image.x() + image.width(), image.y() + image.height()));
            }
            for (final var line : angled) {
                work.step();
                barriers.add(line.box());
            }
            barriers.addAll(excluded);
            tableRules = new DoublePdfRuleNormalizer().normalize(segments, barriers, work);
        } else {
            tableRules = segments;
        }
        final var grids = new ArrayList<PdfTableGrid>();
        for (final var grid : new RuledPdfTableDetector(options.tableLimits()).detect(tableRules, work)) {
            boolean blocked = excluded(new PdfLayoutItem.Bounds(grid.xs().getFirst(), grid.ys().getFirst(),
                                               grid.xs().getLast(), grid.ys().getLast()),
                    work);
            for (final var line : angled) {
                work.step();
                final var box = line.box();
                blocked |= box.right() >= grid.xs().getFirst() - 0.5 && box.left() <= grid.xs().getLast() + 0.5
                           && box.bottom() >= grid.ys().getFirst() - 0.5 && box.top() <= grid.ys().getLast() + 0.5;
            }
            if (!blocked) {
                grids.add(grid);
            }
        }
        final var outside = new ArrayList<PdfPageText.Glyph>();
        final var tableGlyphs = new ArrayList<List<List<PdfPageText.Glyph>>>();
        for (final var grid : grids) {
            final var cells = new ArrayList<List<PdfPageText.Glyph>>();
            for (int index = 0; index < grid.cells().size(); index++) {
                cells.add(new ArrayList<>());
            }
            tableGlyphs.add(cells);
        }
        for (final var glyph : glyphs) {
            ElementCollector.checkInterrupted();
            boolean owned = false;
            for (int index = 0; index < grids.size(); index++) {
                work.step();
                final var grid = grids.get(index);
                final var center = geometry.center(glyph);
                final int column = interval(grid.xs(), center.x);
                final int row = interval(grid.ys(), center.y);
                if (row >= 0 && column >= 0) {
                    final int owner = grid.owners()[row * (grid.xs().size() - 1) + column];
                    tableGlyphs.get(index).get(owner).add(glyph);
                    owned = true;
                    break;
                }
            }
            if (!owned) {
                outside.add(glyph);
            }
        }
        for (int index = 0; index < grids.size(); index++) {
            addTable(items, grids.get(index), tableGlyphs.get(index), work);
        }
        final List<PdfPageText.Glyph> textGlyphs;
        if (options.tableDetection().shadedRows() && angled.isEmpty()) {
            final boolean scripts =
                    options.tableDetection() == PdfOptions.TableDetection.RULED_AND_SHADED_ROWS_INLINE_SCRIPTS;
            final boolean compact =
                    scripts
                    || options.tableDetection() == PdfOptions.TableDetection.RULED_AND_SHADED_ROWS_COMPACT_PADDING;
            textGlyphs = addInferredTables(items, outside, segments, images, work,
                    new InferredStrategy(
                            new ShadedRowsPdfTableDetector(options.tableLimits(), geometry, rowBands, compact, scripts),
                            "shaded_row_text_heuristic",
                            scripts   ? "painted_row_bands_inline_script_runs_v1"
                            : compact ? "painted_row_bands_compact_padding_v1"
                                      : "painted_row_bands_numeric_rules_v1",
                            "Shaded-row PDF tables infer row and cell areas from paint and numeric rules; semantic structure is not guaranteed"));
        } else if (options.tableDetection() == PdfOptions.TableDetection.RULED_AND_HORIZONTAL_TEXT
                   && angled.isEmpty()) {
            textGlyphs = addHorizontalTables(items, outside, segments, images, work);
        } else if ((options.tableDetection() == PdfOptions.TableDetection.RULED_AND_PARTIAL_TEXT
                           || options.tableDetection() == PdfOptions.TableDetection.RULED_AND_SEGMENTED_ROWS)
                   && angled.isEmpty()) {
            final var partial = addInferredTables(items, outside, segments, images, work,
                    new InferredStrategy(new PartiallyRuledPdfTableDetector(options.tableLimits(), geometry),
                            "partial_rule_text_heuristic", "partial_segmented_multilevel_numeric_v1",
                            "Partial-rule PDF table detection infers cell areas and header spans; semantic merges are not guaranteed"));
            textGlyphs =
                    options.tableDetection() == PdfOptions.TableDetection.RULED_AND_SEGMENTED_ROWS
                            ? addInferredTables(items, partial, segments, images, work,
                                      new InferredStrategy(
                                              new SegmentedRowsPdfTableDetector(options.tableLimits(), geometry),
                                              "segmented_rule_text_heuristic", "segmented_horizontal_row_spans_v1",
                                              "Segmented-rule PDF table detection infers columns and body row spans; semantic merges are not guaranteed"))
                            : partial;
        } else {
            textGlyphs = outside;
        }
        return new NativeStage(List.copyOf(items), List.copyOf(textGlyphs), work.nativeAnchors());
    }

    /**
     * One internal native strategy and its explicit evidence boundary.
     * @param detector source-only recognition service
     * @param method emitted extraction method
     * @param policy versioned evidence scope
     * @param warning content-free compatibility limitation
     */
    private record InferredStrategy(PdfNativeTableDetector detector, String method, String policy, String warning) {}

    /**
     * Emits explicitly inferred native tables while retaining rejected source text and raster elements.
     * @param items pending page output
     * @param glyphs source glyphs remaining after closed-grid ownership
     * @param segments painted source support and barriers
     * @param images raster ambiguity barriers
     * @param work shared table work/cancellation counter
     * @param strategy hidden detector and its evidence/diagnostic contract
     * @return original unowned glyphs, without a second text copy for accepted tables
     * @throws IOException if cancelled or table/work/output limits fail
     */
    private List<PdfPageText.Glyph> addInferredTables(final List<PdfLayoutItem> items,
            final List<PdfPageText.Glyph> glyphs, final List<PdfPageGraphics.Segment> segments,
            final List<PdfPageGraphics.Image> images, final PdfTableWork work, final InferredStrategy strategy)
            throws IOException {
        context.warn(PartitionResult.WarningCode.PDF_EXTRACTION_LIMITATION, strategy.warning());
        final boolean[] owned = new boolean[glyphs.size()];
        for (final var table : strategy.detector().detect(glyphs, segments, images, work)) {
            if (excluded(table.frame(), work)) {
                continue;
            }
            for (final int index : table.members()) {
                work.step();
                if (owned[index]) {
                    throw new IllegalStateException("Partial PDF tables overlap glyph ownership");
                }
                owned[index] = true;
            }
            final var text = new StringBuilder();
            final var html = new StringBuilder("<table><tbody>");
            final var descriptions = cellMetadata == null ? null : new ArrayList<Map<String, Object>>();
            for (int row = 0; row < table.rows().size(); row++) {
                html.append("<tr>");
                for (final var cell : table.rows().get(row)) {
                    work.step();
                    if (!cell.text().isEmpty()) {
                        if (!text.isEmpty()) {
                            text.append(' ');
                        }
                        text.append(cell.text());
                    }
                    html.append("<td");
                    if (cell.rowSpan() > 1) {
                        html.append(" rowspan=\"").append(cell.rowSpan()).append('"');
                    }
                    if (cell.columnSpan() > 1) {
                        html.append(" colspan=\"").append(cell.columnSpan()).append('"');
                    }
                    html.append('>').append(HtmlTextEscaper.escape(cell.text())).append("</td>");
                    if (descriptions != null) {
                        final var box = cell.bounds();
                        descriptions.add(cellMetadata.cell(row, cell.column(), cell.rowSpan(), cell.columnSpan(),
                                box.left(), box.top(), box.right(), box.bottom(), work));
                    }
                }
                html.append("</tr>");
            }
            html.append("</tbody></table>");
            final var metadata = new LinkedHashMap<String, Object>();
            metadata.put("text_as_html", html.toString());
            metadata.put("pdf_table_method", strategy.method());
            final var evidence =
                    Map.<String, Object>of("rows", table.rows().size(), "columns", table.columns(), "header_rows",
                            table.headerRows(), "numeric_columns", table.numericColumns(), "policy", strategy.policy());
            if (table.omittedPaddingGlyphs() > 0) {
                final var projected = new LinkedHashMap<>(evidence);
                projected.put("overlaid_digit_padding_glyphs", table.omittedPaddingGlyphs());
                metadata.put("pdf_table_evidence", projected);
            } else {
                metadata.put("pdf_table_evidence", evidence);
            }
            if (descriptions != null) {
                metadata.put("pdf_table_cells", PdfTableCells.describe("inferred_cell_bounds", table.rows().size(),
                                                        table.columns(), descriptions));
            }
            items.add(new PdfLayoutItem(ElementType.Table, text.toString(), table.frame(), metadata));
            work.retainNativeAnchors(table.rows().stream().mapToLong(List::size).sum());
        }
        final var remaining = new ArrayList<PdfPageText.Glyph>();
        for (int index = 0; index < glyphs.size(); index++) {
            work.step();
            if (!owned[index]) {
                remaining.add(glyphs.get(index));
            }
        }
        return remaining;
    }

    /**
     * Recognizes opt-in borderless candidates from remaining ordinary runs, retaining rejected text unchanged.
     * @param items page output
     * @param ordinary non-table native runs
     * @param segments source strokes used as ambiguity barriers
     * @param images raster placements used as ambiguity barriers
     * @param work shared per-page recognition counter
     * @throws IOException if cancelled or a table/work limit is exceeded
     */
    private void addAlignedTables(final List<PdfLayoutItem> items, final List<PdfLayoutItem> ordinary,
            final List<PdfPageGraphics.Segment> segments, final List<PdfPageGraphics.Image> images,
            final PdfTableWork work) throws IOException {
        context.warn(PartitionResult.WarningCode.PDF_EXTRACTION_LIMITATION,
                "Aligned-text PDF table detection is heuristic; rejected candidates remain ordinary text");
        final var runs =
                ordinary.stream()
                        .map(item
                                -> new PdfTextRun(item.text(), item.box().left(), item.box().top(), item.box().right(),
                                        item.box().bottom(),
                                        ((Number) item.metadata().getOrDefault("pdf_text_rotation", 0)).intValue()))
                        .toList();
        final PdfBorderlessTableDetector detector =
                options.tableDetection() == PdfOptions.TableDetection.RULED_AND_MULTILINE_ALIGNED_TEXT
                        ? new MultilinePdfTableDetector(options.tableLimits())
                : options.tableDetection() == PdfOptions.TableDetection.RULED_AND_FINANCIAL_ALIGNED_TEXT
                        ? AlignedPdfTableDetector.financial(options.tableLimits())
                        : new AlignedPdfTableDetector(options.tableLimits());
        final var tables = detector.detect(runs, segments, images, work);
        final boolean[] owned = new boolean[runs.size()];
        for (final var table : tables) {
            final var html = new StringBuilder("<table><tbody>");
            final var text = new StringBuilder();
            final var cellDescriptions = cellMetadata == null ? null : new ArrayList<Map<String, Object>>();
            int rowIndex = 0;
            float left = Float.POSITIVE_INFINITY;
            float top = Float.POSITIVE_INFINITY;
            float right = Float.NEGATIVE_INFINITY;
            float bottom = Float.NEGATIVE_INFINITY;
            for (final var row : table.rows()) {
                ElementCollector.checkInterrupted();
                html.append("<tr>");
                int columnIndex = 0;
                for (final int index : row) {
                    work.step();
                    if (owned[index]) {
                        throw new IllegalStateException("Aligned PDF tables overlap source ownership");
                    }
                    owned[index] = true;
                    var run = runs.get(index);
                    final Integer continuation = table.continuations().get(index);
                    if (continuation != null) {
                        work.step();
                        if (owned[continuation]) {
                            throw new IllegalStateException("Aligned PDF tables overlap continuation ownership");
                        }
                        owned[continuation] = true;
                        final var next = runs.get(continuation);
                        run = new PdfTextRun(run.text() + " " + next.text(), Math.min(run.left(), next.left()),
                                Math.min(run.top(), next.top()), Math.max(run.right(), next.right()),
                                Math.max(run.bottom(), next.bottom()));
                    }
                    if (cellDescriptions != null) {
                        cellDescriptions.add(cellMetadata.cell(
                                rowIndex, columnIndex, 1, 1, run.left(), run.top(), run.right(), run.bottom(), work));
                    }
                    columnIndex++;
                    if (!text.isEmpty()) {
                        text.append(' ');
                    }
                    text.append(run.text());
                    html.append("<td>").append(HtmlTextEscaper.escape(run.text())).append("</td>");
                    left = Math.min(left, run.left());
                    top = Math.min(top, run.top());
                    right = Math.max(right, run.right());
                    bottom = Math.max(bottom, run.bottom());
                }
                html.append("</tr>");
                rowIndex++;
            }
            html.append("</tbody></table>");
            final var metadata = new LinkedHashMap<String, Object>();
            metadata.put("text_as_html", html.toString());
            metadata.put("pdf_table_method", "aligned_text_heuristic");
            metadata.put("pdf_table_evidence",
                    Map.of("rows", table.rows().size(), "columns", table.rows().getFirst().size(), "numeric_columns",
                            table.numericColumns(), "policy",
                            options.tableDetection() == PdfOptions.TableDetection.RULED_AND_FINANCIAL_ALIGNED_TEXT
                                    ? "aligned_financial_v1"
                            : table.continuations().isEmpty() ? "aligned_numeric_v1"
                                                              : "aligned_numeric_multiline_v1"));
            if (cellDescriptions != null) {
                metadata.put("pdf_table_cells", PdfTableCells.describe("text_bounds", table.rows().size(),
                                                        table.rows().getFirst().size(), cellDescriptions));
            }
            items.add(new PdfLayoutItem(
                    ElementType.Table, text.toString(), new PdfLayoutItem.Bounds(left, top, right, bottom), metadata));
        }
        for (int index = 0; index < ordinary.size(); index++) {
            work.step();
            if (!owned[index]) {
                items.add(ordinary.get(index));
            }
        }
    }

    /**
     * Emits supported horizontal-rule text tables and retains every unowned glyph for ordinary layout.
     * @param items pending page output
     * @param glyphs remaining original glyphs
     * @param segments painted source rules
     * @param images raster barriers
     * @param work shared page table budget
     * @return unowned original glyphs
     * @throws IOException on cancellation or resource failure
     */
    private List<PdfPageText.Glyph> addHorizontalTables(final List<PdfLayoutItem> items,
            final List<PdfPageText.Glyph> glyphs, final List<PdfPageGraphics.Segment> segments,
            final List<PdfPageGraphics.Image> images, final PdfTableWork work) throws IOException {
        context.warn(PartitionResult.WarningCode.PDF_EXTRACTION_LIMITATION,
                "Horizontal-rule PDF table detection is heuristic; source rule divisions may be decorative");
        final var candidates =
                new HorizontalPdfTableDetector(options.tableLimits(), geometry).detect(glyphs, segments, images, work);
        final boolean[] owned = new boolean[glyphs.size()];
        for (final var candidate : candidates) {
            if (excluded(candidate.frame(), work)) {
                continue;
            }
            for (final int index : candidate.members()) {
                work.step();
                if (owned[index]) {
                    throw new IllegalStateException("Horizontal PDF tables overlap glyph ownership");
                }
                owned[index] = true;
            }
            final var html = new StringBuilder("<table><tbody>");
            final var text = new StringBuilder();
            final var descriptions = cellMetadata == null ? null : new ArrayList<Map<String, Object>>();
            float left = Float.POSITIVE_INFINITY;
            float top = Float.POSITIVE_INFINITY;
            float right = Float.NEGATIVE_INFINITY;
            float bottom = Float.NEGATIVE_INFINITY;
            for (int row = 0; row < candidate.rows().size(); row++) {
                html.append("<tr>");
                for (int column = 0; column < candidate.rows().get(row).size(); column++) {
                    work.step();
                    final var cell = candidate.rows().get(row).get(column);
                    if (!text.isEmpty()) {
                        text.append(' ');
                    }
                    text.append(cell.text());
                    html.append("<td>").append(HtmlTextEscaper.escape(cell.text())).append("</td>");
                    final var box = cell.box();
                    left = Math.min(left, box.left());
                    top = Math.min(top, box.top());
                    right = Math.max(right, box.right());
                    bottom = Math.max(bottom, box.bottom());
                    if (descriptions != null) {
                        descriptions.add(cellMetadata.cell(
                                row, column, 1, 1, box.left(), box.top(), box.right(), box.bottom(), work));
                    }
                }
                html.append("</tr>");
            }
            html.append("</tbody></table>");
            final var metadata = new LinkedHashMap<String, Object>();
            metadata.put("text_as_html", html.toString());
            metadata.put("pdf_table_method", "horizontal_rule_text_heuristic");
            metadata.put("pdf_table_evidence",
                    Map.of("rows", candidate.rows().size(), "columns", candidate.rows().getFirst().size(),
                            "numeric_columns", candidate.numericColumns(), "policy",
                            "horizontal_segmented_numeric_v1"));
            if (descriptions != null) {
                metadata.put("pdf_table_cells", PdfTableCells.describe("text_bounds", candidate.rows().size(),
                                                        candidate.rows().getFirst().size(), descriptions));
            }
            items.add(new PdfLayoutItem(
                    ElementType.Table, text.toString(), new PdfLayoutItem.Bounds(left, top, right, bottom), metadata));
            work.retainNativeAnchors(candidate.rows().stream().mapToLong(List::size).sum());
        }
        final var remaining = new ArrayList<PdfPageText.Glyph>();
        for (int index = 0; index < glyphs.size(); index++) {
            work.step();
            if (!owned[index]) {
                remaining.add(glyphs.get(index));
            }
        }
        return remaining;
    }

    /**
     * Locates a cell by center point, excluding points outside the outer rules.
     * @param bounds sorted unique rule positions
     * @param value coordinate
     * @return zero-based cell index, or -1
     */
    private static int interval(final List<Float> bounds, final float value) {
        if (value < bounds.getFirst() || value >= bounds.getLast()) {
            return -1;
        }
        final int found = java.util.Collections.binarySearch(bounds, value);
        return found >= 0 ? found : -found - 2;
    }

    /**
     * Serializes row-major anchor cells and validates text before emitting a whole table.
     * @param items pending page output
     * @param grid validated source topology
     * @param cells glyphs owned by each anchor exactly once
     * @param work shared bounded table/metadata work
     * @throws IOException if cancelled or a cell-text budget is exceeded
     */
    private void addTable(final List<PdfLayoutItem> items, final PdfTableGrid grid,
            final List<List<PdfPageText.Glyph>> cells, final PdfTableWork work) throws IOException {
        final var html = new StringBuilder("<table><tbody>");
        final var text = new StringBuilder();
        boolean merged = false;
        final var header =
                continuationFrame != null && PdfTableSignature.supports(grid) ? new ArrayList<String>() : null;
        boolean body = false;
        int anchor = 0;
        for (int row = 0; row < grid.ys().size() - 1; row++) {
            ElementCollector.checkInterrupted();
            html.append("<tr>");
            while (anchor < grid.cells().size() && grid.cells().get(anchor).row() == row) {
                final var cell = grid.cells().get(anchor);
                final String value =
                        String.join(" ", lines(cells.get(anchor)).stream().map(PdfLayoutItem::text).toList());
                if (value.codePointCount(0, value.length()) > options.tableLimits().maxCellCharacters()) {
                    throw new PartitionException(
                            PartitionException.Code.TABLE_LIMIT, "PDF grid cell exceeds maxCellCharacters");
                }
                if (header != null && row == 0) {
                    header.add(value);
                } else if (row > 0 && !value.isBlank()) {
                    body = true;
                }
                if (!value.isEmpty()) {
                    if (!text.isEmpty()) {
                        text.append(' ');
                    }
                    text.append(value);
                }
                html.append("<td");
                if (cell.rowSpan() > 1) {
                    html.append(" rowspan=\"").append(cell.rowSpan()).append('"');
                    merged = true;
                }
                if (cell.columnSpan() > 1) {
                    html.append(" colspan=\"").append(cell.columnSpan()).append('"');
                    merged = true;
                }
                html.append('>').append(HtmlTextEscaper.escape(value)).append("</td>");
                anchor++;
            }
            html.append("</tr>");
        }
        html.append("</tbody></table>");
        if (!text.isEmpty()) {
            final var metadata = new LinkedHashMap<String, Object>();
            metadata.put("text_as_html", html.toString());
            metadata.put("pdf_table_method", merged ? "ruled_grid_with_spans" : "complete_ruled_grid");
            if (options.tableDetection() == PdfOptions.TableDetection.RULED_AND_DOUBLE_BORDERS) {
                metadata.put("pdf_table_rule_policy", "double_border_v1");
            }
            if (cellMetadata != null) {
                metadata.put("pdf_table_cells", cellMetadata.grid(grid, work));
            }
            items.add(new PdfLayoutItem(ElementType.Table, text.toString(),
                    new PdfLayoutItem.Bounds(
                            grid.xs().getFirst(), grid.ys().getFirst(), grid.xs().getLast(), grid.ys().getLast()),
                    metadata,
                    header == null ? null : continuationFrame.signature(grid, header, body, "native_rules", work)));
            work.retainNativeAnchors(grid.cells().size());
        }
    }

    /**
     * Groups glyphs by approximate baseline, then splits large horizontal gaps to avoid joining distant columns.
     * These line-level units feed the separately selected paragraph and column-order phase.
     * @param input document-confined positions
     * @return nonblank ordinary-text lines with approximate bounds
     * @throws IOException if interrupted
     */
    private List<PdfLayoutItem> lines(final List<PdfPageText.Glyph> input) throws IOException {
        return PdfGlyphLines.displayed(input, geometry);
    }

    /**
     * Groups ordinary unclaimed text under the explicit small-glyph policy; the default keeps historical lines.
     * Assignment requires an unrotated upright display frame, matching the native cell policy's frame check.
     * @param input exact original positions not claimed by native or provided tables
     * @param work shared page work and cancellation counter
     * @return nonblank lines retaining every original character and bound
     * @throws IOException if work, cancellation or grouping limits fail
     */
    private List<PdfLayoutItem> ordinaryLines(final List<PdfPageText.Glyph> input, final PdfTableWork work)
            throws IOException {
        if (options.inlineScripts() == PdfOptions.InlineScripts.PRESERVE || geometry.textRotation(0) != 0) {
            return lines(input);
        }
        final var baselines = PdfSmallGlyphRuns.pageBaselines(input, work);
        return PdfGlyphLines.displayed(input, geometry, glyph -> baselines.getOrDefault(glyph, (double) glyph.y()));
    }

    /**
     * Rejects whole fallback candidates before claiming any glyph in a source-authored reserved region.
     * @param candidate proposed native envelope
     * @param work shared source/native counter
     * @return positive interior overlap with an accepted source tag table
     * @throws IOException if work/cancellation fails
     */
    private boolean excluded(final PdfLayoutItem.Bounds candidate, final PdfTableWork work) throws IOException {
        for (final var bounds : excluded) {
            work.step();
            if (PdfTaggedTables.overlaps(candidate, bounds)) {
                return true;
            }
        }
        return false;
    }
}
