package io.docweave.partition;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.state.Concatenate;
import org.apache.pdfbox.contentstream.operator.state.Restore;
import org.apache.pdfbox.contentstream.operator.state.Save;
import org.apache.pdfbox.contentstream.operator.state.SetGraphicsStateParameters;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDFormContentStream;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Bounded source-graph diagnosis of XObject image invocation roles; not a renderer or visibility oracle. */
public final class AuditPdfMaskImages {
    /** Prevents diagnostic tool construction. */
    private AuditPdfMaskImages() {
    }

    /**
     * Audits the complete pinned source after owned role, resource and cancellation controls.
     * @param args source PDF, source-bearing reference JSON, original extraction and fresh numeric report
     * @throws Exception if source identities, controls, budgets or parsing fail
     */
    public static void main(final String[] args) throws Exception {
        require(args.length == 4 && !Files.exists(Path.of(args[3])),
                "Expected source, reference, extraction and fresh report");
        controls();
        final var mapper = JsonMapper.builder().build();
        final byte[] referenceBytes = Files.readAllBytes(Path.of(args[1]));
        final byte[] extractionBytes = Files.readAllBytes(Path.of(args[2]));
        final var extraction = mapper.readTree(extractionBytes);
        final var reference = mapper.readTree(referenceBytes).get("source");
        final var path = Path.of(args[0]);
        require(Files.size(path) <= 16 * 1024 * 1024, "Source exceeds bounded audit input");
        final byte[] bytes = Files.readAllBytes(path);
        final String sourceHash = hash(bytes);
        require(bytes.length == reference.get("bytes").asLong()
                        && sourceHash.equals(reference.get("sha256").asString()),
                "Source identity changed");
        require(sourceHash.equals(extraction.get("source_sha256").asString()), "Extraction source changed");
        try (final var document = Loader.loadPDF(bytes)) {
            final var audit = new Visitor(500000);
            audit.read(document);
            final var correspondence = pageCorrespondence(audit.rows, extraction);
            Files.writeString(Path.of(args[3]),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("source_sha256", sourceHash,
                            "reference_sha256", hash(referenceBytes), "java_runtime",
                            System.getProperty("java.runtime.version"), "pages", document.getNumberOfPages(),
                            "role_counts", counts(audit.rows), "rows", audit.rows, "page_correspondence",
                            correspondence, "extraction_sha256", hash(extractionBytes), "scope",
                            "Invoked image XObjects; PAGE versus explicitly activated soft-mask groups. No inline images, mask pixel comparison, complete visibility, blend/composite interpretation or extraction accuracy claim."))
                            + "\n",
                    StandardOpenOption.CREATE_NEW);
        }
    }

    /**
     * Checks complete direct-source/retained-output correspondence without treating auxiliary masks as page images.
     * This shares PDFBox source geometry and is diagnostic; Cairo evidence separately checks affine discrepancies.
     * @param rows complete graph invocation population
     * @param extraction complete previously captured original result
     * @return exact population counts and match count under a fixed floating-point-only tolerance
     */
    private static Map<String, Integer> pageCorrespondence(
            final List<Map<String, Object>> rows, final JsonNode extraction) {
        final var supported = new ArrayList<JsonNode>();
        extraction.get("results").forEach(result -> {
            if (result.get("profile").asString().equals("ORIENTED_LINES")) {
                supported.add(result);
            }
        });
        require(supported.size() == 1 && supported.getFirst().get("outcome").asString().equals("SUCCESS"),
                "Expected one complete supported source capture");
        final var images = supported.getFirst().get("images");
        final var pageRows = rows.stream().filter(row -> row.get("role").equals("PAGE")).toList();
        require(images.size() == pageRows.size(), "Direct source/output populations differ");
        final var used = new java.util.HashSet<Integer>();
        for (final var row : pageRows) {
            boolean matched = false;
            for (int index = 0; index < images.size(); index++) {
                final var image = images.get(index);
                if (used.contains(index) || !row.get("page").equals(image.get("page").asInt())) {
                    continue;
                }
                final var expected = (List<?>) row.get("bounds");
                boolean near = image.get("bounds").size() == 4;
                for (int edge = 0; edge < 4 && near; edge++) {
                    final double value = image.get("bounds").get(edge).asDouble();
                    near = Double.isFinite(value)
                           && Math.abs(((Number) expected.get(edge)).doubleValue() - value) <= 0.001;
                }
                if (near) {
                    used.add(index);
                    matched = true;
                    break;
                }
            }
            require(matched, "Unmatched direct-source invocation");
        }
        return Map.of(
                "direct_source_invocations", pageRows.size(), "emitted_images", images.size(), "matched", used.size());
    }

    /**
     * Authors two direct occurrences, one activated mask occurrence and one unused resource.
     * Rechecks operator bounds, interruption and fresh traversal after cancellation without reference inference.
     * @throws Exception if independent source authoring or a role/control invariant fails
     */
    private static void controls() throws Exception {
        final byte[] bytes;
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(200, 200));
            page.setResources(new PDResources());
            document.addPage(page);
            final var base =
                    LosslessFactory.createFromImage(document, new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB));
            final var maskImage =
                    LosslessFactory.createFromImage(document, new BufferedImage(4, 4, BufferedImage.TYPE_BYTE_GRAY));
            page.getResources().add(
                    LosslessFactory.createFromImage(document, new BufferedImage(3, 3, BufferedImage.TYPE_INT_RGB)));
            final var group = new PDTransparencyGroup(document);
            group.setBBox(new PDRectangle(200, 200));
            group.setResources(new PDResources());
            final var attributes = new COSDictionary();
            attributes.setItem(COSName.S, COSName.TRANSPARENCY);
            group.getCOSObject().setItem(COSName.GROUP, attributes);
            try (final var content = new PDFormContentStream(group)) {
                content.drawImage(maskImage, 0, 0, 200, 200);
            }
            final var mask = new COSDictionary();
            mask.setItem(COSName.S, COSName.LUMINOSITY);
            mask.setItem(COSName.G, group);
            final var state = new PDExtendedGraphicsState();
            state.getCOSObject().setItem(COSName.SMASK, mask);
            try (final var content = new PDPageContentStream(document, page)) {
                content.saveGraphicsState();
                content.setGraphicsStateParameters(state);
                content.drawImage(base, 20, 30, 40, 50);
                content.restoreGraphicsState();
                content.drawImage(base, 100, 110, 40, 50);
            }
            document.save(output);
            bytes = output.toByteArray();
        }
        try (final var document = Loader.loadPDF(bytes)) {
            final var audit = new Visitor(500000);
            audit.read(document);
            require(counts(audit.rows).equals(Map.of("PAGE", 2L, "SOFT_MASK", 1L)), "Owned invocation roles changed");
            require(audit.rows.stream().noneMatch(row -> row.get("width").equals(3)), "Unused resource counted");
            boolean bounded = false;
            try {
                new Visitor(1).read(document);
            } catch (final IOException expected) {
                bounded = expected.getMessage().equals("Audit operator budget exceeded");
            }
            require(bounded, "Operator budget ignored");
            Thread.currentThread().interrupt();
            try {
                boolean interrupted = false;
                try {
                    new Visitor(500000).read(document);
                } catch (final InterruptedIOException expected) {
                    interrupted = true;
                }
                require(interrupted, "Cancellation ignored");
            } finally {
                Thread.interrupted();
            }
            final var fresh = new Visitor(500000);
            fresh.read(document);
            require(fresh.rows.equals(audit.rows), "Traversal changed after cancelled control");
        }
    }

    /**
     * Counts the complete observed role population without dropping any record.
     * @param rows all bounded source invocations
     * @return exact page/mask invocation counts
     */
    private static Map<Object, Long> counts(final List<Map<String, Object>> rows) {
        return rows.stream().collect(Collectors.groupingBy(row -> row.get("role"), Collectors.counting()));
    }

    /** Source-only graphics state traversal; no image decode or source element emission occurs. */
    private static final class Visitor extends PDFStreamEngine {
        /** All source records, bounded before each addition. */
        private final List<Map<String, Object>> rows = new ArrayList<>();
        /** Active mask groups detect cycles independently of object-number availability. */
        private final Set<COSBase> masks = Collections.newSetFromMap(new IdentityHashMap<>());
        /** Fixed per-page operator budget, separately reduced only by an owned limit control. */
        private final int maximumOperators;
        /** Active auxiliary mask depth. */
        private int depth;
        /** Visited content operators in the current original page and its activated masks/forms. */
        private int operators;
        /** One-based original source page. */
        private int pageNumber;
        /** Shared source-coordinate convention, without physical-unit conversion. */
        private PdfPageGeometry geometry;

        /**
         * Registers only operators needed for XObject invocation scope and transformation provenance.
         * @param maximumOperators positive per-page invocation budget
         */
        private Visitor(final int maximumOperators) {
            this.maximumOperators = maximumOperators;
            addOperator(new org.apache.pdfbox.contentstream.operator.DrawObject(this));
            addOperator(new Concatenate(this));
            addOperator(new Save(this));
            addOperator(new Restore(this));
            addOperator(new SetGraphicsStateParameters(this));
        }

        /**
         * Traverses every page under an explicit complete-document cap.
         * @param document borrowed caller-owned source
         * @throws IOException if parsing, cancellation or bounded traversal fails
         */
        private void read(final PDDocument document) throws IOException {
            require(document.getNumberOfPages() <= 128, "Audit exceeds page cap");
            for (final var page : document.getPages()) {
                pageNumber++;
                operators = 0;
                geometry = new PdfPageGeometry(page);
                processPage(page);
            }
        }

        /**
         * Records actual image Do invocations and separately traverses explicitly activated soft-mask groups.
         * Mask traversal is diagnostic only; its content does not become page-image output.
         * @param operator original source operator
         * @param operands original operands
         * @throws IOException if unsupported inline images, budgets, cyclic masks or parsing prevent completeness
         */
        @Override
        protected void processOperator(final Operator operator, final List<COSBase> operands) throws IOException {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Audit cancelled");
            }
            if (++operators > maximumOperators) {
                throw new IOException("Audit operator budget exceeded");
            }
            if (operator.getName().equals("BI")) {
                throw new IOException("Inline images require a separate audit");
            }
            if (operator.getName().equals("Do") && operands.size() == 1 && operands.getFirst() instanceof COSName name
                    && getResources().isImageXObject(name)) {
                record
                ((PDImageXObject) getResources().getXObject(name));
            }
            super.processOperator(operator, operands);
            if (operator.getName().equals("gs") && operands.size() == 1
                    && operands.getFirst() instanceof COSName name) {
                final var state = getResources().getExtGState(name);
                if (state != null && state.getCOSObject().containsKey(COSName.SMASK)) {
                    final var mask = getGraphicsState().getSoftMask();
                    final var group = mask == null ? null : mask.getGroup();
                    if (group != null) {
                        if (depth >= 16 || !masks.add(group.getCOSObject())) {
                            throw new IOException("Audit mask recursion exceeded");
                        }
                        depth++;
                        try {
                            processSoftMask(group);
                        } finally {
                            depth--;
                            masks.remove(group.getCOSObject());
                        }
                    }
                }
            }
        }

        /**
         * Copies numerical occurrence provenance without decoding source pixels.
         * @param image invoked source image XObject
         * @throws IOException if the complete image population exceeds its fixed cap
         */
        private void record(final PDImageXObject image) throws IOException {
            if (rows.size() >= 1024) {
                throw new IOException("Audit image cap exceeded");
            }
            final var matrix = getGraphicsState().getCurrentTransformationMatrix();
            final var points = List.of(matrix.transformPoint(0, 0), matrix.transformPoint(1, 0),
                                           matrix.transformPoint(0, 1), matrix.transformPoint(1, 1))
                                       .stream()
                                       .map(point -> geometry.point(point.x, point.y))
                                       .toList();
            rows.add(Map.of("page", pageNumber, "role", depth == 0 ? "PAGE" : "SOFT_MASK", "object",
                    String.valueOf(image.getCOSObject().getKey()), "width", image.getWidth(), "height",
                    image.getHeight(), "bounds",
                    List.of(points.stream().mapToDouble(point -> point.getX()).min().orElseThrow(),
                            points.stream().mapToDouble(point -> point.getY()).min().orElseThrow(),
                            points.stream().mapToDouble(point -> point.getX()).max().orElseThrow(),
                            points.stream().mapToDouble(point -> point.getY()).max().orElseThrow())));
        }

        /**
         * Prevents PDFBox recovery from turning an incomplete audit into a successful report.
         * @param operator failing source operator
         * @param operands original operands
         * @param failure parsing or policy failure
         * @throws IOException always, preserving the original failure
         */
        @Override
        protected void operatorException(
                final Operator operator, final List<COSBase> operands, final IOException failure) throws IOException {
            throw failure;
        }
    }

    /**
     * Computes a complete source/reference identity without content repair.
     * @param bytes exact input
     * @return lowercase SHA256
     * @throws Exception if the standard digest is unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Stops invalid source/control evidence instead of changing its population.
     * @param condition required invariant
     * @param message bounded diagnostic text
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
