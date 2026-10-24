package io.docweave.partition;

import java.util.Objects;

/**
 * Immutable nested-table policy and additional document-wide preservation budgets.
 * These limits do not replace format/archive/table limits or guarantee a heap bound.
 * Adapters must explicitly advertise preservation support; selecting it never silently falls back.
 *
 * @param mode nonnull extraction policy
 * @param maxNestedTables positive total preserved-child limit across root tables
 * @param maxNestedDepth nested depth in [1, 256], with an immediate child at depth one
 * @param maxMetadataCodePoints positive cumulative descriptor budget: string keys/values in code points,
 *        one unit per scalar/container, including repeated copies; excludes the fixed root metadata envelope
 */
public record NestedTableOptions(Mode mode, int maxNestedTables, int maxNestedDepth, long maxMetadataCodePoints) {
    /** Nested-table representation policy. */
    public enum Mode {
        /** Existing flattened text/HTML and diagnostics, without preservation descriptors. */ FLATTEN,
        /** Preserve bounded child snapshots in root-table metadata when the adapter supports it. */ PRESERVE_METADATA
    }

    /**
     * Validates policy and limits without allocating descriptors.
     * @param mode nonnull representation policy
     * @param maxNestedTables positive document-wide child count
     * @param maxNestedDepth depth in [1, 256]
     * @param maxMetadataCodePoints positive cumulative payload limit
     * @throws NullPointerException if mode is null
     * @throws IllegalArgumentException if a limit is invalid
     */
    public NestedTableOptions {
        Objects.requireNonNull(mode, "mode");
        if (maxNestedTables < 1 || maxNestedDepth < 1 || maxNestedDepth > 256 || maxMetadataCodePoints < 1) {
            throw new IllegalArgumentException("Invalid nested-table budgets");
        }
    }

    /**
     * Returns unchanged flattening with budgets available for an explicit future selection.
     * @return FLATTEN, 256 children, depth 16 and 1 Mi code points
     */
    public static NestedTableOptions defaults() {
        return new NestedTableOptions(Mode.FLATTEN, 256, 16, 1024 * 1024);
    }

    /**
     * Selects preservation with conservative initial budgets; unsupported adapters reject it.
     * @return PRESERVE_METADATA, 256 children, depth 16 and 1 Mi code points
     */
    public static NestedTableOptions preserveMetadata() {
        return new NestedTableOptions(Mode.PRESERVE_METADATA, 256, 16, 1024 * 1024);
    }
}
