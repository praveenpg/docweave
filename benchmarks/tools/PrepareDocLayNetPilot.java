package dev.docweave.validation;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/** Deterministic metadata-only selection and private export; performs no detector inference. */
public final class PrepareDocLayNetPilot {
    /** Prevents construction. */
    private PrepareDocLayNetPilot() {
    }

    /**
     * Exports exactly four positive and four negative pages per available category, without model outputs.
     * @param args inventory JSON, pinned shard, fresh private directory and fresh selection report
     * @throws Exception if pins, metadata, coordinates, JDBC or source pairing fails
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("inventory shard private-output selection-report");
        }
        final var mapper = JsonMapper.builder().build();
        final var inventory = mapper.readTree(Files.readAllBytes(Path.of(args[0])));
        final var selected = new ArrayList<JsonNode>();
        final var usage = new HashMap<String, Integer>();
        for (final var category : List.of("laws_and_regulations", "manuals", "patents")) {
            for (final boolean positive : List.of(true, false)) {
                final var candidates = new ArrayList<JsonNode>();
                for (final var row : inventory) {
                    if (row.get("metadata").get("doc_category").asString().equals(category)
                            && (row.get("table_count").asInt() > 0) == positive) {
                        candidates.add(row);
                    }
                }
                int count = 0;
                while (count < 4) {
                    candidates.sort(Comparator.comparingInt((JsonNode n) -> usage.getOrDefault(doc(n), 0))
                                    .thenComparing(PrepareDocLayNetPilot::doc)
                                    .thenComparingInt(n -> n.get("metadata").get("page_no").asInt())
                                    .thenComparing(n -> n.get("metadata").get("page_hash").asString()));
                    final var next = candidates.stream()
                                             .filter(n -> usage.getOrDefault(doc(n), 0) < 2)
                                             .findFirst()
                                             .orElseThrow();
                    candidates.remove(next);
                    selected.add(next);
                    usage.merge(doc(next), 1, Integer::sum);
                    count++;
                }
            }
        }
        final var documents = selected.stream().map(PrepareDocLayNetPilot::doc).distinct().sorted().toList();
        final Path output = Files.createDirectory(Path.of(args[2]));
        Class.forName("org.duckdb.DuckDBDriver");
        final var pages = new ArrayList<Map<String, Object>>();
        final var contact = new BufferedImage(1024, selected.size() * 360 / 2, BufferedImage.TYPE_INT_RGB);
        final var canvas = contact.createGraphics();
        canvas.setColor(Color.WHITE);
        canvas.fillRect(0, 0, contact.getWidth(), contact.getHeight());
        try (final var connection = DriverManager.getConnection("jdbc:duckdb:");
                final var statement = connection.createStatement()) {
            statement.execute("SET autoinstall_known_extensions=false");
            statement.execute("SET threads=1");
            statement.execute("SET memory_limit='384MB'");
            final var query = connection.prepareStatement(
                    "SELECT pdf,image.bytes FROM read_parquet(?) WHERE metadata.image_id=?");
            for (int index = 0; index < selected.size(); index++) {
                final var row = selected.get(index);
                final var meta = row.get("metadata");
                query.setString(1, args[1]);
                query.setLong(2, meta.get("image_id").asLong());
                final byte[] pdfBytes;
                final byte[] pngBytes;
                try (final var result = query.executeQuery()) {
                    if (!result.next()) {
                        throw new IllegalStateException("Missing selected row");
                    }
                    pdfBytes = result.getBytes(1);
                    pngBytes = result.getBytes(2);
                    if (result.next()) {
                        throw new IllegalStateException("Duplicate row");
                    }
                }
                require(hash(pdfBytes).equals(row.get("pdf_sha256").asString()), "PDF hash changed");
                final String id = meta.get("page_hash").asString();
                require(id.matches("[a-f0-9]{64}"), "Invalid page identity");
                Files.write(output.resolve(id + ".pdf"), pdfBytes, StandardOpenOption.CREATE_NEW);
                Files.write(output.resolve(id + ".png"), pngBytes, StandardOpenOption.CREATE_NEW);
                final var png = ImageIO.read(new java.io.ByteArrayInputStream(pngBytes));
                final int width = meta.get("coco_width").asInt(), height = meta.get("coco_height").asInt();
                require(png.getWidth() == width && png.getHeight() == height, "Annotation frame differs");
                final var tables = new ArrayList<List<Double>>();
                require(row.get("boxes").size() == row.get("categories").size(), "Labels incomplete");
                for (int i = 0; i < row.get("boxes").size(); i++) {
                    final var b = row.get("boxes").get(i);
                    require(b.size() == 4, "Invalid COCO box");
                    double l = b.get(0).asDouble(), t = b.get(1).asDouble(), r = l + b.get(2).asDouble(),
                           bottom = t + b.get(3).asDouble();
                    require(Double.isFinite(l) && Double.isFinite(t) && Double.isFinite(r) && Double.isFinite(bottom)
                                    && r > l && bottom > t && l >= -1 && t >= -1 && r <= width + 1
                                    && bottom <= height + 1,
                            "Invalid bounds");
                    if (row.get("categories").get(i).asInt() == 9) {
                        tables.add(
                                List.of(Math.max(0, l), Math.max(0, t), Math.min(width, r), Math.min(height, bottom)));
                    }
                }
                final var page = new LinkedHashMap<String, Object>();
                page.put("id", id);
                page.put("metadata", meta);
                page.put("pdf_sha256", hash(pdfBytes));
                page.put("png_sha256", hash(pngBytes));
                page.put("tables", tables);
                page.put("negative", tables.isEmpty());
                page.put("producer", row.get("producer").asString());
                page.put("group", documents.indexOf(doc(row)) % 2 == 0 ? "development" : "document_transfer");
                try (final var pdf = Loader.loadPDF(pdfBytes)) {
                    require(pdf.getNumberOfPages() == 1, "Expected one extracted page");
                    final var p = pdf.getPage(0);
                    require(p.getRotation() == 0 && p.getUserUnit() == 1 && p.getCropBox().getLowerLeftX() == 0
                                    && p.getCropBox().getLowerLeftY() == 0,
                            "Unsupported frame");
                    require(Math.abs(p.getCropBox().getWidth() - meta.get("original_width").asDouble()) < 0.02
                                    && Math.abs(p.getCropBox().getHeight() - meta.get("original_height").asDouble())
                                               < 0.02,
                            "Original page extent mismatch");
                    page.put("pdf_width", p.getCropBox().getWidth());
                    page.put("pdf_height", p.getCropBox().getHeight());
                    final var rendered = new PDFRenderer(pdf).renderImageWithDPI(0, 72);
                    final var scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                    final var g = scaled.createGraphics();
                    g.drawImage(rendered, 0, 0, width, height, null);
                    g.dispose();
                    rendered.flush();
                    long error = 0;
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
                            int a = png.getRGB(x, y), b = scaled.getRGB(x, y);
                            error += Math.abs((a >> 16 & 255) - (b >> 16 & 255))
                                     + Math.abs((a >> 8 & 255) - (b >> 8 & 255)) + Math.abs((a & 255) - (b & 255));
                        }
                    }
                    page.put("frame_rgb_mae", error / (double) (width * height * 3));
                    final int x = (index % 2) * 512, y = (index / 2) * 360;
                    canvas.drawImage(png, x, y, 256, 336, null);
                    canvas.drawImage(scaled, x + 256, y, 256, 336, null);
                    canvas.setColor(Color.RED);
                    for (final var b : tables) {
                        for (int offset : new int[] {0, 256})
                            canvas.drawRect(x + offset + (int) (b.get(0) * 256 / width),
                                    y + (int) (b.get(1) * 336 / height), (int) ((b.get(2) - b.get(0)) * 256 / width),
                                    (int) ((b.get(3) - b.get(1)) * 336 / height));
                    }
                    canvas.setColor(Color.BLACK);
                    canvas.drawString(index + " " + doc(row) + " p" + meta.get("page_no").asInt(), x, y + 350);
                    scaled.flush();
                }
                png.flush();
                pages.add(page);
            }
            query.close();
        }
        canvas.dispose();
        ImageIO.write(contact, "png", output.resolve("frame-contact.png").toFile());
        contact.flush();
        Files.writeString(Path.of(args[3]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("shard_sha256",
                        hash(Files.readAllBytes(Path.of(args[1]))), "inventory_sha256",
                        hash(Files.readAllBytes(Path.of(args[0]))), "selection",
                        "4 positive + 4 negative per category; minimum document usage then filename/page/hash; max2 per original document; sorted document parity transfer group",
                        "pages", pages))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Returns original document identity for grouping.
     * @param row inventory row
     * @return document filename
     */
    private static String doc(final JsonNode row) {
        return row.get("metadata").get("original_filename").asString();
    }

    /**
     * Validates source invariants.
     * @param condition invariant
     * @param message failure explanation
     */
    private static void require(final boolean condition, final String message) {
        if (!condition)
            throw new IllegalStateException(message);
    }

    /**
     * Hashes exact source bytes.
     * @param bytes complete bytes
     * @return SHA-256
     * @throws Exception if unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
