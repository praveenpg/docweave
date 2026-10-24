package io.docweave.validation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Replays immutable saved Word packets through current public Java services without starting Word. */
interface WordEvidenceReplay {
    /** Creates the real-library replay implementation. */
    static WordEvidenceReplay create() {
        return new DefaultWordEvidenceReplay(WordCandidateCollector.create());
    }

    /**
     * Validates the catalog and all original bytes before creating a fresh, self-contained output directory.
     * @param reviewRoot directory containing word-evidence-manifest.json and the pinned packet directories
     * @param output new directory; any existing path is refused, including an empty directory
     * @return immutable packet-level summary, always pending human review
     * @throws IOException on malformed/tampered evidence, invalid candidates or write/extraction errors
     */
    Report run(Path reviewRoot, Path output) throws IOException;

    /** Raw evidence hashes and current candidate/comparison hashes for a single packet. */
    record PacketSummary(String packet, int cases, int propertyDifferences, String manifestSha256,
            String observationsSha256, String candidateSha256, String comparisonSha256) {
        Map<String, Object> json() {
            return Map.of("packet", packet, "cases", cases, "property_differences", propertyDifferences,
                    "manifest_sha256", manifestSha256, "observations_sha256", observationsSha256, "candidate_sha256",
                    candidateSha256, "comparison_sha256", comparisonSha256);
        }
    }

    /** No successful comparison changes review status or expands comparator scope. */
    record Report(List<PacketSummary> packets, String catalogSha256, Map<String, Object> provenance) {
        public Report {
            packets = List.copyOf(packets);
            provenance = Map.copyOf(provenance);
        }

        int cases() {
            return packets.stream().mapToInt(PacketSummary::cases).sum();
        }

        int differenceCount() {
            return packets.stream().mapToInt(PacketSummary::propertyDifferences).sum();
        }

        Map<String, Object> json() {
            final var summary = new java.util.LinkedHashMap<String, Object>(provenance);
            summary.put("status", WordEvidenceComparator.STATUS);
            summary.put("implementation", "docweave-validation Java/JUnit saved Word replay");
            summary.put("packets", packets.stream().map(PacketSummary::json).toList());
            summary.put("cases", cases());
            summary.put("property_differences", differenceCount());
            summary.put("evidence_catalog_sha256", catalogSha256);
            summary.put("excluded",
                    "Four direct-heading controls: comparator cannot expose direct heading ranges; Java policy regressions cover them");
            summary.put("limit", "Saved observations from one Word build; no new Word run or human approval");
            return summary;
        }
    }
}
