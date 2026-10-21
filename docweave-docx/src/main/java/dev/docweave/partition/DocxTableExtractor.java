package dev.docweave.partition;

import org.apache.poi.xwpf.usermodel.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Structured cell ownership, gap positions and legacy/nonempty merge composition in stored order. */
final class DocxTableExtractor {
    /**
     * Rendered table content and metadata.
     *
     * @param content accumulated table text, links, annotations, supplements, and nested tables
     * @param metadata structured table and style metadata
     */
    record Rendered(DocxParagraphText.Rendered content, Map<String, Object> metadata) {
        /**
         * Returns the flattened plain-text representation of the table.
         *
         * @return flattened table text
         */
        String text() {
            return content.text();
        }
    }

    /** Mutable logical grid cell tracking span, merge state, and accumulated content. */
    private static final class Cell {
        /** 0-based origin row index of this cell. */
        final int row;
        /** 0-based origin logical column index of this cell. */
        final int column;
        /** Accumulated cell text, links, annotations, and nested tables. */
        final DocxTextAccumulator content = new DocxTextAccumulator();
        /** Vertical merge state ({@code "none"}, {@code "restart"}, or {@code "continue"}). */
        final String vertical;
        /** Horizontal column span of this cell. */
        int columns;
        /** Vertical row span of this cell. */
        int rows = 1;

        /**
         * Creates a logical grid cell initialized with the given coordinates, span, merge state, and content.
         *
         * @param row 0-based origin row index
         * @param column 0-based origin logical column index
         * @param columns horizontal column span
         * @param vertical vertical merge state
         * @param value initial rendered cell content
         */
        Cell(final int row, int column, final int columns, final String vertical, DocxParagraphText.Rendered value) {
            this.row = row;
            this.column = column;
            this.columns = columns;
            this.vertical = vertical;
            content.append(value, "");
        }
    }

    /** Nested-table preservation helper when enabled, or {@code null}. */
    private final DocxNestedTables nested;
    /** Active partition context. */
    private final PartitionContext context;
    /** Parsed Word document. */
    private final XWPFDocument document;
    /** Ordered Word body view adapter. */
    private final DocxBodyView view;
    /** Part-local numbering counter resolver. */
    private final DocxNumberingResolver numbering;
    /** Paragraph style and numbering policy resolver. */
    private final DocxParagraphPolicyResolver policies;

    /**
     * Creates a table extractor with a fresh numbering resolver for the document.
     *
     * @param context active partition context
     * @param document parsed Word document
     */
    DocxTableExtractor(final PartitionContext context, final XWPFDocument document) {
        this(context, document, new DocxNumberingResolver(document));
    }

    /**
     * Creates a table extractor using the provided numbering resolver and context-configured nested-table helper.
     *
     * @param context active partition context
     * @param document parsed Word document
     * @param numbering part-local numbering counter resolver
     */
    DocxTableExtractor(
            final PartitionContext context, final XWPFDocument document, final DocxNumberingResolver numbering) {
        this(context, document, numbering,
                context.options().nestedTableOptions().mode() == NestedTableOptions.Mode.PRESERVE_METADATA
                        ? new DocxNestedTables(context.options().nestedTableOptions())
                        : null);
    }

    /**
     * Creates a table extractor with explicit numbering and nested-table helpers.
     *
     * @param context active partition context
     * @param document parsed Word document
     * @param numbering part-local numbering counter resolver
     * @param nested nested-table preservation helper, or {@code null}
     */
    DocxTableExtractor(final PartitionContext context, final XWPFDocument document,
            final DocxNumberingResolver numbering, final DocxNestedTables nested) {
        this.nested = nested;
        this.context = context;
        this.document = document;
        this.numbering = numbering;
        policies = new DocxParagraphPolicyResolver(document);
        view = new DocxBodyView(context);
    }

    /**
     * Extracts a root Word table into flattened text, HTML, structured metadata, and nested-table descriptors.
     *
     * @param table root Word table to extract
     * @return rendered table content and metadata
     * @throws IOException if extraction fails or is interrupted
     */
    Rendered extract(final XWPFTable table) throws IOException {
        final var result = extract(table, 0, -1);
        if (nested == null) {
            return result;
        }
        final var metadata = new LinkedHashMap<>(result.metadata());
        metadata.putAll(nested.metadata(result.content()));
        return new Rendered(result.content(), Map.copyOf(metadata));
    }

