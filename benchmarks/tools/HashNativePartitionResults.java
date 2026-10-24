package io.docweave.validation;

import io.docweave.DocWeave;
import io.docweave.partition.DocumentSource;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Complete canonical element-and-warning fingerprints for fixed native source/profile regression, without source text
 * output.
 */
public final class HashNativePartitionResults {
    /** Prevents utility construction. */
    private HashNativePartitionResults() {
    }

    /**
     * Reuses the fixed five-source/three-profile native composition, including existing compound wrapping.
     * Canonical map ordering avoids cross-process Map iteration differences. All public elements, IDs,
     * text, metadata and warnings enter each hash; no element/warning field is removed or normalized.
     * Envelope format/encoding/input-byte/timing diagnostics are outside this fingerprint; original
     * source identities are checked independently.
     * @param args private four-column source TSV and fresh numeric report
     * @throws Exception if source pins, complete population, extraction or fresh output requirements fail
     */
    public static void main(final String[] args) throws Exception {
        require(args.length == 2, "Expected fixed input TSV and fresh report");
        final var rows = Files.readAllLines(Path.of(args[0]));
        require(rows.size() == 5, "Complete five-source population required");
        final var factory = CompareNativeCompoundText.class.getDeclaredMethod("processor", String.class, boolean.class);
        factory.setAccessible(true);
        final var mapper = JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
        final var results = new ArrayList<Map<String, Object>>();
        final var seen = new java.util.HashSet<String>();
        for (final String row : rows) {
            final var fields = row.split("\t", -1);
            require(fields.length == 4 && fields[0].matches("[a-z][a-z0-9_-]{0,30}") && seen.add(fields[0]),
                    "Invalid source identity");
            final var source = Path.of(fields[1]);
            require(Files.size(source) == Long.parseLong(fields[2]) && Files.size(source) <= 16 * 1024 * 1024,
                    "Original source length changed");
            final var bytes = Files.readAllBytes(source);
            require(hash(bytes).equals(fields[3]), "Original source bytes changed");
            for (final String profile : List.of("NATIVE_LINES", "NATIVE_COLUMNS", "SOURCE_TAGGED_LINES")) {
                final var processor = (DocWeave) factory.invoke(null, profile, true);
                final var result = processor.partition(DocumentSource.bytes(bytes, fields[0] + ".pdf"));
                final var canonical =
                        mapper.writeValueAsBytes(Map.of("elements", result.elements(), "warnings", result.warnings()));
                results.add(Map.of("case", fields[0], "profile", profile, "source_sha256", fields[3], "elements",
                        result.elements().size(), "warnings", result.warnings().size(), "canonical_result_sha256",
                        hash(canonical)));
            }
        }
        require(results.size() == 15, "Incomplete source/profile population");
        Files.writeString(Path.of(args[1]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("scope",
                        "All15 fixed native source/profile results, complete public elements/IDs/text/metadata/warnings canonicalized only by map key order",
                        "runtime", Runtime.version().toString(), "source_tsv_sha256",
                        hash(Files.readAllBytes(Path.of(args[0]))), "results", results))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Hashes complete source or canonical result bytes.
     * @param bytes exact source or output bytes
     * @return lowercase SHA256
     * @throws Exception if SHA256 is unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Rejects changed populations or sources without selecting a different case.
     * @param condition required invariant
     * @param message content-free diagnostic
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
