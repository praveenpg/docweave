package io.docweave.json;

/**
 * Immutable limits for saved-element JSON reads; independent of native partitioner limits.
 * Limits bound accepted input, not heap use or elapsed time. Output lists still materialize.
 * Jackson retains its additional number/name/token constraints.
 * @param maxInputBytes positive file byte limit; strings count strict UTF-8 encoded bytes
 * @param maxElements positive number of top-level element objects
 * @param maxNestingDepth positive JSON container depth, including the root array and element object
 * @param maxStringLength positive decoded string limit in UTF-16 code units
 */
public record JsonReadOptions(int maxInputBytes, int maxElements, int maxNestingDepth, int maxStringLength) {
    /**
     * Validates positive, finite resource limits.
     * @param maxInputBytes maximum encoded bytes
     * @param maxElements maximum elements
     * @param maxNestingDepth maximum container depth
     * @param maxStringLength maximum decoded string length
     * @throws IllegalArgumentException if any limit is not positive
     */
    public JsonReadOptions {
        if (maxInputBytes < 1 || maxElements < 1 || maxNestingDepth < 1 || maxStringLength < 1) {
            throw new IllegalArgumentException("JSON read limits must be positive");
        }
    }

    /**
     * Returns the standard bounded read policy.
     * @return 16 MiB input, 100,000 elements, depth 128, and 16 Mi UTF-16 units per string
     */
    public static JsonReadOptions defaults() {
        return new JsonReadOptions(16 * 1024 * 1024, 100_000, 128, 16 * 1024 * 1024);
    }
}
