package io.docweave.partition;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Multi-budget precedence through whole-document partitioning at common-pool parallelism 1 to 4 (batch sizes 2 to 5),
 * each in its own JVM, against the rule measured on beta.13: the earliest batch with an event wins; in that batch an
 * image crossing beats a replay check (B or C), the page-local check A beats a replay check, and A against an image
 * crossing is a timing race whose outcome must be one of the two.
 */
class PdfBudgetPrecedenceTest {
    /** Page-local message. */
    private static final String A = "FAIL ELEMENT_LIMIT PDF page exceeds document element budget";
    /** Image message. */
    private static final String IMAGE = "FAIL IMAGE_LIMIT PDF images exceed maxImageBytes";

    /** Creates the test. */
    PdfBudgetPrecedenceTest() {
    }

    /**
     * Every scenario at every batch size follows the measured rule.
     * @throws Exception if a probe JVM fails
     */
    @Test
    void precedenceFollowsTheBeta13RuleAtEveryBatchSize() throws Exception {
        for (int parallelism = 1; parallelism <= 4; parallelism++) {
            final var outcomes = probe(parallelism);
            final int batch = Integer.parseInt(outcomes.get("batch"));
            assertEquals(parallelism + 1, batch);
            for (final var scenario : PdfPrecedenceProbe.scenarios()) {
                final var allowed = expected(scenario, batch);
                final String actual = outcomes.get(scenario.name());
                assertTrue(allowed.contains(actual),
                        scenario.name() + " B=" + batch + ": " + actual + " not in " + allowed);
            }
        }
    }

    /**
     * Applies the measured precedence rule.
     * @param scenario scenario
     * @param batch batch size
     * @return accepted outcomes; two only for the A-against-image race
     */
    static Set<String> expected(final PdfPrecedenceProbe.Scenario scenario, final int batch) {
        int earliest = Integer.MAX_VALUE;
        for (final int page : List.of(scenario.elementPage(), scenario.imagePage(), scenario.densePage())) {
            if (page > 0) {
                earliest = Math.min(earliest, (page - 1) / batch);
            }
        }
        if (earliest == Integer.MAX_VALUE) {
            return Set.of("OK " + PdfPrecedenceProbe.PAGES * (PdfPrecedenceProbe.LINES + 1));
        }
        final int first = earliest;
        final boolean image = scenario.imagePage() > 0 && (scenario.imagePage() - 1) / batch == first;
        final boolean dense = scenario.densePage() > 0 && (scenario.densePage() - 1) / batch == first;
        if (image && dense) {
            return Set.of(A, IMAGE);
        }
        if (image) {
            return Set.of(IMAGE);
        }
        if (dense) {
            return Set.of(A);
        }
        final int maxElements =
                (PdfPrecedenceProbe.LINES + 1) * (scenario.elementPage() - 1) + (PdfPrecedenceProbe.LINES + 1) / 2;
        return Set.of(scenario.buffered() ? "FAIL ELEMENT_LIMIT PDF buffered page exceeds pending-element budget"
                                          : "FAIL ELEMENT_LIMIT Document exceeds maxElements=" + maxElements);
    }

    /**
     * Runs the probe in a JVM with a fixed common-pool parallelism.
     * @param parallelism common-pool parallelism
     * @return outcome by scenario name, plus the observed batch size
     * @throws IOException if the JVM cannot run
     * @throws InterruptedException if interrupted while waiting
     */
    private static Map<String, String> probe(final int parallelism) throws IOException, InterruptedException {
        final String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        final var process = new ProcessBuilder(java, "-Xmx1g", "-Djava.awt.headless=true",
                "-Djava.util.concurrent.ForkJoinPool.common.parallelism=" + parallelism, "-cp",
                System.getProperty("java.class.path"), PdfPrecedenceProbe.class.getName())
                                    .redirectErrorStream(true)
                                    .start();
        final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(5, TimeUnit.MINUTES), "probe timed out");
        assertEquals(0, process.exitValue(), output);
        final var outcomes = new LinkedHashMap<String, String>();
        for (final String line : output.split("\n")) {
            final int separator = line.indexOf('|');
            if (separator > 0) {
                outcomes.put(line.substring(0, separator), line.substring(separator + 1).strip());
            }
        }
        return outcomes;
    }
}
