import io.docweave.chunking.ChunkOptions;
import io.docweave.chunking.Chunker;
import io.docweave.json.ElementJson;

import java.nio.file.Path;

/** Java 21-compatible standalone source-file launcher. */
final class ChunkElements {
    /** Prevents external construction of the launcher. */
    private ChunkElements() {
    }

    /**
     * Runs the standalone tool using a document-confined launcher instance.
     * @param args command-line arguments
     * @throws Exception if processing or I/O fails
     */
    public static void main(final String[] args) throws Exception {
        new ChunkElements().run(args);
    }

    // Java 21 source-file launcher; no preview flags or framework needed.
    private void run(final String[] args) throws Exception {
        if (args.length < 3 || args.length > 4) {
            throw new IllegalArgumentException(
                    "Usage: ChunkElements.java basic|by_title input.json output.json [maxCharacters]");
        }
        final var strategy = switch (args[0]) {
            case "basic" -> Chunker.Strategy.BASIC;
            case "by_title" -> Chunker.Strategy.BY_TITLE;
            default -> throw new IllegalArgumentException("Unknown strategy: " + args[0]);
        };
        final var options = ChunkOptions.builder();
        if (args.length == 4) {
            options.maxCharacters(Integer.parseInt(args[3]));
        }
        final var json = ElementJson.create();
        final var chunks = Chunker.create().chunk(json.read(Path.of(args[1])), strategy, options.build());
        json.write(Path.of(args[2]), chunks);
        System.out.println("Wrote " + chunks.size() + " chunks to " + args[2]);
    }
}
