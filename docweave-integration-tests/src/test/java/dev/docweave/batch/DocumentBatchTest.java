package dev.docweave.batch;

import dev.docweave.chunking.ChunkOptions;
import dev.docweave.chunking.Chunker;
import dev.docweave.partition.DocumentFormat;
import dev.docweave.partition.PartitionOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DocumentBatchTest {
    @Test
    void partitionsMixedFilesAndChunksInInputOrder(final @TempDir Path directory) throws Exception {
        final var txt = directory.resolve("first.txt");
        final var html = directory.resolve("second.html");
        Files.writeString(txt, "Title\n\nA paragraph.");
        Files.writeString(html, "<h1>Heading</h1><p>Body.</p>");
        final var results = DocumentBatch.create().partitionAndChunk(
                List.of(txt, html), PartitionOptions.defaults(), Chunker.Strategy.BY_TITLE, ChunkOptions.defaults(), 2);
        assertEquals(List.of(txt, html), results.stream().map(DocumentBatch.Result::source).toList());
        assertEquals(DocumentFormat.TXT, results.getFirst().partition().format());
        assertEquals(DocumentFormat.HTML, results.getLast().partition().format());
        assertEquals("Heading\n\nBody.", results.getLast().chunks().getFirst().text());
    }
}
