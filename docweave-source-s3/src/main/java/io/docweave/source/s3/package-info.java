/**
 * Optional S3 provider for {@link io.docweave.partition.DocumentSource.Remote} inputs: {@link
 * io.docweave.source.s3.S3SourceResolver} reads objects from AWS S3 or an explicitly configured S3-compatible
 * endpoint. This module is not part of {@code docweave-all}, creates no client or credentials unless configured, and
 * performs no network access until a remote source is partitioned.
 */
package io.docweave.source.s3;
