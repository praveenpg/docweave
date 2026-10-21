package dev.docweave.partition;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
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
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PdfFontInspectorTest {
    @Test
    void usedDictionaryIdentityAndPhysicalPagesSurviveBlankPagesWithoutTextRetention() throws Exception {
        final var report = PdfFontInspector.create().inspect(document());
        assertEquals(3, report.pageCount());
        assertEquals(2, report.fonts().size());
        final var first = report.fonts().getFirst();
        assertEquals("Helvetica", first.name());
        assertEquals("Type1", first.subtype());
        assertTrue(first.standard14());
        assertFalse(first.embedded());
        assertFalse(first.toUnicodeDeclared());
        assertEquals(6, first.glyphs());
        assertEquals(0, first.unmappedGlyphs());
        assertEquals(0, first.replacementGlyphs());
        assertEquals(List.of(2, 3), first.pages());
        final var second = report.fonts().getLast();
        assertEquals(first.name(), second.name());
        assertEquals(2, second.id());
        assertEquals(1, second.glyphs());
        assertEquals(List.of(3), second.pages());
        assertThrows(UnsupportedOperationException.class, () -> report.fonts().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.pages().clear());
        assertFalse(java.lang.reflect.Modifier.isPublic(PdfFontInspector.create().getClass().getModifiers()));
    }

    @Test
    void explicitUnicodeMappingsReplacementAndLigaturesAreReportedAsObservedFacts() throws Exception {
        final var report = PdfFontInspector.create().inspect(mappedDocument(false));
        final var font = report.fonts().getFirst();
        assertTrue(font.toUnicodeDeclared());
        assertEquals(3, font.glyphs());
        assertEquals(0, font.unmappedGlyphs());
        assertEquals(1, font.replacementGlyphs());
        final var unmapped = PdfFontInspector.create().inspect(mappedDocument(true)).fonts().getFirst();
        assertEquals(1, unmapped.unmappedGlyphs());
        assertFalse(unmapped.toUnicodeDeclared());
    }

    @Test
    void inputPageGlyphOperatorAndFontLimitsProduceTypedFailures() throws Exception {
        final byte[] bytes = document();
        final var base = PdfOptions.defaults();
        assertCode(bytes, PdfFontInspector.create(base, 1, bytes.length));
        assertCode(bytes, PdfFontInspector.create(base, 10, bytes.length - 1));
        for (final var limits : List.of(policy(2, 100, 100), policy(10, 2, 100), policy(10, 100, 1))) {
            assertCode(bytes, PdfFontInspector.create(limits, 10, bytes.length));
        }
        assertEquals(3, PdfFontInspector.create(base, 2, bytes.length).inspect(bytes).pageCount());
        assertEquals(PartitionException.Code.MALFORMED_DOCUMENT,
                assertThrows(PartitionException.class, () -> PdfFontInspector.create().inspect(new byte[] {1, 2, 3}))
                        .code());
        assertThrows(NullPointerException.class, () -> PdfFontInspector.create().inspect(null));
        assertThrows(IllegalArgumentException.class, () -> PdfFontInspector.create(base, 0, 1));
        assertThrows(NullPointerException.class, () -> PdfFontInspector.create(null, 1, 1));
    }

    @Test
    void cancellationAndConcurrentReuseKeepStateDocumentConfined() throws Exception {
        final byte[] bytes = document();
        final var inspector = PdfFontInspector.create();
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> inspector.inspect(bytes));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        final var expected = inspector.inspect(bytes);
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var tasks = java.util.stream.IntStream.range(0, 12)
                                      .mapToObj(index -> executor.submit(() -> inspector.inspect(bytes)))
                                      .toList();
            for (final var task : tasks) {
                assertEquals(expected, task.get(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void repeatedFormPaintsCountActualEncountersUnderTheSameGlyphLimit() throws Exception {
        final byte[] bytes;
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage();
            document.addPage(page);
            final var form = new org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject(document);
            form.setBBox(new org.apache.pdfbox.pdmodel.common.PDRectangle(100, 100));
            form.setResources(new org.apache.pdfbox.pdmodel.PDResources());
            try (final var content = new org.apache.pdfbox.pdmodel.PDFormContentStream(form)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.showText("ABC");
                content.endText();
            }
            try (final var content = new PDPageContentStream(document, page)) {
                content.drawForm(form);
                content.drawForm(form);
            }
            document.save(output);
            bytes = output.toByteArray();
        }
        final var report = PdfFontInspector.create().inspect(bytes);
        assertEquals(1, report.fonts().size());
        assertEquals(6, report.fonts().getFirst().glyphs());
        assertEquals(List.of(1), report.fonts().getFirst().pages());
        assertCode(bytes, PdfFontInspector.create(policy(10, 5, 100), 10, bytes.length));
    }

    @Test
    void encryptionAndExtractionPermissionsRemainExplicit() throws Exception {
        for (final String password : List.of("secret", "")) {
            final byte[] bytes;
            try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
                document.addPage(new PDPage());
                final var permissions = new org.apache.pdfbox.pdmodel.encryption.AccessPermission();
                permissions.setCanExtractContent(false);
                document.protect(new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy(
                        "owner", password, permissions));
                document.save(output);
                bytes = output.toByteArray();
            }
            assertEquals(PartitionException.Code.UNSUPPORTED_OPTION,
                    assertThrows(PartitionException.class, () -> PdfFontInspector.create().inspect(bytes)).code());
        }
    }

    private static void assertCode(final byte[] bytes, final PdfFontInspector inspector) {
        assertEquals(PartitionException.Code.INPUT_TOO_LARGE,
                assertThrows(PartitionException.class, () -> inspector.inspect(bytes)).code());
    }

    private static PdfOptions policy(final int pages, final int glyphs, final int operators) {
        final var base = PdfOptions.defaults();
        return new PdfOptions(pages, glyphs, operators, base.maxImagePixels(), base.maxImageBytes(),
                base.includeImageBytes(), base.tableLimits());
    }

    private static byte[] document() throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            final var shared = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (final String text : List.of("ABC", "DEF")) {
                final var page = new PDPage();
                document.addPage(page);
                try (final var content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(shared, 12);
                    content.newLineAtOffset(50, 700);
                    content.showText(text);
                    if (text.equals("DEF")) {
                        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        content.showText("G");
                    }
                    content.endText();
                }
                page.getResources().put(
                        COSName.getPDFName("Unused"), new PDType1Font(Standard14Fonts.FontName.COURIER));
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static byte[] mappedDocument(final boolean unmapped) throws Exception {
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            final var page = new PDPage();
            document.addPage(page);
            final var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            if (unmapped) {
                final var encoding = new COSDictionary();
                encoding.setItem(COSName.BASE_ENCODING, COSName.WIN_ANSI_ENCODING);
                final var differences = new COSArray();
                differences.add(COSInteger.get(65));
                differences.add(COSName.getPDFName(".notdef"));
                encoding.setItem(COSName.DIFFERENCES, differences);
                font.getCOSObject().setItem(COSName.ENCODING, encoding);
            } else {
                final String cmap = "/CIDInit /ProcSet findresource begin 12 dict begin begincmap "
                                    + "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def "
                                    + "/CMapName /DocWeaveUnicode def /CMapType 2 def "
                                    + "1 begincodespacerange <00> <FF> endcodespacerange "
                                    + "3 beginbfchar <41> <03A9> <42> <FFFD> <43> <00660069> endbfchar "
                                    + "endcmap CMapName currentdict /CMap defineresource pop end end";
                font.getCOSObject().setItem(COSName.TO_UNICODE,
                        new PDStream(
                                document, new java.io.ByteArrayInputStream(cmap.getBytes(StandardCharsets.US_ASCII))));
            }
            try (final var content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(font, 12);
                content.newLineAtOffset(50, 700);
                content.showText(unmapped ? "A" : "ABC");
                content.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
