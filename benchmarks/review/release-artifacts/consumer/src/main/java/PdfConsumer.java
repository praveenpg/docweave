import dev.docweave.DocWeave;
import dev.docweave.core.ElementType;
import dev.docweave.partition.DocumentSource;
import dev.docweave.partition.PartitionResult;
import dev.docweave.partition.PartitionerRegistry;
import dev.docweave.partition.PdfOcrProvider;
import dev.docweave.partition.PdfOptions;
import dev.docweave.partition.PdfParagraphAssembler;
import dev.docweave.partition.PdfPartitioner;
import dev.docweave.partition.PdfStrategy;
import dev.docweave.partition.PdfTableLayoutProvider;
import dev.docweave.partition.PdfTaggedParagraphOptions;
import dev.docweave.partition.PdfWordOcrProvider;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/** Independent PDF-only classpath smoke check using an owned minimal PDF. */
public final class PdfConsumer {
    /** Prevents construction of the standalone consumer check. */
    private PdfConsumer() {
    }

    /**
     * Verifies discovery and native text extraction using only the PDF dependency.
     * @param args unused command-line arguments
     * @throws Exception if the owned PDF cannot be created or extracted
     */
    public static void main(final String[] args) throws Exception {
        final var source = DocumentSource.bytes(
                pdf("BT /F1 12 Tf 40 100 Td (DocWeave consumer smoke) Tj ET\n", 300, 200), "owned.pdf");
        final var result = DocWeave.create().partition(source);
        if (result.elements().stream().noneMatch(element -> element.text().contains("DocWeave consumer smoke"))) {
            throw new AssertionError("PDF discovery/text extraction failed");
        }
        verifyPresets(source, result);
        final var fragments = new PdfTaggedParagraphOptions(10, 1000, PdfTaggedParagraphOptions.WordWrap.PRESERVE)
                                      .withPageBoundary(PdfTaggedParagraphOptions.PageBoundary.PAGE_FRAGMENTS);
        final var tagged = PdfPartitioner.create(PdfOptions.defaults()).withTaggedParagraphs(fragments);
        final var fallback = DocWeave.create(PartitionerRegistry.of(List.of(tagged))).partition(source);
        if (!result.elements().equals(fallback.elements())
                || !PdfParagraphAssembler.create().assemble(fallback.elements()).equals(result.elements())) {
            throw new AssertionError("Untagged fragment/assembly fallback changed native content");
        }
        System.out.println("PDF-only ServiceLoader discovery and text extraction passed on " + Runtime.version());
        System.out.println("All four presets, full-page ownership and paragraph API linkage passed");
        verifySideStreamRowTies();
        System.out.println("Explicit side-stream row-tie selection passed");
    }

    /**
     * Selects each side-stream policy through packaged public APIs on an owned page whose smaller-type sidebar starts
     * on the row of one main line: the row-tie policy alone emits the sidebar block before that line, with the same
     * elements, and LINES output ignores the selection.
     * @throws Exception if writing, extraction or a check fails
     */
    private static void verifySideStreamRowTies() throws Exception {
        final var names = List.of("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
                "eleven", "twelve", "thirteen");
        final var content = new StringBuilder();
        for (int index = 0; index < names.size(); index++) {
            content.append("BT /F1 9 Tf 200 ")
                    .append(700 - index * 30)
                    .append(" Td (Main row ")
                    .append(names.get(index))
                    .append(") Tj ET\n");
        }
        for (int index = 0; index < 12; index++) {
            content.append("BT /F1 6 Tf 36 ")
                    .append(639 - index * 9)
                    .append(" Td (Sidebar note line ")
                    .append((char) ('a' + index))
                    .append(") Tj ET\n");
        }
        final var source = DocumentSource.bytes(pdf(content.toString(), 600, 800), "owned-sidebar.pdf");
        final var columns = PdfOptions.defaults().withTextLayout(PdfOptions.TextLayout.COLUMN_PARAGRAPHS);
        final var blocks = texts(source, columns.withSideStreams(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS));
        final var selected = columns.withSideStreams(PdfOptions.SideStreams.valueOf("SMALLER_TYPE_BLOCKS_ROW_TIES"));
        final var ties = texts(source, selected);
        final int side = blocks.indexOf(
                blocks.stream().filter(text -> text.startsWith("Sidebar note line a")).findFirst().orElse(""));
        if (side < 1 || !blocks.get(side - 1).equals("Main row two") || !ties.get(side - 1).equals(blocks.get(side))
                || !ties.get(side).equals("Main row two") || ties.size() != blocks.size()
                || !blocks.stream().sorted().toList().equals(ties.stream().sorted().toList())) {
            throw new AssertionError("Side-stream row-tie selection failed");
        }
        final var lines = PdfOptions.defaults();
        if (!texts(source, lines).equals(texts(source, lines.withSideStreams(selected.sideStreams())))
                || selected.withInlineScripts(PdfOptions.InlineScripts.NEARBY_SMALL_GLYPHS).sideStreams()
                           != PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_TIES
                || PdfOptions.defaults().sideStreams() != PdfOptions.SideStreams.PRESERVE) {
            throw new AssertionError("Side-stream option compatibility failed");
        }
    }

