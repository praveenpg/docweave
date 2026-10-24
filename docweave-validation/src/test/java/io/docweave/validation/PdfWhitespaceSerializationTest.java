package io.docweave.validation;

import io.docweave.DocWeave;
import io.docweave.json.ElementJson;
import io.docweave.partition.DocumentSource;
import io.docweave.partition.PartitionerRegistry;
import io.docweave.partition.PdfOptions;
import io.docweave.partition.PdfPartitioner;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Source whitespace characters survive native text, ruled-table cell HTML and the JSON round trip unchanged.
 *
 * <p>An owned font maps single-byte codes to U+0009, U+00A0, U+2003, U+3000 and U+200B with narrow advances. The
 * guard covers the procurement separator question beyond tab: extraction must neither trim nor normalize source
 * whitespace, and serialization may apply only DocWeave's documented HTML escapes ({@code &nbsp;} for U+00A0).
 */
class PdfWhitespaceSerializationTest {
    /** Mapped whitespace characters in code order 1-5. */
    private static final String SPACES = "\t  　​";

    /** Creates the owned guard. */
    PdfWhitespaceSerializationTest() {
    }

    /**
     * Every mapped whitespace character survives element text in line/paragraph layouts, ruled-table HTML and JSON.
     * @throws Exception if authoring, extraction or serialization fails
     */
    @Test
    void sourceWhitespaceSurvivesTextTableHtmlAndJson() throws Exception {
        final byte[] pdf = page();
        final String expected = "A" + SPACES.charAt(0) + "B" + SPACES.charAt(1) + "C" + SPACES.charAt(2) + "D"
                                + SPACES.charAt(3) + "E" + SPACES.charAt(4) + "F";
        for (final var layout : List.of(PdfOptions.TextLayout.LINES, PdfOptions.TextLayout.PARAGRAPHS)) {
            final var elements = partition(pdf, PdfOptions.defaults().withTextLayout(layout));
            final var texts = elements.stream().map(element -> element.text()).toList();
            assertTrue(texts.contains(expected),
                    () -> layout + " " + texts.stream().map(PdfWhitespaceSerializationTest::codes).toList());
            final var json = ElementJson.create();
            assertEquals(texts, json.read(json.write(elements)).stream().map(element -> element.text()).toList());
        }
        final var tables = partition(pdf, PdfOptions.defaults())
                                   .stream()
                                   .filter(element -> element.metadata().get("text_as_html") instanceof String)
                                   .toList();
        assertEquals(1, tables.size(), "Owned ruled grid must be one table");
        final String html = (String) tables.getFirst().metadata().get("text_as_html");
        assertTrue(html.contains("<td>A\tB&nbsp;C D　E​F</td>"), () -> codes(html));
        assertTrue(tables.getFirst().text().contains(expected), () -> codes(tables.getFirst().text()));
    }

    /**
     * Partitions owned bytes.
     *
     * @param pdf source
     * @param options policy
     * @return elements
     * @throws Exception if extraction fails
     */
    private static List<io.docweave.core.Element> partition(final byte[] pdf, final PdfOptions options)
            throws Exception {
        return DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options))))
                .partition(DocumentSource.bytes(pdf, "whitespace.pdf"))
                .elements();
    }

    /**
     * Shows code points for failure messages without relying on terminal rendering.
     *
     * @param text value
     * @return hexadecimal code points
     */
    private static String codes(final String text) {
        return text.codePoints().mapToObj(point -> String.format("U+%04X", point)).toList().toString();
    }

    /**
     * Authors a page with one 2x1 ruled grid whose first cell holds the whitespace-separated letters, plus the same
     * run as free text above the grid.
     *
     * @return owned PDF bytes
     * @throws Exception if authoring fails
     */
    private static byte[] page() throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(400, 300));
            document.addPage(page);
            final var standard = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            final var font = new COSDictionary();
            font.setName(COSName.TYPE, "Font");
            font.setName(COSName.SUBTYPE, "Type1");
            font.setName(COSName.BASE_FONT, "Helvetica");
            font.setItem(COSName.ENCODING, COSName.WIN_ANSI_ENCODING);
            font.setInt(COSName.FIRST_CHAR, 1);
            font.setInt(COSName.LAST_CHAR, 126);
            final var widths = new COSArray();
            for (int code = 1; code <= 126; code++) {
                widths.add(COSInteger.get(code <= 5 ? 220 : code < 32 ? 0 : Math.round(standard.getWidth(code))));
            }
            font.setItem(COSName.WIDTHS, widths);
            final var map = new PDStream(document);
            try (final var stream = map.createOutputStream()) {
                stream.write(("/CIDInit /ProcSet findresource begin 12 dict begin begincmap /CMapName /T def "
                              + "1 begincodespacerange <00> <FF> endcodespacerange 5 beginbfchar <01> <0009> "
                              + "<02> <00A0> <03> <2003> <04> <3000> <05> <200B> endbfchar "
                              + "1 beginbfrange <20> <7E> <0020> endbfrange endcmap CMapName currentdict /CMap "
                              + "defineresource pop end end")
                                .getBytes(StandardCharsets.US_ASCII));
            }
            font.setItem(COSName.TO_UNICODE, map);
            final var fonts = new COSDictionary();
            fonts.setItem(COSName.getPDFName("F1"), font);
            final var resources = new PDResources();
            resources.getCOSObject().setItem(COSName.FONT, fonts);
            page.setResources(resources);
            final var content = new PDStream(document);
            try (final var stream = content.createOutputStream()) {
                final String run = "(A\u0001B\u0002C\u0003D\u0004E\u0005F) Tj";
                stream.write(("BT /F1 10 Tf 40 250 Td " + run + " ET\n"
                              + "0.5 w 40 100 m 340 100 l S 40 140 m 340 140 l S 40 180 m 340 180 l S "
                              + "40 100 m 40 180 l S 190 100 m 190 180 l S 340 100 m 340 180 l S\n"
                              + "BT /F1 10 Tf 50 155 Td " + run + " ET\n"
                              + "BT /F1 10 Tf 200 155 Td (right) Tj ET\n"
                              + "BT /F1 10 Tf 50 115 Td (lower) Tj ET\n"
                              + "BT /F1 10 Tf 200 115 Td (cell) Tj ET\n")
                                .getBytes(StandardCharsets.ISO_8859_1));
            }
            page.setContents(content);
            document.save(output);
            return output.toByteArray();
        }
    }
}
