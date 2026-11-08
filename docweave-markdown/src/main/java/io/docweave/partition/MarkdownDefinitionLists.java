package io.docweave.partition;

import org.commonmark.Extension;
import org.commonmark.node.Block;
import org.commonmark.node.CustomBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.parser.Parser;
import org.commonmark.parser.PostProcessor;
import org.commonmark.parser.block.AbstractBlockParser;
import org.commonmark.parser.block.AbstractBlockParserFactory;
import org.commonmark.parser.block.BlockContinue;
import org.commonmark.parser.block.BlockStart;
import org.commonmark.parser.block.MatchedBlockParser;
import org.commonmark.parser.block.ParserState;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.html.HtmlWriter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

/**
 * Opt-in CommonMark extension for PHP Markdown Extra definition lists.
 *
 * <p>A line whose first non-space character (indented fewer than four columns) is {@code :} followed by a space or tab
 * and content starts a definition when the immediately preceding sibling block is a paragraph (optionally separated by
 * blank lines) or another definition. Each line of that paragraph becomes a term. Definition content continues on
 * lines indented at least to the content column, and paragraphs accept CommonMark lazy continuation lines.
 * Consecutive terms and definitions are grouped into one {@link DefinitionList}. No other syntax changes.</p>
 */
final class MarkdownDefinitionLists implements Parser.ParserExtension, HtmlRenderer.HtmlRendererExtension {
    /**
     * Creates the extension.
     * @return new stateless extension
     */
    static Extension create() {
        return new MarkdownDefinitionLists();
    }

    /** Use {@link #create()}. */
    private MarkdownDefinitionLists() {}

    /**
     * Registers the definition block parser and the term/grouping post-processor.
     * @param builder parser builder
     */
    @Override
    public void extend(final Parser.Builder builder) {
        builder.customBlockParserFactory(new DescriptionParser.Factory());
        builder.postProcessor(new Grouping());
    }

    /**
     * Registers the {@code <dl>}/{@code <dt>}/{@code <dd>} renderer used by EXTRACT_DOM.
     * @param builder renderer builder
     */
    @Override
    public void extend(final HtmlRenderer.Builder builder) {
        builder.nodeRendererFactory(Renderer::new);
    }

    /** A group of consecutive terms and definitions. */
    static final class DefinitionList extends CustomBlock {}

    /** One term; its children are inline nodes. */
    static final class DefinitionTerm extends CustomBlock {}

    /** One definition; its children are blocks. */
    static final class DefinitionDescription extends CustomBlock {}

    /** Container parser for one definition. */
    private static final class DescriptionParser extends AbstractBlockParser {
        /** Parsed block. */
        private final DefinitionDescription block = new DefinitionDescription();
        /** Column, relative to the enclosing container, at which content lines must be indented. */
        private final int contentIndent;

        /**
         * Creates a definition parser.
         * @param contentIndent columns of the marker and its following whitespace
         */
        private DescriptionParser(final int contentIndent) {
            this.contentIndent = contentIndent;
        }

        @Override
        public Block getBlock() {
            return block;
        }

        @Override
        public boolean isContainer() {
            return true;
        }

        @Override
        public boolean canContain(final Block childBlock) {
            return true;
        }

        @Override
        public BlockContinue tryContinue(final ParserState state) {
            if (state.isBlank()) {
                return block.getFirstChild() == null ? BlockContinue.none()
                                                     : BlockContinue.atIndex(state.getNextNonSpaceIndex());
            }
            if (state.getIndent() >= contentIndent) {
                return BlockContinue.atColumn(state.getColumn() + contentIndent);
            }
            return BlockContinue.none();
        }

        /** Starts a definition at a {@code :} marker that follows a paragraph or another definition. */
        private static final class Factory extends AbstractBlockParserFactory {
            @Override
            public BlockStart tryStart(final ParserState state, final MatchedBlockParser matched) {
                if (state.getIndent() >= 4) {
                    return BlockStart.none();
                }
                final CharSequence line = state.getLine().getContent();
                final int marker = state.getNextNonSpaceIndex();
                if (marker >= line.length() || line.charAt(marker) != ':') {
                    return BlockStart.none();
                }
                // The marker must be followed by whitespace and then content on the same line.
                int column = state.getColumn() + state.getIndent() + 1;
                int index = marker + 1;
                final int markerColumn = column;
                while (index < line.length() && (line.charAt(index) == ' ' || line.charAt(index) == '\t')) {
                    column += line.charAt(index) == '\t' ? 4 - (column % 4) : 1;
                    index++;
                }
                if (index == marker + 1 || index >= line.length()) {
                    return BlockStart.none();
                }
                if (!follows(matched)) {
                    return BlockStart.none();
                }
                // As for list items, five or more spaces after the marker start indented content one column in.
                final int spaces = column - markerColumn;
                final int contentColumn = spaces > 4 ? markerColumn + 1 : column;
                final int contentIndent = contentColumn - state.getColumn();
                return BlockStart.of(new DescriptionParser(contentIndent)).atColumn(contentColumn);
            }

