package dev.docweave.partition;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Bounded local source loading and strict text decoding. DOCX/ZIP candidates retain binary payloads
 * for strategy validation. Other strong binary signatures fail before text
 * routing; structured filename hints precede HTML signatures. Explicit policies can override text
 * routing but cannot make binary signatures parseable. Preview sniffing is bounded to 8 KiB.
 * Decoded Text sources retain their original String (except leading-BOM removal), measure UTF-8
 * directly and allocate only a bounded encoded prefix; byte/file inputs still materialize fully.
 * File loading uses the open channel size as an allocation hint and verifies actual bytes, avoiding
 * growth/final-copy arrays for stable files while retaining exact payloads for binary strategies.
 */
final class SourceLoader {
    /**
     * Validated and decoded (or binary-retained) source content ready for format partitioning.
     *
     * @param text decoded document text, or {@code null} for binary formats such as DOCX
     * @param filename optional source filename
     * @param format resolved document format
     * @param encoding canonical charset name, or {@code "binary"} for binary sources
     * @param bytes measured input byte footprint
     * @param warnings non-fatal format detection or loading warnings
     * @param binary raw binary payload for binary formats, or {@code null} for text formats
     */
    record Content(String text, String filename, DocumentFormat format, String encoding, int bytes,
            List<PartitionResult.Warning> warnings, byte[] binary) {}

    /**
     * Intermediate raw source representation holding either full bytes or a bounded UTF-8 preview of decoded text.
     *
     * @param bytes full input bytes (for file/byte sources) or UTF-8 prefix bytes (for decoded text sources)
     * @param filename optional source filename
     * @param decodedText pre-decoded text when loaded from {@link DocumentSource.Text}, or {@code null}
     * @param size total UTF-8 or raw byte footprint of the input
     */
    private record Raw(byte[] bytes, String filename, String decodedText, int size) {
        /**
         * Creates a raw byte-backed source representation.
         *
         * @param bytes full input byte array
         * @param filename optional source filename
         */
        Raw(final byte[] bytes, final String filename) {
            this(bytes, filename, null, bytes.length);
        }
    }

    /**
     * Detected byte-order mark (BOM) charset and byte length.
     *
     * @param charset detected BOM charset, or {@code null} if no BOM is present
     * @param offset number of leading BOM bytes to skip
     */
    private record Bom(Charset charset, int offset) {}

    /** Leading markup pattern used to sniff HTML content when no authoritative structured extension is present. */
    private static final Pattern HTML =
            Pattern.compile("(?is)^\\s*(?:<!doctype\\s+html\\b|<(?:html|head|body|h[1-6]|p|div|ul|ol|table|article|"
                            + "section|pre|header|footer|main|li|blockquote|span|b|strong|a|em|meta|title)\\b)");
    /** Lowercase file extensions explicitly rejected when no format override or text signature matches. */
    private static final Set<String> UNSUPPORTED = Set.of("pdf", "doc", "ppt", "pptx", "xls", "xlsx", "png", "jpg",
            "jpeg", "gif", "zip", "gz", "json", "eml", "msg", "rtf", "epub", "odt");

