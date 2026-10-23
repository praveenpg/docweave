package dev.docweave.partition;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.RenderDestination;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import javax.imageio.IIOException;
import javax.imageio.ImageIO;

/**
 * Stateless page-crop orchestration. The source snapshot, PDDocument, PDFRenderer, page rasters and
 * encoder buffers are all confined to one {@link #render} call; nothing is cached between calls.
 */
final class DefaultPdfPageCropRenderer implements PdfPageCropRenderer {
    /**
     * PDFRenderer's hard page-raster ceiling: it refuses a width-height product above this value
     * (PDFBOX-4518) before allocating, and Java2D integer rasters cannot hold more samples.
     */
    private static final long RENDERER_MAX_PIXELS = Integer.MAX_VALUE;

    /** Renderer identity recorded with each crop; the version comes from the loaded PDFBox package. */
    private static final String RENDERER =
            "Apache PDFBox "
            + Objects.requireNonNullElse(org.apache.pdfbox.util.Version.getVersion(), "unknown version")
            + " PDFRenderer EXPORT";

    /** Immutable resolution, background and resource policy. */
    private final PdfPageCropOptions options;
    /** Optional caller-owned credential lookup; credentials stay confined to opening one document. */
    private final PdfPasswordProvider passwords;
    /**
     * Creates one call-confined PDFRenderer per planned page. PDFRenderer keeps a strong reference to
     * the last page image it rendered, so a renderer is never reused for another page.
     */
    private final Function<PDDocument, PDFRenderer> renderers;

    /**
     * Captures configuration without I/O.
     * @param options nonnull crop policy
     * @param passwords optional caller-owned credential provider
     */
    DefaultPdfPageCropRenderer(final PdfPageCropOptions options, final PdfPasswordProvider passwords) {
        this(options, passwords, PDFRenderer::new);
    }

    /**
     * Captures configuration with an explicit per-page renderer factory; package tests use it to observe
     * the actual render-call lifecycle without exposing a public hook.
     * @param options nonnull crop policy
     * @param passwords optional caller-owned credential provider
     * @param renderers nonnull factory invoked once per planned page
     */
    DefaultPdfPageCropRenderer(final PdfPageCropOptions options, final PdfPasswordProvider passwords,
            final Function<PDDocument, PDFRenderer> renderers) {
        this.options = options;
        this.passwords = passwords;
        this.renderers = renderers;
    }

    @Override
    public PdfPageCropRenderer withPasswords(final PdfPasswordProvider passwords) {
        return new DefaultPdfPageCropRenderer(options, Objects.requireNonNull(passwords, "passwords"), renderers);
    }

    @Override
    public List<PdfPageCrop> render(final DocumentSource source, final List<PdfPageCropRequest> requests)
            throws IOException {
        Objects.requireNonNull(source, "source");
        final var ordered = List.copyOf(Objects.requireNonNull(requests, "requests"));
        if (ordered.isEmpty()) {
            throw new IllegalArgumentException("At least one PDF page crop request is required");
        }
        if (ordered.size() > options.maxRequests()) {
            throw limit("PDF page crop requests exceed maxRequests");
        }
        final var input = read(source);
        try (final var document = PdfDocumentLoader.load(input.bytes(), input.filename(), passwords)) {
            ElementCollector.checkInterrupted();
            if (!document.getCurrentAccessPermission().canExtractContent()) {
                throw new PartitionException(
                        PartitionException.Code.UNSUPPORTED_OPTION, "PDF permissions prohibit content extraction");
            }
            return crop(document, plan(document, ordered), ordered.size());
        } catch (final PartitionException | InterruptedIOException failure) {
            throw failure;
        } catch (final InvalidPasswordException failure) {
            throw new PartitionException(
                    PartitionException.Code.UNSUPPORTED_OPTION, "PDF password is missing or incorrect", failure);
        } catch (final IOException | IllegalArgumentException | IllegalStateException failure) {
            throw new PartitionException(
                    PartitionException.Code.MALFORMED_DOCUMENT, "PDF could not be parsed or rendered", failure);
        }
    }

