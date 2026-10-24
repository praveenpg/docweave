package io.docweave.partition;

/**
 * Immutable TXT partition-size policy, measured in Unicode code points after whitespace
 * normalization and optional bullet removal. Sizing follows paragraph grouping. A maximum is
 * hard: prefer the last whitespace in its window, otherwise split at a code-point boundary.
 * Whitespace at fragment edges is omitted; surrogate pairs remain intact, but grapheme clusters
 * may split. Fragments keep the category of their source paragraph.
 *
 * <p>The minimum is a soft merge target for consecutive prose paragraphs with the same output
 * category. Titles and list items are barriers even when classification is disabled. Merging
 * inserts one space and may cross blank-line or explicit line boundaries. It never exceeds the
 * maximum, drops short text, or pads output. Final fragments and structural elements may be below
 * the minimum. These rules are local extraction contracts, not upstream sentence-tokenizer parity.</p>
 * @param minPartition nonnegative best-effort prose merge target, default zero (no merging)
 * @param maxPartition positive hard partition limit, default Integer.MAX_VALUE (no practical limit)
 */
public record TxtOptions(int minPartition, int maxPartition) {
    /**
     * Validates code-point size limits without processing input.
     * @param minPartition nonnegative minimum no larger than the maximum
     * @param maxPartition positive maximum
     * @throws IllegalArgumentException if the bounds are invalid
     */
    public TxtOptions {
        if (minPartition < 0 || maxPartition < 1 || minPartition > maxPartition) {
            throw new IllegalArgumentException("Require 0 <= minPartition <= maxPartition and maxPartition > 0");
        }
    }

    /**
     * Returns the size policy preserving historical TXT partition boundaries.
     * @return no minimum merging and an Integer.MAX_VALUE maximum
     */
    public static TxtOptions defaults() {
        return new TxtOptions(0, Integer.MAX_VALUE);
    }
}
