import io.docweave.partition.*;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Compares source-only and built-in partition allocations for identical byte sources/options.
 * The difference includes traversal, element construction and metadata; it is not retained heap.
 */
class MeasurePartitionAllocation {
    record Input(String name, DocumentFormat format, String content) {}

    static volatile int consumed;

    public static void main(final String[] args) throws Exception {
        final var meter = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!meter.isThreadAllocatedMemorySupported()) {
            throw new IllegalStateException("Allocation counters unavailable");
        }
        meter.setThreadAllocatedMemoryEnabled(true);
        final var inputs = List.of(new Input("ascii.txt", DocumentFormat.TXT,
                                           "This is a complete sentence for the allocation probe.\n\n".repeat(4096)),
                new Input("unicode.txt", DocumentFormat.TXT,
                        "Café 漢 🧵 is a complete sentence for the allocation probe.\n\n".repeat(4096)),
                new Input("sample.html", DocumentFormat.HTML,
                        "<html><body>"
                                + "<p>Café 🧵 is a complete sentence.</p>".repeat(2048) + "</body></html>"),
                new Input("sample.csv", DocumentFormat.CSV,
                        "name,value\n"
                                + "Café,🧵\n".repeat(2048)));
        for (final var item : inputs) {
            final byte[] bytes = item.content().getBytes(StandardCharsets.UTF_8);
            final var source = DocumentSource.bytes(bytes, item.name());
            final var options = PartitionOptions.builder().format(item.format()).build();
            final var strategy = new Partitioner() {
                public DocumentFormat format() {
                    return item.format();
                }
                public void partition(final PartitionContext context) {
                    consumed = context.text().length();
                }
            };
            for (final boolean full : List.of(false, true)) {
                final var engine = full ? PartitionEngine.create()
                                        : PartitionEngine.create(PartitionerRegistry.of(List.of(strategy)));
                for (int i = 0; i < 5; i++) {
                    engine.partition(source, options);
                }
                final long before = meter.getThreadAllocatedBytes(Thread.currentThread().threadId());
                int elements = 0;
                for (int i = 0; i < 5; i++) {
                    final var result = engine.partition(source, options);
                    elements = result.elements().size();
                    consumed = result.inputBytes() + elements;
                }
                final long allocated = (meter.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before) / 5;
                System.out.printf("%s full_partition=%s input_bytes=%d elements=%d allocated_bytes=%d%n", item.name(),
                        full, bytes.length, elements, allocated);
            }
        }
    }
}