    /**
     * Takes an owned bounded snapshot so that no caller stream or file handle outlives this step.
     * @param source caller source; file handles are opened and closed here
     * @return owned bytes with an optional password lookup hint
     * @throws IOException if the source is text, too large, unreadable or cancellation occurs
     */
    private Input read(final DocumentSource source) throws IOException {
        return switch (source) {
            case DocumentSource.File file -> {
                try (final var channel = FileChannel.open(file.path(), StandardOpenOption.READ)) {
                    final var name = file.path().getFileName();
                    yield new Input(BoundedFileInput.read(channel, options.maxInputBytes()),
                            name == null ? null : name.toString());
                }
            }
            case DocumentSource.Bytes bytes -> {
                if (bytes.size() > options.maxInputBytes()) {
                    throw new PartitionException(PartitionException.Code.INPUT_TOO_LARGE,
                            "Document exceeds maxInputBytes=" + options.maxInputBytes());
                }
                yield new Input(bytes.content(), bytes.filename());
            }
            case DocumentSource.Text ignored ->
                throw new PartitionException(
                        PartitionException.Code.UNSUPPORTED_FORMAT, "PDF page crops require file or byte sources");
        };
    }

    /**
     * Resolves every request to integer page-raster pixels and checks all geometry budgets before rendering.
     * Mapping follows PDFRenderer: the crop box is scaled by {@code dpi * UserUnit / 72}, each raster
     * dimension is the floor of the scaled crop dimension (at least one), and quarter turns swap
     * dimensions. A displayed point (u, v) therefore lies at pixel (u * scale, v * scale). Requested
     * edges round exactly outward to whole pixels and are then intersected with the actual raster.
     * Each page raster is at most {@link Integer#MAX_VALUE} pixels, so the running total of at most
     * {@code maxPages} rasters cannot overflow a long.
     * @param document opened call-confined source
     * @param requests validated caller requests
     * @return page plans in ascending physical page order
     * @throws IOException if a page is missing/unsupported, a region has no pixels or a limit would be exceeded
     */
    private TreeMap<Integer, PagePlan> plan(final PDDocument document, final List<PdfPageCropRequest> requests)
            throws IOException {
        final var plans = new TreeMap<Integer, PagePlan>();
        long totalPixels = 0;
        for (int position = 0; position < requests.size(); position++) {
            ElementCollector.checkInterrupted();
            final var request = requests.get(position);
            if (request.pageNumber() > document.getNumberOfPages()) {
                throw new PartitionException(
                        PartitionException.Code.UNSUPPORTED_OPTION, "Requested PDF crop page does not exist");
            }
            var plan = plans.get(request.pageNumber());
            if (plan == null) {
                if (plans.size() == options.maxPages()) {
                    throw limit("PDF page crop pages exceed maxPages");
                }
                plan = page(document, request.pageNumber() - 1);
                totalPixels += (long) plan.renderWidth() * plan.renderHeight();
                if (totalPixels > options.maxTotalPagePixels()) {
                    throw limit("PDF page crop rendering exceeds maxTotalPagePixels");
                }
                plans.put(request.pageNumber(), plan);
            }
            plan.slots().add(slot(position, request, plan));
        }
        return plans;
    }

