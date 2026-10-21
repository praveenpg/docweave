package dev.docweave.chunking;

import java.io.IOException;
import java.util.Objects;

/**
 * Caller-owned document-aware context generation, independent of model vendors and source formats.
 * Providers may use local or hosted models; only an explicit enrichment call invokes them.
 * Implementations shared across calls must support concurrency and honor interruption/timeouts.
 * The caller retains provider/client lifecycle ownership. Document and chunk text may contain
 * confidential content; choosing a remote provider explicitly determines where that text is sent.
 */
@FunctionalInterface
public interface ChunkContextProvider {
    /**
     * Generates context for one original chunk without changing that chunk's source text.
     * @param request bounded document/chunk strings and original zero-based position
     * @return nonnull generated context with explicit provider/model provenance
     * @throws IOException if generation fails
     */
    Context generate(Request request) throws IOException;

    /**
     * Immutable text-only input; image payloads, element metadata and provenance are not forwarded.
     * The enrichment service validates all input budgets before invoking any provider.
     * @param documentText explicit caller-selected document context, shared across requests
     * @param chunkText original chunk text, including table or code formatting
     * @param chunkIndex zero-based position in the complete supplied chunk list
     * @param chunkCount complete supplied chunk count
     */
    record Request(String documentText, String chunkText, int chunkIndex, int chunkCount) {
        /**
         * Validates text ownership and a position inside a nonempty chunk population.
         * @throws NullPointerException if either text is null
         * @throws IllegalArgumentException if the position/count is invalid
         */
        public Request {
            Objects.requireNonNull(documentText, "documentText");
            Objects.requireNonNull(chunkText, "chunkText");
            if (chunkIndex < 0 || chunkIndex >= chunkCount) {
                throw new IllegalArgumentException("Chunk context position is outside the chunk list");
            }
        }
    }

    /**
     * Generated enrichment, never treated as original document content or a verified factual summary.
     * Provider/model identities are explicit caller-supplied provenance, not inferred engine versions.
     * @param text nonblank generated context; preserved verbatim
     * @param providerId nonblank provider identity, at most256 Unicode code points
     * @param modelId nonblank model/configuration identity, at most256 Unicode code points
     */
    record Context(String text, String providerId, String modelId) {
        /**
         * Validates provenance and nonblank context; service options separately bound context length.
         * @throws NullPointerException if any component is null
         * @throws IllegalArgumentException if text/identity is blank or an identity is oversized
         */
        public Context {
            Objects.requireNonNull(text, "text");
            identity(providerId, "providerId");
            identity(modelId, "modelId");
            if (text.isBlank()) {
                throw new IllegalArgumentException("Generated context must not be blank");
            }
        }

        /**
         * Bounds one diagnostic identity without changing its original characters.
         * @param value supplied identity
         * @param name component name for a content-free diagnostic
         */
        private static void identity(final String value, final String name) {
            Objects.requireNonNull(value, name);
            if (value.isBlank() || value.length() > 512 || value.codePointCount(0, value.length()) > 256) {
                throw new IllegalArgumentException(name + " must contain1 through256 code points");
            }
        }
    }
}
