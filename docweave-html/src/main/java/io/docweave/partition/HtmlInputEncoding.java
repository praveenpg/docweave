package io.docweave.partition;

import org.jsoup.parser.Parser;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Installed HTML-module head sniffing, separate from dependency-free source loading. */
final class HtmlInputEncoding {
    /** Pattern matching a {@code charset=...} parameter inside an HTML {@code content-type} meta attribute. */
    private static final Pattern CHARSET = Pattern.compile("(?i)\\bcharset\\s*=\\s*[\"']?([a-z0-9._-]+)");

    /** Prevents instantiation of this static utility class. */
    private HtmlInputEncoding() {
    }

    /**
     * Inspects the leading 8 KiB of HTML bytes for a {@code <meta>} charset declaration.
     *
     * @param bytes raw HTML input bytes
     * @return declared {@link Charset}, or {@code null} if no meta charset is present
     * @throws PartitionException if the declared charset name is invalid or unsupported
     */
    static Charset detect(final byte[] bytes) throws PartitionException {
        final String head = new String(bytes, 0, Math.min(bytes.length, 8192), StandardCharsets.ISO_8859_1);
        final var document = Parser.htmlParser().parseInput(head, "");
        // Inspect <meta charset="..."> and <meta http-equiv="content-type" content="...charset=..."> in document order.
        for (final var tag : document.head().getElementsByTag("meta")) {
            String name = tag.hasAttr("charset") ? tag.attr("charset").strip() : null;
            if (name == null && tag.attr("http-equiv").equalsIgnoreCase("content-type")) {
                final var charset = CHARSET.matcher(tag.attr("content"));
                if (charset.find()) {
                    name = charset.group(1);
                }
            }
            if (name != null && !name.isEmpty()) {
                try {
                    return Charset.forName(name);
                } catch (final IllegalArgumentException error) {
                    throw new PartitionException(
                            PartitionException.Code.INVALID_ENCODING, "Unsupported HTML charset: " + name, error);
                }
            }
        }
        return null;
    }
}
