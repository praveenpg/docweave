package dev.docweave.layout;

import dev.docweave.partition.PartitionException;
import dev.docweave.partition.PdfTableLayoutProvider;
import dev.docweave.partition.TableLimits;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Explicit two-stage whole-page pipeline with shared deadlines, work and aggregate prospective slots. */
final class DetectedOnnxTableLayout implements OnnxTableLayout {
    /** Fixed aggregate slot ceiling independent of permissive caller policies. */
    private static final int MAX_SLOTS = 2_000_000;
    /** Detection graph and admission policy. */
    private final OnnxTableOptions detectionOptions;
    /** Structure graph and admission policy. */
    private final OnnxTableOptions structureOptions;
    /** Stricter of both caller-selected dimension/slot policies. */
    private final TableLimits limits;
    /** Declared detector preprocessing identity, kept within the public provider provenance cap. */
    private final String detectorLabel;
    /** Owned explicitly selected page detector, sharing managed session lifetime. */
    private final ManagedTableSession<?> detector;
    /** Owned seven-label child structure recognizer. */
    private final ManagedTatrSession structure;

    /**
     * Verifies both trusted standalone local graphs and loads their CPU sessions transactionally.
     * @param detectionOptions three-label detection graph identity and policy
     * @param structureOptions seven-label structure graph identity and policy
     * @throws IOException if either graph/schema/session cannot be initialized
     */
    DetectedOnnxTableLayout(final OnnxTableOptions detectionOptions, final OnnxTableOptions structureOptions)
            throws IOException {
        this(detectionOptions, structureOptions, false);
    }

