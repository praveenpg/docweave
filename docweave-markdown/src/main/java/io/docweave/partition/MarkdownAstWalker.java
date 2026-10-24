package io.docweave.partition;

import io.docweave.core.ElementType;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.node.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Document-local AST traversal with section/list hierarchy, normalized inline text and literal code blocks. */
final class MarkdownAstWalker {
    /** Active partition context receiving emitted elements and warnings. */
    private final PartitionContext context;
    /** Active heading level-to-element-ID map for hierarchical parent tracking. */
    private final TreeMap<Integer, String> headings = new TreeMap<>();
    /** Count of raw HTML block or inline nodes preserved as literal text. */
    private int rawHtmlNodes;

    /**
     * Creates a document-local CommonMark AST walker.
     * @param context active document partition context
     */
    MarkdownAstWalker(final PartitionContext context) {
        this.context = context;
    }

    /**
     * Walks the parsed CommonMark document root and emits elements and any raw-HTML warning.
     * @param document parsed CommonMark root node
     * @throws IOException if element emission, table budgets or interruption fails
     */
    void partition(final Node document) throws IOException {
        children(document, null, 0, false);
        if (rawHtmlNodes > 0) {
            context.warn(PartitionResult.WarningCode.RAW_HTML_PRESERVED,
                    "Preserved " + rawHtmlNodes + " raw HTML nodes as literal Markdown text");
        }
    }

    /**
     * Returns the deepest active heading element ID, if any.
     * @return current heading parent element ID, or {@code null}
     */
    private String headingParent() {
        return headings.isEmpty() ? null : headings.lastEntry().getValue();
    }

    /**
     * Builds a mutable metadata map containing the effective parent ID when present.
     * @param parent explicit parent override, or {@code null} to use the active heading parent
     * @return mutable metadata map
     */
    private Map<String, Object> parentMetadata(final String parent) {
        final var metadata = new LinkedHashMap<String, Object>();
        final String effective = parent == null ? headingParent() : parent;
        if (effective != null) {
            metadata.put("parent_id", effective);
        }
        return metadata;
    }

