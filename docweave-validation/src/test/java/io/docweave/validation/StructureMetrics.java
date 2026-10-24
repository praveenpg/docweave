package io.docweave.validation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Report-shaped table association metrics shared by the migrated detector and scan scorers.
 *
 * <p>Every method returns insertion-ordered maps with the retired Python key order and value types, so
 * that {@link PythonJson#dumps} reproduces historical report bytes. Geometry alone associates tables;
 * topology is scored only after association and can never select a candidate.
 */
final class StructureMetrics {
    /** One-to-one association threshold used by every migrated envelope comparison. */
    static final double THRESHOLD = 0.5;

    /** Prevents instantiation of this static utility class. */
    private StructureMetrics() {
    }

    /**
     * Associates by geometry only, then scores dimensions/spans ({@code structure_metric}).
     *
     * @param references published tables
     * @param candidates predicted tables
     * @return candidate count, one-to-one matches, exact topologies and legacy highest-overlap records
     */
    static Map<String, Object> structure(
            final List<TableGeometry.Table> references, final List<TableGeometry.Table> candidates) {
        final var pairs = TableGeometry.maximumMatches(references.stream().map(TableGeometry.Table::box).toList(),
                candidates.stream().map(TableGeometry.Table::box).toList(), THRESHOLD);
        final var matched = new ArrayList<Object>();
        int exact = 0;
        for (final var pair : pairs) {
            final var expected = references.get(pair.reference());
            final var actual = candidates.get(pair.candidate());
            final boolean topology = expected.sameTopology(actual);
            exact += topology ? 1 : 0;
            final var record = new LinkedHashMap<String, Object>();
            record.put("reference", (long) pair.reference())
            ;
            record.put("candidate", (long) pair.candidate())
            ;
            record.put("envelope_iou", TableGeometry.iou(expected.box(), actual.box()))
            ;
            record.put("reference_rows", (long) expected.rows())
            ;
            record.put("reference_columns", (long) expected.columns())
            ;
            record.put("predicted_rows", (long) actual.rows())
            ;
            record.put("predicted_columns", (long) actual.columns())
            ;
            record.put("reference_anchors", (long) expected.anchors().size())
            ;
            record.put("predicted_anchors", (long) actual.anchors().size())
            ;
            record.put("matching_anchors", (long) expected.matchingAnchors(actual))
            ;
            record.put("exact_topology", topology)
            ;
            matched.add(record);
        }
        final var result = new LinkedHashMap<String, Object>();
        result.put("candidate_tables", (long) candidates.size());
        result.put("matched_reference_tables_at_iou_0_5", (long) pairs.size());
        result.put("exact_topologies_one_to_one_at_iou_0_5", (long) exact);
        result.put("matches", matched);
        result.put("legacy_highest_positive_overlap",
                new ArrayList<Object>(references.stream().map(table -> highestOverlap(table, candidates)).toList()));
        return result;
    }

    /**
     * Selects the maximum-overlap candidate for evaluation only ({@code compare_tiled_regions.compare}).
     *
     * <p>The first maximal candidate wins ties. A candidate without positive overlap is never compared,
     * even when its span keys are identical.
     *
     * @param reference published table
     * @param candidates all candidates of one pool
     * @return text-free comparison record
     */
    static Map<String, Object> highestOverlap(
            final TableGeometry.Table reference, final List<TableGeometry.Table> candidates) {
        TableGeometry.Table best = null;
        Number bestIou = null;
        for (final var candidate : candidates) {
            final Number overlap = TableGeometry.iou(reference.box(), candidate.box());
            if (best == null || PythonJson.numericCompare(overlap, bestIou) > 0) {
                best = candidate;
                bestIou = overlap;
            }
        }
        final var result = new LinkedHashMap<String, Object>();
        result.put("candidates", (long) candidates.size());
        if (best == null || bestIou.doubleValue() <= 0) {
            result.put("best_iou", 0L);
            result.put("exact_topology", false);
            result.put("matching_anchors", 0L);
            result.put("no_positive_overlap", true);
            return result;
        }
        result.put("best_iou", bestIou);
        result.put("profile", best.profile());
        result.put("predicted_rows", (long) best.rows());
        result.put("predicted_columns", (long) best.columns());
        result.put("predicted_anchors", (long) best.anchors().size());
        result.put("matching_anchors", (long) best.matchingAnchors(reference));
        result.put("exact_topology", best.sameTopology(reference));
        return result;
    }

    /**
     * Counts proposals and geometry-only matches without labelling unmatched boxes false positives
     * ({@code compare_scan_detectors.metric}).
     *
     * @param references published envelopes
     * @param candidates proposal envelopes
     * @return proposal, match and unmatched counts with matched IoUs
     */
    static Map<String, Object> proposals(
            final List<TableGeometry.Box> references, final List<TableGeometry.Box> candidates) {
        final var matches = TableGeometry.maximumMatches(references, candidates, THRESHOLD);
        final var result = new LinkedHashMap<String, Object>();
        result.put("table_proposals", (long) candidates.size());
        result.put("matched_reference_tables_at_iou_0_5", (long) matches.size());
        result.put("unmatched_proposals", (long) (candidates.size() - matches.size()));
        final var records = new ArrayList<Object>();
        for (final var match : matches) {
            final var record = new LinkedHashMap<String, Object>();
            record.put("reference", (long) match.reference())
            ;
            record.put("proposal", (long) match.candidate())
            ;
            record.put("envelope_iou",
                    TableGeometry.iou(references.get(match.reference()), candidates.get(match.candidate())))
            ;
            records.add(record);
        }
        result.put("matches", records);
        return result;
    }

    /**
     * Sums integer metric fields over selected records, as the Python totals comprehensions do.
     *
     * @param records per-input metric maps
     * @param key integer field
     * @return total
     */
    static long sum(final List<Map<String, Object>> records, final String key) {
        long total = 0;
        for (final var record : records) {
            final Object value = PythonJson.member(record, key);
            total += value instanceof Boolean flag ? (flag ? 1 : 0) : ((Number) value).longValue();
        }
        return total;
    }
}