    /**
     * Predicts one page raster exactly as PDFRenderer will allocate it, without rendering.
     * The output grid uses the requested scale. A supersampling policy renders at an exact power-of-two
     * multiple of that float scale. Normally every output pixel has a complete rendered sample block.
     * For sub-pixel page axes PDFRenderer's minimum-one-pixel output grid may extend beyond its
     * supersampled raster; missing samples use the selected background. Limits apply to the raster
     * actually allocated, not to a padded page image.
     * @param document call-confined source
     * @param index zero-based page index
     * @return empty-slot page plan
     * @throws IOException if geometry is malformed/unsupported, or the page raster exceeds maxPagePixels or
     *                     PDFRenderer's hard integer raster ceiling
     */
    private PagePlan page(final PDDocument document, final int index) throws IOException {
        final var page = document.getPage(index);
        // Validates crop, quarter-turn rotation and UserUnit with the partitioner's shared rules.
        new PdfPageGeometry(page);
        final float userUnit = page.getUserUnit();
        final float scale = (float) (options.dpi() * (double) userUnit / 72.0);
        final var crop = page.getCropBox();
        // Same float products and floor as PDFRenderer.renderImage, evaluated before any allocation.
        final float renderScale = scale * options.sampling().factor();
        final double rawWidth = Math.max(Math.floor(crop.getWidth() * scale), 1);
        final double rawHeight = Math.max(Math.floor(crop.getHeight() * scale), 1);
        final double renderWidth = Math.max(Math.floor(crop.getWidth() * renderScale), 1);
        final double renderHeight = Math.max(Math.floor(crop.getHeight() * renderScale), 1);
        if (!Float.isFinite(renderScale) || scale <= 0 || !(renderWidth * renderHeight <= RENDERER_MAX_PIXELS)) {
            // PDFRenderer would refuse this valid page; report the unsupported raster size, not a malformed PDF.
            throw limit("PDF page crop rendering exceeds the renderer's maximum raster size");
        }
        if (renderWidth * renderHeight > options.maxPagePixels()) {
            throw limit("PDF page crop rendering exceeds maxPagePixels");
        }
        final int rotation = page.getRotation();
        final boolean quarter = rotation == 90 || rotation == 270;
        return new PagePlan(index, scale, (int) (quarter ? rawHeight : rawWidth),
                (int) (quarter ? rawWidth : rawHeight), renderScale, (int) (quarter ? renderHeight : renderWidth),
                (int) (quarter ? renderWidth : renderHeight), rotation, userUnit, new ArrayList<>());
    }

    /**
     * Maps one request to an outward-rounded pixel rectangle intersected with the page raster.
     * Edges are rounded from the exact product of the requested double and the renderer's float scale
     * (divided exactly by UserUnit for points), so no requested pixel is dropped by intermediate
     * rounding: any positive-width intersection with a pixel column or row includes it, and an edge
     * exactly on a pixel boundary adds no extra column or row.
     * @param position original request index
     * @param request caller request
     * @param plan page raster prediction
     * @return checked pixel slot
     * @throws PartitionException if no rendered pixel remains or the crop exceeds maxCropPixels
     */
    private Slot slot(final int position, final PdfPageCropRequest request, final PagePlan plan)
            throws PartitionException {
        final double unitScale = request.units() == PdfPageCropRequest.Units.USER_SPACE
                                         ? plan.scale()
                                         : plan.scale() / (double) plan.userUnit();
        final var rawLeft = edge(request.left(), request.units(), plan, RoundingMode.FLOOR);
        final var rawTop = edge(request.top(), request.units(), plan, RoundingMode.FLOOR);
        final var rawRight = edge(request.right(), request.units(), plan, RoundingMode.CEILING);
        final var rawBottom = edge(request.bottom(), request.units(), plan, RoundingMode.CEILING);
        final int left = clamp(rawLeft, plan.width());
        final int top = clamp(rawTop, plan.height());
        final int right = clamp(rawRight, plan.width());
        final int bottom = clamp(rawBottom, plan.height());
        if (right <= left || bottom <= top) {
            throw new PartitionException(PartitionException.Code.UNSUPPORTED_OPTION,
                    "Requested PDF crop region contains no rendered page pixels");
        }
        if ((long) (right - left) * (bottom - top) > options.maxCropPixels()) {
            throw limit("PDF page crop exceeds maxCropPixels");
        }
        final boolean clipped = rawLeft.signum() < 0 || rawTop.signum() < 0
                                || rawRight.compareTo(BigDecimal.valueOf(plan.width())) > 0
                                || rawBottom.compareTo(BigDecimal.valueOf(plan.height())) > 0;
        return new Slot(position, request, left, top, right, bottom, clipped, unitScale);
    }

