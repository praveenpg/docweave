import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Compares strict full-buffer decode with bounded validation followed by the JDK UTF-8 String path. */
class MeasureDecoderAllocation {
    static volatile String consumed;

    static String decode(final byte[] bytes, final boolean bounded) throws Exception {
        final var decoder = StandardCharsets.UTF_8.newDecoder()
                                    .onMalformedInput(CodingErrorAction.REPORT)
                                    .onUnmappableCharacter(CodingErrorAction.REPORT);
        final var input = ByteBuffer.wrap(bytes);
        if (!bounded) {
            return decoder.decode(input).toString();
        }
        final var scratch = CharBuffer.allocate(8192);
        while (true) {
            final var status = decoder.decode(input, scratch, true);
            if (status.isError()) {
                status.throwException();
            }
            if (status.isUnderflow()) {
                break;
            }
            scratch.clear();
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static void main(final String[] args) throws Exception {
        final var meter = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!meter.isThreadAllocatedMemorySupported()) {
            throw new IllegalStateException("Allocation counters unavailable");
        }
        meter.setThreadAllocatedMemoryEnabled(true);
        for (final String kind : List.of("ascii", "unicode")) {
            final String text = kind.equals("ascii") ? "a".repeat(1_048_576) : "é漢🧵".repeat(262_144);
            final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            for (final boolean bounded : List.of(false, true)) {
                for (int i = 0; i < 5; i++) {
                    consumed = decode(bytes, bounded);
                }
                final long thread = Thread.currentThread().threadId();
                final long before = meter.getThreadAllocatedBytes(thread);
                final long started = System.nanoTime();
                for (int i = 0; i < 5; i++) {
                    consumed = decode(bytes, bounded);
                }
                final long elapsed = (System.nanoTime() - started) / 5;
                final long allocated = (meter.getThreadAllocatedBytes(thread) - before) / 5;
                if (!text.equals(consumed)) {
                    throw new AssertionError("Decoder changed text");
                }
                System.out.printf("%s bounded_validation=%s utf8_bytes=%d allocated_bytes=%d nanos=%d%n", kind, bounded,
                        bytes.length, allocated, elapsed);
            }
        }
    }
}
