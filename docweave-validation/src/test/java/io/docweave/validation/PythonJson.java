package io.docweave.validation;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Python-compatible JSON values for migrated benchmark scorers.
 *
 * <p>Retired Python scorers wrote reports with {@code json.dumps(value, indent=2)} and sometimes
 * {@code sort_keys=True}. Historical evidence must stay byte-comparable, so this class reproduces the
 * observable behavior those reports depend on: insertion-ordered objects, the distinction between Python
 * integers and floats, shortest round-trip {@code repr} floats, {@code ensure_ascii} escaping and the
 * standard two-space layout. Parsed objects are {@link LinkedHashMap}s, arrays are {@link ArrayList}s,
 * integers are {@link Long} or {@link BigInteger}, floats are {@link Double}, and {@code null},
 * {@link Boolean} and {@link String} map directly. Python's lenient non-standard literals
 * ({@code NaN}, {@code Infinity}) are accepted on input and emitted on output exactly as Python does.
 */
final class PythonJson {
    /** Prevents instantiation of this static utility class. */
    private PythonJson() {
    }

    /**
     * Parses one complete JSON document with Python {@code json.loads} value semantics.
     *
     * @param text complete document text
     * @return parsed Python-compatible value
     * @throws IllegalArgumentException if the document is malformed or has trailing content
     */
    static Object parse(final String text) {
        final var parser = new Parser(text);
        parser.whitespace();
        final Object value = parser.value(0);
        parser.whitespace();
        if (parser.position != text.length()) {
            throw new IllegalArgumentException("Extra data at offset " + parser.position);
        }
        return value;
    }

    /**
     * Formats a value like {@code json.dumps(value, indent=2, sort_keys=sortKeys)}.
     *
     * @param value Python-compatible value
     * @param sortKeys whether object keys are emitted in code-point order at every level
     * @return formatted text without a trailing newline
     */
    static String dumps(final Object value, final boolean sortKeys) {
        final var output = new StringBuilder();
        write(output, value, 0, sortKeys);
        return output.toString();
    }

    /**
     * Formats a value like {@code json.dumps(value, separators=(',', ':'))}.
     *
     * @param value Python-compatible value
     * @return compact text
     */
    static String compact(final Object value) {
        final var output = new StringBuilder();
        writeCompact(output, value);
        return output.toString();
    }

    /**
     * Returns Python {@code repr}/{@code str} text for an integer or float.
     *
     * @param number integer ({@link Long}, {@link Integer}, {@link BigInteger}) or {@link Double}
     * @return Python numeric text
     */
    static String repr(final Number number) {
        if (number instanceof Double || number instanceof Float) {
            return floatRepr(number.doubleValue());
        }
        if (number instanceof Long || number instanceof Integer || number instanceof BigInteger) {
            return number.toString();
        }
        throw new IllegalArgumentException("Unsupported numeric type: " + number.getClass().getName());
    }

    /**
     * Applies Python {@code round(value, digits)}: integers stay integers, floats round half-even exactly.
     *
     * @param number Python integer or float
     * @param digits nonnegative decimal places
     * @return rounded value of the same Python type
     */
    static Number round(final Number number, final int digits) {
        if (!(number instanceof Double value)) {
            return number;
        }
        if (value.isNaN() || value.isInfinite()) {
            return value;
        }
        // CPython keeps the input sign when the rounded value is zero.
        return Math.copySign(new BigDecimal(value).setScale(digits, RoundingMode.HALF_EVEN).doubleValue(), value);
    }

