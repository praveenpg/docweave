package io.docweave.partition;

import io.docweave.core.ElementType;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.parser.Parser;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Per-document semantic DOM walker. Inline runs flush at block boundaries, headings maintain a
 * level-indexed parent stack, lists retain local anchors, and tables are emitted atomically.
 * DOM recovery is delegated to jsoup; no rendering or external-resource loading is performed.
 */
final class HtmlDomWalker {
    /** Active heading element IDs indexed by 1-based heading level ({@code h1}..{@code h6}). */
    private final TreeMap<Integer, String> headings = new TreeMap<>();
    /** Current document partition context receiving emitted elements and warnings. */
    private PartitionContext context;
    /** Active partition options for the current invocation. */
    private PartitionOptions options;
    /** Optional table dimension and cell limits enforced on HTML tables. */
    private TableLimits tableLimits;
    /** Optional nested-table metadata extractor when nested-table preservation is enabled. */
    private HtmlNestedTables nestedTables;

    /**
     * Parses and walks the HTML document text from the given partition context.
     *
     * @param context active partition context containing decoded HTML text and options
     * @throws IOException if resource limits are exceeded or partitioning fails
     */
    void partition(final PartitionContext context) throws IOException {
        partition(context, context.text(), null);
    }

    /**
     * Shares DOM semantics with rendered Markdown while preserving its original emission context.
     *
     * @param context active partition context receiving emitted elements and warnings
     * @param text HTML markup to parse and traverse
     * @param tableLimits optional explicit table limits, or {@code null} to use default preservation limits
     * @throws IOException if resource limits are exceeded or partitioning fails
     */
    void partition(final PartitionContext context, final String text, final TableLimits tableLimits)
            throws IOException {
        this.context = context;
        this.options = context.options();
        final boolean preserve = options.nestedTableOptions().mode() == NestedTableOptions.Mode.PRESERVE_METADATA;
        this.tableLimits = tableLimits != null ? tableLimits : preserve ? TableLimits.defaults() : null;
        this.nestedTables = preserve ? new HtmlNestedTables(options.nestedTableOptions(), this.tableLimits) : null;
        final var parser = Parser.htmlParser().setTrackErrors(20);
        ElementCollector.checkInterrupted();
        final var document = parser.parseInput(text, options.baseUri() == null ? "" : options.baseUri().toString());
        ElementCollector.checkInterrupted();
        document.outputSettings().prettyPrint(false);
        if (!parser.getErrors().isEmpty()) {
            context.warn(PartitionResult.WarningCode.HTML_RECOVERED,
                    "HTML parser recovered " + parser.getErrors().size() + " tracked syntax errors");
        }
        checkDepth(document.body());
        document.select("script, style, template, noscript, [hidden], [aria-hidden=true]").remove();
        HtmlDomPolicy.apply(document);
        if (options.skipHeadersAndFooters()) {
            document.select("header, footer").remove();
        }
        if (this.tableLimits != null) {
            for (final Element table : document.body().getElementsByTag("table")) {
                checkTableLimits(table);
            }
        }
        walkContainer(document.body(), null, null, 0, false);
    }

    /**
     * Verifies that the DOM subtree rooted at {@code body} does not exceed {@link PartitionOptions#maxHtmlDepth()}.
     *
     * @param body root body element to inspect iteratively
     * @throws IOException if the maximum HTML element nesting depth is exceeded or the thread is interrupted
     */
    private void checkDepth(final Element body) throws IOException {
        record AtDepth(Element node, int depth) {}
        final var pending = new ArrayDeque<AtDepth>();
        pending.push(new AtDepth(body, 0));
        while (!pending.isEmpty()) {
            ElementCollector.checkInterrupted();
            final var current = pending.pop();
            if (current.depth() > options.maxHtmlDepth()) {
                throw new PartitionException(PartitionException.Code.HTML_DEPTH_LIMIT,
                        "HTML exceeds maxHtmlDepth=" + options.maxHtmlDepth());
            }
            for (final var child : current.node().children()) {
                pending.push(new AtDepth(child, current.depth() + 1));
            }
        }
    }

