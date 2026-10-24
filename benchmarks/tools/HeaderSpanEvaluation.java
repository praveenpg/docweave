package io.docweave.partition;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.jsoup.Jsoup;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Authored synthetic header-span controls whose truth precedes model extraction. */
public final class HeaderSpanEvaluation {
    /** Prevents launcher construction. */
    private HeaderSpanEvaluation() {
    }

    /**
     * One independently authored logical cell; text is owned synthetic content.
     * @param row logical row
     * @param column logical column
     * @param rowSpan logical row extent
     * @param columnSpan logical column extent
     * @param lines explicit physical lines within this logical cell
     */
    private record Cell(int row, int column, int rowSpan, int columnSpan, List<String> lines) {}

    /**
     * Generates fresh PDFs/references or scores unchanged captures against those references.
     * @param args generate NEW directory; score reference directory, capture directory, NEW report; or --self-test
     * @throws Exception if generation, capture validation or scoring fails
     */
    public static void main(final String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--self-test")) {
            controls();
        } else if (args.length == 2 && args[0].equals("generate")) {
            generate(Path.of(args[1]));
        } else if (args.length == 4 && args[0].equals("score")) {
            score(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
        } else {
            throw new IllegalArgumentException(
                    "Expected generate directory; score references captures report; --self-test");
        }
    }

