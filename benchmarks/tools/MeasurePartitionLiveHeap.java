import dev.docweave.partition.*;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.management.ObjectName;

/**
 * Intrusive live-heap observations using HotSpot class histograms (each requests a full GC).
 * Measures source held, source plus result held, then source held again; all classes are included.
 * These are whole-process observations, not an object-graph retained-size or peak-heap analysis.
 */
class MeasurePartitionLiveHeap {
    record Input(String name, DocumentFormat format, String content) {}

    static volatile PartitionResult heldResult;

    static long liveBytes() throws Exception {
        final var server = ManagementFactory.getPlatformMBeanServer();
        final String histogram = (String) server.invoke(new ObjectName("com.sun.management:type=DiagnosticCommand"),
                "gcClassHistogram", new Object[] {new String[0]}, new String[] {"[Ljava.lang.String;"});
        final String total =
                histogram.lines().filter(line -> line.stripLeading().startsWith("Total")).findFirst().orElseThrow();
        return Long.parseLong(total.strip().split("\\s+")[2]);
    }

    static void partition(final PartitionEngine engine, final DocumentSource source, final PartitionOptions options)
            throws Exception {
        heldResult = engine.partition(source, options);
    }

    public static void main(final String[] args) throws Exception {
        final var engine = PartitionEngine.create();
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
        for (int i = 0; i < 3; i++) {
            liveBytes();
        } // Warm management/histogram machinery.
        for (final var item : inputs) {
            final var source = DocumentSource.bytes(item.content().getBytes(StandardCharsets.UTF_8), item.name());
            final var options = PartitionOptions.builder().format(item.format()).build();
            for (int i = 0; i < 5; i++) {
                partition(engine, source, options);
            }
            heldResult = null;
            final long before = liveBytes();
            partition(engine, source, options);
            final int elements = heldResult.elements().size();
            final long held = liveBytes();
            heldResult = null;
            final long released = liveBytes();
            // Keep the source live through the final histogram, including under aggressive JIT liveness.
            java.lang.ref.Reference.reachabilityFence(source);
            java.lang.ref.Reference.reachabilityFence(engine);
            System.out.printf("%s elements=%d before_live_bytes=%d result_held_live_bytes=%d released_live_bytes=%d "
                                      + "held_minus_released=%d%n",
                    item.name(), elements, before, held, released, held - released);
        }
    }
}