    /**
     * Extracts a Word table at the specified nesting depth and preorder ticket.
     *
     * @param table Word table to extract
     * @param depth 0-based table nesting depth
     * @param ticket preorder nested-table ticket, or {@code -1} for a root table
     * @return rendered table content and metadata
     * @throws IOException if extraction fails or is interrupted
     */
    private Rendered extract(final XWPFTable table, final int depth, final int ticket) throws IOException {
        // Resolve table-level and conditional paragraph style layers before scanning rows.
        final var styleLayers = new DocxTableParagraphStyles(document).resolve(table);
        if (styleLayers.unresolved()) {
            context.warn(PartitionResult.WarningCode.DOCX_CONTENT_OMITTED,
                    "DOCX table-style chain could not be resolved; paragraph style properties were omitted");
        }
        if (styleLayers.omittedConditions()) {
            context.warn(PartitionResult.WarningCode.DOCX_CONTENT_OMITTED,
                    "DOCX unknown table-style paragraph conditions are not applied");
        }
        final var limits = context.options().docxOptions().tableLimits();
        // Materialize unwrapped rows and cells up front so region footprints see the full grid.
        final var sourceRows = view.rows(table);
        final var sourceCells = new ArrayList<List<XWPFTableCell>>();
        for (final var sourceRow : sourceRows) {
            ElementCollector.checkInterrupted();
            if (sourceCells.size() >= limits.maxRows()) {
                throw limit("row");
            }
            sourceCells.add(view.cells(sourceRow));
        }
        final var regions = styleLayers.conditions().isEmpty()
                                    ? null
                                    : new DocxTableRegions(sourceRows, sourceCells, styleLayers.settings(),
                                              styleLayers.conditions().keySet(), limits,
                                              table.getCTTbl().getTblGrid() == null
                                                      ? 0
                                                      : table.getCTTbl().getTblGrid().sizeOfGridColArray());
        final var rows = new ArrayList<List<Cell>>();
        final var headers = new ArrayList<Boolean>();
        Map<Integer, Cell> previous = Map.of();
        long totalCells = 0;
        // Pass 1: resolve horizontal grid gaps, gridSpan, horizontal merges, and vertical merge continuations.
        for (int rowIndex = 0; rowIndex < sourceRows.size(); rowIndex++) {
            final var row = sourceRows.get(rowIndex);
            ElementCollector.checkInterrupted();
            if (rows.size() >= limits.maxRows()) {
                throw limit("row");
            }
            final var properties = row.getCtRow().getTrPr();
            // Account for leading and trailing row grid gaps (w:gridBefore and w:gridAfter).
            final int before = properties != null && properties.sizeOfGridBeforeArray() > 0
                                       ? properties.getGridBeforeArray(0).getVal().intValueExact()
                                       : 0;
            final int after = properties != null && properties.sizeOfGridAfterArray() > 0
                                      ? properties.getGridAfterArray(0).getVal().intValueExact()
                                      : 0;
            if (before < 0 || after < 0 || before + (long) after > limits.maxColumns()) {
                throw limit("grid gap");
            }
            final var horizontal = new ArrayList<Cell>();
            if (before > 0) {
                horizontal.add(empty(rows.size(), 0, before));
            }
            int column = before;
            Cell anchor = null;
            // Expand each physical cell across its gridSpan and fold legacy w:hMerge continuations into their anchor.
            for (int cellIndex = 0; cellIndex < sourceCells.get(rowIndex).size(); cellIndex++) {
                final var cell = sourceCells.get(rowIndex).get(cellIndex);
                final var pr = cell.getCTTc().getTcPr();
                final int span = pr != null && pr.isSetGridSpan() ? pr.getGridSpan().getVal().intValueExact() : 1;
                if (span < 1) {
                    throw malformed("Invalid DOCX cell span");
                }
                if (column + (long) span + after > limits.maxColumns()) {
                    throw limit("column");
                }
                var value = cell(cell, styleLayers, regions, rowIndex, cellIndex, column, depth, ticket);
                final String vertical =
                        pr != null && pr.isSetVMerge() ? merge(String.valueOf(pr.getVMerge().getVal())) : "none";
                final String hmerge =
                        pr != null && pr.isSetHMerge() ? merge(String.valueOf(pr.getHMerge().getVal())) : "none";
                if (hmerge.equals("continue")) {
                    if (anchor == null || !anchor.vertical.equals(vertical)) {
                        throw malformed("Unmatched DOCX horizontal merge continuation");
                    }
                    anchor.columns += span;
                    anchor.content.append(value, "\n");
                    anchor.content.nestedOwner(anchor.row, anchor.column);
                    if (anchor.content.size() > limits.maxCellCharacters()) {
                        throw limit("merged cell text");
                    }
                } else {
                    final var own = new Cell(rows.size(), column, span, vertical, value);
                    horizontal.add(own);
                    anchor = hmerge.equals("restart") ? own : null;
                }
                column += span;
            }
            if (after > 0) {
                horizontal.add(empty(rows.size(), column, after));
            }
            column += after;
            totalCells += column;
            if (totalCells > limits.maxCells()) {
                throw limit("total logical cells");
            }
            // Fold vertical merge continuations (w:vMerge) into the owning cell from the preceding row.
            final var materialized = new ArrayList<Cell>();
            final var current = new HashMap<Integer, Cell>();
            for (final var cell : horizontal) {
                if (cell.vertical.equals("continue")) {
                    final Cell owner = previous.get(cell.column);
                    if (owner == null || owner.columns != cell.columns) {
                        throw malformed("Unmatched DOCX vertical merge continuation");
                    }
                    owner.rows++;
                    owner.content.append(cell.content.rendered(), "\n");
                    owner.content.nestedOwner(owner.row, owner.column);
                    if (owner.content.size() > limits.maxCellCharacters()) {
                        throw limit("merged cell text");
                    }
                    materialized.add(owner);
                    current.put(cell.column, owner);
                } else {
                    materialized.add(cell);
                    if (cell.vertical.equals("restart")) {
                        current.put(cell.column, cell);
                    }
                }
            }
            rows.add(List.copyOf(materialized));
            // Only contiguous leading rows marked with w:tblHeader are treated as repeating table headers.
            final var marker = properties == null ? null : DocxBodyView.child(properties.getDomNode(), "tblHeader");
            final String flag = marker == null ? null : DocxBodyView.attribute(marker, DocxBodyView.WORD, "val");
            final boolean repeat = marker != null && !"false".equals(flag) && !"0".equals(flag) && !"off".equals(flag);
            headers.add(repeat && (headers.isEmpty() || headers.getLast()));
            previous = current;
        }
        // Pass 2: serialize the materialized grid into HTML, tab/newline-separated plain text, and structured metadata.
        final var output = new StructuredTable(limits);
        final var combined = new DocxTextAccumulator();
        final var html = new StringBuilder("<table>");
        for (int r = 0; r < rows.size(); r++) {
            ElementCollector.checkInterrupted();
            final var logical = new ArrayList<StructuredTable.Cell>();
            html.append("<tr>");
            for (final var cell : rows.get(r)) {
                // Emit HTML cell markup and text only at the origin row of a vertically merged cell.
                final boolean origin = cell.row == r;
                var value = origin ? cell.content.rendered() : new DocxParagraphText.Rendered("", List.of());
                if (origin) {
                    final String tag = headers.get(r) ? "th" : "td";
                    html.append('<').append(tag);
                    if (cell.columns > 1) {
                        html.append(" colspan=\"").append(cell.columns).append('"');
                    }
                    if (cell.rows > 1) {
                        html.append(" rowspan=\"").append(cell.rows).append('"');
                    }
                    html.append('>')
                            .append(HtmlTextEscaper.escape(value.text()).replace("\n", "<br>"))
                            .append("</")
                            .append(tag)
                            .append('>');
                }
                final String separator = logical.isEmpty() ? r == 0 ? "" : "\n" : "\t";
                combined.appendExact(value, separator);
                logical.add(new StructuredTable.Cell(value.text(), value.links()));
                // Pad horizontally spanned columns with empty logical cells to preserve column alignment.
                for (int c = 1; c < cell.columns; c++) {
                    combined.appendExact(new DocxParagraphText.Rendered("", List.of()), "\t");
                    logical.add(new StructuredTable.Cell(""));
                }
            }
            // A genuinely empty row still contributes a row separator and HTML grid position.
            if (rows.get(r).isEmpty() && r > 0) {
                combined.appendExact(new DocxParagraphText.Rendered("", List.of()), "\n");
            }
            output.row(logical, headers.get(r));
            html.append("</tr>");
        }
        final var metadata = new LinkedHashMap<>(output.metadata());
        metadata.putAll(combined.rendered().annotations());
        metadata.put("text_as_html", html.append("</table>").toString());
        metadata.put("docx_merge_content_policy", "append_to_origin");
        if (styleLayers.style() != null) {
            metadata.put("docx_table_style", styleLayers.style());
        }
        if (styleLayers.implicit() && styleLayers.style() != null) {
            metadata.put("docx_table_style_source", "default");
        }
        if (styleLayers.unresolved()) {
            metadata.put("docx_table_style_unresolved", true);
        }
        if (styleLayers.omittedConditions()) {
            metadata.put("docx_table_style_conditions_omitted", true);
        }
        return new Rendered(combined.rendered(), Map.copyOf(metadata));
    }

