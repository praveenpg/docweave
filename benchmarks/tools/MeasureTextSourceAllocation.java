import dev.docweave.partition.*;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Isolates source loading with a no-output TXT strategy on the calling platform thread.
 * Reports thread-allocated bytes, not retained/peak heap or full-parser memory consumption.
 * Text/byte/file input construction, file writes, warmup and source snapshots are outside measured intervals.
 * Requires a JDK exposing the optional com.sun.management thread-allocation counters.
 */
class MeasureTextSourceAllocation {
    static volatile int consumed;

    public static void main(final String[] args) throws Exception {
        if (!(ManagementFactory.getThreadMXBean() instanceof final com.sun.management.ThreadMXBean meter)
                || !meter.isThreadAllocatedMemorySupported()) {
            throw new IllegalStateException("Allocation counters unavailable");
        }
        meter.setThreadAllocatedMemoryEnabled(true);
        final var strategy = new Partitioner() {
            public DocumentFormat format() {
                return DocumentFormat.TXT;
            }
            public void partition(final PartitionContext context) {
                consumed = context.text().length();
            }
        };
        final var engine = PartitionEngine.create(PartitionerRegistry.of(List.of(strategy)));
        for (final String kind : List.of("ascii", "unicode")) {
            final String text = kind.equals("ascii") ? "a".repeat(1_048_576) : "é漢🧵".repeat(262_144);
            final var path = Files.createTempFile("docweave-allocation-", ".txt");
            final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            Files.write(path, bytes);
            try {
                for (final String variant : List.of("text", "bytes", "file")) {
                    final DocumentSource source = switch (variant) {
                        case "text" -> DocumentSource.text(text, "allocation.txt");
                        case "bytes" -> DocumentSource.bytes(bytes, "allocation.txt");
                        default -> DocumentSource.of(path);
                    };
                    for (int i = 0; i < 5; i++) {
                        engine.partition(source);
                    }
                    final long thread = Thread.currentThread().threadId();
                    final long before = meter.getThreadAllocatedBytes(thread);
                    int footprint = 0;
                    for (int i = 0; i < 5; i++) {
                        footprint = engine.partition(source).inputBytes();
                    }
                    final long allocated = (meter.getThreadAllocatedBytes(thread) - before) / 5;
                    System.out.printf("%s source=%s utf16_units=%d utf8_bytes=%d allocated_bytes_per_load=%d%n", kind,
                            variant, text.length(), footprint, allocated);
                }
            } finally {
                Files.deleteIfExists(path);
            }
        }
    }
}
