package dev.docweave.validation;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Classpath launcher for development tooling, preserving 0/1/2 exact/different/invalid exit codes. */
final class SavedOutputComparisonCli {
    private SavedOutputComparisonCli() {
    }

    /** Infrastructure entrypoint; the comparison service itself remains package-private and interface-based. */
    public static void main(final String[] arguments) {
        System.exit(run(arguments, System.out, System.err));
    }

    /** Writes one aggregate JSON report to stdout, or an invalid-input diagnostic to stderr. */
    static int run(final String[] arguments, final PrintStream output, final PrintStream errors) {
        if (arguments.length != 2) {
            errors.println("Usage: SavedOutputComparisonCli reference.json candidate.json");
            return 2;
        }
        try {
            final var mapper = JsonMapper.builder().build();
            final var reference = load(Path.of(arguments[0]));
            final var candidate = load(Path.of(arguments[1]));
            final var report = SavedOutputComparator.create().compare(reference, candidate);
            output.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            return Boolean.TRUE.equals(report.get("exact_match")) ? 0 : 1;
        } catch (final IOException | RuntimeException error) {
            errors.println("Invalid benchmark input: " + error.getMessage());
            return 2;
        }
    }

    /** Matches the former UTF-8-only file loader and rejects trailing JSON tokens/nonstandard numbers. */
    static JsonNode load(final Path path) throws IOException {
        return JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .build()
                .readTree(Files.readString(path, java.nio.charset.StandardCharsets.UTF_8));
    }
}
