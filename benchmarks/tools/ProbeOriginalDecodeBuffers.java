package io.docweave.validation;

import io.docweave.core.Element;
import io.docweave.core.ElementType;
import io.docweave.json.ElementJson;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import tools.jackson.databind.json.JsonMapper;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import javax.imageio.ImageIO;

/** Explicit owned JFR allocation observation for the public provenance decoder; not a whole-heap benchmark. */
public final class ProbeOriginalDecodeBuffers {
    /** Prevents utility construction. */
    private ProbeOriginalDecodeBuffers() {
    }

    /**
     * Decodes one valid image-bearing wide-Unicode source while recording large decoder buffer allocations.
     * The same tool runs unchanged against isolated baseline and current runtime classes.
     * New-TLAB events sample renewal allocations; outside-TLAB events identify large individual objects.
     * Observed maximums are not complete allocation totals or proof of peak retained heap.
     * @param args fresh numeric report path; or baseline, candidate and fresh verification report
     * @throws Exception if generation, decoding, recording, invariants or reporting fail
     */
    public static void main(final String[] args) throws Exception {
        if (args.length == 3) {
            verify(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]));
            return;
        }
        require(args.length == 1, "Expected fresh report");
        final byte[] png = png();
        final var metadata = new LinkedHashMap<String, Object>();
        metadata.put("image_base64", Base64.getEncoder().encodeToString(png));
        metadata.put("image_mime_type", "image/png");
        final var original = new Element("owned", ElementType.Image, "owned source 😀", metadata);
        final var json = ElementJson.create();
        final String payload = json.encodeOriginals(List.of(original));
        final Path recordingPath = Files.createTempFile("docweave-original-decode-", ".jfr");
        List<Element> decoded;
        try {
            try (final var recording = new Recording()) {
                recording.enable("jdk.ObjectAllocationOutsideTLAB").withStackTrace();
                recording.enable("jdk.ObjectAllocationInNewTLAB").withStackTrace();
                recording.start();
                decoded = json.decodeOriginals(payload);
                recording.stop();
                recording.dump(recordingPath);
            }
            require(decoded.equals(List.of(original)), "Unchanged complete original");
            long maximum = 0, totalObserved = 0;
            int events = 0;
            try (final var file = new RecordingFile(recordingPath)) {
                while (file.hasMoreEvents()) {
                    final var event = file.readEvent();
                    final String type = event.getEventType().getName();
                    if (!type.equals("jdk.ObjectAllocationOutsideTLAB")
                            && !type.equals("jdk.ObjectAllocationInNewTLAB")) {
                        continue;
                    }
                    final String object = event.getClass("objectClass").getName();
                    if ((!object.equals("[B") && !object.equals("byte[]")) || event.getStackTrace() == null) {
                        continue;
                    }
                    boolean decoder = false, originals = false;
                    for (final var frame : event.getStackTrace().getFrames()) {
                        decoder |= frame.getMethod().getType().getName().equals("java.util.Base64$Decoder");
                        originals |=
                                frame.getMethod().getType().getName().equals("io.docweave.json.DefaultElementJson")
                                && frame.getMethod().getName().equals("decodeOriginals");
                    }
                    if (decoder && originals) {
                        final long bytes = event.getLong("allocationSize");
                        events++;
                        maximum = Math.max(maximum, bytes);
                        totalObserved += bytes;
                    }
                }
            }
            final var report = new LinkedHashMap<String, Object>();
            report.put("scope",
                    "Owned complete source; JFR observed Base64 decoder byte-array objects including headers. New-TLAB events are sampled. Not total allocation or retained/peak heap.");
            report.put("java", Runtime.version().toString());
            report.put("png_sha256", hash(png));
            report.put("encoded_payload_characters", payload.length());
            report.put("encoded_payload_sha256", hash(payload.getBytes(StandardCharsets.UTF_8)));
            report.put("decoded_wire_sha256", hash(json.write(decoded).getBytes(StandardCharsets.UTF_8)));
            report.put("decoded_elements", decoded.size());
            report.put("observed_decoder_byte_array_events", events);
            report.put("maximum_observed_decoder_byte_array_allocation_bytes", maximum);
            report.put("sum_observed_decoder_byte_array_allocation_bytes", totalObserved);
            Files.writeString(Path.of(args[0]),
                    JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                    StandardOpenOption.CREATE_NEW);
        } finally {
            Files.deleteIfExists(recordingPath);
        }
    }

    /**
     * Verifies unchanged complete inputs/outputs and the scoped large-buffer allocation observation.
     * @param baseline previous runtime report
     * @param candidate current runtime report
     * @param output fresh verification report
     * @throws Exception if identities or observed buffer reduction do not match
     */
    private static void verify(final Path baseline, final Path candidate, final Path output) throws Exception {
        final var mapper = JsonMapper.builder().build();
        final var before = mapper.readTree(Files.readAllBytes(baseline));
        final var after = mapper.readTree(Files.readAllBytes(candidate));
        for (final String field : List.of("png_sha256", "encoded_payload_characters", "encoded_payload_sha256",
                     "decoded_wire_sha256", "decoded_elements")) {
            require(before.get(field).equals(after.get(field)), "Unchanged full source and decoded wire");
        }
        final long previous = before.get("maximum_observed_decoder_byte_array_allocation_bytes").asLong();
        final long current = after.get("maximum_observed_decoder_byte_array_allocation_bytes").asLong();
        require(before.get("decoded_elements").asInt() == 1 && previous > 65536 && current < 65536,
                "Scoped large decoder buffer reduction");
        Files.writeString(output,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("scope",
                        "Same complete owned source and decoded wire; JFR maximum observed decoder byte-array objects including headers. Sampled events, not total allocation or peak heap.",
                        "encoded_payload_sha256", before.get("encoded_payload_sha256").asString(),
                        "decoded_wire_sha256", before.get("decoded_wire_sha256").asString(),
                        "maximum_observed_decoder_array_bytes_before", previous,
                        "maximum_observed_decoder_array_bytes_after", current, "unchanged_complete_decoded_original",
                        true))
                        + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    /**
     * Creates one valid deterministic high-entropy image and releases the owned raster.
     * @return PNG bytes
     * @throws Exception if encoding fails
     */
    private static byte[] png() throws Exception {
        final var image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
        try (final var bytes = new ByteArrayOutputStream()) {
            final var random = new Random(901);
            for (int y = 0; y < 512; y++) {
                for (int x = 0; x < 512; x++) {
                    image.setRGB(x, y, random.nextInt(0x1000000));
                }
            }
            require(ImageIO.write(image, "png", bytes), "PNG encoder");
            return bytes.toByteArray();
        } finally {
            image.flush();
        }
    }

    /**
     * Hashes complete owned content without writing image bytes or text to the report.
     * @param bytes content to identify
     * @return lowercase SHA-256
     * @throws Exception if the required digest is unavailable
     */
    private static String hash(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Rejects unsupported probe state without weakening decoding validation.
     * @param valid invariant to require
     * @param message content-free diagnostic
     */
    private static void require(final boolean valid, final String message) {
        if (!valid) {
            throw new IllegalStateException(message);
        }
    }
}
