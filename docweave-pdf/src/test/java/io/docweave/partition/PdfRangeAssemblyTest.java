package io.docweave.partition;

import io.docweave.core.Element;
import io.docweave.core.ElementType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Splitting a document into page ranges and assembling them reproduces whole-document output exactly. */
class PdfRangeAssemblyTest {
    /** Default document options. */
    private static final PartitionOptions OPTIONS = PartitionOptions.builder().build();

    /** Creates the test. */
    PdfRangeAssemblyTest() {
    }

    /**
     * Every split position into two ranges, every page alone, and uneven chunks with a short final range.
     * @throws Exception if partitioning fails
     */
    @Test
    void everySplitAssemblesToTheWholeDocument() throws Exception {
        final var assembler = PdfRangeAssembler.create();
        int plansChecked = 0;
        int crossingLinks = 0;
        for (final var testCase : PdfRangeCorpus.cases()) {
            final var whole = testCase.partition();
            final String expected = PdfRangeCorpus.exact(whole);
            final var partitioner = testCase.partitioner().get();
            final int pages = pageCount(testCase.pdf());
            final var plans = new ArrayList<List<PdfPageRange>>();
            plans.add(List.of(new PdfPageRange(1, pages)));
            for (int split = 1; split < pages; split++) {
                plans.add(List.of(new PdfPageRange(1, split), new PdfPageRange(split + 1, pages)));
            }
            for (final int size : List.of(1, 3, 4)) {
                final var plan = new ArrayList<PdfPageRange>();
                for (int first = 1; first <= pages; first += size) {
                    plan.add(new PdfPageRange(first, Math.min(pages, first + size - 1)));
                }
                plans.add(plan);
            }
            for (final var plan : plans) {
                final var ranges = new ArrayList<PdfRangeResult>();
                for (final var range : plan) {
                    ranges.add(partitioner.partitionRange(
                            source(testCase), OPTIONS, range, partitioner.standardBudget(OPTIONS)));
                }
                final var assembled = assembler.assemble(ranges);
                assertEquals(expected, PdfRangeCorpus.exact(assembled), testCase.name() + " " + plan);
                assertEquals(whole.elements(), assembled.elements());
                assertEquals(whole.warnings(), assembled.warnings());
                assertEquals(whole.inputBytes(), assembled.inputBytes());
                final var streamed = new ArrayList<Element>();
                final var session = assembler.open(streamed::add);
                for (final var range : ranges) {
                    session.accept(range);
                }
                assertEquals(whole.warnings(), session.finish());
                assertEquals(expected, PdfRangeCorpus.exact(streamed, whole.warnings()));
                plansChecked++;
                for (int index = 1; index < ranges.size(); index++) {
                    final int boundary = ranges.get(index - 1).coverage().last();
                    if (ranges.get(index).firstPageTable() != null && ranges.get(index - 1).lastPageTable() != null
                            && assembled.elements().stream().anyMatch(element -> crosses(element, boundary))) {
                        crossingLinks++;
                    }
                }
            }
        }
        assertTrue(plansChecked > 100, "plans " + plansChecked);
        assertTrue(crossingLinks > 20, "crossing links " + crossingLinks);
    }

    /**
     * Budget outcomes through ranges equal the whole document when ranges are charged, in order, to one shared
     * standard budget: the exact caps, the cumulative checks and the image limit.
     * @throws Exception if authoring fails
     */
    @Test
    void sharedStandardBudgetReproducesWholeDocumentLimits() throws Exception {
        for (final var scenario : PdfWholeDocumentGoldenTest.limitScenarios()) {
            final String expected = scenario.outcome();
            final var partitioner = scenario.partitioner();
            final var options = scenario.partitionOptions();
            final int pages = pageCount(scenario.pdf());
            for (int split = 0; split < pages; split++) {
                final var plan = split == 0 ? List.of(new PdfPageRange(1, pages))
                                            : List.of(new PdfPageRange(1, split), new PdfPageRange(split + 1, pages));
                final var budget = partitioner.standardBudget(options);
                String outcome;
                try {
                    final var ranges = new ArrayList<PdfRangeResult>();
                    for (final var range : plan) {
                        ranges.add(partitioner.partitionRange(
                                DocumentSource.bytes(scenario.pdf(), "limits.pdf"), options, range, budget));
                    }
                    outcome = "OK " + PdfRangeAssembler.create().assemble(ranges).elements().size();
                } catch (final PartitionException failure) {
                    outcome = "FAIL " + failure.code() + " " + failure.getMessage();
                }
                assertEquals(expected, outcome, scenario.name() + " " + plan);
            }
        }
    }