            /**
             * Checks that the marker continues an open paragraph or follows a closed paragraph or definition.
             * @param matched last matched block parser
             * @return whether a definition may start here
             */
            private static boolean follows(final MatchedBlockParser matched) {
                final Block block = matched.getMatchedBlockParser().getBlock();
                if (block instanceof Paragraph) {
                    return !matched.getParagraphLines().isEmpty();
                }
                final Node previous = block.getLastChild();
                return previous instanceof Paragraph || previous instanceof DefinitionDescription;
            }
        }
    }

    /** Converts the paragraph before each first definition into terms and groups consecutive entries. */
    private static final class Grouping implements PostProcessor {
        @Override
        public Node process(final Node document) {
            final var descriptions = new ArrayList<DefinitionDescription>();
            final var pending = new ArrayDeque<Node>();
            pending.push(document);
            while (!pending.isEmpty()) {
                final Node node = pending.pop();
                if (node instanceof final DefinitionDescription description) {
                    descriptions.add(description);
                }
                for (Node child = node.getLastChild(); child != null; child = child.getPrevious()) {
                    pending.push(child);
                }
            }
            for (final var description : descriptions) {
                if (description.getPrevious() instanceof final Paragraph paragraph) {
                    terms(paragraph);
                }
            }
            for (final var description : descriptions) {
                if (description.getParent() != null && !(description.getParent() instanceof DefinitionList)) {
                    group(description);
                }
            }
            return document;
        }

        /**
         * Replaces a paragraph with one term per line (soft or hard line break), keeping the inline nodes.
         * @param paragraph paragraph immediately before a definition
         */
        private static void terms(final Paragraph paragraph) {
            DefinitionTerm term = new DefinitionTerm();
            for (Node child = paragraph.getFirstChild(); child != null;) {
                final Node next = child.getNext();
                if (child instanceof SoftLineBreak || child instanceof HardLineBreak) {
                    child.unlink();
                    if (term.getFirstChild() != null) {
                        paragraph.insertBefore(term);
                        term = new DefinitionTerm();
                    }
                } else {
                    term.appendChild(child);
                }
                child = next;
            }
            if (term.getFirstChild() != null) {
                paragraph.insertBefore(term);
            }
            paragraph.unlink();
        }

        /**
         * Wraps the run of consecutive terms and definitions around a definition into one list.
         * @param description any definition of the run
         */
        private static void group(final DefinitionDescription description) {
            Node first = description;
            while (entry(first.getPrevious())) {
                first = first.getPrevious();
            }
            final var list = new DefinitionList();
            first.insertBefore(list);
            for (Node node = first; entry(node);) {
                final Node next = node.getNext();
                list.appendChild(node);
                node = next;
            }
        }

        /**
         * Tests whether a node belongs to a definition list run.
         * @param node sibling node, possibly {@code null}
         * @return whether it is a term or definition
         */
        private static boolean entry(final Node node) {
            return node instanceof DefinitionTerm || node instanceof DefinitionDescription;
        }
    }

    /** Renders definition lists as {@code <dl>} markup. */
    private static final class Renderer implements NodeRenderer {
        /** Tag names by node type. */
        private static final Map<Class<? extends Node>, String> TAGS =
                Map.of(DefinitionList.class, "dl", DefinitionTerm.class, "dt", DefinitionDescription.class, "dd");
        /** Rendering context. */
        private final HtmlNodeRendererContext context;
        /** Output writer. */
        private final HtmlWriter html;

        /**
         * Creates a renderer.
         * @param context rendering context
         */
        private Renderer(final HtmlNodeRendererContext context) {
            this.context = context;
            this.html = context.getWriter();
        }

        @Override
        public Set<Class<? extends Node>> getNodeTypes() {
            return TAGS.keySet();
        }

        @Override
        public void render(final Node node) {
            final String tag = TAGS.get(node.getClass());
            html.line();
            html.tag(tag, context.extendAttributes(node, tag, Map.of()));
            for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                context.render(child);
            }
            html.tag("/" + tag);
            html.line();
        }
    }
}
