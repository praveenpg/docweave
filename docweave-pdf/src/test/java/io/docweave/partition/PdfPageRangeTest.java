package io.docweave.partition;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Partitioning one page range: selected pages only, whole-document checks, physical pages, evidence and usage. */
class PdfPageRangeTest {
    /** Default document options. */
    private static final PartitionOptions OPTIONS = PartitionOptions.builder().build();

    /** Creates the test. */
    PdfPageRangeTest() {
    }

    /**
     * Ranges keep physical pages and record continuation evidence, usage and identity; a range starting at page 1
     * already carries final IDs.
     * @throws Exception if partitioning fails
     */
    @Test
    void rangeResultsCarryPagesEvidenceUsageAndIdentity() throws Exception {
        final var testCase = PdfRangeCorpus.cases()
                                     .stream()
                                     .filter(value -> value.name().equals("tables-continuation"))
                                     .findFirst()
                                     .orElseThrow();
        final var partitioner = testCase.partitioner().get();
        final var whole = testCase.partition();
        final var head = partitioner.partitionRange(
                source(testCase), OPTIONS, new PdfPageRange(1, 4), partitioner.standardBudget(OPTIONS));
        final var tail = partitioner.partitionRange(
                source(testCase), OPTIONS, new PdfPageRange(5, 18), partitioner.standardBudget(OPTIONS));
        assertEquals(PdfRangeResult.FORMAT_VERSION, head.formatVersion());
        assertEquals(PdfRangeResult.currentLibraryVersion(), head.libraryVersion());
        assertNotEquals("unknown", head.libraryVersion());
        assertEquals(18, head.documentPages());
        assertEquals(head.sourceSha256(), tail.sourceSha256());
        assertEquals(head.optionsDigest(), tail.optionsDigest());
        assertEquals(PdfRangeResult.ID_REFERENCE_FIELDS, head.idReferenceFields());
        assertEquals(List.of(5, 6, 7), tail.pages().stream().limit(3).map(PdfRangeResult.Page::pageNumber).toList());
        assertEquals(5, tail.pages().getFirst().elements().getFirst().metadata().get("page_number"));
        // A range starting at page 1 uses the whole-document sequence, so its IDs are already final.
        final var headElements = head.pages().stream().flatMap(page -> page.elements().stream()).toList();
        assertEquals(whole.elements().subList(0, headElements.size()), headElements);
        // The chain crosses the split: both sides carry evidence, and the tail's first table is unlinked alone.
        assertNotNull(head.lastPageTable());
        assertNotNull(tail.firstPageTable());
        assertEquals(4, head.lastPageTable().pageNumber());
        assertEquals(5, tail.firstPageTable().pageNumber());
        final var tailFirst = tail.pages().getFirst().elements().getFirst();
        assertEquals(tail.firstPageTable().elementId(), tailFirst.elementId());
        assertFalse(tailFirst.metadata().containsKey("pdf_table_continuation"));
        assertTrue(whole.elements().get(headElements.size()).metadata().containsKey("pdf_table_continuation"));
        for (final var page : head.pages()) {
            assertEquals((long) page.elements().size(), page.usage().get(PdfBudget.Kind.ELEMENTS));
            assertEquals((long) page.elements().size(), page.usage().get(PdfBudget.Kind.PAGE_ELEMENTS));
        }
        final var images = PdfRangeCorpus.cases()
                                   .stream()
                                   .filter(value -> value.name().equals("images-bytes"))
                                   .findFirst()
                                   .orElseThrow();
        final var imageRange = images.partitioner().get().partitionRange(
                source(images), OPTIONS, new PdfPageRange(2, 3), PdfBudget.unlimited());
        assertTrue(imageRange.pages().getFirst().usage().get(PdfBudget.Kind.IMAGE_BYTES) > 0);
        assertNull(imageRange.firstPageTable());
    }