    /**
     * Compares two values with Python {@code ==} semantics for JSON-derived data.
     *
     * <p>Objects compare as unordered mappings, numbers compare numerically across integer and float
     * types, and booleans never equal numbers (JSON booleans and integers stay distinct in reports).
     *
     * @param first first value
     * @param second second value
     * @return whether Python would report the values as equal
     */
    static boolean pyEquals(final Object first, final Object second) {
        if (first == null || second == null) {
            return first == second;
        }
        if (first instanceof Number left && second instanceof Number right) {
            return numericCompare(left, right) == 0;
        }
        if (first instanceof Map<?, ?> left && second instanceof Map<?, ?> right) {
            if (left.size() != right.size()) {
                return false;
            }
            for (final var entry : left.entrySet()) {
                if (!right.containsKey(entry.getKey()) || !pyEquals(entry.getValue(), right.get(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (first instanceof List<?> left && second instanceof List<?> right) {
            if (left.size() != right.size()) {
                return false;
            }
            for (int index = 0; index < left.size(); index++) {
                if (!pyEquals(left.get(index), right.get(index))) {
                    return false;
                }
            }
            return true;
        }
        return first.equals(second);
    }

    /**
     * Orders two Python numbers numerically; NaN compares equal to nothing and is ordered last.
     *
     * @param first first number
     * @param second second number
     * @return negative, zero or positive comparison result
     */
    static int numericCompare(final Number first, final Number second) {
        if (first instanceof Double || second instanceof Double) {
            final double left = first.doubleValue();
            final double right = second.doubleValue();
            if (Double.isNaN(left) || Double.isNaN(right)) {
                return Double.isNaN(left) ? 1 : -1;
            }
            if (left < right) {
                return -1;
            }
            return left > right ? 1 : 0;
        }
        return integer(first).compareTo(integer(second));
    }

    /**
     * Returns a Python integer as {@link BigInteger}.
     *
     * @param number integral value
     * @return exact integer
     */
    private static BigInteger integer(final Number number) {
        return number instanceof BigInteger value ? value : BigInteger.valueOf(number.longValue());
    }

    /**
     * Requires a JSON integer and returns it as an {@code int}.
     *
     * @param value parsed value
     * @param name field name for diagnostics
     * @return exact integer value
     */
    static int integerValue(final Object value, final String name) {
        if (value instanceof Long number && number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE) {
            return number.intValue();
        }
        throw new IllegalArgumentException("Expected integer field: " + name);
    }

    /**
     * Requires a JSON object.
     *
     * @param value parsed value
     * @param name field name for diagnostics
     * @return the object view
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> object(final Object value, final String name) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException("Expected object: " + name);
    }

    /**
     * Requires a JSON array.
     *
     * @param value parsed value
     * @param name field name for diagnostics
     * @return the list view
     */
    @SuppressWarnings("unchecked")
    static List<Object> array(final Object value, final String name) {
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        throw new IllegalArgumentException("Expected array: " + name);
    }

    /**
     * Reads a required object member, failing like a Python {@code KeyError}.
     *
     * @param object parsed object
     * @param key member name
     * @return member value, possibly {@code null} when present as JSON null
     */
    static Object member(final Map<String, Object> object, final String key) {
        if (!object.containsKey(key)) {
            throw new IllegalArgumentException("Missing key: " + key);
        }
        return object.get(key);
    }

    /**
     * Returns Python truthiness for JSON-derived values.
     *
     * @param value parsed value
     * @return whether Python {@code bool(value)} is true
     */
    static boolean truthy(final Object value) {
        return switch (value) {
            case null -> false;
            case Boolean flag -> flag;
            case Double number -> number != 0.0;
            case Number number -> integer(number).signum() != 0;
            case String text -> !text.isEmpty();
            case Collection<?> items -> !items.isEmpty();
            case Map<?, ?> items -> !items.isEmpty();
            default -> true;
        };
    }

    /**
     * Formats a float with CPython's {@code repr} rules: shortest round-trip digits, fixed notation for
     * decimal exponents in {@code (-4, 16]} and exponent notation with at least two digits otherwise.
     *
     * @param value float value
     * @return Python representation
     */
    private static String floatRepr(final double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "Infinity" : "-Infinity";
        }
        final String sign = (Double.doubleToRawLongBits(value) < 0) ? "-" : "";
        if (value == 0.0) {
            return sign + "0.0";
        }
        // JDK 19+ Double.toString yields the closest shortest round-trip digits, as CPython's repr does,
        // except that Java never selects fewer than two digits (Double.MIN_VALUE prints as 4.9E-324).
        final double magnitude = Math.abs(value);
        var decimal = new BigDecimal(Double.toString(magnitude)).stripTrailingZeros();
        if (decimal.precision() == 2) {
            decimal = oneDigit(magnitude, decimal);
        }
        final String digits = decimal.unscaledValue().toString();
        final int point = digits.length() - decimal.scale();
        if (point > -4 && point <= 16) {
            if (point <= 0) {
                return sign + "0."
                        + "0".repeat(-point) + digits;
            }
            if (point >= digits.length()) {
                return sign + digits + "0".repeat(point - digits.length()) + ".0";
            }
            return sign + digits.substring(0, point) + "." + digits.substring(point);
        }
        final int exponent = point - 1;
        final String mantissa = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
        final String power = Integer.toString(Math.abs(exponent));
        return sign + mantissa + "e" + (exponent < 0 ? "-" : "+") + (power.length() < 2 ? "0" : "") + power;
    }

    /**
     * Prefers a one-digit decimal when it also round-trips, choosing the candidate closest to the exact value.
     *
     * @param value positive finite float
     * @param twoDigits Java's two-digit shortest decimal
     * @return CPython's shortest decimal
     */
    private static BigDecimal oneDigit(final double value, final BigDecimal twoDigits) {
        final var exact = new BigDecimal(value);
        BigDecimal best = null;
        for (final var mode : new RoundingMode[] {RoundingMode.FLOOR, RoundingMode.CEILING}) {
            final var candidate = twoDigits.round(new java.math.MathContext(1, mode));
            if (candidate.signum() > 0 && Double.parseDouble(candidate.toString()) == value
                    && (best == null || candidate.subtract(exact).abs().compareTo(best.subtract(exact).abs()) < 0)) {
                best = candidate;
            }
        }
        return best == null ? twoDigits : best.stripTrailingZeros();
    }

    /**
     * Writes one indented value.
     *
     * @param output destination
     * @param value value
     * @param level current nesting level
     * @param sortKeys whether keys are sorted
     */
    private static void write(final StringBuilder output, final Object value, final int level, final boolean sortKeys) {
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                output.append("{}");
                return;
            }
            output.append("{\n");
            boolean first = true;
            for (final var entry : entries(map, sortKeys)) {
                if (!first) {
                    output.append(",\n");
                }
                first = false;
                output.append("  ".repeat(level + 1));
                string(output, entry.getKey());
                output.append(": ");
                write(output, entry.getValue(), level + 1, sortKeys);
            }
            output.append('\n').append("  ".repeat(level)).append('}');
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                output.append("[]");
                return;
            }
            output.append("[\n");
            for (int index = 0; index < list.size(); index++) {
                if (index > 0) {
                    output.append(",\n");
                }
                output.append("  ".repeat(level + 1));
                write(output, list.get(index), level + 1, sortKeys);
            }
            output.append('\n').append("  ".repeat(level)).append(']');
        } else {
            scalar(output, value);
        }
    }

    /**
     * Writes one compact value.
     *
     * @param output destination
     * @param value value
     */
    private static void writeCompact(final StringBuilder output, final Object value) {
        if (value instanceof Map<?, ?> map) {
            output.append('{');
            boolean first = true;
            for (final var entry : entries(map, false)) {
                if (!first) {
                    output.append(',');
                }
                first = false;
                string(output, entry.getKey());
                output.append(':');
                writeCompact(output, entry.getValue());
            }
            output.append('}');
        } else if (value instanceof List<?> list) {
            output.append('[');
            for (int index = 0; index < list.size(); index++) {
                if (index > 0) {
                    output.append(',');
                }
                writeCompact(output, list.get(index));
            }
            output.append(']');
        } else {
            scalar(output, value);
        }
    }

    /**
     * Returns string-keyed entries in insertion or code-point order.
     *
     * @param map source mapping
     * @param sortKeys whether to sort
     * @return ordered entries
     */
    private static List<Map.Entry<String, Object>> entries(final Map<?, ?> map, final boolean sortKeys) {
        final var result = new ArrayList<Map.Entry<String, Object>>();
        for (final var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("JSON object keys must be strings");
            }
            result.add(Map.entry(key, entry.getValue() == null ? NULL : entry.getValue()));
        }
        if (sortKeys) {
            result.sort(Comparator.comparing(Map.Entry::getKey, PythonJson::codePointCompare));
        }
        return result;
    }

