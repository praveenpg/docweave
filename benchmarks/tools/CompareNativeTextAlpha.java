package dev.docweave.validation;

import dev.docweave.DocWeave;
import dev.docweave.core.Element;
import dev.docweave.core.ElementType;
import dev.docweave.partition.DocumentSource;
import dev.docweave.partition.PartitionException;
import dev.docweave.partition.PartitionerRegistry;
import dev.docweave.partition.PdfOptions;
import dev.docweave.partition.PdfPartitioner;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Complete original-document regression of explicit text-alpha selection; no source strings enter numeric reports. */
public final class CompareNativeTextAlpha {
    /** Prevents standalone utility construction. */
    private CompareNativeTextAlpha() {
    }

    /**
     * Runs each pinned complete PDF with all three policies and both native/source-tagged factories.
     * Sources are never cropped or reconstructed. Every typed failure stays in the Cartesian product.
     * Exact equality removes only the declared rendering-policy metadata key; no text/geometry normalization occurs.
     * @param args four-column id/path/length/SHA256 input TSV and NEW numeric report
     * @throws Exception if original identities, duplicate sources or fresh-output requirements fail
     */
    public static void main(final String[] args) throws Exception {
        require(args.length == 2 && !Files.exists(Path.of(args[1])), "Expected pinned inputs and NEW report");
        final var rows = new ArrayList<Map<String, Object>>();
        final var ids = new java.util.HashSet<String>();
        for (final String line : Files.readAllLines(Path.of(args[0]))) {
            final var pin = line.split("\t", -1);
            require(pin.length == 4 && pin[0].matches("[a-z0-9-]+") && pin[3].matches("[a-f0-9]{64}")
                            && ids.add(pin[0]),
                    "Invalid or repeated source identity");
            final var path = Path.of(pin[1]);
            require(Files.size(path) == Long.parseLong(pin[2]) && Files.size(path) <= 10 * 1024 * 1024,
                    "Original length changed or exceeds10MiB");
            final var bytes = Files.readAllBytes(path);
            require(hash(bytes).equals(pin[3]), "Original SHA256 changed");
            for (final boolean tagged : List.of(false, true)) {
                List<Element> control = null;
                String controlFailure = null;
                for (final var policy : List.of(PdfOptions.TextRendering.ALL, PdfOptions.TextRendering.PAINTED_MODES,
                             PdfOptions.TextRendering.NONZERO_ALPHA_PAINTED_MODES)) {
                    final var record = new LinkedHashMap<String, Object>();
                    record.put("case", pin[0])
                    ;
                    record.put("source_sha256", pin[3])
                    ;
                    record.put("factory", tagged ? "SOURCE_TAGGED" : "NATIVE")
                    ;
                    record.put("text_rendering", policy.name())
                    ;
                    final var options = PdfOptions.defaults().withTableCellCoordinates(true).withTextRendering(policy);
                    final var partitioner =
                            tagged ? PdfPartitioner.createWithTaggedTables(options) : PdfPartitioner.create(options);
                    try {
                        final var result = DocWeave.create(PartitionerRegistry.of(List.of(partitioner)))
                                                   .partition(DocumentSource.bytes(bytes, pin[0] + ".pdf"));
                        final var elements = result.elements();
                        record.put("outcome", "SUCCESS")
                        ;
                        record.put("elements", elements.size())
                        ;
                        record.put("tables",
                                elements.stream().filter(element -> element.type() == ElementType.Table).count())
                        ;
                        record.put("text_utf16_units",
                                elements.stream().mapToLong(element -> element.text().length()).sum())
                        ;
                        record.put(
                                "warnings", result.warnings().stream().map(warning -> warning.code().name()).toList())
                        ;
                        final var comparable = elements.stream().map(CompareNativeTextAlpha::withoutPolicy).toList();
                        if (policy == PdfOptions.TextRendering.ALL) {
                            control = comparable;
                        }
                        record.put("all_policy_control_available", control != null)
                        ;
                        record.put("exact_elements_equal_to_all_except_policy",
                                control != null && comparable.equals(control))
                        ;
                    } catch (final PartitionException failure) {
                        record.put("outcome", failure.code().name())
                        ;
                        if (policy == PdfOptions.TextRendering.ALL) {
                            controlFailure = failure.code().name();
                        }
                    }
                    if (controlFailure != null) {
                        record.put("all_policy_control_failure", controlFailure)
                        ;
                    }
                    rows.add(record);
                }
            }
        }
        require(rows.size() == ids.size() * 6, "Incomplete source/factory/policy population");
        Files.writeString(Path.of(args[1]),
                JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("runtime",
                        Runtime.version().toString(), "input_tsv_sha256", hash(Files.readAllBytes(Path.of(args[0]))),
                        "scope",
                        "All complete original sources and factories; exact output regression except declared policy metadata; no independent alpha/visibility oracle or accuracy claim",
                        "results", rows))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Removes only the explicitly varied provenance key; preserves all source text, IDs, geometry and other metadata.
     * @param element original emitted value
     * @return comparable immutable value
     */
    private static Element withoutPolicy(final Element element) {
        final var metadata = new LinkedHashMap<>(element.metadata());
        metadata.remove("pdf_native_text_rendering_policy");
        return new Element(element.elementId(), element.type(), element.text(), metadata);
    }

    /**
     * Hashes exact evaluated bytes without source-text diagnostics.
     * @param bytes complete source/protocol
     * @return lowercase SHA256
     * @throws Exception if hashing fails
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Rejects input/protocol changes without partial source selection.
     * @param condition required invariant
     * @param message bounded content-free diagnostic
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
