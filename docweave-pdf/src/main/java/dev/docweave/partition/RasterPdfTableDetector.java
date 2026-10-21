package dev.docweave.partition;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Conservative raster line-run strategy feeding the existing bounded ruled-topology recognizer. */
final class RasterPdfTableDetector {
    /** Maximum accepted contiguous rule thickness. */
    private static final int MAX_THICKNESS = 8;
    /** Maximum raw runs and final segments per orientation; limits busy/noisy images. */
    private static final int MAX_RUNS = 4096;
    /** Two directional scans have this fixed cumulative pixel ceiling. */
    private static final long MAX_PIXEL_VISITS = 64_000_000;

    /** Utility class. */
    private RasterPdfTableDetector() {
    }

    /**
     * Finds closed grids with rectangular merges, rejecting small atomic slots and ambiguous rule remnants.
     * @param image rendered or native-masked raster
     * @param limits table dimensions/cell policy
     * @param work shared topology and run-band comparison budget
     * @return independent candidate grids in rendered pixels
     * @throws IOException if cancellation or pixel/run/topology/dimension budgets fail
     */
    static List<PdfTableGrid> detect(final BufferedImage image, final TableLimits limits, final PdfTableWork work)
            throws IOException {
        return detect(image, limits, work, PdfTableLayoutProvider.RuledProfile.STANDARD);
    }

