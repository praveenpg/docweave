# docweave-source-s3

Optional `RemoteSourceResolver` for AWS S3 and explicitly configured S3-compatible services (for example MinIO,
SeaweedFS or Ceph). It lets `DocWeave` partition `DocumentSource.remote(...)` inputs held in S3. It is **not** part of
`docweave-all`, it is never discovered implicitly, and installing it makes no network call and reads no credentials.

Status: implemented on `dev` for 0.1.0-beta.9; not published to Maven Central. Requires Java 21.

```xml
<dependency>
    <groupId>io.docweave</groupId>
    <artifactId>docweave-source-s3</artifactId>
    <version>0.1.0-beta.9</version>
</dependency>
```

It adds the AWS SDK for Java 2.x (`s3` with the Apache HttpClient 5 transport). The Netty transport is excluded.

## Quick start

```java
try (S3SourceResolver resolver = S3SourceResolver.builder()
        .region("eu-west-1")
        .credentials(DefaultCredentialsProvider.builder().build())   // explicit opt-in to the AWS default chain
        .defaultBucket("reports")
        .build()) {
    DocWeave processor = DocWeave.builder()
            .remoteSources(resolver)
            .remoteTimeout(Duration.ofSeconds(30))
            .build();
    PartitionResult result = processor.partition(
            DocumentSource.remote("2026/annual-report.pdf"),
            PartitionOptions.builder().maxInputBytes(50_000_000).build());
    SourceProvenance provenance = result.sourceProvenance();      // s3://reports/2026/annual-report.pdf, version, sha256
}
```

An S3-compatible service:

```java
S3SourceResolver resolver = S3SourceResolver.builder()
        .endpoint(URI.create("https://objects.internal.example:9000"))
        .pathStyleAccess(true)
        .region("us-east-1")
        .credentials(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
        .allowedBuckets(Set.of("tenant-a-documents"))
        .defaultBucket("tenant-a-documents")
        .build();
```

A runnable example is in [`examples/PartitionRemoteS3.java`](../examples/PartitionRemoteS3.java); the
[tutorial](../docs/remote-sources-tutorial.md) explains configuration and lifecycle in detail.

## Locations

| Form | Meaning |
|---|---|
| `s3://bucket/key` | Latest version of `key` in `bucket`. The key is percent-decoded: write `%20` for a space, `%3F` for `?`, `%23` for `#`, `%25` for `%`. |
| `s3://bucket/key?versionId=ID` | That exact version. No other parameter is accepted. |
| `any/other/text` | A key in the configured `defaultBucket`, used exactly as written (no decoding), latest version. |

Invalid syntax, an invalid bucket name, an empty or over-long key, control characters, a bucket outside
`allowedBuckets`, or a relative key without `defaultBucket` throws `IllegalArgumentException` before any request. The
message never repeats the location. Locations cannot carry credentials, endpoints or ports, so callers cannot redirect
the resolver to another service.

## Configuration

| Setting | Default | Notes |
|---|---|---|
| `client(S3Client)` | — | Caller-owned client. The resolver never closes it; its own retry and timeout settings apply within each attempt. Cannot be combined with the owned-client settings below. |
| `region(String)` | required for an owned client | Signing region; S3-compatible services usually accept `us-east-1`. |
| `credentials(AwsCredentialsProvider)` | required for an owned client | No implicit discovery. Use `DefaultCredentialsProvider` deliberately, `StaticCredentialsProvider`, or `AnonymousCredentialsProvider` for public objects. |
| `endpoint(URI)` | AWS | http(s) S3-compatible endpoint without user info or query. |
| `pathStyleAccess(boolean)` | false | Most S3-compatible services need `true`. |
| `connectTimeout`, `socketTimeout` | 10 s, 30 s | Owned client only. The acquisition deadline applies regardless. |
| `defaultBucket(String)` | none | Bucket for relative keys. |
| `allowedBuckets(Set)` | no restriction | Must contain the default bucket. |
| `maxAttempts(int)` | 3 | 1–10, including the first attempt. |
| `retryBackoff(Duration)` | 200 ms | Doubles to 5 s, jittered, never beyond the remaining deadline. |
| `maxConcurrentDownloads(int)` | 16 | Per resolver; also the owned client's connection pool size. |
| `spool(SpoolPolicy)` | memory | `SpoolPolicy.temporaryFile(dir)` buffers downloads in owner-only temporary files in `dir`. |

The acquisition deadline (`DocWeave.Builder.remoteTimeout`, default 2 minutes) and the byte ceiling
(`PartitionOptions.maxInputBytes`) come from the processor and the call, not from this module.

## Behavior

- **One GetObject per attempt.** The body is read through `ResolutionContext.receive`, which counts received bytes
  against `maxInputBytes` (also without Content-Length), requires an exact match with a declared length, computes
  SHA-256, and aborts the HTTP response on deadline or interruption.
- **Retries.** HTTP 5xx, 429, network errors and incomplete bodies are retried. Missing objects (404), denied access
  (401/403), oversized objects and invalid locations are not. Later attempts are pinned to the first response's
  version ID, or to its entity tag through `If-Match`, so a retry never mixes generations of the object. A changed
  object fails with `INVALID_RESPONSE`.
- **Errors.** `RemoteSourceException` with `NOT_FOUND`, `ACCESS_DENIED`, `DEADLINE_EXCEEDED`, `INCOMPLETE`,
  `UNAVAILABLE` or `INVALID_RESPONSE`; `PartitionException` `INPUT_TOO_LARGE` for size; `InterruptedIOException` for
  cancellation. Messages include the HTTP status and S3 error code, never SDK messages, credentials, endpoints or
  temporary paths.
- **Provenance.** `PartitionResult.sourceProvenance()`: provider `s3`, reference `s3://bucket/key`, the version
  actually read (null for unversioned objects, which are mutable), the unquoted ETag (opaque, not necessarily an MD5),
  filename (last key segment), received bytes and SHA-256. Element IDs and metadata are the same as for local bytes
  with the same filename.
- **Lifecycle.** `close()` closes an owned client; a caller-supplied client stays open. Close the resolver after
  every processor using it is finished; later resolutions fail with `IllegalStateException`. Resolvers are thread-safe.

## Testing and qualification

```sh
# Unit, scripted-fault and S3Mock tests (S3Mock 5.x needs a Java 25 runtime; the tests themselves run on Java 21)
mvn -pl docweave-source-s3 -am verify -Ddocweave.test.s3mock.java=/path/to/java25/bin/java

# Access-control qualification against SeaweedFS in a throwaway loopback container
SEAWEEDFS_IMAGE=<pinned image> benchmarks/review/remote-sources/seaweedfs-qualification.sh
```

Without a Java 25 runtime the S3Mock tests are skipped (add `-Ddocweave.test.s3mock.required=true` to fail instead).
These are S3-compatible qualifications: S3Mock does not enforce authentication, and SeaweedFS covers access denial.
**No test runs against AWS S3**; IAM policies, KMS-encrypted objects, requester-pays buckets, S3 Express directory
buckets and real throttling are not qualified.
