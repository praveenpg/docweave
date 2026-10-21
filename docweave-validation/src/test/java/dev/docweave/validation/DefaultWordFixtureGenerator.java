package dev.docweave.validation;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Owned experimental transformations; all seeds and derivatives are validated before creating output. */
final class DefaultWordFixtureGenerator implements WordFixtureGenerator {
    private static final String WORD = XmlOwnerReader.WORD;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private enum Mode { ORIGINAL, ORDERED, PLAIN, OUTLINE, OUTLINE_DIRECT, DISTINCT, RESTART, DIRECT }

    private record Spec(String name, String seed, Mode mode, String question) {}

    private record Generated(String name, byte[] bytes, Map<String, Object> contract) {}

    private static final List<Spec> CASES =
            List.of(new Spec("numbering-original-order", "table-style-numbering", Mode.ORIGINAL,
                            "Baseline conditional outline-before-numbering order"),
                    new Spec("numbering-numpr-first", "table-style-numbering", Mode.ORDERED,
                            "Change only conditional numPr/outlineLvl order"),
                    new Spec("override-original-order", "direct-override-control", Mode.ORIGINAL,
                            "Baseline explicit body outline and numId zero"),
                    new Spec("override-numpr-first", "direct-override-control", Mode.ORDERED,
                            "Change only conditional numPr/outlineLvl order"),
                    new Spec("plain-direct-body", "direct-override-control", Mode.PLAIN,
                            "Explicit body outline without table-style inheritance"),
                    new Spec("conditional-outline-only", "direct-override-control", Mode.OUTLINE,
                            "Conditional outline without any numbering or direct outline"),
                    new Spec("conditional-direct-body", "direct-override-control", Mode.OUTLINE_DIRECT,
                            "Add explicit body outline to outline-only control"),
                    new Spec("numbering-distinct-abstract", "table-style-numbering", Mode.DISTINCT,
                            "Use distinct otherwise identical abstract numbering definitions"),
                    new Spec("numbering-explicit-restart", "table-style-numbering", Mode.RESTART,
                            "Add level-zero start override to numId one"),
                    new Spec("direct-numbering-shared", "table-style-numbering", Mode.DIRECT,
                            "Move numId selection from table styles onto paragraphs"));

    @Override
    public void followups(final Path source, final Path output) throws IOException {
        final var seeds = seeds(source, CASES.stream().map(Spec::seed).distinct().toList());
        final var generated = new ArrayList<Generated>();
        for (final var spec : CASES) {
            final byte[] seed = seeds.get(spec.seed());
            final var packet = new FixturePackage(seed);
            final var document = packet.xml("word/document.xml");
            final var styles = packet.xml("word/styles.xml");
            final var numbering = packet.xml("word/numbering.xml");
            final var paragraphs = paragraphs(document);
            rename(document, spec.seed(), spec.name());
            if (spec.mode() != Mode.ORIGINAL) {
                reorder(styles);
            }
            switch (spec.mode()) {
            case ORIGINAL, ORDERED -> {
            }
            case PLAIN -> {
                final var properties = FixturePackage.descendants(document, "tblPr").getFirst();
                properties.removeChild(FixturePackage.child(properties, "tblStyle"));
            }
            case OUTLINE, OUTLINE_DIRECT -> {
                removeNumbering(styles);
                for (final var properties : FixturePackage.descendants(document, "pPr")) {
                    for (var child = properties.getFirstChild(); child != null;) {
                        final var next = child.getNextSibling();
                        if (spec.mode() == Mode.OUTLINE
                                || (WORD.equals(child.getNamespaceURI()) && "numPr".equals(child.getLocalName()))) {
                            properties.removeChild(child);
                        }
                        child = next;
                    }
                }
            }
            case DISTINCT -> {
                final var abstractNum = FixturePackage.descendants(numbering, "abstractNum").getFirst();
                final var copy = (Element) abstractNum.cloneNode(true);
                copy.setAttributeNS(WORD, "w:abstractNumId", "1");
                abstractNum.getParentNode().insertBefore(copy, abstractNum.getNextSibling());
                final var target = numbered(numbering, "2");
                FixturePackage.child(target, "abstractNumId").setAttributeNS(WORD, "w:val", "1");
            }
            case RESTART -> {
                final var override = FixturePackage.append(numbered(numbering, "1"), "lvlOverride", "ilvl", "0");
                FixturePackage.append(override, "startOverride", "val", "1");
            }
            case DIRECT -> {
                removeNumbering(styles);
                for (int index = 0; index < paragraphs.size(); index++) {
                    final var paragraph = paragraphs.get(index);
                    var properties = FixturePackage.child(paragraph, "pPr");
                    if (properties == null) {
                        properties = paragraph.getOwnerDocument().createElementNS(WORD, "w:pPr");
                        paragraph.insertBefore(properties, paragraph.getFirstChild());
                    }
                    final var num = FixturePackage.append(properties, "numPr", null, null);
                    FixturePackage.append(num, "numId", "val", index < 3 ? "2" : "1");
                }
            }
            }
            packet.replace("word/document.xml", document);
            packet.replace("word/styles.xml", styles);
            packet.replace("word/numbering.xml", numbering);
            final byte[] bytes = packet.bytes();
            generated.add(new Generated(
                    spec.name(), bytes, contract(spec.name(), spec.seed(), seed, bytes, spec.question(), null)));
        }
        write(output, "Owned controlled follow-ups; not Word expectations", generated);
    }

