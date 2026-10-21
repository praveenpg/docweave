package dev.docweave.partition;

/** Shared fail-closed option check for facade dispatch and direct built-in SPI invocation. */
final class NestedTableSupport {
    /** Prevents instantiation of this static utility class. */
    private NestedTableSupport() {
    }

    /**
     * Verifies that {@code partitioner} supports nested-table metadata preservation when requested by {@code options}.
     *
     * @param partitioner selected format partitioner strategy
     * @param options active partition options
     * @throws java.io.IOException if nested-table preservation is requested on an unsupported format or interrupted
     */
    static void check(final Partitioner partitioner, final PartitionOptions options) throws java.io.IOException {
        ElementCollector.checkInterrupted();
        if (options.nestedTableOptions().mode() == NestedTableOptions.Mode.PRESERVE_METADATA
                && !partitioner.supportsNestedTablePreservation()) {
            throw new PartitionException(PartitionException.Code.UNSUPPORTED_OPTION,
                    "Selected partitioner does not support nested-table metadata preservation");
        }
    }
}