    /**
     * Defines all eight cases before any PDF is passed to extraction.
     * @param root fresh output directory
     * @throws Exception if an authored PDF or immutable reference cannot be written
     */
    private static void generate(final Path root) throws Exception {
        Files.createDirectory(root);
        final var mapper = JsonMapper.builder().build();
        final var cases = new ArrayList<Map<String, Object>>();
        final var pins = new ArrayList<String>();
        for (final boolean ruled : List.of(true, false)) {
            for (final String kind : List.of("wrapped", "stacked", "grouped", "body-wrapped")) {
                final String id = (ruled ? "ruled-" : "borderless-") + kind;
                final var cells = cells(kind);
                final int headers = kind.equals("stacked") || kind.equals("grouped") ? 2 : 1;
                final double[] heights = new double[headers + 6];
                java.util.Arrays.fill(heights, 28);
                for (int r = 0; r < headers; r++) {
                    heights[r] = headers == 2 ? 20 : 40;
                }
                if (kind.equals("body-wrapped")) {
                    heights[headers] = 40;
                }
                final var pdf = root.resolve(id + ".pdf");
                render(pdf, cells, heights, ruled);
                final String sha = hash(Files.readAllBytes(pdf));
                cases.add(Map.of("id", id, "pdf_sha256", sha, "rows", heights.length, "columns", 4, "header_rows",
                        headers, "anchors", cells.stream().map(HeaderSpanEvaluation::key).toList(), "content_keys",
                        cells.stream().map(cell -> key(cell) + ":" + String.join(" ", cell.lines())).toList()));
                pins.add(id + "\t" + id + ".pdf\t1\t" + sha);
            }
        }
        Files.write(root.resolve("cases.tsv"), pins, StandardOpenOption.CREATE_NEW);
        Files.write(root.resolve("references.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(cases),
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Defines logical topology independently of detector output or text-line count.
     * @param kind authored header/body variant
     * @return every logical cell, including all six body rows
     */
    private static List<Cell> cells(final String kind) {
        final var result = new ArrayList<Cell>();
        final boolean stacked = kind.equals("stacked");
        final boolean grouped = kind.equals("grouped");
        final int headers = stacked || grouped ? 2 : 1;
        if (grouped) {
            result.add(new Cell(0, 0, 2, 1, List.of("Region")));
            result.add(new Cell(0, 1, 1, 3, List.of("Annual amounts")));
        } else {
            for (int c = 0; c < 4; c++) {
                result.add(new Cell(0, c, 1, 1,
                        stacked ? List.of("Region " + (c + 1)) : List.of("Annual revenue", "Region " + (c + 1))));
            }
        }
        if (headers == 2) {
            for (int c = grouped ? 1 : 0; c < 4; c++) {
                result.add(new Cell(1, c, 1, 1, List.of("Year " + (2024 + c % 2))));
            }
        }
        for (int r = 0; r < 6; r++) {
            for (int c = 0; c < 4; c++) {
                result.add(new Cell(headers + r, c, 1, 1,
                        kind.equals("body-wrapped") && r == 0 && c == 0 ? List.of("Service", "revenue")
                                                                        : List.of(Integer.toString(100 + r * 10 + c))));
            }
        }
        return result;
    }

    /**
     * Renders original authored cells, with cell borders derived only from authored topology.
     * @param output fresh source PDF
     * @param cells authored logical cells
     * @param heights physical row heights in source points
     * @param ruled whether explicit cell borders are drawn
     * @throws Exception if PDF generation fails
     */
    private static void render(final Path output, final List<Cell> cells, final double[] heights, final boolean ruled)
            throws Exception {
        try (final var document = new PDDocument()) {
            final var page = new PDPage();
            document.addPage(page);
            try (final var stream = new PDPageContentStream(document, page)) {
                final var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                for (final var cell : cells) {
                    double top = 672;
                    for (int r = 0; r < cell.row(); r++) {
                        top -= heights[r];
                    }
                    double height = 0;
                    for (int r = cell.row(); r < cell.row() + cell.rowSpan(); r++) {
                        height += heights[r];
                    }
                    final float left = 60 + cell.column() * 123;
                    if (ruled) {
                        stream.addRect(left, (float) (top - height), cell.columnSpan() * 123, (float) height);
                        stream.stroke();
                    }
                    for (int i = 0; i < cell.lines().size(); i++) {
                        stream.beginText();
                        stream.setFont(font, 10);
                        stream.newLineAtOffset(left + 8, (float) (top - 14 - i * 12));
                        stream.showText(cell.lines().get(i));
                        stream.endText();
                    }
                }
            }
            document.save(output.toFile());
        }
    }

    /**
     * Scores full logical topology, retaining missing tables and extra anchors as failures.
     * @param references directory generated before extraction
     * @param captures unchanged native-first captures
     * @param output fresh numeric report
     * @throws Exception if references, source identities or capture shape are invalid
     */
    private static void score(final Path references, final Path captures, final Path output) throws Exception {
        final var mapper = JsonMapper.builder().build();
        final var reports = new ArrayList<Map<String, Object>>();
        for (final var reference : mapper.readTree(Files.readAllBytes(references.resolve("references.json")))) {
            final String id = reference.get("id").asString();
            final var source = mapper.readTree(Files.readAllBytes(captures.resolve(id).resolve("source.json")));
            if (!reference.get("pdf_sha256").asString().equals(source.get("pdf_sha256").asString())) {
                throw new IllegalArgumentException("Captured PDF identity mismatch");
            }
            final var capture = mapper.readTree(Files.readAllBytes(captures.resolve(id).resolve("native-first.json")));
            final Set<String> expected = new HashSet<>();
            reference.get("anchors").forEach(node -> expected.add(node.asString()));
            final Set<String> expectedContent = new HashSet<>();
            reference.get("content_keys").forEach(node -> expectedContent.add(node.asString()));
            final Set<String> actualContent = new HashSet<>();
            final Set<String> actual = new HashSet<>();
            final var tables = capture.get("tables");
            // Each authored one-page source contains exactly one table; multiple detections are not cherry-picked.
            if (capture.get("outcome").asString().equals("SUCCESS") && tables.size() == 1) {
                actual.addAll(topology(tables.get(0).get("text_as_html").asString()));
                actualContent.addAll(content(tables.get(0).get("text_as_html").asString()));
            }
            final long matched = expected.stream().filter(actual::contains).count();
            final int headerRows = reference.get("header_rows").asInt();
            final var expectedHeader = expected.stream()
                                               .filter(key -> Integer.parseInt(key.split(":")[0]) < headerRows)
                                               .collect(java.util.stream.Collectors.toSet());
            final var actualHeader = actual.stream()
                                             .filter(key -> Integer.parseInt(key.split(":")[0]) < headerRows)
                                             .collect(java.util.stream.Collectors.toSet());
            final var report = new LinkedHashMap<String, Object>();
            report.put("id", id);
            report.put("outcome", capture.get("outcome").asString());
            report.put("detected_tables", tables.size());
            report.put("expected_anchors", expected.size());
            report.put("actual_anchors", actual.size());
            report.put("matched_anchors", matched);
            report.put("expected_header_anchors", expectedHeader.size());
            report.put("matched_header_anchors", expectedHeader.stream().filter(actualHeader::contains).count());
            report.put("exact_header_topology", tables.size() == 1 && expectedHeader.equals(actualHeader));
            report.put("exact_topology", tables.size() == 1 && expected.equals(actual));
            report.put("matched_content_anchors", expectedContent.stream().filter(actualContent::contains).count());
            report.put("exact_content_and_topology", tables.size() == 1 && expectedContent.equals(actualContent));
            reports.add(report);
        }
        Files.write(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(reports),
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Converts emitted HTML spans to logical keys without consulting text or normalizing row indices.
     * @param html emitted table HTML
     * @return every occupied anchor position and its exact span
     */
    private static Set<String> topology(final String html) {
        return parse(html, false);
    }

    /**
     * Includes owned synthetic text to detect line splitting that geometry-only keys can hide.
     * @param html emitted table
     * @return exact anchor keys with ASCII whitespace-normalized content
     */
    private static Set<String> content(final String html) {
        return parse(html, true);
    }

    /**
     * Parses bounded emitted spans in logical order.
     * @param html emitted table
     * @param withContent whether to include normalized cell text
     * @return unshifted logical anchor keys
     */
    private static Set<String> parse(final String html, final boolean withContent) {
        final var keys = new HashSet<String>();
        final var occupied = new HashSet<String>();
        int row = 0;
        for (final var tr : Jsoup.parse(html).select("tr")) {
            int col = 0;
            for (final var td : tr.children()) {
                if (!td.tagName().equals("td") && !td.tagName().equals("th")) {
                    continue;
                }
                while (occupied.contains(row + ":" + col)) {
                    col++;
                }
                final int rs = td.hasAttr("rowspan") ? Integer.parseInt(td.attr("rowspan")) : 1;
                final int cs = td.hasAttr("colspan") ? Integer.parseInt(td.attr("colspan")) : 1;
                if (rs < 1 || cs < 1 || rs > 1024 || cs > 1024 || (long) rs * cs > 1024 || keys.size() >= 1024
                        || occupied.size() > 4096) {
                    throw new IllegalArgumentException("Invalid emitted span");
                }
                keys.add(row + ":" + col + ":" + rs + ":" + cs
                         + (withContent ? ":" + td.wholeText().replaceAll("[ \t\r\n\f]+", " ").trim() : ""));
                for (int r = row; r < row + rs; r++) {
                    for (int c = col; c < col + cs; c++) {
                        if (!occupied.add(r + ":" + c)) {
                            throw new IllegalArgumentException("Overlapping emitted cells");
                        }
                    }
                }
                col += cs;
            }
            row++;
        }
        return keys;
    }

    /**
     * Serializes authored logical indices without source text.
     * @param cell independently authored cell
     * @return exact row, column and span key
     */
    private static String key(final Cell cell) {
        return cell.row() + ":" + cell.column() + ":" + cell.rowSpan() + ":" + cell.columnSpan();
    }

    /**
     * Pins generated original bytes before extraction.
     * @param bytes generated PDF
     * @return SHA256 identity
     * @throws Exception if SHA256 is unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Checks authored reference serialization and span/text ownership parsing together.
     * @throws Exception if a generated reference is incomplete or temporary files cannot be managed
     */
    private static void controls() throws Exception {
        if (cells("wrapped").size() != 28 || cells("stacked").size() != 32 || cells("grouped").size() != 29
                || !content("<table><tr><td>A<br>B</td></tr></table>").equals(Set.of("0:0:1:1:A B"))
                || !topology("<table><tr><td rowspan='2'>A</td><td>B</td></tr><tr><td>C</td></tr></table>")
                        .equals(Set.of("0:0:2:1", "0:1:1:1", "1:1:1:1"))) {
            throw new IllegalStateException("Independent topology controls failed");
        }
        final var temporary = Files.createTempDirectory("docweave-header-controls-");
        try {
            final var generated = temporary.resolve("sources");
            generate(generated);
            final var reference =
                    JsonMapper.builder().build().readTree(Files.readAllBytes(generated.resolve("references.json")));
            if (reference.size() != 8 || reference.get(0).get("content_keys").size() != 28
                    || !reference.get(0)
                            .get("content_keys")
                            .get(0)
                            .asString()
                            .equals("0:0:1:1:Annual revenue Region 1")) {
                throw new IllegalStateException("Generated content reference is incomplete");
            }
        } finally {
            try (final var files = Files.walk(temporary)) {
                for (final var file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.delete(file);
                }
            }
        }
    }
}
