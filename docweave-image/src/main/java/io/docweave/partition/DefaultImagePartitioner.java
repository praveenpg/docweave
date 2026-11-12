package io.docweave.partition;

import io.docweave.core.ElementType;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.event.IIOReadProgressListener;
import javax.imageio.plugins.tiff.TIFFDirectory;

/** Decodes one page at a time with the JDK's ImageIO readers and recognizes it with the configured OCR provider. */
final class DefaultImagePartitioner implements ImagePartitioner {
    /** Formats this partitioner can be created for. */
    private static final Set<DocumentFormat> FORMATS =
            Set.of(DocumentFormat.PNG, DocumentFormat.JPEG, DocumentFormat.TIFF);

    /** Format of this instance. */
    private final DocumentFormat format;

    /** Resource limits. */
    private final ImageOptions options;

    /** Null for service-loaded instances, which refuse to partition. */
    private final PdfOcrProvider ocr;

    /**
     * Creates a partitioner.
     * @param format image format
     * @param options limits
     * @param ocr provider, or null
     */
    DefaultImagePartitioner(final DocumentFormat format, final ImageOptions options, final PdfOcrProvider ocr) {
        if (!FORMATS.contains(format)) {
            throw new IllegalArgumentException("Not an image format: " + format);
        }
        this.format = format;
        this.options = options;
        this.ocr = ocr;
    }

    @Override
    public DocumentFormat format() {
        return format;
    }

    @Override
    public ImageOptions options() {
        return options;
    }

    @Override
    public void partition(final PartitionContext context) throws IOException {
        NestedTableSupport.check(this, context.options());
        if (ocr == null) {
            throw new PartitionException(PartitionException.Code.UNSUPPORTED_OPTION,
                    format + " text extraction requires an explicitly configured OCR provider "
                            + "(register ImagePartitioner.create(format, options, provider))");
        }
        final ByteBuffer input = context.binaryBuffer();
        final ImageReader reader = reader(input);
        try (final var stream = new ImageBufferInput(input)) {
            reader.setInput(stream, false, format != DocumentFormat.TIFF);
            final List<int[]> frames = frames(reader, input.remaining());
            final int jpegOrientation = format == DocumentFormat.JPEG ? ImageOrientation.jpeg(input) : 1;
            final var progress = context.progress();
            progress.beginUnits("pages", frames.size());
            final var aborted = new boolean[1];
            final var warnings = new ArrayList<String>();
            reader.addIIOReadProgressListener(new Abort(aborted));
            reader.addIIOReadWarningListener((source, warning) -> warnings.add(warning));
            for (int index = 0; index < frames.size(); index++) {
                ElementCollector.checkInterrupted();
                final int orientation = format == DocumentFormat.TIFF ? frames.get(index)[2] : jpegOrientation;
                final BufferedImage decoded = decode(reader, index, aborted, warnings);
                BufferedImage upright = null;
                try {
                    upright = ImageOrientation.upright(decoded, orientation);
                    recognize(context, upright, index, orientation);
                } finally {
                    if (upright != null && upright != decoded) {
                        upright.flush();
                    }
                    decoded.flush();
                }
                progress.unitCompleted(index);
            }
        } finally {
            reader.dispose();
        }
    }

    /**
     * Returns the JDK reader of this format after checking that the content is that format.
     * @param input image bytes
     * @return reader
     * @throws PartitionException if the content is not a decodable image of this format
     */
    private ImageReader reader(final ByteBuffer input) throws IOException {
        final var readers = ImageIO.getImageReadersByFormatName(switch (format) {
            case PNG -> "png";
            case JPEG -> "jpeg";
            default -> "tiff";
        });
        if (!readers.hasNext()) {
            throw new PartitionException(PartitionException.Code.UNSUPPORTED_FORMAT, "No " + format + " decoder");
        }
        final ImageReader reader = readers.next();
        try (final var probe = new ImageBufferInput(input)) {
            if (!reader.getOriginatingProvider().canDecodeInput(probe)) {
                reader.dispose();
                if (format == DocumentFormat.TIFF && bigTiff(input)) {
                    throw new PartitionException(
                            PartitionException.Code.UNSUPPORTED_FORMAT, "BigTIFF is not supported");
                }
                throw new PartitionException(
                        PartitionException.Code.MALFORMED_DOCUMENT, "Content is not a " + format + " image");
            }
        }
        return reader;
    }

