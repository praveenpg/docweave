import dev.docweave.partition.*;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

/** Captures sampled allocation stacks for owned fixtures; weights are estimates, not exact byte counts. */
class ProfilePartitionAllocation {
    record Input(String name, DocumentFormat format, String content) {}

    static volatile int consumed;

    public static void main(final String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: ProfilePartitionAllocation.java output-directory");
        }
        final var output = Path.of(args[0]);
        Files.createDirectories(output);
        final var inputs = List.of(new Input("ascii-txt", DocumentFormat.TXT,
                                           "This is a complete sentence for the allocation probe.\n\n".repeat(4096)),
                new Input("unicode-txt", DocumentFormat.TXT,
                        "Café 漢 🧵 is a complete sentence for the allocation probe.\n\n".repeat(4096)),
                new Input("html", DocumentFormat.HTML,
                        "<html><body>"
                                + "<p>Café 🧵 is a complete sentence.</p>".repeat(2048) + "</body></html>"),
                new Input("csv", DocumentFormat.CSV,
                        "name,value\n"
                                + "Café,🧵\n".repeat(2048)));
        final var engine = PartitionEngine.create();
        final long thread = Thread.currentThread().threadId();
        for (final var item : inputs) {
            final var source = DocumentSource.bytes(item.content().getBytes(StandardCharsets.UTF_8), item.name());
            final var options = PartitionOptions.builder().format(item.format()).build();
            for (int i = 0; i < 5; i++) {
                consumed = engine.partition(source, options).elements().size();
            }
            final var path = output.resolve(item.name() + ".jfr");
            try (final var recording = new Recording(Configuration.getConfiguration("profile"))) {
                recording.enable("jdk.ObjectAllocationSample").with("throttle", "off").withStackTrace();
                recording.start();
                for (int i = 0; i < 20; i++) {
                    consumed = engine.partition(source, options).elements().size();
                }
                recording.stop();
                recording.dump(path);
            }
            final var weights = new HashMap<String, Long>();
            final var emission = new HashMap<String, Long>();
            int samples = 0;
            try (final var recording = new RecordingFile(path)) {
                while (recording.hasMoreEvents()) {
                    final var event = recording.readEvent();
                    if (!event.getEventType().getName().equals("jdk.ObjectAllocationSample")) {
                        continue;
                    }
                    if (event.getThread() == null || event.getThread().getJavaThreadId() != thread
                            || event.getStackTrace() == null) {
                        continue;
                    }
                    // First sample can carry allocation weight from before recording began.
                    if (samples++ == 0) {
                        continue;
                    }
                    final String frame = event.getStackTrace()
                                                 .getFrames()
                                                 .stream()
                                                 .map(value -> value.getMethod())
                                                 .filter(value -> value.getType().getName().startsWith("dev.docweave."))
                                                 .map(value -> value.getType().getName() + "." + value.getName())
                                                 .findFirst()
                                                 .orElse("<no DocWeave frame in recorded stack>");
                    weights.merge(frame, event.getLong("weight"), Long::sum);
                    if (frame.equals("dev.docweave.partition.ElementCollector.add")
                            || frame.startsWith("dev.docweave.partition.ElementIdGenerator.")
                            || frame.startsWith("dev.docweave.partition.Utf8Digest.")) {
                        final String stack = event.getStackTrace()
                                                     .getFrames()
                                                     .stream()
                                                     .limit(6)
                                                     .map(value
                                                             -> value.getMethod().getType().getName() + "."
                                                                        + value.getMethod().getName())
                                                     .collect(java.util.stream.Collectors.joining(" <- "));
                        emission.merge(event.getClass("objectClass").getName() + " : " + stack, event.getLong("weight"),
                                Long::sum);
                    }
                }
            }
            System.out.printf("%s observed_samples=%d (first sample excluded from weights)%n", item.name(), samples);
            weights.entrySet()
                    .stream()
                    .sorted(java.util.Map.Entry.comparingByValue(Comparator.reverseOrder()))
                    .limit(15)
                    .forEach(value
                            -> System.out.printf("  estimated_weight_bytes=%d %s%n", value.getValue(), value.getKey()));
            System.out.println("  emission allocation stacks (sampled estimates):");
            emission.entrySet()
                    .stream()
                    .sorted(java.util.Map.Entry.comparingByValue(Comparator.reverseOrder()))
                    .limit(10)
                    .forEach(value
                            -> System.out.printf(
                                    "    estimated_weight_bytes=%d %s%n", value.getValue(), value.getKey()));
        }
    }
}