    /**
     * Visits all direct child nodes of a container while tracking list-item anchor parentage.
     * @param node container AST node
     * @param parent explicit parent ID, or {@code null} to inherit from headings
     * @param listDepth current nesting depth of enclosing list blocks
     * @param listItem whether {@code node} is a {@link ListItem}
     * @throws IOException if visiting a child fails or is interrupted
     */
    private void children(final Node node, final String parent, final int listDepth, final boolean listItem)
            throws IOException {
        String anchor = null;
        // Traverse sibling AST nodes, binding nested ListBlocks inside a ListItem to the item's first emitted element
        // ID.
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            ElementCollector.checkInterrupted();
            final boolean nestedList = child instanceof ListBlock;
            final int before = context.elementCount();
            visit(child, listItem && nestedList && anchor != null ? anchor : parent, listDepth, listItem);
            if (listItem && anchor == null && !nestedList && context.elementCount() > before) {
                anchor = context.elementAt(before).elementId();
            }
        }
    }

    /**
     * Dispatches a single block or container AST node for element emission or recursive traversal.
     * @param node AST node to visit
     * @param parent explicit parent element ID, or {@code null}
     * @param listDepth current enclosing list depth
     * @param inList whether the node is directly inside a list item
     * @throws IOException if rendering, table extraction, emission or interruption fails
     */
    private void visit(final Node node, final String parent, final int listDepth, final boolean inList)
            throws IOException {
        ElementCollector.checkInterrupted();
        switch (node) {
        case final Heading heading -> {
            final var inline = render(heading);
            if (inline.text().isEmpty()) {
                return;
            }
            // Clear any active headings at the same or deeper level before recording the new heading parent.
            headings.tailMap(heading.getLevel()).clear();
            final var metadata = inline.metadata();
            metadata.putAll(parentMetadata(parent));
            metadata.put("category_depth", heading.getLevel() - 1);
            final var element = context.emit(ElementType.Title, inline.text(), metadata);
            headings.put(heading.getLevel(), element.elementId());
        }
        case final Paragraph paragraph -> {
            final var inline = render(paragraph);
            // A paragraph containing only a single Image node is emitted as an Image element even if its alt text is
            // empty.
            final boolean standaloneImage =
                    paragraph.getFirstChild() instanceof Image && paragraph.getFirstChild().getNext() == null;
            if (inline.text().isEmpty() && !standaloneImage) {
                return;
            }
            final var metadata = inline.metadata();
            metadata.putAll(parentMetadata(parent));
            ElementType type = inList ? ElementType.ListItem : ElementType.NarrativeText;
            if (inList) {
                metadata.put("category_depth", Math.max(0, listDepth - 1));
            }
            if (paragraph.getFirstChild() instanceof final Image image && image.getNext() == null) {
                type = ElementType.Image;
                metadata.put("image_url", resolve(image.getDestination()));
            }
            context.emit(type, inline.text(), metadata);
        }
        case final FencedCodeBlock code -> {
            final var metadata = parentMetadata(parent);
            if (code.getInfo() != null && !code.getInfo().isBlank()) {
                metadata.put("code_info", code.getInfo());
                metadata.put("code_language", code.getInfo().strip().split("\\s+", 2)[0]);
            }
            context.emit(ElementType.CodeSnippet, code.getLiteral(), metadata);
        }
        case final IndentedCodeBlock code ->
            context.emit(ElementType.CodeSnippet, code.getLiteral(), parentMetadata(parent));
        case final ListBlock list -> children(list, parent, listDepth + 1, false);
        case final ListItem item -> children(item, parent, listDepth, true);
        case final TableBlock table -> table(table, parent);
        case final HtmlBlock html -> {
            if (context.options().markdownOptions().rawHtml() == MarkdownOptions.RawHtml.PRESERVE_TEXT) {
                rawHtmlNodes++;
                if (!html.getLiteral().isEmpty()) {
                    context.emit(ElementType.UncategorizedText, html.getLiteral(), parentMetadata(parent));
                }
            }
        }
        case final ThematicBreak ignored -> { /* A visual separator has no semantic text. */
        }
        case final LinkReferenceDefinition ignored -> { /* Destinations are resolved by CommonMark link nodes. */
        }
        default -> children(node, parent, listDepth, inList);
        }
    }

    /**
     * Extracts a GFM pipe table block into a structured table element with alignment and link metadata.
     * @param block CommonMark table block node
     * @param parent explicit parent element ID, or {@code null}
     * @throws IOException if table limits are exceeded or interruption occurs
     */
    private void table(final TableBlock block, final String parent) throws IOException {
        final var table = new StructuredTable(context.options().markdownOptions().tableLimits());
        final var alignments = new ArrayList<String>();
        final var imageUrls = new ArrayList<String>();
        // Walk TableHead and TableBody sections, validating row widths and collecting cell text, links, images, and
        // header alignments.
        for (Node section = block.getFirstChild(); section != null; section = section.getNext()) {
            for (Node child = section.getFirstChild(); child != null; child = child.getNext()) {
                if (!(child instanceof final TableRow row)) {
                    continue;
                }
                int count = 0;
                for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
                    count++;
                }
                table.checkRow(count);
                final var cells = new ArrayList<StructuredTable.Cell>();
                boolean header = false;
                for (Node childCell = row.getFirstChild(); childCell != null; childCell = childCell.getNext()) {
                    if (!(childCell instanceof TableCell cell)) {
                        continue;
                    }
                    final var inline = render(cell);
                    imageUrls.addAll(inline.imageUrls);
                    cells.add(new StructuredTable.Cell(inline.text(), inline.links));
                    if (cell.isHeader()) {
                        header = true;
                        alignments.add(cell.getAlignment() == null
                                               ? ""
                                               : cell.getAlignment().name().toLowerCase(java.util.Locale.ROOT));
                    }
                }
                table.row(cells, header);
            }
        }
        if (table.rowCount() == 0) {
            return;
        }
        final var metadata = table.metadata();
        metadata.putAll(parentMetadata(parent));
        if (alignments.stream().anyMatch(value -> !value.isEmpty())) {
            metadata.put("table_column_alignments", alignments);
        }
        if (!imageUrls.isEmpty()) {
            metadata.put("image_urls", imageUrls);
        }
        context.emit(ElementType.Table, table.text(), metadata);
    }

    /**
     * Resolves a link or image destination against the configured base URI when valid.
     * @param destination raw link or image target from Markdown
     * @return resolved URI string, or the original destination if base URI is absent or invalid
     */
    private String resolve(final String destination) {
        if (context.options().baseUri() == null) {
            return destination;
        }
        try {
            return context.options().baseUri().resolve(destination).toString();
        } catch (final IllegalArgumentException ignored) {
            return destination;
        }
    }

    /**
     * Renders the inline children of a block or table cell node into normalized text and link metadata.
     * @param node container node whose inline subtree should be rendered
     * @return rendered inline text accumulator
     * @throws IOException if the calling thread is interrupted during inline traversal
     */
    private InlineText render(final Node node) throws IOException {
        final var inline = new InlineText();
        inline.children(node);
        return inline;
    }

    /** Accumulates normalized inline text, code spans, link offsets and image URLs for one block or cell. */
    private final class InlineText {
        /** Normalized inline text buffer. */
        private final StringBuilder text = new StringBuilder();
        /** Collected link metadata entries in inline appearance order. */
        private final List<Map<String, Object>> links = new ArrayList<>();
        /** Collected resolved image URLs in inline appearance order. */
        private final List<String> imageUrls = new ArrayList<>();
        /** Number of Unicode code points appended to {@link #text}. */
        private int points;
        /** Whether a collapsing space should be emitted before the next non-space token. */
        private boolean pendingSpace;

        /**
         * Returns the accumulated inline text string.
         * @return normalized inline text
         */
        String text() {
            return text.toString();
        }

        /**
         * Appends all direct children of the given inline container node.
         * @param node parent AST node
         * @throws IOException if the calling thread is interrupted
         */
        void children(final Node node) throws IOException {
            for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                append(child);
            }
        }

        /**
         * Appends one inline AST node to the accumulated text and link/image collections.
         * @param node inline AST node
         * @throws IOException if the calling thread is interrupted
         */
        private void append(final Node node) throws IOException {
            ElementCollector.checkInterrupted();
            switch (node) {
            case final Text value -> normalized(value.getLiteral());
            case final Code code -> literal(code.getLiteral());
            case final SoftLineBreak ignored -> pendingSpace = !text.isEmpty();
            case final HardLineBreak ignored -> pendingSpace = !text.isEmpty();
            case final Link link -> {
                int start = points;
                children(link);
                String label = text.substring(text.offsetByCodePoints(0, start));
                if (label.startsWith(" ")) {
                    label = label.substring(1);
                    start++;
                }
                if (!label.isEmpty()) {
                    links.add(Map.of("text", label, "url", resolve(link.getDestination()), "start_index", start));
                }
            }
            case final Image image -> {
                children(image);
                imageUrls.add(resolve(image.getDestination()));
            }
            case final HtmlInline html -> {
                if (context.options().markdownOptions().rawHtml() == MarkdownOptions.RawHtml.PRESERVE_TEXT) {
                    rawHtmlNodes++;
                    literal(html.getLiteral());
                }
            }
            default -> children(node);
            }
        }

        /**
         * Appends prose text while collapsing consecutive whitespace into a single space.
         * @param value literal text from a CommonMark {@link Text} node
         */
        private void normalized(final String value) {
            for (int offset = 0; offset < value.length();) {
                final int point = value.codePointAt(offset);
                offset += Character.charCount(point);
                if (TextSupport.space(point)) {
                    pendingSpace = !text.isEmpty();
                } else {
                    flushSpace();
                    text.appendCodePoint(point);
                    points++;
                }
            }
        }

        /**
         * Appends literal inline code or preserved raw HTML without collapsing internal whitespace.
         * @param value literal span text
         */
        private void literal(final String value) {
            if (value.isEmpty()) {
                return;
            }
            flushSpace();
            text.append(value);
            points += value.codePointCount(0, value.length());
        }

        /**
         * Emits a deferred separating space when non-whitespace content follows whitespace.
         */
        private void flushSpace() {
            if (pendingSpace) {
                text.append(' ');
                points++;
                pendingSpace = false;
            }
        }

        /**
         * Builds the inline link and image metadata map for the rendered block.
         * @return mutable metadata map containing link and image lists when non-empty
         */
        Map<String, Object> metadata() {
            final var metadata = new LinkedHashMap<String, Object>();
            if (!links.isEmpty()) {
                metadata.put("links", List.copyOf(links));
                metadata.put("link_texts", links.stream().map(link -> link.get("text")).toList());
                metadata.put("link_urls", links.stream().map(link -> link.get("url")).toList());
                metadata.put("link_start_indexes", links.stream().map(link -> link.get("start_index")).toList());
            }
            if (!imageUrls.isEmpty()) {
                metadata.put("image_urls", List.copyOf(imageUrls));
            }
            return metadata;
        }
    }
}