    /**
     * Whether the input starts with a BigTIFF signature ({@code II+\0} or {@code MM\0+}).
     * @param input image bytes
     * @return true for BigTIFF
     */
    private static boolean bigTiff(final ByteBuffer input) {
        if (input.remaining() < 4) {
            return false;
        }
        final int at = input.position();
        final int a = input.get(at) & 0xff;
        final int b = input.get(at + 1) & 0xff;
        final int c = input.get(at + 2) & 0xff;
        final int d = input.get(at + 3) & 0xff;
        return a == 0x49 && b == 0x49 && c == 0x2b && d == 0 || a == 0x4d && b == 0x4d && c == 0 && d == 0x2b;
    }

    /**
     * Reads every page's dimensions (and, for TIFF, its orientation and data extents) from headers and enforces page
     * and pixel limits before any pixel is decoded.
     * @param reader positioned reader
     * @param length input length in bytes
     * @return width, height and orientation of each page
     * @throws IOException if headers are malformed or limits are exceeded
     */
    private List<int[]> frames(final ImageReader reader, final long length) throws IOException {
        final var frames = new ArrayList<int[]>();
        long total = 0;
        for (int index = 0; format == DocumentFormat.TIFF || index == 0; index++) {
            ElementCollector.checkInterrupted();
            final int width;
            final int height;
            try {
                width = reader.getWidth(index);
                height = reader.getHeight(index);
            } catch (final IndexOutOfBoundsException end) {
                break;
            } catch (final IOException | RuntimeException failure) {
                if (index >= options.maxFrames()) {
                    throw new PartitionException(PartitionException.Code.IMAGE_LIMIT,
                            "Image has more than maxFrames " + options.maxFrames() + " pages");
                }
                if (index == 0) {
                    throw malformed(failure);
                }
                throw new PartitionException(PartitionException.Code.MALFORMED_DOCUMENT,
                        "TIFF page " + (index + 1) + " header is malformed", failure);
            }
            if (index >= options.maxFrames()) {
                throw new PartitionException(PartitionException.Code.IMAGE_LIMIT,
                        "Image has more than maxFrames " + options.maxFrames() + " pages");
            }
            if (width < 1 || height < 1) {
                throw new PartitionException(PartitionException.Code.MALFORMED_DOCUMENT, "Image has no pixels");
            }
            final long pixels = (long) width * height;
            if (pixels > options.maxFramePixels()) {
                throw new PartitionException(PartitionException.Code.IMAGE_LIMIT,
                        "Image page " + (index + 1) + " has " + pixels + " pixels, more than maxFramePixels "
                                + options.maxFramePixels());
            }
            total += pixels;
            if (total > options.maxTotalPixels()) {
                throw new PartitionException(PartitionException.Code.IMAGE_LIMIT,
                        "Image pages exceed maxTotalPixels " + options.maxTotalPixels());
            }
            frames.add(new int[] {width, height, format == DocumentFormat.TIFF ? tiffPage(reader, index, length) : 1});
        }
        if (frames.isEmpty()) {
            throw new PartitionException(PartitionException.Code.MALFORMED_DOCUMENT, "Image has no pages");
        }
        return frames;
    }

