package dev.docweave.partition;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Audits fixed raster-rule prototypes in an isolated diagnostic classpath, without reading annotations.
 * The runner verifies the production source identity and exposes two private helpers only in temporary
 * compiled copies. No production classes, public service API, image or threshold are changed at runtime.
 */
public final class CaptureRasterRuleAudit {
    /** Prevents construction of the diagnostic launcher. */
    private CaptureRasterRuleAudit() {
    }

    /**
     * Records extraction, post-topology size and remnant rejection stages for one fixed prototype.
     * The accepted grids must reproduce the actual compiled detector entry point exactly. Diagnostic
     * scanning and the entry-point replay have separate work counters, not a combined production budget.
     * @param args region captures, pinned JPEG TSV, NEW output, minimum column width, minimum row height, profile
     * @throws Exception if identity, fresh output, resource or stage-replay requirements fail
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 6) {
            throw new IllegalArgumentException("Expected captures, pins TSV, NEW output, min column/row, profile");
        }
        final var output = Files.createDirectory(Path.of(args[2]));
        final int minColumn = Integer.parseInt(args[3]);
        final int minRow = Integer.parseInt(args[4]);
        controls(output.resolve("controls.tsv"), args[5]);
        for (final var line : Files.readAllLines(Path.of(args[1]))) {
            final var pin = line.split("\t");
            if (pin.length != 2 || !pin[0].matches("cTDaR_[st][0-9]+\\.jpg") || !pin[1].matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("Unexpected source pin");
            }
            final var source = Path.of(args[0]).resolve(pin[0].replace(".jpg", ""));
            final var directory = Files.createDirectory(output.resolve(source.getFileName()));
            final var frame = Files.readAllLines(source.resolve("source.tsv"));
            if (frame.size() != 2 || !frame.get(1).split("\t")[0].equals(pin[1])) {
                throw new IllegalArgumentException("Source identity mismatch");
            }
            Files.copy(source.resolve("source.tsv"), directory.resolve("source.tsv"));
            final var outcome = Files.readString(source.resolve("outcome.txt")).strip();
            if (!outcome.equals("OK")) {
                write(directory.resolve("outcome.txt"), List.of(outcome));
                continue;
            }
            final var raster = source.resolve("render.png");
            final var expected = Files.readString(source.resolve("render.sha256")).strip();
            final var actual =
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(raster)));
            if (!actual.equals(expected)) {
                throw new IllegalArgumentException("Raster identity mismatch");
            }
            write(directory.resolve("render.sha256"), List.of(actual));
            final var image = ImageIO.read(raster.toFile());
            if (image == null) {
                throw new IOException("Unreadable raster");
            }
            try {
                if ((long) image.getWidth() * image.getHeight() > 16_000_000) {
                    throw new IllegalArgumentException("Admitted source exceeds original pixel limit");
                }
                audit(image, directory, minColumn, minRow);
            } finally {
                image.flush();
            }
            System.out.println("Audited: " + source.getFileName());
        }
    }

    /**
     * Checks independent authored grids and false-grid controls without persisting their pixels.
     * These expected geometries are independent of model/annotation output, not corpus labels.
     * @param path NEW numeric control report
     * @param profile predeclared prototype identifier
     * @throws IOException if a resource check or output fails
     */
    private static void controls(final Path path, final String profile) throws IOException {
        final var result = new ArrayList<String>();
        result.add("control\texpected_tables\tobserved_tables");
        final boolean compact = profile.equals("compact") || profile.equals("chromatic_compact");
        for (final var name : List.of("normal_closed", "compact_closed", "colored_compact", "gray_compact",
                     "under_minimum", "broken_outer", "thick_block")) {
            final boolean normal = name.equals("normal_closed");
            final int rows = normal ? 3 : 6;
            final int columns = normal ? 3 : 5;
            final int width = normal ? 40 : name.equals("under_minimum") ? 15 : 16;
            final int height = normal ? 32 : name.equals("under_minimum") ? 11 : 12;
            final var image = new BufferedImage(columns * width + 21, rows * height + 21, BufferedImage.TYPE_INT_RGB);
            try {
                final var graphics = image.createGraphics();
                try {
                    graphics.setColor(Color.WHITE);
                    graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
                    graphics.setColor(name.equals("colored_compact") ? new Color(160, 80, 180)
                                      : name.equals("gray_compact")  ? new Color(160, 160, 160)
                                                                     : Color.BLACK);
                    if (name.equals("thick_block")) {
                        graphics.fillRect(10, 10, columns * width + 1, rows * height + 1);
                    } else {
                        for (int row = 0; row <= rows; row++) {
                            graphics.drawLine(10, 10 + row * height, 10 + columns * width, 10 + row * height);
                        }
                        for (int column = 0; column <= columns; column++) {
                            graphics.drawLine(10 + column * width, 10, 10 + column * width, 10 + rows * height);
                        }
                    }
                    if (name.equals("broken_outer")) {
                        graphics.setColor(Color.WHITE);
                        graphics.fillRect(35, 9, 20, 3);
                    }
                } finally {
                    graphics.dispose();
                }
                final int expected =
                        normal || (compact && name.equals("compact_closed"))
                                        || (profile.equals("chromatic_compact") && name.equals("colored_compact"))
                                ? 1
                                : 0;
                final var grids = RasterPdfTableDetector.detect(
                        image, new TableLimits(100_000, 1_000, 256, 1_000_000), new PdfTableWork());
                if (grids.size() != expected
                        || (!grids.isEmpty()
                                && (grids.getFirst().ys().size() != rows + 1
                                        || grids.getFirst().xs().size() != columns + 1
                                        || grids.getFirst().cells().size() != rows * columns
                                        || grids.getFirst().cells().stream().anyMatch(
                                                cell -> cell.rowSpan() != 1 || cell.columnSpan() != 1)))) {
                    throw new IllegalStateException("Authored topology control failed: " + name);
                }
                result.add(name + "\t" + expected + "\t" + grids.size());
            } finally {
                image.flush();
            }
        }
        write(path, result);
    }

