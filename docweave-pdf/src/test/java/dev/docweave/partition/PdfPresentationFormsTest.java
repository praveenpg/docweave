package dev.docweave.partition;

import dev.docweave.DocWeave;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class PdfPresentationFormsTest {
    @Test
    void expandsOnlyPresentationFormsAndPreservesMathematicsMicroSignsAndCombiningMarks() throws Exception {
        assertEquals("ffi fl", PdfPresentationForms.expand("\uFB03 \uFB02", 100));
        assertEquals("\u05D0\u05BC \u0644\u0627", PdfPresentationForms.expand("\uFB30 \uFEFB", 100));
        final String distinct = "\u00B5 \u212A \u2460 \uFF21 \uD835\uDC4B\u0302 e\u0301";
        assertSame(distinct, PdfPresentationForms.expand(distinct, 100));
        assertEquals(distinct + "fi", PdfPresentationForms.expand(distinct + "\uFB01", 100));
        assertEquals("\uFEFF", PdfPresentationForms.expand("\uFEFF", 1));
    }

    @Test
    void chargesExpandedUnitsBeforeAppendingAndPreservesInterruption() throws Exception {
        assertEquals("ffi", PdfPresentationForms.expand("\uFB03", 3));
        for (final var value : new String[] {"\uFB03", "abc", "xx\uFB01"}) {
            assertEquals(PartitionException.Code.INPUT_TOO_LARGE,
                    assertThrows(PartitionException.class, () -> PdfPresentationForms.expand(value, 2)).code());
        }
        assertEquals(PartitionException.Code.INPUT_TOO_LARGE,
                assertThrows(PartitionException.class, () -> PdfPresentationForms.expand("\uFB03x", 3)).code());
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class, () -> PdfPresentationForms.expand("plain", 100));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals("fi", PdfPresentationForms.expand("\uFB01", 2));
    }

    @Test
    void immutableOptionCopiesRetainExplicitPolicyAndOldConstructorsStayRaw() {
        final var source = PdfOptions.defaults();
        assertEquals(PdfOptions.UnicodeNormalization.RAW, source.unicodeNormalization());
        final var selected = source.withUnicodeNormalization(PdfOptions.UnicodeNormalization.PRESENTATION_FORMS)
                                     .withTableDetection(PdfOptions.TableDetection.RULED_AND_DOUBLE_BORDERS)
                                     .withTextLayout(PdfOptions.TextLayout.ORIENTED_LINES)
                                     .withTableCellCoordinates(true)
                                     .withTableContinuation(PdfOptions.TableContinuation.REPEATED_HEADER);
        assertEquals(PdfOptions.UnicodeNormalization.PRESENTATION_FORMS, selected.unicodeNormalization());
        final var legacy = new PdfOptions(selected.maxPages(), selected.maxGlyphsPerPage(),
                selected.maxOperatorsPerPage(), selected.maxImagePixels(), selected.maxImageBytes(),
                selected.includeImageBytes(), selected.tableLimits(), selected.tableDetection(), selected.textLayout(),
                selected.includeTableCellCoordinates(), selected.tableContinuation());
        assertEquals(PdfOptions.UnicodeNormalization.RAW, legacy.unicodeNormalization());
        assertThrows(NullPointerException.class, () -> source.withUnicodeNormalization(null));
    }

    @Test
    void nativeGlyphExpansionChangesTextIdentityWithOriginalGeometryAndRecordsPolicy() throws Exception {
        final byte[] bytes;
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage();
            document.addPage(page);
            final var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            final var cmap = new PDStream(document);
            try (final var target = cmap.createOutputStream()) {
                target.write(("/CIDInit /ProcSet findresource begin 12 dict begin begincmap "
                              + "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def "
                              + "/CMapName /OwnedPresentation def /CMapType 2 def "
                              + "1 begincodespacerange <00> <FF> endcodespacerange "
                              + "2 beginbfchar <41> <FB01> <42> <D835DC4B0302> endbfchar "
                              + "endcmap CMapName currentdict /CMap defineresource pop end end")
                                .getBytes(StandardCharsets.US_ASCII));
            }
            font.getCOSObject().setItem(COSName.TO_UNICODE, cmap);
            try (final var content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(font, 12);
                content.newLineAtOffset(40, 700);
                content.showText("AB");
                content.endText();
            }
            document.save(output);
            bytes = output.toByteArray();
        }
        final var input = DocumentSource.bytes(bytes, "owned-mapping.pdf");
        final var raw =
                DocWeave.create(PartitionerRegistry.of(java.util.List.of(PdfPartitioner.create(PdfOptions.defaults()))))
                        .partition(input)
                        .elements()
                        .getFirst();
        final var expanded = DocWeave.create(PartitionerRegistry.of(java.util.List.of(PdfPartitioner.create(
                                                     PdfOptions.defaults().withUnicodeNormalization(
                                                             PdfOptions.UnicodeNormalization.PRESENTATION_FORMS)))))
                                     .partition(input)
                                     .elements()
                                     .getFirst();
        assertEquals("\uFB01\uD835\uDC4B\u0302", raw.text());
        assertEquals("fi\uD835\uDC4B\u0302", expanded.text());
        assertEquals(raw.metadata().get("coordinates"), expanded.metadata().get("coordinates"));
        assertFalse(raw.metadata().containsKey("pdf_native_unicode_policy"));
        assertEquals("presentation_forms_nfkc_v1", expanded.metadata().get("pdf_native_unicode_policy"));
        assertNotEquals(raw.elementId(), expanded.elementId());
    }
}
