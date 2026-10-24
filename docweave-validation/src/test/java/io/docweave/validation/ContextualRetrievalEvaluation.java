package io.docweave.validation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Exact offline ranking-capture scoring; never generates context, embeds text or substitutes missing queries. */
final class ContextualRetrievalEvaluation {
    /**
     * Frozen independent relevance population for one query.
     * @param id unique query identity
     * @param relevantChunkIds nonempty distinct original chunk identities
     */
    record Query(String id, List<String> relevantChunkIds) {
        /**
         * Snapshots relevance labels and rejects ambiguous/empty source identities.
         * @throws IllegalArgumentException if an identity/population is invalid
         */
        Query {
            identity(id);
            relevantChunkIds = List.copyOf(relevantChunkIds);
            if (relevantChunkIds.isEmpty() || relevantChunkIds.size() > 10_000
                    || new HashSet<>(relevantChunkIds).size() != relevantChunkIds.size()) {
                throw new IllegalArgumentException("Invalid complete query relevance population");
            }
            relevantChunkIds.forEach(ContextualRetrievalEvaluation::identity);
        }
    }

    /**
     * One actual retrieval outcome, with the ranker order preserved exactly.
     * @param queryId original query identity
     * @param success whether actual retrieval completed
     * @param rankedChunkIds complete captured ranked IDs, empty for failed retrieval
     */
    record Capture(String queryId, boolean success, List<String> rankedChunkIds) {
        /**
         * Rejects duplicate ranks and results attached to a failed observation.
         * @throws IllegalArgumentException if captured identities/outcome disagree
         */
        Capture {
            identity(queryId);
            rankedChunkIds = List.copyOf(rankedChunkIds);
            if (rankedChunkIds.size() > 10_000 || new HashSet<>(rankedChunkIds).size() != rankedChunkIds.size()
                    || !success && !rankedChunkIds.isEmpty()) {
                throw new IllegalArgumentException("Invalid complete captured ranking");
            }
            rankedChunkIds.forEach(ContextualRetrievalEvaluation::identity);
        }
    }

    /** Prevents utility instantiation. */
    private ContextualRetrievalEvaluation() {
    }

    /**
     * Scores complete frozen query/relevance populations, charging missing and failed captures as zero.
     * Captured rank order is never repaired, deduplicated, reranked or supplemented from relevance labels.
     * Macro recall divides relevant hits by each query's complete relevance count before averaging;
     * reciprocal rank uses the first relevant result within k. A hit rate counts queries with any hit.
     * @param chunkIds complete distinct original source chunk identities
     * @param queries complete independent relevance population
     * @param captures actual outcomes; missing queries remain explicit zero-score observations
     * @param topK positive common rank depth, at most1000
     * @return numeric/per-query report retaining every original query
     * @throws IllegalArgumentException if identities, scope or bounded populations are invalid
     */
    static Map<String, Object> score(
            final List<String> chunkIds, final List<Query> queries, final List<Capture> captures, final int topK) {
        Objects.requireNonNull(chunkIds, "chunkIds");
        Objects.requireNonNull(queries, "queries");
        Objects.requireNonNull(captures, "captures");
        if (chunkIds.isEmpty() || chunkIds.size() > 10_000 || queries.isEmpty() || queries.size() > 10_000
                || captures.size() > queries.size() || topK < 1 || topK > 1000) {
            throw new IllegalArgumentException("Invalid complete retrieval population or rank depth");
        }
        final Set<String> chunks = new HashSet<>(chunkIds);
        if (chunks.size() != chunkIds.size()) {
            throw new IllegalArgumentException("Duplicate original chunk identity");
        }
        chunks.forEach(ContextualRetrievalEvaluation::identity);
        final var expected = new HashSet<String>();
        for (final var query : queries) {
            if (!expected.add(query.id()) || !chunks.containsAll(query.relevantChunkIds())) {
                throw new IllegalArgumentException("Duplicate query or relevance outside source scope");
            }
        }
        final var observed = new LinkedHashMap<String, Capture>();
        for (final var capture : captures) {
            if (!expected.contains(capture.queryId()) || !chunks.containsAll(capture.rankedChunkIds())
                    || observed.putIfAbsent(capture.queryId(), capture) != null) {
                throw new IllegalArgumentException("Unknown/duplicate query or ranked chunk outside source scope");
            }
        }
        int succeeded = 0;
        int failed = 0;
        int missing = 0;
        int hitQueries = 0;
        double recall = 0;
        double reciprocalRank = 0;
        final var details = new ArrayList<Map<String, Object>>(queries.size());
        for (final var query : queries) {
            final var capture = observed.get(query.id());
            final var status = capture == null ? "MISSING" : capture.success() ? "SUCCESS" : "FAILED";
            succeeded += status.equals("SUCCESS") ? 1 : 0;
            failed += status.equals("FAILED") ? 1 : 0;
            missing += status.equals("MISSING") ? 1 : 0;
            final var ranked = capture == null ? List.<String>of() : capture.rankedChunkIds();
            final var relevant = new HashSet<>(query.relevantChunkIds());
            int hits = 0;
            int first = 0;
            for (int index = 0; index < Math.min(topK, ranked.size()); index++) {
                if (relevant.contains(ranked.get(index))) {
                    hits++;
                    if (first == 0) {
                        first = index + 1;
                    }
                }
            }
            final double queryRecall = (double) hits / relevant.size();
            final double queryReciprocal = first == 0 ? 0 : 1.0 / first;
            recall += queryRecall;
            reciprocalRank += queryReciprocal;
            hitQueries += hits > 0 ? 1 : 0;
            details.add(Map.of("query_id", query.id(), "outcome", status, "relevant_chunks", relevant.size(),
                    "observed_ranks", ranked.size(), "relevant_hits_at_k", hits, "first_relevant_rank_at_k", first,
                    "recall_at_k", queryRecall, "reciprocal_rank_at_k", queryReciprocal));
        }
        final var report = new LinkedHashMap<String, Object>();
        report.put("source_chunks", chunks.size());
        report.put("reference_queries", queries.size());
        report.put("top_k", topK);
        report.put("successful_queries", succeeded);
        report.put("failed_queries", failed);
        report.put("missing_queries", missing);
        report.put("hit_queries_at_k", hitQueries);
        report.put("hit_rate_at_k", (double) hitQueries / queries.size());
        report.put("macro_recall_at_k", recall / queries.size());
        report.put("mean_reciprocal_rank_at_k", reciprocalRank / queries.size());
        report.put("queries", List.copyOf(details));
        return Map.copyOf(report);
    }

    /**
     * Bounds content-free source/query identifiers without rewriting their characters.
     * @param value nonblank original identity
     * @throws IllegalArgumentException if blank/oversize
     */
    private static void identity(final String value) {
        Objects.requireNonNull(value, "identity");
        if (value.isBlank() || value.length() > 1024) {
            throw new IllegalArgumentException("Retrieval identity must contain1 through1024 UTF16 units");
        }
    }
}
