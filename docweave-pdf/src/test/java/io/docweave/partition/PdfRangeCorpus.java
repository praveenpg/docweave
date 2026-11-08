package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.core.Element;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Deterministic synthetic PDFs and partitioner configurations shared by the whole-document and page-range
 * equivalence tests. Every document is authored in memory; nothing is read from disk.
 */
final class PdfRangeCorpus {
    /** Utility class. */
    private PdfRangeCorpus() {
    }

    /**
     * One document under one configuration.
     * @param name stable case name, also the golden file name
     * @param partitioner fresh configured strategy
     * @param pdf authored document bytes
     * @param filename source filename
     */
    record Case(String name, Supplier<PdfPartitioner> partitioner, byte[] pdf, String filename) {
        /**
         * Partitions the whole document through the public facade.
         * @return result
         * @throws IOException if partitioning fails
         */
        PartitionResult partition() throws IOException {
            return DocWeave.create(PartitionerRegistry.of(List.of(partitioner.get())))
                    .partition(DocumentSource.bytes(pdf, filename));
        }
    }

    /**
     * Returns every equivalence case.
     * @return ordered cases
     * @throws IOException if authoring fails
     */
    static List<Case> cases() throws IOException {
        final var text = textDocument();
        final var tables = tableDocument();
        final var images = imageDocument();
        final var mixed = mixedDocument();
        final var continuation =
                PdfOptions.defaults().withTableContinuation(PdfOptions.TableContinuation.REPEATED_HEADER);
        final var ocr = new PdfOcrOptions(72, 10_000_000, 20_000, 1_000_000)
                                .withReadingOrder(PdfOcrOptions.ReadingOrder.GEOMETRIC);
        return List.of(new Case("text-default", () -> PdfPartitioner.create(PdfOptions.defaults()), text, "text.pdf"),
                new Case("text-paragraphs",
                        ()
                                -> PdfPartitioner.create(
                                        PdfOptions.defaults().withTextLayout(PdfOptions.TextLayout.PARAGRAPHS)),
                        text, "text.pdf"),
                new Case("tables-default", () -> PdfPartitioner.create(PdfOptions.defaults()), tables, "tables.pdf"),
                new Case("tables-continuation", () -> PdfPartitioner.create(continuation), tables, "tables.pdf"),
                new Case("tables-continuation-cells",
                        ()
                                -> PdfPartitioner.create(continuation.withTableCellCoordinates(true).withTextLayout(
                                        PdfOptions.TextLayout.COLUMN_PARAGRAPHS)),
                        tables, null),
                new Case("images-bytes",
                        ()
                                -> PdfPartitioner.create(withImages(PdfOptions.defaults(), 10_000_000)),
                        images, "images.pdf"),
                new Case("mixed-continuation-geometric",
                        ()
                                -> PdfPartitioner.create(withImages(continuation, 10_000_000),
                                        ocr.withSelection(PdfOcrOptions.Selection.TEXTLESS_PAGES), provider()),
                        mixed, "mixed.pdf"),
                new Case("mixed-ocr-only",
                        ()
                                -> PdfPartitioner.create(continuation,
                                        ocr.withReadingOrder(PdfOcrOptions.ReadingOrder.SOURCE_ORDER)
                                                .withSelection(PdfOcrOptions.Selection.ALL_PAGES),
                                        provider()),
                        mixed, "mixed.pdf"));
    }

    /**
     * Copies options with source image bytes and an explicit cumulative image-byte budget.
     * @param base options to copy
     * @param maxImageBytes cumulative encoded PNG budget
     * @return copied options
     */
    static PdfOptions withImages(final PdfOptions base, final int maxImageBytes) {
        return new PdfOptions(base.maxPages(), base.maxGlyphsPerPage(), base.maxOperatorsPerPage(),
                base.maxImagePixels(), maxImageBytes, true, base.tableLimits(), base.tableDetection(),
                base.textLayout(), base.includeTableCellCoordinates(), base.tableContinuation(),
                base.unicodeNormalization(), base.textRendering(), base.figureCaptions(), base.includeImageClipBounds(),
                base.includeImageGroupContext(), base.curvedClipEnvelopes(), base.inlineScripts(), base.sideStreams());
    }