    /**
     * Uses exactly the compiled variant's extraction/topology/remnant logic, with fixed256-slot limits.
     * Raw rule geometry is retained; rejected topology is not repaired or promoted to a table.
     * @param image borrowed unchanged raster
     * @param output fresh source directory
     * @param minColumn prototype's fixed minimum atomic column width
     * @param minRow prototype's fixed minimum atomic row height
     * @throws IOException if cancellation, resource limits or output fail
     */
    private static void audit(final BufferedImage image, final Path output, final int minColumn, final int minRow)
            throws IOException {
        final var work = new PdfTableWork();
        final var limits = new TableLimits(100_000, 1_000, 256, 1_000_000);
        final var segmentRows = new ArrayList<String>();
        segmentRows.add("x1\ty1\tx2\ty2");
        final var accepted = new ArrayList<PdfTableGrid>();
        try {
            final var segments = RasterPdfTableDetector.segments(image, work);
            for (final var segment : segments) {
                segmentRows.add(segment.x1() + "\t" + segment.y1() + "\t" + segment.x2() + "\t" + segment.y2());
            }
            final var trimmed =
                    ((List<?>) inspect("trimCaps", new Class<?>[] {List.class, PdfTableWork.class}, segments, work))
                            .stream()
                            .map(PdfPageGraphics.Segment.class ::cast)
                            .toList();
            final var candidates = new RuledPdfTableDetector(limits).detect(trimmed, work);
            int small = 0;
            int remnants = 0;
            for (final var grid : candidates) {
                boolean size = true;
                for (int index = 1; index < grid.xs().size(); index++) {
                    work.step();
                    size &= grid.xs().get(index) - grid.xs().get(index - 1) >= minColumn;
                }
                for (int index = 1; index < grid.ys().size(); index++) {
                    work.step();
                    size &= grid.ys().get(index) - grid.ys().get(index - 1) >= minRow;
                }
                if (!size) {
                    small++;
                } else if ((Boolean) inspect("hasRuleRemnants",
                                   new Class<?>[] {BufferedImage.class, PdfTableGrid.class, PdfTableWork.class}, image,
                                   grid, work)) {
                    remnants++;
                } else {
                    accepted.add(grid);
                }
            }
            final var replay = RasterPdfTableDetector.detect(image, limits, new PdfTableWork());
            if (!geometry(accepted).equals(geometry(replay))) {
                throw new IllegalStateException("Stage reconstruction differs from compiled detector entry point");
            }
            final long horizontal = segments.stream().filter(segment -> segment.y1() == segment.y2()).count();
            write(output.resolve("stages.tsv"),
                    List.of("horizontal\tvertical\ttopology_candidates\tsmall_cell_rejections\tremnant_rejections\taccepted",
                            horizontal + "\t" + (segments.size() - horizontal) + "\t" + candidates.size() + "\t" + small
                                    + "\t" + remnants + "\t" + accepted.size()));
            write(output.resolve("outcome.txt"), List.of("OK"));
        } catch (final PartitionException failure) {
            write(output.resolve("outcome.txt"), List.of(failure.code().name()));
            accepted.clear();
        }
        write(output.resolve("segments.tsv"), segmentRows);
        final var tables = new ArrayList<String>();
        tables.add("crop\ttable\trows\tcolumns\tleft\ttop\tright\tbottom");
        final var anchors = new ArrayList<String>();
        anchors.add("crop\ttable\trow\tcolumn\trow_span\tcolumn_span\tleft\ttop\tright\tbottom");
        for (int index = 0; index < accepted.size(); index++) {
            final var grid = accepted.get(index);
            tables.add("0\t" + index + "\t" + (grid.ys().size() - 1) + "\t" + (grid.xs().size() - 1) + "\t"
                       + grid.xs().getFirst() + "\t" + grid.ys().getFirst() + "\t" + grid.xs().getLast() + "\t"
                       + grid.ys().getLast());
            for (final var cell : grid.cells()) {
                anchors.add("0\t" + index + "\t" + cell.row() + "\t" + cell.column() + "\t" + cell.rowSpan() + "\t"
                            + cell.columnSpan() + "\t" + grid.xs().get(cell.column()) + "\t" + grid.ys().get(cell.row())
                            + "\t" + grid.xs().get(cell.column() + cell.columnSpan()) + "\t"
                            + grid.ys().get(cell.row() + cell.rowSpan()));
            }
        }
        write(output.resolve("tables.tsv"), tables);
        write(output.resolve("anchors.tsv"), anchors);
    }