    /**
     * Rounds one request edge to a whole pixel boundary using exact decimal arithmetic.
     * The double value and float scale are both exactly representable as BigDecimal, so the product
     * and its floor/ceiling are exact; point values are divided by UserUnit with the same rounding mode.
     * @param value finite requested edge
     * @param units request scale
     * @param plan page raster prediction supplying the renderer scale and UserUnit
     * @param mode FLOOR for left/top edges, CEILING for right/bottom edges
     * @return unclamped integral pixel boundary
     */
    private static BigDecimal edge(
            final double value, final PdfPageCropRequest.Units units, final PagePlan plan, final RoundingMode mode) {
        final var product = new BigDecimal(value).multiply(new BigDecimal(plan.scale()));
        return units == PdfPageCropRequest.Units.USER_SPACE ? product.setScale(0, mode)
                                                            : product.divide(new BigDecimal(plan.userUnit()), 0, mode);
    }

    /**
     * Intersects an integral pixel boundary with a raster axis.
     * @param edge unclamped integral boundary
     * @param extent raster width or height
     * @return boundary within zero through extent
     */
    private static int clamp(final BigDecimal edge, final int extent) {
        if (edge.signum() <= 0) {
            return 0;
        }
        return edge.compareTo(BigDecimal.valueOf(extent)) >= 0 ? extent : edge.intValueExact();
    }

    /**
     * Renders each planned page once and copies all of its crops before the next page is rendered.
     * @param document call-confined source
     * @param plans checked page plans
     * @param count number of requests
     * @return crops in original request order
     * @throws IOException if rendering, raster consistency, encoding limits or cancellation fail
     */
    private List<PdfPageCrop> crop(final PDDocument document, final TreeMap<Integer, PagePlan> plans, final int count)
            throws IOException {
        final var type = options.background() == PdfPageCropOptions.Background.WHITE ? ImageType.RGB : ImageType.ARGB;
        final var budget = new EncodedBudget(options.maxEncodedBytes());
        final var crops = new PdfPageCrop[count];
        for (final var plan : plans.values()) {
            page(document, plan, type, budget, crops);
        }
        return List.of(crops);
    }

    /**
     * Renders one planned page with its own PDFRenderer and copies every crop on that page.
     * PDFRenderer retains its last page image, and BufferedImage.flush does not release pixel storage.
     * The renderer and raster are therefore referenced only from this frame, which ends before the next
     * page's renderer is created, so the previous page raster is unreachable during the next allocation.
     * @param document call-confined source
     * @param plan checked page plan
     * @param type raster type matching the background policy
     * @param budget cumulative encoded-byte budget
     * @param crops request-ordered output slots
     * @throws IOException if rendering, raster consistency, encoding limits or cancellation fail
     */
    private void page(final PDDocument document, final PagePlan plan, final ImageType type, final EncodedBudget budget,
            final PdfPageCrop[] crops) throws IOException {
        ElementCollector.checkInterrupted();
        final var raster =
                renderers.apply(document).renderImage(plan.index(), plan.renderScale(), type, RenderDestination.EXPORT);
        try {
            ElementCollector.checkInterrupted();
            if (raster.getWidth() != plan.renderWidth() || raster.getHeight() != plan.renderHeight()) {
                throw new IOException("PDF renderer returned an unexpected page raster size");
            }
            for (final var slot : plan.slots()) {
                crops[slot.position()] = crop(raster, plan, slot, budget);
            }
        } finally {
            raster.flush();
        }
    }