    /**
     * Decodes one page, aborting on interruption and refusing truncated or corrupt data.
     * @param reader reader
     * @param index page
     * @param aborted abort flag set by the progress listener
     * @param warnings decoder warnings of this page
     * @return decoded raster
     * @throws IOException if decoding fails, is interrupted or warns
     */
    private BufferedImage decode(final ImageReader reader, final int index, final boolean[] aborted,
            final List<String> warnings) throws IOException {
        warnings.clear();
        final BufferedImage decoded;
        try {
            if (!reader.getImageTypes(index).hasNext()) {
                throw new PartitionException(
                        PartitionException.Code.UNSUPPORTED_FORMAT, format + " color type is not supported");
            }
            decoded = reader.read(index);
        } catch (final PartitionException failure) {
            throw failure;
        } catch (final IIOException failure) {
            if (Thread.currentThread().isInterrupted()) {
                throw interrupted(failure);
            }
            throw String.valueOf(failure.getMessage()).contains("nsupported")
                    ? new PartitionException(PartitionException.Code.UNSUPPORTED_FORMAT,
                              format + " variant is not supported: " + failure.getMessage(), failure)
                    : malformed(failure);
        } catch (final IOException | RuntimeException failure) {
            if (Thread.currentThread().isInterrupted()) {
                throw interrupted(failure);
            }
            throw malformed(failure);
        }
        if (aborted[0] || decoded == null) {
            if (decoded != null) {
                decoded.flush();
            }
            throw new InterruptedIOException("Document processing interrupted");
        }
        // libjpeg reports stray bytes between segments ("extraneous bytes before marker"), common in camera and
        // scanner files, as corrupt data although the image decodes completely; every other warning fails the page.
        final var fatal = warnings.stream().filter(warning -> !warning.contains("extraneous bytes")).findFirst();
        if (fatal.isPresent()) {
            decoded.flush();
            throw new PartitionException(PartitionException.Code.MALFORMED_DOCUMENT,
                    "Image page " + (index + 1) + " is truncated or corrupt: " + fatal.get());
        }
        return decoded;
    }

    /**
     * Reads a TIFF page's directory: its Orientation, and checks that every strip or tile lies inside the input, so a
     * declared byte count can never make the decoder allocate more than the input holds.
     * @param reader reader with metadata
     * @param index page
     * @param length input length in bytes
     * @return orientation 1 to 8
     * @throws PartitionException if the directory is unreadable or a strip or tile lies outside the input
     */
    private static int tiffPage(final ImageReader reader, final int index, final long length)
            throws PartitionException {
        final TIFFDirectory directory;
        try {
            directory = TIFFDirectory.createFromMetadata(reader.getImageMetadata(index));
        } catch (final IOException | RuntimeException failure) {
            throw new PartitionException(PartitionException.Code.MALFORMED_DOCUMENT,
                    "TIFF page " + (index + 1) + " directory is malformed", failure);
        }
        for (final int[] tags : new int[][] {{273, 279}, {324, 325}}) {
            final var offsets = directory.getTIFFField(tags[0]);
            final var counts = directory.getTIFFField(tags[1]);
            if (offsets == null && counts == null) {
                continue;
            }
            if (offsets == null || counts == null || offsets.getCount() != counts.getCount()) {
                throw new PartitionException(PartitionException.Code.MALFORMED_DOCUMENT,
                        "TIFF page " + (index + 1) + " has inconsistent strip or tile tables");
            }
            for (int item = 0; item < offsets.getCount(); item++) {
                final long offset = offsets.getAsLong(item);
                final long count = counts.getAsLong(item);
                if (offset < 0 || count < 0 || offset > length || count > length - offset) {
                    throw new PartitionException(PartitionException.Code.MALFORMED_DOCUMENT,
                            "TIFF page " + (index + 1) + " declares data outside the input");
                }
            }
        }
        final var orientation = directory.getTIFFField(ImageOrientation.TAG);
        return orientation == null ? 1 : ImageOrientation.valid(orientation.getAsInt(0));
    }

