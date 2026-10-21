package dev.docweave.partition;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owned optional upright raster and exact quarter-turn inverse frame for OCR/table output.
 */
final class PdfOcrFrame implements AutoCloseable {
    /**
     * Borrowed original rendered/native-masked input.
     */
    private final BufferedImage source;
    /**
     * Owned rotated input, or the unchanged borrowed raster when correction is zero.
     */
    private final BufferedImage image;
    /**
     * Optional explicit estimator observation; null preserves historical metadata/defaults.
     */
    private final PdfOrientationEstimator.Estimate estimate;

    /**
     * Calls an explicit estimator once and prepares an owned bounded quarter-turn raster if accepted.
     *
     * @param source    borrowed rendered/native-masked RGB raster
     * @param estimator optional caller-owned estimation strategy
     * @throws IOException if estimation, interruption or provider contracts fail
     */
    PdfOcrFrame(final BufferedImage source, final PdfOrientationEstimator estimator) throws IOException {
        this.source = source;
        ElementCollector.checkInterrupted();
        try {
            estimate = estimator == null ? null : estimator.estimate(source);
        } catch (final PartitionException | InterruptedIOException failure) {
            throw failure;
        } catch (final IOException | RuntimeException failure) {
            throw new PartitionException(
                PartitionException.Code.OCR_FAILURE, "PDF orientation estimator failed", failure);
        }
        ElementCollector.checkInterrupted();
        if (estimator != null && estimate == null) {
            throw new PartitionException(
                PartitionException.Code.OCR_FAILURE, "PDF orientation estimator returned null");
        }
        if (correction() == 0) {
            image = source;
        } else {
            final boolean swap = correction() % 180 != 0;
            image = new BufferedImage(swap ? source.getHeight() : source.getWidth(),
                swap ? source.getWidth() : source.getHeight(), BufferedImage.TYPE_INT_RGB);
            final var graphics = image.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
                switch (correction()) {
                    case 90 -> graphics.translate(source.getHeight(), 0);
                    case 180 -> graphics.translate(source.getWidth(), source.getHeight());
                    case 270 -> graphics.translate(0, source.getWidth());
                    default -> throw new IllegalStateException("Validated quarter-turn correction required");
                }
                graphics.rotate(Math.toRadians(correction()));
                graphics.drawImage(source, 0, 0, null);
            } finally {
                graphics.dispose();
            }
            try {
                ElementCollector.checkInterrupted();
            } catch (final IOException failure) {
                image.flush();
                throw failure;
            }
        }
    }

    /**
     * Returns the synchronous borrowed/owned input for all PAGE/layout/CELL stages.
     *
     * @return upright input raster
     */
    BufferedImage image() {
        return image;
    }

    /**
     * Reports the accepted source-to-upright correction.
     *
     * @return 0/90/180/270; unaccepted/no-estimator observations remain zero
     */
    int correction() {
        return estimate == null ? 0 : estimate.clockwiseDegrees();
    }

    /**
     * Inverts one upright pixel rectangle to the original raster without another PDF crop/page rotation.
     *
     * @param line upright bounds and unchanged text
     * @return original rendered/native-masked pixel bounds
     * @throws IOException if finite-precision subtraction cannot represent positive restored extents
     */
    PdfOcrProvider.Line toSource(final PdfOcrProvider.Line line) throws IOException {
        if (correction() == 0) {
            return line;
        }
        final var box =
            inverse(line.left(), line.top(), line.right(), line.bottom(), source.getWidth(), source.getHeight());
        try {
            return new PdfOcrProvider.Line(line.text(), box[0], box[1], box[2], box[3]);
        } catch (final IllegalArgumentException failure) {
            throw new PartitionException(PartitionException.Code.OCR_FAILURE,
                "Rotated OCR bounds cannot be represented in the source frame", failure);
        }
    }

    /**
     * Inverts a quarter-turn rectangle in pixels or normalized PDF units, clamping edge roundoff.
     * Internal serialized cells may have degenerate sub-precision extents; arrays preserve them
     * without imposing the stricter positive raw-provider rectangle constructor on metadata.
     *
     * @param left   upright left
     * @param top    upright top
     * @param right  upright right
     * @param bottom upright bottom
     * @param width  original frame width in the same units
     * @param height original frame height in the same units
     * @return private call-local left/top/right/bottom array, never exposed as application state
     */
    private double[] inverse(final double left, final double top, final double right, final double bottom,
                             final double width, final double height) {
        final var box = switch (correction()) {
            case 90 -> new double[]{top, height - right, bottom, height - left};
            case 180 -> new double[]{width - right, height - bottom, width - left, height - top};
            case 270 -> new double[]{width - bottom, left, width - top, right};
            default -> new double[]{left, top, right, bottom};
        };
        box[0] = Math.max(0, Math.min(width, box[0]));
        box[2] = Math.max(0, Math.min(width, box[2]));
        box[1] = Math.max(0, Math.min(height, box[1]));
        box[3] = Math.max(0, Math.min(height, box[3]));
        return box;
    }

    /**
     * Adapts upright stage ownership to the existing original-raster mixed-page selection.
     *
     * @param regions original source selection, or null
     * @return same selection for0, otherwise a stateless inverse-frame delegate
     */
    PdfOcrSelection selection(final PdfOcrRegions regions) {
        if (regions == null || correction() == 0) {
            return regions;
        }
        return new PdfOcrSelection() {
            @Override
            public boolean owns(final PdfOcrProvider.Line line) throws IOException {
                return regions.owns(toSource(line));
            }

            @Override
            public boolean retain(final PdfOcrProvider.Line line) throws IOException {
                return regions.retain(toSource(line));
            }
        };
    }

    /**
     * Restores serialized upright cell rectangles and records raw estimator provenance in parent metadata.
     *
     * @param metadata mutable private pending metadata copied before emission
     * @param geometry original displayed page frame
     * @throws IOException if cell traversal is cancelled
     */
    void describe(final Map<String, Object> metadata, final PdfPageGeometry geometry) throws IOException {
        if (estimate == null) {
            return;
        }
        metadata.put("pdf_ocr_orientation_correction", correction());
        metadata.put("pdf_ocr_orientation_status", estimate.accepted() ? "accepted" : "undetermined");
        metadata.put("pdf_ocr_orientation_raw_score", estimate.rawScore());
        metadata.put("pdf_ocr_orientation_provenance", estimate.provenance());
        if (correction() == 0 || !(metadata.get("pdf_table_cells") instanceof Map<?, ?> extension)) {
            return;
        }
        final var cells = new ArrayList<Map<String, Object>>();
        for (final var value : (List<?>) extension.get("cells")) {
            ElementCollector.checkInterrupted();
            final var cell = (Map<?, ?>) value;
            final var coordinates = (Map<?, ?>) cell.get("coordinates");
            final var points = (List<?>) coordinates.get("points");
            final var first = (List<?>) points.getFirst();
            final var third = (List<?>) points.get(2);
            final var box = inverse(number(first.get(0)), number(first.get(1)), number(third.get(0)),
                number(third.get(1)), geometry.width(), geometry.height());
            final var restored = new LinkedHashMap<String, Object>();
            cell.forEach((key, item) -> restored.put((String) key, item));
            restored.put("coordinates", Map.of("system", "PDFUserSpace", "layout_width", (double) geometry.width(),
                "layout_height", (double) geometry.height(), "points",
                List.of(List.of(box[0], box[1]), List.of(box[2], box[1]),
                    List.of(box[2], box[3]), List.of(box[0], box[3]))));
            cells.add(restored);
        }
        final var restored = new LinkedHashMap<String, Object>();
        extension.forEach((key, item) -> restored.put((String) key, item));
        restored.put("cells", List.copyOf(cells));
        metadata.put("pdf_table_cells", restored);
    }

    /**
     * Reads internal serializer-owned numeric coordinates without changing precision.
     *
     * @param value internal coordinate number
     * @return double ordinate
     */
    private static double number(final Object value) {
        return ((Number) value).doubleValue();
    }

    /**
     * Releases only an owned rotated raster; the caller retains the original image.
     */
    @Override
    public void close() {
        if (image != source) {
            image.flush();
        }
    }
}
