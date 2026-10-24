import io.docweave.DocWeave;
import io.docweave.core.ElementType;
import io.docweave.partition.*;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;

/** Isolates one full-text emission per decoded source; includes loading and immutable element creation. */
class MeasureEmissionAllocation {
    static volatile String consumed;

    public static void main(final String[] args) throws Exception {
        final var meter = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!meter.isThreadAllocatedMemorySupported()) {
            throw new IllegalStateException("Allocation counters unavailable");
        }
        meter.setThreadAllocatedMemoryEnabled(true);
        final var parser = new Partitioner() {
            public DocumentFormat format() {
                return DocumentFormat.TXT;
            }
            public void partition(final PartitionContext context) throws java.io.IOException {
                context.emit(ElementType.NarrativeText, context.text(), Map.of());
            }
        };
        final var engine = DocWeave.create(PartitionerRegistry.of(List.of(parser)));
        for (final String kind : List.of("small", "ascii", "unicode")) {
            final String text = switch (kind) {
                case "small" -> "Short.";
                case "ascii" -> "a".repeat(1_048_576);
                default -> "é漢🧵".repeat(262_144);
            };
            final var source = DocumentSource.text(text, "emission.txt");
            for (int i = 0; i < 5; i++) {
                consumed = engine.partition(source).elements().getFirst().elementId();
            }
            final long thread = Thread.currentThread().threadId();
            final long before = meter.getThreadAllocatedBytes(thread);
            final long started = System.nanoTime();
            for (int i = 0; i < 5; i++) {
                consumed = engine.partition(source).elements().getFirst().elementId();
            }
            final long nanos = (System.nanoTime() - started) / 5;
            final long allocated = (meter.getThreadAllocatedBytes(thread) - before) / 5;
            System.out.printf("%s utf16_units=%d allocated_bytes=%d nanos=%d id=%s%n", kind, text.length(), allocated,
                    nanos, consumed);
        }
    }
}
