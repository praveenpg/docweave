package io.docweave.partition;

import io.docweave.core.Element;
import io.docweave.core.ElementType;
import org.commonmark.ext.footnotes.FootnoteDefinition;
import org.commonmark.ext.footnotes.FootnoteReference;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.task.list.items.TaskListItemMarker;
import org.commonmark.node.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Document-local AST traversal with section/list hierarchy, normalized inline text, inline emphasis spans and literal
 * code blocks.
 */
final class MarkdownAstWalker {
    /** Active partition context receiving emitted elements and warnings. */
    private final PartitionContext context;
    /** Active heading level-to-element-ID map for hierarchical parent tracking. */
    private final TreeMap<Integer, String> headings = new TreeMap<>();
    /** Count of raw HTML block or inline nodes preserved as literal text. */
    private int rawHtmlNodes;
    /** Checked state of a task-list marker awaiting its item's first paragraph, or {@code null}. */
    private Boolean pendingTask;
    /** Whether the FOOTNOTES extension is selected. */
    private final boolean footnotes;
    /** Metadata for the next emitted element (the first of a footnote or definition), or {@code null}. */
    private Map<String, Object> pendingFirst;
    /** Footnote numbering shared with EXTRACT_DOM, or {@code null} until the walk starts. */
    private MarkdownFootnotes footnoteNumbers;

    /**
     * Creates a document-local CommonMark AST walker.
     * @param context active document partition context
     */
    MarkdownAstWalker(final PartitionContext context) {
        this.context = context;
        this.footnotes =
                context.options().markdownOptions().extensions().contains(MarkdownOptions.Extension.FOOTNOTES);
    }

    /**
     * Walks the parsed CommonMark document root and emits elements and any raw-HTML warning.
     * @param document parsed CommonMark root node
     * @throws IOException if element emission, table budgets or interruption fails
     */
    void partition(final Node document) throws IOException {
        // Footnote nodes exist only when the extension is selected.
        footnoteNumbers = footnotes ? MarkdownFootnotes.analyze(document) : null;
        children(document, null, 0, false, false);
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
     * Emits an element, first adding any metadata reserved for the first element of a footnote or definition.
     * @param type element type
     * @param text element text
     * @param metadata mutable strategy metadata
     * @return emitted element
     * @throws IOException if emission fails
     */
    private Element emit(final ElementType type, final String text, final Map<String, Object> metadata)
            throws IOException {
        if (pendingFirst != null) {
            pendingFirst.forEach(metadata::putIfAbsent);
            pendingFirst = null;
        }
        return context.emit(type, text, metadata);
    }

    /**
     * Visits a container whose first emitted element receives extra metadata, merged with any metadata still pending
     * from an enclosing container whose first element this also is. If the container emits nothing, the enclosing
     * metadata stays pending for the next element. Headings inside the container do not change the heading parents
     * active after it.
     * @param first metadata for the container's first element
     * @param node container
     * @param parent explicit parent element ID, or {@code null}
     * @param listDepth list depth for the container's children
     * @param inList whether paragraphs directly inside become {@code ListItem} elements
     * @throws IOException if emission or interruption fails
     */
    private void anchoredWithFirst(final Map<String, Object> first, final Node node, final String parent,
            final int listDepth, final boolean inList) throws IOException {
        final Map<String, Object> enclosing = pendingFirst;
        final var merged = new LinkedHashMap<String, Object>(first);
        if (enclosing != null) {
            enclosing.forEach(merged::putIfAbsent);
        }
        pendingFirst = merged;
        final int before = context.elementCount();
        // Headings inside a note or definition outline that container only: later terms, outer headings and body
        // text keep the heading parent they had before it.
        final var outer = new TreeMap<>(headings);
        try {
            children(node, parent, listDepth, true, inList);
        } finally {
            restoreHeadings(outer);
        }
        if (context.elementCount() == before) {
            pendingFirst = enclosing;
        }
    }

    /**
     * Restores the active heading parents saved before a footnote definition or definition description.
     * @param outer headings active before the container
     */
    private void restoreHeadings(final TreeMap<Integer, String> outer) {
        headings.clear();
        headings.putAll(outer);
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
     * Visits all direct child nodes of a container while tracking anchor parentage.
     * @param node container AST node
     * @param parent explicit parent ID, or {@code null} to inherit from headings
     * @param listDepth current nesting depth of enclosing list blocks
     * @param anchored whether later blocks take the container's first element as parent (list items, footnote
     *        definitions and definitions)
     * @param inList whether paragraphs directly inside become {@code ListItem} elements
     * @throws IOException if visiting a child fails or is interrupted
     */
    private void children(final Node node, final String parent, final int listDepth, final boolean anchored,
            final boolean inList) throws IOException {
        String anchor = null;
        // Traverse sibling AST nodes, binding nested lists and continuation blocks inside an anchored container to its
        // first emitted element ID.
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            ElementCollector.checkInterrupted();
            final boolean nestedList = child instanceof ListBlock || child instanceof MarkdownDefinitionLists.DefinitionList;
            final int before = context.elementCount();
            visit(child, anchored && anchor != null ? anchor : parent, listDepth, inList);
            if (anchored && anchor == null && !nestedList && context.elementCount() > before) {
                anchor = context.elementAt(before).elementId();
            }
        }
    }

