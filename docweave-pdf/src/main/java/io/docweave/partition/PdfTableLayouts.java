package io.docweave.partition;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.List;

/** Shared bounded provider invocation and complete-table validation, separate from text ownership. */
final class PdfTableLayouts {
    /** Utility class. */
    private PdfTableLayouts() {
    }

    /**
     * Retains a declared prefix for chunk repetition only when no anchor crosses into body rows.
     * Crossing cells may contain body text; repeating them could duplicate unqualified source text.
     * The original provider declaration and topology remain unchanged when this hint is omitted.
     * @param layout already validated complete provider table
     * @param work shared page geometry and cancellation counter
     * @return declared prefix or zero when unknown or crossed by an anchor
     * @throws IOException if page work or cancellation prevents the bounded check
     */
    static int chunkHeaderRows(final PdfTableLayoutProvider.Table layout, final PdfTableWork work) throws IOException {
        final int headers = layout.headerRows();
        if (headers == 0) {
            return 0;
        }
        for (final var anchor : layout.anchors()) {
            work.step();
            if (anchor.row() < headers && anchor.rowSpan() > headers - anchor.row()) {
                return 0;
            }
        }
        return headers;
    }

    /**
     * Calls a provider once and validates all raw claimed layouts before ownership filtering.
     * No atomic slot matrix is allocated: disjoint in-range rectangles plus exact summed area prove coverage.
     * @param provider synchronous strategy
     * @param image borrowed raster
     * @param limits table dimensions/text policy
     * @param options aggregate raw anchor ceiling
     * @param work caller's shared bounded geometry and cancellation counter
     * @return stable claimed layouts
     * @throws IOException if provider output, geometry/topology, budgets or cancellation fail
     */
    static List<PdfTableLayoutProvider.Table> locate(final PdfTableLayoutProvider provider, final BufferedImage image,
            final TableLimits limits, final PdfOcrOptions options, final PdfTableWork work) throws IOException {
        ElementCollector.checkInterrupted();
        final List<PdfTableLayoutProvider.Table> result;
        try {
            result = provider.locate(image);
        } catch (final PartitionException | InterruptedIOException failure) {
            throw failure;
        } catch (final IOException | RuntimeException failure) {
            throw new PartitionException(
                    PartitionException.Code.OCR_FAILURE, "PDF table layout provider failed", failure);
        }
        return validate(result, image, limits, options, work);
    }

    /**
     * Validates claimed topology without allocating an atomic-slot matrix or invoking a provider.
     * @param result raw nonnull claimed layouts
     * @param image borrowed coordinate frame
     * @param limits structured dimension policy
     * @param options raw table/anchor ceiling
     * @param work caller's shared bounded geometry and cancellation counter
     * @return the validated claimed layouts
     * @throws IOException if a claim, budget or cancellation check fails
     */
    static List<PdfTableLayoutProvider.Table> validate(final List<PdfTableLayoutProvider.Table> result,
            final BufferedImage image, final TableLimits limits, final PdfOcrOptions options, final PdfTableWork work)
            throws IOException {
        ElementCollector.checkInterrupted();
        if (result == null) {
            throw invalid();
        }
        if (result.size() > options.maxTableCells()) {
            throw limit("PDF layout provider exceeds maxTableCells");
        }
        long anchors = 0;
        for (int table = 0; table < result.size(); table++) {
            work.step();
            final var layout = result.get(table);
            if (layout == null) {
                throw invalid();
            }
            if (layout.rows() > limits.maxRows() || layout.columns() > limits.maxColumns()
                    || (long) layout.rows() * layout.columns() > limits.maxCells()) {
                throw limit("Provider PDF table exceeds table dimensions");
            }
            anchors += layout.anchors().size();
            if (anchors > options.maxTableCells() || layout.anchors().size() > limits.maxCells()) {
                throw limit("PDF layout provider exceeds maxTableCells");
            }
            if (layout.bounds().right() > image.getWidth() || layout.bounds().bottom() > image.getHeight()) {
                throw invalid();
            }
            long covered = 0;
            for (int index = 0; index < layout.anchors().size(); index++) {
                work.step();
                final var anchor = layout.anchors().get(index);
                if ((long) anchor.row() + anchor.rowSpan() > layout.rows()
                        || (long) anchor.column() + anchor.columnSpan() > layout.columns()
                        || !contains(layout.bounds(), anchor.bounds())) {
                    throw invalid();
                }
                covered += (long) anchor.rowSpan() * anchor.columnSpan();
                if (covered > (long) layout.rows() * layout.columns()) {
                    throw invalid();
                }
                for (int previous = 0; previous < index; previous++) {
                    work.step();
                    final var other = layout.anchors().get(previous);
                    if (!ordered(anchor, other) || overlaps(anchor.bounds(), other.bounds())
                            || anchor.row() < (long) other.row() + other.rowSpan()
                                       && other.row() < (long) anchor.row() + anchor.rowSpan()
                                       && anchor.column() < (long) other.column() + other.columnSpan()
                                       && other.column() < (long) anchor.column() + anchor.columnSpan()) {
                        throw invalid();
                    }
                }
            }
            if (covered != (long) layout.rows() * layout.columns()) {
                throw invalid();
            }
            for (int previous = 0; previous < table; previous++) {
                work.step();
                if (overlaps(layout.bounds(), result.get(previous).bounds())) {
                    throw invalid();
                }
            }
        }
        return result;
    }