    /**
     * Selects an explicit detector tensor strategy while retaining the existing child pipeline.
     * @param detectionOptions local detector graph identity and limits
     * @param structureOptions local TATR structure graph identity and limits
     * @param yoloX whether to select the fixed exported YOLOX contract instead of TATR detection
     * @throws IOException if either graph/schema/session cannot be initialized
     */
    DetectedOnnxTableLayout(final OnnxTableOptions detectionOptions, final OnnxTableOptions structureOptions,
            final boolean yoloX) throws IOException {
        this.detectionOptions = detectionOptions;
        this.structureOptions = structureOptions;
        detectorLabel = yoloX ? "yolox114_v1;d=" : "detector_sha256=";
        final var first = detectionOptions.tableLimits();
        final var second = structureOptions.tableLimits();
        limits = new TableLimits(Math.min(first.maxRows(), second.maxRows()),
                Math.min(first.maxColumns(), second.maxColumns()),
                Math.min(MAX_SLOTS, Math.min(first.maxCells(), second.maxCells())),
                Math.min(first.maxCellCharacters(), second.maxCellCharacters()));
        detector = yoloX ? new ManagedTableSession<>(detectionOptions, new YoloXTableModel())
                         : new ManagedTableSession<>(detectionOptions, new TatrTableModel(3));
        try {
            structure = new ManagedTatrSession(structureOptions, 7);
        } catch (final IOException | RuntimeException | Error failure) {
            try {
                detector.discardUnpublished();
            } catch (final IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /**
     * Detects ordinary tables before per-crop structure inference and original-frame translation.
     * Both stages share the shorter configured timeout, one geometry counter and aggregate slots.
     * @param image borrowed whole-page raster
     * @return immutable disjoint complete layouts in original source pixels
     * @throws IOException if either stage, lifecycle or resource validation fails
     */
    @Override
    public List<PdfTableLayoutProvider.Table> locate(final BufferedImage image) throws IOException {
        final var timeout = detectionOptions.timeout().compareTo(structureOptions.timeout()) <= 0
                                    ? detectionOptions.timeout()
                                    : structureOptions.timeout();
        final var deadline = new LayoutDeadline(timeout);
        final var work = new LayoutWork(deadline);
        return detector.infer(image, deadline, work, objects -> recognize(image, objects, deadline, work));
    }

    /**
     * Runs bounded child crops while parent admission/read ownership remains active.
     * @param image borrowed whole-page source
     * @param detections finite thresholded page predictions
     * @param deadline shared total pipeline deadline
     * @param work shared geometry/deadline counter
     * @return translated disjoint complete layouts
     * @throws IOException if child raster, inference or aggregate geometry budgets fail
     */
    private List<PdfTableLayoutProvider.Table> recognize(final BufferedImage image, final List<LayoutObject> detections,
            final LayoutDeadline deadline, final LayoutWork work) throws IOException {
        final var crops = DetectionCrops.plan(detections, image, structureOptions, work);
        final var candidates = new ArrayList<PdfTableLayoutProvider.Table>();
        long slots = 0;
        for (final var crop : crops) {
            work.step();
            final int remaining = (int) (limits.maxCells() - slots);
            if (remaining <= 0) {
                throw new PartitionException(PartitionException.Code.TABLE_LIMIT, "ONNX page exceeds aggregate slots");
            }
            final var bounded = structureOptions.withTableLimits(
                    new TableLimits(limits.maxRows(), limits.maxColumns(), remaining, limits.maxCellCharacters()));
            final var raster = copy(image, crop);
            try {
                final var topology = new TableTopology(bounded, work);
                final var tables = structure.infer(raster, deadline, topology::tables);
                slots += topology.consumedSlots();
                for (final var table : tables) {
                    candidates.add(translate(table, crop, work));
                }
            } finally {
                raster.flush();
            }
        }
        return disjoint(candidates, work);
    }

    /**
     * Copies a preflighted source rectangle over white without retaining or changing the source.
     * @param source borrowed page raster
     * @param crop bounded source rectangle
     * @return owned raster released by the caller
     */
    private static BufferedImage copy(final BufferedImage source, final DetectionCrops.Crop crop) {
        final int width = crop.right() - crop.left();
        final int height = crop.bottom() - crop.top();
        final var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        final var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.drawImage(source, 0, 0, width, height, crop.left(), crop.top(), crop.right(), crop.bottom(), null);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    /**
     * Translates a complete child layout once using the actual clipped integer crop origin.
     * @param table crop-relative geometry
     * @param crop actual source crop
     * @param work shared geometry/cancellation counter
     * @return original-frame layout with both pinned graph identities
     * @throws IOException if geometry work/cancellation fails
     */
    private PdfTableLayoutProvider.Table translate(final PdfTableLayoutProvider.Table table,
            final DetectionCrops.Crop crop, final LayoutWork work) throws IOException {
        final var anchors = new ArrayList<PdfTableLayoutProvider.Anchor>();
        for (final var anchor : table.anchors()) {
            work.step();
            anchors.add(new PdfTableLayoutProvider.Anchor(anchor.row(), anchor.column(), anchor.rowSpan(),
                    anchor.columnSpan(), offset(anchor.bounds(), crop)));
        }
        return new PdfTableLayoutProvider.Table(table.rows(), table.columns(), offset(table.bounds(), crop), anchors,
                provenance(table.provenance()), table.headerRows());
    }

    /**
     * Preserves both graph identities and complete structure policy within the256-character provider contract.
     * Historical TATR strings remain unchanged unless a long valid score representation would overflow.
     * @param child complete bounded TATR structure provenance
     * @return complete model/policy provenance without truncation
     */
    private String provenance(final String child) {
        final String full = detectorLabel + detectionOptions.sha256() + ";" + child;
        return full.length() <= 256 ? full : "d=" + detectionOptions.sha256() + ";" + child;
    }

    /**
     * Adds one crop origin without another scale/rotation transform.
     * @param box source-crop pixel rectangle
     * @param crop actual integer crop origin
     * @return original-page pixel rectangle
     */
    private static PdfTableLayoutProvider.Box offset(
            final PdfTableLayoutProvider.Box box, final DetectionCrops.Crop crop) {
        return new PdfTableLayoutProvider.Box(
                box.left() + crop.left(), box.top() + crop.top(), box.right() + crop.left(), box.bottom() + crop.top());
    }

    /**
     * Drops both conflicting final table envelopes rather than emitting ambiguous ownership.
     * @param tables translated candidate layouts
     * @param work shared geometry/cancellation counter
     * @return immutable disjoint layouts in detection encounter order
     * @throws IOException if geometry work/cancellation fails
     */
    private static List<PdfTableLayoutProvider.Table> disjoint(
            final List<PdfTableLayoutProvider.Table> tables, final LayoutWork work) throws IOException {
        final var ambiguous = new boolean[tables.size()];
        for (int first = 0; first < tables.size(); first++) {
            for (int second = first + 1; second < tables.size(); second++) {
                work.step();
                final var a = tables.get(first).bounds();
                final var b = tables.get(second).bounds();
                if (Math.min(a.right(), b.right()) > Math.max(a.left(), b.left())
                        && Math.min(a.bottom(), b.bottom()) > Math.max(a.top(), b.top())) {
                    ambiguous[first] = true;
                    ambiguous[second] = true;
                }
            }
        }
        final var result = new ArrayList<PdfTableLayoutProvider.Table>();
        for (int index = 0; index < tables.size(); index++) {
            work.step();
            if (!ambiguous[index]) {
                result.add(tables.get(index));
            }
        }
        return List.copyOf(result);
    }

    /**
     * Stops parent admissions before closing both owned sessions; interrupted cleanup may be retried.
     * @throws IOException if either session cannot be closed
     */
    @Override
    public void close() throws IOException {
        IOException failure = null;
        try {
            detector.close();
        } catch (final IOException exception) {
            failure = exception;
        }
        try {
            structure.close();
        } catch (final IOException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
