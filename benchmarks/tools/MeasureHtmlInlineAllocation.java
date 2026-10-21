package dev.docweave.partition;

import org.jsoup.nodes.TextNode;

import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * Isolates inline normalization from source loading/DOM parsing/emission. Compile onto the ordinary
 * application classpath with the HTML module so its hidden renderer remains unexposed as a public API.
 * Current-thread allocated bytes exclude input construction; results are not throughput or retained heap.
 */
public class MeasureHtmlInlineAllocation {
    private static volatile String consumed;

    public static void main(final String[] args) throws Exception {
        final var meter = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        meter.setThreadAllocatedMemoryEnabled(true);
        final long thread = Thread.currentThread().threadId();
        System.out.println("java=" + System.getProperty("java.runtime.version"));
        for (final String unit : List.of("ascii words ", "Café 漢 words ", "😀 𐐀 words ")) {
            final String input = unit.repeat(4096);
            final var node = new TextNode(input);
            for (int i = 0; i < 20; i++) {
                render(node);
            }
            final long before = meter.getThreadAllocatedBytes(thread);
            for (int i = 0; i < 20; i++) {
                render(node);
            }
            final long amount = (meter.getThreadAllocatedBytes(thread) - before) / 20;
            System.out.printf("unit=%s input_code_points=%d output_code_points=%d allocated_bytes_per_call=%d%n",
                    unit.strip(), input.codePointCount(0, input.length()),
                    consumed.codePointCount(0, consumed.length()), amount);
        }
    }

    private static void render(final TextNode node) throws Exception {
        final var rendered = new HtmlInlineText();
        rendered.append(node);
        consumed = rendered.text();
    }
}
