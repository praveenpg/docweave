package io.docweave.partition;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Prepared documents give identical range results; counting charges identically without retaining elements. */
class PdfPreparedDocumentTest {
    /** Default document options. */
    private static final PartitionOptions OPTIONS = PartitionOptions.builder().build();

    /** Creates the test. */
    PdfPreparedDocumentTest() {
    }

    /**
     * Every corpus case and a tagged cross-page paragraph document give the same range results with and without
     * preparation, and prepared ranges assemble to the whole document.
     * @throws Exception if partitioning fails
     */
    @Test
    void preparedRangesEqualSourceRanges() throws Exception {
        final var cases = new ArrayList<>(PdfRangeCorpus.cases());
        final var tagged = PdfTaggedParagraphTest.fragmentedSource("valid");
        final var paragraphs = PdfTaggedParagraphTest.joining().withPageBoundary(
                PdfTaggedParagraphOptions.PageBoundary.PAGE_FRAGMENTS);
        cases.add(new PdfRangeCorpus.Case("tagged-fragments",
                ()
                        -> PdfPartitioner.create(PdfOptions.defaults()).withTaggedParagraphs(paragraphs),
                tagged, "tagged.pdf"));
        for (final var testCase : cases) {
            final var partitioner = testCase.partitioner().get();
            final var source = DocumentSource.bytes(testCase.pdf(), testCase.filename());
            final var prepared = partitioner.prepare(source, OPTIONS);
            final int pages = prepared.pageCount();
            final int split = Math.max(1, pages / 2);
            final var plan = pages == 1 ? List.of(new PdfPageRange(1, 1))
                                        : List.of(new PdfPageRange(1, split), new PdfPageRange(split + 1, pages));
            final var ranges = new ArrayList<PdfRangeResult>();
            for (final var range : plan) {
                final var direct =
                        partitioner.partitionRange(source, OPTIONS, range, partitioner.standardBudget(OPTIONS));
                final var reused =
                        partitioner.partitionRange(prepared, range, partitioner.standardBudget(OPTIONS), null);
                assertEquals(direct, reused, testCase.name() + " " + range);
                assertEquals(PdfRangeCorpus.exact(elements(direct), direct.documentWarnings()),
                        PdfRangeCorpus.exact(elements(reused), reused.documentWarnings()));
                ranges.add(reused);
            }
            assertEquals(direct(testCase), PdfRangeCorpus.exact(PdfRangeAssembler.create().assemble(ranges)),
                    testCase.name());
            assertEquals(ranges.getFirst().sourceSha256(), prepared.sourceSha256());
            assertEquals(ranges.getFirst().documentWarnings(), prepared.documentWarnings());
            assertEquals(testCase.pdf().length, prepared.sourceBytes());
        }
    }

    /**
     * Counting charges exactly the partitioning usage per page and keeps warnings, but retains no element; a dense page
     * that partitioning cannot hold is counted under a permissive budget, and the standard budget fails alike.
     * @throws Exception if partitioning fails
     */
    @Test
    void countingChargesLikePartitioningWithoutRetaining() throws Exception {
        for (final var testCase : PdfRangeCorpus.cases()) {
            final var partitioner = testCase.partitioner().get();
            final var prepared =
                    partitioner.prepare(DocumentSource.bytes(testCase.pdf(), testCase.filename()), OPTIONS);
            final var range = new PdfPageRange(2, prepared.pageCount());
            final var partitioned = partitioner.partitionRange(prepared, range, PdfBudget.unlimited(), null);
            final var counted = partitioner.countRange(prepared, range, PdfBudget.unlimited(), null);
            assertEquals(prepared.pageCount(), counted.documentPages());
            for (int index = 0; index < counted.pages().size(); index++) {
                final var expected = partitioned.pages().get(index);
                final var actual = counted.pages().get(index);
                assertEquals(expected.usage(), actual.usage(), testCase.name() + " page " + actual.pageNumber());
                assertEquals(expected.warnings(), actual.warnings());
                assertTrue(actual.elements().isEmpty());
            }
        }
        final var options = PartitionOptions.builder().maxElements(100).build();
        final var pdf = PdfWholeDocumentGoldenTest.ocrPages(List.of(3, 5000, 2));
        for (final var order : List.of(PdfOcrOptions.ReadingOrder.SOURCE_ORDER, PdfOcrOptions.ReadingOrder.GEOMETRIC)) {
            final var partitioner = PdfBudgetHookTest.ocr(order);
            final var prepared = partitioner.prepare(DocumentSource.bytes(pdf, "dense.pdf"), options);
            final var counted = partitioner.countRange(prepared, new PdfPageRange(1, 3), PdfBudget.unlimited(), null);
            assertEquals(5000L, counted.pages().get(1).usage().get(PdfBudget.Kind.PAGE_ELEMENTS));
            assertEquals(5000L, counted.pages().get(1).usage().get(PdfBudget.Kind.ELEMENTS));
            final var partitionFailure = assertThrows(PartitionException.class,
                    ()
                            -> partitioner.partitionRange(
                                    prepared, new PdfPageRange(1, 3), partitioner.standardBudget(options), null));
            final var countFailure = assertThrows(PartitionException.class,
                    ()
                            -> partitioner.countRange(
                                    prepared, new PdfPageRange(1, 3), partitioner.standardBudget(options), null));
            assertEquals(partitionFailure.code(), countFailure.code());
            assertEquals(partitionFailure.getMessage(), countFailure.getMessage());
            final var cumulative = PdfWholeDocumentGoldenTest.ocrPages(List.of(60, 41));
            final var preparedCumulative = partitioner.prepare(DocumentSource.bytes(cumulative, "sum.pdf"), options);
            final var whole = assertThrows(PartitionException.class,
                    ()
                            -> partitioner.partitionRange(preparedCumulative, new PdfPageRange(1, 2),
                                    partitioner.standardBudget(options), null));
            final var count = assertThrows(PartitionException.class,
                    ()
                            -> partitioner.countRange(preparedCumulative, new PdfPageRange(1, 2),
                                    partitioner.standardBudget(options), null));
            assertEquals(whole.getMessage(), count.getMessage());
        }
    }