    @Override
    public void outlineControls(final Path source, final Path output) throws IOException {
        final var seeds = seeds(source, List.of("plain-direct-body", "conditional-direct-body"));
        final var generated = new ArrayList<Generated>();
        for (final String seed : List.of("plain-direct-body", "conditional-direct-body")) {
            for (final int level : List.of(0, 3)) {
                final String name = seed.replace("body", "outline-" + level);
                final var packet = new FixturePackage(seeds.get(seed));
                final var document = packet.xml("word/document.xml");
                final var outlines = paragraphs(document)
                                             .stream()
                                             .map(paragraph -> FixturePackage.child(paragraph, "pPr"))
                                             .filter(java.util.Objects::nonNull)
                                             .map(properties -> FixturePackage.child(properties, "outlineLvl"))
                                             .filter(java.util.Objects::nonNull)
                                             .toList();
                if (outlines.size() != 9) {
                    throw new IOException("Expected nine direct paragraph outlines");
                }
                outlines.forEach(node -> node.setAttributeNS(WORD, "w:val", Integer.toString(level)));
                rename(document, seed, name);
                packet.replace("word/document.xml", document);
                final byte[] bytes = packet.bytes();
                generated.add(new Generated(name, bytes,
                        contract(name, seed, seeds.get(seed), bytes,
                                "Does direct heading outline override the conditional table style?", level)));
            }
        }
        write(output, "Direct-heading diagnostics only; candidate comparator does not support direct heading ranges",
                generated);
    }

    /** Requires the owned one-table, 3x3, one-direct-paragraph-per-cell experiment geometry. */
    private static List<Element> paragraphs(final Document document) throws IOException {
        final var tables = FixturePackage.descendants(document, "tbl");
        if (tables.size() != 1) {
            throw new IOException("Expected one controlled table");
        }
        final var rows = FixturePackage.descendants(tables.getFirst(), "tr");
        if (rows.size() != 3) {
            throw new IOException("Expected three controlled rows");
        }
        final var result = new ArrayList<Element>();
        for (final var row : rows) {
            final var cells = FixturePackage.descendants(row, "tc");
            if (cells.size() != 3) {
                throw new IOException("Expected three controlled cells per row");
            }
            for (final var cell : cells) {
                final var paragraphs = FixturePackage.descendants(cell, "p");
                if (paragraphs.size() != 1 || paragraphs.getFirst() != FixturePackage.child(cell, "p")) {
                    throw new IOException("Expected one direct paragraph per controlled cell");
                }
                result.add(paragraphs.getFirst());
            }
        }
        return result;
    }