    /**
     * Stored table captions are metadata only; no layout or extra top-level caption is inferred.
     *
     * @param table Word table to inspect
     * @return immutable list of stored table caption strings
     */
    private List<String> captions(final XWPFTable table) {
        final var properties = table.getCTTbl().getTblPr();
        final var caption = properties == null ? null : DocxBodyView.child(properties.getDomNode(), "tblCaption");
        final String value = caption == null ? null : DocxBodyView.attribute(caption, DocxBodyView.WORD, "val");
        return value == null || value.isEmpty() ? List.of() : List.of(value);
    }

    /**
     * Creates an empty placeholder cell for a {@code gridBefore} or {@code gridAfter} gap.
     *
     * @param row 0-based row index
     * @param column 0-based starting column index
     * @param span gap column span
     * @return empty grid cell
     */
    private Cell empty(final int row, int column, final int span) {
        return new Cell(row, column, span, "none", new DocxParagraphText.Rendered("", List.of()));
    }

    /**
     * Normalizes a raw Word merge attribute value to {@code "restart"} or {@code "continue"}.
     *
     * @param value raw merge attribute value
     * @return normalized merge token
     */
    private static String merge(String value) {
        return value.equals("restart") ? "restart" : "continue";
    }

    /**
     * Extracts the paragraphs and nested tables within a single table cell.
     *
     * @param cell Word table cell to extract
     * @param styleLayers resolved table-style paragraph layers
     * @param regions logical grid region matcher, or {@code null}
     * @param row 0-based physical row index
     * @param column 0-based physical cell index within the row
     * @param logicalColumn 0-based logical grid column index
     * @param depth 0-based table nesting depth
     * @param ticket preorder nested-table ticket of the enclosing table
     * @return rendered cell content
     * @throws IOException if cell extraction fails or is interrupted
     */
    private DocxParagraphText.Rendered cell(final XWPFTableCell cell, final DocxTableParagraphStyles.Layers styleLayers,
            final DocxTableRegions regions, final int row, int column, final int logicalColumn, final int depth,
            final int ticket) throws IOException {
        final var accumulated = new DocxTextAccumulator();
        int ordinal = 0;
        for (final var element : view.elements(cell)) {
            ElementCollector.checkInterrupted();
            if (element instanceof final XWPFParagraph paragraph) {
                var value = new DocxParagraphText(context, document).render(paragraph);
                // Match conditional table regions for this cell/paragraph and resolve effective paragraph policy.
                final var selectedRegions =
                        regions == null ? java.util.Set.<String>of() : regions.match(row, column, paragraph);
                final var policy = policies.resolve(paragraph, styleLayers.forRegions(selectedRegions),
                        styleLayers.conditionalOutline(selectedRegions));
                // Record table-style paragraph provenance when conditional or whole-table layers contributed
                // properties.
                if (!value.text().isEmpty() && !policy.tableProperties().isEmpty()) {
                    final var source = new LinkedHashMap<String, Object>();
                    source.put("start_index", 0);
                    source.put("end_index", value.text().codePointCount(0, value.text().length()));
                    source.put("docx_table_style", styleLayers.style());
                    source.put("properties", policy.tableProperties());
                    source.put("regions",
                            DocxTableParagraphStyles.ORDER.stream().filter(selectedRegions::contains).toList());
                    if (policy.tableProperties().contains("outline_level")) {
                        source.put("outline_level", policy.heading() == 0 ? 9 : policy.heading() - 1);
                    }
                    final var annotations = new LinkedHashMap<>(value.annotations());
                    annotations.put("docx_table_style_paragraphs", List.of(source));
                    value = new DocxParagraphText.Rendered(
                            value.text(), value.links(), annotations, value.supplements());
                }
                // Advance list numbering counters for numbered paragraphs inside the cell and attach list metadata.
                if (!value.text().isEmpty() && policy.numId() != null && policy.numId().signum() > 0) {
                    final var item =
                            new LinkedHashMap<String, Object>(numbering.consume(policy.numId(), policy.listLevel()));
                    if (!policy.tableProperties().isEmpty()) {
                        item.put("docx_table_style", styleLayers.style());
                        item.put("docx_table_style_properties", policy.tableProperties());
                    }
                    if (!policy.documentDefaults().isEmpty()) {
                        item.put("docx_document_defaults", policy.documentDefaults());
                    }
                    if (policy.implicitStyle()) {
                        item.put("docx_style", policy.style());
                        item.put("docx_style_source", "default");
                    }
                    if (item.containsKey("docx_numbering_unsupported")
                            || item.containsKey("docx_numbering_unresolved")) {
                        context.warn(PartitionResult.WarningCode.DOCX_CONTENT_OMITTED,
                                "DOCX table-list numbering reference or format could not produce a label");
                    }
                    item.put("start_index", 0);
                    item.put("end_index", value.text().codePointCount(0, value.text().length()));
                    item.put("docx_num_id", policy.numId().toString());
                    item.put("category_depth", policy.listLevel());
                    final var annotations = new LinkedHashMap<>(value.annotations());
                    annotations.put("docx_list_items", List.of(item));
                    value = new DocxParagraphText.Rendered(
                            value.text(), value.links(), annotations, value.supplements());
                }
                accumulated.append(value, "\n");
            } else if (element instanceof final XWPFTable nested) {
                // Reserve a preorder ticket before recursing so nested-table IDs reflect document order.
                final int childTicket = this.nested == null ? -1 : this.nested.child(depth + 1);
                final var rendered = extract(nested, depth + 1, childTicket);
                var content = rendered.content();
                if (this.nested != null) {
                    content = new DocxParagraphText.Rendered(content.text(), content.links(), content.annotations(),
                            content.supplements(),
                            this.nested.capture(
                                    childTicket, ticket, row, logicalColumn, ordinal, rendered, captions(nested)));
                }
                accumulated.append(content, "\n");
                ordinal++;
                context.warn(PartitionResult.WarningCode.NESTED_TABLE_FLATTENED,
                        "DOCX nested table text and links were retained in its containing cell");
            }
            if (accumulated.size() > context.options().docxOptions().tableLimits().maxCellCharacters()) {
                throw limit("cell text");
            }
        }
        return accumulated.rendered();
    }

    /**
     * Creates a table resource budget exception for the specified budget category.
     *
     * @param category budget category description
     * @return partition exception with {@link PartitionException.Code#TABLE_LIMIT}
     */
    private PartitionException limit(final String category) {
        return new PartitionException(PartitionException.Code.TABLE_LIMIT, "DOCX " + category + " budget exceeded");
    }

    /**
     * Creates a malformed table structure exception with the given message.
     *
     * @param message diagnostic error message
     * @return partition exception with {@link PartitionException.Code#MALFORMED_DOCUMENT}
     */
    private PartitionException malformed(final String message) {
        return new PartitionException(PartitionException.Code.MALFORMED_DOCUMENT, message);
    }
}
