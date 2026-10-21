package dev.docweave.partition;

import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/** Stateless DOCX strategy; archive verification and source-order extraction are composed per call. */
final class DocxPartitioner implements Partitioner {
    /**
     * Returns the DOCX document format handled by this partitioner.
     *
     * @return {@link DocumentFormat#DOCX}
     */
    public DocumentFormat format() {
        return DocumentFormat.DOCX;
    }

    /**
     * Indicates that the DOCX partitioner supports nested-table metadata preservation.
     *
     * @return {@code true}
     */
    @Override
    public boolean supportsNestedTablePreservation() {
        return true;
    }

    /**
     * Verifies the DOCX archive and extracts semantic elements in document order.
     *
     * @param context active partition context
     * @throws IOException if reading fails or the thread is interrupted
     */
    public void partition(final PartitionContext context) throws IOException {
        NestedTableSupport.check(this, context.options());
        ElementCollector.checkInterrupted();
        final byte[] bytes = context.binaryContent();
        new DocxArchiveVerifier(context.options().docxOptions()).verify(bytes);
        ElementCollector.checkInterrupted();
        try (final var document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            ElementCollector.checkInterrupted();
            if (!DocxArchiveVerifier.MAIN_TYPE.equals(document.getPackagePart().getContentType())) {
                throw new PartitionException(
                        PartitionException.Code.UNSUPPORTED_FORMAT, "Only non-macro DOCX documents are supported");
            }
            new DocxBodyExtractor(context, document).extract();
        } catch (final PartitionException | java.io.InterruptedIOException error) {
            throw error;
        } catch (final IOException | RuntimeException error) {
            throw new PartitionException(PartitionException.Code.MALFORMED_DOCUMENT, "DOCX package could not be read");
        }
    }
}
