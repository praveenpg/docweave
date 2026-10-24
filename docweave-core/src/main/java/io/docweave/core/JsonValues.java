package io.docweave.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deep immutable snapshots of JSON-compatible extension data.
 * Supported values are strings, booleans, primitive-wrapper numbers, big integers/decimals,
 * lists and string-keyed maps. Floating-point values must be finite. Arbitrary objects, arrays,
 * cycles and nesting beyond 100 levels are rejected. No mutable input collection is retained.
 */
final class JsonValues {
    /** Prevents instantiation of this static utility class. */
    private JsonValues() {
    }

    /**
     * Copies metadata recursively while preserving insertion order.
     * Top-level null fields are omitted; nested nulls remain meaningful extension data.
     * Empty collections are retained here; the compatibility codec applies wire omission rules.
     * @param source metadata with string keys and JSON-compatible values
     * @return a recursively immutable snapshot
     * @throws NullPointerException if source is null
     * @throws IllegalArgumentException if keys/values or nesting are unsupported
     */
    public static Map<String, Object> snapshot(final Map<?, ?> source) {
        final var copy = new LinkedHashMap<String, Object>();
        source.forEach((key, value) -> {
            if (!(key instanceof final String name)) {
                throw new IllegalArgumentException("JSON keys must be strings");
            }
            if (value != null) {
                copy.put(name, freeze(value, 0));
            }
        });
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Recursively validates and freezes a JSON-compatible metadata value at the given nesting depth.
     *
     * @param value candidate metadata value (may be {@code null} when nested)
     * @param depth current 0-based nesting depth
     * @return immutable copy of {@code value}
     */
    private static Object freeze(final Object value, final int depth) {
        if (depth > 100) {
            throw new IllegalArgumentException("Metadata nesting exceeds 100 levels");
        }
        return switch (value) {
            case null -> null;
            case final String s -> s;
            case final Boolean b -> b;
            case final Byte n -> n;
            case final Short n -> n;
            case final Integer n -> n;
            case final Long n -> n;
            case final BigInteger n -> n;
            case final BigDecimal n -> n;
            case final Double n when Double.isFinite(n) -> n;
            case final Float n when Float.isFinite(n) -> n;
            case final Map<?, ?> map -> {
                final var copy = new LinkedHashMap<String, Object>();
                map.forEach((key, nested) -> {
                    if (!(key instanceof final String name)) {
                        throw new IllegalArgumentException("JSON keys must be strings");
                    }
                    // Nested nulls can carry meaning in extension data.
                    copy.put(name, freeze(nested, depth + 1));
                });
                yield Collections.unmodifiableMap(copy);
            }
            case final List<?> list -> {
                final var copy = new ArrayList<>();
                for (final var nested : list) {
                    copy.add(freeze(nested, depth + 1));
                }
                yield Collections.unmodifiableList(copy);
            }
            default -> throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass().getName());
        };
    }
}
