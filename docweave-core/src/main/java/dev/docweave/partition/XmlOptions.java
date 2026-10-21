package dev.docweave.partition;

/**
 * Immutable generic XML extraction policies. DTDs and external resources are always forbidden.
 * Elements delimit text runs: mixed content before, inside and after children remains ordered,
 * without duplicating descendant text. Comments and processing instructions produce no output.
 * Limits bound nesting and start-element count, including empty elements; input bytes and emitted
 * elements remain governed by {@link PartitionOptions}. These are not execution deadlines.
 *
 * @param maxDepth maximum root-inclusive nesting, in [1, 256]
 * @param maxNodes positive maximum number of element starts
 * @param preserveWhitespace whether text runs retain XML-decoded whitespace; otherwise normalize it
 * @param includeAttributes whether namespace-qualified attributes accompany each text run
 */
public record XmlOptions(int maxDepth, int maxNodes, boolean preserveWhitespace, boolean includeAttributes) {
    /**
     * Validates immutable policies.
     * @param maxDepth root-inclusive nesting in [1, 256]
     * @param maxNodes positive element-node budget
     * @param preserveWhitespace whether to retain text whitespace
     * @param includeAttributes whether to retain attributes
     * @throws IllegalArgumentException if a limit is invalid
     */
    public XmlOptions {
        if (maxDepth < 1 || maxDepth > 256) {
            throw new IllegalArgumentException("maxDepth must be in [1, 256]");
        }
        if (maxNodes < 1) {
            throw new IllegalArgumentException("maxNodes must be positive");
        }
    }

    /**
     * Returns normalized text with attributes, depth 256 and 100,000 element nodes.
     * @return bounded default policy
     */
    public static XmlOptions defaults() {
        return new XmlOptions(256, 100_000, false, true);
    }
}
