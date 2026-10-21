package dev.docweave.partition;

import org.junit.jupiter.api.Test;

import java.io.InterruptedIOException;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.jupiter.api.Assertions.*;

class PartitionCancellationTest {
    @Test
    void sourceLoaderChecksBeforeOpeningFiles() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class,
                    ()
                            -> new SourceLoader().load(
                                    DocumentSource.of(Path.of("does-not-exist.txt")), PartitionOptions.defaults()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void wordTextTraversalPreservesInterruption() throws Exception {
        final var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        final var root = document.createElement("t");
        root.appendChild(document.createTextNode("Cached field or inline text"));
        assertEquals("Cached field or inline text", DocxBodyView.text(root));
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> DocxBodyView.text(root));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertEquals("Cached field or inline text", DocxBodyView.text(root));
    }
}