    /**
     * Applies one explicit geometry profile while preserving all shared work, slot and pixel limits.
     * @param image borrowed original raster
     * @param limits structured dimensions and prospective slots
     * @param work shared page-confined budget
     * @param profile fixed minimum run/cell geometry
     * @return immutable complete observed grids, without repaired edges
     * @throws IOException if cancellation or resource limits fail
     */
    static List<PdfTableGrid> detect(final BufferedImage image, final TableLimits limits, final PdfTableWork work,
            final PdfTableLayoutProvider.RuledProfile profile) throws IOException {
        final var segments = segments(image, work, profile);
        final var result = new ArrayList<PdfTableGrid>();
        for (final var grid : new RuledPdfTableDetector(limits).detect(trimCaps(segments, work), work)) {
            boolean valid = true;
            for (int column = 1; column < grid.xs().size(); column++) {
                work.step();
                valid &= grid.xs().get(column) - grid.xs().get(column - 1) >= profile.minimumColumnPixels();
            }
            for (int row = 1; row < grid.ys().size(); row++) {
                work.step();
                valid &= grid.ys().get(row) - grid.ys().get(row - 1) >= profile.minimumRowPixels();
            }
            if (valid && !hasRuleRemnants(image, grid, work)) {
                result.add(grid);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Extracts observed dark rule centerlines without repairing gaps or recognizing topology.
     * Shared with explicit word-layout inference so painted rules remain ambiguity barriers.
     * @param image borrowed rendered raster
     * @param work shared bounded geometry counter
     * @return immutable horizontal/vertical centerlines in pixel coordinates
     * @throws IOException if pixel/run/work limits or cancellation fail
     */
    static List<PdfPageGraphics.Segment> segments(final BufferedImage image, final PdfTableWork work)
            throws IOException {
        return segments(image, work, PdfTableLayoutProvider.RuledProfile.STANDARD);
    }

    /**
     * Extracts uninterrupted observed runs for one fixed profile, with no gap or color repair.
     * @param image borrowed raster
     * @param work shared geometry/cancellation counter
     * @param profile explicit minimum run geometry
     * @return immutable observed dark centerlines
     * @throws IOException if pixel, run, work or cancellation limits fail
     */
    private static List<PdfPageGraphics.Segment> segments(final BufferedImage image, final PdfTableWork work,
            final PdfTableLayoutProvider.RuledProfile profile) throws IOException {
        if ((long) image.getWidth() * image.getHeight() > MAX_PIXEL_VISITS / 2) {
            throw limit("Scanned PDF table raster exceeds pixel-work ceiling");
        }
        final var segments = new ArrayList<PdfPageGraphics.Segment>();
        scan(image, true, segments, work, profile.minimumRunPixels());
        scan(image, false, segments, work, profile.minimumRunPixels());
        return List.copyOf(segments);
    }

    /**
     * Trims painted endpoints to observed perpendicular centerlines, removing only stroke-cap overlap.
     * A thick raster junction protrudes into the neighboring interval; passing those pixels through
     * unchanged would falsely classify a fully absent merged divider as partial coverage. Only inward
     * movement within half the supported rule thickness is permitted, never gap extension/repair.
     * @param segments original pixel-run centerlines and painted extents
     * @param work shared comparison/cancellation ceiling
     * @return centerline segments with bounded inward endpoint adjustment
     * @throws IOException if cancelled or over the shared work ceiling
     */
    private static List<PdfPageGraphics.Segment> trimCaps(
            final List<PdfPageGraphics.Segment> segments, final PdfTableWork work) throws IOException {
        final var trimmed = new ArrayList<PdfPageGraphics.Segment>();
        for (final var segment : segments) {
            final boolean horizontal = segment.y1() == segment.y2();
            final float position = horizontal ? segment.y1() : segment.x1();
            float start = horizontal ? segment.x1() : segment.y1();
            float end = horizontal ? segment.x2() : segment.y2();
            final float originalStart = start;
            final float originalEnd = end;
            for (final var crossing : segments) {
                work.step();
                if (horizontal == (crossing.y1() == crossing.y2())) {
                    continue;
                }
                final float axis = horizontal ? crossing.x1() : crossing.y1();
                final float crossStart = horizontal ? crossing.y1() : crossing.x1();
                final float crossEnd = horizontal ? crossing.y2() : crossing.x2();
                if (position < crossStart || position > crossEnd) {
                    continue;
                }
                if (axis >= originalStart && axis - originalStart <= MAX_THICKNESS / 2.0f) {
                    start = Math.max(start, axis);
                }
                if (axis <= originalEnd && originalEnd - axis <= MAX_THICKNESS / 2.0f) {
                    end = Math.min(end, axis);
                }
            }
            trimmed.add(horizontal ? new PdfPageGraphics.Segment(start, position, end, position)
                                   : new PdfPageGraphics.Segment(position, start, position, end));
        }
        return List.copyOf(trimmed);
    }

    /**
     * Rejects endpoint-attached rule stubs inside an inferred merge. Discovery ignores runs below the selected
     * profile minimum, so accepting every absent divider would otherwise reinterpret some broken rules
     * as merges. Eight dark pixels beyond the five-pixel junction allowance are treated as a stub.
     * Text touching an expected junction can conservatively reject a legitimate merge; detached noise
     * and smaller/fainter remnants are not reliably classified. No missing stroke is repaired.
     * @param image borrowed raster
     * @param grid validated rectangular ownership
     * @param work shared bounded probe/cancellation counter
     * @return whether a missing atomic divider has a suspicious attached remnant
     * @throws IOException if interrupted or over the shared work ceiling
     */
    private static boolean hasRuleRemnants(final BufferedImage image, final PdfTableGrid grid, final PdfTableWork work)
            throws IOException {
        final int columns = grid.xs().size() - 1;
        final int rows = grid.ys().size() - 1;
        for (int row = 0; row < rows; row++) {
            for (int column = 1; column < columns; column++) {
                work.step();
                if (grid.owners()[row * columns + column - 1] == grid.owners()[row * columns + column]
                        && attached(image, false, grid.xs().get(column), grid.ys().get(row), grid.ys().get(row + 1),
                                work)) {
                    return true;
                }
            }
        }
        for (int row = 1; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                work.step();
                if (grid.owners()[(row - 1) * columns + column] == grid.owners()[row * columns + column]
                        && attached(image, true, grid.ys().get(row), grid.xs().get(column), grid.xs().get(column + 1),
                                work)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Probes both endpoints of one missing divider, allowing a one-pixel centerline rounding offset.
     * All probes count against the shared topology work limit; the directional scan limit is separate.
     * @param image input pixels
     * @param horizontal divider orientation
     * @param position expected divider centerline
     * @param start minimum axis coordinate
     * @param end maximum axis coordinate
     * @param work bounded work counter
     * @return whether either junction has eight uninterrupted interior dark samples
     * @throws IOException if cancelled or over the work limit
     */
    private static boolean attached(final BufferedImage image, final boolean horizontal, final float position,
            final float start, final float end, final PdfTableWork work) throws IOException {
        final int cross = Math.round(position);
        for (int direction = 1; direction >= -1; direction -= 2) {
            final int origin = direction == 1 ? (int) Math.ceil(start) : (int) Math.floor(end);
            boolean stub = true;
            for (int distance = 5; distance < 13; distance++) {
                work.step();
                final int axis = origin + direction * distance;
                boolean sample = false;
                for (int offset = -1; offset <= 1; offset++) {
                    final int x = horizontal ? axis : cross + offset;
                    final int y = horizontal ? cross + offset : axis;
                    if (x >= 0 && x < image.getWidth() && y >= 0 && y < image.getHeight()) {
                        sample |= dark(image.getRGB(x, y));
                    }
                }
                if (!sample) {
                    stub = false;
                    break;
                }
            }
            if (stub) {
                return true;
            }
        }
        return false;
    }

    /**
     * Scans maximal contiguous dark runs and coalesces adjacent equal-endpoint rows into one thin rule.
     * @param image input raster
     * @param horizontal true for rows, false for columns
     * @param segments bounded destination
     * @param minimumRun profile minimum uninterrupted run
     * @param work shared comparison/cancellation counter
     * @throws IOException if cancellation or a work ceiling is reached
     */
    private static void scan(final BufferedImage image, final boolean horizontal,
            final List<PdfPageGraphics.Segment> segments, final PdfTableWork work, final int minimumRun)
            throws IOException {
        final int rows = horizontal ? image.getHeight() : image.getWidth();
        final int columns = horizontal ? image.getWidth() : image.getHeight();
        var active = new ArrayList<Band>();
        var next = new ArrayList<Band>();
        int runs = 0;
        for (int row = 0; row < rows; row++) {
            ElementCollector.checkInterrupted();
            next.clear();
            int start = -1;
            for (int column = 0; column <= columns; column++) {
                if ((column & 1023) == 0) {
                    ElementCollector.checkInterrupted();
                }
                final boolean dark =
                        column < columns && dark(image.getRGB(horizontal ? column : row, horizontal ? row : column));
                if (dark && start < 0) {
                    start = column;
                } else if (!dark && start >= 0) {
                    if (column - start >= minimumRun) {
                        if (++runs > MAX_RUNS) {
                            throw limit("Scanned PDF table raster exceeds run ceiling");
                        }
                        Band match = null;
                        for (final var band : active) {
                            work.step();
                            if (!band.matched && Math.abs(band.start - start) <= 2
                                    && Math.abs(band.end - (column - 1)) <= 2) {
                                match = band;
                                break;
                            }
                        }
                        if (match == null) {
                            match = new Band(start, column - 1, row);
                        } else {
                            match.matched = true;
                            match.last = row;
                        }
                        next.add(match);
                    }
                    start = -1;
                }
            }
            for (final var band : active) {
                if (!band.matched) {
                    add(band, horizontal, segments);
                }
            }
            for (final var band : next) {
                band.matched = false;
            }
            final var previous = active;
            active = next;
            next = previous;
        }
        for (final var band : active) {
            add(band, horizontal, segments);
        }
    }

    /**
     * Converts a thin band into one centered axis segment; thick graphic blocks are not table rules.
     * @param band completed run band
     * @param horizontal orientation
     * @param segments bounded output
     * @throws PartitionException if the segment ceiling is exceeded
     */
    private static void add(final Band band, final boolean horizontal, final List<PdfPageGraphics.Segment> segments)
            throws PartitionException {
        if (band.last - band.first + 1 <= MAX_THICKNESS) {
            if (segments.size() >= MAX_RUNS) {
                throw limit("Scanned PDF table raster exceeds segment ceiling");
            }
            final float position = (band.first + band.last) / 2.0f;
            segments.add(horizontal ? new PdfPageGraphics.Segment(band.start, position, band.end, position)
                                    : new PdfPageGraphics.Segment(position, band.start, position, band.end));
        }
    }

    /**
     * Uses a fixed per-channel threshold; no adaptive binarization or photographic classification is implied.
     * @param rgb rendered RGB value
     * @return true for a dark pixel
     */
    private static boolean dark(final int rgb) {
        return ((rgb >>> 16) & 255) <= 80 && ((rgb >>> 8) & 255) <= 80 && (rgb & 255) <= 80;
    }

    /**
     * Creates a stable work-budget diagnostic without page contents.
     * @param message diagnostic
     * @return typed table limit
     */
    private static PartitionException limit(final String message) {
        return new PartitionException(PartitionException.Code.TABLE_LIMIT, message);
    }

    /** Mutable adjacent-row accumulator confined to a single directional scan. */
    private static final class Band {
        /** First dark coordinate along the run. */
        private final int start;
        /** Last dark coordinate along the run. */
        private final int end;
        /** First row in the band. */
        private final int first;
        /** Last contiguous row. */
        private int last;
        /** Whether the next row continued this band. */
        private boolean matched;

        /**
         * Starts a new contiguous band.
         * @param start first run coordinate
         * @param end last run coordinate
         * @param row starting row
         */
        Band(final int start, final int end, final int row) {
            this.start = start;
            this.end = end;
            first = row;
            last = row;
        }
    }
}