    /**
     * Returns the element ID of the deepest currently active heading, or {@code null} if none is active.
     *
     * @return deepest active heading element ID, or {@code null}
     */
    private String headingParent() {
        return headings.isEmpty() ? null : headings.lastEntry().getValue();
    }

    /**
     * Dispatches semantic handling for a block-level HTML element.
     *
     * @param node block element to visit
     * @param forced inherited forced element type, or {@code null} for default classification
     * @param parent explicit parent element ID override, or {@code null} to inherit the active heading
     * @param listDepth current nested list depth
     * @throws IOException if emitting elements fails or limits are exceeded
     */
    private void visit(final Element node, final ElementType forced, String parent, final int listDepth)
            throws IOException {
        ElementCollector.checkInterrupted();
        final String tag = node.normalName();
        if (tag.matches("h[1-6]")) {
            final int level = tag.charAt(1) - '0';
            final var rendered = render(node);
            if (!rendered.text().isEmpty()) {
                // Prune any active headings at the same or deeper level before registering the new heading.
                headings.tailMap(level).clear();
                final var metadata = rendered.metadata();
                metadata.put("category_depth", level - 1);
                if (headingParent() != null) {
                    metadata.put("parent_id", headingParent());
                }
                final var heading = context.emit(ElementType.Title, rendered.text(), metadata);
                headings.put(level, heading.elementId());
            }
        } else {
            switch (tag) {
            case "table" -> table(node, parent);
            case "pre" -> {
                final String code = node.wholeText();
                if (!TextSupport.blank(code)) {
                    context.emit(ElementType.CodeSnippet, code, parentMetadata(parent));
                }
            }
            case "ul", "ol" -> walkContainer(node, forced, parent, listDepth + 1, false);
            case "li" -> walkContainer(node, ElementType.ListItem, parent, Math.max(1, listDepth), true);
            case "header" -> walkContainer(node, ElementType.Header, parent, listDepth, false);
            case "footer" -> walkContainer(node, ElementType.Footer, parent, listDepth, false);
            case "figcaption" -> walkContainer(node, ElementType.FigureCaption, parent, listDepth, false);
            case "p", "blockquote", "dt", "dd" ->
                walkContainer(node, forced == null ? ElementType.NarrativeText : forced, parent, listDepth, false);
            default -> walkContainer(node, forced, parent, listDepth, false);
            }
        }
    }

    /**
     * Flushes inline runs at block boundaries; wrappers themselves never duplicate descendant text.
     *
     * @param node container element whose child nodes are traversed
     * @param forced forced element type for inline text runs, or {@code null} for automatic classification
     * @param parent explicit parent element ID override, or {@code null} to inherit the active heading
     * @param listDepth current nested list depth
     * @param listItem {@code true} if {@code node} is a list item ({@code <li>})
     * @throws IOException if emitting elements fails or limits are exceeded
     */
    private void walkContainer(final Element node, final ElementType forced, String parent, final int listDepth,
            final boolean listItem) throws IOException {
        var inline = new HtmlInlineText();
        String listAnchor = null;
        // Accumulate contiguous inline nodes and flush them whenever a block child is encountered.
        for (final Node child : node.childNodes()) {
            ElementCollector.checkInterrupted();
            if (child instanceof final Element element && element.isBlock() && !element.normalName().equals("br")) {
                final String emitted = emit(inline, forced, parent, listDepth);
                if (listAnchor == null) {
                    listAnchor = emitted;
                }
                inline = new HtmlInlineText();
                // Bind nested <ul>/<ol> sublists inside an <li> to the first non-list element emitted for that <li>.
                final String childParent =
                        listItem && (element.normalName().equals("ul") || element.normalName().equals("ol"))
                                        && listAnchor != null
                                ? listAnchor
                                : parent;
                final int before = context.elementCount();
                visit(element, forced, childParent, listDepth);
                if (listItem && listAnchor == null && context.elementCount() > before
                        && !element.normalName().equals("ul") && !element.normalName().equals("ol")) {
                    listAnchor = context.elementAt(before).elementId();
                }
            } else {
                inline.append(child);
            }
        }
        emit(inline, forced, parent, listDepth);
    }

