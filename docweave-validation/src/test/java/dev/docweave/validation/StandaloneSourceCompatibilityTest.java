package dev.docweave.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.*;

/** Keeps standalone examples and benchmark tools within the same Java 21 baseline as library modules. */
class StandaloneSourceCompatibilityTest {
    /** Creates isolated source-compatibility controls. */
    StandaloneSourceCompatibilityTest() {
    }

    /**
     * Compiles every standalone launcher and runs owned diagnostic controls in isolated JVMs.
     * @param destination fresh class-output directory owned by this test
     * @throws Exception if compilation, process execution or bounded joining fails
     */
    @Test
    void allStandaloneSourcesCompileForJava21(final @TempDir Path destination) throws Exception {
        final var sources = new ArrayList<Path>();
        for (final var directory : List.of(Path.of("examples"), Path.of("benchmarks/tools"))) {
            try (final var files = Files.list(directory)) {
                sources.addAll(files.filter(path -> path.toString().endsWith(".java")).sorted().toList());
            }
        }
        assertFalse(sources.isEmpty());
        final var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Standalone validation requires a JDK");
        final var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (final var manager =
                        compiler.getStandardFileManager(diagnostics, null, java.nio.charset.StandardCharsets.UTF_8)) {
            final var options = List.of("--release", "21", "-proc:none", "-classpath",
                    System.getProperty("java.class.path"), "-d", destination.toString());
            assertTrue(compiler.getTask(null, manager, diagnostics, options, null,
                                       manager.getJavaFileObjectsFromPaths(sources))
                               .call(),
                    () -> diagnostics.getDiagnostics().toString());
        }
        for (final String helper : List.of("ProbeNativeModelText", "AuditCapturedRowBands", "AuditPublishedRowBands",
                     "ProbeHeaderWrapGeometry", "AuditCapturedHeaderPrefixes", "AuditHeaderCellOwnership",
                     "HeaderSpanEvaluation", "ScoreScientificNativeComparison", "AuditScientificTextDifferences",
                     "MeasurePackagedPdfStudy")) {
            // A subprocess keeps each diagnostic and package-private native helpers in the same class loader.
            final var controls = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", destination + java.io.File.pathSeparator + System.getProperty("java.class.path"),
                    (helper.equals("MeasurePackagedPdfStudy") ? ""
                            : helper.equals("ScoreScientificNativeComparison")
                                            || helper.equals("AuditScientificTextDifferences")
                                    ? "dev.docweave.validation."
                                    : "dev.docweave.partition.")
                            + helper,
                    "--self-test")
                                         .redirectErrorStream(true)
                                         .start();
            try {
                assertTrue(controls.waitFor(20, java.util.concurrent.TimeUnit.SECONDS),
                        "Diagnostic controls timed out: " + helper);
                assertEquals(0, controls.exitValue(), () -> {
                    try {
                        return new String(
                                controls.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    } catch (final java.io.IOException failure) {
                        return failure.toString();
                    }
                });
            } finally {
                controls.destroyForcibly();
            }
        }
    }
}
