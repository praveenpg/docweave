import dev.docweave.DocWeave;
import dev.docweave.chunking.ChunkOptions;
import dev.docweave.chunking.Chunker;
import dev.docweave.json.ElementJson;

import java.nio.file.Path;

/** Java 21-compatible standalone source-file launcher. */
final class PartitionDocument {
    /** Prevents external construction of the launcher. */
    private PartitionDocument() {
    }

    /**
     * Runs the standalone tool using a document-confined launcher instance.
     * @param args command-line arguments
     * @throws Exception if processing or I/O fails
     */
    public static void main(final String[] args) throws Exception {
        new PartitionDocument().run(args);
    }

    /**
     * Demonstrates interface-based native partitioning with optional shared chunking on Java21.
     * @param args local input, JSON output and optional chunking strategy
     * @throws Exception if input parsing, chunking or output writing fails
     */
    private void run(final String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) {
            throw new IllegalArgumentException(
                    "Usage: PartitionDocument.java input.txt|html|md|csv|tsv|xml|docx|pdf output.json [basic|by_title]");
        }
        final DocWeave processor = DocWeave.create();
        final ElementJson codec = ElementJson.create();
        final var result = processor.partition(Path.of(args[0]));
        var elements = result.elements();
        if (args.length == 3) {
            final var strategy = switch (args[2]) {
                case "basic" -> Chunker.Strategy.BASIC;
                case "by_title" -> Chunker.Strategy.BY_TITLE;
                default -> throw new IllegalArgumentException("Unknown chunking strategy: " + args[2]);
            };
            elements = Chunker.create().chunk(elements, strategy, ChunkOptions.defaults());
        }
        codec.write(Path.of(args[1]), elements);
        System.out.println("Parsed " + result.format() + " with " + result.elements().size() + " elements; wrote "
                           + elements.size() + " output elements and observed " + result.warnings().size()
                           + " warnings.");
    }
}
