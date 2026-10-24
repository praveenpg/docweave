package io.docweave.partition;

import io.docweave.core.ElementType;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Bounded anchor-cell OCR and exclusive ownership for conservative closed rendered grids. */
final class PdfScannedTables {
    /** Border removal in rendered pixels; accepted rules are at most eight pixels thick. */
    private static final int INSET = 5;
    /** White OCR margin around cell contents. */
    private static final int PADDING = 12;
    /** Fixed projected fill/copy/masking work ceiling, separate from detector pixel scans. */
    private static final long MAX_PIXEL_VISITS = 64_000_000;
    /** Accepted tables in rendered pixel coordinates. */
    private final List<PdfOcrElement> tables = new ArrayList<>();
    /** Shared scanned-stage topology and ownership work. */
    private final PdfTableWork work = new PdfTableWork();
    /** Projected raster work consumed by cell crops and table whiteout. */
    private long pixelVisits;

    /** Optional page-coordinate writer, configured only by explicit PdfOptions. */
    private final PdfTableCells cellMetadata;

    /** Optional bounded continuation-evidence frame, independent of emitted cell metadata. */
    private final PdfTableCells continuationFrame;

    /** Creates page-confined ownership state without optional cell geometry. */
    PdfScannedTables() {
        this(null);
    }

    /**
     * Creates page-local ownership and optional geometry serialization.
     * @param cellMetadata page/raster scale policy; null disables the metadata extension
     */
    PdfScannedTables(final PdfTableCells cellMetadata) {
        this(cellMetadata, null);
    }

    /**
     * Selects independent optional geometry and continuation policies for this page.
     * @param cellMetadata emitted geometry writer, or null
     * @param continuationFrame private continuation evidence writer, or null
     */
    PdfScannedTables(final PdfTableCells cellMetadata, final PdfTableCells continuationFrame) {
        this.cellMetadata = cellMetadata;
        this.continuationFrame = continuationFrame;
    }

    /**
     * Recognizes eligible cells before clearing accepted table areas from ordinary OCR input.
     * @param image internally owned full-page or native-masked raster; accepted areas are whitened after recognition
     * @param provider caller-owned synchronous OCR strategy
     * @param budget shared page line/character budget
     * @param options scanned-table policy and call ceiling
     * @param limits structured-table dimensions/field policy
     * @param regions optional mixed-page/native ownership constraints
     * @return immutable accepted table elements
     * @throws IOException if recognition, cancellation or any cell/table/output/work budget fails
     */
    List<PdfOcrElement> recognize(final BufferedImage image, final PdfOcrProvider provider, final PdfOcrBudget budget,
            final PdfOcrOptions options, final TableLimits limits, final PdfOcrSelection regions) throws IOException {
        final var eligible = new ArrayList<PdfTableGrid>();
        int cells = 0;
        final var bounded = new TableLimits(limits.maxRows(), limits.maxColumns(),
                Math.min(limits.maxCells(), options.maxTableCells()), limits.maxCellCharacters());
        for (final var grid : RasterPdfTableDetector.detect(image, bounded, work)) {
            work.step();
            final var box = bounds(grid);
            if (regions != null && !regions.owns(box)) {
                continue;
            }
            if (grid.cells().size() > options.maxTableCells() - cells) {
                throw limit("Scanned PDF tables exceed maxTableCells");
            }
            cells += grid.cells().size();
            for (final var cell : grid.cells()) {
                work.step();
                final var crop = crop(grid, cell);
                final long pixels =
                        (long) (crop.right - crop.left + 2 * PADDING) * (crop.bottom - crop.top + 2 * PADDING);
                if (pixels > options.maxRenderPixels()) {
                    throw new PartitionException(
                            PartitionException.Code.OCR_LIMIT, "PDF OCR cell exceeds maxRenderPixels");
                }
                reserve(2 * pixels);
            }
            reserve((long) Math.ceil(box.right() - box.left() + 1) * (long) Math.ceil(box.bottom() - box.top() + 1));
            eligible.add(grid);
        }
        for (final var grid : eligible) {
            final var values = new ArrayList<String>();
            for (final var cell : grid.cells()) {
                work.step();
                final var rectangle = crop(grid, cell);
                final var raster = cellImage(image, rectangle);
                try {
                    final var valuesInCell = budget.recognize(provider, raster, PdfOcrProvider.InputKind.TABLE_CELL)
                                                     .stream()
                                                     .map(PdfOcrProvider.Line::text)
                                                     .filter(value -> !value.isBlank())
                                                     .toList();
                    budget.reserveCharacters(Math.max(0, valuesInCell.size() - 1));
                    final String value = String.join(" ", valuesInCell);
                    if (value.codePointCount(0, value.length()) > limits.maxCellCharacters()) {
                        throw limit("Scanned PDF table cell exceeds maxCellCharacters");
                    }
                    values.add(value);
                } finally {
                    raster.flush();
                }
            }
            if (values.stream().anyMatch(value -> !value.isEmpty())) {
                final var nonempty = values.stream().filter(value -> !value.isEmpty()).toList();
                budget.reserveCharacters(Math.max(0, nonempty.size() - 1));
                final var metadata = new LinkedHashMap<String, Object>();
                metadata.put("text_as_html", html(grid, values));
                metadata.put("pdf_table_method",
                        grid.cells().stream().anyMatch(cell -> cell.rowSpan() > 1 || cell.columnSpan() > 1)
                                ? "scanned_ruled_grid_with_spans_v1"
                                : "scanned_complete_ruled_grid_v1");
                if (cellMetadata != null) {
                    metadata.put("pdf_table_cells", cellMetadata.grid(grid, work));
                }
                PdfTableSignature signature = null;
                if (continuationFrame != null && PdfTableSignature.supports(grid)) {
                    final int columns = grid.xs().size() - 1;
                    boolean body = false;
                    for (int index = columns; index < values.size(); index++) {
                        work.step();
                        body |= !values.get(index).isBlank();
                    }
                    signature = continuationFrame.signature(grid, values.subList(0, columns), body, "ocr_rules", work);
                }
                tables.add(new PdfOcrElement(
                        ElementType.Table, String.join(" ", nonempty), bounds(grid), metadata, signature));
            }
        }
        final var drawing = image.createGraphics();
        try {
            drawing.setColor(Color.WHITE);
            for (final var table : tables) {
                work.step();
                final var box = table.box();
                drawing.fillRect((int) Math.floor(box.left()), (int) Math.floor(box.top()),
                        (int) Math.ceil(box.right() - box.left()) + 1, (int) Math.ceil(box.bottom() - box.top()) + 1);
            }
        } finally {
            drawing.dispose();
        }
        return List.copyOf(tables);
    }

