package io.docweave.validation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * File and text primitives shared by the JVM benchmark scorers that replaced Python tools.
 *
 * <p>Each method mirrors the Python library call the retired scorer used, so that identities, rows and
 * hashes stay identical: {@code Path.read_text()} universal newlines, {@code csv.reader} and
 * {@code csv.DictReader} parsing, {@code str.strip()}, {@code pathlib} path rendering and SHA digests.
 * Inputs that Python would accept only through undocumented leniency (unterminated quoted fields or
 * rows longer than their header) are rejected instead of being silently reinterpreted.
 */
final class ScoringFiles {
    /** Prevents instantiation of this static utility class. */
    private ScoringFiles() {
    }

    /**
     * Reads UTF-8 text with Python universal-newline translation ({@code \r\n} and {@code \r} become {@code \n}).
     *
     * @param path file to read
     * @return translated text
     * @throws IOException if the file cannot be read
     */
    static String readText(final Path path) throws IOException {
        return universalNewlines(Files.readString(path, StandardCharsets.UTF_8));
    }

    /**
     * Applies Python universal-newline translation.
     *
     * @param text raw text
     * @return translated text
     */
    static String universalNewlines(final String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /**
     * Applies Python {@code str.strip()} with no arguments.
     *
     * @param text value
     * @return value without leading/trailing Python whitespace
     */
    static String strip(final String text) {
        int start = 0;
        int end = text.length();
        while (start < end && whitespace(text.charAt(start))) {
            start++;
        }
        while (end > start && whitespace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    /** Python {@code float()} decimal literal after stripping, with optional digit-group underscores. */
    private static final java.util.regex.Pattern FLOAT = java.util.regex.Pattern.compile(
            "[+-]?(?:(?:[0-9](?:_?[0-9])*)?\\.[0-9](?:_?[0-9])*|[0-9](?:_?[0-9])*\\.?)(?:[eE][+-]?[0-9](?:_?[0-9])*)?");
    /** Python {@code int()} decimal literal after stripping. */
    private static final java.util.regex.Pattern INTEGER = java.util.regex.Pattern.compile("[+-]?[0-9](?:_?[0-9])*");

    /**
     * Parses text exactly as Python {@code float(str)} accepts ASCII input.
     *
     * <p>Unlike {@link Double#parseDouble}, Java-only forms such as type suffixes and hexadecimal floats
     * are rejected; Python's underscores, {@code inf}, {@code infinity} and {@code nan} are accepted.
     *
     * @param text field text
     * @return correctly rounded value
     */
    static double pyFloat(final String text) {
        final String value = strip(text);
        final String lower = value.toLowerCase(java.util.Locale.ROOT);
        final String unsigned = lower.startsWith("+") || lower.startsWith("-") ? lower.substring(1) : lower;
        if (unsigned.equals("inf") || unsigned.equals("infinity")) {
            return lower.startsWith("-") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        }
        if (unsigned.equals("nan")) {
            return Double.NaN;
        }
        if (!FLOAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid float: " + value);
        }
        return Double.parseDouble(value.replace("_", ""));
    }

    /**
     * Parses text exactly as Python {@code int(str)} accepts ASCII decimal input.
     *
     * @param text field text
     * @return parsed value
     */
    static int pyInt(final String text) {
        final String value = strip(text);
        if (!INTEGER.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid integer: " + value);
        }
        return Integer.parseInt(value.replace("_", ""));
    }

    /**
     * Splits on runs of Python whitespace, as {@code str.split()} with no arguments.
     *
     * @param text value
     * @return nonempty tokens
     */
    static List<String> splitWhitespace(final String text) {
        final var result = new ArrayList<String>();
        int index = 0;
        while (index < text.length()) {
            while (index < text.length() && whitespace(text.charAt(index))) {
                index++;
            }
            final int start = index;
            while (index < text.length() && !whitespace(text.charAt(index))) {
                index++;
            }
            if (index > start) {
                result.add(text.substring(start, index));
            }
        }
        return result;
    }

    /**
     * Tests Python {@code str.isspace()} for one BMP character.
     *
     * @param c character
     * @return whether Python treats it as whitespace
     */
    private static boolean whitespace(final char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\u0085';
    }

    /**
     * Returns the hexadecimal SHA-256 digest of a file.
     *
     * @param path file
     * @return lowercase digest
     * @throws IOException if the file cannot be read
     */
    static String sha256(final Path path) throws IOException {
        return sha256(Files.readAllBytes(path));
    }

    /**
     * Returns the hexadecimal SHA-256 digest of bytes.
     *
     * @param bytes content
     * @return lowercase digest
     */
    static String sha256(final byte[] bytes) {
        return digest("SHA-256", bytes);
    }

    /**
     * Returns the Git blob SHA-1 of bytes, as {@code git hash-object} computes it.
     *
     * @param bytes content
     * @return lowercase digest
     */
    static String gitBlobSha1(final byte[] bytes) {
        final byte[] header = ("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII);
        final byte[] combined = new byte[header.length + bytes.length];
        System.arraycopy(header, 0, combined, 0, header.length);
        System.arraycopy(bytes, 0, combined, header.length, bytes.length);
        return digest("SHA-1", combined);
    }

    /**
     * Computes one standard digest.
     *
     * @param algorithm JDK algorithm name
     * @param bytes content
     * @return lowercase hexadecimal digest
     */
    private static String digest(final String algorithm, final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
        } catch (final NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /**
     * Refuses an existing output path, matching the retired tools' fresh-output requirement.
     *
     * @param output requested output
     * @throws FileAlreadyExistsException if the path exists
     */
    static void requireNew(final Path output) throws FileAlreadyExistsException {
        if (Files.exists(output)) {
            throw new FileAlreadyExistsException(output.toString());
        }
    }

    /**
     * Lists every regular file below a root as relative {@code /}-separated paths ({@code rglob('*')}).
     *
     * @param root directory
     * @return sorted relative paths
     * @throws IOException if traversal fails
     */
    static Set<String> relativeFiles(final Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            final var result = new TreeSet<String>();
            paths.filter(Files::isRegularFile)
                    .forEach(path -> result.add(root.relativize(path).toString().replace('\\', '/')));
            return result;
        }
    }

    /**
     * Renders a command-line path as {@code str(pathlib.Path(argument))} does on POSIX.
     *
     * @param argument path argument as given
     * @return path text with empty and {@code .} components removed
     */
    static String pythonPath(final String argument) {
        final boolean absolute = argument.startsWith("/");
        final var parts = new ArrayList<String>();
        for (final String part : argument.split("/")) {
            if (!part.isEmpty() && !part.equals(".")) {
                parts.add(part);
            }
        }
        final String joined = String.join("/", parts);
        if (absolute) {
            final String root = argument.startsWith("//") && !argument.startsWith("///") ? "//" : "/";
            return root + joined;
        }
        return joined.isEmpty() ? "." : joined;
    }

    /**
     * Reads a tab-separated file like {@code csv.DictReader(stream, delimiter='\t')} over a text-mode stream.
     *
     * <p>Blank records are skipped; a short record yields {@code null} for missing fields, as Python's
     * default {@code restval} does. A record longer than its header is rejected.
     *
     * @param path TSV file
     * @return header-keyed rows in file order
     * @throws IOException if the file cannot be read
     */
    static List<Map<String, String>> rows(final Path path) throws IOException {
        final var records = records(readText(path), '\t');
        final var result = new ArrayList<Map<String, String>>();
        if (records.isEmpty()) {
            return result;
        }
        // DictReader takes the first record as the header even when it is blank.
        final List<String> header = records.getFirst();
        for (int index = 1; index < records.size(); index++) {
            final List<String> record = records.get(index);
            if (record.isEmpty()) {
                continue;
            }
            if (record.size() > header.size()) {
                throw new IllegalArgumentException("Row has more fields than its header: " + path.getFileName());
            }
            final var row = new LinkedHashMap<String, String>();
            for (int column = 0; column < header.size(); column++) {
                row.put(header.get(column), column < record.size() ? record.get(column) : null);
            }
            result.add(row);
        }
        return result;
    }

    /**
     * Reads a UTF-8 comma-separated file opened with {@code newline=''} like {@code list(csv.reader(...))}.
     *
     * @param path CSV file
     * @return records including empty lists for blank lines
     * @throws IOException if the file cannot be read
     */
    static List<List<String>> csv(final Path path) throws IOException {
        return records(Files.readString(path, StandardCharsets.UTF_8), ',');
    }

    /** Parser states from CPython's {@code _csv.c} for the default excel dialect. */
    private enum State {
        /** Before the first character of a record. */
        START_RECORD,
        /** Before the first character of a field. */
        START_FIELD,
        /** Inside an unquoted field. */
        IN_FIELD,
        /** Inside a quoted field. */
        IN_QUOTED_FIELD,
        /** After a quote inside a quoted field. */
        QUOTE_IN_QUOTED_FIELD,
        /** Consuming the rest of a line terminator. */
        EAT_CRNL
    }

    /**
     * Parses delimited records with the default excel dialect ({@code "} quotes, doubled quotes, not strict).
     *
     * <p>CPython feeds each physical line (terminated by {@code \n}, {@code \r\n} or {@code \r}) followed by
     * an end-of-line marker; this parser follows the same state transitions.
     *
     * @param text complete input
     * @param delimiter field delimiter
     * @return parsed records
     */
    static List<List<String>> records(final String text, final char delimiter) {
        final var result = new ArrayList<List<String>>();
        var fields = new ArrayList<String>();
        final var field = new StringBuilder();
        var state = State.START_RECORD;
        int index = 0;
        while (index < text.length()) {
            int end = index;
            while (end < text.length() && text.charAt(end) != '\n' && text.charAt(end) != '\r') {
                end++;
            }
            if (end < text.length()) {
                end += text.charAt(end) == '\r' && end + 1 < text.length() && text.charAt(end + 1) == '\n' ? 2 : 1;
            }
            // Process the physical line, then the end-of-line marker (encoded as -1).
            for (int position = index; position <= end; position++) {
                final int c = position == end ? -1 : text.charAt(position);
                switch (state) {
                case START_RECORD -> {
                    if (c == -1) {
                        break;
                    }
                    if (c == '\n' || c == '\r') {
                        state = State.EAT_CRNL;
                        break;
                    }
                    state = startField(c, delimiter, fields, field);
                }
                case START_FIELD -> state = startField(c, delimiter, fields, field);
                case IN_FIELD -> {
                    if (c == '\n' || c == '\r' || c == -1) {
                        fields.add(field.toString());
                        field.setLength(0);
                        state = c == -1 ? State.START_RECORD : State.EAT_CRNL;
                    } else if (c == delimiter) {
                        fields.add(field.toString());
                        field.setLength(0);
                        state = State.START_FIELD;
                    } else {
                        field.append((char) c);
                    }
                }
                case IN_QUOTED_FIELD -> {
                    if (c == '"') {
                        state = State.QUOTE_IN_QUOTED_FIELD;
                    } else if (c != -1) {
                        field.append((char) c);
                    }
                }
                case QUOTE_IN_QUOTED_FIELD -> {
                    if (c == '"') {
                        field.append('"');
                        state = State.IN_QUOTED_FIELD;
                    } else if (c == delimiter) {
                        fields.add(field.toString());
                        field.setLength(0);
                        state = State.START_FIELD;
                    } else if (c == '\n' || c == '\r' || c == -1) {
                        fields.add(field.toString());
                        field.setLength(0);
                        state = c == -1 ? State.START_RECORD : State.EAT_CRNL;
                    } else {
                        field.append((char) c);
                        state = State.IN_FIELD;
                    }
                }
                case EAT_CRNL -> {
                    if (c == -1) {
                        state = State.START_RECORD;
                    } else if (c != '\n' && c != '\r') {
                        throw new IllegalArgumentException("New-line character seen in unquoted field");
                    }
                }
                }
            }
            if (state == State.START_RECORD) {
                result.add(fields);
                fields = new ArrayList<>();
            }
            index = end;
        }
        if (state != State.START_RECORD) {
            throw new IllegalArgumentException("Unexpected end of data inside a quoted field");
        }
        return result;
    }

    /**
     * Handles the start of a field.
     *
     * @param c current character or -1 for end of line
     * @param delimiter field delimiter
     * @param fields completed fields of the record
     * @param field current field buffer
     * @return next state
     */
    private static State startField(
            final int c, final char delimiter, final List<String> fields, final StringBuilder field) {
        if (c == '\n' || c == '\r' || c == -1) {
            fields.add(field.toString());
            field.setLength(0);
            return c == -1 ? State.START_RECORD : State.EAT_CRNL;
        }
        if (c == '"') {
            return State.IN_QUOTED_FIELD;
        }
        if (c == delimiter) {
            fields.add(field.toString());
            field.setLength(0);
            return State.START_FIELD;
        }
        field.append((char) c);
        return State.IN_FIELD;
    }
}
