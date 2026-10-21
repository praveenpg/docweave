import dev.docweave.DocWeave;
import dev.docweave.core.ElementType;
import dev.docweave.partition.DocumentSource;
import dev.docweave.partition.PartitionException;
import dev.docweave.partition.PartitionResult;
import dev.docweave.partition.PartitionerRegistry;
import dev.docweave.partition.PdfOptions;
import dev.docweave.partition.PdfPartitioner;

import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

/** Single-call packaged native PDF resource observation; no throughput or corpus-accuracy claim. */
public final class MeasureNativePdfResources {
    /** Prevents construction of this explicit command-line probe. */
    private MeasureNativePdfResources() {
    }

    /**
     * Extracts one complete pinned original with default or explicit oriented-line native options.
     * Run each source in its own JVM with an explicit heap cap; no warmup, OCR or model invocation occurs.
     * Peak heap-pool usages are summed after extraction and are not a simultaneous process heap peak.
     * Linux VmHWM, when available, includes JVM startup and native memory in this isolated process.
     * Typed extraction failures remain observations with absent output counts; their codes are recorded.
     * @param args original PDF, expected SHA256, fresh properties report and optional ORIENTED_LINES profile
     * @throws Exception if identity, extraction or report creation fails
     */
    public static void main(final String[] args) throws Exception {
        if ((args.length != 3 && args.length != 4) || Files.exists(Path.of(args[2]))
                || args.length == 4 && !args[3].equals("ORIENTED_LINES")) {
            throw new IllegalArgumentException(
                    "Expected pinned original, SHA256, fresh report and optional ORIENTED_LINES");
        }
        final var source = Path.of(args[0]);
        final var digest = MessageDigest.getInstance("SHA-256");
        try (final var input = new DigestInputStream(Files.newInputStream(source), digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        final var identity = HexFormat.of().formatHex(digest.digest());
        if (!identity.equals(args[1])) {
            throw new IllegalArgumentException("Source identity mismatch");
        }
        final long started = System.nanoTime();
        PartitionResult result = null;
        PartitionException failure = null;
        try {
            final var engine =
                    args.length == 3
                            ? DocWeave.create()
                            : DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(
                                      PdfOptions.defaults().withTextLayout(PdfOptions.TextLayout.ORIENTED_LINES)))));
            result = engine.partition(DocumentSource.of(source));
        } catch (final PartitionException rejected) {
            failure = rejected;
        }
        final long elapsed = System.nanoTime() - started;
        final var report = new Properties();
        report.setProperty("source_sha256", identity);
        report.setProperty("native_profile", args.length == 3 ? "DEFAULT_DISCOVERY" : args[3]);
        report.setProperty("input_bytes", Long.toString(Files.size(source)));
        report.setProperty("outcome", failure == null ? "SUCCESS" : failure.code().name());
        report.setProperty("elapsed_nanos_including_discovery", Long.toString(elapsed));

        report.setProperty("java_runtime", System.getProperty("java.runtime.version"));
        report.setProperty("max_heap_bytes", Long.toString(Runtime.getRuntime().maxMemory()));
        report.setProperty(
                "heap_pool_peak_used_sum_bytes", Long.toString(ManagementFactory.getMemoryPoolMXBeans()
                                                                 .stream()
                                                                 .filter(pool -> pool.getType() == MemoryType.HEAP)
                                                                 .mapToLong(pool -> pool.getPeakUsage().getUsed())
                                                                 .sum()));
        if (result != null) {
            report.setProperty("partition_elapsed_nanos", Long.toString(result.elapsed().toNanos()));
            report.setProperty("elements", Integer.toString(result.elements().size()));
            report.setProperty("warnings", Integer.toString(result.warnings().size()));
            for (final var type : ElementType.values()) {
                long count = 0;
                for (final var element : result.elements()) {
                    if (element.type() == type) {
                        count++;
                    }
                }
                report.setProperty("elements_" + type.name(), Long.toString(count));
            }
        }
        final var status = Path.of("/proc/self/status");
        if (Files.isReadable(status)) {
            try (final var lines = Files.lines(status)) {
                lines.filter(line -> line.startsWith("VmHWM:"))
                        .findFirst()
                        .ifPresent(line -> report.setProperty("linux_process_vm_hwm", line.substring(6).strip()));
            }
        }
        try (final var output = Files.newOutputStream(Path.of(args[2]), StandardOpenOption.CREATE_NEW)) {
            report.store(output, "Complete-source native PDF observation; source text excluded");
        }
        if (failure != null) {
            throw failure;
        }
    }
}