    /**
     * Gaps, overlaps, missing ends, other sources, options or versions, and misuse are rejected.
     * @throws Exception if partitioning fails
     */
    @Test
    void assemblerRejectsInconsistentCoverageAndIdentity() throws Exception {
        final var testCase = PdfRangeCorpus.cases()
                                     .stream()
                                     .filter(value -> value.name().equals("tables-continuation"))
                                     .findFirst()
                                     .orElseThrow();
        final var partitioner = testCase.partitioner().get();
        final var a = range(partitioner, testCase, 1, 4);
        final var b = range(partitioner, testCase, 5, 18);
        final var assembler = PdfRangeAssembler.create();
        assertRejected("uncovered", () -> assembler.assemble(List.of(a, range(partitioner, testCase, 6, 18))));
        assertRejected("overlap", () -> assembler.assemble(List.of(a, range(partitioner, testCase, 4, 18))));
        assertRejected("uncovered", () -> assembler.assemble(List.of(b)));
        assertRejected("uncovered", () -> assembler.assemble(List.of(a)));
        assertRejected("pages 1-4 uncovered", () -> assembler.assemble(List.of(b, a)));
        assertRejected("No PDF range", () -> assembler.assemble(List.of()));
        final var other = PdfRangeCorpus.cases()
                                  .stream()
                                  .filter(value -> value.name().equals("text-default"))
                                  .findFirst()
                                  .orElseThrow();
        final var foreign =
                other.partitioner().get().partitionRange(DocumentSource.bytes(other.pdf(), testCase.filename()),
                        OPTIONS, new PdfPageRange(5, 13), PdfBudget.unlimited());
        assertRejected("different sources", () -> assembler.assemble(List.of(a, foreign)));
        final var otherOptions = PdfPartitioner.create(PdfOptions.defaults().withTableContinuation(
                PdfOptions.TableContinuation.REPEATED_HEADER_WITH_PROVIDERS));
        assertRejected("different options", () -> assembler.assemble(List.of(a, range(otherOptions, testCase, 5, 18))));
        final var otherLimits = partitioner.partitionRange(source(testCase),
                PartitionOptions.builder().maxElements(99).build(), new PdfPageRange(5, 18), PdfBudget.unlimited());
        assertRejected("different options", () -> assembler.assemble(List.of(a, otherLimits)));
        assertRejected("cannot be assembled",
                () -> assembler.assemble(List.of(copy(a, "0.0.0", 1, a.idReferenceFields()), b)));
        assertRejected("cannot be assembled",
                () -> assembler.assemble(List.of(copy(a, a.libraryVersion(), 2, a.idReferenceFields()), b)));
        assertRejected("ID reference fields",
                () -> assembler.assemble(List.of(copy(a, a.libraryVersion(), 1, List.of("parent_id")), b)));
        final var session = assembler.open(element -> {});
        session.accept(a);
        assertRejected("overlap", () -> session.accept(a));
        assertThrows(IllegalStateException.class, () -> session.accept(b));
        assertThrows(IllegalStateException.class, session::finish);
        final var complete = assembler.open(element -> {});
        complete.accept(a);
        complete.accept(b);
        complete.finish();
        assertThrows(IllegalStateException.class, complete::finish);
        assertThrows(NullPointerException.class, () -> assembler.open(null));
    }

    /**
     * Post-extraction steps give the same result on assembled and whole-document elements.
     * @throws Exception if partitioning fails
     */
    @Test
    void postExtractionStepsApplyUnchanged() throws Exception {
        final var testCase = PdfRangeCorpus.cases().getFirst();
        final var partitioner = testCase.partitioner().get();
        final var assembled = PdfRangeAssembler.create().assemble(
                List.of(range(partitioner, testCase, 1, 6), range(partitioner, testCase, 7, 13)));
        final var whole = testCase.partition();
        final var classifier = PdfMarginClassifier.create();
        final var classified = classifier.classify(assembled.elements(), 13);
        assertEquals(classifier.classify(whole.elements(), 13), classified);
        assertTrue(classified.stream().anyMatch(element -> element.type() == ElementType.Header));
    }

