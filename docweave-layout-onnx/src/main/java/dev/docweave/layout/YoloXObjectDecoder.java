package dev.docweave.layout;

import dev.docweave.partition.PartitionException;
import dev.docweave.partition.PdfTableLayoutProvider;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Fixed-grid YOLOX decoding and bounded class-agnostic suppression before selecting table class8. */
final class YoloXObjectDecoder {
    /** Fixed exported query count across strides8,16,32. */
    static final int QUERIES = 16_128;
    /** Fixed candidate ceiling before quadratic suppression work. */
    private static final int MAX_CANDIDATES = 2048;

    /**
     * One finite source-frame contender, including competing non-table classes.
     * @param query original stable output index
     * @param label strongest published class
     * @param score objectness times class probability, not calibrated accuracy
     * @param left unclipped source left edge
     * @param top unclipped source top edge
     * @param right unclipped source right edge
     * @param bottom unclipped source bottom edge
     */
    private record Proposal(int query, int label, double score, double left, double top, double right, double bottom) {}

    /** Prevents utility construction. */
    private YoloXObjectDecoder() {
    }

    /**
     * Checks every raw score/coordinate before retaining any selected table layout.
     * Class-agnostic inclusive-area NMS uses0.1, with stable query ties and post-suppression clipping.
     * @param values fixed16128x16 native output view, borrowed only for this call
     * @param pixels original frame and nominal resize ratio
     * @param options caller-selected score and raw-query limits
     * @param deadline shared total pipeline deadline
     * @param work shared suppression/crop/topology comparison budget
     * @return immutable clipped ordinary-table objects for the existing crop planner
     * @throws IOException if schema, numeric values, candidate/query/work or cancellation limits fail
     */
    static List<LayoutObject> decode(final FloatBuffer values, final YoloXPixels pixels, final OnnxTableOptions options,
            final LayoutDeadline deadline, final LayoutWork work) throws IOException {
        if (values.remaining() != QUERIES * 16) {
            throw invalid();
        }
        if (QUERIES > options.maxQueries()) {
            throw new PartitionException(PartitionException.Code.OCR_LIMIT, "YOLOX output exceeds maxQueries");
        }
        final var candidates = new ArrayList<Proposal>();
        int query = 0;
        final int start = values.position();
        for (final int stride : new int[] {8, 16, 32}) {
            for (int row = 0; row < YoloXImageTransform.HEIGHT / stride; row++) {
                for (int column = 0; column < YoloXImageTransform.WIDTH / stride; column++) {
                    deadline.remaining();
                    final int offset = start + query * 16;
                    for (int coordinate = 0; coordinate < 4; coordinate++) {
                        if (!Float.isFinite(values.get(offset + coordinate))) {
                            throw invalid();
                        }
                    }
                    int label = 0;
                    float strongest = probability(values.get(offset + 5));
                    for (int category = 1; category < 11; category++) {
                        final float score = probability(values.get(offset + 5 + category));
                        if (score > strongest) {
                            strongest = score;
                            label = category;
                        }
                    }
                    final float score = probability(values.get(offset + 4)) * strongest;
                    if (score > options.minimumScore()) {
                        final double centerX = (values.get(offset) + column) * stride;
                        final double centerY = (values.get(offset + 1) + row) * stride;
                        final double width = (float) Math.exp(values.get(offset + 2)) * stride;
                        final double height = (float) Math.exp(values.get(offset + 3)) * stride;
                        if (!Double.isFinite(centerX) || !Double.isFinite(centerY) || !Double.isFinite(width)
                                || !Double.isFinite(height) || width <= 0 || height <= 0) {
                            throw invalid();
                        }
                        candidates.add(new Proposal(query, label, score, (centerX - width / 2) / pixels.ratio(),
                                (centerY - height / 2) / pixels.ratio(), (centerX + width / 2) / pixels.ratio(),
                                (centerY + height / 2) / pixels.ratio()));
                        if (candidates.size() > MAX_CANDIDATES) {
                            throw new PartitionException(
                                    PartitionException.Code.OCR_LIMIT, "YOLOX output exceeds fixed candidate limit");
                        }
                    }
                    query++;
                }
            }
        }
        final var kept = new ArrayList<Proposal>();
        for (final var candidate : candidates.stream()
                        .sorted(Comparator.comparingDouble(Proposal::score)
                                        .reversed()
                                        .thenComparingInt(Proposal::query))
                        .toList()) {
            boolean duplicate = false;
            for (final var previous : kept) {
                work.step();
                if (overlap(candidate, previous) > .1) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                kept.add(candidate);
            }
        }
        final var tables = new ArrayList<LayoutObject>();
        for (final var proposal : kept) {
            work.step();
            if (proposal.label() == 8) {
                final double left = Math.max(0, proposal.left());
                final double top = Math.max(0, proposal.top());
                final double right = Math.min(pixels.sourceWidth(), proposal.right());
                final double bottom = Math.min(pixels.sourceHeight(), proposal.bottom());
                if (right > left && bottom > top) {
                    tables.add(new LayoutObject(
                            0, proposal.score(), new PdfTableLayoutProvider.Box(left, top, right, bottom)));
                }
            }
        }
        return List.copyOf(tables);
    }

    /**
     * Checks a raw model probability independently of thresholding and selected class.
     * @param value original model score
     * @return unchanged validated score
     * @throws IOException if nonfinite or outside zero through one
     */
    private static float probability(final float value) throws IOException {
        if (!Float.isFinite(value) || value < 0 || value > 1) {
            throw invalid();
        }
        return value;
    }

    /**
     * Computes the declared inclusive-area source-frame intersection over union.
     * @param first candidate rectangle
     * @param second previously retained rectangle
     * @return overlap fraction, without clipping before suppression
     */
    private static double overlap(final Proposal first, final Proposal second) {
        final double width =
                Math.max(0, Math.min(first.right(), second.right()) - Math.max(first.left(), second.left()) + 1);
        final double height =
                Math.max(0, Math.min(first.bottom(), second.bottom()) - Math.max(first.top(), second.top()) + 1);
        final double intersection = width * height;
        final double area = (first.right() - first.left() + 1) * (first.bottom() - first.top() + 1);
        final double other = (second.right() - second.left() + 1) * (second.bottom() - second.top() + 1);
        return intersection / (area + other - intersection);
    }

    /**
     * Creates a content-free output contract failure.
     * @return typed recognition failure
     */
    private static PartitionException invalid() {
        return new PartitionException(PartitionException.Code.OCR_FAILURE, "Invalid YOLOX output tensor contract");
    }
}
