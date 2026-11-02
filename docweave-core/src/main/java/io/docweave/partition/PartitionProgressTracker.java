package io.docweave.partition;

import java.util.BitSet;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Hidden per-call progress state machine. One instance belongs to one partition call; the shared {@link #NONE}
 * instance has no listener and keeps no state. Every listener invocation happens while holding this instance's
 * lock, so snapshots are delivered serially with forward-only phases, a stable total and monotonic counters.
 * A {@link ReentrantLock} is used instead of a monitor so a listener that blocks does not pin a virtual thread's
 * carrier on Java 21.
 *
 * <p>Cancellation latch: once any reporting thread is observed interrupted, PREPARING/EXTRACTING reports from every
 * thread are discarded. ASSEMBLING and DONE are entered only by the engine after its last extraction checkpoint, so
 * they are always delivered while the call is still running; DONE is delivered whenever the result is final.
 */
final class PartitionProgressTracker implements PartitionProgressReporter {
    /** Stateless reporter used when a call has no listener. */
    static final PartitionProgressTracker NONE = new PartitionProgressTracker(null);

    /** Caller listener, or null for the shared no-op instance. */
    private final PartitionProgressListener listener;

    /** Serializes state changes and listener delivery. */
    private final ReentrantLock lock = new ReentrantLock();

    /** Last reported phase; null before the first report. */
    private PartitionProgress.Phase phase;

    /** Declared unit name, or null before {@link #beginUnits(String, int)}. */
    private String unit;

    /** Declared unit total, or {@link PartitionProgress#UNKNOWN_TOTAL}. */
    private int total = (int) PartitionProgress.UNKNOWN_TOTAL;

    /** Units already counted, so concurrent or repeated completions count once. */
    private BitSet done;

    /** Number of distinct completed units. */
    private int completed;

    /** Whether an interrupted reporting thread was observed; extraction reports are then discarded. */
    private boolean cancelled;

    /** Whether the owning call ended; later reports are discarded. */
    private boolean closed;

    /**
     * Creates call-confined progress state.
     *
     * @param listener caller listener, or null to discard every report
     */
    PartitionProgressTracker(final PartitionProgressListener listener) {
        this.listener = listener;
    }

    /**
     * Creates a tracker for an optional listener.
     *
     * @param listener caller listener, or null
     * @return shared no-op instance without a listener, otherwise a new call-confined tracker
     */
    static PartitionProgressTracker of(final PartitionProgressListener listener) {
        return listener == null ? NONE : new PartitionProgressTracker(listener);
    }

    @Override
    public void beginUnits(final String unit, final int total) {
        Objects.requireNonNull(unit, "unit");
        if (unit.isBlank() || total < 0) {
            throw new IllegalArgumentException("unit must be nonblank and total nonnegative");
        }
        if (listener == null) {
            return;
        }
        lock.lock();
        try {
            if (stopped() || this.unit != null || phase != PartitionProgress.Phase.EXTRACTING) {
                return;
            }
            this.unit = unit;
            this.total = total;
            this.done = new BitSet(total);
            deliver();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void unitCompleted(final int index) {
        if (index < 0) {
            throw new IllegalArgumentException("index must be nonnegative");
        }
        if (listener == null) {
            return;
        }
        lock.lock();
        try {
            if (stopped() || unit == null || phase != PartitionProgress.Phase.EXTRACTING || index >= total
                    || done.get(index)) {
                return;
            }
            done.set(index);
            completed++;
            deliver();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Advances to a later phase and reports it; earlier or repeated phases are ignored. PREPARING and EXTRACTING
     * honor the cancellation latch; ASSEMBLING and DONE are delivered until the call is closed.
     *
     * @param next nonnull later phase
     */
    void enter(final PartitionProgress.Phase next) {
        if (listener == null) {
            return;
        }
        lock.lock();
        try {
            if (closed || phase != null && next.compareTo(phase) <= 0) {
                return;
            }
            final boolean extraction = next.compareTo(PartitionProgress.Phase.EXTRACTING) <= 0;
            if (extraction && stopped()) {
                return;
            }
            phase = next;
            deliver();
        } finally {
            lock.unlock();
        }
    }

    /** Ends the owning call; every later report is discarded. */
    void close() {
        if (listener == null) {
            return;
        }
        lock.lock();
        try {
            closed = true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Checks whether extraction reports must be discarded, latching cancellation observed on the current thread.
     * Callers hold the lock.
     *
     * @return true after close or once any reporting thread was seen interrupted
     */
    private boolean stopped() {
        if (Thread.currentThread().isInterrupted()) {
            cancelled = true;
        }
        return closed || cancelled;
    }

    /**
     * Offers the current snapshot while holding the lock, isolating every listener failure except
     * {@link VirtualMachineError}. Callers hold the lock.
     */
    private void deliver() {
        final var progress = unit == null ? PartitionProgress.indeterminate(phase)
                                          : new PartitionProgress(phase, unit, completed, total);
        try {
            listener.onProgress(progress);
        } catch (final VirtualMachineError fatal) {
            throw fatal;
        } catch (final Throwable ignored) {
            // Listener failures, including undeclared checked exceptions, never alter partitioning.
            if (ignored instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
