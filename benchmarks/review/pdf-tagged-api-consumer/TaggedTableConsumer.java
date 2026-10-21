import dev.docweave.DocWeave;
import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
import dev.docweave.partition.DocumentSource;
import dev.docweave.partition.PartitionerRegistry;
import dev.docweave.partition.PdfNativeLayoutOptions;
import dev.docweave.partition.PdfOptions;
import dev.docweave.partition.PdfPartitioner;
import dev.docweave.partition.PdfTaggedTableGeometry;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/** Independent public-API consumer of packaged PDF/core JARs, with no test or parser implementation imports. */
public final class TaggedTableConsumer {
    /** Prevents standalone consumer construction. */
    private TaggedTableConsumer() {
    }

    /**
     * Exercises real source tags and conservative fallback through only public Java21 library contracts.
     * @param args optional fresh properties receipt and copied consumer source path
     * @throws Exception if owned PDF construction, API linkage or extraction assertions fail
     */
    public static void main(final String[] args) throws Exception {
        final var source = DocumentSource.bytes(document(), "owned-tagged-table.pdf");
        final var options = PdfOptions.defaults().withTableCellCoordinates(false);
        final var strict = PdfPartitioner.createWithTaggedTables(options).withTaggedTableGeometry(
                PdfTaggedTableGeometry.LOGICAL_TEXT_ENVELOPE);
        final var strictResult = DocWeave.create(PartitionerRegistry.of(List.of(strict))).partition(source);
        require(strictResult.elements().stream().noneMatch(TaggedTableConsumer::sourceTable));
        final var selected =
                PdfPartitioner.createWithTaggedTables(options, PdfNativeLayoutOptions.CellTextJoin.PRESERVE_LINE_BREAKS)
                        .withTaggedTableGeometry(PdfTaggedTableGeometry.LOGICAL_CELL_TEXT_ENVELOPES);
        final var result = DocWeave.create(PartitionerRegistry.of(List.of(selected))).partition(source);
        final var tables = result.elements().stream().filter(TaggedTableConsumer::sourceTable).toList();
        require(tables.size() == 1);
        final var table = tables.getFirst();
        require(table.text().equals("Header one\nHeader two Label 123"));
        require(table.metadata()
                        .get("text_as_html")
                        .equals("<table><tbody><tr><th colspan=\"2\">Header one<br>Header two</th></tr>"
                                + "<tr><td>Label</td><td>123</td></tr><tr><td></td><td></td></tr></tbody></table>"));
        require(table.metadata().get("pdf_table_geometry_policy").equals("source_logical_cell_text_envelopes_v1"));
        require(table.metadata().get("pdf_table_cell_text_policy").equals("native_line_breaks_v1"));
        require(table.metadata().get("pdf_table_multiline_cells").equals(1));
        require(table.metadata().get("pdf_table_tagged_empty_cells").equals(2));
        require(table.metadata().get("pdf_table_caption_references").equals(1));
        require(!table.metadata().containsKey("pdf_table_cells"));
        require(result.elements().stream().filter(e -> e.type() == ElementType.Image).count() == 1);
        for (final String outside : List.of("Report caption", "Source note")) {
            require(result.elements()
                            .stream()
                            .filter(e -> e.type() != ElementType.Table)
                            .filter(e -> e.text().equals(outside))
                            .count()
                    == 1);
        }
        require(strictResult.elements().stream().filter(e -> e.type() == ElementType.Image).count() == 1);
        // Layout-inserted ASCII whitespace differs; every original nonblank code point must remain.
        require(characters(strictResult.elements()).equals(characters(result.elements())));
        require(PdfPartitioner.class.isInterface());
        if (args.length != 0) {
            record
            (args);
        }
        System.out.println("Java " + Runtime.version() + ": packaged tagged-table API consumer passed");
    }