    /**
     * Only the selected pages are extracted, charges name physical pages, and progress counts the range.
     * @throws Exception if partitioning fails
     */
    @Test
    void onlySelectedPagesAreExtractedAndReported() throws Exception {
        final var widths = ConcurrentHashMap.<Integer>newKeySet();
        final PdfOcrProvider recording = image -> {
            widths.add(image.getWidth());
            return PdfWholeDocumentGoldenTest.countingProvider().recognize(image);
        };
        final var partitioner = PdfPartitioner.builder()
                                        .strategy(PdfStrategy.OCR_ONLY)
                                        .ocrOptions(new PdfOcrOptions(72, 10_000_000, 20_000, 1_000_000))
                                        .ocrProvider(recording)
                                        .build();
        final var pdf = PdfWholeDocumentGoldenTest.ocrPages(List.of(1, 2, 3, 4, 5, 6, 7, 8));
        final var charged = ConcurrentHashMap.<Integer>newKeySet();
        final PdfBudget budget = (kind, amount, page) -> charged.add(page);
        final var progress = new CopyOnWriteArrayList<PartitionProgress>();
        final var result = partitioner.partitionRange(
                DocumentSource.bytes(pdf, "pages.pdf"), OPTIONS, new PdfPageRange(3, 5), budget, progress::add);
        assertEquals(Set.of(603, 604, 605), widths);
        assertEquals(Set.of(3, 4, 5), charged);
        assertEquals(List.of(3, 4, 5), result.pages().stream().map(PdfRangeResult.Page::pageNumber).toList());
        assertEquals(PartitionProgress.Phase.DONE, progress.getLast().phase());
        final var pageProgress = progress.stream().filter(value -> "pages".equals(value.unit())).toList();
        assertEquals(List.of(0L, 1L, 2L, 3L, 3L, 3L), pageProgress.stream().map(PartitionProgress::completed).toList());
        assertTrue(pageProgress.stream().allMatch(value -> value.total() == 3));
    }

    /**
     * Document-wide checks still cover the whole document; invalid ranges and sources are rejected.
     * @throws Exception if authoring fails
     */
    @Test
    void documentChecksAndInvalidRanges() throws Exception {
        final var pdf = PdfWholeDocumentGoldenTest.ocrPages(List.of(0, 0, 0, 0));
        final var source = DocumentSource.bytes(pdf, "four.pdf");
        final var limited = new PdfOptions(3, PdfOptions.defaults().maxGlyphsPerPage(),
                PdfOptions.defaults().maxOperatorsPerPage(), PdfOptions.defaults().maxImagePixels(),
                PdfOptions.defaults().maxImageBytes(), false, PdfOptions.defaults().tableLimits());
        final var tooLarge = assertThrows(PartitionException.class,
                ()
                        -> PdfPartitioner.create(limited).partitionRange(
                                source, OPTIONS, new PdfPageRange(1, 1), PdfBudget.unlimited()));
        assertEquals(PartitionException.Code.INPUT_TOO_LARGE, tooLarge.code());
        final var partitioner = PdfPartitioner.create(PdfOptions.defaults());
        final var beyond = assertThrows(PartitionException.class,
                () -> partitioner.partitionRange(source, OPTIONS, new PdfPageRange(4, 5), PdfBudget.unlimited()));
        assertEquals(PartitionException.Code.UNSUPPORTED_OPTION, beyond.code());
        assertThrows(IllegalArgumentException.class, () -> new PdfPageRange(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new PdfPageRange(3, 2));
        assertThrows(IllegalArgumentException.class,
                ()
                        -> partitioner.partitionRange(DocumentSource.remote("s3://bucket/key"), OPTIONS,
                                new PdfPageRange(1, 1), PdfBudget.unlimited()));
        assertEquals(PartitionException.Code.UNSUPPORTED_FORMAT,
                assertThrows(PartitionException.class,
                        ()
                                -> partitioner.partitionRange(DocumentSource.text("plain text"), OPTIONS,
                                        new PdfPageRange(1, 1), PdfBudget.unlimited()))
                        .code());
        final PdfPartitioner custom = new PdfPartitioner() {
            @Override
            public DocumentFormat format() {
                return DocumentFormat.PDF;
            }

            @Override
            public void partition(final PartitionContext context) {
            }
        };
        assertThrows(UnsupportedOperationException.class,
                () -> custom.partitionRange(source, OPTIONS, new PdfPageRange(1, 1), PdfBudget.unlimited()));
    }

    /**
     * Returns a case's source.
     * @param testCase case
     * @return byte source
     */
    private static DocumentSource source(final PdfRangeCorpus.Case testCase) {
        return DocumentSource.bytes(testCase.pdf(), testCase.filename());
    }
}