    /**
     * Copies or box-averages one rectangle into an independent raster and encodes it as PNG.
     * @param raster borrowed complete rendered page raster
     * @param plan page geometry
     * @param slot checked pixel rectangle
     * @param budget cumulative encoded-byte budget
     * @return immutable crop
     * @throws IOException if encoding fails, the byte budget is exhausted or cancellation occurs
     */
    private PdfPageCrop crop(final BufferedImage raster, final PagePlan plan, final Slot slot,
            final EncodedBudget budget) throws IOException {
        final int width = slot.right() - slot.left();
        final int height = slot.bottom() - slot.top();
        final var copy = new BufferedImage(width, height,
                options.background() == PdfPageCropOptions.Background.WHITE ? BufferedImage.TYPE_INT_RGB
                                                                            : BufferedImage.TYPE_INT_ARGB);
        try {
            final int factor = options.sampling().factor();
            if (factor == 1) {
                // An owned copy, never a getSubimage view sharing the page raster's storage.
                final int[] row = new int[width];
                for (int y = 0; y < height; y++) {
                    ElementCollector.checkInterrupted();
                    raster.getRGB(slot.left(), slot.top() + y, width, 1, row, 0, width);
                    copy.setRGB(0, y, width, 1, row, 0, width);
                }
            } else {
                downsample(raster, slot, factor, copy, options.background());
            }
            final byte[] png = budget.encode(copy);
            final double unitScale = slot.unitScale();
            return new PdfPageCrop(slot.request(), png, width, height, slot.left(), slot.top(), plan.width(),
                    plan.height(), slot.left() / unitScale, slot.top() / unitScale, slot.right() / unitScale,
                    slot.bottom() / unitScale, slot.clipped(), plan.scale(), options.dpi(), plan.rotation(),
                    plan.userUnit(), options.background(), options.sampling(), RENDERER);
        } finally {
            copy.flush();
        }
    }

    /**
     * Averages each factor-by-factor block of the supersampled raster into one output pixel.
     * Color is weighted by alpha (premultiplied averaging) so transparent samples contribute no color;
     * for opaque rasters this equals the plain rounded channel mean. Integer sums cannot overflow: at
     * most 16 samples of 255 * 255 per channel. If a minimum-one-pixel output axis extends past a
     * sub-pixel page's rendered raster, absent samples use the selected background; edge colors are
     * never repeated and no larger page raster is allocated.
     * @param raster borrowed supersampled page raster
     * @param slot output pixel rectangle on the requested-scale grid
     * @param factor per-axis supersampling factor
     * @param copy owned output raster
     * @param background fill for samples beyond the actually rendered page raster
     * @throws InterruptedIOException if cancelled between output rows
     */
    private static void downsample(final BufferedImage raster, final Slot slot, final int factor,
            final BufferedImage copy, final PdfPageCropOptions.Background background) throws InterruptedIOException {
        final int width = copy.getWidth();
        final int samples = factor * factor;
        final int sourceLeft = slot.left() * factor;
        final int sampleWidth = Math.min(width * factor, raster.getWidth() - sourceLeft);
        // Buffer only existing samples: for very short pages width * factor * factor could exceed
        // the actual page-pixel budget (or overflow), even though the rendered raster itself fits.
        final int[] block = new int[sampleWidth * Math.min(factor, raster.getHeight())];
        final int padding = background == PdfPageCropOptions.Background.WHITE ? 0xffffffff : 0;
        for (int y = 0; y < copy.getHeight(); y++) {
            ElementCollector.checkInterrupted();
            final int sourceTop = (slot.top() + y) * factor;
            final int sampleRows = Math.min(factor, raster.getHeight() - sourceTop);
            raster.getRGB(sourceLeft, sourceTop, sampleWidth, sampleRows, block, 0, sampleWidth);
            for (int x = 0; x < width; x++) {
                int alpha = 0;
                int red = 0;
                int green = 0;
                int blue = 0;
                for (int dy = 0; dy < factor; dy++) {
                    for (int dx = 0; dx < factor; dx++) {
                        final int sampleX = x * factor + dx;
                        final int pixel =
                                dy < sampleRows && sampleX < sampleWidth ? block[dy * sampleWidth + sampleX] : padding;
                        final int a = pixel >>> 24;
                        alpha += a;
                        red += ((pixel >> 16) & 255) * a;
                        green += ((pixel >> 8) & 255) * a;
                        blue += (pixel & 255) * a;
                    }
                }
                final int value = alpha == 0 ? 0
                                             : (alpha + samples / 2) / samples << 24 | (red + alpha / 2) / alpha << 16
                                                       | (green + alpha / 2) / alpha << 8 | (blue + alpha / 2) / alpha;
                copy.setRGB(x, y, value);
            }
        }
    }