    /** Verifies each required seed's exact manifest hash before interpreting any package content. */
    private static Map<String, byte[]> seeds(final Path source, final List<String> names) throws IOException {
        final var manifest = JSON.readTree(Files.readAllBytes(source.resolve("manifest.json")));
        final var known = DefaultWordEvidenceComparator.manifestCases(manifest);
        final var seeds = new LinkedHashMap<String, byte[]>();
        for (final String name : names) {
            final var contract = known.get(name + ".docx");
            if (contract == null) {
                throw new IOException("Missing fixture seed: " + name);
            }
            final byte[] bytes = Files.readAllBytes(source.resolve(name + ".docx"));
            if (!DocxOwnership.sha256(bytes).equals(contract.get("sha256").asString())) {
                throw new IOException("Pinned seed hash mismatch: " + name);
            }
            seeds.put(name, bytes);
        }
        return seeds;
    }

    /** Keeps questions/provenance and review-pending status separate from generated byte identities. */
    private static Map<String, Object> contract(final String name, final String seed, final byte[] seedBytes,
            final byte[] bytes, final String question, final Integer outline) {
        final var result =
                new LinkedHashMap<String, Object>(Map.of("file", name + ".docx", "sha256", DocxOwnership.sha256(bytes),
                        "seed_file", seed + ".docx", "seed_sha256", DocxOwnership.sha256(seedBytes), "rows", 3,
                        "columns", 3, "question", question, "independent_review", "pending"));
        if (outline != null) {
            result.put("direct_outline_level", outline);
        }
        return result;
    }

    /** Creates a fresh packet only after all transformations succeed; no Word/candidate output is written. */
    private static void write(final Path output, final String scope, final List<Generated> generated)
            throws IOException {
        final Path parent = output.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Files.createDirectory(output);
        for (final var item : generated) {
            Files.write(output.resolve(item.name() + ".docx"), item.bytes(), StandardOpenOption.CREATE_NEW);
        }
        CorpusReview.write(
                output.resolve("manifest.json"), Map.of("scope", scope, "independent_review", "pending", "cases",
                                                         generated.stream().map(Generated::contract).toList()));
    }

    /** Renames cell anchors without changing the controlled text suffixes. */
    private static void rename(final Document document, final String seed, final String name) {
        FixturePackage.descendants(document, "t")
                .forEach(text -> text.setTextContent(text.getTextContent().replace(seed + "-r", name + "-r")));
    }

    /** Moves only numPr before outlineLvl; every other property retains its relative order. */
    private static void reorder(final Document styles) {
        for (final var properties : FixturePackage.descendants(styles, "pPr")) {
            final var num = FixturePackage.child(properties, "numPr");
            final var outline = FixturePackage.child(properties, "outlineLvl");
            if (num != null && outline != null) {
                properties.insertBefore(num, outline);
            }
        }
    }

    /** Removes table-style numbering while retaining source outline definitions. */
    private static void removeNumbering(final Document styles) {
        for (final var properties : FixturePackage.descendants(styles, "pPr")) {
            final var num = FixturePackage.child(properties, "numPr");
            if (num != null) {
                properties.removeChild(num);
            }
        }
    }

    /** Locates a controlled concrete numbering definition by its namespaced ID. */
    private static Element numbered(final Document numbering, final String id) throws IOException {
        return FixturePackage.descendants(numbering, "num")
                .stream()
                .filter(node -> id.equals(node.getAttributeNS(WORD, "numId")))
                .findFirst()
                .orElseThrow(() -> new IOException("Missing controlled numbering definition"));
    }
}
