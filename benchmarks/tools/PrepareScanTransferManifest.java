package io.docweave.validation;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Freezes complete source/reference identities for preselected new scan pages before any recognition. */
public final class PrepareScanTransferManifest {
    /** Prevents construction of the preparation utility. */
    private PrepareScanTransferManifest() {
    }

    /**
     * Validates original Git blob identities and reuses the unchanged strict JVM reference/DPI parser.
     * No OCR output is read and no source can be omitted because of recognition difficulty.
     * @param args frozen selection, source cache and fresh generated manifest
     * @throws Exception if source pins, XML/reference metadata, source DPI or fresh output fail
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Expected selection, cache and fresh manifest");
        }
        final var mapper = JsonMapper.builder().build();
        final var selection = mapper.readTree(Files.readAllBytes(Path.of(args[0])));
        final var profilesPath = Path.of(selection.get("profiles_manifest").asString());
        require(hash("SHA-256", Files.readAllBytes(profilesPath))
                        .equals(selection.get("profiles_manifest_sha256").asString()),
                "Profile protocol changed");
        final var profiles = mapper.readTree(Files.readAllBytes(profilesPath)).get("profiles");
        final var harness = Class.forName("io.docweave.validation.PdfHistoricalTextCorpusTest");
        final var referenceReader = harness.getDeclaredMethod("reference", byte[].class);
        final var joined = harness.getDeclaredMethod("joined", List.class);
        final var resolution = harness.getDeclaredMethod("sourceResolution", Path.class);
        referenceReader.setAccessible(true);
        joined.setAccessible(true);
        resolution.setAccessible(true);
        final var cases = new ArrayList<Map<String, Object>>();
        for (final var selected : selection.get("cases")) {
            final String id = selected.get("id").asString();
            require(id.matches("[a-z0-9_]+"), "Unsafe case identity");
            final var inputs = new ArrayList<Map<String, Object>>();
            for (final var input : selected.get("inputs")) {
                final String name = input.get("filename").asString();
                require(Path.of(name).getFileName().toString().equals(name), "Unsafe input name");
                final var bytes = Files.readAllBytes(Path.of(args[1], name));
                require(bytes.length == input.get("bytes").asLong(), "Source length changed");
                final var digest = MessageDigest.getInstance("SHA-1");
                digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
                require(HexFormat.of().formatHex(digest.digest(bytes)).equals(input.get("git_blob_sha1").asString()),
                        "Source Git identity changed");
                inputs.add(Map.of("filename", name, "bytes", bytes.length, "sha256", hash("SHA-256", bytes),
                        "git_blob_sha1", input.get("git_blob_sha1").asString()));
            }
            final var reference = referenceReader.invoke(null, Files.readAllBytes(Path.of(args[1], id + ".xml")));
            final var lines = (List<?>) read(reference, "lines");
            final String text = (String) joined.invoke(null, lines);
            final int dpi = (int) resolution.invoke(null, Path.of(args[1], id + ".tif"));
            require(dpi >= 70 && dpi <= 600, "Source DPI cannot use the existing fixed-resolution corpus path");
            final var row = new LinkedHashMap<String, Object>();
            row.put("id", id);
            row.put("year", selected.get("year").asInt());
            row.put("image", id + ".tif");
            row.put("xml", id + ".xml");
            row.put("width", read(reference, "width"));
            row.put("height", read(reference, "height"));
            row.put("expected_lines", lines.size());
            row.put("reference_code_points", text.codePointCount(0, text.length()));
            row.put("reference_text_sha256", hash("SHA-256", text.getBytes(StandardCharsets.UTF_8)));
            row.put("source_dpi", dpi);
            row.put("inputs", inputs);
            cases.add(row);
        }
        require(cases.size() == 2 && profiles.size() == 2, "Complete two-page/two-model transfer required");
        Files.writeString(Path.of(args[2]),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("selection_sha256",
                        hash("SHA-256", Files.readAllBytes(Path.of(args[0]))), "reference_parser_sha256",
                        hash("SHA-256",
                                Files.readAllBytes(Path.of(
                                        "docweave-validation/src/test/java/io/docweave/validation/PdfHistoricalTextCorpusTest.java"))),
                        "scope",
                        "All preselected new pages, original labels and fixed model profiles; generated before OCR; no repaired line order",
                        "profiles", profiles, "cases", cases))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Reads an unchanged private reference record accessor without altering its result.
     * @param reference strict parsed reference
     * @param accessor fixed numeric/list accessor name
     * @return original accessor value
     * @throws Exception if the frozen parser contract changes
     */
    private static Object read(final Object reference, final String accessor) throws Exception {
        final var method = reference.getClass().getDeclaredMethod(accessor);
        method.setAccessible(true);
        return method.invoke(reference);
    }

    /**
     * Hashes complete original or protocol bytes.
     * @param algorithm SHA-256 or original Git SHA-1
     * @param bytes exact bytes
     * @return lowercase digest
     * @throws Exception if a digest is unavailable
     */
    private static String hash(final String algorithm, final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
    }

    /**
     * Rejects changed protocols without silently selecting another page.
     * @param condition required invariant
     * @param message content-free diagnostic
     */
    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
