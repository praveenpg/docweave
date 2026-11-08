package io.docweave.partition;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The standard budget reproduces the 0.1.0-beta.13 limits, codes and messages exactly. */
class PdfBudgetTest {
    /** Page-local check message (check A). */
    private static final String PAGE = "PDF page exceeds document element budget";
    /** Buffered cumulative check message (check B). */
    private static final String BUFFERED = "PDF buffered page exceeds pending-element budget";

    /** Creates the test. */
    PdfBudgetTest() {
    }

    /**
     * Check A admits exactly the cap and fails on the next element, per page, for both cap values.
     * @throws Exception if a charge fails unexpectedly
     */
    @Test
    void pageLocalCapAdmitsExactlyTheCap() throws Exception {
        final var continuation =
                PdfOptions.defaults().withTableContinuation(PdfOptions.TableContinuation.REPEATED_HEADER);
        assertPageCap(PdfPartitioner.create(continuation).standardBudget(options(100_000)), 4096);
        assertPageCap(PdfPartitioner.create(continuation).standardBudget(options(50)), 50);
        assertPageCap(geometric().standardBudget(options(100_000)), 4096);
        assertPageCap(PdfPartitioner.create(PdfOptions.defaults()).standardBudget(options(50)), 50);
        assertPageCap(PdfPartitioner.create(PdfOptions.defaults()).standardBudget(options(5000)), 5000);
    }

    /**
     * Checks B and C admit exactly maxElements and report the buffered or the collector message.
     * @throws Exception if a charge fails unexpectedly
     */
    @Test
    void orderedElementsAdmitMaxElementsWithTheModeMessage() throws Exception {
        final var direct = PdfPartitioner.create(PdfOptions.defaults()).standardBudget(options(50));
        final var buffered = geometric().standardBudget(options(50));
        for (int index = 0; index < 50; index++) {
            direct.charge(PdfBudget.Kind.ELEMENTS, 1, 1 + index / 20);
            buffered.charge(PdfBudget.Kind.ELEMENTS, 1, 1 + index / 20);
        }
        assertLimit(PartitionException.Code.ELEMENT_LIMIT, "Document exceeds maxElements=50",
                () -> direct.charge(PdfBudget.Kind.ELEMENTS, 1, 3));
        assertLimit(
                PartitionException.Code.ELEMENT_LIMIT, BUFFERED, () -> buffered.charge(PdfBudget.Kind.ELEMENTS, 1, 3));
        // Page-local admissions are independent of the cumulative count, as in beta.13.
        direct.charge(PdfBudget.Kind.PAGE_ELEMENTS, 1, 4);
    }

    /**
     * The buffered pending check admits 4096 elements per page with the buffered-page message, as beta.13's writer.
     * @throws Exception if a charge fails unexpectedly
     */
    @Test
    void pendingBufferAdmitsTheFixedCeilingPerPage() throws Exception {
        final var budget = geometric().standardBudget(options(100_000));
        budget.charge(PdfBudget.Kind.PAGE_PENDING, 4095, 3);
        budget.charge(PdfBudget.Kind.PAGE_PENDING, 1, 3);
        assertLimit(PartitionException.Code.ELEMENT_LIMIT, BUFFERED,
                () -> budget.charge(PdfBudget.Kind.PAGE_PENDING, 1, 3));
        budget.charge(PdfBudget.Kind.PAGE_PENDING, 4096, 4);
        budget.pageCompleted(4);
        budget.charge(PdfBudget.Kind.PAGE_PENDING, 1, 5);
    }

    /**
     * The image total admits exactly maxImageBytes; a refused write is not retained.
     * @throws Exception if a charge fails unexpectedly
     */
    @Test
    void imageBytesAdmitTheCeilingAndRetainNothingOnRefusal() throws Exception {
        final var budget = PdfPartitioner.create(PdfRangeCorpus.withImages(PdfOptions.defaults(), 100))
                                   .standardBudget(options(10));
        budget.charge(PdfBudget.Kind.IMAGE_BYTES, 60, 1);
        assertLimit(PartitionException.Code.IMAGE_LIMIT, "PDF images exceed maxImageBytes",
                () -> budget.charge(PdfBudget.Kind.IMAGE_BYTES, 41, 2));
        budget.charge(PdfBudget.Kind.IMAGE_BYTES, 40, 2);
        assertLimit(PartitionException.Code.IMAGE_LIMIT, "PDF images exceed maxImageBytes",
                () -> budget.charge(PdfBudget.Kind.IMAGE_BYTES, 1, 1));
    }

