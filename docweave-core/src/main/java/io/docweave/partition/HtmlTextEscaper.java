package io.docweave.partition;

/**
 * Dependency-free table-text escaping shared across native format modules.
 * Matches the previous jsoup default UTF-8 escaping for quotes, markup, nonbreaking spaces,
 * disallowed C0 controls and unpaired surrogates. Valid supplementary Unicode is retained.
 * This is text serialization, not HTML parsing or a sanitizer for arbitrary markup.
 */
final class HtmlTextEscaper {
    /** Prevents instantiation of this static utility class. */
    private HtmlTextEscaper() {
    }

    /**
     * Escapes plain cell text for safe inclusion in compact HTML table serialization.
     *
     * @param value plain text string to escape
     * @return HTML-escaped string
     */
    static String escape(final String value) {
        final var output = new StringBuilder();
        for (int offset = 0; offset < value.length();) {
            final int begin = offset;
            final int point = value.codePointAt(offset);
            offset += Character.charCount(point);
            switch (point) {
            case '&' -> output.append("&amp;");
            case '<' -> output.append("&lt;");
            case '>' -> output.append("&gt;");
            case '"' -> output.append("&quot;");
            case '\'' -> output.append("&apos;");
            case 0xa0 -> output.append("&nbsp;");
            default -> {
                if (point < 32 && point != 9 && point != 10 && point != 13 || point >= 0xd800 && point <= 0xdfff) {
                    output.append("&#x").append(Integer.toHexString(point)).append(';');
                } else {
                    output.append(value, begin, offset);
                }
            }
            }
        }
        return output.toString();
    }
}
