package dev.docweave.partition;

import dev.docweave.DocWeave;
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
 * Source whitespace glyphs are retained verbatim. A producer may paint narrow whitespace glyphs whose ToUnicode maps
 * them to U+0009, as the procurement original does on both sides of an ampersand; native text keeps both separators
 * instead of dropping one to match a saved reference.
 */
class PdfSourceWhitespaceGlyphTest {
    /** Creates the owned control. */
    PdfSourceWhitespaceGlyphTest() {
    }

    /**
     * Both U+0009 separator glyphs around an ampersand survive line and paragraph layouts unchanged.
     * @throws Exception if authoring or extraction fails
     */
    @Test
    void tabMappedSeparatorGlyphsOnBothSidesAreRetained() throws Exception {
        final byte[] pdf = page();
        for (final var layout : List.of(PdfOptions.TextLayout.LINES, PdfOptions.TextLayout.PARAGRAPHS)) {
            final var elements = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(
                                                         PdfOptions.defaults().withTextLayout(layout)))))
                                         .partition(DocumentSource.bytes(pdf, "separators.pdf"))
                                         .elements();
            assertEquals(List.of("Black\t&\tWhite Printers"), elements.stream().map(e -> e.text()).toList());
        }
    }

    /**
     * Authors a page whose Helvetica font maps code 9 to U+0009 with a narrow advance, between visible words.
     * @return owned PDF bytes
     * @throws Exception if authoring fails
     */
    private static byte[] page() throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage(new PDRectangle(300, 100));
            document.addPage(page);
            final var standard = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            final var font = new COSDictionary();
            font.setName(COSName.TYPE, "Font");
            font.setName(COSName.SUBTYPE, "Type1");
            font.setName(COSName.BASE_FONT, "Helvetica");
            font.setItem(COSName.ENCODING, COSName.WIN_ANSI_ENCODING);
            font.setInt(COSName.FIRST_CHAR, 9);
            font.setInt(COSName.LAST_CHAR, 126);
            final var widths = new COSArray();
            for (int code = 9; code <= 126; code++) {
                widths.add(COSInteger.get(code < 32 ? (code == 9 ? 220 : 0) : Math.round(standard.getWidth(code))));
            }
            font.setItem(COSName.WIDTHS, widths);
            final var map = new PDStream(document);
            try (final var stream = map.createOutputStream()) {
                stream.write(("/CIDInit /ProcSet findresource begin 12 dict begin begincmap /CMapName /T def "
                              + "1 begincodespacerange <00> <FF> endcodespacerange 1 beginbfchar <09> <0009> endbfchar "
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
                stream.write(
                        "BT /F1 8 Tf 20 60 Td (Black\t&\tWhite Printers) Tj ET".getBytes(StandardCharsets.ISO_8859_1));
            }
            page.setContents(content);
            document.save(output);
            return output.toByteArray();
        }
    }
}
