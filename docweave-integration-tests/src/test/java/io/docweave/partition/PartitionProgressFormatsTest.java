package io.docweave.partition;

import io.docweave.DocWeave;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Installed formats without a reliable unit count report indeterminate phases and unchanged output. */
class PartitionProgressFormatsTest {
    /** Creates independent format controls. */
    PartitionProgressFormatsTest() {
    }

    /**
     * TXT, HTML, Markdown and CSV report every phase without totals; listener output equals plain output.
     * @throws Exception if partitioning fails
     */
    @Test
    void unknownTotalFormatsReportPhasesWithoutTotals() throws Exception {
        final var processor = DocWeave.create();
        for (final var source : List.of(DocumentSource.text("Title\n\nFirst paragraph.\n\nSecond.", "owned.txt"),
                     DocumentSource.text("<h1>Title</h1><p>Body</p><ul><li>Item</li></ul>", "owned.html"),
                     DocumentSource.text("# Title\n\nBody text.", "owned.md"),
                     DocumentSource.text("a,b\n1,2\n", "owned.csv"))) {
            final var events = new CopyOnWriteArrayList<PartitionProgress>();
            final var observed = processor.partition(source, PartitionOptions.defaults(), events::add);
            final var plain = processor.partition(source, PartitionOptions.defaults());
            assertEquals(plain.elements(), observed.elements(), source.toString());
            assertEquals(plain.warnings(), observed.warnings(), source.toString());
            assertEquals(List.of(PartitionProgress.Phase.values()),
                    events.stream().map(PartitionProgress::phase).toList(), source.toString());
            assertTrue(events.stream().noneMatch(PartitionProgress::determinate), source.toString());
            assertTrue(events.stream().allMatch(event -> event.unit() == null), source.toString());
        }
    }
}
