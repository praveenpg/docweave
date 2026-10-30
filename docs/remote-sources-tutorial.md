# Tutorial: partitioning documents from S3

This tutorial configures DocWeave to partition documents stored in AWS S3 or an S3-compatible service, explains who
owns which resource, and shows how failures surface. The design is in
[remote-document-sources.md](remote-document-sources.md); the module reference is
[docweave-source-s3/README.md](../docweave-source-s3/README.md).

Status: implemented on `dev` for 0.1.0-beta.9; not yet published to Maven Central.

## 1. Add the modules

Remote sources need two things: the format modules you already use (or `io.docweave:docweave` for all formats) and the
optional S3 provider. The provider is never included or discovered automatically.

```xml
<dependency>
    <groupId>io.docweave</groupId>
    <artifactId>docweave</artifactId>
    <version>0.1.0-beta.9</version>
</dependency>
<dependency>
    <groupId>io.docweave</groupId>
    <artifactId>docweave-source-s3</artifactId>
    <version>0.1.0-beta.9</version>
</dependency>
```

Adding the dependency does nothing by itself: no client is created, no credentials are read and no request is made.

## 2. Build a resolver

A resolver turns a location string into document bytes. Configure it explicitly.

**AWS S3**, using the AWS default credential chain on purpose:

```java
S3SourceResolver resolver = S3SourceResolver.builder()
        .region("eu-west-1")
        .credentials(DefaultCredentialsProvider.builder().build())
        .defaultBucket("reports")
        .allowedBuckets(Set.of("reports"))
        .build();
```

**An S3-compatible service** (MinIO, SeaweedFS, Ceph RGW and similar), usually path-style:

```java
S3SourceResolver resolver = S3SourceResolver.builder()
        .endpoint(URI.create("https://objects.internal.example:9000"))
        .pathStyleAccess(true)
        .region("us-east-1")
        .credentials(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
        .defaultBucket("documents")
        .build();
```

**An existing client** that your application already manages:

```java
S3SourceResolver resolver = S3SourceResolver.builder()
        .client(applicationS3Client)     // never closed by the resolver
        .defaultBucket("documents")
        .build();
```

Credentials, endpoint, region and bucket policy belong to the resolver. None of them can come from a document
location, so a caller cannot redirect the resolver to another service or account.

## 3. Configure the processor

```java
DocWeave processor = DocWeave.builder()
        .remoteSources(resolver)
        .remoteTimeout(Duration.ofSeconds(30))   // finite acquisition deadline per call (default 2 minutes)
        .build();
```

`DocWeave.create()` stays local-only. A processor built without `remoteSources` rejects remote inputs with
`IllegalArgumentException` and makes no network call.

## 4. Partition

```java
PartitionOptions options = PartitionOptions.builder().maxInputBytes(50_000_000).build();

PartitionResult latest = processor.partition(DocumentSource.remote("2026/annual-report.pdf"), options);
PartitionResult exact = processor.partition(
        DocumentSource.remote("s3://reports/2026/annual-report.pdf?versionId=3HL4kqtJlcpXroDTDmJ.rmSpXd3dIbrHY"),
        options);

SourceProvenance provenance = exact.sourceProvenance();
// provider "s3", reference "s3://reports/2026/annual-report.pdf", version, entityTag, filename, bytes, sha256
```

- A relative location is a key in the default bucket, used verbatim.
- An `s3://` location is percent-decoded and may select a version.
- The logical filename (`annual-report.pdf`) drives format detection, element metadata and element IDs, so the
  elements are identical to partitioning the same bytes locally under that filename.
- `maxInputBytes` limits the bytes actually received, not only the size the server announces. An oversized object
  fails; it is never truncated.

## 5. Lifecycle and ownership

| Resource | Owner | Released by |
|---|---|---|
| `S3SourceResolver` | Your application | `resolver.close()` after every processor using it is done |
| Owned `S3Client` (built from region, credentials, endpoint) | The resolver | `resolver.close()` |
| Caller-supplied `S3Client` | Your application | You; the resolver never closes it |
| Downloaded content of one call | The processor | Automatically after parsing, on success, failure or cancellation |
| Temporary file (with `SpoolPolicy.temporaryFile(dir)`) | The processor | Deleted automatically; `dir` is yours |

The processor does not own the resolver. Use try-with-resources around the resolver in short-lived tools, or close it
when your application shuts down. A closed resolver fails later calls with `IllegalStateException`.

