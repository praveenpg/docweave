package io.docweave.partition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Data-only reader for a leading Markdown front-matter block.
 *
 * <p>The block starts on the first line ({@code ---}, optionally preceded by a byte-order mark and followed by spaces)
 * and ends at the next line that is {@code ---} or {@code ...}. Without that closing line the input has no front
 * matter. The content is read as a restricted YAML subset and never handed to a YAML library: top-level
 * {@code key: value} pairs with plain keys; plain, single-quoted or double-quoted string scalars; block sequences
 * ({@code - item}) and single-line flow sequences ({@code [a, "b"]}) of scalars; and literal ({@code |}) or folded
 * ({@code >}) block scalars with optional {@code -}/{@code +} chomping. Comments and blank lines are ignored. Values are
 * always strings or lists of strings; {@code true}, {@code 3} and {@code null} are not typed. Tags, anchors, aliases,
 * directives, nested mappings, flow mappings, multi-line plain or quoted scalars, tab indentation and duplicate keys
 * make the block malformed, as do more than {@link #MAX_VALUES} keys and sequence items together. Because the values are
 * repeated on every element, the limits are small. The first content line must be a {@code key:} line or a comment,
 * and the closing delimiter must follow within {@link #MAX_CHARACTERS} characters; otherwise the input has no front
 * matter, so a leading thematic break followed by ordinary Markdown keeps its meaning.</p>
 */
final class MarkdownFrontMatter {
    /** Largest block content, in UTF-16 characters, within which the closing delimiter must appear. */
    static final int MAX_CHARACTERS = 4 * 1024;
    /** Largest accepted number of keys and sequence items together. */
    static final int MAX_VALUES = 64;

    /**
     * Outcome of splitting a document.
     * @param values parsed values in source order, empty when absent, malformed or empty
     * @param invalid literal block content when the block is malformed, otherwise {@code null}
     * @param reason malformed-block diagnostic without document content, otherwise {@code null}
     * @param body Markdown after the block, or the whole input when there is no block
     */
    record Split(Map<String, Object> values, String invalid, String reason, String body) {}

    /** Signals malformed content with a content-free diagnostic. */
    private static final class Malformed extends Exception {
        /**
         * Creates a diagnostic.
         * @param reason description without document content
         */
        Malformed(final String reason) {
            super(reason, null, false, false);
        }
    }

    /** Static helpers only. */
    private MarkdownFrontMatter() {}

    /**
     * Separates and reads a leading front-matter block.
     * @param text whole Markdown input
     * @return values, malformed literal content or the unchanged input
     */
    static Split split(final String text) {
        int start = text.startsWith("﻿") ? 1 : 0;
        final int firstEnd = lineEnd(text, start);
        if (!delimiter(text.substring(start, firstEnd), false)) {
            return new Split(Map.of(), null, null, text);
        }
        final var lines = new ArrayList<String>();
        final int contentStart = next(text, firstEnd);
        int position = contentStart;
        int close = -1;
        while (position < text.length() && position - contentStart <= MAX_CHARACTERS) {
            final int end = lineEnd(text, position);
            final String line = text.substring(position, end);
            if (delimiter(line, true)) {
                close = next(text, end);
                break;
            }
            if (lines.isEmpty() && !opening(line)) {
                return new Split(Map.of(), null, null, text);
            }
            lines.add(line);
            position = next(text, end);
        }
        if (close < 0 || lines.stream().noneMatch(MarkdownFrontMatter::keyLine)
                && lines.stream().anyMatch(line -> !line.isBlank())) {
            // No closing line in time, or only comments: ordinary Markdown (a "# Heading" between two thematic
            // breaks keeps its text).
            return new Split(Map.of(), null, null, text);
        }
        final String content = String.join("\n", lines);
        final String body = text.substring(close);
        try {
            return new Split(Collections.unmodifiableMap(parse(lines)), null, null, body);
        } catch (final Malformed error) {
            return new Split(Map.of(), content, error.getMessage(), body);
        }
    }

    /**
     * Tests for a top-level {@code key:} line.
     * @param line content line
     * @return whether the line starts a top-level key
     */
    private static boolean keyLine(final String line) {
        return !line.isEmpty() && line.charAt(0) != ' ' && line.charAt(0) != '\t' && line.charAt(0) != '#'
               && keyEnd(line) > 0;
    }

    /**
     * Tests whether the first content line can open front matter: a top-level {@code key:} line or a comment.
     * @param line first line after the opening delimiter
     * @return whether the block is read as front matter
     */
    private static boolean opening(final String line) {
        if (line.isEmpty() || line.charAt(0) == ' ' || line.charAt(0) == '\t') {
            return false;
        }
        return line.charAt(0) == '#' || keyEnd(line) > 0;
    }

    /**
     * Tests for a delimiter line.
     * @param line line without its terminator
     * @param closing whether {@code ...} is also accepted
     * @return whether the line is a delimiter followed only by spaces or tabs
     */
    private static boolean delimiter(final String line, final boolean closing) {
        final String trimmed = line.stripTrailing();
        return trimmed.equals("---") || closing && trimmed.equals("...");
    }

    /**
     * Finds the end of the line starting at a position.
     * @param text input
     * @param from line start
     * @return index of the line terminator or the input length
     */
    private static int lineEnd(final String text, final int from) {
        int index = from;
        while (index < text.length() && text.charAt(index) != '\n' && text.charAt(index) != '\r') {
            index++;
        }
        return index;
    }

    /**
     * Skips one line terminator.
     * @param text input
     * @param end index returned by {@link #lineEnd}
     * @return start of the next line
     */
    private static int next(final String text, final int end) {
        if (end < text.length() && text.charAt(end) == '\r') {
            return end + 1 < text.length() && text.charAt(end + 1) == '\n' ? end + 2 : end + 1;
        }
        return end < text.length() ? end + 1 : end;
    }

    /**
     * Reads the block content.
     * @param lines content lines without terminators
     * @return values in source order
     * @throws Malformed if the content is outside the supported subset
     */
    private static Map<String, Object> parse(final List<String> lines) throws Malformed {
        final var values = new LinkedHashMap<String, Object>();
        int count = 0;
        int index = 0;
        while (index < lines.size()) {
            final String line = lines.get(index);
            if (ignorable(line)) {
                index++;
                continue;
            }
            if (line.substring(0, indent(line)).indexOf('\t') >= 0) {
                throw new Malformed("tab indentation is not supported");
            }
            if (indent(line) > 0) {
                throw new Malformed("nested or continued values are not supported");
            }
            final int colon = keyEnd(line);
            if (colon < 0) {
                throw new Malformed("expected a key: value pair");
            }
            final String key = line.substring(0, colon).strip();
            plainKey(key);
            if (values.containsKey(key)) {
                throw new Malformed("duplicate key");
            }
            if (++count > MAX_VALUES) {
                throw new Malformed("front matter has more than " + MAX_VALUES + " values");
            }
            final String rest = line.substring(colon + 1).strip();
            index++;
            if (rest.isEmpty() || rest.startsWith("#")) {
                final var items = new ArrayList<String>();
                int itemIndent = -1;
                while (index < lines.size()) {
                    final String item = lines.get(index);
                    if (ignorable(item)) {
                        index++;
                        continue;
                    }
                    final String stripped = item.stripLeading();
                    if (item.startsWith("\t") || !(stripped.equals("-") || stripped.startsWith("- "))) {
                        break;
                    }
                    if (itemIndent >= 0 && indent(item) != itemIndent) {
                        throw new Malformed("sequence items must share one indentation");
                    }
                    itemIndent = indent(item);
                    if (++count > MAX_VALUES) {
                        throw new Malformed("front matter has more than " + MAX_VALUES + " values");
                    }
                    items.add(item(stripped.substring(1).strip()));
                    index++;
                }
                if (index < lines.size() && !ignorable(lines.get(index)) && indent(lines.get(index)) > 0) {
                    throw new Malformed("nested or continued values are not supported");
                }
                values.put(key, items.isEmpty() ? "" : List.copyOf(items));
            } else if (rest.charAt(0) == '|' || rest.charAt(0) == '>') {
                final var block = new ArrayList<String>();
                while (index < lines.size() && (lines.get(index).isBlank() || indent(lines.get(index)) > 0)) {
                    block.add(lines.get(index));
                    index++;
                }
                values.put(key, blockScalar(rest, block));
            } else if (rest.charAt(0) == '[') {
                final List<String> items = flow(rest);
                count += items.size();
                if (count > MAX_VALUES) {
                    throw new Malformed("front matter has more than " + MAX_VALUES + " values");
                }
                values.put(key, items);
            } else {
                values.put(key, scalar(rest));
            }
        }
        return values;
    }

    /**
     * Tests for a blank or comment line.
     * @param line content line
     * @return whether the line carries no value
     */
    private static boolean ignorable(final String line) {
        final String stripped = line.strip();
        return stripped.isEmpty() || stripped.startsWith("#");
    }

    /**
     * Counts leading spaces and tabs.
     * @param line content line
     * @return indentation width in characters
     */
    private static int indent(final String line) {
        int index = 0;
        while (index < line.length() && (line.charAt(index) == ' ' || line.charAt(index) == '\t')) {
            index++;
        }
        return index;
    }

    /**
     * Finds the key separator: the first colon followed by a space, a tab or the end of the line.
     * @param line top-level line
     * @return colon index, or -1
     */
    private static int keyEnd(final String line) {
        for (int index = 0; index < line.length(); index++) {
            if (line.charAt(index) == ':'
                    && (index + 1 == line.length() || line.charAt(index + 1) == ' ' || line.charAt(index + 1) == '\t')) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Rejects empty keys and keys that would need YAML structure to interpret.
     * @param key stripped key
     * @throws Malformed if the key is not a plain scalar
     */
    private static void plainKey(final String key) throws Malformed {
        if (key.isEmpty() || "-?:,[]{}#&*!|>'\"%@`".indexOf(key.charAt(0)) >= 0) {
            throw new Malformed("keys must be plain scalars");
        }
    }

    /**
     * Reads one sequence item.
     * @param value item text after the dash
     * @return string value
     * @throws Malformed if the item is a nested collection or unsupported node
     */
    private static String item(final String value) throws Malformed {
        if (value.isEmpty() || value.startsWith("#")) {
            return "";
        }
        if (value.startsWith("-") && (value.length() == 1 || value.charAt(1) == ' ') || keyEnd(value) >= 0
                && value.charAt(0) != '"' && value.charAt(0) != '\'') {
            throw new Malformed("nested collections are not supported");
        }
        if (value.charAt(0) == '[' || value.charAt(0) == '|' || value.charAt(0) == '>') {
            throw new Malformed("nested collections are not supported");
        }
        return scalar(value);
    }

    /**
     * Reads a single-line scalar.
     * @param value stripped value text
     * @return string value
     * @throws Malformed if the value is a tag, anchor, alias, mapping or unterminated quote
     */
    private static String scalar(final String value) throws Malformed {
        final char first = value.charAt(0);
        if (first == '!' || first == '&' || first == '*') {
            throw new Malformed("tags, anchors and aliases are not supported");
        }
        if (first == '{' || first == '%' || first == '@' || first == '`') {
            throw new Malformed("unsupported value syntax");
        }
        if (first == '"' || first == '\'') {
            final var quoted = new StringBuilder();
            final int end = quoted(value, 0, quoted);
            trailing(value, end);
            return quoted.toString();
        }
        final int comment = comment(value);
        final String plain = (comment >= 0 ? value.substring(0, comment) : value).strip();
        if (keyEnd(plain) >= 0) {
            throw new Malformed("nested mappings are not supported");
        }
        if (plain.startsWith("- ") || plain.equals("-") || plain.startsWith("? ") || plain.startsWith(", ")
                || plain.startsWith("]")) {
            throw new Malformed("unsupported value syntax");
        }
        return plain;
    }

    /**
     * Finds the start of a trailing comment: a {@code #} preceded by a space or tab.
     * @param value value text
     * @return index of the separating whitespace, or -1
     */
    private static int comment(final String value) {
        for (int index = 1; index < value.length(); index++) {
            if (value.charAt(index) == '#' && (value.charAt(index - 1) == ' ' || value.charAt(index - 1) == '\t')) {
                return index - 1;
            }
        }
        return -1;
    }

    /**
     * Reads a quoted scalar starting at an index.
     * @param value line text
     * @param start index of the opening quote
     * @param out receives the unescaped value
     * @return index after the closing quote
     * @throws Malformed if the quote is unterminated or an escape is invalid
     */
    private static int quoted(final String value, final int start, final StringBuilder out) throws Malformed {
        final char quote = value.charAt(start);
        int index = start + 1;
        while (index < value.length()) {
            final char c = value.charAt(index);
            if (quote == '\'' && c == '\'') {
                if (index + 1 < value.length() && value.charAt(index + 1) == '\'') {
                    out.append('\'');
                    index += 2;
                    continue;
                }
                return index + 1;
            }
            if (quote == '"' && c == '"') {
                return index + 1;
            }
            if (quote == '"' && c == '\\') {
                if (index + 1 >= value.length()) {
                    throw new Malformed("invalid escape");
                }
                final char escape = value.charAt(index + 1);
                index += 2;
                switch (escape) {
                case '"', '\\', '/' -> out.append(escape);
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case '0' -> out.append('\0');
                case ' ' -> out.append(' ');
                case 'u' -> {
                    if (index + 4 > value.length() || !value.substring(index, index + 4).matches("[0-9A-Fa-f]{4}")) {
                        throw new Malformed("invalid escape");
                    }
                    final char escaped = (char) Integer.parseInt(value.substring(index, index + 4), 16);
                    if (Character.isSurrogate(escaped)) {
                        throw new Malformed("surrogate escapes are not supported");
                    }
                    out.append(escaped);
                    index += 4;
                }
                default -> throw new Malformed("invalid escape");
                }
                continue;
            }
            out.append(c);
            index++;
        }
        throw new Malformed("unterminated quoted value");
    }

    /**
     * Allows only whitespace or a comment after a closed value.
     * @param value line text
     * @param end index after the value
     * @throws Malformed if other content follows
     */
    private static void trailing(final String value, final int end) throws Malformed {
        final String rest = value.substring(end).strip();
        if (!rest.isEmpty() && !rest.startsWith("#")) {
            throw new Malformed("unexpected content after a quoted value");
        }
    }

    /**
     * Reads a single-line flow sequence of scalars.
     * @param value stripped value starting with {@code [}
     * @return items
     * @throws Malformed if the sequence is unterminated, nested or contains unsupported nodes
     */
    private static List<String> flow(final String value) throws Malformed {
        final var items = new ArrayList<String>();
        int index = 1;
        boolean expectItem = true;
        while (true) {
            while (index < value.length() && value.charAt(index) == ' ') {
                index++;
            }
            if (index >= value.length()) {
                throw new Malformed("unterminated flow sequence");
            }
            final char c = value.charAt(index);
            if (c == ']') {
                if (expectItem && !items.isEmpty()) {
                    throw new Malformed("empty flow sequence item");
                }
                trailing(value, index + 1);
                return List.copyOf(items);
            }
            if (!expectItem) {
                if (c != ',') {
                    throw new Malformed("expected a comma in flow sequence");
                }
                index++;
                expectItem = true;
                continue;
            }
            if (c == '"' || c == '\'') {
                final var quoted = new StringBuilder();
                index = quoted(value, index, quoted);
                items.add(quoted.toString());
            } else {
                int end = index;
                while (end < value.length() && value.charAt(end) != ',' && value.charAt(end) != ']') {
                    end++;
                }
                final String plain = value.substring(index, end).strip();
                if (plain.isEmpty() || "[{!&*#".indexOf(plain.charAt(0)) >= 0 || keyEnd(plain) >= 0) {
                    throw new Malformed("unsupported flow sequence item");
                }
                items.add(plain);
                index = end;
            }
            expectItem = false;
        }
    }

    /**
     * Reads a literal or folded block scalar.
     * @param header indicator text, such as {@code |}, {@code >-} or {@code |+}
     * @param lines following lines that are blank or indented
     * @return string value with YAML chomping applied
     * @throws Malformed if the header is not supported
     */
    private static String blockScalar(final String header, final List<String> lines) throws Malformed {
        final int comment = comment(header);
        final String indicator = (comment >= 0 ? header.substring(0, comment) : header).strip();
        if (!indicator.matches("[|>][+-]?")) {
            throw new Malformed("unsupported block scalar header");
        }
        final boolean folded = indicator.charAt(0) == '>';
        final char chomping = indicator.length() > 1 ? indicator.charAt(1) : ' ';
        // As in YAML, the first non-empty line sets the indentation; a later line indented less is malformed.
        int width = -1;
        for (final String line : lines) {
            if (!line.isBlank()) {
                if (line.substring(0, indent(line)).indexOf('\t') >= 0) {
                    throw new Malformed("tab indentation is not supported");
                }
                if (width < 0) {
                    width = indent(line);
                } else if (indent(line) < width) {
                    throw new Malformed("block scalar lines must keep the first line's indentation");
                }
            }
        }
        if (width < 0) {
            return "";
        }
        final var content = new ArrayList<String>();
        for (final String line : lines) {
            content.add(line.isBlank() ? "" : line.substring(width));
        }
        int last = content.size();
        while (last > 0 && content.get(last - 1).isEmpty()) {
            last--;
        }
        final var text = new StringBuilder();
        for (int index = 0; index < last; index++) {
            final String line = content.get(index);
            if (index == 0) {
                text.append(line);
                continue;
            }
            final String previous = content.get(index - 1);
            if (!folded) {
                text.append('\n').append(line);
            } else if (line.isEmpty()) {
                // Folding turns each empty line into one line break.
                text.append('\n');
            } else if (previous.isEmpty()) {
                text.append(line);
            } else if (line.startsWith(" ") || previous.startsWith(" ")) {
                // More-indented lines keep their line breaks.
                text.append('\n').append(line);
            } else {
                text.append(' ').append(line);
            }
        }
        return switch (chomping) {
        case '-' -> text.toString();
        case '+' -> text + "\n".repeat(content.size() - last + 1);
        default -> text + "\n";
        };
    }
}
