package io.docweave.source.s3;

import io.docweave.DocWeave;
import io.docweave.partition.DocumentSource;
import io.docweave.partition.PartitionException;
import io.docweave.partition.PartitionOptions;
import io.docweave.partition.RemoteSourceException;
import io.docweave.partition.SpoolPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Transport faults against a scripted local endpoint: error mapping, bounded retries with generation pinning,
 * incomplete and oversized bodies, deadlines, cancellation, concurrency limits, cleanup and client ownership.
 * Secrets used here are fake and checked never to appear in failures.
 */
class S3SourceResolverFaultTest {
    /** Fake secret key that must never appear in failures. */
    private static final String SECRET = "fake-secret-key-0123456789";

    /** Content used by successful replies. */
    private static final byte[] TEXT = "Remote heading\n\nRemote paragraph body.".getBytes(StandardCharsets.UTF_8);

    @TempDir Path spool;

    /** Scripted endpoint. */
    private ScriptedS3Server server;

    /** Resolvers to close. */
    private final List<S3SourceResolver> resolvers = new ArrayList<>();

    @AfterEach
    void stop() {
        resolvers.forEach(S3SourceResolver::close);
        if (server != null) {
            server.close();
        }
    }

    @Test
    void missingAndDeniedObjectsAreTypedPermanentFailuresWithoutSecrets() throws Exception {
        server = new ScriptedS3Server().then(ScriptedS3Server.failure(404, "NoSuchKey"),
                ScriptedS3Server.failure(403, "AccessDenied"), ScriptedS3Server.failure(404, "NoSuchBucket"));
        final var processor = processor(builder().maxAttempts(3));
        final var missing =
                assertThrows(RemoteSourceException.class, () -> partition(processor, "s3://docs/missing.txt"));
        assertEquals(RemoteSourceException.Reason.NOT_FOUND, missing.reason());
        final var denied =
                assertThrows(RemoteSourceException.class, () -> partition(processor, "s3://docs/secret.txt"));
        assertEquals(RemoteSourceException.Reason.ACCESS_DENIED, denied.reason());
        final var bucket = assertThrows(RemoteSourceException.class, () -> partition(processor, "s3://nobucket/a.txt"));
        assertEquals(RemoteSourceException.Reason.NOT_FOUND, bucket.reason());
        assertEquals(3, server.requests.size(), "missing and denied objects are not retried");
        for (final Exception failure : List.of(missing, denied, bucket)) {
            assertNoSecrets(failure);
        }
        assertEquals("true", server.requests.get(0).get("authorization"), "requests are signed");
    }

    @Test
    void transientFailuresAreRetriedWithinTheLimitAndThenSucceed() throws Exception {
        server = new ScriptedS3Server().then(ScriptedS3Server.failure(500, "InternalError"),
                ScriptedS3Server.failure(503, "SlowDown"), ScriptedS3Server.object(TEXT, null, "\"etag-1\""));
        final var result = partition(processor(builder().maxAttempts(3)), "s3://docs/report.txt");
        assertEquals("Remote heading", result.elements().get(0).text());
        assertEquals(3, server.requests.size());
        final var provenance = result.sourceProvenance();
        assertEquals("s3", provenance.provider());
        assertEquals("s3://docs/report.txt", provenance.reference());
        assertEquals("etag-1", provenance.entityTag());
        assertEquals(TEXT.length, provenance.bytes());
    }

    @Test
    void exhaustedRetriesReportUnavailableAndRespectTheAttemptLimit() throws Exception {
        server = new ScriptedS3Server().always(ScriptedS3Server.failure(503, "SlowDown"));
        final var failure = assertThrows(RemoteSourceException.class,
                () -> partition(processor(builder().maxAttempts(2)), "s3://docs/report.txt"));
        assertEquals(RemoteSourceException.Reason.UNAVAILABLE, failure.reason());
        assertEquals(2, server.requests.size());
    }

