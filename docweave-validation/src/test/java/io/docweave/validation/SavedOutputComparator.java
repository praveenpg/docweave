package io.docweave.validation;

import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.Map;

/** Development-only comparison of saved compatibility arrays; never invokes document parsers. */
interface SavedOutputComparator {
    /** Validates both arrays and reports historical exact/category/paired/text-similarity diagnostics. */
    Map<String, Object> compare(JsonNode reference, JsonNode candidate) throws IOException;

    /** Creates the hidden implementation, preserving the retired Python comparator's diagnostic semantics. */
    static SavedOutputComparator create() {
        return new DefaultSavedOutputComparator();
    }
}