    /**
     * Arguments are validated; the unlimited budget admits everything; custom strategies opt in.
     * @throws Exception if a charge fails unexpectedly
     */
    @Test
    void argumentsUnlimitedAndCustomStrategies() throws Exception {
        final var budget = PdfPartitioner.create(PdfOptions.defaults()).standardBudget(options(10));
        assertThrows(NullPointerException.class, () -> budget.charge(null, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> budget.charge(PdfBudget.Kind.ELEMENTS, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> budget.charge(PdfBudget.Kind.ELEMENTS, 1, 0));
        assertThrows(
                NullPointerException.class, () -> PdfPartitioner.create(PdfOptions.defaults()).standardBudget(null));
        final var unlimited = PdfBudget.unlimited();
        unlimited.charge(PdfBudget.Kind.ELEMENTS, Long.MAX_VALUE, Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class, () -> unlimited.charge(PdfBudget.Kind.IMAGE_BYTES, -1, 1));
        final PdfPartitioner custom = new PdfPartitioner() {
            @Override
            public DocumentFormat format() {
                return DocumentFormat.PDF;
            }

            @Override
            public void partition(final PartitionContext context) {
            }
        };
        assertThrows(UnsupportedOperationException.class, () -> custom.standardBudget(options(10)));
    }

    /**
     * Whole-document partitioning, which runs through the standard budget, keeps every beta.13 outcome: the exact
     * caps (cap - 1, cap, cap + 1 for both cap values), the buffered and direct cumulative checks and the image
     * budget, each with its code and message.
     * @throws Exception if authoring fails
     */
    @Test
    void wholeDocumentLimitsKeepBeta13Outcomes() throws Exception {
        final var outcomes = new LinkedHashMap<String, String>();
        for (final var scenario : PdfWholeDocumentGoldenTest.limitScenarios()) {
            outcomes.put(scenario.name(), scenario.outcome());
        }
        final var expected = Map.of("geometric-cap-4095", "OK 4095", "geometric-cap-4096", "OK 4096",
                "geometric-cap-4097", "FAIL ELEMENT_LIMIT " + PAGE, "continuation-cap-4096", "OK 4099",
                "continuation-cap-4097", "FAIL ELEMENT_LIMIT " + PAGE, "direct-cap-50", "OK 50", "direct-cap-51",
                "FAIL ELEMENT_LIMIT " + PAGE, "direct-cumulative-30-21",
                "FAIL ELEMENT_LIMIT Document exceeds maxElements=50", "buffered-cumulative-30-21",
                "FAIL ELEMENT_LIMIT " + BUFFERED, "images-400", "FAIL IMAGE_LIMIT PDF images exceed maxImageBytes");
        expected.forEach((name, outcome) -> assertEquals(outcome, outcomes.get(name), name));
    }

    /**
     * Asserts one page admits exactly {@code cap} elements; another page has its own count.
     * @param budget fresh budget
     * @param cap expected ceiling
     * @throws IOException if an admission fails unexpectedly
     */
    private static void assertPageCap(final PdfBudget budget, final int cap) throws IOException {
        for (int index = 0; index < cap - 1; index++) {
            budget.charge(PdfBudget.Kind.PAGE_ELEMENTS, 1, 7);
        }
        budget.charge(PdfBudget.Kind.PAGE_ELEMENTS, 1, 7);
        assertLimit(
                PartitionException.Code.ELEMENT_LIMIT, PAGE, () -> budget.charge(PdfBudget.Kind.PAGE_ELEMENTS, 1, 7));
        assertLimit(
                PartitionException.Code.ELEMENT_LIMIT, PAGE, () -> budget.charge(PdfBudget.Kind.PAGE_ELEMENTS, 1, 7));
        budget.charge(PdfBudget.Kind.PAGE_ELEMENTS, 1, 8);
    }

    /**
     * Asserts a typed budget failure.
     * @param code expected code
     * @param message expected message
     * @param charge refused charge
     */
    private static void assertLimit(final PartitionException.Code code, final String message,
            final org.junit.jupiter.api.function.Executable charge) {
        final var failure = assertThrows(PartitionException.class, charge);
        assertEquals(code, failure.code());
        assertEquals(message, failure.getMessage());
    }

    /**
     * Partition options with an element ceiling.
     * @param maxElements ceiling
     * @return options
     */
    private static PartitionOptions options(final int maxElements) {
        return PartitionOptions.builder().maxElements(maxElements).build();
    }

    /**
     * A strategy with geometric reading order, which buffers each page.
     * @return strategy
     */
    private static PdfPartitioner geometric() {
        return PdfPartitioner.create(PdfOptions.defaults(),
                PdfOcrOptions.defaults().withReadingOrder(PdfOcrOptions.ReadingOrder.GEOMETRIC),
                PdfWholeDocumentGoldenTest.countingProvider());
    }
}