    /**
     * Emits a non-empty accumulated inline text run as an element and returns its element ID.
     *
     * @param rendered accumulated inline text and link metadata
     * @param forced forced element type, or {@code null} to classify from text heuristics
     * @param parent explicit parent element ID override, or {@code null} to inherit the active heading
     * @param listDepth current nested list depth
     * @return emitted element ID, or {@code null} if {@code rendered} is empty
     * @throws IOException if emitting the element fails
     */
    private String emit(final HtmlInlineText rendered, final ElementType forced, String parent, final int listDepth)
            throws IOException {
        final String text = rendered.text();
        if (text.isEmpty()) {
            return null;
        }
        final ElementType type = forced == null ? TextSupport.classify(text) : forced;
        final var metadata = rendered.metadata();
        metadata.putAll(parentMetadata(parent));
        if (type == ElementType.ListItem) {
            metadata.put("category_depth", Math.max(0, listDepth - 1));
        }
        return context.emit(type, text, metadata).elementId();
    }

    /**
     * Builds a mutable metadata map populated with {@code parent_id} when an explicit or heading parent exists.
     *
     * @param parent explicit parent element ID override, or {@code null} to fall back to {@link #headingParent()}
     * @return mutable metadata map containing {@code parent_id} if applicable
     */
    private Map<String, Object> parentMetadata(String parent) {
        final var metadata = new LinkedHashMap<String, Object>();
        final String effective = parent == null ? headingParent() : parent;
        if (effective != null) {
            metadata.put("parent_id", effective);
        }
        return metadata;
    }

    /**
     * Extracts captions, normalizes rows and cells, captures nested-table metadata if enabled, and emits a table.
     *
     * @param table HTML {@code <table>} element to extract
     * @param parent explicit parent element ID override, or {@code null} to inherit the active heading
     * @throws IOException if table limits are exceeded or element emission fails
     */
    private void table(final Element table, String parent) throws IOException {
        // Emit direct <caption> elements as FigureCaption elements before the table itself.
        for (final Element caption : table.children()) {
            if (caption.normalName().equals("caption")) {
                final var rendered = render(caption);
                if (!rendered.text().isEmpty()) {
                    final var metadata = rendered.metadata();
                    metadata.putAll(parentMetadata(parent));
                    context.emit(ElementType.FigureCaption, rendered.text(), metadata);
                }
            }
        }
        if (table.getElementsByTag("table").size() > 1) {
            context.warn(PartitionResult.WarningCode.NESTED_TABLE_FLATTENED,
                    "Nested table content was flattened inside its containing cell");
        }
        final var canonical = new Element("table");
        final var plain = new StringBuilder();
        final var tableLinks = new ArrayList<Map<String, Object>>();
        // Build both the tab/newline plain-text representation and the sanitized canonical HTML table structure.
        for (final Element row : table.getElementsByTag("tr")) {
            ElementCollector.checkInterrupted();
            if (closestTable(row) != table) {
                continue;
            }
            final var outputRow = new Element("tr");
            final var values = new ArrayList<String>();
            final int rowOffset = plain.codePointCount(0, plain.length()) + (plain.isEmpty() ? 0 : 1);
            int cellOffset = rowOffset;
            for (final Element cell : row.children()) {
                if (cell.normalName().equals("td") || cell.normalName().equals("th")) {
                    final var rendered = render(cell);
                    final String value = rendered.text();
                    if (!values.isEmpty()) {
                        cellOffset++;
                    } // tab separator occupies one code point
                    for (final var link : rendered.links()) {
                        final var adjusted = new LinkedHashMap<>(link);
                        adjusted.put("start_index", cellOffset + (Integer) link.get("start_index"));
                        tableLinks.add(adjusted);
                    }
                    cellOffset += value.codePointCount(0, value.length());
                    values.add(value);
                    final var outputCell = new Element(cell.normalName()).text(value);
                    for (final String span : List.of("colspan", "rowspan")) {
                        final String attribute = cell.attr(span);
                        if (attribute.isEmpty()) {
                            continue;
                        }
                        try {
                            if (Integer.parseInt(attribute) > 0) {
                                outputCell.attr(span, attribute);
                            }
                        } catch (final NumberFormatException ignored) { /* Invalid span does not describe geometry. */
                        }
                    }
                    outputRow.appendChild(outputCell);
                }
            }
            if (!values.isEmpty()) {
                if (!plain.isEmpty()) {
                    plain.append('\n');
                }
                plain.append(String.join("\t", values));
                canonical.appendChild(outputRow);
            }
        }
        if (TextSupport.blank(plain.toString())) {
            return;
        }
        // Attach a document to configure compact serialization; cells contain only text and span attributes.
        final var shell = org.jsoup.nodes.Document.createShell("");
        shell.outputSettings().prettyPrint(false);
        shell.body().appendChild(canonical);
        final var metadata = parentMetadata(parent);
        metadata.putAll(linkMetadata(tableLinks));
        metadata.put("text_as_html", canonical.outerHtml());
        if (nestedTables != null) {
            metadata.putAll(nestedTables.capture(table, plain.toString()));
        }
        context.emit(ElementType.Table, plain.toString(), metadata);
    }

