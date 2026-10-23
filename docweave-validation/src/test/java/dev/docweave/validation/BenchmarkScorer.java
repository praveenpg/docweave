package dev.docweave.validation;

import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.Optional;

/**
 * Development-only, text-free benchmark scorer that replaced a retired Python comparison tool.
 *
 * <p>Scorers read retained numeric captures, pinned original inputs and evaluation-only labels, verify
 * every declared identity before scoring, and write a new report whose bytes match the retired tool.
 * They never execute document engines, models or Unstructured. Implementations are hidden.
 */
interface BenchmarkScorer {
    /**
     * Returns the command name used by {@link ScoreBenchmarkCaptures}.
     *
     * @return stable kebab-case command
     */
    String command();

    /**
     * Runs the scorer.
     *
     * @param arguments command arguments after the command name
     * @param console destination for the human-readable summary the retired tool printed
     * @throws IOException if an input cannot be read or the new output cannot be written
     * @throws IllegalArgumentException if an identity, pairing or denominator check fails
     */
    void run(List<String> arguments, PrintStream console) throws IOException;

    /**
     * Returns every migrated scorer.
     *
     * @return scorers in documentation order
     */
    static List<BenchmarkScorer> all() {
        return List.of(new BusinessCellTextScorer(), new TiledRegionScorer(), new DetectorStructureScorer(),
                new CompactLayoutScorer(), new RuledScanScorer(), new RasterRulePrototypeScorer(),
                new RasterRuleAuditScorer(), new ScanDetectorVerification(), new UpstreamTableSummary());
    }

    /**
     * Finds a scorer by command name.
     *
     * @param command command name
     * @return matching scorer, if any
     */
    static Optional<BenchmarkScorer> named(final String command) {
        return all().stream().filter(scorer -> scorer.command().equals(command)).findFirst();
    }
}
