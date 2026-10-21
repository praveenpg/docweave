package dev.docweave.partition;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

/** Fixed projection search with input/work bounds and explicit nonprobabilistic ambiguity gates. */
final class ProjectedPdfDeskewEstimator implements PdfDeskewEstimator {
    /** Maximum sampled ink coordinates; bounds every projection sweep regardless of caller input cap. */
    private static final int MAX_INK = 120_000;
    /** Caller-selected source raster ceiling. */
    private final long maxPixels;

    /**
     * Captures a positive input pixel ceiling.
     * @param maxPixels borrowed raster limit
     */
    ProjectedPdfDeskewEstimator(final long maxPixels) {
        if (maxPixels < 1) {
            throw new IllegalArgumentException("maxPixels must be positive");
        }
        this.maxPixels = maxPixels;
    }

    @Override
    public Estimate estimate(final BufferedImage image) throws IOException {
        Objects.requireNonNull(image, "image");
        ElementCollector.checkInterrupted();
        if ((long) image.getWidth() * image.getHeight() > maxPixels) {
            throw new PartitionException(PartitionException.Code.OCR_LIMIT, "PDF deskew exceeds maxPixels");
        }
        final double scale = Math.min(1, 1000.0 / Math.max(image.getWidth(), image.getHeight()));
        final int width = Math.max(1, (int) Math.floor(image.getWidth() * scale));
        final int height = Math.max(1, (int) Math.floor(image.getHeight() * scale));
        final var points = new ArrayList<int[]>();
        for (int y = 0; y < height; y++) {
            ElementCollector.checkInterrupted();
            final int sourceY = Math.min(image.getHeight() - 1, (int) ((y + 0.5) * image.getHeight() / height));
            for (int x = 0; x < width; x++) {
                final int sourceX = Math.min(image.getWidth() - 1, (int) ((x + 0.5) * image.getWidth() / width));
                final int rgb = image.getRGB(sourceX, sourceY);
                final int alpha = (rgb >>> 24) & 255;
                final int red = 255 - alpha * (255 - ((rgb >>> 16) & 255)) / 255;
                final int green = 255 - alpha * (255 - ((rgb >>> 8) & 255)) / 255;
                final int blue = 255 - alpha * (255 - (rgb & 255)) / 255;
                if (red <= 100 && green <= 100 && blue <= 100) {
                    if (points.size() == MAX_INK) {
                        return new Estimate(Status.AMBIGUOUS, 0, 0, 0, 0, points.size(), width, height);
                    }
                    points.add(new int[] {x, y});
                }
            }
        }
        if (points.size() < 200 || points.size() < width * height * 0.002) {
            return new Estimate(Status.INSUFFICIENT_INK, 0, 0, 0, 0, points.size(), width, height);
        }
        if (points.size() > width * height * 0.25) {
            return new Estimate(Status.AMBIGUOUS, 0, 0, 0, 0, points.size(), width, height);
        }
        final int offset = (int) Math.ceil(Math.hypot(width, height));
        final var histogram = new int[offset * 2 + 1];
        final var scores = new double[81];
        int best = 40;
        for (int step = 0; step < scores.length; step++) {
            ElementCollector.checkInterrupted();
            Arrays.fill(histogram, 0);
            final double radians = Math.toRadians((step - 40) * 0.25);
            final double sine = Math.sin(radians);
            final double cosine = Math.cos(radians);
            int visited = 0;
            for (final var point : points) {
                if ((visited++ & 1023) == 0) {
                    ElementCollector.checkInterrupted();
                }
                histogram[offset + (int) Math.floor(sine * point[0] + cosine * point[1])]++;
            }
            for (final int count : histogram) {
                scores[step] += (double) count * (count - 1);
            }
            if (scores[step] > scores[best]) {
                best = step;
            }
        }
        double separated = 0;
        for (int step = 0; step < scores.length; step++) {
            if (Math.abs(step - best) >= 4) {
                separated = Math.max(separated, scores[step]);
            }
        }
        boolean accepted = best > 0 && best < 80 && Math.abs(best - 40) >= 2 && scores[best] >= scores[40] * 1.15
                           && scores[best] >= separated * 1.05;
        if (accepted) {
            Arrays.fill(histogram, 0);
            final double radians = Math.toRadians((best - 40) * 0.25);
            for (final var point : points) {
                histogram[offset + (int) Math.floor(Math.sin(radians) * point[0] + Math.cos(radians) * point[1])]++;
            }
            final int peak = Arrays.stream(histogram).max().orElse(0);
            int bands = 0;
            int gap = 4;
            for (final int count : histogram) {
                if (count >= Math.max(1, peak * 0.25)) {
                    if (gap >= 4) {
                        bands++;
                    }
                    gap = 0;
                } else {
                    gap++;
                }
            }
            accepted = bands >= 2;
        }
        return new Estimate(accepted ? Status.ACCEPTED : Status.AMBIGUOUS, accepted ? (best - 40) * 0.25 : 0,
                scores[40], scores[best], separated, points.size(), width, height);
    }
}
