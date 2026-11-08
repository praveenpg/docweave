package io.docweave.partition;

import java.util.ArrayList;
import java.util.List;

/**
 * Hidden prepared document: the owned source bytes, identity and the results of the document-wide passes.
 * @param bytes owned source bytes, never exposed
 * @param filename optional filename
 * @param options document options
 * @param optionsDigest digest of the preparing configuration and options
 * @param sourceSha256 source digest
 * @param sourceBytes source size
 * @param loaderWarnings warnings from loading the source
 * @param inspection document-wide pass results
 */
record DefaultPdfPreparedDocument(byte[] bytes, String filename, PartitionOptions options, String optionsDigest,
        String sourceSha256, int sourceBytes, List<PartitionResult.Warning> loaderWarnings,
        DefaultPdfPartitioner.Inspection inspection) implements PdfPreparedDocument {
    @Override
    public int pageCount() {
        return inspection.documentPages();
    }

    @Override
    public List<PartitionResult.Warning> documentWarnings() {
        final var warnings = new ArrayList<>(loaderWarnings);
        warnings.addAll(inspection.warnings());
        return List.copyOf(warnings);
    }

    /**
     * Returns a read-only view of the source.
     * @return buffer at position zero
     */
    java.nio.ByteBuffer input() {
        return java.nio.ByteBuffer.wrap(bytes).asReadOnlyBuffer();
    }
}
