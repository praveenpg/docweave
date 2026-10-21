package dev.docweave.validation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Shared source/runtime fingerprints for independent development-only evidence checks. */
final class ValidationProvenance {
    private ValidationProvenance() {
    }

    /** Fingerprints the validation sources actually present during this run, not historical versions. */
    static Map<String, String> sources() throws IOException {
        final var hashes = new TreeMap<String, String>();
        final var directory = Path.of("docweave-validation/src/test/java/dev/docweave/validation");
        try (final var paths = Files.list(directory)) {
            for (final var path : paths.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                hashes.put(path.getFileName().toString(), DocxOwnership.sha256(Files.readAllBytes(path)));
            }
        }
        return Map.copyOf(hashes);
    }

    /** Surefire uses class directories; record ordered entries and every actual class/JAR byte hash. */
    static List<Map<String, Object>> classpath() throws IOException {
        final String raw = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        final var entries = new ArrayList<Map<String, Object>>();
        for (final String item : raw.split(Pattern.quote(System.getProperty("path.separator")))) {
            final var path = Path.of(item);
            final var hashes = new TreeMap<String, String>();
            if (Files.isDirectory(path)) {
                try (final var paths = Files.walk(path)) {
                    for (final var file : paths.filter(Files::isRegularFile).sorted().toList()) {
                        hashes.put(path.relativize(file).toString().replace('\\', '/'),
                                DocxOwnership.sha256(Files.readAllBytes(file)));
                    }
                }
            } else if (Files.isRegularFile(path)) {
                hashes.put(path.getFileName().toString(), DocxOwnership.sha256(Files.readAllBytes(path)));
            }
            entries.add(Map.of("index", entries.size(), "entry", path.getFileName().toString(), "exists",
                    Files.exists(path), "sha256", Map.copyOf(hashes)));
        }
        return List.copyOf(entries);
    }
}
