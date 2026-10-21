package dev.docweave.partition;

/**
 * JDK service-discovery bridge for the HTML module; applications use {@link PartitionerRegistry}.
 * Public construction is required by ServiceLoader; the returned parser implementation is hidden.
 */
public final class HtmlPartitionerProvider implements PartitionerProvider, HtmlExtraction {
    /** Creates a stateless service-discovery bridge. */
    public HtmlPartitionerProvider() {
    }

    /**
     * Returns the module key.
     * @return {@link DocumentFormat#HTML}
     */
    public DocumentFormat format() {
        return DocumentFormat.HTML;
    }

    /**
     * Creates a hidden thread-safe parser strategy.
     * @return reusable HTML strategy
     */
    public Partitioner create() {
        return new HtmlPartitioner();
    }

    /**
     * Detects an HTML head-meta declaration without loading any external resource.
     * @param prefix bounded original encoded bytes
     * @return declared charset, or null
     * @throws PartitionException if the declared charset is unsupported
     */
    @Override
    public java.nio.charset.Charset detectCharset(final byte[] prefix) throws PartitionException {
        return HtmlInputEncoding.detect(prefix);
    }

    /**
     * Advertises bounded nested-table preservation for rendered markup.
     * @return true
     */
    @Override
    public boolean supportsNestedTablePreservation() {
        return true;
    }

    /**
     * Extracts rendered markup for optional Markdown DOM processing.
     * @param context original document context
     * @param html rendered markup
     * @param tableLimits table resource budgets
     * @throws java.io.IOException if processing, limits or interruption fails
     */
    public void partition(final PartitionContext context, final String html, final TableLimits tableLimits)
            throws java.io.IOException {
        new HtmlDomWalker().partition(context, html, tableLimits);
    }
}