    /**
     * Counts source cells, including nested tables independently; spans do not allocate grid slots.
     *
     * @param table HTML {@code <table>} element to validate against {@link #tableLimits}
     * @throws IOException if row, column, total-cell, or per-cell character limits are exceeded
     */
    private void checkTableLimits(final Element table) throws IOException {
        int rows = 0;
        long cells = 0;
        for (final Element row : table.getElementsByTag("tr")) {
            ElementCollector.checkInterrupted();
            if (closestTable(row) != table) {
                continue;
            }
            int fields = 0;
            for (final Element cell : row.children()) {
                if (!cell.normalName().equals("td") && !cell.normalName().equals("th")) {
                    continue;
                }
                fields++;
                final String value = render(cell).text();
                if (value.codePointCount(0, value.length()) > tableLimits.maxCellCharacters()) {
                    throw new PartitionException(
                            PartitionException.Code.TABLE_LIMIT, "Table field exceeds its code-point budget");
                }
            }
            if (fields == 0) {
                continue;
            }
            if (rows >= tableLimits.maxRows() || fields > tableLimits.maxColumns()
                    || cells + fields > tableLimits.maxCells()) {
                throw new PartitionException(
                        PartitionException.Code.TABLE_LIMIT, "Table exceeds row, column or total-cell budget");
            }
            rows++;
            cells += fields;
        }
    }

    /**
     * Finds the nearest ancestor {@code <table>} element for {@code element}.
     *
     * @param element descendant DOM element
     * @return nearest enclosing {@code <table>} element, or {@code null} if none exists
     */
    private Element closestTable(final Element element) {
        for (Element parent = element.parent(); parent != null; parent = parent.parent()) {
            if (parent.normalName().equals("table")) {
                return parent;
            }
        }
        return null;
    }

    /**
     * Renders all child nodes of {@code node} into normalized inline text and link metadata.
     *
     * @param node container element to render inline
     * @return accumulated inline text and link metadata
     * @throws IOException if rendering is interrupted or exceeds limits
     */
    private HtmlInlineText render(final Element node) throws IOException {
        var inline = new HtmlInlineText();
        for (final Node child : node.childNodes()) {
            inline.append(child);
        }
        return inline;
    }

    /**
     * Converts a list of extracted link descriptors into parallel element metadata entries.
     *
     * @param links ordered link maps containing {@code text}, {@code url}, and {@code start_index}
     * @return metadata map containing {@code links}, {@code link_texts}, {@code link_urls}, and {@code
     *         link_start_indexes}
     */
    static Map<String, Object> linkMetadata(final List<Map<String, Object>> links) {
        final var metadata = new LinkedHashMap<String, Object>();
        if (!links.isEmpty()) {
            metadata.put("links", List.copyOf(links));
            metadata.put("link_texts", links.stream().map(link -> link.get("text")).toList());
            metadata.put("link_urls", links.stream().map(link -> link.get("url")).toList());
            metadata.put("link_start_indexes", links.stream().map(link -> link.get("start_index")).toList());
        }
        return metadata;
    }
}
