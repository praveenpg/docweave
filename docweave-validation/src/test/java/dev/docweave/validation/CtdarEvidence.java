package dev.docweave.validation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Identity checks shared by the migrated cTDaR scorers.
 *
 * <p>Every scorer verifies the pinned original images and annotations, and the byte identity of paired
 * Java 21/25 numeric captures, before any label is read for evaluation.
 */
final class CtdarEvidence {
    /** Modern-domain originals; every other selected original is historical. */
    private static final Set<String> MODERN = Set.of("cTDaR_s101", "cTDaR_s102", "cTDaR_s103", "cTDaR_s104");

    /** Prevents instantiation of this static utility class. */
    private CtdarEvidence() {
    }

    /**
     * Pinned original image entry.
     *
     * @param name file stem used for capture directories and XML labels
     * @param sha256 pinned image digest
     */
    record Input(String name, String sha256) {}

    /**
     * Reads a manifest and verifies every pinned cache file.
     *
     * @param manifestPath manifest JSON
     * @param cache original cache directory
     * @param message failure message prefix
     * @param appendName whether the failing filename is appended to the message
     * @return parsed manifest
     * @throws IOException if a file cannot be read
     */
    static Map<String, Object> verifiedManifest(final Path manifestPath, final Path cache, final String message,
            final boolean appendName) throws IOException {
        final var manifest = PythonJson.object(PythonJson.parse(ScoringFiles.readText(manifestPath)), "manifest");
        for (final Object item : PythonJson.array(PythonJson.member(manifest, "inputs"), "inputs")) {
            final var entry = PythonJson.object(item, "input");
            final String filename = (String) PythonJson.member(entry, "filename");
            if (!ScoringFiles.sha256(cache.resolve(filename)).equals(PythonJson.member(entry, "sha256"))) {
                throw new IllegalArgumentException(appendName ? message + filename : message);
            }
        }
        return manifest;
    }

    /**
     * Returns the selected JPEG originals in manifest order.
     *
     * @param manifest parsed manifest
     * @return image entries
     */
    static List<Input> images(final Map<String, Object> manifest) {
        final var result = new ArrayList<Input>();
        for (final Object item : PythonJson.array(PythonJson.member(manifest, "inputs"), "inputs")) {
            final var entry = PythonJson.object(item, "input");
            final String filename = (String) PythonJson.member(entry, "filename");
            if (filename.endsWith(".jpg")) {
                result.add(new Input(stem(filename), (String) PythonJson.member(entry, "sha256")));
            }
        }
        return result;
    }

    /**
     * Returns {@code pathlib.Path(filename).stem}.
     *
     * @param filename manifest filename
     * @return final component without its last suffix
     */
    static String stem(final String filename) {
        final String name = Path.of(filename).getFileName().toString();
        final int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /**
     * Requires identical relative file sets and bytes for paired captures.
     *
     * @param first Java 21 capture root
     * @param second Java 25 capture root
     * @param differentSets message when file sets differ
     * @param differentBytes message prefix when one file differs
     * @param appendName whether the differing relative path is appended
     * @return number of paired files
     * @throws IOException if a file cannot be read
     */
    static int pairedFiles(final Path first, final Path second, final String differentSets, final String differentBytes,
            final boolean appendName) throws IOException {
        final var files = ScoringFiles.relativeFiles(first);
        if (!files.equals(ScoringFiles.relativeFiles(second))) {
            throw new IllegalArgumentException(differentSets);
        }
        for (final String name : files) {
            if (!Arrays.equals(Files.readAllBytes(first.resolve(name)), Files.readAllBytes(second.resolve(name)))) {
                throw new IllegalArgumentException(appendName ? differentBytes + name : differentBytes);
            }
        }
        return files.size();
    }

    /**
     * Returns the whole-source outcome: the stripped {@code outcome.txt} when present, otherwise {@code OK}.
     *
     * @param capture source capture directory
     * @return outcome
     * @throws IOException if the file cannot be read
     */
    static String outcome(final Path capture) throws IOException {
        final Path file = capture.resolve("outcome.txt");
        return Files.exists(file) ? ScoringFiles.strip(ScoringFiles.readText(file)) : "OK";
    }

    /**
     * Reads and strips a required outcome file.
     *
     * @param directory profile directory
     * @return stripped outcome
     * @throws IOException if the file cannot be read
     */
    static String requiredOutcome(final Path directory) throws IOException {
        return ScoringFiles.strip(ScoringFiles.readText(directory.resolve("outcome.txt")));
    }

    /**
     * Classifies an original as modern or historical.
     *
     * @param name file stem
     * @return domain label
     */
    static String domain(final String name) {
        return MODERN.contains(name) ? "modern" : "historical";
    }

    /**
     * Reads the single captured source frame.
     *
     * @param capture source capture directory
     * @return first {@code source.tsv} row
     * @throws IOException if the file cannot be read
     */
    static Map<String, String> frame(final Path capture) throws IOException {
        final var rows = ScoringFiles.rows(capture.resolve("source.tsv"));
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Missing source frame");
        }
        return rows.getFirst();
    }

    /**
     * Writes a report as {@code json.dumps(report, indent=2) + '\n'} to a file that must not exist.
     *
     * @param output new report path
     * @param report report value
     * @param sortKeys whether keys are sorted
     * @throws IOException if writing fails
     */
    static void write(final Path output, final Object report, final boolean sortKeys) throws IOException {
        Files.writeString(
                output, PythonJson.dumps(report, sortKeys) + "\n", java.nio.file.StandardOpenOption.CREATE_NEW);
    }
}
