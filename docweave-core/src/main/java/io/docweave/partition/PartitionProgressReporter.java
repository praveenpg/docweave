package io.docweave.partition;

/**
 * Strategy-side progress sink for one partition call, obtained from {@link PartitionContext#progress()}.
 *
 * <p>A {@link Partitioner} with a reliable work-unit count declares it once with {@link #beginUnits(String, int)}
 * and reports each unit with {@link #unitCompleted(int)} only after all required work for that unit has finished,
 * including emission of its elements. Strategies without a reliable count need not call this interface; their
 * extraction phase is then reported as indeterminate.
 *
 * <p>Implementations are thread-safe: units may complete concurrently, and each unit index counts exactly once.
 * Reports are dropped when no listener was supplied, when they arrive before {@code beginUnits} or after the call
 * ended, and when they repeat an index or fall outside the declared total. Progress reporting never fails
 * partitioning except for invalid arguments. A total of zero is valid and reports a determinate {@code 0/0}.
 * Units are indexed by {@code int} because they are tracked in a bit set and the built-in unit, the PDF page, is
 * {@code int}-indexed; {@link PartitionProgress} widens the counters to {@code long}.
 */
public interface PartitionProgressReporter {
    /**
     * Returns a shared reporter that validates arguments and discards every report.
     *
     * @return stateless thread-safe no-op reporter
     */
    static PartitionProgressReporter none() {
        return PartitionProgressTracker.NONE;
    }

    /**
     * Declares the stable unit total for the extraction phase; later declarations in the same call are ignored.
     *
     * @param unit nonblank unit name such as {@code "pages"}
     * @param total nonnegative number of units
     * @throws NullPointerException if unit is null
     * @throws IllegalArgumentException if unit is blank or total is negative
     */
    void beginUnits(String unit, int total);

    /**
     * Marks one unit as fully processed; repeated or out-of-range indices are ignored.
     *
     * @param index zero-based unit index
     * @throws IllegalArgumentException if index is negative
     */
    void unitCompleted(int index);
}