Content is received **in memory** by default: one exact-size copy, handed to the parser without further copying, and no
local file. Parsing needs the whole document in memory in any case. Choose `SpoolPolicy.temporaryFile(dir)` only when
you prefer to buffer the download on disk; files are created owner-only and deleted after use.

## 6. Concurrency, deadlines and cancellation

- One processor and one resolver serve concurrent calls, for example from virtual threads.
  `maxConcurrentDownloads` (default 16) bounds transfers; a call waits for a slot only until its deadline.
- `remoteTimeout` bounds acquisition, including retries and waits for a download slot. Parsing afterwards keeps its
  existing limits.
- Interrupting the calling thread cancels a download promptly, including one blocked in a socket read.
  `InterruptedIOException` is thrown and the interrupt flag is preserved. Batch deadlines (`BatchOptions`) work the
  same way because they interrupt.

## 7. Handling failures

```java
try {
    processor.partition(DocumentSource.remote(location), options);
} catch (IllegalArgumentException invalid) {
    // Invalid or unsupported location, or no resolver configured: a caller error; do not retry.
} catch (RemoteSourceException acquisition) {
    switch (acquisition.reason()) {
        case NOT_FOUND, ACCESS_DENIED -> { /* permanent for this location */ }
        case DEADLINE_EXCEEDED, UNAVAILABLE, INCOMPLETE -> { /* transient; already retried within the deadline */ }
        case INVALID_RESPONSE -> { /* the object changed during retries, or the service answered unexpectedly */ }
    }
} catch (PartitionException processing) {
    // INPUT_TOO_LARGE for oversized objects; other codes as for local documents.
} catch (InterruptedIOException cancelled) {
    // The calling thread was interrupted.
}
```

Messages never contain credentials, signed URLs, endpoints or temporary paths. Treat locations as potentially sensitive:
`DocumentSource.Remote.toString()` redacts them, and the library does not log them.

## 8. Run the example

```sh
mvn -pl docweave-all,docweave-source-s3 -am package -DskipTests \
    org.apache.maven.plugins:maven-dependency-plugin:3.10.0:build-classpath \
    -DincludeScope=runtime -Dmdep.outputFile=target/runtime-classpath.txt
DOCWEAVE_S3_CP="$(cat docweave-all/target/runtime-classpath.txt):docweave-all/target/docweave-0.1.0-beta.9.jar"
DOCWEAVE_S3_CP="$DOCWEAVE_S3_CP:$(cat docweave-source-s3/target/runtime-classpath.txt):docweave-source-s3/target/docweave-source-s3-0.1.0-beta.9.jar"

# AWS, with the default credential chain
java --class-path "$DOCWEAVE_S3_CP" examples/PartitionRemoteS3.java eu-west-1 s3://reports/2026/annual-report.pdf /tmp/elements.json

# An S3-compatible service, with explicit credentials
DOCWEAVE_S3_ACCESS_KEY=... DOCWEAVE_S3_SECRET_KEY=... \
java --class-path "$DOCWEAVE_S3_CP" examples/PartitionRemoteS3.java us-east-1 s3://documents/plain.txt /tmp/elements.json http://127.0.0.1:8333
```

## 9. Writing your own resolver

Other storage systems implement `RemoteSourceResolver` directly; no new `DocumentSource` type is needed.

```java
RemoteSourceResolver resolver = (source, context) -> {
    StoredObject object = store.lookup(parseMyLocation(source.location()));   // IllegalArgumentException if invalid
    InputStream body = object.open();                                         // your client and credentials
    try (body) {
        return context.receive(body, object.length(), SpoolPolicy.memory(), object.name(),
                received -> new SourceProvenance("my-store", object.safeReference(), object.version(), null,
                        object.name(), received.bytes(), received.sha256()));
    }
};
```

`context.receive` enforces the byte ceiling, exact length, deadline and interruption, computes SHA-256 and cleans up on
failure. Throw `RemoteSourceException` for missing objects, denied access and transport failures, and
`IllegalArgumentException` only for locations your resolver does not accept. Make the resolver thread-safe. For a
transport whose reads ignore interruption, wrap blocking calls in `context.abortOnStop(...)`.

## What is and is not qualified

The S3 provider is tested against a scripted fault endpoint, S3Mock (S3 REST and versioning, no authentication) and
SeaweedFS (enforced access control). It has **not** been tested against AWS S3. Validate IAM policies, encryption
(SSE-KMS), requester-pays and directory buckets, and throttling in your own AWS account before relying on them.
