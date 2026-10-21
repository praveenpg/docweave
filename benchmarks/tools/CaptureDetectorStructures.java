package dev.docweave.layout;

import dev.docweave.partition.PartitionException;
import dev.docweave.partition.PdfTableLayoutProvider;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Runs fixed detector proposals through the existing bounded crop planner and structure recognizer.
 * This optional diagnostic reads numeric predictions and pinned local rasters, never annotations.
 * It does not implement a new production detector, run OCR or select a profile from reference labels.
 */
public final class CaptureDetectorStructures {
    /** Prevents construction of the standalone diagnostic. */
    private CaptureDetectorStructures() {
    }

    /**
     * Recognizes both fixed proposal profiles on identical previously captured local rasters.
     * Each profile gets a separate deadline, not one hardened aggregate production transaction.
     * @param args capture directory, proposal directory, NEW output directory, structure graph/hash;
     *             optionally a uniform explicit structure-class score cutoff from zero through one
     * @throws Exception if identities, fresh output, model schema or inference requirements fail
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 5 && args.length != 6) {
            throw new IllegalArgumentException(
                    "Expected captures, proposals, NEW output, structure graph/hash and optional score");
        }
        final var captures = Path.of(args[0]);
        final var proposals = Path.of(args[1]);
        final var defaults = OnnxTableOptions.defaults(Path.of(args[3]), args[4]);
        final double minimumScore = args.length == 6 ? Double.parseDouble(args[5]) : defaults.minimumScore();
        final var options = new OnnxTableOptions(defaults.model(), defaults.sha256(), defaults.timeout(),
                defaults.maxConcurrentCalls(), defaults.maxModelBytes(), defaults.maxInputPixels(),
                defaults.maxQueries(), defaults.tableLimits(), minimumScore, defaults.nativeThreads(),
                OnnxTableOptions.MergeConflicts.KEEP_HIGHER_SCORE);
        final var output = Files.createDirectory(Path.of(args[2]));
        write(output.resolve("policy.tsv"),
                List.of("structure_sha256\tminimum_score\tmerge_conflicts",
                        options.sha256() + "\t" + options.minimumScore() + "\t" + options.mergeConflicts().name()));
        try (final var recognizer = new ManagedTatrSession(options, 7)) {
            // The stream owns a directory handle, even though only the materialized paths are used.
            try (final var entries = Files.list(captures)) {
                for (final var source : entries.sorted().toList()) {
                    if (!source.getFileName().toString().matches("[A-Za-z][A-Za-z0-9_.-]{0,100}")) {
                        throw new IllegalArgumentException("Unexpected capture entry");
                    }
                    final var directory = Files.createDirectory(output.resolve(source.getFileName()));
                    Files.copy(source.resolve("source.tsv"), directory.resolve("source.tsv"));
                    final var outcome = Files.readString(source.resolve("outcome.txt")).strip();
                    if (!outcome.equals("OK")) {
                        write(directory.resolve("outcome.txt"), List.of(outcome));
                        continue;
                    }
                    final var rasterPath = source.resolve("render.png");
                    final var hash = hash(rasterPath);
                    if (!hash.equals(Files.readString(source.resolve("render.sha256")).strip())) {
                        throw new IllegalArgumentException("Captured raster identity mismatch");
                    }
                    write(directory.resolve("render.sha256"), List.of(hash));
                    final var image = ImageIO.read(rasterPath.toFile());
                    if (image == null) {
                        throw new IllegalArgumentException("Unreadable captured raster");
                    }
                    try {
                        if ((long) image.getWidth() * image.getHeight() > options.maxInputPixels()) {
                            throw new IllegalArgumentException("Capture unexpectedly exceeds original source limit");
                        }
                        for (final var profile : List.of("tatr", "yolox")) {
                            capture(image, proposals.resolve(source.getFileName()).resolve(profile + ".tsv"),
                                    directory.resolve(profile), recognizer, options);
                        }
                    } finally {
                        image.flush();
                    }
                    System.out.println("Captured structures: " + source.getFileName());
                }
            }
        }
    }

    /**
     * Applies identical crop policy to thresholded ordinary-table proposals from either detector.
     * Raw out-of-frame boxes are explicitly clipped and audited before constructing validated boxes.
     * Scores retain their detector-specific threshold; no second score threshold is introduced.
     * @param image borrowed whole-page raster
     * @param predictions pinned TSV with score and raw raster-coordinate bounds
     * @param output NEW profile directory
     * @param recognizer borrowed seven-label structure session
     * @param options fixed structure policy and resource ceilings
     * @throws IOException if output, geometry or model requirements fail
     */
    private static void capture(final BufferedImage image, final Path predictions, final Path output,
            final ManagedTatrSession recognizer, final OnnxTableOptions options) throws IOException {
        Files.createDirectory(output);
        write(output.resolve("proposals.sha256"), List.of(hash(predictions)));
        final var deadline = new LayoutDeadline(options.timeout());
        final var work = new LayoutWork(deadline);
        final var objects = new ArrayList<LayoutObject>();
        final var audit = new ArrayList<String>();
        audit.add("proposal\tboundary_action");
        final var lines = Files.readAllLines(predictions);
        if (lines.isEmpty() || !lines.getFirst().equals("score\tleft\ttop\tright\tbottom")) {
            throw new IllegalArgumentException("Unexpected proposal schema");
        }
        if (lines.size() > 33) {
            throw new IllegalArgumentException("Raw proposals exceed existing32-crop policy");
        }
        for (int index = 1; index < lines.size(); index++) {
            final var fields = lines.get(index).split("\t");
            if (fields.length != 5) {
                throw new IllegalArgumentException("Unexpected proposal row");
            }
            final var values = new double[5];
            for (int field = 0; field < values.length; field++) {
                values[field] = Double.parseDouble(fields[field]);
                if (!Double.isFinite(values[field])) {
                    throw new IllegalArgumentException("Nonfinite proposal");
                }
            }
            if (values[0] < 0 || values[0] > 1 || values[3] <= values[1] || values[4] <= values[2]) {
                throw new IllegalArgumentException("Invalid raw proposal");
            }
            final double left = Math.max(0, values[1]);
            final double top = Math.max(0, values[2]);
            final double right = Math.min(image.getWidth(), values[3]);
            final double bottom = Math.min(image.getHeight(), values[4]);
            if (right <= left || bottom <= top) {
                audit.add((index - 1) + "\tOUTSIDE");
                continue;
            }
            final boolean clipped = left != values[1] || top != values[2] || right != values[3] || bottom != values[4];
            audit.add((index - 1) + "\t" + (clipped ? "CLIPPED" : "UNCHANGED"));
            objects.add(new LayoutObject(0, values[0], new PdfTableLayoutProvider.Box(left, top, right, bottom)));
        }
        write(output.resolve("boundary.tsv"), audit);
        final var cropRows = new ArrayList<String>();
        cropRows.add("crop\tleft\ttop\tright\tbottom");
        final var tables = new ArrayList<String>();
        tables.add("crop\ttable\trows\tcolumns\tleft\ttop\tright\tbottom");
        final var anchors = new ArrayList<String>();
        anchors.add("crop\ttable\trow\tcolumn\trow_span\tcolumn_span\tleft\ttop\tright\tbottom");
        try {
            final var crops = DetectionCrops.plan(objects, image, options, work);
            for (int index = 0; index < crops.size(); index++) {
                final var crop = crops.get(index);
                cropRows.add(
                        index + "\t" + crop.left() + "\t" + crop.top() + "\t" + crop.right() + "\t" + crop.bottom());
                final var raster = copy(image, crop);
                try {
                    final int cropIndex = index;
                    recognizer.infer(raster, deadline, predictionsForCrop -> {
                        final var recognized = new TableTopology(options, work).tables(predictionsForCrop);
                        for (int tableIndex = 0; tableIndex < recognized.size(); tableIndex++) {
                            final var table = recognized.get(tableIndex);
                            tables.add(cropIndex + "\t" + tableIndex + "\t" + table.rows() + "\t" + table.columns()
                                       + "\t" + bounds(table.bounds(), crop.left(), crop.top()));
                            for (final var anchor : table.anchors()) {
                                anchors.add(cropIndex + "\t" + tableIndex + "\t" + anchor.row() + "\t" + anchor.column()
                                            + "\t" + anchor.rowSpan() + "\t" + anchor.columnSpan() + "\t"
                                            + bounds(anchor.bounds(), crop.left(), crop.top()));
                            }
                        }
                        return recognized.size();
                    });
                } finally {
                    raster.flush();
                }
            }
            write(output.resolve("outcome.txt"), List.of("OK"));
        } catch (final PartitionException failure) {
            write(output.resolve("outcome.txt"), List.of(failure.code().name()));
        }
        write(output.resolve("crops.tsv"), cropRows);
        write(output.resolve("tables.tsv"), tables);
        write(output.resolve("anchors.tsv"), anchors);
    }

