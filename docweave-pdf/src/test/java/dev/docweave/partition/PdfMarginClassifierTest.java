package dev.docweave.partition;

import dev.docweave.DocWeave;
import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/** Conservative margin classification preserves complete source inventories and rejects ambiguous evidence. */
class PdfMarginClassifierTest {
    /** Creates isolated margin-classification source controls. */
    PdfMarginClassifierTest() {
    }

    /**
     * Unicode whitespace changes matching keys without rewriting the original header characters or geometry.
     * @throws Exception if explicit classification fails
     */
    @Test
    void unicodeWhitespaceMarginsMatchWhileOriginalTextAndIdentityRemainVerbatim() throws Exception {
        final var classifier = PdfMarginClassifier.create();
        for (final char separator : List.of('\u00a0', '\u2007', '\u202f', '\u2009', '\u0085', '\u2028', '\u2029')) {
            final var source = List.of(text(1, "Report heading", 20, 50),
                    text(2, separator + "Report" + separator + "heading" + separator, 20, 50),
                    text(3, "Report" + separator + separator + "heading", 20, 50));
            final var result = classifier.classify(source, 3);
            assertEquals(3, result.stream().filter(e -> e.type() == ElementType.Header).count());
            for (int index = 0; index < source.size(); index++) {
                assertEquals(source.get(index).elementId(), result.get(index).elementId());
                assertEquals(source.get(index).text(), result.get(index).text());
                assertEquals(source.get(index).metadata().get("coordinates"),
                        result.get(index).metadata().get("coordinates"));
                assertEquals(ElementType.UncategorizedText, source.get(index).type());
            }
            assertEquals(result, classifier.classify(result, 3));
        }
    }

    /**
     * A visually equivalent body occurrence must veto matching margins even when it uses nonbreaking spaces.
     * @throws Exception if explicit body-policy classification fails
     */
    @Test
    void unicodeWhitespaceBodyOccurrencesStillVetoFalseMarginClassification() throws Exception {
        for (final var classifier : List.of(
                     PdfMarginClassifier.create(), PdfMarginClassifier.create(new PdfMarginOptions(.08, .08, true)))) {
            final var source = new ArrayList<>(repeated(3, 20));
            source.add(text(2, "\u00a0Report\u202fheading\u00a0", 400, 50));
            assertEquals(source, classifier.classify(source, 3));
        }
        final var source = new ArrayList<>(repeated(3, 20));
        source.add(text(1, "Report\u00a0heading", 400, 50));
        assertEquals(source, PdfMarginClassifier.create().classify(source, 3));
        final var allowed = PdfMarginClassifier.create(new PdfMarginOptions(.08, .08, true)).classify(source, 3);
        assertEquals(3, allowed.stream().filter(e -> e.type() == ElementType.Header).count());
        assertSame(source.getLast(), allowed.getLast());
    }

    /**
     * Whitespace equivalence must not introduce case, punctuation, number or zero-width character repair.
     * @throws Exception if explicit classification fails
     */
    @Test
    void whitespaceMatchingKeepsNonWhitespaceDifferencesSignificant() throws Exception {
        for (final String different :
                List.of("report heading", "Report-heading", "Report heading 2", "Report\u200b heading")) {
            final var source = List.of(
                    text(1, "Report heading", 20, 50), text(2, "Report heading", 20, 50), text(3, different, 20, 50));
            assertEquals(source, PdfMarginClassifier.create().classify(source, 3));
        }
    }

    /**
     * JSON-compatible numeric zero representations are the same upright source orientation.
     * @throws Exception if explicit margin classification fails
     */
    @Test
    void acceptsEquivalentNumericZeroRotationWithoutChangingSourceMetadata() throws Exception {
        final var classifier = PdfMarginClassifier.create();
        for (final Number rotation : List.<Number>of(
                     (byte) 0, (short) 0, 0, 0L, 0.0f, 0.0, -0.0, BigInteger.ZERO, new BigDecimal("0E-1000"))) {
            final var source = repeated(3, 20).stream().map(e -> rotation(e, rotation)).toList();
            final var result = classifier.classify(source, 3);
            assertEquals(3, result.stream().filter(e -> e.type() == ElementType.Header).count(), rotation.toString());
            assertEquals(result, classifier.classify(result, 3));
            for (int index = 0; index < source.size(); index++) {
                assertEquals(ElementType.UncategorizedText, source.get(index).type());
                assertEquals(source.get(index).elementId(), result.get(index).elementId());
                assertEquals(source.get(index).text(), result.get(index).text());
                assertEquals(source.get(index).metadata().get("coordinates"),
                        result.get(index).metadata().get("coordinates"));
                assertEquals(rotation, result.get(index).metadata().get("pdf_text_rotation"));
            }
        }
    }

