package io.docweave.json;

import io.docweave.partition.PdfRangeResult;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Lossless UTF-8 JSON codec for {@link PdfRangeResult}, for storing range results between partitioning and assembly.
 * Decoding returns a result equal to the encoded one: element metadata keeps its key order and its number types
 * ({@code Integer} and {@code Double} as plain JSON numbers; {@code Long}, {@code Short}, {@code Byte}, {@code Float},
 * {@code BigInteger} and {@code BigDecimal} as tagged values), so assembling decoded results gives exactly the output
 * of assembling the originals. Non-finite doubles (NaN and infinities) are tagged as well. Reads are bounded by
 * {@link #DEFAULT_MAX_ENCODED_BYTES} unless created with {@link #create(long)}. The format is versioned and specific to
 * this library; it is not the element compatibility format of {@link ElementJson}.
 *
 * <p>Requires {@code io.docweave:docweave-pdf} on the classpath, which {@code docweave-json} declares as optional.
 * Instances are stateless and thread-safe.</p>
 */
public interface PdfRangeResultJson {
    /**
     * Creates the built-in codec.
     * @return thread-safe codec with a hidden implementation
     */
    static PdfRangeResultJson create() {
        return new DefaultPdfRangeResultJson();
    }

    /** Default maximum encoded size accepted by {@code read}: 256 MiB. */
    long DEFAULT_MAX_ENCODED_BYTES = 256L * 1024 * 1024;

    /**
     * Creates a codec whose reads accept at most {@code maxEncodedBytes} of encoded input. Reading stops with
     * {@link IllegalArgumentException} as soon as the input exceeds the limit, before the rest is read or decoded;
     * the parser also bounds string length by the same limit and nesting depth to 256.
     * @param maxEncodedBytes positive maximum encoded size in bytes
     * @return thread-safe codec
     * @throws IllegalArgumentException if the limit is not positive
     */
    static PdfRangeResultJson create(final long maxEncodedBytes) {
        return new DefaultPdfRangeResultJson(maxEncodedBytes);
    }

    /**
     * Encodes a result onto a caller-owned stream, one element at a time; the stream is flushed, not closed.
     * @param result nonnull result
     * @param output open stream
     * @throws IOException if writing fails
     * @throws NullPointerException if an argument is null
     */
    void write(PdfRangeResult result, OutputStream output) throws IOException;

    /**
     * Encodes a result to bytes.
     * @param result nonnull result
     * @return UTF-8 JSON
     * @throws NullPointerException if result is null
     */
    byte[] write(PdfRangeResult result);

    /**
     * Decodes one result from a stream, which is read to its end and not closed.
     * @param input UTF-8 JSON
     * @return decoded result
     * @throws IOException if reading fails
     * @throws IllegalArgumentException if the content is not a supported range result or exceeds the read limits
     * @throws tools.jackson.core.JacksonException if the JSON is malformed
     */
    PdfRangeResult read(InputStream input) throws IOException;

    /**
     * Decodes one result from bytes.
     * @param json UTF-8 JSON
     * @return decoded result
     * @throws IllegalArgumentException if the content is not a supported range result or exceeds the read limits
     * @throws tools.jackson.core.JacksonException if the JSON is malformed
     */
    PdfRangeResult read(byte[] json);
}