    /**
     * Records the exact isolated runtime and consumer identities only after all public assertions pass.
     * @param args fresh properties receipt and copied source
     * @throws Exception if artifact closure, bytecode, source hashing or fresh output checks fail
     */
    private static void record(final String[] args) throws Exception {
        require(args.length == 2 && Runtime.version().feature() == 21);
        final var entries = System.getProperty("java.class.path").split(File.pathSeparator);
        require(entries.length == 7 && Files.isDirectory(Path.of(entries[0])));
        final var expected = Set.of("docweave-core-0.1.0-SNAPSHOT.jar", "docweave-pdf-0.1.0-SNAPSHOT.jar",
                "pdfbox-3.0.8.jar", "pdfbox-io-3.0.8.jar", "fontbox-3.0.8.jar", "commons-logging-1.4.0.jar");
        final var seen = new HashSet<String>();
        final var receipt = new Properties();
        for (int index = 1; index < entries.length; index++) {
            final var path = Path.of(entries[index]);
            require(Files.isRegularFile(path) && seen.add(path.getFileName().toString()));
            receipt.setProperty("sha256." + path.getFileName(), hash(Files.readAllBytes(path)));
        }
        require(seen.equals(expected));
        final var bytecode = Files.readAllBytes(Path.of(entries[0]).resolve("TaggedTableConsumer.class"));
        require(ByteBuffer.wrap(bytecode).getInt() == 0xCAFEBABE && ByteBuffer.wrap(bytecode).getShort(6) == 65);
        receipt.setProperty("consumer_source_sha256", hash(Files.readAllBytes(Path.of(args[1]))));
        receipt.setProperty("consumer_class_sha256", hash(bytecode));
        receipt.setProperty("runtime", Runtime.version().toString());
        receipt.setProperty("consumer_class_major", "65");
        receipt.setProperty("runtime_artifact_count", "6");
        receipt.setProperty("public_api_assertions_passed", "true");
        receipt.setProperty("original_nonblank_codepoint_counts_preserved", "true");
        try (final var writer = Files.newBufferedWriter(Path.of(args[0]), StandardOpenOption.CREATE_NEW)) {
            receipt.store(writer, "Owned packaged PDF public API consumer; no external accuracy claim");
        }
    }

    /**
     * Computes exact consumer and packaged runtime identities without storing local paths.
     * @param bytes consumer source, bytecode or JAR bytes
     * @return lowercase SHA256
     * @throws Exception if SHA256 is unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Distinguishes accepted source tables from any native fallback table without searching by content.
     * @param element public emitted element
     * @return whether source-tagged extraction owns this table
     */
    private static boolean sourceTable(final Element element) {
        return element.type() == ElementType.Table
                && "tagged_structure_native_glyphs_v1".equals(element.metadata().get("pdf_table_method"));
    }

    /**
     * Counts original non-ASCII-whitespace characters independently of element boundaries and text order.
     * @param elements complete public output
     * @return code-point occurrence counts; not a semantic reading-order accuracy score
     */
    private static Map<Integer, Long> characters(final List<Element> elements) {
        return elements.stream()
                .flatMapToInt(e -> e.text().codePoints())
                .filter(c -> c != 32 && c != 9 && c != 10 && c != 11 && c != 12 && c != 13)
                .boxed()
                .collect(Collectors.groupingBy(c -> c, Collectors.counting()));
    }

