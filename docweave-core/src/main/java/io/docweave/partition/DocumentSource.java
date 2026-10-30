package io.docweave.partition;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A document input, independent of its detected format.
 * {@link File}, {@link Bytes} and {@link Text} are local inputs. {@link Remote} names a document held elsewhere by
 * an opaque location; it is resolved into a local input by the {@link RemoteSourceResolver} explicitly configured
 * on the processor ({@link io.docweave.DocWeave#builder()}). Constructing any source performs no I/O.
 * File sources retain a path and open it on each partition call; bytes are copied into an immutable
 * snapshot; text is already decoded Unicode. A Text source can contain any supported decoded text format.
 * Native loading reuses valid decoded Text content rather than encoding and decoding the whole
 * String. Its byte budget and diagnostic footprint remain the UTF-8 size including a leading BOM;
 * Unicode validation and bounded routing previews still apply. Leading-BOM removal may copy text.
 * File loading uses the open channel's size as an allocation hint, checks actual bytes against
 * the configured limit, and reads until EOF. Files changed during a call are not snapshotted
 * atomically; a growth or truncation may change the result or exceed the byte budget.
 * This does not make partitioning streaming: parsers and output collections may retain full content.
 * Filename hints affect routing and deterministic element IDs, but are not opened for byte/text sources.
 * All source variants can be shared across threads; concurrent changes to a file remain the caller's concern.
 *
 * <p>Compatibility: {@link Remote} was added in 0.1.0-beta.9. Exhaustive {@code switch} statements over this
 * sealed interface in consumer code must add a {@code Remote} case (or a default branch) to compile.</p>
 */
public sealed interface DocumentSource {
    /**
     * References a file without opening it yet.
     * @param path local file, opened during partitioning
     * @return a reusable file source
     * @throws NullPointerException if path is null
     */
    static DocumentSource of(final Path path) {
        return new File(path);
    }

    /**
     * Wraps decoded Unicode content with no filename hint.
     * @param content supported text-format content
     * @return an immutable text source
     * @throws NullPointerException if content is null
     */
    static DocumentSource text(String content) {
        return new Text(content, null);
    }

    /**
     * Wraps decoded Unicode content with a logical filename.
     * @param content supported text-format content
     * @param filename optional routing/metadata name; null omits filename metadata
     * @return an immutable text source; byte limits use its UTF-8 footprint
     * @throws NullPointerException if content is null
     */
    static DocumentSource text(String content, final String filename) {
        return new Text(content, filename);
    }

    /**
     * Copies input bytes. Mutation of the caller's array cannot alter later results.
     * @param content encoded document bytes
     * @param filename optional routing/metadata name
     * @return an immutable byte source
     * @throws NullPointerException if content is null
     */
    static DocumentSource bytes(byte[] content, final String filename) {
        return new Bytes(content, filename);
    }

    /**
     * Names a remote document by an opaque location. Core never interprets the location: it passes it unchanged
     * to the processor's configured {@link RemoteSourceResolver}, which defines and validates its own syntax
     * (for example a bucket-relative key or a provider URI). No I/O, parsing or credential lookup happens here.
     * @param location provider-defined location; validated only by the resolver
     * @return an immutable remote source
     * @throws NullPointerException if location is null
     */
    static DocumentSource remote(final String location) {
        return new Remote(location);
    }

    /**
     * Local file reference; file contents are not snapshotted.
     * @param path path to open during partitioning
     */
    record File(Path path) implements DocumentSource {
        /**
         * Validates a path without doing I/O.
         * @param path nonnull file reference
         * @throws NullPointerException if path is null
         */
        public File {
            Objects.requireNonNull(path, "path");
        }
    }

    /**
     * Already-decoded Unicode, unaffected by byte-decoding charset options or HTML meta charsets.
     * A leading Unicode BOM is removed during loading. Invalid unpaired surrogates fail partitioning.
     * @param content decoded document content
     * @param filename optional logical filename
     */
    record Text(String content, String filename) implements DocumentSource {
        /**
         * Validates decoded content; actual format detection happens during partitioning.
         * @param content nonnull Unicode string
         * @param filename optional logical filename
         * @throws NullPointerException if content is null
         */
        public Text {
            Objects.requireNonNull(content, "content");
        }
    }

    /**
     * Opaque reference to a document held outside the process, resolved by a configured
     * {@link RemoteSourceResolver} at partition time. Each partition call resolves again; no content is cached,
     * so a mutable remote object may yield newer content on a later call. {@link #toString()} does not reveal the
     * location, because object names can be sensitive.
     * @param location provider-defined location string, passed to the resolver unchanged
     */
    record Remote(String location) implements DocumentSource {
        /**
         * Rejects null only; all other validation belongs to the resolver.
         * @param location nonnull provider-defined location
         * @throws NullPointerException if location is null
         */
        public Remote {
            Objects.requireNonNull(location, "location");
        }

        /**
         * Describes the source without its location.
         * @return a redacted description containing only the location length
         */
        @Override
        public String toString() {
            return "Remote[location=<redacted, " + location.length() + " chars>]";
        }
    }

    /**
     * Immutable encoded byte snapshot. Construction and accessor calls copy the array.
     * @param content bytes to snapshot
     * @param filename optional logical filename
     */
    record Bytes(byte[] content, String filename) implements DocumentSource {
        /**
         * Takes ownership of a copy; no file is opened.
         * @param content nonnull encoded bytes
         * @param filename optional logical filename
         * @throws NullPointerException if content is null
         */
        public Bytes {
            content = Objects.requireNonNull(content, "content").clone();
        }

        /**
         * Copies the snapshot for the caller.
         * @return a defensive copy of the encoded document
         */
        @Override
        public byte[] content() {
            return content.clone();
        }

        /**
         * Measures the snapshot without copying it.
         * @return byte count without allocating a defensive copy
         */
        public int size() {
            return content.length;
        }
    }
}
