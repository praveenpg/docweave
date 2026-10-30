package io.docweave.partition;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Where {@link ResolutionContext#receive} keeps remote content while it is being parsed. Partitioning materializes the
 * whole input in memory either way (see {@link DocumentSource}); the policy only decides whether the download itself
 * is buffered in memory or in an owner-only temporary file.
 * <ul>
 * <li>{@link #memory()} (default): no local file is written. One exact-length heap copy is handed to the parser
 * without further copying when the provider declares the length.</li>
 * <li>{@link #temporaryFile(Path)}: the download goes to a file in the given directory, created with owner-only
 * permissions where the filesystem supports them and deleted when the handle closes or acquisition fails. The parser
 * then reads it into memory under the same byte ceiling.</li>
 * </ul>
 * @param directory temporary-file directory, or null for in-memory buffering
 */
public record SpoolPolicy(Path directory) {
    /**
     * Buffers downloads in memory.
     * @return the in-memory policy
     */
    public static SpoolPolicy memory() {
        return new SpoolPolicy(null);
    }

    /**
     * Buffers downloads in temporary files in an explicit directory. No default directory is assumed.
     * @param directory existing writable directory
     * @return a temporary-file policy
     * @throws NullPointerException if directory is null
     */
    public static SpoolPolicy temporaryFile(final Path directory) {
        return new SpoolPolicy(Objects.requireNonNull(directory, "directory"));
    }

    /**
     * Tells whether downloads are buffered in memory.
     * @return true for {@link #memory()}
     */
    public boolean inMemory() {
        return directory == null;
    }

    /**
     * Describes the policy without exposing the directory path.
     * @return "memory" or "temporaryFile"
     */
    @Override
    public String toString() {
        return inMemory() ? "SpoolPolicy[memory]" : "SpoolPolicy[temporaryFile]";
    }
}