    /**
     * Loads, validates, detects format/encoding, and decodes (or retains binary payload for) {@code source}.
     *
     * @param source input document source
     * @param options partition options and resource limits
     * @return loaded {@link Content}
     * @throws IOException if I/O fails, limits are exceeded, encoding/format is invalid, or interrupted
     */
    Content load(final DocumentSource source, final PartitionOptions options) throws IOException {
        ElementCollector.checkInterrupted();
        final Raw raw = switch (source) {
            case final DocumentSource.File file -> {
                try (final var channel = Files.newByteChannel(file.path())) {
                    yield new Raw(BoundedFileInput.read(channel, options.maxInputBytes()),
                            file.path().getFileName().toString());
                }
            }
            case final DocumentSource.Bytes bytes -> {
                if (bytes.size() > options.maxInputBytes()) {
                    throw tooLarge(options);
                }
                yield new Raw(bytes.content(), bytes.filename());
            }
            case final DocumentSource.Text text -> {
                final int size = DecodedTextInput.measure(text.content(), options.maxInputBytes());
                yield new Raw(DecodedTextInput.preview(text.content()), text.filename(), text.content(), size);
            }
        };
        if (raw.size() > options.maxInputBytes()) {
            throw tooLarge(options);
        }
        ElementCollector.checkInterrupted();
        final boolean pdf = starts(raw.bytes(), 0x25, 0x50, 0x44, 0x46, 0x2d);
        if (options.format() == DocumentFormat.PDF
                || options.format() == null && (pdf || extension(raw.filename()).equals("pdf"))) {
            if (raw.decodedText() != null) {
                throw new PartitionException(
                        PartitionException.Code.UNSUPPORTED_FORMAT, "PDF requires a file or binary source");
            }
            final var notices = new ArrayList<PartitionResult.Warning>();
            if (pdf && !extension(raw.filename()).isEmpty() && !extension(raw.filename()).equals("pdf")) {
                notices.add(new PartitionResult.Warning(
                        PartitionResult.WarningCode.FORMAT_MISMATCH, "PDF signature overrides filename extension"));
            }
            return new Content(
                    null, raw.filename(), DocumentFormat.PDF, "binary", raw.size(), List.copyOf(notices), raw.bytes());
        }
        final boolean zip = starts(raw.bytes(), 0x50, 0x4b, 3, 4) || starts(raw.bytes(), 0x50, 0x4b, 5, 6);
        final boolean namedDocx = extension(raw.filename()).equals("docx");
        if (options.format() == DocumentFormat.DOCX || options.format() == null && (namedDocx || zip)) {
            if (raw.decodedText() != null) {
                throw new PartitionException(
                        PartitionException.Code.UNSUPPORTED_FORMAT, "DOCX requires a file or binary source");
            }
            final var notices = new ArrayList<PartitionResult.Warning>();
            if (Set.of("txt", "text", "log", "html", "htm", "md", "markdown", "csv", "tsv", "xml")
                            .contains(extension(raw.filename()))) {
                notices.add(new PartitionResult.Warning(
                        PartitionResult.WarningCode.FORMAT_MISMATCH, "DOCX package overrides filename extension"));
            }
            return new Content(
                    null, raw.filename(), DocumentFormat.DOCX, "binary", raw.size(), List.copyOf(notices), raw.bytes());
        }
        if (binarySignature(raw.bytes())) {
            throw new PartitionException(
                    PartitionException.Code.UNSUPPORTED_FORMAT, "Binary document format is not yet supported");
        }

        final Bom bom = bom(raw.bytes());
        Charset previewEncoding = bom.charset() != null                                    ? bom.charset()
                                  : raw.decodedText() == null && options.charset() != null ? options.charset()
                                                                                           : StandardCharsets.UTF_8;
        if (bom.charset() == null && raw.decodedText() == null && options.charset() == null
                && (options.format() == null || options.format() == DocumentFormat.XML)) {
            final Charset xmlEncoding = xmlCharset(raw.bytes());
            if (xmlEncoding != null) {
                previewEncoding = xmlEncoding;
            }
        }
        final String preview = new String(
                raw.bytes(), bom.offset(), Math.min(raw.bytes().length - bom.offset(), 8192), previewEncoding);
        final String extension = extension(raw.filename());
        final DocumentFormat namedFormat = switch (extension) {
            case "html", "htm" -> DocumentFormat.HTML;
            case "txt", "text", "log" -> DocumentFormat.TXT;
            case "md", "markdown" -> DocumentFormat.MARKDOWN;
            case "csv" -> DocumentFormat.CSV;
            case "tsv" -> DocumentFormat.TSV;
            case "xml" -> DocumentFormat.XML;
            default -> null;
        };
        // Structured text extensions are authoritative: markup may itself be a cell or Markdown content.
        final boolean structured = namedFormat == DocumentFormat.MARKDOWN || namedFormat == DocumentFormat.CSV
                                   || namedFormat == DocumentFormat.TSV || namedFormat == DocumentFormat.XML;
        final DocumentFormat detected = structured                                   ? namedFormat
                                        : preview.stripLeading().startsWith("<?xml") ? DocumentFormat.XML
                                        : looksLikeHtml(preview)                     ? DocumentFormat.HTML
                                                                                     : namedFormat;
        if (options.format() == null && detected == null && UNSUPPORTED.contains(extension)) {
            throw new PartitionException(
                    PartitionException.Code.UNSUPPORTED_FORMAT, "Unsupported file extension: " + extension);
        }
        final DocumentFormat format = options.format() != null ? options.format()
                                      : detected == null       ? DocumentFormat.TXT
                                                               : detected;
        final var warnings = new ArrayList<PartitionResult.Warning>();
        if (namedFormat != null && namedFormat != format) {
            warnings.add(new PartitionResult.Warning(PartitionResult.WarningCode.FORMAT_MISMATCH,
                    "Content or explicit format overrides filename extension"));
        }

        Charset encoding = raw.decodedText() != null ? StandardCharsets.UTF_8 : options.charset();
        if (encoding != null && bom.charset() != null && !encoding.equals(bom.charset())) {
            if (encoding.name().equals("UTF-16") && bom.charset().name().startsWith("UTF-16")) {
                encoding = bom.charset();
            } else if (encoding.name().equals("UTF-32") && bom.charset().name().startsWith("UTF-32")) {
                encoding = bom.charset();
            } else {
                throw new PartitionException(
                        PartitionException.Code.INVALID_ENCODING, "Byte-order mark conflicts with explicit charset");
            }
        }
        if (encoding == null) {
            encoding = bom.charset();
        }
        if (encoding == null && format == DocumentFormat.XML) {
            encoding = xmlCharset(raw.bytes());
        }
        if (encoding == null && format == DocumentFormat.HTML) {
            encoding = htmlCharset(raw.bytes());
        }
        if (encoding == null) {
            encoding = StandardCharsets.UTF_8;
        }
        try {
            final String text = raw.decodedText() != null
                                        ? (bom.offset() == 0 ? raw.decodedText() : raw.decodedText().substring(1))
                                        : StrictTextDecoder.decode(raw.bytes(), bom.offset(),
                                                  raw.bytes().length - bom.offset(), encoding);
            ElementCollector.checkInterrupted();
            TextSupport.validateControls(text);
            return new Content(text, raw.filename(), format, encoding.name(), raw.size(), List.copyOf(warnings), null);
        } catch (final CharacterCodingException error) {
            throw new PartitionException(
                    PartitionException.Code.INVALID_ENCODING, "Input is not valid " + encoding.name(), error);
        }
    }

