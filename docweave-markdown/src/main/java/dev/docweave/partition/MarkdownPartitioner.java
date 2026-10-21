package dev.docweave.partition;

import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;

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
     * @param context synchronous document context
     * @throws IOException if parsing, depth/table limits, HTML service discovery or interruption fails
     */
    public void partition(final PartitionContext context) throws IOException {
        NestedTableSupport.check(this, context.options());
        final var options = context.options().markdownOptions();
        final boolean preserve =
                context.options().nestedTableOptions().mode() == NestedTableOptions.Mode.PRESERVE_METADATA;
        if (preserve && options.rawHtml() != MarkdownOptions.RawHtml.EXTRACT_DOM) {
            throw new PartitionException(PartitionException.Code.UNSUPPORTED_OPTION,
                    "Markdown nested-table preservation requires EXTRACT_DOM");
        }
        // Resolve preservation support before parsing. Default mode retains its existing failure precedence.
        final HtmlExtraction preservation = preserve ? htmlExtraction(true) : null;
        final var builder = Parser.builder()
                                    .maxOpenBlockParsers(options.maxNesting() + 1)
                                    .maxInlineNesting(options.maxNesting() + 1);
        if (options.tables()) {
            builder.extensions(List.of(TablesExtension.builder().maxCells(options.tableLimits().maxCells()).build()));
        }
        ElementCollector.checkInterrupted();
        Node document;
        try {
            document = builder.build().parse(context.text());
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
            final var renderer = HtmlRenderer.builder();
            if (options.tables()) {
                renderer.extensions(List.of(TablesExtension.create()));
            }
            html.partition(context, renderer.build().render(document), options.tableLimits());
        } else {
            new MarkdownAstWalker(context).partition(document);
        }
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
