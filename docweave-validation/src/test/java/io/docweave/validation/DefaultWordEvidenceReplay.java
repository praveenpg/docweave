package io.docweave.validation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hidden replay coordinator. Every source file is captured and hash-checked before any output is created.
 * Current candidates are collected separately from immutable source observations and archived candidates.
 */
final class DefaultWordEvidenceReplay implements WordEvidenceReplay {
    static final String CATALOG = "word-evidence-manifest.json";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final WordCandidateCollector collector;

    DefaultWordEvidenceReplay(final WordCandidateCollector collector) {
        this.collector = java.util.Objects.requireNonNull(collector);
    }

    @Override
    public Report run(final Path reviewRoot, final Path output) throws IOException {
        DefaultWordEvidenceComparator.require(!Files.exists(output), "Word evidence output already exists");
        final byte[] catalogBytes = Files.readAllBytes(reviewRoot.resolve(CATALOG));
        final var catalog = JSON.readTree(catalogBytes);
        DefaultWordEvidenceComparator.require(catalog.has("version") && catalog.get("version").isIntegralNumber()
                                                      && catalog.get("version").canConvertToInt()
                                                      && catalog.get("version").asInt() == 1,
                "Unsupported Word evidence catalog");
        final var sources = new ArrayList<Packet>();
        final var names = new java.util.HashSet<String>();
        for (final var description : DefaultWordEvidenceComparator.array(catalog, "packets")) {
            final var packet = load(reviewRoot, description, true);
            DefaultWordEvidenceComparator.require(names.add(packet.name()), "Duplicate evidence packet");
            sources.add(packet);
        }
        DefaultWordEvidenceComparator.require(sources.size() == 2, "Saved replay requires the two supported packets");
        final var controls = load(reviewRoot, catalog.get("excluded_controls"), false);
        DefaultWordEvidenceComparator.require(names.add(controls.name()), "Duplicate excluded evidence packet");
        DefaultWordEvidenceComparator.require(
                DefaultWordEvidenceComparator.manifestCases(controls.manifest()).size() == 4,
                "Saved catalog must retain the four excluded heading controls");
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.createDirectory(output);
        writeBytes(output.resolve(CATALOG), catalogBytes);
        copy(controls, output.resolve(controls.name()));
        final var summaries = new ArrayList<PacketSummary>();
        final var comparator = WordEvidenceComparator.create();
        for (final var packet : sources) {
            final Path destination = output.resolve(packet.name());
            copy(packet, destination);
            final var candidate = collector.collect(destination, packet.manifest());
            CorpusReview.write(destination.resolve("candidate.json"), candidate);
            final var comparison =
                    comparator.compare(packet.manifest(), candidate, packet.observations(), packet.manifestHash());
            CorpusReview.write(destination.resolve("comparison.json"), comparison.json());
            summaries.add(new PacketSummary(packet.name(), comparison.cases().size(), comparison.differenceCount(),
                    packet.manifestHash(), DocxOwnership.sha256(packet.observationBytes()),
                    hash(destination.resolve("candidate.json")), hash(destination.resolve("comparison.json"))));
        }
        final var provenance = new LinkedHashMap<String, Object>();
        provenance.put("java_version", Runtime.version().toString());
        provenance.put("java_vendor", System.getProperty("java.vendor"));
        provenance.put("validation_sources", ValidationProvenance.sources());
        provenance.put("runtime_classpath", ValidationProvenance.classpath());
        final var report = new Report(summaries, DocxOwnership.sha256(catalogBytes), provenance);
        CorpusReview.write(output.resolve("summary.json"), report.json());
        return report;
    }

    /** Snapshot includes raw BOM/CRLF bytes and all pinned original inputs; none are rewritten. */
    private static Packet load(final Path reviewRoot, final JsonNode description, final boolean comparable)
            throws IOException {
        final String name = DefaultWordEvidenceComparator.filename(description, "directory", "");
        final var directory = reviewRoot.resolve(name);
        final var pins = description.get("files");
        DefaultWordEvidenceComparator.require(pins != null && pins.isObject(), "Evidence file pins required");
        final var bytes = new LinkedHashMap<String, byte[]>();
        for (final var entry : pins.properties()) {
            final String filename = entry.getKey();
            final var field = JSON.createObjectNode().put("name", filename);
            DefaultWordEvidenceComparator.filename(field, "name", ".json");
            DefaultWordEvidenceComparator.require(
                    entry.getValue().isString() && entry.getValue().asString().matches("[a-f0-9]{64}"),
                    "Evidence file SHA-256 required");
            final byte[] content = Files.readAllBytes(directory.resolve(filename));
            DefaultWordEvidenceComparator.require(DocxOwnership.sha256(content).equals(entry.getValue().asString()),
                    "Pinned evidence file hash mismatch: " + filename);
            bytes.put(filename, content);
        }
        DefaultWordEvidenceComparator.require(bytes.containsKey("manifest.json"), "Pinned manifest required");
        final String manifestHash = DocxOwnership.sha256(bytes.get("manifest.json"));
        final var manifest = JSON.readTree(bytes.get("manifest.json"));
        for (final var entry : DefaultWordEvidenceComparator.manifestCases(manifest).entrySet()) {
            final byte[] data = Files.readAllBytes(directory.resolve(entry.getKey()));
            DefaultWordEvidenceComparator.require(
                    DocxOwnership.sha256(data).equals(entry.getValue().get("sha256").asString()),
                    "Pinned input hash mismatch: " + entry.getKey());
            bytes.put(entry.getKey(), data);
        }
        final String observation =
                comparable ? DefaultWordEvidenceComparator.filename(description, "observations", ".json")
                           : "word-observations.json";
        DefaultWordEvidenceComparator.require(bytes.containsKey(observation), "Pinned Word observations required");
        final var observations = JSON.readTree(bytes.get(observation));
        DefaultWordEvidenceComparator.observationCases(manifest, observations, manifestHash);
        if (comparable) {
            final String first = DefaultWordEvidenceComparator.filename(description, "first_observations", ".json");
            DefaultWordEvidenceComparator.require(bytes.containsKey(first) && bytes.containsKey("candidate.json")
                                                          && bytes.containsKey("word-comparison.json"),
                    "Pinned first observation and historical reports required");
            final var original = JSON.readTree(bytes.get(first));
            DefaultWordEvidenceComparator.observationCases(manifest, original, manifestHash);
            DefaultWordEvidenceComparator.require(original.get("cases").equals(observations.get("cases")),
                    "First and repeat Word measurements disagree");
        }
        return new Packet(name, manifestHash, manifest, observations, bytes.get(observation), bytes);
    }

    private static void copy(final Packet packet, final Path output) throws IOException {
        Files.createDirectory(output);
        for (final var entry : packet.files().entrySet()) {
            final String target =
                    entry.getKey().equals("candidate.json") ? "historical-candidate.json" : entry.getKey();
            writeBytes(output.resolve(target), entry.getValue());
        }
        writeBytes(output.resolve("observations.json"), packet.observationBytes());
    }

    private static void writeBytes(final Path path, final byte[] bytes) throws IOException {
        Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static String hash(final Path path) throws IOException {
        return DocxOwnership.sha256(Files.readAllBytes(path));
    }

    private record Packet(String name, String manifestHash, JsonNode manifest, JsonNode observations,
            byte[] observationBytes, Map<String, byte[]> files) {}
}