    /**
     * Excludes ordinary lines whose boxes intersect a table already owning the source content.
     * @param line validated ordinary OCR line
     * @return whether no accepted table overlaps the line
     * @throws IOException if cancelled or over the shared geometry budget
     */
    boolean retain(final PdfOcrProvider.Line line) throws IOException {
        for (final var table : tables) {
            work.step();
            final var box = table.box();
            if (line.left() < box.right() && line.right() > box.left() && line.top() < box.bottom()
                    && line.bottom() > box.top()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Builds all-td HTML, preserving empty positions and escaping recognized text.
     * @param grid closed topology with row-major rectangular anchor cells
     * @param values text for each anchor exactly once
     * @return bounded structural HTML
     * @throws IOException if interrupted or over geometry work
     */
    private String html(final PdfTableGrid grid, final List<String> values) throws IOException {
        final var html = new StringBuilder("<table><tbody>");
        int anchor = 0;
        for (int row = 0; row < grid.ys().size() - 1; row++) {
            work.step();
            html.append("<tr>");
            while (anchor < grid.cells().size() && grid.cells().get(anchor).row() == row) {
                work.step();
                final var cell = grid.cells().get(anchor);
                html.append("<td");
                if (cell.rowSpan() > 1) {
                    html.append(" rowspan=\"").append(cell.rowSpan()).append('"');
                }
                if (cell.columnSpan() > 1) {
                    html.append(" colspan=\"").append(cell.columnSpan()).append('"');
                }
                html.append('>').append(HtmlTextEscaper.escape(values.get(anchor++))).append("</td>");
            }
            html.append("</tr>");
        }
        return html.append("</tbody></table>").toString();
    }

    /**
     * Copies only cell interiors into a white padded raster. Does not mutate the page while other cells need it.
     * @param source rendered page
     * @param crop source interior bounds
     * @return caller-owned cell raster
     */
    private static BufferedImage cellImage(final BufferedImage source, final Crop crop) {
        final int width = crop.right - crop.left;
        final int height = crop.bottom - crop.top;
        final var image = new BufferedImage(width + 2 * PADDING, height + 2 * PADDING, BufferedImage.TYPE_INT_RGB);
        final var drawing = image.createGraphics();
        try {
            drawing.setColor(Color.WHITE);
            drawing.fillRect(0, 0, image.getWidth(), image.getHeight());
            drawing.drawImage(source, PADDING, PADDING, PADDING + width, PADDING + height, crop.left, crop.top,
                    crop.right, crop.bottom, null);
        } finally {
            drawing.dispose();
        }
        return image;
    }

    /**
     * Removes rule pixels using the fixed supported thickness bound.
     * @param grid complete grid
     * @param cell rectangular anchor, including all occupied rows/columns
     * @return positive interior bounds
     */
    private static Crop crop(final PdfTableGrid grid, final PdfTableGrid.Cell cell) {
        return new Crop((int) Math.ceil(grid.xs().get(cell.column())) + INSET,
                (int) Math.ceil(grid.ys().get(cell.row())) + INSET,
                (int) Math.floor(grid.xs().get(cell.column() + cell.columnSpan())) - INSET,
                (int) Math.floor(grid.ys().get(cell.row() + cell.rowSpan())) - INSET);
    }

    /**
     * Returns enclosing grid bounds in provider pixel coordinates.
     * @param grid validated topology
     * @return rectangle value with no recognized text
     */
    private static PdfOcrProvider.Line bounds(final PdfTableGrid grid) {
        return new PdfOcrProvider.Line(
                "", grid.xs().getFirst(), grid.ys().getFirst(), grid.xs().getLast(), grid.ys().getLast());
    }

    /**
     * Admits projected drawing work without overflow.
     * @param pixels projected affected pixels
     * @throws PartitionException if the fixed cell-copy/whiteout budget is exceeded
     */
    private void reserve(final long pixels) throws PartitionException {
        if (pixels > MAX_PIXEL_VISITS - pixelVisits) {
            throw limit("Scanned PDF table cell copies exceed pixel-work ceiling");
        }
        pixelVisits += pixels;
    }

    /**
     * Creates a stable table-policy failure.
     * @param message limit diagnostic
     * @return checked typed failure
     */
    private static PartitionException limit(final String message) {
        return new PartitionException(PartitionException.Code.TABLE_LIMIT, message);
    }

    /**
     * Positive source pixel interior.
     * @param left minimum x
     * @param top minimum y
     * @param right exclusive maximum x
     * @param bottom exclusive maximum y
     */
    private record Crop(int left, int top, int right, int bottom) {}
}