    @Test
    void retriesArePinnedToTheFirstVersionOrEntityTag() throws Exception {
        server = new ScriptedS3Server().then(
                ScriptedS3Server.truncated(TEXT.length, 5), ScriptedS3Server.object(TEXT, "v-7", "\"e\""));
        final var versioned = partition(processor(builder()), "s3://docs/a.txt");
        assertEquals("v-7", versioned.sourceProvenance().version());
        server.close();

        server = new ScriptedS3Server().then(exchange -> {
            exchange.getResponseHeaders().add("x-amz-version-id", "v-1");
            ScriptedS3Server.truncated(TEXT.length, 5).write(exchange);
        }, ScriptedS3Server.object(TEXT, "v-1", "\"e\""));
        partition(processor(builder()), "s3://docs/a.txt");
        assertTrue(server.requests.get(1).get("query").contains("versionId=v-1"), "retry requests the same version");
        server.close();

        server = new ScriptedS3Server().then(exchange -> {
            exchange.getResponseHeaders().add("ETag", "\"gen-1\"");
            ScriptedS3Server.truncated(TEXT.length, 5).write(exchange);
        }, ScriptedS3Server.failure(412, "PreconditionFailed"));
        final var changed =
                assertThrows(RemoteSourceException.class, () -> partition(processor(builder()), "s3://docs/a.txt"));
        assertEquals(RemoteSourceException.Reason.INVALID_RESPONSE, changed.reason());
        assertEquals("\"gen-1\"", server.requests.get(1).get("ifMatch"), "unversioned retry is conditional");
    }

    @Test
    void incompleteBodiesAreNeverParsedAndLeaveNoTemporaryFile() throws Exception {
        server = new ScriptedS3Server().always(ScriptedS3Server.truncated(1_000, 100));
        final var failure = assertThrows(RemoteSourceException.class,
                ()
                        -> partition(processor(builder().maxAttempts(2).spool(SpoolPolicy.temporaryFile(spool))),
                                "s3://docs/a.txt"));
        assertTrue(failure.reason() == RemoteSourceException.Reason.INCOMPLETE
                           || failure.reason() == RemoteSourceException.Reason.UNAVAILABLE,
                failure.reason().toString());
        assertEquals(2, server.requests.size());
        assertNoSpoolFiles();
    }

    @Test
    void oversizedObjectsFailWithoutRetryOrTruncationWhetherOrNotTheLengthIsDeclared() throws Exception {
        final byte[] large = new byte[5_000];
        java.util.Arrays.fill(large, (byte) 'a');
        for (final ScriptedS3Server.Reply reply :
                List.of(ScriptedS3Server.object(large, null, null), ScriptedS3Server.chunked(large))) {
            server = new ScriptedS3Server().always(reply);
            final var failure = assertThrows(PartitionException.class,
                    ()
                            -> processor(builder().spool(SpoolPolicy.temporaryFile(spool)))
                                    .partition(DocumentSource.remote("s3://docs/big.txt"),
                                            PartitionOptions.builder().maxInputBytes(4_096).build()));
            assertEquals(PartitionException.Code.INPUT_TOO_LARGE, failure.code());
            assertEquals(1, server.requests.size(), "a size failure is not retried");
            server.close();
        }
        assertNoSpoolFiles();
    }

