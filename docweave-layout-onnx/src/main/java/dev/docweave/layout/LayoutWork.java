package dev.docweave.layout;

import dev.docweave.partition.PartitionException;

import java.io.IOException;

/** Fixed bounded model-object/NMS/slot traversal, shared across all table candidates in one call. */
final class LayoutWork {
    /** Fixed geometry ceiling independent of permissive caller dimensions. */
    private static final int MAX_WORK = 2_000_000;
    /** Shared total call deadline. */
    private final LayoutDeadline deadline;
    /** Geometry steps consumed. */
    private int used;

    /**
     * Starts a call-confined work counter.
     * @param deadline existing total recognition budget
     */
    LayoutWork(final LayoutDeadline deadline) {
        this.deadline = deadline;
    }

    /**
     * Admits one geometry/slot operation and checks cooperative cancellation periodically.
     * @throws IOException if deadline, interruption or fixed geometry work is exceeded
     */
    void step() throws IOException {
        if ((used & 255) == 0) {
            deadline.remaining();
        }
        if (++used > MAX_WORK) {
            throw new PartitionException(PartitionException.Code.TABLE_LIMIT, "ONNX table geometry exceeds work limit");
        }
    }
}
