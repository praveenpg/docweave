import io.docweave.DocWeave;
import io.docweave.json.ElementJson;
import io.docweave.partition.DocumentSource;
import io.docweave.partition.PartitionOptions;
import io.docweave.source.s3.S3SourceResolver;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Java 21-compatible standalone launcher that partitions one document from S3 or an S3-compatible service.
 *
 * <pre>
 * java --class-path "$DOCWEAVE_S3_CP" examples/PartitionRemoteS3.java REGION LOCATION OUTPUT.json [ENDPOINT]
 * </pre>
 *
 * LOCATION is {@code s3://bucket/key[?versionId=...]}. With ENDPOINT (an S3-compatible service, path-style), the
 * credentials are read explicitly from DOCWEAVE_S3_ACCESS_KEY and DOCWEAVE_S3_SECRET_KEY; without it, the AWS
 * default credential chain is used, deliberately. Nothing is written except OUTPUT.json.
 */
final class PartitionRemoteS3 {
    /** Prevents external construction of the launcher. */
    private PartitionRemoteS3() {
    }

    /**
     * Runs the launcher.
     * @param args region, location, output and optional endpoint
     * @throws Exception if configuration, acquisition, parsing or output fails
     */
    public static void main(final String[] args) throws Exception {
        if (args.length < 3 || args.length > 4) {
            throw new IllegalArgumentException("Usage: REGION LOCATION OUTPUT.json [ENDPOINT]");
        }
        final var builder = S3SourceResolver.builder().region(args[0]).maxAttempts(3);
        if (args.length == 4) {
            final String access = System.getenv("DOCWEAVE_S3_ACCESS_KEY");
            final String secret = System.getenv("DOCWEAVE_S3_SECRET_KEY");
            if (access == null || secret == null) {
                throw new IllegalStateException("Set DOCWEAVE_S3_ACCESS_KEY and DOCWEAVE_S3_SECRET_KEY");
            }
            builder.endpoint(URI.create(args[3]))
                    .pathStyleAccess(true)
                    .credentials(StaticCredentialsProvider.create(AwsBasicCredentials.create(access, secret)));
        } else {
            builder.credentials(DefaultCredentialsProvider.builder().build());
        }
        try (S3SourceResolver resolver = builder.build()) {
            final var processor =
                    DocWeave.builder().remoteSources(resolver).remoteTimeout(Duration.ofSeconds(60)).build();
            final var result = processor.partition(
                    DocumentSource.remote(args[1]), PartitionOptions.builder().maxInputBytes(50_000_000).build());
            ElementJson.create().write(Path.of(args[2]), result.elements());
            final var provenance = result.sourceProvenance();
            System.out.printf("%d elements from %s (version %s, %d bytes, sha256 %s)%n", result.elements().size(),
                    provenance.reference(), provenance.version(), provenance.bytes(), provenance.sha256());
        }
    }
}