    /**
     * Preparation applies the document checks, and a prepared document only fits its own configuration.
     * @throws Exception if authoring fails
     */
    @Test
    void preparationChecksAndConfigurationBinding() throws Exception {
        final var pdf = PdfWholeDocumentGoldenTest.ocrPages(List.of(0, 0, 0, 0));
        final var source = DocumentSource.bytes(pdf, "four.pdf");
        final var limited = new PdfOptions(3, PdfOptions.defaults().maxGlyphsPerPage(),
                PdfOptions.defaults().maxOperatorsPerPage(), PdfOptions.defaults().maxImagePixels(),
                PdfOptions.defaults().maxImageBytes(), false, PdfOptions.defaults().tableLimits());
        assertEquals(PartitionException.Code.INPUT_TOO_LARGE,
                assertThrows(PartitionException.class, () -> PdfPartitioner.create(limited).prepare(source, OPTIONS))
                        .code());
        final var prepared = PdfPartitioner.create(PdfOptions.defaults()).prepare(source, OPTIONS);
        assertEquals(4, prepared.pageCount());
        assertEquals("four.pdf", prepared.filename());
        assertSame(OPTIONS, prepared.options());
        final var other = PdfPartitioner.create(
                PdfOptions.defaults().withTableContinuation(PdfOptions.TableContinuation.REPEATED_HEADER));
        assertThrows(IllegalArgumentException.class,
                () -> other.partitionRange(prepared, new PdfPageRange(1, 1), PdfBudget.unlimited(), null));
        assertThrows(IllegalArgumentException.class,
                () -> other.countRange(prepared, new PdfPageRange(1, 1), PdfBudget.unlimited(), null));
        assertEquals(PartitionException.Code.UNSUPPORTED_OPTION,
                assertThrows(PartitionException.class,
                        ()
                                -> PdfPartitioner.create(PdfOptions.defaults())
                                        .partitionRange(prepared, new PdfPageRange(4, 5), PdfBudget.unlimited(), null))
                        .code());
        assertThrows(IllegalArgumentException.class,
                () -> PdfPartitioner.create(PdfOptions.defaults()).prepare(DocumentSource.remote("s3://b/k"), OPTIONS));
        final PdfPartitioner custom = new PdfPartitioner() {
            @Override
            public DocumentFormat format() {
                return DocumentFormat.PDF;
            }

            @Override
            public void partition(final PartitionContext context) {
            }
        };
        assertThrows(UnsupportedOperationException.class, () -> custom.prepare(source, OPTIONS));
        assertThrows(UnsupportedOperationException.class,
                () -> custom.partitionRange(prepared, new PdfPageRange(1, 1), PdfBudget.unlimited(), null));
        assertThrows(UnsupportedOperationException.class,
                () -> custom.countRange(prepared, new PdfPageRange(1, 1), PdfBudget.unlimited(), null));
    }

    /**
     * Flattens a range's elements.
     * @param range result
     * @return elements
     */
    private static List<io.docweave.core.Element> elements(final PdfRangeResult range) {
        return range.pages().stream().flatMap(page -> page.elements().stream()).toList();
    }

    /**
     * Whole-document output of a case.
     * @param testCase case
     * @return exact text
     * @throws Exception if partitioning fails
     */
    private static String direct(final PdfRangeCorpus.Case testCase) throws Exception {
        return PdfRangeCorpus.exact(testCase.partition());
    }
}
