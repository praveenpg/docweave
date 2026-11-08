package io.docweave.partition;

import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.autolink.AutolinkType;
import org.commonmark.ext.footnotes.FootnotesExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/** Stateless CommonMark strategy with optional rendered-DOM delegation; all traversal state is document-local. */
final class MarkdownPartitioner implements Partitioner {
    /**
     * Identifies the Markdown format handled by this strategy.
     * @return {@link DocumentFormat#MARKDOWN}
     */
    public DocumentFormat format() {
        return DocumentFormat.MARKDOWN;
    }

    /**
     * Preservation is available only with EXTRACT_DOM and a capable optional HTML service.
     * @return {@code true}
     */
    @Override
    public boolean supportsNestedTablePreservation() {
        return true;
    }

    /**
     * Parses Markdown within configured nesting/table budgets and partitions either the AST or rendered DOM.
     * @param input synchronous document context
     * @throws IOException if parsing, depth/table limits, HTML service discovery or interruption fails
     */
    public void partition(final PartitionContext input) throws IOException {
        NestedTableSupport.check(this, input.options());
        final var options = input.options().markdownOptions();
        final boolean preserve =
                input.options().nestedTableOptions().mode() == NestedTableOptions.Mode.PRESERVE_METADATA;
        if (preserve && options.rawHtml() != MarkdownOptions.RawHtml.EXTRACT_DOM) {
            throw new PartitionException(PartitionException.Code.UNSUPPORTED_OPTION,
                    "Markdown nested-table preservation requires EXTRACT_DOM");
        }
        // Resolve preservation support before parsing. Default mode retains its existing failure precedence.
        final HtmlExtraction preservation = preserve ? htmlExtraction(true) : null;
        // Read content only after option checks, so unsupported combinations fail before the input is touched.
        PartitionContext context = input;
        String text = input.text();
        if (options.extensions().contains(MarkdownOptions.Extension.FRONT_MATTER)) {
            final var split = MarkdownFrontMatter.split(text);
            text = split.body();
            if (split.invalid() != null) {
                input.warn(PartitionResult.WarningCode.MARKDOWN_FRONT_MATTER_INVALID,
                        "Markdown front matter was kept as literal text: " + split.reason());
                if (!split.invalid().isBlank()) {
                    input.emit(io.docweave.core.ElementType.UncategorizedText, split.invalid(), Map.of());
                }
            }
            context = new FrontMatterContext(input, text, split.values());
        }
        final var builder = Parser.builder()
                                    .maxOpenBlockParsers(options.maxNesting() + 1)
                                    .maxInlineNesting(options.maxNesting() + 1);
        final var parserExtensions = syntaxExtensions(options);
        if (options.tables()) {
            parserExtensions.addFirst(TablesExtension.builder().maxCells(options.tableLimits().maxCells()).build());
        }
        if (!parserExtensions.isEmpty()) {
            builder.extensions(parserExtensions);
        }
        ElementCollector.checkInterrupted();
        Node document;
        try {
            document = builder.build().parse(text);
        } catch (final IllegalArgumentException error) {
            if (options.tables() && error.getMessage() != null
                    && error.getMessage().startsWith("Aborting parsing because maximum number of cells reached")) {
                throw new PartitionException(
                        PartitionException.Code.TABLE_LIMIT, "Markdown table exceeds total-cell budget", error);
            }
            throw error;
        }
        ElementCollector.checkInterrupted();
        // Verify that the parsed CommonMark AST does not exceed maxNesting before walking or rendering it.
        record AtDepth(Node node, int depth) {}
        final var pending = new ArrayDeque<AtDepth>();
        pending.push(new AtDepth(document, 0));
        while (!pending.isEmpty()) {
            ElementCollector.checkInterrupted();
            final var next = pending.pop();
            if (next.depth() > options.maxNesting()) {
                throw new PartitionException(PartitionException.Code.MARKDOWN_DEPTH_LIMIT,
                        "Markdown AST exceeds maxNesting=" + options.maxNesting());
            }
            for (Node child = next.node().getFirstChild(); child != null; child = child.getNext()) {
                pending.push(new AtDepth(child, next.depth() + 1));
            }
        }
        // Delegate to the HTML DOM walker when EXTRACT_DOM is active; otherwise walk the CommonMark AST directly.
        if (options.rawHtml() == MarkdownOptions.RawHtml.EXTRACT_DOM) {
            final var html = preservation == null ? htmlExtraction(false) : preservation;
            if (options.extensions().contains(MarkdownOptions.Extension.FOOTNOTES)) {
                // The renderer omits footnotes it does not place in its notes section; keep their text.
                MarkdownFootnotes.keepMalformed(document);
                MarkdownFootnotes.analyze(document).unwrap(document);
            }
            final var renderer = HtmlRenderer.builder();
            final var renderExtensions = syntaxExtensions(options);
            if (options.tables()) {
                renderExtensions.addFirst(TablesExtension.create());
            }
            if (!renderExtensions.isEmpty()) {
                renderer.extensions(renderExtensions);
            }
            html.partition(context, renderer.build().render(document), options.tableLimits());
        } else {
            new MarkdownAstWalker(context).partition(document);
        }
    }

    /**
     * Creates the opt-in CommonMark syntax extensions selected by the caller, in a fixed order.
     * @param options active Markdown policies
     * @return mutable list of parser/renderer extensions; empty when no syntax extension is selected
     */
    private static List<Extension> syntaxExtensions(final MarkdownOptions options) {
        final var selected = options.extensions();
        final var result = new ArrayList<Extension>();
        if (selected.contains(MarkdownOptions.Extension.STRIKETHROUGH)) {
            result.add(StrikethroughExtension.builder().requireTwoTildes(true).build());
        }
        if (selected.contains(MarkdownOptions.Extension.TASK_LIST_ITEMS)) {
            result.add(TaskListItemsExtension.create());
        }
        if (selected.contains(MarkdownOptions.Extension.AUTOLINKS)) {
            result.add(AutolinkExtension.builder()
                            .linkTypes(EnumSet.of(AutolinkType.URL, AutolinkType.WWW, AutolinkType.EMAIL))
                            .build());
        }
        if (selected.contains(MarkdownOptions.Extension.FOOTNOTES)) {
            result.add(FootnotesExtension.create());
        }
        if (selected.contains(MarkdownOptions.Extension.DEFINITION_LISTS)) {
            result.add(MarkdownDefinitionLists.create());
        }
        return result;
    }

    /**
     * Resolves the same first service used by default DOM extraction; never silently switches providers.
     * @param preserve whether nested-table preservation support is required from the provider
     * @return discovered HTML extraction service
     * @throws IOException if interrupted, no HTML service is installed or preservation is unsupported
     */
    private HtmlExtraction htmlExtraction(final boolean preserve) throws IOException {
        ElementCollector.checkInterrupted();
        final var html =
                java.util.ServiceLoader.load(HtmlExtraction.class)
                        .findFirst()
                        .orElseThrow(()
                                             -> new PartitionException(PartitionException.Code.UNSUPPORTED_FORMAT,
                                                     "Markdown EXTRACT_DOM requires the docweave-html module"));
        if (preserve && !html.supportsNestedTablePreservation()) {
            throw new PartitionException(PartitionException.Code.UNSUPPORTED_OPTION,
                    "Selected HTML extraction service does not support nested-table metadata preservation");
        }
        return html;
    }
}