    /**
     * Tiny nonzero decimal angles must not underflow into upright eligibility; invalid representations stay excluded.
     * @throws Exception if explicit margin classification fails
     */
    @Test
    void nonzeroOrNonnumericRotationsStayExcludedIncludingDecimalUnderflow() throws Exception {
        final var classifier = PdfMarginClassifier.create();
        for (final Object value : List.of(90, -90L, .25, Float.MIN_VALUE, Double.MIN_VALUE, new BigDecimal("1E-1000"),
                     new BigDecimal("-1E-1000"), "0", false)) {
            final var source = repeated(3, 20).stream().map(e -> rotation(e, value)).toList();
            final var result = classifier.classify(source, 3);
            assertEquals(source, result);
            for (int index = 0; index < source.size(); index++) {
                assertSame(source.get(index), result.get(index));
            }
        }
    }

    /**
     * An upright body occurrence still vetoes matching margins after numeric representation changes.
     * @throws Exception if explicit classification fails
     */
    @Test
    void numericZeroBodyRotationPreservesBodyVetoAndFirstPagePolicy() throws Exception {
        final var source = new ArrayList<>(repeated(3, 20));
        source.add(rotation(text(2, "Report heading", 400, 50), 0.0));
        for (final var classifier : List.of(
                     PdfMarginClassifier.create(), PdfMarginClassifier.create(new PdfMarginOptions(.08, .08, true)))) {
            assertEquals(source, classifier.classify(source, 3));
        }
        source.set(3, rotation(text(1, "Report heading", 400, 50), 0L));
        assertEquals(source, PdfMarginClassifier.create().classify(source, 3));
        final var allowed = PdfMarginClassifier.create(new PdfMarginOptions(.08, .08, true)).classify(source, 3);
        assertEquals(3, allowed.stream().filter(e -> e.type() == ElementType.Header).count());
        assertSame(source.getLast(), allowed.getLast());
    }

    /**
     * Adds one declared source orientation without changing text, page geometry or identity.
     * @param source original owned margin occurrence
     * @param rotation JSON-compatible orientation value
     * @return immutable source fixture
     */
    private static Element rotation(final Element source, final Object rotation) {
        final var metadata = new LinkedHashMap<>(source.metadata());
        metadata.put("pdf_text_rotation", rotation);
        return new Element(source.elementId(), source.type(), source.text(), metadata);
    }