    /**
     * Inspects only the explicitly pinned detector's private stage helpers in this diagnostic JVM.
     * Reflection keeps the source compilable against unmodified production classes without adding
     * public/internal access bridges. Typed I/O failures retain their original cancellation/limit code.
     * @param name fixed private helper name from this source
     * @param parameters exact reviewed parameter types
     * @param arguments borrowed call-scoped inputs
     * @return helper result, decoded by the fixed caller
     * @throws IOException if the invoked helper reports a checked failure
     */
    private static Object inspect(final String name, final Class<?>[] parameters, final Object... arguments)
            throws IOException {
        try {
            final var method = RasterPdfTableDetector.class.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return method.invoke(null, arguments);
        } catch (final InvocationTargetException failure) {
            final var cause = failure.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException("Unexpected diagnostic stage failure", cause);
        } catch (final ReflectiveOperationException failure) {
            throw new IllegalStateException("Pinned diagnostic stage schema changed", failure);
        }
    }

    /**
     * Canonicalizes all observed grid coordinates and cell spans for actual-entry-point replay.
     * @param grids complete accepted grids
     * @return deterministic source-free geometry strings
     */
    private static List<String> geometry(final List<PdfTableGrid> grids) {
        return grids.stream().map(grid -> grid.xs() + ";" + grid.ys() + ";" + grid.cells()).toList();
    }

    /**
     * Saves new numeric evidence without overwriting earlier captures.
     * @param path new file
     * @param values source-free diagnostic rows
     * @throws IOException if output fails
     */
    private static void write(final Path path, final List<String> values) throws IOException {
        Files.write(path, values, StandardOpenOption.CREATE_NEW);
    }
}
