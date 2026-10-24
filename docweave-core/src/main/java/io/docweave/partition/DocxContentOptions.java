package io.docweave.partition;

/**
 * Immutable policies for Word content previously omitted by basic body extraction.
 * Notes and comments are emitted as separate stored parts with reference metadata; text boxes and
 * images are anchored to their containing paragraph/table by code-point offsets and parent IDs.
 * Cached field results are read without evaluating instructions or accessing external targets.
 *
 * @param includeNotes whether normal footnote/endnote bodies are emitted (separators are excluded)
 * @param includeComments whether stored comments are emitted with IDs and authors
 * @param includeTextBoxes whether drawing/VML text-box paragraphs and tables are extracted
 * @param images embedded/linked-image handling policy
 * @param maxEmbeddedImageBytes per-image byte limit in [1, Integer.MAX_VALUE) when embedding payloads
 * @param revisions accepted, original or all stored insertion/deletion text
 */
public record DocxContentOptions(boolean includeNotes, boolean includeComments, boolean includeTextBoxes, Images images,
        int maxEmbeddedImageBytes, Revisions revisions) {
    /** Controls image-reference and byte extraction without rendering or OCR. */
    public enum Images {
        /** Excludes images without decoding or fetching their payloads. */ SKIP,
        /** Emits internal part names or external reference URLs, without payload bytes. */ REFERENCE,
        /** Includes base64 bytes for embedded images; external images remain references. */ EMBED
    }

    /** Selects stored content; this does not accept/reject edits in or modify the source file. */
    public enum Revisions {
        /** Includes insertions/move destinations and excludes deletions/move origins. */ ACCEPTED,
        /** Includes deletions/move origins and excludes insertions/move destinations. */ ORIGINAL,
        /** Includes both stored alternatives in XML order, without deduplication. */ ALL
    }

    /**
     * Validates content policies.
     * @param includeNotes whether to emit normal note parts
     * @param includeComments whether to emit stored comments
     * @param includeTextBoxes whether to extract text-box bodies
     * @param images nonnull image policy
     * @param maxEmbeddedImageBytes per-embedded-image budget in [1, Integer.MAX_VALUE)
     * @param revisions nonnull stored-revision policy
     * @throws NullPointerException if a policy is null
     * @throws IllegalArgumentException if the image budget is not positive
     */
    public DocxContentOptions {
        java.util.Objects.requireNonNull(images, "images");
        java.util.Objects.requireNonNull(revisions, "revisions");
        if (maxEmbeddedImageBytes < 1 || maxEmbeddedImageBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("maxEmbeddedImageBytes must be in [1, Integer.MAX_VALUE)");
        }
    }

    /**
     * Enables notes/comments/text boxes, image references and accepted revisions, with a 4 MiB embedding budget.
     * @return immutable content defaults
     */
    public static DocxContentOptions defaults() {
        return new DocxContentOptions(true, true, true, Images.REFERENCE, 4 * 1024 * 1024, Revisions.ACCEPTED);
    }
}
