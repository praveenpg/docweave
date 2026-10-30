package io.docweave.partition;

import java.io.IOException;
import java.util.List;

/**
 * Explicit service producing lossless crops of final rendered PDF page regions.
 * This is a separate derivative contract: it never changes partition elements, source-image
 * payloads, source-image counts/order or their clip/group metadata. The PDF renderer composites the
 * whole page (transparency groups, soft masks, clipping, blend modes and later overpainting as
 * implemented by PDFBox), and each crop copies the requested rectangle of that page raster.
 * Each distinct page is rendered once per call, even when several requests share it, by its own
 * call-confined PDFRenderer. The service keeps no reference to a page raster or its renderer after
 * that page's crops are copied, so a previous page raster is unreachable when the next one is
 * allocated. This is not a heap bound: PDFBox parser, font, decoder and blend intermediates are outside
 * it. Requested edges round exactly outward to whole pixels. Implementations are stateless,
 * open and close their own document per call and can be shared across threads. No OCR, model,
 * external process or network access is used. Cancellation uses thread interruption, checked before
 * and after each page render and during crop copying/encoding; a render call already in progress
 * inside PDFBox is not interrupted.
 */
public interface PdfPageCropRenderer {
    /**
     * Creates a stateless renderer without opening or rendering anything.
     * @param options nonnull immutable resolution, background and resource policy
     * @return hidden reusable implementation
     * @throws NullPointerException if options is null
     */
    static PdfPageCropRenderer create(final PdfPageCropOptions options) {
        return new DefaultPdfPageCropRenderer(java.util.Objects.requireNonNull(options, "options"), null);
    }

    /**
     * Copies this renderer with explicit caller-owned password lookup for encrypted documents.
     * The opening and permission policy matches {@link PdfPartitioner#withPasswords}: content
     * extraction permission is still required. Custom implementations may reject this method.
     * @param passwords nonnull thread-safe provider returning fresh transferred character arrays
     * @return independent reusable renderer
     * @throws NullPointerException if passwords is null
     * @throws UnsupportedOperationException if an implementation does not support passwords
     */
    default PdfPageCropRenderer withPasswords(final PdfPasswordProvider passwords) {
        java.util.Objects.requireNonNull(passwords, "passwords");
        throw new UnsupportedOperationException("PDF page crop renderer does not support password configuration");
    }

    /**
     * Renders the requested page regions and returns one crop per request, in request order.
     * File sources are read once into an owned bounded snapshot and closed before parsing; byte
     * sources are copied; decoded text sources are rejected. All request, page-raster, total-render
     * and crop-pixel limits are checked from page geometry before any page is rendered.
     * Failures are typed: {@code INPUT_TOO_LARGE} for source bytes, {@code IMAGE_LIMIT} for request,
     * page, pixel and encoded-byte budgets and for page rasters above PDFRenderer's
     * {@link Integer#MAX_VALUE}-pixel ceiling, {@code UNSUPPORTED_FORMAT} for decoded text sources,
     * {@code UNSUPPORTED_OPTION} for missing pages, regions without rendered pixels, unsupported
     * rotation, credentials or permissions, and {@code MALFORMED_DOCUMENT} for parse/render failures.
     * No partial result is returned after a failure.
     * @param source nonnull PDF file or byte source
     * @param requests nonnull, nonempty list of nonnull requests
     * @return immutable list of independent crops matching the request order
     * @throws NullPointerException if source, requests or a request is null
     * @throws IllegalArgumentException if requests is empty, or the source is Remote (resolve it first)
     * @throws IOException if reading, parsing, limits, permissions, rendering or cancellation fail
     */
    List<PdfPageCrop> render(DocumentSource source, List<PdfPageCropRequest> requests) throws IOException;
}
