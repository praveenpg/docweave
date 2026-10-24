package io.docweave.validation;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Command-line entry point for the JVM benchmark scorers that replaced the retired Python tools.
 *
 * <p>Run from the repository root with the validation test classes and the development runtime
 * classpath, for example
 * {@code java -cp docweave-validation/target/test-classes:$CLASSPATH
 * io.docweave.validation.ScoreBenchmarkCaptures tiled-regions --capture21 ...}. No Python process,
 * OCR engine, model or network access is involved.
 */
public final class ScoreBenchmarkCaptures {
    /** Prevents instantiation of this launcher. */
    private ScoreBenchmarkCaptures() {
    }

    /**
     * Dispatches to one scorer and exits nonzero on any failed check.
     *
     * @param arguments command name followed by its options
     * @throws Exception if the selected scorer fails; the JVM then exits nonzero
     */
    public static void main(final String[] arguments) throws Exception {
        final String commands =
                BenchmarkScorer.all().stream().map(BenchmarkScorer::command).collect(Collectors.joining(", "));
        if (arguments.length == 0) {
            System.err.println("Usage: ScoreBenchmarkCaptures <command> [options]; commands: " + commands);
            System.exit(2);
        }
        final var scorer = BenchmarkScorer.named(arguments[0]);
        if (scorer.isEmpty()) {
            System.err.println("Unknown command " + arguments[0] + "; commands: " + commands);
            System.exit(2);
        }
        scorer.get().run(Arrays.asList(arguments).subList(1, arguments.length), System.out);
    }
}
