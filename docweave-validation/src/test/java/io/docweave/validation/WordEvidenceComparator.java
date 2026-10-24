package io.docweave.validation;

import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Development-only comparator for controlled 3x3 table evidence. Effective outlines are read from
 * table-style ranges, defaulting to body level 9; direct heading ranges are outside this instrument.
 * Comparison never grants human approval, including when no property differences remain.
 */
interface WordEvidenceComparator {
    String STATUS = "comparison_only_human_review_pending";
    String LIMIT = "Versioned observations are not a human approval or universal Word/upstream parity claim";

    /** Creates a stateless comparator with per-call indexing and validation. */
    static WordEvidenceComparator create() {
        return new DefaultWordEvidenceComparator();
    }

    /**
     * Checks identities, hashes, application metadata, complete grids and exact stored properties.
     * @param manifest source packet manifest
     * @param candidate public-library output, not independent expected data
     * @param observations separately collected Word observations, never inferred from candidate output
     * @param manifestHash SHA-256 of original manifest bytes, including encoding/newlines
     * @return immutable pending-review comparison; differences are retained rather than approved
     * @throws IOException for missing, duplicate, malformed or mismatched provenance/property data
     */
    Comparison compare(JsonNode manifest, JsonNode candidate, JsonNode observations, String manifestHash)
            throws IOException;

    /** Extracts the nine unique anchor properties from one candidate Table; offsets count code points. */
    List<Cell> candidateCells(JsonNode candidateCase) throws IOException;

    /** Coordinate identity is distinct from presentation order in an observation file. */
    record Coordinate(int row, int column) {}

    /** Immutable controlled-cell observation; values are validated before construction by the comparator. */
    record Cell(Coordinate coordinate, String text, int outlineLevel, String listString) {
        Map<String, Object> json() {
            return Map.of("row", coordinate.row(), "column", coordinate.column(), "text", text, "outline_level",
                    outlineLevel, "list_string", listString);
        }
    }

    /** Candidate/Word values are immutable Integer or String values for the two supported properties. */
    record Difference(Coordinate coordinate, String property, Object candidate, Object word) {
        Map<String, Object> json() {
            return Map.of("row", coordinate.row(), "column", coordinate.column(), "property", property, "candidate",
                    candidate, "word", word);
        }
    }

    /** Ordered property differences for one original source case. */
    record CaseComparison(String filename, List<Difference> differences) {
        public CaseComparison {
            differences = List.copyOf(differences);
        }

        Map<String, Object> json() {
            return Map.of("file", filename, "differences", differences.stream().map(Difference::json).toList());
        }
    }

    /** No passed/approved flag exists: even an empty difference set remains pending human review. */
    record Comparison(String wordVersion, String wordBuild, String platform, List<CaseComparison> cases) {
        public Comparison {
            cases = List.copyOf(cases);
        }

        int differenceCount() {
            return cases.stream().mapToInt(c -> c.differences().size()).sum();
        }

        Map<String, Object> json() {
            return Map.of("status", STATUS, "word_version", wordVersion, "word_build", wordBuild, "platform", platform,
                    "cases", cases.stream().map(CaseComparison::json).toList(), "limit", LIMIT);
        }
    }
}
