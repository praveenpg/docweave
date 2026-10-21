package dev.docweave.partition;

import java.lang.management.ManagementFactory;
import java.util.List;

/** Isolates shared table escaping allocation; compile onto the ordinary application classpath. */
public class MeasureTableEscapeAllocation {
    private static volatile String consumed;

    public static void main(final String[] args) {
        final var meter = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        meter.setThreadAllocatedMemoryEnabled(true);
        final long thread = Thread.currentThread().threadId();
        System.out.println("java=" + System.getProperty("java.runtime.version"));
        int index = 0;
        for (final String unit :
                List.of("ascii words ", "Café 漢 words ", "😀 𐐀 words ", "😀 <&>\"'\u00a0\u0001\ud800 ")) {
            final String input = unit.repeat(4096);
            for (int i = 0; i < 100; i++) {
                consumed = HtmlTextEscaper.escape(input);
            }
            final long before = meter.getThreadAllocatedBytes(thread);
            for (int i = 0; i < 100; i++) {
                consumed = HtmlTextEscaper.escape(input);
            }
            final long amount = (meter.getThreadAllocatedBytes(thread) - before) / 100;
            System.out.printf("case=%d input_code_points=%d output_code_points=%d allocated_bytes_per_call=%d%n",
                    ++index, input.codePointCount(0, input.length()), consumed.codePointCount(0, consumed.length()),
                    amount);
        }
    }
}