    /**
     * Detects XML character encoding from 4-byte UCS-4/UTF-16 signatures or an XML prolog declaration.
     *
     * @param bytes raw input bytes
     * @return detected XML {@link Charset}, or {@code null} if none is declared
     * @throws PartitionException if the declared XML encoding name is invalid or unsupported
     */
    private static Charset xmlCharset(final byte[] bytes) throws PartitionException {
        if (starts(bytes, 0, 0, 0, 0x3c)) {
            return Charset.forName("UTF-32BE");
        }
        if (starts(bytes, 0x3c, 0, 0, 0)) {
            return Charset.forName("UTF-32LE");
        }
        if (starts(bytes, 0, 0x3c, 0, 0x3f)) {
            return StandardCharsets.UTF_16BE;
        }
        if (starts(bytes, 0x3c, 0, 0x3f, 0)) {
            return StandardCharsets.UTF_16LE;
        }
        final String preview = new String(bytes, 0, Math.min(bytes.length, 8192), StandardCharsets.ISO_8859_1);
        final var declaration =
                Pattern.compile("^<\\?xml\\s+[^?]*?encoding\\s*=\\s*(['\"])([^'\"]+)\\1").matcher(preview);
        if (!declaration.find()) {
            return null;
        }
        try {
            return Charset.forName(declaration.group(2));
        } catch (final IllegalArgumentException error) {
            throw new PartitionException(PartitionException.Code.INVALID_ENCODING, "Unsupported XML encoding", error);
        }
    }