    /** Marker replacing {@code null} values, which {@link Map#entry} does not accept. */
    private static final Object NULL = new Object();

    /**
     * Compares strings by Unicode code point, matching Python string ordering.
     *
     * @param first first string
     * @param second second string
     * @return comparison result
     */
    static int codePointCompare(final String first, final String second) {
        int left = 0;
        int right = 0;
        while (left < first.length() && right < second.length()) {
            final int a = first.codePointAt(left);
            final int b = second.codePointAt(right);
            if (a != b) {
                return Integer.compare(a, b);
            }
            left += Character.charCount(a);
            right += Character.charCount(b);
        }
        return Integer.compare(first.length() - left, second.length() - right);
    }

    /**
     * Writes a scalar value.
     *
     * @param output destination
     * @param value scalar
     */
    private static void scalar(final StringBuilder output, final Object value) {
        if (value == NULL) {
            output.append("null");
            return;
        }
        switch (value) {
        case null -> output.append("null");
        case Boolean flag -> output.append(flag ? "true" : "false");
        case Number number -> output.append(repr(number));
        case String text -> string(output, text);
        default -> throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass().getName());
        }
    }

    /**
     * Writes an {@code ensure_ascii} string literal.
     *
     * @param output destination
     * @param text string value
     */
    private static void string(final StringBuilder output, final String text) {
        output.append('"');
        for (int index = 0; index < text.length(); index++) {
            final char c = text.charAt(index);
            switch (c) {
            case '"' -> output.append("\\\"");
            case '\\' -> output.append("\\\\");
            case '\n' -> output.append("\\n");
            case '\r' -> output.append("\\r");
            case '\t' -> output.append("\\t");
            case '\b' -> output.append("\\b");
            case '\f' -> output.append("\\f");
            default -> {
                if (c < ' ' || c > '~') {
                    output.append(String.format("\\u%04x", (int) c));
                } else {
                    output.append(c);
                }
            }
            }
        }
        output.append('"');
    }

    /** Recursive-descent reader with Python {@code json} scanner semantics. */
    private static final class Parser {
        /** Maximum nesting accepted, below Python's default recursion limit. */
        private static final int MAX_DEPTH = 900;
        /** Complete input. */
        private final String text;
        /** Current offset. */
        private int position;

        /**
         * Creates a reader positioned at the start.
         *
         * @param text complete input
         */
        Parser(final String text) {
            this.text = Objects.requireNonNull(text, "text");
        }

        /** Skips JSON whitespace. */
        void whitespace() {
            while (position < text.length() && " \t\n\r".indexOf(text.charAt(position)) >= 0) {
                position++;
            }
        }

        /**
         * Reads one value.
         *
         * @param depth current nesting depth
         * @return parsed value
         */
        Object value(final int depth) {
            if (depth > MAX_DEPTH) {
                throw new IllegalArgumentException("JSON nesting too deep");
            }
            if (position >= text.length()) {
                throw new IllegalArgumentException("Expecting value at offset " + position);
            }
            final char c = text.charAt(position);
            if (c == '{') {
                return object(depth);
            }
            if (c == '[') {
                return array(depth);
            }
            if (c == '"') {
                return string();
            }
            for (final var literal : List.of("null", "true", "false", "NaN", "Infinity", "-Infinity")) {
                if (text.startsWith(literal, position)) {
                    position += literal.length();
                    return switch (literal) {
                        case "null" -> null;
                        case "true" -> Boolean.TRUE;
                        case "false" -> Boolean.FALSE;
                        case "NaN" -> Double.NaN;
                        case "Infinity" -> Double.POSITIVE_INFINITY;
                        default -> Double.NEGATIVE_INFINITY;
                    };
                }
            }
            return number();
        }

        /**
         * Reads an object; duplicate keys keep their first position and last value, as in Python.
         *
         * @param depth current nesting depth
         * @return ordered mapping
         */
        private Map<String, Object> object(final int depth) {
            position++;
            final var result = new LinkedHashMap<String, Object>();
            whitespace();
            if (peek() == '}') {
                position++;
                return result;
            }
            while (true) {
                whitespace();
                if (peek() != '"') {
                    throw new IllegalArgumentException("Expecting property name at offset " + position);
                }
                final String key = string();
                whitespace();
                expect(':');
                whitespace();
                result.put(key, value(depth + 1));
                whitespace();
                final char next = peek();
                position++;
                if (next == '}') {
                    return result;
                }
                if (next != ',') {
                    throw new IllegalArgumentException("Expecting ',' delimiter at offset " + (position - 1));
                }
            }
        }

        /**
         * Reads an array.
         *
         * @param depth current nesting depth
         * @return ordered values
         */
        private List<Object> array(final int depth) {
            position++;
            final var result = new ArrayList<Object>();
            whitespace();
            if (peek() == ']') {
                position++;
                return result;
            }
            while (true) {
                whitespace();
                result.add(value(depth + 1));
                whitespace();
                final char next = peek();
                position++;
                if (next == ']') {
                    return result;
                }
                if (next != ',') {
                    throw new IllegalArgumentException("Expecting ',' delimiter at offset " + (position - 1));
                }
            }
        }

        /**
         * Reads a string literal; raw control characters are rejected as in strict Python decoding.
         *
         * @return decoded string, possibly containing unpaired surrogates as Python permits
         */
        private String string() {
            position++;
            final var result = new StringBuilder();
            while (true) {
                if (position >= text.length()) {
                    throw new IllegalArgumentException("Unterminated string");
                }
                final char c = text.charAt(position++);
                if (c == '"') {
                    return result.toString();
                }
                if (c < ' ') {
                    throw new IllegalArgumentException("Invalid control character at offset " + (position - 1));
                }
                if (c != '\\') {
                    result.append(c);
                    continue;
                }
                if (position >= text.length()) {
                    throw new IllegalArgumentException("Unterminated string");
                }
                final char escape = text.charAt(position++);
                switch (escape) {
                case '"' -> result.append('"');
                case '\\' -> result.append('\\');
                case '/' -> result.append('/');
                case 'b' -> result.append('\b');
                case 'f' -> result.append('\f');
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                case 'u' -> {
                    if (position + 4 > text.length()) {
                        throw new IllegalArgumentException("Invalid \\u escape");
                    }
                    final String hex = text.substring(position, position + 4);
                    if (!hex.matches("[0-9a-fA-F]{4}")) {
                        throw new IllegalArgumentException("Invalid \\u escape");
                    }
                    result.append((char) Integer.parseInt(hex, 16));
                    position += 4;
                }
                default -> throw new IllegalArgumentException("Invalid escape at offset " + (position - 1));
                }
            }
        }

        /**
         * Reads a number, keeping integers distinct from floats.
         *
         * @return {@link Long}, {@link BigInteger} or {@link Double}
         */
        private Number number() {
            final int start = position;
            if (peek() == '-') {
                position++;
            }
            if (peek() == '0') {
                position++;
            } else if (peek() >= '1' && peek() <= '9') {
                while (isAsciiDigit(peek())) {
                    position++;
                }
            } else {
                throw new IllegalArgumentException("Expecting value at offset " + start);
            }
            boolean real = false;
            if (peek() == '.' && position + 1 < text.length() && isAsciiDigit(text.charAt(position + 1))) {
                real = true;
                position++;
                while (isAsciiDigit(peek())) {
                    position++;
                }
            }
            if ((peek() == 'e' || peek() == 'E')) {
                int cursor = position + 1;
                if (cursor < text.length() && (text.charAt(cursor) == '+' || text.charAt(cursor) == '-')) {
                    cursor++;
                }
                if (cursor < text.length() && isAsciiDigit(text.charAt(cursor))) {
                    real = true;
                    position = cursor;
                    while (isAsciiDigit(peek())) {
                        position++;
                    }
                }
            }
            final String literal = text.substring(start, position);
            if (real) {
                return Double.parseDouble(literal);
            }
            final var value = new BigInteger(literal);
            return value.bitLength() < 64 ? (Number) value.longValue() : value;
        }

        /**
         * Tests for an ASCII digit.
         *
         * @param c character
         * @return whether it is {@code 0}-{@code 9}
         */
        private static boolean isAsciiDigit(final char c) {
            return c >= '0' && c <= '9';
        }

        /**
         * Returns the current character or NUL at the end.
         *
         * @return current character
         */
        private char peek() {
            return position < text.length() ? text.charAt(position) : '\0';
        }

        /**
         * Requires one punctuation character.
         *
         * @param expected expected character
         */
        private void expect(final char expected) {
            if (peek() != expected) {
                throw new IllegalArgumentException("Expecting '" + expected + "' at offset " + position);
            }
            position++;
        }
    }
}