    /**
     * Creates a complete owned PDF with class spans, an empty logical row, caption, gap text and raster placement.
     * @return valid original owned PDF bytes, without importing PDFBox into the consumer source
     * @throws Exception if in-memory PDF assembly fails
     */
    private static byte[] document() throws Exception {
        final String content = """
                /Caption << /MCID 0 >> BDC BT /F1 12 Tf 50 740 Td (Report caption) Tj ET EMC
                /P << /MCID 1 >> BDC BT /F1 12 Tf 50 700 Td (Header one) Tj 0 -16 Td (Header two) Tj ET EMC
                BT /F1 12 Tf 100 665 Td (Source note) Tj ET
                /P << /MCID 2 >> BDC BT /F1 12 Tf 50 640 Td (Label) Tj ET EMC
                /P << /MCID 3 >> BDC BT /F1 12 Tf 260 640 Td (123) Tj ET EMC
                /P << /MCID 4 >> BDC EMC
                /P << /MCID 5 >> BDC EMC
                q 20 0 0 20 450 50 cm /Im1 Do Q
                """;
        final var objects =
                List.of("<< /Type /Catalog /Pages 2 0 R /StructTreeRoot 7 0 R /MarkInfo << /Marked true >> >>",
                        "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /StructParents 0 "
                                + "/Resources << /Font << /F1 4 0 R >> /XObject << /Im1 6 0 R >> >> /Contents 5 0 R >>",
                        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>", stream("", content),
                        stream("/Type /XObject /Subtype /Image /Width 1 /Height 1 /ColorSpace /DeviceRGB "
                                        + "/BitsPerComponent 8 /Filter /ASCIIHexDecode",
                                "FF0000>"),
                        "<< /Type /StructTreeRoot /K [8 0 R] /ParentTree 18 0 R /ParentTreeNextKey 1 "
                                + "/ClassMap << /Wide << /O /Table /ColSpan 2 >> >> >>",
                        "<< /Type /StructElem /S /Table /P 7 0 R /Pg 3 0 R /K [9 0 R 10 0 R 11 0 R 12 0 R] >>",
                        "<< /Type /StructElem /S /Caption /P 8 0 R /Pg 3 0 R /K 0 >>",
                        "<< /Type /StructElem /S /TR /P 8 0 R /K [13 0 R] >>",
                        "<< /Type /StructElem /S /TR /P 8 0 R /K [14 0 R 15 0 R] >>",
                        "<< /Type /StructElem /S /TR /P 8 0 R /K [16 0 R 17 0 R] >>",
                        "<< /Type /StructElem /S /TH /P 10 0 R /Pg 3 0 R /C /Wide /K 1 >>",
                        "<< /Type /StructElem /S /TD /P 11 0 R /Pg 3 0 R /K 2 >>",
                        "<< /Type /StructElem /S /TD /P 11 0 R /Pg 3 0 R /K 3 >>",
                        "<< /Type /StructElem /S /TD /P 12 0 R /Pg 3 0 R /K 4 >>",
                        "<< /Type /StructElem /S /TD /P 12 0 R /Pg 3 0 R /K 5 >>",
                        "<< /Nums [0 [9 0 R 13 0 R 14 0 R 15 0 R 16 0 R 17 0 R]] >>");
        final var pdf = new ByteArrayOutputStream();
        pdf.write("%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII));
        final var offsets = new ArrayList<Integer>();
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(pdf.size());
            pdf.write(((i + 1) + " 0 obj\n" + objects.get(i) + "\nendobj\n").getBytes(StandardCharsets.US_ASCII));
        }
        final int xref = pdf.size();
        pdf.write(("xref\n0 " + (objects.size() + 1) + "\n0000000000 65535 f \n").getBytes(StandardCharsets.US_ASCII));
        for (final int offset : offsets) {
            pdf.write(String.format(Locale.ROOT, "%010d 00000 n \n", offset).getBytes(StandardCharsets.US_ASCII));
        }
        pdf.write(("trailer\n<< /Size " + (objects.size() + 1) + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n")
                        .getBytes(StandardCharsets.US_ASCII));
        return pdf.toByteArray();
    }

    /**
     * Encodes an exact ASCII stream length before object/xref assembly.
     * @param attributes additional stream dictionary fields
     * @param content owned ASCII content
     * @return one complete stream object body
     */
    private static String stream(final String attributes, final String content) {
        return "<< " + attributes + " /Length " + content.length() + " >>\nstream\n" + content + "\nendstream";
    }

    /**
     * Rejects broken public behavior without changing expected values to fit actual extraction.
     * @param valid required public behavior
     */
    private static void require(final boolean valid) {
        if (!valid) {
            throw new AssertionError("Packaged tagged-table public API consumer failed");
        }
    }
}
