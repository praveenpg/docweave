package dev.docweave.partition;

import org.apache.pdfbox.Loader;
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

/** Offline hypothesis probe for geometric row-wrap cues; never changes production tables or reads reference strings. */
public final class ProbeHeaderWrapGeometry {
    /** Prevents utility construction. */
    private ProbeHeaderWrapGeometry() {
    }

    /**
     * Native single-band metrics without retained source strings.
     * @param glyphs nonblank source positions
     * @param left smallest source x
     * @param right largest source x
     * @param baseline median baseline
     * @param baselineSpread greatest minus least baseline
     * @param height median native glyph height
     */
    private record
            Profile(int glyphs, double left, double right, double baseline, double baselineSpread, double height) {}

    /**
     * Probes every unchanged model-row boundary with one fixed rule and source geometry only.
     * @param args pinned original PDF, prior numeric row audit, NEW output; or --self-test
     * @throws Exception if source identity, geometry, work, cancellation or fresh-output checks fail
     */
    public static void main(final String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--self-test")) {
            controls();
            return;
        }
        if (args.length != 3) {
            throw new IllegalArgumentException("Expected original PDF, numeric model-band audit, NEW report");
        }
        final byte[] pdf = bounded(Path.of(args[0]));
        final byte[] audit = bounded(Path.of(args[1]));
        final var mapper = JsonMapper.builder().build();
        final var prior = mapper.readTree(audit);
        if (!hash(pdf).equals(prior.get("pdf_sha256").asString())) {
            throw new IllegalArgumentException("Original PDF identity mismatch");
        }
        final var work = new PdfTableWork();
        final var reports = new ArrayList<Map<String, Object>>();
        try (final var document = Loader.loadPDF(pdf)) {
            final int pageNumber = prior.get("source_page").asInt();
            final var page = document.getPage(pageNumber - 1);
            // This research probe uses raw upright baselines; reject other frames instead of applying a second turn.
            if (page.getRotation() != 0 || page.getUserUnit() != 1 || page.getCropBox().getLowerLeftX() != 0
                    || page.getCropBox().getLowerLeftY() != 0) {
                throw new IllegalArgumentException("Probe requires upright unit-scaled zero-origin pages");
            }
            final var options = PdfOptions.defaults();
            final var geometry = new PdfPageGeometry(page);
            final var graphics = new PdfPageGraphics(page, options, new PdfImageBudget(options), geometry);
            graphics.processPage(page);
            final var text = new PdfPageText(options);
            text.setStartPage(pageNumber);
            text.setEndPage(pageNumber);
            text.getText(document);
            final var profiles = new ArrayList<Profile>();
            final var indices = new ArrayList<Integer>();
            double previousBottom = Double.NEGATIVE_INFINITY;
            int previousIndex = -1;
            for (final var band : prior.get("model_bands")) {
                final double top = band.get("top").asDouble();
                final double bottom = band.get("bottom").asDouble();
                final int index = band.get("row").asInt();
                if (!Double.isFinite(top) || !Double.isFinite(bottom) || top >= bottom || top < previousBottom
                        || index <= previousIndex) {
                    throw new IllegalArgumentException("Invalid original model bands");
                }
                final var members = new ArrayList<PdfPageText.Glyph>();
                for (final var glyph : text.glyphs()) {
                    work.step();
                    if (glyph.direction() != 0) {
                        throw new IllegalArgumentException("Probe requires upright native text");
                    }
                    final var center = geometry.center(glyph);
                    if (!glyph.text().isBlank() && center.y >= top && center.y < bottom) {
                        members.add(glyph);
                    }
                }
                profiles.add(profile(members));
                indices.add(index);
                previousBottom = bottom;
                previousIndex = index;
            }
            for (int index = 1; index < profiles.size(); index++) {
                work.step();
                final var upper = profiles.get(index - 1);
                final var lower = profiles.get(index);
                boolean rule = false;
                for (final var segment : graphics.segments()) {
                    work.step();
                    rule |= Math.abs(segment.y1() - segment.y2()) <= 0.01 && segment.y1() > upper.baseline()
                            && segment.y1() < lower.baseline()
                            && Math.max(segment.x1(), segment.x2()) > Math.max(upper.left(), lower.left())
                            && Math.min(segment.x1(), segment.x2()) < Math.min(upper.right(), lower.right());
                }
                final var report = new LinkedHashMap<String, Object>();
                report.put("upper_model_row", indices.get(index - 1));
                report.put("lower_model_row", indices.get(index));
                report.put("upper", upper);
                report.put("lower", lower);
                report.put("native_horizontal_rule_between_baselines", rule);
                report.put("eligible_under_hypothesis",
                        indices.get(index) == indices.get(index - 1) + 1 && eligible(upper, lower, rule));
                reports.add(report);
            }
        }
        Files.write(Path.of(args[2]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(
                        Map.of("pdf_sha256", hash(pdf), "numeric_audit_sha256", hash(audit), "source_page",
                                prior.get("source_page").asInt(), "boundaries", reports)),
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Tests a fixed nonsemantic hypothesis, not a calibrated classifier or production merge policy.
     * @param upper original upper-band metrics
     * @param lower original lower-band metrics
     * @param rule true when a source rule separates the baselines
     * @return eligibility shared by both wrapped and legitimate stacked rows
     */
    private static boolean eligible(final Profile upper, final Profile lower, final boolean rule) {
        final double height = Math.max(upper.height(), lower.height());
        final double width = Math.min(upper.right() - upper.left(), lower.right() - lower.left());
        return !rule && upper.glyphs() > 0 && lower.glyphs() > 0 && height > 0 && width > 0
                && upper.baselineSpread() <= 2 && lower.baselineSpread() <= 2
                && Math.min(upper.height(), lower.height()) >= 0.9 * height && lower.baseline() > upper.baseline()
                && lower.baseline() - upper.baseline() <= 2 * height
                && Math.min(upper.right(), lower.right()) - Math.max(upper.left(), lower.left()) >= 0.8 * width;
    }

    /**
     * Summarizes source positions geometrically without retaining decoded text.
     * @param glyphs bounded nonblank positions from one unchanged model band
     * @return finite summary, including an explicit empty-band summary
     */
    private static Profile profile(final List<PdfPageText.Glyph> glyphs) {
        if (glyphs.isEmpty()) {
            return new Profile(0, 0, 0, 0, 0, 0);
        }
        final var baselines = glyphs.stream().mapToDouble(PdfPageText.Glyph::y).sorted().toArray();
        final var heights = glyphs.stream().mapToDouble(PdfPageText.Glyph::height).sorted().toArray();
        return new Profile(glyphs.size(), glyphs.stream().mapToDouble(PdfPageText.Glyph::x).min().orElseThrow(),
                glyphs.stream().mapToDouble(glyph -> glyph.x() + glyph.width()).max().orElseThrow(),
                baselines[baselines.length / 2], baselines[baselines.length - 1] - baselines[0],
                heights[heights.length / 2]);
    }

    /**
     * Reads one bounded original input, without downloading anything.
     * @param path explicit local source
     * @return unchanged input bytes
     * @throws Exception if missing or larger than16MiB
     */
    private static byte[] bounded(final Path path) throws Exception {
        if (Files.size(path) > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("Probe input exceeds16MiB");
        }
        return Files.readAllBytes(path);
    }

    /**
     * Computes source byte identity without exporting source strings.
     * @param bytes original input
     * @return lowercase SHA256
     * @throws Exception if the required digest is unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /** Checks geometric eligibility and its semantic ambiguity without model/source dependencies. */
    private static void controls() {
        final var upper = new Profile(10, 10, 100, 20, 0, 8);
        final var continuation = new Profile(10, 10, 100, 32, 0, 8);
        // Legitimate stacked labels can have exactly these metrics: geometric agreement alone cannot certify wrapping.
        final var distinctSubheader = new Profile(10, 10, 100, 32, 0, 8);
        if (!eligible(upper, continuation, false) || !eligible(upper, distinctSubheader, false)
                || eligible(upper, continuation, true) || eligible(upper, new Profile(0, 0, 0, 0, 0, 0), false)
                || eligible(upper, new Profile(10, 10, 100, 50, 0, 8), false)) {
            throw new IllegalStateException("Geometry hypothesis or veto controls regressed");
        }
    }
}