    /**
     * Requires shared logical rows/columns to preserve physical left-to-right/top-to-bottom order.
     * @param first first anchor
     * @param second second anchor
     * @return whether disjoint logical positions agree with source geometry
     */
    private static boolean ordered(
            final PdfTableLayoutProvider.Anchor first, final PdfTableLayoutProvider.Anchor second) {
        final boolean sharedRows = first.row() < (long) second.row() + second.rowSpan()
                                   && second.row() < (long) first.row() + first.rowSpan();
        final boolean sharedColumns = first.column() < (long) second.column() + second.columnSpan()
                                      && second.column() < (long) first.column() + first.columnSpan();
        return !(sharedRows
                         && ((long) first.column() + first.columnSpan() <= second.column()
                                         && first.bounds().right() > second.bounds().left()
                                 || (long) second.column() + second.columnSpan() <= first.column()
                                            && second.bounds().right() > first.bounds().left())
                 || sharedColumns
                            && ((long) first.row() + first.rowSpan() <= second.row()
                                            && first.bounds().bottom() > second.bounds().top()
                                    || (long) second.row() + second.rowSpan() <= first.row()
                                               && second.bounds().bottom() > first.bounds().top()));
    }

    /**
     * Tests full source-envelope containment without another coordinate transform.
     * @param outer enclosing provider rectangle
     * @param inner anchor rectangle
     * @return whether every inner edge is contained
     */
    private static boolean contains(final PdfTableLayoutProvider.Box outer, final PdfTableLayoutProvider.Box inner) {
        return outer.left() <= inner.left() && outer.top() <= inner.top() && outer.right() >= inner.right()
                && outer.bottom() >= inner.bottom();
    }

    /**
     * Tests positive interior overlap, allowing adjacent cells/tables to share borders.
     * @param first first source rectangle
     * @param second second source rectangle
     * @return whether the interiors intersect
     */
    static boolean overlaps(final PdfTableLayoutProvider.Box first, final PdfTableLayoutProvider.Box second) {
        return first.left() < second.right() && second.left() < first.right() && first.top() < second.bottom()
                && second.top() < first.bottom();
    }

    /**
     * Creates a content-free invalid provider diagnostic.
     * @return typed invalid output failure
     */
    private static PartitionException invalid() {
        return new PartitionException(PartitionException.Code.OCR_FAILURE, "Invalid PDF table layout provider output");
    }

    /**
     * Creates a stable structured-table budget failure.
     * @param message content-free policy diagnostic
     * @return typed table limit
     */
    private static PartitionException limit(final String message) {
        return new PartitionException(PartitionException.Code.TABLE_LIMIT, message);
    }
}
