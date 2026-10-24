package io.docweave.partition;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Closed observed-rule precedence over overlapping model proposals, preserving disjoint model tables. */
final class RuledFirstPdfTableLayout implements PdfTableLayoutProvider {
    /** Supported eight-pixel rule thickness plus rounding clearance, matching direct ruled-cell OCR. */
    private static final int INSET = 5;
    /** Explicit immutable pixel geometry; historical callers select STANDARD. */
    private final PdfTableLayoutProvider.RuledProfile profile;
    /** Caller-owned secondary provider; this decorator never acquires lifecycle ownership. */
    private final PdfTableLayoutProvider secondary;
    /** Immutable structured dimension policy. */
    private final TableLimits limits;
    /** Immutable borrowed-pixel and combined raw-anchor ceilings. */
    private final PdfOcrOptions options;

    /**
     * Captures configuration without reading pixels or invoking the secondary provider.
     * @param secondary caller-owned model or fallback strategy
     * @param limits structured dimension policy
     * @param options pixel/anchor budget policy
     * @param profile fixed observed-rule geometry
     */
    RuledFirstPdfTableLayout(final PdfTableLayoutProvider secondary, final TableLimits limits,
            final PdfOcrOptions options, final PdfTableLayoutProvider.RuledProfile profile) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.secondary = Objects.requireNonNull(secondary, "secondary");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.options = Objects.requireNonNull(options, "options");
    }

    /**
     * Recognizes closed rules and validates all raw secondary claims before selecting precedence.
     * Each call has its own geometry counter. Detection, validation and overlap checks share the
     * fixed two-million-step ceiling; integration independently applies its existing work ceiling.
     * @param image borrowed rendered or native-masked raster
     * @return immutable ruled tables followed by disjoint secondary tables, preserving their provenance
     * @throws IOException if either stage, pixel/anchor/work limits, invalid topology or cancellation fails
     */
    @Override
    public List<Table> locate(final BufferedImage image) throws IOException {
        Objects.requireNonNull(image, "image");
        ElementCollector.checkInterrupted();
        if ((long) image.getWidth() * image.getHeight() > options.maxRenderPixels()) {
            throw new PartitionException(
                    PartitionException.Code.OCR_LIMIT, "Ruled-first layout exceeds maxRenderPixels");
        }
        final var work = new PdfTableWork();
        final var bounded = new TableLimits(limits.maxRows(), limits.maxColumns(),
                Math.min(limits.maxCells(), options.maxTableCells()), limits.maxCellCharacters());
        final var primary = new ArrayList<Table>();
        int rawAnchors = 0;
        for (final var grid : RasterPdfTableDetector.detect(image, bounded, work, profile)) {
            work.step();
            if (grid.cells().size() > options.maxTableCells() - rawAnchors) {
                throw limit();
            }
            rawAnchors += grid.cells().size();
            final var anchors = new ArrayList<Anchor>();
            for (final var cell : grid.cells()) {
                work.step();
                anchors.add(new Anchor(cell.row(), cell.column(), cell.rowSpan(), cell.columnSpan(),
                        new Box(Math.ceil(grid.xs().get(cell.column())) + INSET,
                                Math.ceil(grid.ys().get(cell.row())) + INSET,
                                Math.floor(grid.xs().get(cell.column() + cell.columnSpan())) - INSET,
                                Math.floor(grid.ys().get(cell.row() + cell.rowSpan())) - INSET)));
            }
            primary.add(new Table(grid.ys().size() - 1, grid.xs().size() - 1,
                    new Box(grid.xs().getFirst(), grid.ys().getFirst(), grid.xs().getLast(), grid.ys().getLast()),
                    anchors,
                    "raster_ruled_interiors_v1;inset_pixels=5;precedence=ruled_first_v1"
                            + (profile == PdfTableLayoutProvider.RuledProfile.COMPACT ? ";rule_profile=compact_dark_v1"
                                                                                      : "")));
        }
        PdfTableLayouts.validate(primary, image, limits, options, work);
        final var alternatives = PdfTableLayouts.locate(secondary, image, limits, options, work);
        // Count raw proposals before dropping overlaps: preference must not bypass candidate resource limits.
        for (final var table : alternatives) {
            work.step();
            if (table.anchors().size() > options.maxTableCells() - rawAnchors) {
                throw limit();
            }
            rawAnchors += table.anchors().size();
        }
        final var selected = new ArrayList<Table>(primary);
        for (final var table : alternatives) {
            boolean overlap = false;
            for (final var ruled : primary) {
                work.step();
                overlap |= PdfTableLayouts.overlaps(ruled.bounds(), table.bounds());
            }
            if (!overlap) {
                selected.add(table);
            }
        }
        ElementCollector.checkInterrupted();
        return List.copyOf(selected);
    }

    /**
     * Reports raw candidate exhaustion without embedding input content or paths.
     * @return typed aggregate candidate limit
     */
    private static PartitionException limit() {
        return new PartitionException(PartitionException.Code.TABLE_LIMIT, "Ruled-first layout exceeds maxTableCells");
    }
}