    /**
     * Runs OCR on an upright page and emits its lines in provider order.
     * @param context sink
     * @param upright upright raster
     * @param index page
     * @param orientation applied orientation
     * @throws IOException if OCR fails, exceeds limits or emission fails
     */
    private void recognize(final PartitionContext context, final BufferedImage upright, final int index,
            final int orientation) throws IOException {
        ElementCollector.checkInterrupted();
        final List<PdfOcrProvider.Line> lines;
        try {
            lines = ocr.recognize(upright, PdfOcrProvider.InputKind.PAGE);
        } catch (final PartitionException | InterruptedIOException failure) {
            throw failure;
        } catch (final IOException | RuntimeException failure) {
            throw new PartitionException(PartitionException.Code.OCR_FAILURE, "Image OCR provider failed", failure);
        }
        ElementCollector.checkInterrupted();
        if (lines == null) {
            throw new PartitionException(PartitionException.Code.OCR_FAILURE, "Image OCR provider returned null");
        }
        if (lines.size() > options.maxLinesPerFrame()) {
            throw new PartitionException(PartitionException.Code.OCR_LIMIT, "Image OCR exceeds maxLinesPerFrame");
        }
        long characters = 0;
        for (final var line : lines) {
            if (line == null || line.right() > upright.getWidth() || line.bottom() > upright.getHeight()) {
                throw new PartitionException(PartitionException.Code.OCR_FAILURE, "Image OCR line is outside the page");
            }
            characters += line.text().length();
        }
        if (characters > options.maxCharactersPerFrame()) {
            throw new PartitionException(PartitionException.Code.OCR_LIMIT, "Image OCR exceeds maxCharactersPerFrame");
        }
        int emitted = 0;
        for (final var line : lines) {
            ElementCollector.checkInterrupted();
            if (line.text().isBlank()) {
                continue;
            }
            final var metadata = new LinkedHashMap<String, Object>();
            metadata.put("page_number", index + 1);
            metadata.put("image_text_method", "ocr");
            if (orientation != 1) {
                metadata.put("image_orientation", orientation);
            }
            metadata.put("coordinates",
                    Map.of("system", "PixelSpace", "layout_width", (double) upright.getWidth(), "layout_height",
                            (double) upright.getHeight(), "points",
                            List.of(List.of(line.left(), line.top()), List.of(line.right(), line.top()),
                                    List.of(line.right(), line.bottom()), List.of(line.left(), line.bottom()))));
            context.emit(ElementType.UncategorizedText, line.text(), metadata);
            emitted++;
        }
        if (emitted == 0) {
            context.warn(
                    PartitionResult.WarningCode.IMAGE_NO_TEXT, "OCR recognized no text on image page " + (index + 1));
        }
    }

    /**
     * Describes undecodable content.
     * @param failure decoder failure
     * @return typed failure
     */
    private PartitionException malformed(final Throwable failure) {
        return new PartitionException(
                PartitionException.Code.MALFORMED_DOCUMENT, format + " image is malformed or truncated", failure);
    }

    /**
     * Describes a decode stopped by interruption.
     * @param failure decoder failure
     * @return interruption
     */
    private static InterruptedIOException interrupted(final Throwable failure) {
        final var interrupted = new InterruptedIOException("Document processing interrupted");
        interrupted.initCause(failure);
        return interrupted;
    }

    /**
     * Aborts a decode in progress when the processing thread is interrupted.
     * @param aborted flag set when a decode was aborted
     */
    private record Abort(boolean[] aborted) implements IIOReadProgressListener {
        @Override
        public void imageProgress(final ImageReader source, final float percentageDone) {
            if (Thread.currentThread().isInterrupted()) {
                aborted[0] = true;
                source.abort();
            }
        }

        @Override
        public void readAborted(final ImageReader source) {
            aborted[0] = true;
        }

        @Override
        public void sequenceStarted(final ImageReader source, final int minIndex) {
        }

        @Override
        public void sequenceComplete(final ImageReader source) {
        }

        @Override
        public void imageStarted(final ImageReader source, final int imageIndex) {
        }

        @Override
        public void imageComplete(final ImageReader source) {
        }

        @Override
        public void thumbnailStarted(final ImageReader source, final int imageIndex, final int thumbnailIndex) {
        }

        @Override
        public void thumbnailProgress(final ImageReader source, final float percentageDone) {
        }

        @Override
        public void thumbnailComplete(final ImageReader source) {
        }
    }
}
