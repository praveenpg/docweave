package io.docweave.partition;

import java.io.IOException;

/**
 * Explicit caller-owned PDF credential lookup, separate from extraction options and result metadata.
 * Implementations must be thread-safe when a configured service is shared between documents.
 * Lookup runs synchronously only after an empty-password open fails or an encrypted document's
 * empty-password permissions prohibit extraction. No retry loop, prompting, discovery or caching is added.
 */
@FunctionalInterface
public interface PdfPasswordProvider {
    /**
     * Supplies at most one credential for the current document.
     * Return a fresh array whose ownership transfers to DocWeave: it is cleared after the open attempt,
     * including failure or cancellation. Return null when no credential is available. Never return a
     * shared or cached array. An empty array explicitly selects the empty password.
     * PDFBox requires a temporary String; clearing the array does not guarantee erasure of JVM/library copies.
     * The nullable source name is only a lookup hint, not a file handle or unique identity. Standalone
     * font inspection supplies null. Caller code owns any external lookup, deadline and interruption behavior.
     * Provider error messages/causes are not retained in processing failures, to avoid exposing credentials.
     * @param filename source metadata name, or null when absent
     * @return newly owned password characters, or null when unavailable
     * @throws IOException if credential lookup fails or is interrupted
     */
    char[] password(String filename) throws IOException;
}