    /**
     * Extracts element texts with explicit options.
     * @param source owned source
     * @param options explicit options
     * @return texts in order
     * @throws Exception if extraction fails
     */
    private static List<String> texts(final DocumentSource source, final PdfOptions options) throws Exception {
        return DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options))))
                .partition(source)
                .elements()
                .stream()
                .map(element -> element.text())
                .toList();
    }

    /**
     * Writes a one-page Helvetica PDF.
     * @param content page content stream
     * @param width media box width
     * @param height media box height
     * @return complete owned PDF bytes
     * @throws Exception if writing fails
     */
    private static byte[] pdf(final String content, final int width, final int height) throws Exception {
        final var objects =
                new String[] {"<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + width + " " + height
                                + "] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
                        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
                        "<< /Length " + content.length() + " >>\nstream\n" + content + "endstream"};
        final var pdf = new ByteArrayOutputStream();
        pdf.write("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
        final var offsets = new ArrayList<Integer>();
        for (int i = 0; i < objects.length; i++) {
            offsets.add(pdf.size());
            pdf.write(((i + 1) + " 0 obj\n" + objects[i] + "\nendobj\n").getBytes(StandardCharsets.US_ASCII));
        }
        final int xref = pdf.size();
        pdf.write("xref\n0 6\n0000000000 65535 f \n".getBytes(StandardCharsets.US_ASCII));
        for (final int offset : offsets) {
            pdf.write(String.format(Locale.ROOT, "%010d 00000 n \n", offset).getBytes(StandardCharsets.US_ASCII));
        }
        pdf.write(("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n")
                        .getBytes(StandardCharsets.US_ASCII));
        return pdf.toByteArray();
    }

    /**
     * Checks routing and actual-word table ownership through packaged public APIs in an isolated classpath.
     * No external OCR engine or learned model is used; fixed provider geometry is independently declared.
     * @param source unchanged owned native PDF
     * @param nativeResult ordinary discovery result used only after extraction for equivalence checks
     * @throws Exception if partitioning, capability linkage or ownership checks fail
     */
    private static void verifyPresets(final DocumentSource source, final PartitionResult nativeResult)
            throws Exception {
        for (final var strategy : PdfStrategy.values()) {
            final var calls = new AtomicInteger();
            final var layouts = new AtomicInteger();
            final PdfWordOcrProvider words = (image, kind) -> {
                calls.incrementAndGet();
                final var line = new PdfOcrProvider.Line("OCR", 5, 5, 20, 10);
                return List.of(new PdfWordOcrProvider.WordLine(
                        line, List.of(new PdfWordOcrProvider.Word("OCR", 5, 5, 20, 10))));
            };
            final var builder = PdfPartitioner.builder().strategy(strategy).ocrProvider(words);
            if (strategy == PdfStrategy.HI_RES) {
                builder.tableLayoutProvider(image -> {
                    layouts.incrementAndGet();
                    final var box = new PdfTableLayoutProvider.Box(0, 0, 40, 40);
                    return List.of(new PdfTableLayoutProvider.Table(1, 1, box,
                            List.of(new PdfTableLayoutProvider.Anchor(0, 0, 1, 1, box)), "owned-release-consumer"));
                });
            }
            final var actual = DocWeave.create(PartitionerRegistry.of(List.of(builder.build()))).partition(source);
            final boolean forced = strategy == PdfStrategy.OCR_ONLY || strategy == PdfStrategy.HI_RES;
            if (calls.get() != (forced ? 1 : 0) || layouts.get() != (strategy == PdfStrategy.HI_RES ? 1 : 0)) {
                throw new AssertionError("Unexpected provider routing: " + strategy);
            }
            if (forced) {
                if (actual.elements().size() != 1 || !actual.elements().getFirst().text().equals("OCR")
                        || !"all_pages_v1".equals(actual.elements().getFirst().metadata().get("pdf_ocr_selection"))
                        || actual.elements().getFirst().type()
                                   != (strategy == PdfStrategy.HI_RES ? ElementType.Table
                                                                      : ElementType.UncategorizedText)) {
                    throw new AssertionError("Forced text/table ownership failed: " + strategy);
                }
            } else if (!actual.elements().equals(nativeResult.elements())) {
                // Observed elapsed time belongs to each call; complete deterministic extraction must match.
                throw new AssertionError("Native preset changed discovery output: " + strategy);
            }
            if (strategy == PdfStrategy.FAST && !actual.warnings().equals(nativeResult.warnings())) {
                throw new AssertionError("FAST changed native diagnostics");
            }
            if (strategy == PdfStrategy.AUTO
                    && (actual.warnings().size() != 1
                            || !actual.warnings().getFirst().message().contains("pages without native text"))) {
                // An explicitly supplied recognizer changes advertised capability even when this page uses native text.
                throw new AssertionError("AUTO lost textless-page capability diagnostics");
            }
        }
    }
}
