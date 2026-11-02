package io.docweave.partition;

import io.docweave.DocWeave;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The per-page content-operator ceiling is optional (docweave-studio#227): {@link PdfOptions#UNLIMITED_OPERATORS}
 * disables it, an explicit positive ceiling is enforced exactly with the effective limit in the diagnostic, and
 * cancellation still stops an unbounded page.
 */
class PdfOperatorLimitTest {
    /** More operators than the library's default ceiling of 100,000. */
    private static final int COMPLEX_PAGE = 150_001;

    @Test
    void unlimitedProcessesAPageBeyondTheDefaultCeiling() throws Exception {
        final var bytes = page(COMPLEX_PAGE);
        final var limited = assertThrows(PartitionException.class,
                () -> processor(PdfOptions.defaults()).partition(DocumentSource.bytes(bytes, "complex.pdf")));
        assertEquals(PartitionException.Code.INPUT_TOO_LARGE, limited.code());
        assertTrue(limited.getMessage().contains("maxOperatorsPerPage 100000"), limited.getMessage());

        final var unlimited = PdfOptions.defaults().withMaxOperatorsPerPage(PdfOptions.UNLIMITED_OPERATORS);
        assertFalse(unlimited.operatorsLimited());
        assertNotNull(processor(unlimited).partition(DocumentSource.bytes(bytes, "complex.pdf")));
    }

    @Test
    void anExplicitCeilingIsEnforcedAtItsBoundary() throws Exception {
        final var options = PdfOptions.defaults().withMaxOperatorsPerPage(1_000);
        assertTrue(options.operatorsLimited());
        assertNotNull(processor(options).partition(DocumentSource.bytes(page(1_000), "at-limit.pdf")));
        final var over = assertThrows(PartitionException.class,
                () -> processor(options).partition(DocumentSource.bytes(page(1_001), "over-limit.pdf")));
        assertEquals(PartitionException.Code.INPUT_TOO_LARGE, over.code());
        assertTrue(over.getMessage().contains("maxOperatorsPerPage 1000"), over.getMessage());
        // The smallest ceiling: one operator passes, two fail.
        final var one = PdfOptions.defaults().withMaxOperatorsPerPage(1);
        assertNotNull(processor(one).partition(DocumentSource.bytes(page(1), "one.pdf")));
        assertEquals(PartitionException.Code.INPUT_TOO_LARGE, assertThrows(PartitionException.class,
                () -> processor(one).partition(DocumentSource.bytes(page(2), "two.pdf"))).code());
    }

    @Test
    void fontInspectionHonoursTheSameSetting() throws Exception {
        final var base = PdfOptions.defaults();
        final var unlimited = PdfFontInspector.create(base.withMaxOperatorsPerPage(PdfOptions.UNLIMITED_OPERATORS),
                64, 64 * 1024 * 1024);
        assertNotNull(unlimited.inspect(page(COMPLEX_PAGE)));
        final var limited = PdfFontInspector.create(base.withMaxOperatorsPerPage(1_000), 64, 64 * 1024 * 1024);
        assertNotNull(limited.inspect(page(1_000)));
        final var over = assertThrows(PartitionException.class, () -> limited.inspect(page(1_001)));
        assertEquals(PartitionException.Code.INPUT_TOO_LARGE, over.code());
        assertTrue(over.getMessage().contains("maxOperatorsPerPage 1000"), over.getMessage());
    }

    @Test
    void negativeCeilingsAreRejectedAndZeroMeansUnlimited() {
        final var error = assertThrows(IllegalArgumentException.class,
                () -> PdfOptions.defaults().withMaxOperatorsPerPage(-1));
        assertTrue(error.getMessage().contains("UNLIMITED_OPERATORS"), error.getMessage());
        assertEquals(0, PdfOptions.UNLIMITED_OPERATORS);
        assertFalse(PdfOptions.defaults().withMaxOperatorsPerPage(0).operatorsLimited());
        // Other budgets keep their positive-only contract.
        final var base = PdfOptions.defaults();
        assertThrows(IllegalArgumentException.class, () -> new PdfOptions(base.maxPages(), 0, 0,
                base.maxImagePixels(), base.maxImageBytes(), base.includeImageBytes(), base.tableLimits()));
    }

    @Test
    void interruptionInTheMiddleOfAnUnboundedPageStopsIt() throws Exception {
        // Tens of millions of operators take seconds; the interruption lands well inside the page.
        final var bytes = compressed(repeated("n\n", 50_000_000L), null);
        final var unlimited = processor(PdfOptions.defaults().withMaxOperatorsPerPage(PdfOptions.UNLIMITED_OPERATORS));
        assertStopsMidPage(() -> unlimited.partition(DocumentSource.bytes(bytes, "huge.pdf")));
    }

    @Test
    void interruptionInsideAFormStopsEveryPass() throws Exception {
        // The page only paints a form; the form holds the operators, so the interruption is raised inside it, where
        // PDFBox would otherwise log the failure and skip the rest of the form.
        final var bytes = compressed("/Fm0 Do\n".getBytes(StandardCharsets.US_ASCII), repeated("n\n", 50_000_000L));
        final var unlimited = PdfOptions.defaults().withMaxOperatorsPerPage(PdfOptions.UNLIMITED_OPERATORS);
        assertStopsMidPage(() -> processor(unlimited).partition(DocumentSource.bytes(bytes, "form.pdf")));
        try (var document = org.apache.pdfbox.Loader.loadPDF(bytes)) {
            assertStopsMidPage(() -> {
                final var text = new PdfPageText(unlimited, false);
                return text.getText(document);
            });
            assertStopsMidPage(() -> new InterruptiblePdfRenderer(document).renderImage(0, 0.01f));
        }
        assertStopsMidPage(() -> PdfFontInspector.create(unlimited, 64, 64 * 1024 * 1024).inspect(bytes));
    }

    @Test
    void graphicsStateNestingIsBoundedInEveryPass() throws Exception {
        final int max = PdfContentGuard.MAX_GRAPHICS_STATE_DEPTH;
        final var unlimited = PdfOptions.defaults().withMaxOperatorsPerPage(PdfOptions.UNLIMITED_OPERATORS);
        final var atLimit = compressed(repeated("q\n", max), null);
        final var over = compressed(repeated("q\n", max + 1), null);
        assertNotNull(processor(unlimited).partition(DocumentSource.bytes(atLimit, "nested.pdf")));
        final var error = assertThrows(PartitionException.class,
                () -> processor(unlimited).partition(DocumentSource.bytes(over, "too-nested.pdf")));
        assertEquals(PartitionException.Code.INPUT_TOO_LARGE, error.code());
        assertTrue(error.getMessage().contains(max + " nested graphics states"), error.getMessage());
        // Also under the default ceiling, and in the text, font-inspection and rendering passes.
        assertEquals(PartitionException.Code.INPUT_TOO_LARGE, assertThrows(PartitionException.class,
                () -> processor(PdfOptions.defaults()).partition(DocumentSource.bytes(over, "d.pdf"))).code());
        assertThrows(PartitionException.class,
                () -> PdfFontInspector.create(unlimited, 64, 64 * 1024 * 1024).inspect(over));
        try (var document = org.apache.pdfbox.Loader.loadPDF(over)) {
            assertThrows(PartitionException.class, () -> new PdfPageText(unlimited, false).getText(document));
            assertThrows(PartitionException.class, () -> new InterruptiblePdfRenderer(document).renderImage(0, 0.01f));
        }
    }

    @Test
    void imageOccurrencesAreBoundedPerPage() throws Exception {
        assertEquals(100_000, PdfPageGraphics.MAX_IMAGE_OCCURRENCES_PER_PAGE);
        final var options = PdfOptions.defaults().withMaxOperatorsPerPage(PdfOptions.UNLIMITED_OPERATORS);
        try (var document = new PDDocument()) {
            final var page = new PDPage();
            document.addPage(page);
            final var resources = new org.apache.pdfbox.pdmodel.PDResources();
            resources.put(org.apache.pdfbox.cos.COSName.getPDFName("Im0"),
                    org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(document,
                            new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB)));
            page.setResources(resources);
            page.setContents(new PDStream(document, new ByteArrayInputStream(
                    "/Im0 Do\n".repeat(11).getBytes(StandardCharsets.US_ASCII))));
            final var eleven = new PdfPageGraphics(page, options, new PdfImageBudget(options),
                    new PdfPageGeometry(page),
                    11, PdfPageGraphics.MAX_RETAINED_SEGMENTS);
            eleven.processPage(page);
            assertEquals(11, eleven.images().size());
            final var bounded = new PdfPageGraphics(page, options, new PdfImageBudget(options),
                    new PdfPageGeometry(page), 10, PdfPageGraphics.MAX_RETAINED_SEGMENTS);
            final var error = assertThrows(PartitionException.class, () -> bounded.processPage(page));
            assertEquals(PartitionException.Code.INPUT_TOO_LARGE, error.code());
            assertTrue(error.getMessage().contains("10 image occurrences"), error.getMessage());
        }
    }

    @Test
    void retainedSegmentsAreCountedAcrossPathsSetAsideByForms() throws Exception {
        final var options = PdfOptions.defaults().withMaxOperatorsPerPage(PdfOptions.UNLIMITED_OPERATORS);
        final var path = "0 0 m 1 1 l 2 2 l 3 3 l 4 4 l 5 5 l 6 6 l\n";
        try (var document = org.apache.pdfbox.Loader.loadPDF(compressed(
                (path + "/Fm0 Do\n").getBytes(StandardCharsets.US_ASCII), path.getBytes(StandardCharsets.US_ASCII)))) {
            final var page = document.getPage(0);
            // Six segments in the page's path and six in the form's: each list stays under ten, the page does not.
            final var graphics = new PdfPageGraphics(page, options, new PdfImageBudget(options),
                    new PdfPageGeometry(page), PdfPageGraphics.MAX_IMAGE_OCCURRENCES_PER_PAGE, 10);
            graphics.processPage(page);
            assertTrue(graphics.geometryDropped());
            assertTrue(graphics.segments().isEmpty());
            final var roomy = new PdfPageGraphics(page, options, new PdfImageBudget(options),
                    new PdfPageGeometry(page), PdfPageGraphics.MAX_IMAGE_OCCURRENCES_PER_PAGE, 13);
            roomy.processPage(page);
            assertFalse(roomy.geometryDropped());
        }
    }

    @Test
    void taggedIdentifiersAreBoundedPerPage() throws Exception {
        final int max = PdfMarkedGlyphs.MAX_DECLARED_IDENTIFIERS;
        assertTrue(declaresAll(max));
        assertFalse(declaresAll(max + 1));
    }

    private static boolean declaresAll(final int count) throws Exception {
        final var marked = new PdfMarkedGlyphs();
        final var bdc = org.apache.pdfbox.contentstream.operator.Operator.getOperator("BDC");
        final var emc = org.apache.pdfbox.contentstream.operator.Operator.getOperator("EMC");
        for (int id = 0; id < count; id++) {
            final var properties = new org.apache.pdfbox.cos.COSDictionary();
            properties.setInt(org.apache.pdfbox.cos.COSName.MCID, id);
            marked.operator(bdc, List.of(org.apache.pdfbox.cos.COSName.P, properties), null);
            marked.operator(emc, List.of(), null);
        }
        return marked.declared(0);
    }

    /** Work that should stop when its thread is interrupted. */
    private interface Work {
        Object run() throws Exception;
    }

    /**
     * Runs work on another thread, interrupts it once it is well under way, and requires it to stop with an
     * interruption rather than finish. Deterministic: the work takes many seconds uninterrupted.
     */
    private static void assertStopsMidPage(final Work work) throws Exception {
        final var outcome = new java.util.concurrent.atomic.AtomicReference<Object>();
        final var worker = new Thread(() -> {
            try {
                outcome.set(work.run());
            } catch (final Throwable error) {
                outcome.set(error);
            }
        });
        worker.start();
        Thread.sleep(300);
        assertTrue(worker.isAlive(), "the fixture finished before it could be interrupted");
        worker.interrupt();
        worker.join(10_000);
        assertFalse(worker.isAlive(), "the page must stop after interruption");
        assertTrue(outcome.get() instanceof Throwable error && interrupted(error),
                () -> "expected an interruption mid-page, got " + outcome.get());
    }

    /** Streams a repeated ASCII token without materializing it. */
    private static java.io.InputStream repeatedStream(final String token, final long times) {
        final byte[] unit = token.getBytes(StandardCharsets.US_ASCII);
        return new java.io.InputStream() {
            private long remaining = times * unit.length;

            @Override
            public int read() {
                if (remaining == 0) {
                    return -1;
                }
                final long position = times * unit.length - remaining--;
                return unit[(int) (position % unit.length)];
            }

            @Override
            public int read(final byte[] buffer, final int offset, final int length) {
                if (remaining == 0) {
                    return -1;
                }
                final int count = (int) Math.min(length, remaining);
                for (int i = 0; i < count; i++) {
                    final long position = times * unit.length - remaining;
                    buffer[offset + i] = unit[(int) (position % unit.length)];
                    remaining--;
                }
                return count;
            }
        };
    }

    private static Object repeated(final String token, final long times) {
        return new Object[] {token, times};
    }

    /**
     * A one-page PDF with Flate-compressed content and, optionally, a form {@code Fm0} with Flate-compressed content.
     * @param page page content: bytes or a {@link #repeated} token
     * @param form form content, or null for none
     */
    private static byte[] compressed(final Object page, final Object form) throws Exception {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            final var pdfPage = new PDPage();
            document.addPage(pdfPage);
            final var flate = org.apache.pdfbox.cos.COSName.FLATE_DECODE;
            if (form != null) {
                final var xobject = new org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject(
                        new PDStream(document, stream(form), flate));
                xobject.setBBox(new org.apache.pdfbox.pdmodel.common.PDRectangle(612, 792));
                final var resources = new org.apache.pdfbox.pdmodel.PDResources();
                resources.put(org.apache.pdfbox.cos.COSName.getPDFName("Fm0"), xobject);
                pdfPage.setResources(resources);
            }
            pdfPage.setContents(new PDStream(document, stream(page), flate));
            document.save(output);
            return output.toByteArray();
        }
    }

    private static java.io.InputStream stream(final Object content) {
        if (content instanceof final byte[] bytes) {
            return new ByteArrayInputStream(bytes);
        }
        final Object[] spec = (Object[]) content;
        return repeatedStream((String) spec[0], ((Number) spec[1]).longValue());
    }

    @Test
    void theTextPassAndRenderingCheckInterruptionOnEveryOperator() throws Exception {
        final var bytes = page(1_000);
        try (var document = org.apache.pdfbox.Loader.loadPDF(bytes)) {
            final var text = new PdfPageText(PdfOptions.defaults().withMaxOperatorsPerPage(PdfOptions.UNLIMITED_OPERATORS),
                    false);
            text.setStartPage(1);
            text.setEndPage(1);
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedIOException.class, () -> text.getText(document), "text pass");
            } finally {
                Thread.interrupted();
            }
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedIOException.class,
                        () -> new InterruptiblePdfRenderer(document).renderImage(0), "rendering");
            } finally {
                Thread.interrupted();
            }
            // Control: PDFBox's own renderer does not notice the interruption.
            Thread.currentThread().interrupt();
            try {
                assertNotNull(new org.apache.pdfbox.rendering.PDFRenderer(document).renderImage(0));
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void retainedRuleGeometryIsBoundedIndependentlyOfTheOperatorCeiling() throws Exception {
        final int over = PdfPageGraphics.MAX_RETAINED_SEGMENTS + 1;
        final var content = new StringBuilder(over * 16);
        for (int i = 0; i < over; i++) {
            content.append("0 0 m 1 1 l S\n");
        }
        final byte[] bytes;
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            final var page = new PDPage();
            document.addPage(page);
            page.setContents(new PDStream(document,
                    new ByteArrayInputStream(content.toString().getBytes(StandardCharsets.US_ASCII))));
            document.save(output);
            bytes = output.toByteArray();
        }
        final var result = processor(PdfOptions.defaults().withMaxOperatorsPerPage(PdfOptions.UNLIMITED_OPERATORS))
                .partition(DocumentSource.bytes(bytes, "geometry.pdf"));
        assertTrue(result.warnings().stream().anyMatch(warning ->
                        warning.code() == PartitionResult.WarningCode.PDF_EXTRACTION_LIMITATION
                        && warning.message().contains("line segments")),
                () -> "expected a geometry warning, got " + result.warnings());
    }

    private static boolean interrupted(final Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof InterruptedIOException || t instanceof InterruptedException) {
                return true;
            }
        }
        return false;
    }

    private static DocWeave processor(final PdfOptions options) {
        return DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(options))));
    }

    /**
     * A one-page PDF whose content stream holds exactly {@code operators} no-op operators ({@code n}, end path).
     * @param operators operator count
     * @return PDF bytes
     */
    private static byte[] page(final int operators) throws Exception {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            final var page = new PDPage();
            document.addPage(page);
            final var content = "n\n".repeat(operators).getBytes(StandardCharsets.US_ASCII);
            page.setContents(new PDStream(document, new ByteArrayInputStream(content)));
            document.save(output);
            return output.toByteArray();
        }
    }
}