    /**
     * Delegates HTML {@code <meta>} charset sniffing to the installed HTML {@link PartitionerProvider}, if present.
     *
     * @param bytes raw input bytes
     * @return detected HTML {@link Charset}, or {@code null} if not declared or no HTML provider is installed
     * @throws PartitionException if a declared HTML charset is invalid or unsupported
     */
    private static Charset htmlCharset(final byte[] bytes) throws PartitionException {
        final byte[] prefix = java.util.Arrays.copyOf(bytes, Math.min(bytes.length, 8192));
        for (final var provider : java.util.ServiceLoader.load(PartitionerProvider.class)) {
            if (provider.format() == DocumentFormat.HTML) {
                return provider.detectCharset(prefix);
            }
        }
        return null;
    }

    /**
     * Checks whether the leading preview text begins with an HTML doctype or common HTML element tag.
     *
     * @param preview decoded leading preview text
     * @return {@code true} if the preview matches HTML markup heuristics
     */
    private static boolean looksLikeHtml(final String preview) {
        String remaining = preview.stripLeading();
        while (remaining.startsWith("<!--")) {
            final int end = remaining.indexOf("-->");
            if (end < 0) {
                return false;
            }
            remaining = remaining.substring(end + 3).stripLeading();
        }
        return HTML.matcher(remaining).find();
    }

    /**
     * Inspects the leading bytes for a UTF-32, UTF-8, or UTF-16 byte-order mark.
     *
     * @param bytes raw input bytes
     * @return detected {@link Bom} (with {@code null} charset and {@code 0} offset when absent)
     */
    private static Bom bom(final byte[] bytes) {
        if (starts(bytes, 0, 0, 0xfe, 0xff)) {
            return new Bom(Charset.forName("UTF-32BE"), 4);
        }
        if (starts(bytes, 0xff, 0xfe, 0, 0)) {
            return new Bom(Charset.forName("UTF-32LE"), 4);
        }
        if (starts(bytes, 0xef, 0xbb, 0xbf)) {
            return new Bom(StandardCharsets.UTF_8, 3);
        }
        if (starts(bytes, 0xfe, 0xff)) {
            return new Bom(StandardCharsets.UTF_16BE, 2);
        }
        if (starts(bytes, 0xff, 0xfe)) {
            return new Bom(StandardCharsets.UTF_16LE, 2);
        }
        return new Bom(null, 0);
    }

    /**
     * Checks whether {@code bytes} begins with a known binary file magic signature (OLE2, PDF, ZIP, PNG, JPEG, GIF,
     * GZIP).
     *
     * @param bytes raw input bytes
     * @return {@code true} if a known binary signature is present
     */
    private static boolean binarySignature(final byte[] bytes) {
        return starts(bytes, 0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1)
                || starts(bytes, 0x25, 0x50, 0x44, 0x46, 0x2d) || starts(bytes, 0x50, 0x4b, 3, 4)
                || starts(bytes, 0x50, 0x4b, 5, 6) || starts(bytes, 0x50, 0x4b, 7, 8)
                || starts(bytes, 0x89, 0x50, 0x4e, 0x47) || starts(bytes, 0xff, 0xd8)
                || starts(bytes, 0x47, 0x49, 0x46, 0x38) || starts(bytes, 0x1f, 0x8b);
    }

    /**
     * Checks whether {@code bytes} starts with the given unsigned byte sequence.
     *
     * @param bytes byte array to inspect
     * @param prefix expected leading unsigned byte values
     * @return {@code true} if {@code bytes} begins with {@code prefix}
     */
    private static boolean starts(final byte[] bytes, final int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (Byte.toUnsignedInt(bytes[i]) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Extracts the lowercase file extension after the last dot in {@code filename}.
     *
     * @param filename filename string, or {@code null}
     * @return lowercase extension without leading dot, or empty string if none
     */
    private static String extension(final String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Creates a {@link PartitionException} indicating that the input exceeds {@link PartitionOptions#maxInputBytes()}.
     *
     * @param options active partition options
     * @return input-too-large partition exception
     */
    private static PartitionException tooLarge(final PartitionOptions options) {
        return new PartitionException(
                PartitionException.Code.INPUT_TOO_LARGE, "Document exceeds maxInputBytes=" + options.maxInputBytes());
    }
}