    /**
     * A streaming session keeps neither accepted range results nor their elements once the sink has them.
     * @throws Exception if partitioning fails
     */
    @Test
    void streamingSessionDoesNotRetainAcceptedRanges() throws Exception {
        final var testCase = PdfRangeCorpus.cases()
                                     .stream()
                                     .filter(value -> value.name().equals("tables-continuation"))
                                     .findFirst()
                                     .orElseThrow();
        final var partitioner = testCase.partitioner().get();
        final var counted = new int[1];
        final var session = PdfRangeAssembler.create().open(element -> counted[0]++);
        final var released = new ArrayList<java.lang.ref.WeakReference<Object>>();
        for (final var range : List.of(new PdfPageRange(1, 4), new PdfPageRange(5, 11), new PdfPageRange(12, 18))) {
            released.addAll(acceptAndRelease(session, range(partitioner, testCase, range.first(), range.last())));
        }
        for (int attempt = 0; attempt < 50 && released.stream().anyMatch(ref -> ref.get() != null); attempt++) {
            System.gc();
            Thread.sleep(10);
        }
        assertTrue(released.stream().allMatch(ref -> ref.get() == null), "session retained an accepted range");
        session.finish();
        assertEquals(testCase.partition().elements().size(), counted[0]);
    }

    /**
     * Accepts a range and returns weak references to it and its elements, dropping every strong reference.
     * @param session open session
     * @param range result
     * @return weak references
     * @throws IOException if assembly fails
     */
    private static List<java.lang.ref.WeakReference<Object>> acceptAndRelease(
            final PdfRangeAssembler.Session session, final PdfRangeResult range) throws IOException {
        session.accept(range);
        final var references = new ArrayList<java.lang.ref.WeakReference<Object>>();
        references.add(new java.lang.ref.WeakReference<>(range));
        range.pages().forEach(
                page -> page.elements().forEach(e -> references.add(new java.lang.ref.WeakReference<>(e))));
        return references;
    }

    /**
     * Tests whether an element continues a table on a given page.
     * @param element assembled element
     * @param page previous page
     * @return whether the element links back to that page
     */
    private static boolean crosses(final Element element, final int page) {
        return element.metadata().get("pdf_table_continuation") instanceof final java.util.Map < ?,
                ? > link && Integer.valueOf(page).equals(link.get("previous_page_number"));
    }

    /**
     * Copies a result with another version or reference declaration.
     * @param source result
     * @param library library version
     * @param format format version
     * @param fields reference fields
     * @return copy
     */
    private static PdfRangeResult copy(
            final PdfRangeResult source, final String library, final int format, final List<String> fields) {
        return new PdfRangeResult(format, library, source.sourceSha256(), source.sourceBytes(), source.filename(),
                source.optionsDigest(), source.documentPages(), source.coverage(), source.documentWarnings(), fields,
                source.pages(), source.firstPageTable(), source.lastPageTable());
    }

    /**
     * Asserts an assembly rejection mentioning a phrase.
     * @param phrase expected message fragment
     * @param call rejected call
     */
    private static void assertRejected(final String phrase, final org.junit.jupiter.api.function.Executable call) {
        final var failure = assertThrows(IllegalArgumentException.class, call);
        assertTrue(failure.getMessage().contains(phrase), failure.getMessage());
    }

    /**
     * Partitions one range with the standard budget.
     * @param partitioner strategy
     * @param testCase document
     * @param first first page
     * @param last last page
     * @return result
     * @throws IOException if partitioning fails
     */
    private static PdfRangeResult range(final PdfPartitioner partitioner, final PdfRangeCorpus.Case testCase,
            final int first, final int last) throws IOException {
        return partitioner.partitionRange(
                source(testCase), OPTIONS, new PdfPageRange(first, last), partitioner.standardBudget(OPTIONS));
    }

    /**
     * Returns a case's source.
     * @param testCase case
     * @return byte source
     */
    private static DocumentSource source(final PdfRangeCorpus.Case testCase) {
        return DocumentSource.bytes(testCase.pdf(), testCase.filename());
    }

    /**
     * Reads a document's page count.
     * @param pdf document
     * @return page count
     * @throws IOException if loading fails
     */
    private static int pageCount(final byte[] pdf) throws IOException {
        try (final var document = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            return document.getNumberOfPages();
        }
    }
}