    /**
     * Emits a definition list: each term as a {@code ListItem}, each definition's blocks under its first term.
     * @param list definition list node
     * @param parent explicit parent element ID, or {@code null}
     * @param listDepth list depth of this definition list
     * @throws IOException if emission or interruption fails
     */
    private void definitionList(final MarkdownDefinitionLists.DefinitionList list, final String parent,
            final int listDepth) throws IOException {
        final var terms = new ArrayList<String>();
        boolean afterDefinition = false;
        for (Node child = list.getFirstChild(); child != null; child = child.getNext()) {
            ElementCollector.checkInterrupted();
            if (child instanceof final MarkdownDefinitionLists.DefinitionTerm term) {
                if (afterDefinition) {
                    terms.clear();
                    afterDefinition = false;
                }
                final var inline = render(term);
                if (inline.text().isEmpty()) {
                    continue;
                }
                final var metadata = inline.metadata();
                metadata.putAll(parentMetadata(parent));
                metadata.put("category_depth", Math.max(0, listDepth - 1));
                metadata.put("definition_list_role", "term");
                terms.add(emit(ElementType.ListItem, inline.text(), metadata).elementId());
            } else {
                afterDefinition = true;
                final String owner = terms.isEmpty() ? parent : terms.getFirst();
                final Node content = child.getFirstChild();
                if (content instanceof ListBlock || content instanceof MarkdownDefinitionLists.DefinitionList) {
                    // A definition that starts with a list has no definition element: the items, like the
                    // definition's later blocks, take the first term as parent.
                    final var outer = new TreeMap<>(headings);
                    try {
                        children(child, owner, listDepth + 1, true, true);
                    } finally {
                        restoreHeadings(outer);
                    }
                    continue;
                }
                final var first = new LinkedHashMap<String, Object>();
                first.put("definition_list_role", "definition");
                if (!terms.isEmpty()) {
                    first.put("definition_term_ids", List.copyOf(terms));
                }
                anchoredWithFirst(first, child, owner, listDepth + 1, true);
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
            final var element = emit(ElementType.Title, inline.text(), metadata);
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
                if (pendingTask != null) {
                    metadata.put("task_list_item_checked", pendingTask);
                }
            }
            pendingTask = null;
            if (paragraph.getFirstChild() instanceof final Image image && image.getNext() == null) {
                type = ElementType.Image;
                metadata.put("image_url", resolve(image.getDestination()));
            }
            emit(type, inline.text(), metadata);
        }
        case final FencedCodeBlock code -> {
            final var metadata = parentMetadata(parent);
            if (code.getInfo() != null && !code.getInfo().isBlank()) {
                metadata.put("code_info", code.getInfo());
                metadata.put("code_language", code.getInfo().strip().split("\\s+", 2)[0]);
            }
            emit(ElementType.CodeSnippet, code.getLiteral(), metadata);
        }
        case final IndentedCodeBlock code ->
            emit(ElementType.CodeSnippet, code.getLiteral(), parentMetadata(parent));
        case final ListBlock list -> children(list, parent, listDepth + 1, false, false);
        case final ListItem item -> {
            pendingTask = null;
            children(item, parent, listDepth, true, true);
        }
        case final MarkdownDefinitionLists.DefinitionList list -> definitionList(list, parent, listDepth + 1);
        case final FootnoteDefinition definition -> {
            final var first = new LinkedHashMap<String, Object>();
            first.put("footnote_label", definition.getLabel());
            final Integer number = footnoteNumbers.number(definition);
            if (number != null) {
                first.put("footnote_number", number);
            }
            anchoredWithFirst(first, definition, parent, listDepth, false);
        }
        case final TableBlock table -> table(table, parent);
        case final HtmlBlock html -> {
            if (context.options().markdownOptions().rawHtml() == MarkdownOptions.RawHtml.PRESERVE_TEXT) {
                rawHtmlNodes++;
                if (!html.getLiteral().isEmpty()) {
                    emit(ElementType.UncategorizedText, html.getLiteral(), parentMetadata(parent));
                }
            }
        }
        case final TaskListItemMarker marker -> pendingTask = marker.isChecked();
        case final ThematicBreak ignored -> { /* A visual separator has no semantic text. */
        }
        case final LinkReferenceDefinition definition -> {
            // Destinations are resolved by CommonMark link nodes. With FOOTNOTES, a definition whose label starts
            // with ^ was meant as a footnote but is malformed (for example [^a b]: or [^]:); keep its text.
            if (footnotes && MarkdownFootnotes.malformed(definition)) {
                emit(ElementType.UncategorizedText, MarkdownFootnotes.literal(definition), parentMetadata(parent));
            }
        }
        default -> children(node, parent, listDepth, inList, inList);
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
        final var emphasis = new ArrayList<Span>();
        final var struck = new ArrayList<Span>();
        final var footnotes = new ArrayList<Object[]>();
        // Code-point offset of the next cell in the table text: StructuredTable separates cells by tabs, rows by
        // newlines.
        int offset = 0;
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
                if (table.rowCount() > 0) {
                    offset++;
                }
                for (Node childCell = row.getFirstChild(); childCell != null; childCell = childCell.getNext()) {
                    if (!(childCell instanceof TableCell cell)) {
                        continue;
                    }
                    final var inline = render(cell);
                    imageUrls.addAll(inline.imageUrls);
                    if (!cells.isEmpty()) {
                        offset++;
                    }
                    for (final var span : inline.emphasis) {
                        emphasis.add(span.shifted(offset));
                    }
                    for (final var span : inline.struck) {
                        struck.add(span.shifted(offset));
                    }
                    for (final var note : inline.footnotes) {
                        footnotes.add(new Object[] {note[0], note[1], (Integer) note[2] + offset});
                    }
                    offset += inline.points;
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
        Span.put(metadata, emphasis, struck);
        putFootnotes(metadata, footnotes);
        emit(ElementType.Table, table.text(), metadata);
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
     * Adds footnote reference metadata as three parallel lists when references are present.
     * @param metadata mutable element metadata
     * @param footnotes label, number and code-point offset of each reference in appearance order
     */
    private static void putFootnotes(final Map<String, Object> metadata, final List<Object[]> footnotes) {
        if (!footnotes.isEmpty()) {
            metadata.put("footnote_reference_labels", footnotes.stream().map(note -> note[0]).toList());
            metadata.put("footnote_reference_numbers", footnotes.stream().map(note -> note[1]).toList());
            metadata.put("footnote_reference_start_indexes", footnotes.stream().map(note -> note[2]).toList());
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

    /**
     * One annotated inline span of an element's text.
     * @param text span text with surrounding whitespace removed
     * @param tag emphasis tag ({@code "b"}, {@code "i"} or {@code "bi"}), or {@code null} for strikethrough
     * @param start code-point offset of {@code text} in the element text
     */
    private record Span(String text, String tag, int start) {
        /**
         * Returns this span moved by a code-point distance, for table cells placed in the whole table text.
         * @param distance code points preceding the cell in the table text
         * @return shifted span
         */
        Span shifted(final int distance) {
            return new Span(text, tag, start + distance);
        }

        /**
         * Adds emphasis and strikethrough metadata lists in appearance order when spans are present.
         * @param metadata mutable element metadata
         * @param emphasis bold/italic spans
         * @param struck strikethrough spans
         */
        static void put(final Map<String, Object> metadata, final List<Span> emphasis, final List<Span> struck) {
            if (!emphasis.isEmpty()) {
                metadata.put("emphasized_text_contents", emphasis.stream().map(Span::text).toList());
                metadata.put("emphasized_text_tags", emphasis.stream().map(Span::tag).toList());
                metadata.put("emphasized_text_start_indexes", emphasis.stream().map(Span::start).toList());
            }
            if (!struck.isEmpty()) {
                metadata.put("strikethrough_texts", struck.stream().map(Span::text).toList());
                metadata.put("strikethrough_start_indexes", struck.stream().map(Span::start).toList());
            }
        }
    }

    /**
     * Accumulates normalized inline text, code spans, link offsets, emphasis/strikethrough spans and image URLs for one
     * block or cell.
     */
    private final class InlineText {
        /** Normalized inline text buffer. */
        private final StringBuilder text = new StringBuilder();
        /** Collected link metadata entries in inline appearance order. */
        private final List<Map<String, Object>> links = new ArrayList<>();
        /** Collected resolved image URLs in inline appearance order. */
        private final List<String> imageUrls = new ArrayList<>();
        /** Maximal bold/italic runs in appearance order. */
        private final List<Span> emphasis = new ArrayList<>();
        /** Strikethrough spans in appearance order. */
        private final List<Span> struck = new ArrayList<>();
        /** Footnote reference labels, numbers and code-point offsets in appearance order. */
        private final List<Object[]> footnotes = new ArrayList<>();
        /** Number of enclosing strong-emphasis nodes at the current position. */
        private int strong;
        /** Number of enclosing emphasis nodes at the current position. */
        private int italic;
        /** Code-point offset at which the current emphasis run started. */
        private int runPoints;
        /** UTF-16 offset at which the current emphasis run started. */
        private int runChars;
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
            case final StrongEmphasis value -> {
                closeRun();
                strong++;
                children(value);
                closeRun();
                strong--;
            }
            case final Emphasis value -> {
                closeRun();
                italic++;
                children(value);
                closeRun();
                italic--;
            }
            case final Strikethrough value -> {
                final int start = points;
                final int chars = text.length();
                children(value);
                final var span = span(start, chars, null);
                if (span != null) {
                    struck.add(span);
                }
            }
            case final HtmlInline html -> {
                if (context.options().markdownOptions().rawHtml() == MarkdownOptions.RawHtml.PRESERVE_TEXT) {
                    rawHtmlNodes++;
                    literal(html.getLiteral());
                }
            }
            case final FootnoteReference reference -> {
                // As the HTML renderer: a reference whose definition is not rendered (for example one only
                // defined inside another note) stays literal.
                final Integer number = footnoteNumbers.number(reference);
                if (number == null) {
                    literal("[^" + reference.getLabel() + "]");
                } else {
                    flushSpace();
                    footnotes.add(new Object[] {reference.getLabel(), number, points});
                    literal("[" + number + "]");
                }
            }
            default -> children(node);
            }
        }

        /**
         * Ends the current emphasis run at the current position, recording it when it is emphasized and non-blank.
         * Runs break at every emphasis boundary, so nested and adjacent emphasis yield separate spans.
         */
        private void closeRun() {
            if (strong > 0 || italic > 0) {
                final var span = span(runPoints, runChars, (strong > 0 ? "b" : "") + (italic > 0 ? "i" : ""));
                if (span != null) {
                    emphasis.add(span);
                }
            }
            runPoints = points;
            runChars = text.length();
        }

        /**
         * Creates a span for text appended since the given offsets, excluding surrounding whitespace.
         * @param startPoints code-point offset where the span began
         * @param startChars UTF-16 offset where the span began
         * @param tag emphasis tag, or {@code null}
         * @return span, or {@code null} when only whitespace was appended
         */
        private Span span(final int startPoints, final int startChars, final String tag) {
            final String raw = text.substring(startChars);
            final String value = raw.strip();
            if (value.isEmpty()) {
                return null;
            }
            final int leading = raw.indexOf(value);
            return new Span(value, tag, startPoints + raw.codePointCount(0, leading));
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
         * Builds the inline link, image, emphasis and strikethrough metadata map for the rendered block.
         * @return mutable metadata map containing the non-empty lists
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
            Span.put(metadata, emphasis, struck);
            putFootnotes(metadata, footnotes);
            return metadata;
        }
    }
}