    /**
     * A deterministic recognizer: the rendered width identifies the page, so output does not depend on call order.
     * @return thread-safe provider
     */
    static PdfOcrProvider provider() {
        return image -> {
            final int marker = image.getWidth() % 7;
            final var lines = new ArrayList<PdfOcrProvider.Line>();
            for (int index = 0; index <= marker; index++) {
                final double top = 20 + (index * 37 + marker * 11) % (image.getHeight() - 40);
                final double left = 10 + (index * 53) % (image.getWidth() / 2);
                lines.add(new PdfOcrProvider.Line(
                        "Scanned w" + image.getWidth() + " line " + index + " é𝄞", left, top, left + 80, top + 12));
            }
            return lines;
        };
    }

    /**
     * Native text pages, including blank and quarter-turned pages and Unicode.
     * @return PDF bytes
     * @throws IOException if authoring fails
     */
    static byte[] textDocument() throws IOException {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            for (int index = 0; index < 13; index++) {
                final var page = new PDPage(new PDRectangle(400, 500));
                document.addPage(page);
                if (index == 4 || index == 9) {
                    continue;
                }
                if (index == 6) {
                    page.setRotation(90);
                }
                try (final var content = new PDPageContentStream(document, page)) {
                    text(content, "Running header", 30, 480, 9);
                    for (int line = 0; line < 3 + index % 4; line++) {
                        text(content, "Page " + (index + 1) + " paragraph line " + line + " with body text", 40,
                                420 - line * 14, 11);
                    }
                    text(content, "Second block on page " + (index + 1), 40, 250, 11);
                    text(content, "Footer " + (index + 1), 30, 15, 8);
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    /**
     * Ruled tables: a long chain, a broken chain, multi-table, narrative and blank pages.
     * @return PDF bytes
     * @throws IOException if authoring fails
     */
    static byte[] tableDocument() throws IOException {
        final var specs = new ArrayList<Spec>();
        specs.add(spec(400, 760, "plain"));
        for (int index = 0; index < 8; index++) {
            specs.add(spec(40, 760, index == 3 ? "mergedBody" : "plain"));
        }
        specs.add(spec(40, 400, "plain"));
        specs.add(new Spec(400, 760, List.of("Product", "Count"), 220, "plain"));
        specs.add(new Spec(40, 400, List.of("Product", "Count"), 220, "plain"));
        specs.add(spec(400, 760, "multiple"));
        specs.add(spec(40, 400, "plain"));
        specs.add(spec(0, 0, "blank"));
        specs.add(spec(400, 760, "after"));
        specs.add(spec(40, 760, "plain"));
        specs.add(spec(40, 760, "plain"));
        return tables(specs, false);
    }

    /**
     * Native images with and without text, plus an image-only page that produces a page warning.
     * @return PDF bytes
     * @throws IOException if authoring fails
     */
    static byte[] imageDocument() throws IOException {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            for (int index = 0; index < 9; index++) {
                final var page = new PDPage(new PDRectangle(300, 400));
                document.addPage(page);
                if (index == 5) {
                    page.setRotation(180);
                }
                try (final var content = new PDPageContentStream(document, page)) {
                    if (index % 3 != 2) {
                        text(content, "Figure page " + (index + 1), 20, 370, 12);
                    }
                    if (index != 7) {
                        image(document, content, index, 30, 100, 40 + index * 3, 30 + index * 2);
                    }
                    if (index == 4) {
                        image(document, content, 40, 150, 250, 20, 20);
                    }
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    /**
     * Native table chains interleaved with image-only scanned pages of distinct widths.
     * @return PDF bytes
     * @throws IOException if authoring fails
     */
    static byte[] mixedDocument() throws IOException {
        final var specs = new ArrayList<Spec>();
        specs.add(spec(400, 760, "plain"));
        specs.add(spec(40, 760, "plain"));
        specs.add(spec(40, 400, "plain"));
        specs.add(spec(0, 0, "scan"));
        specs.add(spec(400, 760, "plain"));
        specs.add(spec(40, 400, "plain"));
        specs.add(spec(0, 0, "scan"));
        specs.add(spec(0, 0, "scan"));
        specs.add(spec(400, 760, "rotation"));
        specs.add(spec(40, 760, "plain"));
        specs.add(spec(0, 0, "blank"));
        return tables(specs, true);
    }

    /**
     * Repeated-header table geometry, matching the continuation tests.
     * @param top displayed table top
     * @param bottom displayed table bottom
     * @param feature page variation
     * @return spec
     */
    private static Spec spec(final int top, final int bottom, final String feature) {
        return new Spec(top, bottom, List.of("Item", "Qty"), 220, feature);
    }

    /**
     * Table page description.
     * @param top displayed table top
     * @param bottom displayed table bottom
     * @param header header labels
     * @param middle column split
     * @param feature variation
     */
    private record Spec(int top, int bottom, List<String> header, int middle, String feature) {}

    /**
     * Authors table pages.
     * @param specs ordered pages
     * @param widths whether scanned pages receive distinct widths
     * @return PDF bytes
     * @throws IOException if authoring fails
     */
    private static byte[] tables(final List<Spec> specs, final boolean widths) throws IOException {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            int index = 0;
            for (final var spec : specs) {
                final int marker = index++;
                final boolean scan = spec.feature().equals("scan");
                final var page = new PDPage(new PDRectangle(scan && widths ? 500 + marker : 500, 800));
                document.addPage(page);
                if (spec.feature().equals("blank")) {
                    continue;
                }
                if (spec.feature().equals("rotation")) {
                    page.setRotation(90);
                }
                try (final var content = new PDPageContentStream(document, page)) {
                    if (scan) {
                        image(document, content, marker, 0, 0, 500 + marker, 800);
                        continue;
                    }
                    drawTable(content, spec, 40, spec.middle(), 400);
                    if (spec.feature().equals("multiple")) {
                        drawTable(content, new Spec(420, 600, List.of("A", "B"), 450, "plain"), 420, 450, 480);
                    }
                    if (spec.feature().equals("after")) {
                        text(content, "End of section", 40, 15, 12);
                    }
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    /**
     * Draws a small deterministic RGB image.
     * @param document owner
     * @param content page stream
     * @param seed color seed
     * @param x left
     * @param y bottom
     * @param width displayed width
     * @param height displayed height
     * @throws IOException if drawing fails
     */
    private static void image(final PDDocument document, final PDPageContentStream content, final int seed,
            final float x, final float y, final float width, final float height) throws IOException {
        final var image = new BufferedImage(8 + seed % 5, 6 + seed % 3, BufferedImage.TYPE_INT_RGB);
        for (int px = 0; px < image.getWidth(); px++) {
            for (int py = 0; py < image.getHeight(); py++) {
                image.setRGB(px, py, new Color((seed * 40 + px * 20) % 256, (py * 30) % 256, 120).getRGB());
            }
        }
        content.drawImage(LosslessFactory.createFromImage(document, image), x, y, width, height);
    }

    /**
     * Draws one ruled two-column table with a repeated header.
     * @param content page stream
     * @param spec geometry
     * @param left left edge
     * @param middle column split
     * @param right right edge
     * @throws IOException if drawing fails
     */
    private static void drawTable(final PDPageContentStream content, final Spec spec, final int left, final int middle,
            final int right) throws IOException {
        final int top = 800 - spec.top();
        final int split = top - 40;
        final int bottom = 800 - spec.bottom();
        for (final int y : List.of(top, split, bottom)) {
            content.moveTo(left, y);
            content.lineTo(right, y);
        }
        for (final int x : List.of(left, middle, right)) {
            content.moveTo(x, spec.feature().equals("mergedBody") && x == middle ? split : bottom);
            content.lineTo(x, top);
        }
        content.stroke();
        final int size = right - left < 100 ? 8 : 12;
        text(content, spec.header().getFirst(), left + 5, top - 25, size);
        text(content, spec.header().getLast(), middle + 5, top - 25, size);
        text(content, "Alpha", left + 5, split - 25, right - left < 100 ? 6 : 12);
        text(content, "10", middle + 5, split - 25, size);
    }

    /**
     * Shows one Helvetica run.
     * @param content page stream
     * @param text visible text
     * @param x left
     * @param y baseline
     * @param size font size
     * @throws IOException if drawing fails
     */
    private static void text(final PDPageContentStream content, final String text, final float x, final float y,
            final float size) throws IOException {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), size);
        content.newLineAtOffset(x, y);
        content.showText(text);
        content.endText();
    }

    /**
     * Renders a result as stable, type-tagged text: one line per element and warning. Top-level metadata keeps
     * insertion order; nested maps are key-sorted because some are built with {@code Map.of}, whose iteration order
     * differs between JVM runs (the comparison within one JVM uses {@link #exact(PartitionResult)}).
     * @param result partition result
     * @return canonical text
     */
    static String canonical(final PartitionResult result) {
        final var out = new StringBuilder();
        for (final var element : result.elements()) {
            out.append(element.type()).append(' ').append(element.elementId()).append(' ');
            value(out, element.text(), true);
            out.append(' ');
            value(out, element.metadata(), false);
            out.append('\n');
        }
        for (final var warning : result.warnings()) {
            out.append("warning ").append(warning.code()).append(' ');
            value(out, warning.message(), true);
            out.append('\n');
        }
        return out.toString();
    }

    /**
     * Renders a result with every map in insertion order, for same-JVM comparisons.
     * @param result partition result
     * @return exact text
     */
    static String exact(final PartitionResult result) {
        return exact(result.elements(), result.warnings());
    }

    /**
     * Renders elements and warnings with every map in insertion order.
     * @param elements elements
     * @param warnings warnings
     * @return exact text
     */
    static String exact(final List<Element> elements, final List<PartitionResult.Warning> warnings) {
        final var out = new StringBuilder();
        for (final var element : elements) {
            out.append(element.type()).append(' ').append(element.elementId()).append(' ');
            value(out, element.text(), true);
            out.append(' ');
            exactValue(out, element.metadata());
            out.append('\n');
        }
        for (final var warning : warnings) {
            out.append("warning ").append(warning.code()).append(' ');
            value(out, warning.message(), true);
            out.append('\n');
        }
        return out.toString();
    }

    /**
     * Appends a type-tagged value, preserving every map's insertion order.
     * @param out destination
     * @param value value
     */
    private static void exactValue(final StringBuilder out, final Object value) {
        if (value instanceof final Map<?, ?> map) {
            out.append('{');
            map.forEach((key, nested) -> {
                out.append(key).append('=');
                exactValue(out, nested);
                out.append(';');
            });
            out.append('}');
        } else if (value instanceof final List<?> list) {
            out.append('[');
            list.forEach(nested -> {
                exactValue(out, nested);
                out.append(',');
            });
            out.append(']');
        } else {
            value(out, value, true);
        }
    }

    /**
     * Appends a type-tagged value.
     * @param out destination
     * @param value value
     * @param sort whether a map value is rendered in key order instead of insertion order
     */
    private static void value(final StringBuilder out, final Object value, final boolean sort) {
        switch (value) {
        case null -> out.append("null");
        case final String text -> {
            out.append('"');
            text.chars().forEach(ch -> {
                if (ch < 0x20 || ch > 0x7e || ch == '"' || ch == '\\') {
                    out.append(String.format("\\u%04x", ch));
                } else {
                    out.append((char) ch);
                }
            });
            out.append('"');
        }
        case final Integer number -> out.append("i").append(number);
        case final Long number -> out.append("l").append(number);
        case final Double number -> out.append("d").append(number);
        case final Float number -> out.append("f").append(number);
        case final BigDecimal number -> out.append("D").append(number);
        case final BigInteger number -> out.append("I").append(number);
        case final Boolean bool -> out.append(bool);
        case final List<?> list -> {
            out.append('[');
            list.forEach(nested -> {
                value(out, nested, true);
                out.append(',');
            });
            out.append(']');
        }
        case final Map<?, ?> map -> {
            out.append('{');
            final Map<?, ?> view = sort ? new TreeMap<>(map) : map;
            view.forEach((key, nested) -> {
                out.append(key).append('=');
                value(out, nested, true);
                out.append(';');
            });
            out.append('}');
        }
        default -> out.append("?").append(value.getClass().getName()).append(':').append(value);
        }
    }
}
