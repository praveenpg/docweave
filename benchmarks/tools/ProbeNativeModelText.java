package io.docweave.partition;

import org.apache.pdfbox.Loader;
import org.jsoup.Jsoup;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline native-text feasibility probe using previously captured, frozen model cell geometry.
 * This is not a production partitioner: it neither invokes a model nor emits ordinary unclaimed text.
 * Raw local output contains document text and must remain outside version control.
 */
public final class ProbeNativeModelText {
    /** Prevents utility construction. */
    private ProbeNativeModelText() {
    }

    /**
     * Reprojects unchanged native glyphs onto saved displayed-frame model anchors.
     * @param args source cache, frozen cases TSV, existing capture root and new output root;
     *             or --self-test for owned offline ownership controls
     * @throws Exception if identity, frame, geometry, ownership or output checks fail
     */
    public static void main(final String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--self-test")) {
            controls();
            return;
        }
        if (args.length != 4) {
            throw new IllegalArgumentException("Expected cache, cases TSV, captured geometry root, NEW output root");
        }
        final var output = Files.createDirectory(Path.of(args[3]));
        for (final String row : Files.readAllLines(Path.of(args[1]))) {
            final var pin = row.split("\\t", -1);
            if (pin.length != 4 || !pin[0].matches("[a-z-]+") || !pin[1].matches("[A-Za-z0-9_.-]+\\.pdf")
                    || !pin[3].matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("Invalid pinned source case");
            }
            final var sourcePdf = Path.of(args[0], pin[1]);
            if (Files.size(sourcePdf) > 16 * 1024 * 1024) {
                throw new IllegalArgumentException("Probe PDF exceeds 16MiB");
            }
            final var bytes = Files.readAllBytes(sourcePdf);
            if (!digest(bytes).equals(pin[3])) {
                throw new IllegalArgumentException("Source SHA256 mismatch");
            }
            final int selected = Integer.parseInt(pin[2]);
            final var directory = Files.createDirectory(output.resolve(pin[0]));
            final var mapper = JsonMapper.builder().build();
            final var sourcePath = Path.of(args[2], pin[0], "source.json");
            final var source = mapper.readTree(Files.readString(sourcePath));
            if (!source.get("pdf_sha256").asString().equals(pin[3]) || source.get("source_page").asInt() != selected) {
                throw new IllegalArgumentException("Capture source identity mismatch");
            }
            final var capturePath = Path.of(args[2], pin[0], "page.json");
            final var identities = mapper.readTree(
                    Files.readString(Path.of("benchmarks/review/pdf-current-business-model/identities.json")));
            final String captureHash = digest(Files.readAllBytes(capturePath));
            final String captureKey = pin[0] + "/page.json";
            if (!captureHash.equals(identities.get("local_capture_identities").get("21").get(captureKey).asString())
                    && !captureHash.equals(
                            identities.get("local_capture_identities").get("25").get(captureKey).asString())) {
                throw new IllegalArgumentException("Frozen geometry capture identity mismatch");
            }
            final var capture = mapper.readTree(Files.readString(capturePath));
            if (!capture.get("outcome").asString().equals("SUCCESS")
                    || !capture.get("layout_strategy").asString().equals("onnx_detection_structure")) {
                throw new IllegalArgumentException("Expected successful frozen model capture");
            }
            final var tables = new ArrayList<Map<String, Object>>();
            try (final var document = Loader.loadPDF(bytes)) {
                final var page = document.getPage(selected - 1);
                final var crop = page.getCropBox();
                if (page.getRotation() != 0 || page.getUserUnit() != 1 || crop.getLowerLeftX() != 0
                        || crop.getLowerLeftY() != 0 || source.get("source_rotation").asInt() != 0
                        || crop.getWidth() != source.get("page_width").asDouble()
                        || crop.getHeight() != source.get("page_height").asDouble()) {
                    throw new IllegalArgumentException("Probe requires matching upright origin-zero unit crop");
                }
                final var text = new PdfPageText(PdfOptions.defaults());
                text.setStartPage(selected);
                text.setEndPage(selected);
                text.getText(document);
                if (text.glyphs().stream().anyMatch(glyph -> glyph.direction() != 0)) {
                    throw new IllegalArgumentException("Probe requires upright native glyphs");
                }
                final var owned = new boolean[text.glyphs().size()];
                final var work = new PdfTableWork();
                for (final var table : capture.get("tables")) {
                    final var html = Jsoup.parse(table.get("text_as_html").asString()).selectFirst("table");
                    if (html == null) {
                        throw new IllegalArgumentException("Missing captured HTML topology");
                    }
                    // Keep frozen row/span topology but discard every OCR cell string before assignment.
                    final var htmlCells = html.select("td, th");
                    final var cells = table.get("pdf_table_cells").get("cells");
                    if (htmlCells.size() != cells.size() || cells.size() > 1024) {
                        throw new IllegalArgumentException("Unexpected frozen anchor count");
                    }
                    int index = 0;
                    for (final var cell : cells) {
                        final var box = rectangle(cell.get("coordinates"), crop.getWidth(), crop.getHeight());
                        htmlCells.get(index++).text(assign(text.glyphs(), owned, box, work));
                    }
                    final var metadata = new LinkedHashMap<String, Object>();
                    metadata.put("page_number", 1);
                    metadata.put("coordinates", mapper.convertValue(table.get("coordinates"), Map.class));
                    metadata.put("text_as_html", html.outerHtml());
                    tables.add(metadata);
                }
                final var report = new LinkedHashMap<String, Object>();
                report.put("outcome", "SUCCESS");
                report.put("layout_strategy", "frozen_model_native_glyph_probe");
                report.put("tables", tables);
                report.put("source_glyphs", text.glyphs().size());
                int claimed = 0;
                for (final boolean member : owned) {
                    if (member) {
                        claimed++;
                    }
                }
                report.put("claimed_glyphs", claimed);
                report.put("unclaimed_glyphs", owned.length - claimed);
                report.put("geometry_capture_sha256", digest(Files.readAllBytes(capturePath)));
                for (final String counter : List.of("page_calls", "cell_calls", "layout_calls", "layout_tables")) {
                    report.put(counter, 0);
                }
                Files.write(directory.resolve("native.json"), mapper.writeValueAsBytes(report),
                        StandardOpenOption.CREATE_NEW);
                Files.copy(sourcePath, directory.resolve("source.json"));
            }
        }
    }

    /**
     * Assigns each source glyph once by its center to half-open cell bounds, then uses native line spacing.
     * @param glyphs original upright glyph sequence
     * @param owned page-wide ownership flags; overlapping claims fail instead of silently duplicating text
     * @param box left/top/right/bottom in displayed PDF units
     * @param work bounded shared cancellation/work counter
     * @return baseline-ordered native lines joined by one space, without numeric or punctuation repair
     * @throws Exception if cancellation, work, ownership or cell text ceilings fail
     */
    private static String assign(final List<PdfPageText.Glyph> glyphs, final boolean[] owned, final double[] box,
            final PdfTableWork work) throws Exception {
        final var members = new ArrayList<PdfPageText.Glyph>();
        for (int index = 0; index < glyphs.size(); index++) {
            work.step();
            final var glyph = glyphs.get(index);
            final double x = glyph.x() + glyph.width() / 2;
            final double y = glyph.y() - glyph.height() / 2;
            if (x >= box[0] && x < box[2] && y >= box[1] && y < box[3]) {
                if (owned[index]) {
                    throw new IllegalArgumentException("Overlapping native glyph ownership");
                }
                owned[index] = true;
                members.add(glyph);
            }
        }
        final var value = String.join(" ", PdfGlyphLines.lines(members).stream().map(PdfLayoutItem::text).toList());
        if (value.codePointCount(0, value.length()) > TableLimits.defaults().maxCellCharacters()) {
            throw new IllegalArgumentException("Probe cell text limit exceeded");
        }
        return value;
    }

    /**
     * Validates a saved cell rectangle against the exact source frame.
     * @param coordinates frozen displayed coordinate object
     * @param width original crop width
     * @param height original crop height
     * @return positive contained half-open rectangle
     */
    private static double[] rectangle(final JsonNode coordinates, final double width, final double height) {
        if (!coordinates.get("system").asString().equals("PDFUserSpace")
                || coordinates.get("layout_width").asDouble() != width
                || coordinates.get("layout_height").asDouble() != height) {
            throw new IllegalArgumentException("Geometry frame mismatch");
        }
        final var points = coordinates.get("points");
        final double[] box = {points.get(0).get(0).asDouble(), points.get(0).get(1).asDouble(),
                points.get(2).get(0).asDouble(), points.get(2).get(1).asDouble()};
        if (!java.util.Arrays.stream(box).allMatch(Double::isFinite) || box[0] < 0 || box[1] < 0 || box[2] > width
                || box[3] > height || box[0] >= box[2] || box[1] >= box[3]) {
            throw new IllegalArgumentException("Invalid probe cell bounds");
        }
        return box;
    }

    /**
     * Runs owned controls for wrapped/section/numeric text, source preservation and boundary ownership.
     * @throws Exception if the native projection contract regresses
     */
    private static void controls() throws Exception {
        final var glyphs = List.of(new PdfPageText.Glyph("Section", 2, 10, 8, 4, 3, 0),
                new PdfPageText.Glyph("Wrapped", 2, 20, 8, 4, 3, 0), new PdfPageText.Glyph("label", 2, 30, 8, 4, 3, 0),
                new PdfPageText.Glyph("(001)", 16, 30, 8, 4, 3, 0));
        final var owned = new boolean[4];
        final var work = new PdfTableWork();
        if (!assign(glyphs, owned, new double[] {0, 0, 20, 40}, work).equals("Section Wrapped label")
                || !assign(glyphs, owned, new double[] {20, 0, 30, 40}, work).equals("(001)")) {
            throw new IllegalStateException("Wrapped/section/subtotal or half-open boundary contract failed");
        }
        try {
            assign(glyphs, owned, new double[] {0, 0, 30, 40}, work);
            throw new IllegalStateException("Duplicate ownership was accepted");
        } catch (final IllegalArgumentException expected) {
            // The overlapping replay must fail instead of silently claiming a source glyph twice.
        }
        if (!assign(glyphs, new boolean[4], new double[] {40, 0, 50, 40}, new PdfTableWork()).isEmpty()) {
            throw new IllegalStateException("Empty cell gained fabricated text");
        }
    }

    /**
     * Hashes exact source/capture bytes without exposing their content.
     * @param bytes local input bytes
     * @return lowercase SHA256
     * @throws Exception if the required digest is unavailable
     */
    private static String digest(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