    /**
     * Creates a typed derivative budget failure.
     * @param message diagnostic without document content
     * @return IMAGE_LIMIT failure
     */
    private static PartitionException limit(final String message) {
        return new PartitionException(PartitionException.Code.IMAGE_LIMIT, message);
    }

    /**
     * Owned source snapshot.
     * @param bytes owned PDF bytes
     * @param filename nullable password lookup hint
     */
    private record Input(byte[] bytes, String filename) {}

    /**
     * One predicted page raster and the requests it serves.
     * @param index zero-based page index
     * @param scale output-grid scale, pixels per unscaled user-space unit
     * @param width displayed output-grid width
     * @param height displayed output-grid height
     * @param renderScale actual PDFRenderer scale, a power-of-two multiple of scale
     * @param renderWidth displayed rendered raster width
     * @param renderHeight displayed rendered raster height
     * @param rotation normalized clockwise rotation
     * @param userUnit physical points per user-space unit
     * @param slots mutable call-confined request slots
     */
    private record PagePlan(int index, float scale, int width, int height, float renderScale, int renderWidth,
            int renderHeight, int rotation, float userUnit, List<Slot> slots) {}

    /**
     * One request resolved to page-raster pixels.
     * @param position original request index
     * @param request caller request
     * @param left inclusive pixel column
     * @param top inclusive pixel row
     * @param right exclusive pixel column
     * @param bottom exclusive pixel row
     * @param clipped whether the outward-rounded request extended beyond the raster
     * @param unitScale pixels per request unit
     */
    private record Slot(int position, PdfPageCropRequest request, int left, int top, int right, int bottom,
            boolean clipped, double unitScale) {}

    /** Call-confined cumulative PNG byte budget, charged before each codec write is retained. */
    private static final class EncodedBudget {
        /** Maximum cumulative bytes. */
        private final int limit;
        /** Bytes already charged in this call. */
        private int used;

        /**
         * Creates an empty budget.
         * @param limit positive cumulative byte ceiling
         */
        EncodedBudget(final int limit) {
            this.limit = limit;
        }

        /**
         * Encodes one owned raster as PNG through a sink that rejects writes beyond the remaining budget.
         * @param image owned crop raster
         * @return exact PNG bytes
         * @throws IOException if the encoder is unavailable, the budget is exhausted or cancellation occurs
         */
        byte[] encode(final BufferedImage image) throws IOException {
            final var output = new ByteArrayOutputStream();
            final var bounded = new OutputStream() {
                @Override
                public void write(final int value) throws IOException {
                    reserve(1);
                    output.write(value);
                }

                @Override
                public void write(final byte[] bytes, final int offset, final int length) throws IOException {
                    reserve(length);
                    output.write(bytes, offset, length);
                }
            };
            try {
                if (!ImageIO.write(image, "png", bounded)) {
                    throw new IOException("PNG encoder unavailable");
                }
            } catch (final IIOException failure) {
                // ImageIO may wrap the bounded sink's typed failure.
                if (failure.getCause() instanceof final PartitionException exceeded) {
                    throw exceeded;
                }
                if (failure.getCause() instanceof final InterruptedIOException cancelled) {
                    throw cancelled;
                }
                throw failure;
            }
            return output.toByteArray();
        }

        /**
         * Checks cancellation and the cumulative budget before a codec write.
         * @param count requested byte count
         * @throws IOException if cancelled or the budget is exhausted
         */
        private void reserve(final int count) throws IOException {
            ElementCollector.checkInterrupted();
            if (count > limit - used) {
                throw limit("PDF page crops exceed maxEncodedBytes");
            }
            used += count;
        }
    }
}