    /**
     * Real native headers/footers retain identity, geometry and ordering under idempotent concurrent reuse.
     * @throws Exception if owned PDF writing, extraction or concurrent classification fails
     */
    @Test
    void realNativeRepeatedMarginsRetainSourceTextIdentityAndOrder() throws Exception {
        final byte[] bytes;
        try (final var document = new PDDocument()) {
            for (int pageNumber = 1; pageNumber <= 4; pageNumber++) {
                final var page = new PDPage();
                document.addPage(page);
                try (final var content = new PDPageContentStream(document, page)) {
                    final var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
                    for (final var text : List.of(Map.entry(770, "Quarterly Review"),
                                 Map.entry(400, "Body of page " + pageNumber), Map.entry(25, "Private circulation"))) {
                        content.beginText();
                        content.setFont(font, 10);
                        content.newLineAtOffset(50, text.getKey());
                        content.showText(text.getValue());
                        content.endText();
                    }
                }
            }
            final var output = new ByteArrayOutputStream();
            document.save(output);
            bytes = output.toByteArray();
        }
        final var source = DocWeave.create().partition(DocumentSource.bytes(bytes, "margins.pdf")).elements();
        final var classifier = PdfMarginClassifier.create();
        final var tagged = classifier.classify(source, 4);
        assertEquals(4, tagged.stream().filter(e -> e.type() == ElementType.Header).count());
        assertEquals(4, tagged.stream().filter(e -> e.type() == ElementType.Footer).count());
        assertEquals(source.stream().map(Element::text).toList(), tagged.stream().map(Element::text).toList());
        assertEquals(
                source.stream().map(Element::elementId).toList(), tagged.stream().map(Element::elementId).toList());
        assertEquals(tagged, classifier.classify(tagged, 4));
        assertTrue(source.stream().noneMatch(e -> e.metadata().containsKey("pdf_margin_classification")));
        for (int index = 0; index < source.size(); index++) {
            assertEquals(
                    source.get(index).metadata().get("coordinates"), tagged.get(index).metadata().get("coordinates"));
        }
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var tasks = java.util.stream.IntStream.range(0, 4)
                                      .mapToObj(i -> executor.submit(() -> classifier.classify(source, 4)))
                                      .toList();
            for (final var task : tasks) {
                assertEquals(tagged, task.get());
            }
        }
    }

    /**
     * Repetition must span distinct physical pages and cannot override duplicate/body/position ambiguity.
     * @throws Exception if bounded classification fails
     */
    @Test
    void requiresDistinctPagesAndCoverageIncludingBlankPhysicalPages() throws Exception {
        final var classifier = PdfMarginClassifier.create();
        final var three = repeated(3, 20);
        assertEquals(3, classifier.classify(three, 5).stream().filter(e -> e.type() == ElementType.Header).count());
        assertEquals(three, classifier.classify(three, 6));
        assertEquals(repeated(2, 20), classifier.classify(repeated(2, 20), 2));
        final var duplicate = new ArrayList<>(three);
        duplicate.add(text(1, "Report heading", 20, 50));
        assertEquals(duplicate, classifier.classify(duplicate, 3));
        final var body = new ArrayList<>(three);
        body.add(text(2, "Report heading", 400, 50));
        assertEquals(body, classifier.classify(body, 3));
        final var moved = new ArrayList<>(three);
        moved.set(2, text(3, "Report heading", 20, 100));
        assertEquals(moved, classifier.classify(moved, 3));
    }

    /**
     * Converted coordinate frames remain eligible while structural, numeric and genuinely rotated content does not.
     * @throws Exception if conversion or explicit classification fails
     */
    @Test
    void preservesStructuralRotatedNumericAndChangingMarginTextAndAcceptsConvertedFrames() throws Exception {
        final var classifier = PdfMarginClassifier.create();
        final var source = repeated(3, 20);
        final var pixels = source.stream().map(e -> PdfCoordinateConverter.create().toPixels(e, 300)).toList();
        assertTrue(classifier.classify(pixels, 3).stream().allMatch(e -> e.type() == ElementType.Header));
        for (final var type : List.of(ElementType.Table, ElementType.Image, ElementType.ListItem, ElementType.Header)) {
            final var structural =
                    source.stream().map(e -> new Element(e.elementId(), type, e.text(), e.metadata())).toList();
            assertEquals(structural, classifier.classify(structural, 3));
        }
        for (final String label : List.of("123", "")) {
            final var numeric =
                    source.stream().map(e -> new Element(e.elementId(), e.type(), label, e.metadata())).toList();
            assertEquals(numeric, classifier.classify(numeric, 3));
        }
        final var numbered =
                java.util.stream.IntStream.rangeClosed(1, 3).mapToObj(i -> text(i, "Page " + i, 950, 50)).toList();
        assertEquals(numbered, classifier.classify(numbered, 3));
        final var rotated = source.stream()
                                    .map(e -> {
                                        final var metadata = new java.util.LinkedHashMap<>(e.metadata());
                                        metadata.put("pdf_text_rotation", 90);
                                        return new Element(e.elementId(), e.type(), e.text(), metadata);
                                    })
                                    .toList();
        assertEquals(rotated, classifier.classify(rotated, 3));
    }

    /**
     * Invalid sources, exhausted element budgets and cancellation never produce partial margin classifications.
     * @throws Exception if owned validation or interruption controls fail
     */
    @Test
    void validatesSourceAndHonorsBudgetsAndCancellation() throws Exception {
        final var classifier = PdfMarginClassifier.create();
        assertThrows(IllegalArgumentException.class, () -> classifier.classify(repeated(3, 20), 2));
        assertThrows(IllegalArgumentException.class, () -> classifier.classify(List.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> classifier.classify(List.of(), 10001));
        assertThrows(IllegalArgumentException.class,
                () -> classifier.classify(List.of(new Element("none", ElementType.Image, "", Map.of())), 1));
        assertThrows(PartitionException.class,
                () -> classifier.classify(java.util.Collections.nCopies(100001, text(1, "Report heading", 20, 50)), 1));
        final var mixed = new ArrayList<>(repeated(3, 20));
        final var last = mixed.getLast();
        final var metadata = new java.util.LinkedHashMap<>(last.metadata());
        metadata.put("filename", "other.pdf");
        mixed.set(2, new Element(last.elementId(), last.type(), last.text(), metadata));
        assertThrows(IllegalArgumentException.class, () -> classifier.classify(mixed, 3));
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> classifier.classify(repeated(3, 20), 3));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    /**
     * Explicit margin bands preserve the default policy and describe configured classifications in metadata.
     * @throws Exception if configured classification fails
     */
    @Test
    void configuredBandsPreserveDefaultsAndRecordExplicitPolicy() throws Exception {
        final var source = repeated(3, 850);
        assertEquals(source, PdfMarginClassifier.create().classify(source, 3));
        final var options = PdfMarginOptions.defaults().withBands(.08, .16);
        final var classifier = PdfMarginClassifier.create(options);
        final var tagged = classifier.classify(source, 3);
        assertTrue(tagged.stream().allMatch(element -> element.type() == ElementType.Footer));
        assertEquals(tagged, classifier.classify(tagged, 3));
        for (int index = 0; index < source.size(); index++) {
            assertEquals(source.get(index).elementId(), tagged.get(index).elementId());
            assertEquals(source.get(index).text(), tagged.get(index).text());
            assertEquals(
                    source.get(index).metadata().get("coordinates"), tagged.get(index).metadata().get("coordinates"));
            final var evidence = (Map<?, ?>) tagged.get(index).metadata().get("pdf_margin_classification");
            assertEquals("repeated_margin_configured_v1", evidence.get("policy"));
            assertEquals(.08, evidence.get("header_band_fraction"));
            assertEquals(.16, evidence.get("footer_band_fraction"));
            assertEquals(false, evidence.get("first_page_body_text_allowed"));
        }
        final var headerAtExpandedBoundary = repeated(3, 150);
        assertEquals(headerAtExpandedBoundary, classifier.classify(headerAtExpandedBoundary, 3));
        assertTrue(PdfMarginClassifier.create(options.withBands(.16, .16))
                        .classify(headerAtExpandedBoundary, 3)
                        .stream()
                        .allMatch(element -> element.type() == ElementType.Header));
    }

    /**
     * Allowing physical page1 body text cannot bypass later-page duplicates or ambiguous occurrences.
     * @throws Exception if explicit body-policy classification fails
     */
    @Test
    void firstPageBodyAllowanceNeverDisablesLaterBodyDuplicateVetoes() throws Exception {
        final var source = new ArrayList<>(repeated(3, 20));
        source.add(text(1, "Report heading", 400, 50));
        assertEquals(source, PdfMarginClassifier.create().classify(source, 3));
        final var classifier = PdfMarginClassifier.create(PdfMarginOptions.defaults().withFirstPageBodyText(true));
        final var tagged = classifier.classify(source, 3);
        assertEquals(3, tagged.stream().filter(element -> element.type() == ElementType.Header).count());
        assertSame(source.getLast(), tagged.getLast());
        source.add(text(2, "Report heading", 400, 50));
        assertEquals(source, classifier.classify(source, 3));
    }

    /**
     * Margin options enforce finite nonoverlapping bounds while configured instances remain shareable.
     * @throws Exception if concurrent classifier reuse fails
     */
    @Test
    void marginOptionsAreFiniteBoundedAndConfiguredClassifiersRemainShareable() throws Exception {
        for (final double invalid : new double[] {0, -.01, .20001, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new PdfMarginOptions(invalid, .08, false));
            assertThrows(IllegalArgumentException.class, () -> new PdfMarginOptions(.08, invalid, false));
        }
        assertEquals(new PdfMarginOptions(.08, .08, false), PdfMarginOptions.defaults());
        assertThrows(NullPointerException.class, () -> PdfMarginClassifier.create(null));
        final var classifier = PdfMarginClassifier.create(new PdfMarginOptions(.08, .16, true));
        final var source = repeated(3, 850);
        final var expected = classifier.classify(source, 3);
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var futures = java.util.stream.IntStream.range(0, 10)
                                        .mapToObj(index -> executor.submit(() -> classifier.classify(source, 3)))
                                        .toList();
            for (final var future : futures) {
                assertEquals(expected, future.get());
            }
        }
    }

    /**
     * Authors identical source headings on distinct physical pages.
     * @param count physical page count
     * @param top displayed source top coordinate
     * @return immutable complete occurrence population
     */
    private static List<Element> repeated(final int count, final double top) {
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(page -> text(page, "Report heading", top, 50))
                .toList();
    }

    /**
     * Supplies independently specified PDFUserSpace geometry for one ordinary source line.
     * @param page physical source page
     * @param text unchanged source characters
     * @param top displayed source top coordinate
     * @param left displayed source left coordinate
     * @return immutable source occurrence
     */
    private static Element text(final int page, final String text, final double top, final double left) {
        return new Element(page + ":" + top + ":" + left, ElementType.UncategorizedText, text,
                Map.of("filename", "test.pdf", "filetype", "application/pdf", "page_number", page, "coordinates",
                        Map.of("system", "PDFUserSpace", "layout_width", 600.0, "layout_height", 1000.0, "points",
                                List.of(List.of(left, top), List.of(left + 150, top), List.of(left + 150, top + 10),
                                        List.of(left, top + 10)))));
    }
}