    @Test
    void deadlineStopsAStalledBodyAndBoundsRetries() throws Exception {
        server = new ScriptedS3Server().always(ScriptedS3Server.stall());
        final var processor = DocWeave.builder()
                                      .remoteSources(
                                              track(builder().maxAttempts(5)
                                                              .socketTimeout(Duration.ofSeconds(60))
                                                              .spool(SpoolPolicy.temporaryFile(spool))
                                                              .build()))
                                      .remoteTimeout(Duration.ofMillis(800))
                                      .build();
        final long start = System.nanoTime();
        final var failure = assertThrows(RemoteSourceException.class, () -> partition(processor, "s3://docs/slow.txt"));
        assertEquals(RemoteSourceException.Reason.DEADLINE_EXCEEDED, failure.reason());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(10)) < 0,
                "the stalled socket did not hold the call");
        assertNoSpoolFiles();
    }

    @Test
    void interruptionCancelsAnInFlightDownload() throws Exception {
        server = new ScriptedS3Server().always(ScriptedS3Server.stall());
        final var processor =
                processor(builder().socketTimeout(Duration.ofSeconds(60)).spool(SpoolPolicy.temporaryFile(spool)));
        final var outcome = new AtomicReference<Throwable>();
        final Thread caller = Thread.ofVirtual().start(() -> {
            try {
                partition(processor, "s3://docs/slow.txt");
            } catch (final Throwable failure) {
                outcome.set(failure);
            }
        });
        while (server.inFlight.get() == 0) {
            Thread.sleep(10);
        }
        Thread.sleep(200);
        caller.interrupt();
        caller.join(Duration.ofSeconds(10));
        assertFalse(caller.isAlive());
        assertInstanceOf(InterruptedIOException.class, outcome.get());
        assertNoSpoolFiles();
    }

    @Test
    void concurrentDownloadsAreBoundedIndependentlyOfCallers() throws Exception {
        server = new ScriptedS3Server().always(exchange -> {
            try {
                Thread.sleep(100);
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            ScriptedS3Server.object(TEXT, null, null).write(exchange);
        });
        final var processor = processor(builder().maxConcurrentDownloads(2));
        final List<Future<?>> calls = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 12; i++) {
                final int index = i;
                calls.add(executor.submit(() -> partition(processor, "s3://docs/n" + index + ".txt")));
            }
            for (final Future<?> call : calls) {
                call.get();
            }
        }
        assertEquals(12, server.requests.size());
        assertTrue(server.maxInFlight.get() <= 2, "at most two concurrent downloads, saw " + server.maxInFlight.get());
    }

    @Test
    void ownedClientsAreClosedAndCallerClientsAreNot() throws Exception {
        server = new ScriptedS3Server().always(ScriptedS3Server.object(TEXT, null, null));
        final S3Client callerClient = S3Client.builder()
                                              .region(Region.US_EAST_1)
                                              .endpointOverride(server.endpoint())
                                              .forcePathStyle(true)
                                              .credentialsProvider(credentials())
                                              .build();
        try (callerClient) {
            final var borrowed = S3SourceResolver.builder().client(callerClient).defaultBucket("docs").build();
            DocWeave.builder().remoteSources(borrowed).build().partition(DocumentSource.remote("one.txt"));
            borrowed.close();
            borrowed.close();
            assertThrows(IllegalStateException.class,
                    ()
                            -> borrowed.resolve((DocumentSource.Remote) DocumentSource.remote("one.txt"),
                                    io.docweave.partition.ResolutionContext.create(1000, Duration.ofSeconds(5))));
            callerClient.headBucket(request -> request.bucket("docs"));
            assertEquals("HEAD", server.requests.get(server.requests.size() - 1).get("method"),
                    "the caller's client still works after the resolver closed");
        }
        final var owned = builder().build();
        owned.close();
        assertThrows(IllegalStateException.class,
                ()
                        -> owned.resolve((DocumentSource.Remote) DocumentSource.remote("s3://docs/one.txt"),
                                io.docweave.partition.ResolutionContext.create(1000, Duration.ofSeconds(5))));
    }

    @Test
    void aResponseReleaseFailureClosesTheAlreadySpooledDocument() throws Exception {
        final S3Client failingRelease = new S3Client() {
            @Override
            public ResponseInputStream<GetObjectResponse> getObject(final GetObjectRequest request) {
                final var body = new ByteArrayInputStream(new byte[] {'a', 'b', 'c'}) {
                    @Override
                    public void close() throws IOException {
                        throw new IOException("connection release failed");
                    }
                };
                return new ResponseInputStream<>(
                        GetObjectResponse.builder().contentLength(3L).build(), AbortableInputStream.create(body));
            }

            @Override
            public String serviceName() {
                return "s3";
            }

            @Override
            public void close() {
            }
        };
        final var resolver = track(S3SourceResolver.builder()
                        .client(failingRelease)
                        .defaultBucket("docs")
                        .spool(SpoolPolicy.temporaryFile(spool))
                        .build());
        final var failure = assertThrows(RemoteSourceException.class,
                () -> DocWeave.builder().remoteSources(resolver).build().partition(DocumentSource.remote("abc.txt")));
        assertEquals(RemoteSourceException.Reason.UNAVAILABLE, failure.reason());
        assertNoSecrets(failure);
        assertNoSpoolFiles();
    }

    @Test
    void configurationIsExplicitAndValidated() throws Exception {
        server = new ScriptedS3Server();
        assertThrows(IllegalStateException.class, () -> S3SourceResolver.builder().build(), "no implicit client");
        assertThrows(IllegalStateException.class,
                () -> S3SourceResolver.builder().region("us-east-1").build(), "no implicit credentials");
        assertThrows(IllegalStateException.class,
                ()
                        -> S3SourceResolver.builder()
                                .client(S3Client.builder()
                                                .region(Region.US_EAST_1)
                                                .credentialsProvider(credentials())
                                                .build())
                                .region("us-east-1")
                                .build(),
                "owned-client settings cannot reconfigure a caller client");
        assertThrows(IllegalArgumentException.class, () -> S3SourceResolver.builder().endpoint(URI.create("ftp://x")));
        assertThrows(IllegalArgumentException.class,
                () -> S3SourceResolver.builder().endpoint(URI.create("http://user:pw@host:9000")));
        assertThrows(IllegalArgumentException.class, () -> S3SourceResolver.builder().maxAttempts(0));
        assertThrows(IllegalArgumentException.class, () -> S3SourceResolver.builder().defaultBucket("Bad_Bucket"));
        assertThrows(IllegalStateException.class,
                ()
                        -> S3SourceResolver.builder()
                                .region("us-east-1")
                                .credentials(credentials())
                                .defaultBucket("docs")
                                .allowedBuckets(java.util.Set.of("other"))
                                .build());
        final var invalid = assertThrows(IllegalArgumentException.class,
                ()
                        -> builder().build().resolve(
                                (DocumentSource.Remote) DocumentSource.remote("relative-without-default"),
                                io.docweave.partition.ResolutionContext.create(10, Duration.ofSeconds(1))));
        assertFalse(invalid.getMessage().contains("relative-without-default"));
    }

    /**
     * Partitions a remote location.
     * @param processor processor
     * @param location location
     * @return result
     * @throws IOException on failure
     */
    private static io.docweave.partition.PartitionResult partition(final DocWeave processor, final String location)
            throws IOException {
        return processor.partition(DocumentSource.remote(location));
    }

    /**
     * Creates a processor around a resolver from the builder.
     * @param builder resolver builder
     * @return processor
     */
    private DocWeave processor(final S3SourceResolver.Builder builder) {
        return DocWeave.builder().remoteSources(track(builder.build())).build();
    }

    /**
     * Registers a resolver for closing.
     * @param resolver resolver
     * @return resolver
     */
    private S3SourceResolver track(final S3SourceResolver resolver) {
        resolvers.add(resolver);
        return resolver;
    }

    /**
     * Creates a builder for the scripted endpoint with fast retries.
     * @return builder
     */
    private S3SourceResolver.Builder builder() {
        return S3SourceResolver.builder()
                .region("us-east-1")
                .endpoint(server.endpoint())
                .pathStyleAccess(true)
                .credentials(credentials())
                .retryBackoff(Duration.ofMillis(20));
    }

    /**
     * Creates fake static credentials.
     * @return provider
     */
    private static StaticCredentialsProvider credentials() {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create("FAKEACCESSKEY", SECRET));
    }

    /**
     * Asserts that a failure chain mentions no credential or temporary path.
     * @param failure failure
     */
    private void assertNoSecrets(final Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            final String text = String.valueOf(current.getMessage());
            assertFalse(
                    text.contains(SECRET) || text.contains("FAKEACCESSKEY") || text.contains(spool.toString()), text);
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
