import io.docweave.core.Element;
import io.docweave.partition.*;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.xmlbeans.XmlObject;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.ref.Reference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import javax.management.ObjectName;

/**
 * Reproducible owned nested-table memory probes. Allocation excludes source/fixture construction;
 * live histograms request full GC and observe the whole process, not exact retained object graphs.
 * JFR weights are sampled estimates. Separate fresh JVMs should run each measurement mode.
 */
class ProfileNestedTableMemory {
    private static final int WARMUPS = 10;
    private static final int CALLS = 10;
    private static final int RECORDED_CALLS = 20;
    private static final String WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static volatile PartitionResult held;
    private static volatile int consumed;

    record Fixture(String name, DocumentFormat format, byte[] bytes, int children) {}

    public static void main(final String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("generate")) {
            generate(Path.of(args[1]));
            return;
        }
        if (args.length != 3 || !List.of("allocation", "heap", "stacks").contains(args[0])) {
            throw new IllegalArgumentException(
                    "Usage: generate NEW_INPUT_DIRECTORY | allocation|heap|stacks INPUT_DIRECTORY NEW_OUTPUT_DIRECTORY");
        }
        final String mode = args[0];
        final var input = Path.of(args[1]);
        final var output = Path.of(args[2]);
        Files.createDirectory(output);
        final var fixtures = load(input);
        final var engine = PartitionEngine.create();
        final var meter = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!meter.isThreadAllocatedMemorySupported()) {
            throw new IllegalStateException("Thread allocation counters unavailable");
        }
        meter.setThreadAllocatedMemoryEnabled(true);
        if (mode.equals("heap")) {
            for (int i = 0; i < 3; i++) {
                liveBytes();
            }
        }
        final var rows = new ArrayList<Map<String, Object>>();
        for (final var fixture : fixtures) {
            final var source = DocumentSource.bytes(fixture.bytes(), fixture.name());
            final long payload = verifiedPayload(engine, source, fixture);
            for (final boolean preserve : List.of(false, true)) {
                final var options = options(fixture.format(), preserve);
                for (int i = 0; i < WARMUPS; i++) {
                    partition(engine, source, options);
                }
                held = null;
                final var row = new LinkedHashMap<String, Object>();
                row.put("fixture", fixture.name());
                row.put("preserve", preserve);
                row.put("input_bytes", fixture.bytes().length);
                row.put("source_children", fixture.children());
                row.put("preserved_children", preserve ? fixture.children() : 0);
                row.put("elements", consumed);
                row.put("descriptor_payload_units", preserve ? payload : 0);
                switch (mode) {
                case "allocation" -> row.put("allocated_bytes_per_call", allocation(meter, engine, source, options));
                case "heap" -> row.put("live_process_observations", heap(engine, source, options));
                case "stacks" ->
                    row.put("sampled_allocations",
                            stacks(engine, source, options,
                                    output.resolve(fixture.name() + (preserve ? "-preserve" : "-flatten") + ".jfr")));
                default -> throw new AssertionError(mode);
                }
                rows.add(row);
                Reference.reachabilityFence(source);
                System.out.println(fixture.name() + " preserve=" + preserve + " complete");
            }
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put("mode", mode);
        report.put("java_runtime", System.getProperty("java.runtime.version"));
        report.put("java_vendor", System.getProperty("java.vendor"));
        report.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        report.put("jvm_arguments", ManagementFactory.getRuntimeMXBean().getInputArguments());
        report.put("warmup_calls_per_case", WARMUPS);
        report.put("allocation_calls", CALLS);
        report.put("jfr_calls", RECORDED_CALLS);
        report.put("input_manifest_sha256", sha(Files.readAllBytes(input.resolve("manifest.json"))));
        report.put("tool_sha256", sha(Files.readAllBytes(Path.of("benchmarks/tools/ProfileNestedTableMemory.java"))));
        final var runtime = new ArrayList<Map<String, Object>>();
        for (final String entry :
                System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            final var path = Path.of(entry);
            if (!Files.isRegularFile(path)) {
                throw new IllegalStateException("Expected JAR runtime classpath: " + path);
            }
            runtime.add(Map.of("file", path.toAbsolutePath().toString(), "sha256", sha(Files.readAllBytes(path))));
        }
        report.put("runtime_jars", runtime);
        report.put("cases", rows);
        Files.writeString(
                output.resolve("report.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    private static PartitionOptions options(final DocumentFormat format, final boolean preserve) {
        return PartitionOptions.builder()
                .format(format)
                .nestedTableOptions(preserve ? NestedTableOptions.preserveMetadata() : NestedTableOptions.defaults())
                .markdownOptions(
                        new MarkdownOptions(true, 128, MarkdownOptions.RawHtml.EXTRACT_DOM, TableLimits.defaults()))
                .build();
    }

    /** Verification frames return before heap observations so neither checked result is retained. */
    private static long verifiedPayload(
            final PartitionEngine engine, final DocumentSource source, final Fixture fixture) throws Exception {
        final var flattened = engine.partition(source, options(fixture.format(), false));
        final var preserved = engine.partition(source, options(fixture.format(), true));
        verify(flattened, preserved, fixture.children());
        return preserved.elements()
                .stream()
                .mapToLong(element -> {
                    final Object nested = element.metadata().get("nested_tables");
                    return nested instanceof final List<?> list
                            ? list.stream().mapToLong(ProfileNestedTableMemory::payload).sum()
                            : 0;
                })
                .sum();
    }

    private static void verify(final PartitionResult baseline, final PartitionResult actual, final int count) {
        if (!baseline.warnings().equals(actual.warnings())) {
            throw new IllegalStateException("Preservation changed warnings");
        }
        final var stripped =
                actual.elements()
                        .stream()
                        .map(element -> {
                            final var metadata = new LinkedHashMap<>(element.metadata());
                            metadata.remove("nested_tables");
                            metadata.remove("nested_tables_version");
                            return new Element(element.elementId(), element.type(), element.text(), metadata);
                        })
                        .toList();
        if (!baseline.elements().equals(stripped)
                || actual.elements()
                                   .stream()
                                   .mapToInt(element
                                           -> element.metadata().get("nested_tables") instanceof final List<?> list
                                                      ? list.size()
                                                      : 0)
                                   .sum()
                           != count) {
            throw new IllegalStateException("Unexpected root output or preserved-child count");
        }
    }

    private static void partition(final PartitionEngine engine, final DocumentSource source,
            final PartitionOptions options) throws Exception {
        held = engine.partition(source, options);
        consumed = held.elements().size();
    }

    private static long[] allocation(final com.sun.management.ThreadMXBean meter, final PartitionEngine engine,
            final DocumentSource source, final PartitionOptions options) throws Exception {
        final var result = new long[CALLS];
        final long thread = Thread.currentThread().threadId();
        for (int i = 0; i < CALLS; i++) {
            final long before = meter.getThreadAllocatedBytes(thread);
            partition(engine, source, options);
            result[i] = meter.getThreadAllocatedBytes(thread) - before;
            held = null;
        }
        return result;
    }

    private static long liveBytes() throws Exception {
        final var server = ManagementFactory.getPlatformMBeanServer();
        final String histogram = (String) server.invoke(new ObjectName("com.sun.management:type=DiagnosticCommand"),
                "gcClassHistogram", new Object[] {new String[0]}, new String[] {"[Ljava.lang.String;"});
        final String total =
                histogram.lines().filter(line -> line.stripLeading().startsWith("Total")).findFirst().orElseThrow();
        return Long.parseLong(total.strip().split("\\s+")[2]);
    }

    private static List<Map<String, Object>> heap(final PartitionEngine engine, final DocumentSource source,
            final PartitionOptions options) throws Exception {
        final var rows = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 3; i++) {
            held = null;
            final long before = liveBytes();
            partition(engine, source, options);
            final long resultHeld = liveBytes();
            held = null;
            final long released = liveBytes();
            Reference.reachabilityFence(source);
            rows.add(Map.of("before_live_bytes", before, "result_held_live_bytes", resultHeld, "released_live_bytes",
                    released, "held_minus_released", resultHeld - released));
        }
        return rows;
    }

    private static Map<String, Object> stacks(final PartitionEngine engine, final DocumentSource source,
            final PartitionOptions options, final Path path) throws Exception {
        final long thread = Thread.currentThread().threadId();
        try (final var recording = new Recording(Configuration.getConfiguration("profile"))) {
            recording.enable("jdk.ObjectAllocationSample").with("throttle", "off").withStackTrace();
            recording.start();
            for (int i = 0; i < RECORDED_CALLS; i++) {
                partition(engine, source, options);
                held = null;
            }
            recording.stop();
            recording.dump(path);
        }
        final var weights = new java.util.HashMap<String, Long>();
        final var details = new java.util.HashMap<String, Long>();
        int samples = 0;
        try (final var recording = new RecordingFile(path)) {
            while (recording.hasMoreEvents()) {
                final var event = recording.readEvent();
                if (!event.getEventType().getName().equals("jdk.ObjectAllocationSample") || event.getThread() == null
                        || event.getThread().getJavaThreadId() != thread || event.getStackTrace() == null) {
                    continue;
                }
                if (samples++ == 0) {
                    continue;
                }
                final var methods = event.getStackTrace()
                                            .getFrames()
                                            .stream()
                                            .map(frame -> frame.getMethod())
                                            .map(method -> method.getType().getName() + "." + method.getName())
                                            .toList();
                final String nearest = methods.stream()
                                               .filter(method -> method.startsWith("io.docweave."))
                                               .findFirst()
                                               .orElse("<no DocWeave frame>");
                weights.merge(nearest, event.getLong("weight"), Long::sum);
                details.merge(event.getClass("objectClass").getName() + " : "
                                      + String.join(" <- ", methods.stream().limit(8).toList()),
                        event.getLong("weight"), Long::sum);
            }
        }
        return Map.of("observed_samples", samples, "first_sample_excluded", true, "nearest_frames", ranked(weights, 15),
                "allocation_stacks", ranked(details, 15), "jfr_sha256", sha(Files.readAllBytes(path)));
    }

    private static List<Map<String, Object>> ranked(final Map<String, Long> weights, final int limit) {
        return weights.entrySet()
                .stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .limit(limit)
                .map(entry
                        -> Map.<String, Object>of("frame", entry.getKey(), "estimated_weight_bytes", entry.getValue()))
                .toList();
    }

    private static long payload(final Object value) {
        if (value instanceof final String text) {
            return text.codePointCount(0, text.length());
        }
        if (value instanceof final Map<?, ?> map) {
            return 1
                    + map.entrySet()
                              .stream()
                              .mapToLong(entry -> payload(entry.getKey()) + payload(entry.getValue()))
                              .sum();
        }
        if (value instanceof final List<?> list) {
            return 1 + list.stream().mapToLong(ProfileNestedTableMemory::payload).sum();
        }
        return 1;
    }

    private static void generate(final Path directory) throws Exception {
        Files.createDirectory(directory);
        final var rows = new ArrayList<Map<String, Object>>();
        for (final String shape : List.of("wide", "deep")) {
            for (final var format : List.of(DocumentFormat.HTML, DocumentFormat.MARKDOWN, DocumentFormat.DOCX)) {
                final boolean docx = format == DocumentFormat.DOCX;
                final String content = markup(shape, docx, 0, new Random(0xD0C5A9));
                final String extension = docx ? ".docx" : format == DocumentFormat.HTML ? ".html" : ".md";
                final String name = shape + extension;
                final byte[] bytes;
                if (docx) {
                    try (final var document = new XWPFDocument(); final var output = new ByteArrayOutputStream()) {
                        final var xml = XmlObject.Factory.parse("<root xmlns:w='" + WORD + "'>" + content + "</root>");
                        try (final var from = xml.newCursor();
                                final var to = document.getDocument().getBody().newCursor()) {
                            from.toFirstChild();
                            to.toEndToken();
                            from.copyXmlContents(to);
                        }
                        document.write(output);
                        bytes = output.toByteArray();
                    }
                } else {
                    bytes = ((format == DocumentFormat.MARKDOWN ? "# Memory\n\n" : "") + content)
                                    .getBytes(StandardCharsets.UTF_8);
                }
                Files.write(directory.resolve(name), bytes);
                rows.add(Map.of("name", name, "format", format.name(), "sha256", sha(bytes), "bytes", bytes.length,
                        "children", shape.equals("wide") ? 128 : 12));
            }
        }
        Files.writeString(directory.resolve("manifest.json"),
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(
                        Map.of("kind", "Owned generated memory fixtures; not rendering evidence", "seed", 0xD0C5A9,
                                "leaf_code_points", 256, "cases", rows)));
    }

    private static String markup(final String shape, final boolean docx, final int depth, final Random random) {
        final var text = new StringBuilder();
        final int[] alphabet = "😀 &<>abcde0123456789".codePoints().toArray();
        for (int i = 0; i < 256; i++) {
            text.appendCodePoint(alphabet[random.nextInt(alphabet.length)]);
        }
        final String escaped = text.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        final var output = new StringBuilder(docx ? "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>" : "<table><tr><td>");
        output.append(escaped).append(docx ? "</w:t></w:r></w:p>" : "");
        if (shape.equals("wide") && depth == 0) {
            for (int i = 0; i < 128; i++) {
                output.append(markup(shape, docx, depth + 1, random));
            }
        } else if (shape.equals("deep") && depth < 12) {
            output.append(markup(shape, docx, depth + 1, random));
        }
        return output.append(docx ? "</w:tc></w:tr></w:tbl>" : "</td></tr></table>").toString();
    }

    private static List<Fixture> load(final Path directory) throws Exception {
        final var manifest = JSON.readTree(Files.readString(directory.resolve("manifest.json")));
        final var result = new ArrayList<Fixture>();
        for (final var row : manifest.get("cases")) {
            final String name = row.get("name").asString();
            if (!List.of("wide.html", "wide.md", "wide.docx", "deep.html", "deep.md", "deep.docx").contains(name)
                    || result.stream().anyMatch(fixture -> fixture.name().equals(name))) {
                throw new IllegalArgumentException("Unexpected/duplicate fixture name");
            }
            final byte[] bytes = Files.readAllBytes(directory.resolve(name));
            if (!sha(bytes).equals(row.get("sha256").asString()) || bytes.length != row.get("bytes").asInt()) {
                throw new IllegalArgumentException("Input fingerprint mismatch: " + name);
            }
            result.add(new Fixture(
                    name, DocumentFormat.valueOf(row.get("format").asString()), bytes, row.get("children").asInt()));
        }
        if (result.size() != 6) {
            throw new IllegalArgumentException("Expected six owned fixtures");
        }
        return List.copyOf(result);
    }

    private static String sha(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
