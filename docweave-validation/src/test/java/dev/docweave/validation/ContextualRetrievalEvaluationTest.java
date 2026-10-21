package dev.docweave.validation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Independent exact-metric controls; owned rankings are not a live retrieval-quality benchmark. */
class ContextualRetrievalEvaluationTest {
    /** Creates immutable source/relevance controls. */
    ContextualRetrievalEvaluationTest() {
    }

    /** Every failed/missing query remains in the denominator with complete multi-relevance populations. */
    @Test
    void completePopulationChargesFailuresAndMissingQueriesAsZero() {
        final var queries = List.of(query("first", "a", "c"), query("failed", "b"), query("missing", "d"));
        final var captures = List.of(
                capture("first", "b", "c", "a"), new ContextualRetrievalEvaluation.Capture("failed", false, List.of()));
        final var report = score(queries, captures, 2);
        assertEquals(3, report.get("reference_queries"));
        assertEquals(1, report.get("successful_queries"));
        assertEquals(1, report.get("failed_queries"));
        assertEquals(1, report.get("missing_queries"));
        assertEquals(1.0 / 6, report.get("macro_recall_at_k"));
        assertEquals(1.0 / 6, report.get("mean_reciprocal_rank_at_k"));
        assertEquals(1.0 / 3, report.get("hit_rate_at_k"));
        assertEquals(
                List.of("SUCCESS", "FAILED", "MISSING"), details(report).stream().map(q -> q.get("outcome")).toList());
    }

    /** Rank depth and exact capture order are preserved; relevance labels never move hits earlier. */
    @Test
    void commonRankDepthAndCapturedOrderControlTheMetrics() {
        final var queries = List.of(query("query", "a", "c"));
        final var captured = List.of(capture("query", "b", "c", "a"));
        assertEquals(0.0, score(queries, captured, 1).get("macro_recall_at_k"));
        assertEquals(0.5, score(queries, captured, 2).get("macro_recall_at_k"));
        assertEquals(1.0, score(queries, captured, 3).get("macro_recall_at_k"));
        assertEquals(0.5, score(queries, captured, 3).get("mean_reciprocal_rank_at_k"));
        final var reversed = List.of(capture("query", "a", "c", "b"));
        assertEquals(1.0, score(queries, reversed, 3).get("mean_reciprocal_rank_at_k"));
    }

    /** Duplicate or out-of-scope identities cannot silently remove failures or inflate relevant hits. */
    @Test
    void ambiguousAndOutOfScopeCapturesAreRejected() {
        final var queries = List.of(query("query", "a"));
        assertThrows(IllegalArgumentException.class, () -> capture("query", "a", "a"));
        assertThrows(IllegalArgumentException.class, () -> query("query", "a", "a"));
        assertThrows(IllegalArgumentException.class, () -> score(queries, List.of(capture("other", "a")), 1));
        assertThrows(IllegalArgumentException.class, () -> score(queries, List.of(capture("query", "outside")), 1));
        assertThrows(IllegalArgumentException.class, () -> score(List.of(query("query", "outside")), List.of(), 1));
        assertThrows(IllegalArgumentException.class,
                () -> score(List.of(query("query", "a"), query("query", "b")), List.of(), 1));
        assertThrows(IllegalArgumentException.class,
                ()
                        -> score(List.of(query("query", "a"), query("other", "b")),
                                List.of(capture("query", "a"), capture("query", "b")), 1));
        assertThrows(IllegalArgumentException.class,
                () -> new ContextualRetrievalEvaluation.Capture("query", false, List.of("a")));
    }

    /**
     * Scores independent fixed source identities.
     * @param queries complete reference queries
     * @param captures original observed rankings
     * @param k explicit rank depth
     * @return numeric complete-population score
     */
    private static Map<String, Object> score(final List<ContextualRetrievalEvaluation.Query> queries,
            final List<ContextualRetrievalEvaluation.Capture> captures, final int k) {
        return ContextualRetrievalEvaluation.score(List.of("a", "b", "c", "d"), queries, captures, k);
    }

    /**
     * Supplies source relevance without referring to candidate ranks.
     * @param id original query identity
     * @param relevant complete relevant source chunks
     * @return immutable reference
     */
    private static ContextualRetrievalEvaluation.Query query(final String id, final String... relevant) {
        return new ContextualRetrievalEvaluation.Query(id, List.of(relevant));
    }

    /**
     * Supplies an actual-ranker-order fixture, without relevance-based repair.
     * @param id original query identity
     * @param ranked original observed order
     * @return complete successful capture
     */
    private static ContextualRetrievalEvaluation.Capture capture(final String id, final String... ranked) {
        return new ContextualRetrievalEvaluation.Capture(id, true, List.of(ranked));
    }

    /**
     * Reads the documented immutable per-query numeric report.
     * @param report complete score
     * @return ordered query outcomes
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> details(final Map<String, Object> report) {
        return (List<Map<String, Object>>) report.get("queries");
    }
}
