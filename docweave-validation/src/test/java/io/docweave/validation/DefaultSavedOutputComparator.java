package io.docweave.validation;

import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** Preserves schema validation, Python JSON equality and code-point comparison without a Python runtime. */
final class DefaultSavedOutputComparator implements SavedOutputComparator {
    @Override
    public Map<String, Object> compare(final JsonNode reference, final JsonNode candidate) throws IOException {
        validate(reference);
        validate(candidate);
        final String left = text(reference);
        final String right = text(candidate);
        int textMismatches = 0;
        int metadataMismatches = 0;
        int idMismatches = 0;
        boolean categoriesEqual = reference.size() == candidate.size();
        for (int index = 0; index < Math.min(reference.size(), candidate.size()); index++) {
            final var before = reference.get(index);
            final var after = candidate.get(index);
            textMismatches += before.get("text").equals(after.get("text")) ? 0 : 1;
            metadataMismatches += equal(before.get("metadata"), after.get("metadata")) ? 0 : 1;
            idMismatches += before.get("element_id").equals(after.get("element_id")) ? 0 : 1;
            categoriesEqual &= before.get("type").equals(after.get("type"));
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put("exact_match", equal(reference, candidate));
        report.put("reference_elements", reference.size());
        report.put("candidate_elements", candidate.size());
        report.put("reference_code_points", left.codePointCount(0, left.length()));
        report.put("candidate_code_points", right.codePointCount(0, right.length()));
        report.put("text_similarity", CodePointSimilarity.ratio(left, right));
        report.put("category_sequence_equal", categoriesEqual);
        report.put("paired_text_mismatches", textMismatches);
        report.put("paired_metadata_mismatches", metadataMismatches);
        report.put("paired_id_mismatches", idMismatches);
        report.put("unpaired_elements", Math.abs(reference.size() - candidate.size()));
        return Map.copyOf(report);
    }

    /** Requires the historical compatibility envelope without imposing current library enums or metadata policy. */
    private static void validate(final JsonNode elements) throws IOException {
        if (elements == null || !elements.isArray()) {
            throw new IOException("Output must be an element array");
        }
        for (final var element : elements) {
            if (!element.isObject()) {
                throw new IOException("Element must be an object");
            }
            for (final String field : java.util.List.of("type", "element_id", "text")) {
                if (!element.has(field) || !element.get(field).isString()) {
                    throw new IOException("Element " + field + " must be a string");
                }
            }
            if (element.get("type").asString().isEmpty() || element.get("element_id").asString().isEmpty()
                    || !element.has("metadata") || !element.get("metadata").isObject()) {
                throw new IOException("Element requires nonempty type/ID and object metadata");
            }
        }
    }

    /** Blank-line joining preserves the original segmentation-independent text diagnostic. */
    private static String text(final JsonNode elements) {
        return elements.valueStream()
                .map(element -> element.get("text").asString())
                .collect(Collectors.joining("\n\n"));
    }

    /** Python JSON equality compares numbers/booleans by value, object keys without order, and arrays with order. */
    private static boolean equal(final JsonNode left, final JsonNode right) {
        if (numeric(left) && numeric(right)) {
            if ((left.isFloatingPointNumber() && !Double.isFinite(left.asDouble()))
                    || (right.isFloatingPointNumber() && !Double.isFinite(right.asDouble()))) {
                return left.isFloatingPointNumber() && right.isFloatingPointNumber()
                        && left.asDouble() == right.asDouble();
            }
            return number(left).compareTo(number(right)) == 0;
        }
        if (left.isArray() && right.isArray()) {
            if (left.size() != right.size()) {
                return false;
            }
            for (int index = 0; index < left.size(); index++) {
                if (!equal(left.get(index), right.get(index))) {
                    return false;
                }
            }
            return true;
        }
        if (left.isObject() && right.isObject()) {
            if (left.size() != right.size()) {
                return false;
            }
            for (final var property : left.properties()) {
                if (!right.has(property.getKey()) || !equal(property.getValue(), right.get(property.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        return left.equals(right);
    }

    /** JSON booleans participate in Python numeric equality, including nested vendor metadata. */
    private static boolean numeric(final JsonNode node) {
        return node.isNumber() || node.isBoolean();
    }

    /** Converts binary floating values exactly, avoiding decimal spelling or large-integer rounding shortcuts. */
    private static BigDecimal number(final JsonNode node) {
        if (node.isBoolean()) {
            return node.asBoolean() ? BigDecimal.ONE : BigDecimal.ZERO;
        }
        return node.isIntegralNumber() ? new BigDecimal(node.bigIntegerValue()) : new BigDecimal(node.asDouble());
    }
}
