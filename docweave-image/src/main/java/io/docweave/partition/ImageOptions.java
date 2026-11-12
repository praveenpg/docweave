package io.docweave.partition;

/**
 * Resource limits of standalone image partitioning, checked from image headers before any pixel data is decoded.
 *
 * <p>OCR time and process concurrency are limits of the configured OCR provider (for Tesseract,
 * {@code TesseractOptions.timeout()} and {@code maxConcurrentProcesses()}); a caller's deadline applies through thread
 * interruption, checked between pages, and an abort of a page decode in progress is requested from ImageIO's progress
 * callback.</p>
 *
 * @param maxFrames most pages (TIFF directories) in one input; PNG and JPEG inputs have exactly one
 * @param maxFramePixels most decoded pixels (width × height) of any one page
 * @param maxTotalPixels most decoded pixels of all pages together, bounding the work of one input
 * @param maxLinesPerFrame most OCR lines accepted from one page
 * @param maxCharactersPerFrame most OCR characters accepted from one page
 */
public record ImageOptions(
        int maxFrames, long maxFramePixels, long maxTotalPixels, int maxLinesPerFrame, int maxCharactersPerFrame) {
    /**
     * Validates positive limits.
     * @throws IllegalArgumentException if a limit is not positive
     */
    public ImageOptions {
        if (maxFrames < 1 || maxFramePixels < 1 || maxTotalPixels < 1 || maxLinesPerFrame < 1
                || maxCharactersPerFrame < 1) {
            throw new IllegalArgumentException("Image limits must be positive");
        }
    }

    /**
     * Returns the defaults: 100 pages, 16 million pixels per page (Tesseract's default input limit), 200 million
     * pixels in total, and 10,000 lines and 1,000,000 characters of OCR output per page.
     * @return default limits
     */
    public static ImageOptions defaults() {
        return new ImageOptions(100, 16_000_000, 200_000_000, 10_000, 1_000_000);
    }

    /**
     * Returns these limits with another page count limit.
     * @param frames most pages
     * @return options
     */
    public ImageOptions withMaxFrames(final int frames) {
        return new ImageOptions(frames, maxFramePixels, maxTotalPixels, maxLinesPerFrame, maxCharactersPerFrame);
    }

    /**
     * Returns these limits with other decoded-pixel limits.
     * @param framePixels most pixels of one page
     * @param totalPixels most pixels of all pages
     * @return options
     */
    public ImageOptions withMaxPixels(final long framePixels, final long totalPixels) {
        return new ImageOptions(maxFrames, framePixels, totalPixels, maxLinesPerFrame, maxCharactersPerFrame);
    }
}
