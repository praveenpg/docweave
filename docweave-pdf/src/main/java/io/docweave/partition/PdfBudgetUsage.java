package io.docweave.partition;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Records admitted budget charges per page while delegating every decision to the caller's budget. Exceptions other
 * than {@link PartitionException} and cancellation are carried as {@link Failure} and restored by the partitioner.
 */
final class PdfBudgetUsage implements PdfBudget {
    /** Deciding budget. */
    private final PdfBudget delegate;
    /** Admitted amounts per page and kind, removed when the page is taken. */
    private final ConcurrentMap<Integer, AtomicLongArray> pages = new ConcurrentHashMap<>();

    /**
     * Wraps a budget.
     * @param delegate deciding budget
     */
    PdfBudgetUsage(final PdfBudget delegate) {
        this.delegate = delegate;
    }

    @Override
    public void charge(final Kind kind, final long amount, final int page) throws IOException {
        try {
            delegate.charge(kind, amount, page);
        } catch (final PartitionException | java.io.InterruptedIOException failure) {
            throw failure;
        } catch (final IOException | RuntimeException failure) {
            throw new Failure(failure);
        }
        pages.computeIfAbsent(page, ignored -> new AtomicLongArray(Kind.values().length))
                .addAndGet(kind.ordinal(), amount);
    }

    @Override
    public void pageCompleted(final int page) throws IOException {
        try {
            delegate.pageCompleted(page);
        } catch (final PartitionException | java.io.InterruptedIOException failure) {
            throw failure;
        } catch (final IOException | RuntimeException failure) {
            throw new Failure(failure);
        }
    }

    /**
     * Carries a caller budget's own exception through page tasks and PDFBox callbacks, so the partition call can
     * rethrow it unchanged instead of classifying it as a parsing failure.
     */
    static final class Failure extends RuntimeException {
        /**
         * Wraps the budget's exception.
         * @param cause original exception
         */
        Failure(final Exception cause) {
            super(cause.getMessage(), cause, false, false);
        }

        /**
         * Returns the original checked exception, or throws the original unchecked one.
         * @return original IOException
         */
        IOException original() {
            if (getCause() instanceof final RuntimeException runtime) {
                throw runtime;
            }
            return (IOException) getCause();
        }
    }

    /**
     * Returns and forgets the usage of a completed page.
     * @param page one-based page whose charges have all arrived
     * @return positive amounts by kind
     */
    Map<Kind, Long> take(final int page) {
        final var counts = pages.remove(page);
        final var usage = new EnumMap<Kind, Long>(Kind.class);
        if (counts != null) {
            for (final var kind : Kind.values()) {
                final long value = counts.get(kind.ordinal());
                if (value > 0) {
                    usage.put(kind, value);
                }
            }
        }
        return usage;
    }
}
