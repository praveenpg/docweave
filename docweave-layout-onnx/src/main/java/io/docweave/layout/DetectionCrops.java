package io.docweave.layout;

import io.docweave.partition.PartitionException;
import io.docweave.partition.PdfTableLayoutProvider.Box;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Conservative fixed-score page detections, duplicate suppression and bounded source-crop planning. */
final class DetectionCrops {
    /** Maximum positive predictions/crops before any child inference. */
    private static final int MAX_CROPS = 32;
    /** Fixed context around detected table rectangles, clipped to the borrowed raster. */
    private static final int PADDING = 10;
    /** Aggregate copied/filled crop pixel visits across the page. */
    private static final long MAX_PIXEL_VISITS = 64_000_000;

    /** Utility class. */
    private DetectionCrops() {
    }

    /**
     * One bounded integer crop in the original source frame.
     * @param left inclusive source x
     * @param top inclusive source y
     * @param right exclusive source x
     * @param bottom exclusive source y
     */
    record Crop(int left, int top, int right, int bottom) {}

    /**
     * Plans disjoint ordinary-table detections and preflights every child raster before allocation.
     * Rotated-table class1 is deliberately unsupported and returns to ordinary PAGE-word fallback.
     * Score-ordered containment NMS0.5 suppresses duplicates; remaining partially overlapping
     * detections are both rejected. No source-specific thresholds or oracle crop repairs are used.
     * @param objects finite thresholded three-class model predictions
     * @param source borrowed original raster
     * @param structure child image pixel ceiling
     * @param work shared whole-page geometry/deadline budget
     * @return immutable source rectangles, with fixed clipped context
     * @throws IOException if raw positive prediction, pixel, work or cancellation limits fail
     */
    static List<Crop> plan(final List<LayoutObject> objects, final BufferedImage source,
            final OnnxTableOptions structure, final LayoutWork work) throws IOException {
        if (objects.size() > MAX_CROPS) {
            throw new PartitionException(
                    PartitionException.Code.OCR_LIMIT, "ONNX page detections exceed fixed crop limit");
        }
        final var kept = new ArrayList<LayoutObject>();
        for (final var object : objects.stream()
                        .filter(candidate -> candidate.label() == 0)
                        .sorted(Comparator.comparingDouble(LayoutObject::score).reversed())
                        .toList()) {
            boolean duplicate = false;
            for (final var previous : kept) {
                work.step();
                if (intersection(object.box(), previous.box()) / area(object.box()) >= .5) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                kept.add(object);
            }
        }
        final var ambiguous = new boolean[kept.size()];
        for (int first = 0; first < kept.size(); first++) {
            for (int second = first + 1; second < kept.size(); second++) {
                work.step();
                if (intersection(kept.get(first).box(), kept.get(second).box()) > 0) {
                    ambiguous[first] = true;
                    ambiguous[second] = true;
                }
            }
        }
        final var crops = new ArrayList<Crop>();
        long visits = 0;
        for (int index = 0; index < kept.size(); index++) {
            work.step();
            if (ambiguous[index]) {
                continue;
            }
            final var box = kept.get(index).box();
            final int left = (int) Math.max(0, Math.floor(box.left()) - PADDING);
            final int top = (int) Math.max(0, Math.floor(box.top()) - PADDING);
            final int right = (int) Math.min(source.getWidth(), Math.ceil(box.right()) + PADDING);
            final int bottom = (int) Math.min(source.getHeight(), Math.ceil(box.bottom()) + PADDING);
            final long pixels = (long) (right - left) * (bottom - top);
            if (pixels > structure.maxInputPixels() || pixels > (MAX_PIXEL_VISITS - visits) / 2) {
                throw new PartitionException(
                        PartitionException.Code.OCR_LIMIT, "ONNX page crops exceed raster/work limit");
            }
            visits += 2 * pixels;
            crops.add(new Crop(left, top, right, bottom));
        }
        return List.copyOf(crops);
    }

    /**
     * Computes positive finite rectangle area.
     * @param box validated source rectangle
     * @return pixel area
     */
    private static double area(final Box box) {
        return (box.right() - box.left()) * (box.bottom() - box.top());
    }

    /**
     * Computes source rectangle overlap area.
     * @param first validated rectangle
     * @param second validated rectangle
     * @return nonnegative intersection area
     */
    private static double intersection(final Box first, final Box second) {
        return Math.max(0, Math.min(first.right(), second.right()) - Math.max(first.left(), second.left()))
                * Math.max(0, Math.min(first.bottom(), second.bottom()) - Math.max(first.top(), second.top()));
    }
}