    /**
     * Fingerprints explicit local inputs without including their machine paths in saved evidence.
     * @param path local input
     * @return lowercase SHA256
     * @throws IOException if input bytes cannot be read
     */
    private static String hash(final Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (final java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Required SHA-256 is unavailable", failure);
        }
    }

    /**
     * Encodes an enclosing rectangle after adding one exact integer window/crop origin.
     * @param box source-relative box
     * @param x horizontal origin
     * @param y vertical origin
     * @return tab-separated original-frame rectangle
     */
    private static String bounds(final PdfTableLayoutProvider.Box box, final int x, final int y) {
        return (box.left() + x) + "\t" + (box.top() + y) + "\t" + (box.right() + x) + "\t" + (box.bottom() + y);
    }

    /**
     * Copies a bounded source rectangle over white, without changing the borrowed raster.
     * @param source original pixels
     * @param crop validated integer rectangle
     * @return owned RGB raster released by the caller
     */
    private static BufferedImage copy(final BufferedImage source, final DetectionCrops.Crop crop) {
        final var image =
                new BufferedImage(crop.right() - crop.left(), crop.bottom() - crop.top(), BufferedImage.TYPE_INT_RGB);
        final var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.drawImage(source, 0, 0, image.getWidth(), image.getHeight(), crop.left(), crop.top(), crop.right(),
                    crop.bottom(), null);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    /**
     * Saves numeric/identity diagnostics only, rejecting accidental evidence replacement.
     * @param path new output file
     * @param rows text-free geometry or source identity records
     * @throws IOException if the new output cannot be written
     */
    private static void write(final Path path, final List<String> rows) throws IOException {
        Files.write(path, rows, StandardOpenOption.CREATE_NEW);
    }
}
