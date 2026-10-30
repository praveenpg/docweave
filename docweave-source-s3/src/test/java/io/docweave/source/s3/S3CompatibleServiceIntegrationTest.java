package io.docweave.source.s3;

import io.docweave.DocWeave;
import io.docweave.partition.DocumentSource;
import io.docweave.partition.PartitionException;
import io.docweave.partition.PartitionOptions;
import io.docweave.partition.PartitionResult;
import io.docweave.partition.RemoteSourceException;
import io.docweave.partition.SpoolPolicy;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Real S3-compatible services (see {@link S3TestServices}): objects written with the S3 API are partitioned through
 * {@link S3SourceResolver} and compared with local partitioning of the same bytes and filename. Covers provenance,
 * missing objects and buckets, access denial (services that enforce it), versions (services that support them), size
 * limits on real transfers, special-character keys, concurrency and temporary-file cleanup.
 */
class S3CompatibleServiceIntegrationTest {
    @TempDir Path spool;

    /**
     * Supplies configured services.
     * @return services
     * @throws Exception if S3Mock cannot start
     */
    static List<S3TestServices.Service> services() throws Exception {
        final List<S3TestServices.Service> services = S3TestServices.available();
        if (services.isEmpty()) {
            // Reported as skipped rather than as an empty parameterized test; docweave.test.s3mock.required=true fails.
            throw new org.opentest4j.TestAbortedException(
                    "No S3-compatible test service: set -Ddocweave.test.s3mock.java=<java 25> or configure SeaweedFS");
        }
        return services;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("services")
    void remoteObjectsPartitionExactlyLikeLocalBytesWithTheSameName(final S3TestServices.Service service)
            throws Exception {
        final String bucket = bucket(service);
        final Map<String, byte[]> objects = new LinkedHashMap<>();
        objects.put("docs/plain.txt", Files.readAllBytes(fixture("plain.txt")));
        objects.put("docs/page.html", "<h1>Title</h1><p>Body.</p><table><tr><td>a</td><td>b</td></tr></table>".getBytes(
                                              StandardCharsets.UTF_8));
        objects.put("docs/structured.docx", Files.readAllBytes(fixture("structured.docx")));
        objects.put("docs/annual report.pdf", pdf());
        try (S3Client admin = service.adminClient()) {
            objects.forEach(
                    (key, bytes) -> admin.putObject(b -> b.bucket(bucket).key(key), RequestBody.fromBytes(bytes)));
        }
        for (final SpoolPolicy policy : List.of(SpoolPolicy.memory(), SpoolPolicy.temporaryFile(spool))) {
            try (S3SourceResolver resolver = resolver(service, bucket).spool(policy).build()) {
                final DocWeave processor = DocWeave.builder().remoteSources(resolver).build();
                for (final var object : objects.entrySet()) {
                    final String name = object.getKey().substring("docs/".length());
                    final PartitionResult local =
                            DocWeave.create().partition(DocumentSource.bytes(object.getValue(), name));
                    final PartitionResult byKey = processor.partition(DocumentSource.remote(object.getKey()));
                    final PartitionResult byUri = processor.partition(
                            DocumentSource.remote("s3://" + bucket + "/" + encode(object.getKey())));
                    for (final PartitionResult remote : List.of(byKey, byUri)) {
                        assertEquals(local.elements(), remote.elements(), service.name() + " " + name + " " + policy);
                        assertEquals(local.format(), remote.format());
                        final var provenance = remote.sourceProvenance();
                        assertEquals("s3", provenance.provider());
                        assertEquals("s3://" + bucket + "/" + object.getKey(), provenance.reference());
                        assertEquals(name, provenance.filename());
                        assertEquals(object.getValue().length, provenance.bytes());
                        assertEquals(sha256(object.getValue()), provenance.sha256());
                        assertNotNull(provenance.entityTag());
                    }
                }
            }
        }
        assertNoSpoolFiles();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("services")
    void missingObjectsAndBucketsAreNotFound(final S3TestServices.Service service) throws Exception {
        final String bucket = bucket(service);
        try (S3SourceResolver resolver = resolver(service, bucket).build()) {
            final DocWeave processor = DocWeave.builder().remoteSources(resolver).build();
            final var key = assertThrows(RemoteSourceException.class,
                    () -> processor.partition(DocumentSource.remote("absent/" + UUID.randomUUID() + ".txt")));
            assertEquals(RemoteSourceException.Reason.NOT_FOUND, key.reason());
            final var missingBucket = assertThrows(RemoteSourceException.class,
                    () -> processor.partition(DocumentSource.remote("s3://absent-" + UUID.randomUUID() + "/a.txt")));
            assertFalse(missingBucket.reason() == RemoteSourceException.Reason.UNAVAILABLE,
                    "a missing bucket is not a transient failure: " + missingBucket.reason());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("services")
    void deniedIdentitiesGetAccessDenied(final S3TestServices.Service service) throws Exception {
        Assumptions.assumeTrue(service.denied() != null, service.name() + " does not enforce access control");
        final String bucket = bucket(service);
        try (S3Client admin = service.adminClient()) {
            admin.putObject(b -> b.bucket(bucket).key("private.txt"), RequestBody.fromString("private"));
        }
        try (S3SourceResolver resolver = S3SourceResolver.builder()
                        .region("us-east-1")
                        .endpoint(service.endpoint())
                        .pathStyleAccess(true)
                        .credentials(service.denied())
                        .defaultBucket(bucket)
                        .build()) {
            final var failure = assertThrows(RemoteSourceException.class,
                    ()
                            -> DocWeave.builder().remoteSources(resolver).build().partition(
                                    DocumentSource.remote("private.txt")));
            assertEquals(RemoteSourceException.Reason.ACCESS_DENIED, failure.reason());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("services")
    void explicitVersionsAndResolvedVersionsAreReported(final S3TestServices.Service service) throws Exception {
        Assumptions.assumeTrue(service.versioning(), service.name() + " is not configured for versioning");
        final String bucket = bucket(service);
        final String first;
        final String second;
        try (S3Client admin = service.adminClient()) {
            try {
                admin.putBucketVersioning(
                        b -> b.bucket(bucket).versioningConfiguration(v -> v.status(BucketVersioningStatus.ENABLED)));
            } catch (final S3Exception unsupported) {
                Assumptions.abort(service.name() + " rejected bucket versioning: HTTP " + unsupported.statusCode());
            }
            first = admin.putObject(b -> b.bucket(bucket).key("v.txt"), RequestBody.fromString("First version"))
                            .versionId();
            second = admin.putObject(b -> b.bucket(bucket).key("v.txt"), RequestBody.fromString("Second version"))
                             .versionId();
        }
        Assumptions.assumeTrue(first != null && second != null && !first.equals(second),
                service.name() + " did not return distinct version IDs");
        try (S3SourceResolver resolver = resolver(service, bucket).build()) {
            final DocWeave processor = DocWeave.builder().remoteSources(resolver).build();
            final var latest = processor.partition(DocumentSource.remote("v.txt"));
            assertEquals("Second version", latest.elements().get(0).text());
            assertEquals(second, latest.sourceProvenance().version());
            final var pinned = processor.partition(DocumentSource.remote(
                    "s3://" + bucket + "/v.txt?versionId=" + URLEncoder.encode(first, StandardCharsets.UTF_8)));
            assertEquals("First version", pinned.elements().get(0).text());
            assertEquals(first, pinned.sourceProvenance().version());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("services")
    void realOversizedTransfersFailWithoutTruncationOrLeftovers(final S3TestServices.Service service) throws Exception {
        final String bucket = bucket(service);
        try (S3Client admin = service.adminClient()) {
            admin.putObject(b -> b.bucket(bucket).key("large.txt"), RequestBody.fromString("x".repeat(300_000)));
        }
        try (S3SourceResolver resolver = resolver(service, bucket).spool(SpoolPolicy.temporaryFile(spool)).build()) {
            final var failure = assertThrows(PartitionException.class,
                    ()
                            -> DocWeave.builder().remoteSources(resolver).build().partition(
                                    DocumentSource.remote("large.txt"),
                                    PartitionOptions.builder().maxInputBytes(100_000).build()));
            assertEquals(PartitionException.Code.INPUT_TOO_LARGE, failure.code());
        }
        assertNoSpoolFiles();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("services")
    void specialCharacterKeysAndConcurrentCallsResolveIndependently(final S3TestServices.Service service)
            throws Exception {
        final String bucket = bucket(service);
        final List<String> keys = new ArrayList<>();
        try (S3Client admin = service.adminClient()) {
            for (int i = 0; i < 24; i++) {
                final String key = "dir " + i + "/naïve?#%+" + i + ".txt";
                keys.add(key);
                admin.putObject(b -> b.bucket(bucket).key(key), RequestBody.fromString("Document number " + key));
            }
        }
        try (S3SourceResolver resolver = resolver(service, bucket).maxConcurrentDownloads(4).build()) {
            final DocWeave processor = DocWeave.builder().remoteSources(resolver).build();
            final List<Future<PartitionResult>> results = new ArrayList<>();
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (final String key : keys) {
                    results.add(executor.submit(
                            () -> processor.partition(DocumentSource.remote("s3://" + bucket + "/" + encode(key)))));
                }
                for (int i = 0; i < keys.size(); i++) {
                    assertEquals("Document number " + keys.get(i), results.get(i).get().elements().get(0).text());
                }
            }
            // The relative form uses the key verbatim, including characters that the s3:// form must encode.
            assertEquals("Document number " + keys.get(3),
                    processor.partition(DocumentSource.remote(keys.get(3))).elements().get(0).text());
        }
    }

    /**
     * Creates a fresh bucket.
     * @param service service
     * @return bucket name
     */
    private static String bucket(final S3TestServices.Service service) {
        final String bucket = "docweave-it-" + UUID.randomUUID().toString().substring(0, 8);
        try (S3Client admin = service.adminClient()) {
            admin.createBucket(b -> b.bucket(bucket));
        }
        return bucket;
    }

    /**
     * Creates a resolver builder for the service's reader identity.
     * @param service service
     * @param bucket default bucket
     * @return builder
     */
    private static S3SourceResolver.Builder resolver(final S3TestServices.Service service, final String bucket) {
        return S3SourceResolver.builder()
                .region("us-east-1")
                .endpoint(service.endpoint())
                .pathStyleAccess(true)
                .credentials(service.reader())
                .defaultBucket(bucket);
    }

    /**
     * Percent-encodes a key for the s3:// form, keeping slashes.
     * @param key key
     * @return encoded key
     */
    private static String encode(final String key) {
        return URLEncoder.encode(key, StandardCharsets.UTF_8).replace("+", "%20").replace("%2F", "/");
    }

    /**
     * Resolves a shared fixture.
     * @param name file name
     * @return path
     */
    private static Path fixture(final String name) {
        return Path.of(System.getProperty("docweave.test.fixtures", "../benchmarks/fixtures"), name);
    }

    /**
     * Computes a hex SHA-256.
     * @param bytes content
     * @return digest
     * @throws Exception never in practice
     */
    private static String sha256(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Builds a one-page PDF.
     * @return bytes
     * @throws IOException if PDFBox fails
     */
    private static byte[] pdf() throws IOException {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            final var page = new PDPage();
            document.addPage(page);
            try (var stream = new PDPageContentStream(document, page)) {
                int baseline = 700;
                for (final String line : List.of("Annual report", "Revenue grew in every region.")) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(72, baseline);
                    stream.showText(line);
                    stream.endText();
                    baseline -= 20;
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    /** Asserts the spool directory is empty. */
    private void assertNoSpoolFiles() {
        try (Stream<Path> files = Files.list(spool)) {
            assertEquals(0, files.count(), "no temporary file remains");
        } catch (final IOException failure) {
            throw new AssertionError(failure);
        }
    }
}
