package io.docweave.validation;

import io.docweave.DocWeave;
import io.docweave.partition.DocumentSource;
import io.docweave.partition.PartitionerRegistry;
import io.docweave.partition.PdfOptions;
import io.docweave.partition.PdfPartitioner;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Optional pinned external regression controls, without downloads, OCR or inferred accuracy goldens. */
class PdfMultilineCorpusTest {
    @Test
    void optionalPinnedNativeInputsPreserveEstablishedOutput() throws Exception {
        final String cache = System.getProperty("docweave.pdf.multiline.cache");
        final String report = System.getProperty("docweave.pdf.multiline.report");
        if (cache == null && report == null) {
            return;
        }
        assertNotNull(cache);
        assertNotNull(report);
        final var mapper = JsonMapper.builder().build();
        final var manifest =
                mapper.readTree(Files.readAllBytes(Path.of("benchmarks/review/pdf-table-corpus/manifest.json")));
        int checked = 0;
        for (final var input : manifest.get("inputs")) {
            final String filename = input.get("filename").asString();
            if (!filename.endsWith(".pdf")) {
                continue;
            }
            final byte[] bytes = Files.readAllBytes(Path.of(cache).resolve(filename));
            assertEquals(input.get("bytes").asLong(), bytes.length);
            assertEquals(input.get("sha256").asString(),
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            final var blob = MessageDigest.getInstance("SHA-1");
            blob.update(("blob " + bytes.length + "\0").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            assertEquals(input.get("git_blob_sha1").asString(), HexFormat.of().formatHex(blob.digest(bytes)));
            final var source = DocumentSource.bytes(bytes, filename);
            final var historical = processor(PdfOptions.TableDetection.RULED_AND_ALIGNED_TEXT).partition(source);
            final var multiline =
                    processor(PdfOptions.TableDetection.RULED_AND_MULTILINE_ALIGNED_TEXT).partition(source);
            assertFalse(historical.elements().isEmpty());
            assertEquals(historical.elements(), multiline.elements(), filename);
            checked++;
        }
        assertEquals(2, checked);
        Files.write(Path.of(report),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.of("scope",
                        "Pinned native regression comparison; no positive multiline or semantic accuracy oracle",
                        "runtime", Runtime.version().toString(), "inputs_checked", checked, "commit",
                        manifest.get("commit").asString(), "equal_elements", true)),
                StandardOpenOption.CREATE_NEW);
    }

    private static DocWeave processor(final PdfOptions.TableDetection mode) {
        return DocWeave.create(
                PartitionerRegistry.of(List.of(PdfPartitioner.create(PdfOptions.defaults().withTableDetection(mode)))));
    }
}
