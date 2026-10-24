package io.docweave.partition;

import java.io.IOException;

/**
 * Stateless, thread-safe generic XML strategy. Each invocation owns a secure streaming reader and
 * extractor; no schema, stylesheet, XInclude target or external entity is loaded. Domain-specific
 * XML vocabularies can replace this strategy through {@link PartitionerRegistry}.
 * Output uses UncategorizedText because arbitrary XML tags carry no universal semantic category.
 */
final class XmlPartitioner implements Partitioner {
    /** Creates a reusable XML strategy without allocating a parser. */
    XmlPartitioner() {
    }

    /**
     * Returns the strategy key.
     * @return XML
     */
    @Override
    public DocumentFormat format() {
        return DocumentFormat.XML;
    }

    /**
     * Extracts ordered, nonblank text runs with namespace-aware path metadata.
     * @param context document-confined input and emission contract
     * @throws IOException if XML is malformed, forbidden, interrupted or exceeds a budget
     */
    @Override
    public void partition(final PartitionContext context) throws IOException {
        NestedTableSupport.check(this, context.options());
        new XmlStreamExtractor(context).extract();
    }
}
