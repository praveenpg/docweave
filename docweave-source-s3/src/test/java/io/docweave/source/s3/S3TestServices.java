package io.docweave.source.s3;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Isolated S3-compatible services for integration tests.
 *
 * <ul>
 * <li><b>S3Mock</b> (Adobe, Apache 2.0) runs as a separate local process from the Maven-resolved executable JAR. It
 * implements the S3 REST API but does not enforce authentication or authorization. Its 5.x line needs a Java 25
 * runtime ({@code docweave.test.s3mock.java}); the library itself and these tests stay on Java 21.</li>
 * <li><b>SeaweedFS</b> is opt-in: set {@code docweave.test.seaweedfs.endpoint} and reader/denied credentials
 * (see {@code benchmarks/review/remote-sources/seaweedfs-qualification.sh}). It enforces per-identity access, so it
 * qualifies access denial.</li>
 * </ul>
 * Neither establishes AWS S3 behavior; AWS qualification is a separate, credentialed activity.
 */
final class S3TestServices {
    /** Shared S3Mock instance. */
    private static S3TestServices.Service s3mock;

    /** Prevents instantiation. */
    private S3TestServices() {
    }

    /**
     * A running service with its credentials.
     * @param name display name
     * @param endpoint http endpoint
     * @param admin credentials that may create buckets and write objects
     * @param reader credentials the resolver uses
     * @param denied credentials that may not read the test bucket, or null when the service does not enforce access
     * @param versioning whether bucket versioning is expected to work
     */
    record Service(String name, URI endpoint, AwsCredentialsProvider admin, AwsCredentialsProvider reader,
            AwsCredentialsProvider denied, boolean versioning) {
        /**
         * Creates an administrative client.
         * @return client the caller closes
         */
        S3Client adminClient() {
            return S3Client.builder()
                    .region(Region.US_EAST_1)
                    .endpointOverride(endpoint)
                    .forcePathStyle(true)
                    .credentialsProvider(admin)
                    .build();
        }
    }

    /**
     * Returns the shared S3Mock service, starting it on first use.
     * @return service
     * @throws org.opentest4j.TestAbortedException if no Java 25 runtime is configured for S3Mock
     * @throws IOException if the process cannot start
     * @throws InterruptedException if interrupted while waiting
     */
    static synchronized Service s3mock() throws IOException, InterruptedException {
        if (s3mock != null) {
            return s3mock;
        }
        final String jar = System.getProperty("docweave.test.s3mock.jar");
        final String java = System.getProperty("docweave.test.s3mock.java", "java");
        if (jar == null || !Files.exists(Path.of(jar))) {
            throw new IllegalStateException("S3Mock executable not found; run the tests through Maven");
        }
        if (javaFeature(java) < 25) {
            throw new org.opentest4j.TestAbortedException(
                    "S3Mock 5.x needs Java 25: set -Ddocweave.test.s3mock.java=<java 25 executable>");
        }
        final int port = freePort();
        final Path root = Files.createTempDirectory("docweave-s3mock-");
        final Path log = Files.createTempFile("docweave-s3mock-", ".log");
        final Process process = new ProcessBuilder(
                List.of(java, "-Xmx512m", "-jar", jar, "--http.port=" + port, "--server.port=" + freePort(),
                        "--com.adobe.testing.s3mock.store.root=" + root, "--spring.main.banner-mode=off"))
                                        .redirectErrorStream(true)
                                        .redirectOutput(log.toFile())
                                        .start();
        Runtime.getRuntime().addShutdownHook(new Thread(process::destroy, "s3mock-stop"));
        final URI endpoint = URI.create("http://127.0.0.1:" + port);
        waitUntilReady(process, endpoint, log);
        final var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("s3mock", "s3mock-secret"));
        s3mock = new Service("S3Mock 5.2.3", endpoint, credentials, credentials, null, true);
        return s3mock;
    }

    /**
     * Returns the opt-in SeaweedFS service, or null when not configured.
     * @return service or null
     */
    static Service seaweedfs() {
        final String endpoint = System.getProperty("docweave.test.seaweedfs.endpoint");
        if (endpoint == null || endpoint.isBlank()) {
            return null;
        }
        return new Service("SeaweedFS", URI.create(endpoint), credentials("admin"), credentials("reader"),
                credentials("denied"), Boolean.getBoolean("docweave.test.seaweedfs.versioning"));
    }

    /**
     * Lists configured services: S3Mock (when a Java 25 runtime is available) and SeaweedFS (when configured).
     * @return services
     * @throws IOException if S3Mock cannot start
     * @throws InterruptedException if interrupted
     */
    static List<Service> available() throws IOException, InterruptedException {
        final List<Service> services = new ArrayList<>();
        try {
            services.add(s3mock());
        } catch (final org.opentest4j.TestAbortedException unavailable) {
            if (Boolean.getBoolean("docweave.test.s3mock.required")) {
                throw new IllegalStateException(unavailable.getMessage());
            }
        }
        final Service seaweed = seaweedfs();
        if (seaweed != null) {
            services.add(seaweed);
        }
        return services;
    }

    /**
     * Reads static credentials for a SeaweedFS identity from system properties.
     * @param identity identity name
     * @return provider
     */
    private static AwsCredentialsProvider credentials(final String identity) {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(System.getProperty("docweave.test.seaweedfs." + identity + ".accessKey"),
                        System.getProperty("docweave.test.seaweedfs." + identity + ".secretKey")));
    }

    /**
     * Polls until S3Mock answers.
     * @param process process
     * @param endpoint endpoint
     * @param log log file
     * @throws IOException if the process exits or never becomes ready
     * @throws InterruptedException if interrupted
     */
    private static void waitUntilReady(final Process process, final URI endpoint, final Path log)
            throws IOException, InterruptedException {
        final HttpClient client = HttpClient.newHttpClient();
        final long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IOException("S3Mock exited early; see " + log);
            }
            try {
                final var response = client.send(
                        HttpRequest.newBuilder(endpoint).GET().build(), HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() < 500) {
                    return;
                }
            } catch (final IOException notYet) {
                // Not listening yet.
            }
            Thread.sleep(250);
        }
        process.destroy();
        throw new IOException("S3Mock did not start; see " + log);
    }

    /**
     * Returns the feature version of a Java executable.
     * @param java executable
     * @return feature version, or 0 when it cannot be determined
     */
    private static int javaFeature(final String java) {
        try {
            final Process process = new ProcessBuilder(java, "-version").redirectErrorStream(true).start();
            final String output = new String(process.getInputStream().readAllBytes());
            process.waitFor();
            final var matcher = Pattern.compile("version \"(\\d+)").matcher(output);
            return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
        } catch (final IOException | InterruptedException | NumberFormatException failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return 0;
        }
    }

    /**
     * Finds a free loopback port.
     * @return port
     * @throws IOException if none is available
     */
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
