package dev.docweave.partition;

import org.junit.jupiter.api.Test;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NestedTableDescriptorsTest {
    private NestedTableBudget budget(final int count, final long payload) {
        return new NestedTableBudget(
                new NestedTableOptions(NestedTableOptions.Mode.PRESERVE_METADATA, count, 4, payload));
    }

    private List<Map<String, Object>> path(final int ordinal) {
        return List.of(Map.of("row", 0, "column", 0, "table_index", ordinal));
    }

    @Test
    @SuppressWarnings("unchecked")
    void snapshotsAreDeeplyImmutableAndDoNotRetainMutableInputs() throws Exception {
        final var writer = new NestedTableDescriptors(budget(3, 10000), 20);
        final var step = new LinkedHashMap<>(path(0).getFirst());
        final var path = new ArrayList<Map<String, Object>>(List.of(step));
        final var link = new LinkedHashMap<String, Object>(Map.of("start_index", 1, "text", "Link", "url", "relative"));
        final var links = new ArrayList<Map<String, Object>>(List.of(link));
        final var captions = new ArrayList<>(List.of("Caption"));
        writer.add(null, path, 2, 7, "😀Link", "<table></table>", links, captions);
        step.put("row", 99);
        link.put("url", "changed");
        captions.clear();
        final var metadata = writer.metadata();
        final var child = ((List<Map<String, Object>>) metadata.get("nested_tables")).getFirst();
        assertEquals(path(0), child.get("path"));
        assertEquals(List.of("Caption"), child.get("captions"));
        assertEquals("relative", ((List<Map<String, Object>>) child.get("links")).getFirst().get("url"));
        assertThrows(UnsupportedOperationException.class, () -> child.put("index", 9));
        assertThrows(UnsupportedOperationException.class,
                () -> ((List<Map<String, Object>>) child.get("path")).getFirst().put("row", 1));
        assertThrows(UnsupportedOperationException.class,
                () -> ((List<Map<String, Object>>) child.get("links")).getFirst().put("url", "x"));
        assertThrows(UnsupportedOperationException.class, () -> ((List<String>) child.get("captions")).add("x"));
    }

    @Test
    void invalidDescriptorsDoNotReserveOrPollutePaths() throws Exception {
        final var budget = budget(1, 10000);
        final var writer = new NestedTableDescriptors(budget, 10);
        assertThrows(
                IllegalArgumentException.class, () -> writer.add(null, path(0), -1, 1, "x", "", List.of(), List.of()));
        assertThrows(
                IllegalArgumentException.class, () -> writer.add(null, path(0), 0, 11, "x", "", List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> writer.add(0, path(0), 0, 1, "x", "", List.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
                ()
                        -> writer.add(null, List.of(Map.of("row", -1, "column", 0, "table_index", 0)), 0, 1, "x", "",
                                List.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
                ()
                        -> writer.add(null, path(0), 0, 1, "x", "",
                                List.of(Map.of("start_index", 0, "text", "wrong", "url", "x")), List.of()));
        assertThrows(IllegalArgumentException.class,
                ()
                        -> writer.add(null, path(0), 0, 1, "x", "",
                                List.of(Map.of("start_index", 0, "text", "x", "url", "x", "extra", new Object())),
                                List.of()));
        assertEquals(Map.of(), writer.metadata());
        assertEquals(10000, budget.remainingCodePoints());
        writer.add(null, path(0), 0, 1, "x", "", List.of(), List.of());
        assertThrows(
                IllegalArgumentException.class, () -> writer.add(null, path(0), 0, 1, "x", "", List.of(), List.of()));
    }

    @Test
    void childrenRequireExistingParentMatchingPrefixAndContainedRootInterval() throws Exception {
        final var writer = new NestedTableDescriptors(budget(4, 10000), 20);
        writer.add(null, path(0), 3, 10, "parent", "", List.of(), List.of());
        final var deep = new ArrayList<>(path(0));
        deep.add(path(0).getFirst());
        assertThrows(
                IllegalArgumentException.class, () -> writer.add(null, deep, 4, 5, "child", "", List.of(), List.of()));
        assertThrows(
                IllegalArgumentException.class, () -> writer.add(0, deep, 2, 5, "child", "", List.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
                ()
                        -> writer.add(0, List.of(path(1).getFirst(), path(0).getFirst()), 4, 5, "child", "", List.of(),
                                List.of()));
        writer.add(0, deep, 4, 4, "", "", List.of(), List.of());
    }

    @Test
    void payloadFailuresAndInterruptionLeaveCollectorReusable() throws Exception {
        final var budget = budget(1, 200);
        final var writer = new NestedTableDescriptors(budget, 10);
        assertEquals(PartitionException.Code.TABLE_LIMIT,
                assertThrows(PartitionException.class,
                        () -> writer.add(null, path(0), 0, 1, "😀".repeat(300), "", List.of(), List.of()))
                        .code());
        assertEquals(200, budget.remainingCodePoints());
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class,
                    () -> writer.add(null, path(0), 0, 1, "😀", "", List.of(), List.of()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        writer.add(null, path(0), 0, 1, "😀", "", List.of(), List.of());
        assertTrue(budget.remainingCodePoints() < 200);
    }

    @Test
    @SuppressWarnings("unchecked")
    void docxSemanticSnapshotsAreWhitelistedValidatedAndDeeplyImmutable() throws Exception {
        final var budget = budget(2, 10000);
        final var writer = new NestedTableDescriptors(budget, 20);
        final var range = new LinkedHashMap<String, Object>(Map.of("start_index", 0, "end_index", 1, "kind", "ins"));
        final var source = new LinkedHashMap<String, Object>(Map.of("docx_revisions", List.of(range)));
        assertThrows(IllegalArgumentException.class,
                () -> writer.add(null, path(0), 0, 1, "x", "", List.of(), List.of(), Map.of("unknown", "payload")));
        assertThrows(IllegalArgumentException.class,
                ()
                        -> writer.add(null, path(0), 0, 1, "x", "", List.of(), List.of(),
                                Map.of("docx_references", List.of(Map.of("start_index", 2)))));
        assertThrows(IllegalArgumentException.class,
                ()
                        -> writer.add(null, path(0), 0, 1, "x", "", List.of(), List.of(),
                                Map.of("docx_revisions", List.of(Map.of("start_index", 0, "end_index", 1,
                                                                 "nested_tables", List.of())))));
        assertEquals(10000, budget.remainingCodePoints());
        writer.add(null, List.of(Map.of("row", 1, "column", 1, "table_index", 0, "owner_row", 0, "owner_column", 0)), 0,
                1, "x", "", List.of(), List.of(), source);
        range.put("kind", "changed");
        source.clear();
        final var child = ((List<Map<String, Object>>) writer.metadata().get("nested_tables")).getFirst();
        final var snapshot = (Map<String, Object>) child.get("source_metadata");
        final var saved = ((List<Map<String, Object>>) snapshot.get("docx_revisions")).getFirst();
        assertEquals("ins", saved.get("kind"));
        assertThrows(UnsupportedOperationException.class, () -> saved.put("start_index", 99));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("docx_field_policy", "x"));
    }

    @Test
    void incompleteOrNegativeOwnerCoordinatesCannotConsumeCapacity() throws Exception {
        final var budget = budget(1, 10000);
        final var writer = new NestedTableDescriptors(budget, 1);
        for (final var step : List.of(Map.<String, Object>of("row", 0, "column", 0, "table_index", 0, "owner_row", 0),
                     Map.<String, Object>of(
                             "row", 0, "column", 0, "table_index", 0, "owner_row", -1, "owner_column", 0))) {
            assertThrows(IllegalArgumentException.class,
                    () -> writer.add(null, List.of(step), 0, 1, "x", "", List.of(), List.of()));
        }
        assertEquals(10000, budget.remainingCodePoints());
        writer.add(null, path(0), 0, 1, "x", "", List.of(), List.of());
    }

    @Test
    void preflightChecksDoNotReserveAndRespectPriorReservations() throws Exception {
        final var budget = budget(2, 100);
        budget.checkCount(2, 2);
        budget.checkCount(2, 2);
        assertThrows(PartitionException.class, () -> budget.checkCount(3, 1));
        assertThrows(PartitionException.class, () -> budget.checkCount(1, 5));
        assertThrows(IllegalArgumentException.class, () -> budget.checkCount(-1, 1));
        assertEquals(100, budget.remainingCodePoints());
        budget.reserve(1, 10);
        assertThrows(PartitionException.class, () -> budget.checkCount(2, 1));
        budget.checkCount(1, 1);
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> budget.checkCount(1, 1));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        budget.reserve(1, 90);
        assertEquals(0, budget.remainingCodePoints());
    }
}
