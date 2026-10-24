package io.docweave.validation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Node;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

/** Reproduces owned input semantics without generating Word expectations or repinning saved evidence. */
class WordFixtureGeneratorTest {
    private static final Path REVIEW = Path.of("benchmarks/review");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String W = XmlOwnerReader.WORD;
    @TempDir Path temporary;

    /** Hashes every saved packet artifact to detect accidental source/observation mutation. */
    private static Map<String, String> sourceHashes() throws IOException {
        final var hashes = new TreeMap<String, String>();
        for (final String packet : List.of("word-style-cases", "word-style-followups", "word-outline-controls")) {
            try (final var paths = Files.walk(REVIEW.resolve(packet))) {
                for (final var path : paths.filter(Files::isRegularFile).toList()) {
                    hashes.put(path.toString(), DocxOwnership.sha256(Files.readAllBytes(path)));
                }
            }
        }
        return hashes;
    }

    /** Expands owned packages for independent structural and unchanged-payload comparison. */
    private static Map<String, byte[]> parts(final Path path) throws IOException {
        final var result = new TreeMap<String, byte[]>();
        try (final var zip = new ZipFile(path.toFile())) {
            for (final var entry : zip.stream().toList()) {
                try (final var input = zip.getInputStream(entry)) {
                    result.put(entry.getName(), input.readAllBytes());
                }
            }
        }
        return result;
    }

    /** Expanded XML names/attributes and ordered content ignore serializer prefix/declaration differences. */
    private static Object infoset(final Node node) {
        final var attributes = new TreeMap<String, String>();
        for (int index = 0; node.getAttributes() != null && index < node.getAttributes().getLength(); index++) {
            final var attribute = node.getAttributes().item(index);
            if (!javax.xml.XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI())) {
                attributes.put(
                        "{" + attribute.getNamespaceURI() + "}" + attribute.getLocalName(), attribute.getNodeValue());
            }
        }
        final var children = new ArrayList<Object>();
        for (var child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                children.add(infoset(child));
            } else if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                children.add(child.getNodeValue());
            }
        }
        return List.of("{" + node.getNamespaceURI() + "}" + node.getLocalName(), attributes, children);
    }

    /** Verifies every actual generated ZIP hash, manifest metadata and all part contents. */
    private static int comparePacket(final Path original, final Path generated) throws IOException {
        final var expected = JSON.readTree(Files.readAllBytes(original.resolve("manifest.json")));
        final var actual = JSON.readTree(Files.readAllBytes(generated.resolve("manifest.json")));
        assertEquals(expected.get("scope"), actual.get("scope"));
        assertEquals(expected.get("cases").size(), actual.get("cases").size());
        assertEquals("pending", actual.get("independent_review").asString());
        for (int index = 0; index < expected.get("cases").size(); index++) {
            final var before = (ObjectNode) expected.get("cases").get(index).deepCopy();
            final var after = (ObjectNode) actual.get("cases").get(index).deepCopy();
            final String name = before.get("file").asString();
            assertEquals(
                    before.get("sha256").asString(), DocxOwnership.sha256(Files.readAllBytes(original.resolve(name))));
            assertEquals(
                    after.get("sha256").asString(), DocxOwnership.sha256(Files.readAllBytes(generated.resolve(name))));
            before.remove("sha256");
            after.remove("sha256");
            assertEquals(before, after);
            final var oldParts = parts(original.resolve(name));
            final var newParts = parts(generated.resolve(name));
            assertEquals(oldParts.keySet(), newParts.keySet());
            for (final String part : oldParts.keySet()) {
                if (List.of("word/document.xml", "word/styles.xml", "word/numbering.xml").contains(part)) {
                    assertEquals(infoset(XmlOwnerReader.parse(oldParts.get(part)).getDocumentElement()),
                            infoset(XmlOwnerReader.parse(newParts.get(part)).getDocumentElement()), name + ": " + part);
                } else {
                    assertArrayEquals(oldParts.get(part), newParts.get(part), name + ": " + part);
                }
            }
        }
        assertFalse(Files.exists(generated.resolve("candidate.json")));
        assertFalse(Files.exists(generated.resolve("word-observations.json")));
        return actual.get("cases").size();
    }

    @Test
    void allFourteenInputsRetainPinnedSemanticsAndLeaveSavedEvidenceUntouched() throws IOException {
        final var before = sourceHashes();
        final Path output = Path.of("docweave-validation/target/word-fixtures/run-" + UUID.randomUUID());
        final var generator = WordFixtureGenerator.create();
        generator.followups(REVIEW.resolve("word-style-cases"), output.resolve("followups"));
        generator.outlineControls(REVIEW.resolve("word-style-followups"), output.resolve("controls"));
        assertEquals(10, comparePacket(REVIEW.resolve("word-style-followups"), output.resolve("followups")));
        assertEquals(4, comparePacket(REVIEW.resolve("word-outline-controls"), output.resolve("controls")));
        assertEquals(before, sourceHashes());
        CorpusReview.write(output.resolve("summary.json"),
                Map.of("kind", "Owned fixture semantic reproduction; not Word observations", "cases", 14,
                        "saved_sources_unchanged", true, "xml_infosets_equal", true, "other_part_bytes_equal", true,
                        "validation_sources", ValidationProvenance.sources(), "java_version",
                        Runtime.version().toString()));
    }

    @Test
    void repeatedGenerationIsDeterministicAndExistingOutputsAreNeverOverwritten() throws IOException {
        final var generator = WordFixtureGenerator.create();
        final Path first = temporary.resolve("first");
        final Path second = temporary.resolve("second");
        generator.followups(REVIEW.resolve("word-style-cases"), first);
        generator.followups(REVIEW.resolve("word-style-cases"), second);
        try (final var paths = Files.list(first)) {
            for (final var path : paths.toList()) {
                assertArrayEquals(Files.readAllBytes(path), Files.readAllBytes(second.resolve(path.getFileName())));
            }
        }
        final byte[] manifest = Files.readAllBytes(first.resolve("manifest.json"));
        assertThrows(IOException.class, () -> generator.followups(REVIEW.resolve("word-style-cases"), first));
        assertArrayEquals(manifest, Files.readAllBytes(first.resolve("manifest.json")));
        final Path controls = temporary.resolve("controls");
        generator.outlineControls(first, controls);
        assertThrows(IOException.class, () -> generator.outlineControls(first, controls));
    }

    @Test
    void orderingPairsOnlyChangePropertyOrderAndAnchorPrefix() throws IOException {
        for (final String prefix : List.of("numbering", "override")) {
            for (final String part : List.of("document", "styles", "numbering")) {
                final var before = XmlOwnerReader.document(
                        REVIEW.resolve("word-style-followups/" + prefix + "-original-order.docx"),
                        "word/" + part + ".xml");
                final var after =
                        XmlOwnerReader.document(REVIEW.resolve("word-style-followups/" + prefix + "-numpr-first.docx"),
                                "word/" + part + ".xml");
                for (final var text : FixturePackage.descendants(before, "t")) {
                    text.setTextContent(text.getTextContent().replace(prefix + "-original-order", "ANCHOR"));
                }
                for (final var text : FixturePackage.descendants(after, "t")) {
                    text.setTextContent(text.getTextContent().replace(prefix + "-numpr-first", "ANCHOR"));
                }
                for (final var condition : FixturePackage.descendants(before, "tblStylePr")) {
                    final var properties = FixturePackage.child(condition, "pPr");
                    final var num = FixturePackage.child(properties, "numPr");
                    final var outline = FixturePackage.child(properties, "outlineLvl");
                    assertSame(outline, properties.getFirstChild());
                    properties.insertBefore(num, outline);
                }
                for (final var condition : FixturePackage.descendants(after, "tblStylePr")) {
                    assertEquals("numPr", FixturePackage.child(condition, "pPr").getFirstChild().getLocalName());
                }
                assertEquals(infoset(before.getDocumentElement()), infoset(after.getDocumentElement()));
            }
        }
    }

    @Test
    void directBodyOutlineControlsAndHeadingControlsKeepTheirIntendedValues() throws IOException {
        for (final String name : List.of("conditional-outline-only", "conditional-direct-body")) {
            final var styles = XmlOwnerReader.document(
                    REVIEW.resolve("word-style-followups/" + name + ".docx"), "word/styles.xml");
            final var document = XmlOwnerReader.document(REVIEW.resolve("word-style-followups/" + name + ".docx"));
            assertTrue(FixturePackage.descendants(styles, "numPr").isEmpty());
            assertTrue(FixturePackage.descendants(document, "numPr").isEmpty());
            final var outlines = FixturePackage.descendants(document, "outlineLvl");
            assertEquals(name.endsWith("direct-body") ? 9 : 0, outlines.size());
            outlines.forEach(node -> assertEquals("9", node.getAttributeNS(W, "val")));
        }
        assertTrue(FixturePackage
                        .descendants(
                                XmlOwnerReader.document(REVIEW.resolve("word-style-followups/plain-direct-body.docx")),
                                "tblStyle")
                        .isEmpty());
        final var manifest = JSON.readTree(Files.readAllBytes(REVIEW.resolve("word-outline-controls/manifest.json")));
        for (final var contract : manifest.get("cases")) {
            final var outlines = FixturePackage.descendants(
                    XmlOwnerReader.document(
                            REVIEW.resolve("word-outline-controls").resolve(contract.get("file").asString())),
                    "outlineLvl");
            assertEquals(9, outlines.size());
            outlines.forEach(node
                    -> assertEquals(contract.get("direct_outline_level").asInt(),
                            Integer.parseInt(node.getAttributeNS(W, "val"))));
        }
    }

    @Test
    void numberingVariantsIsolateAbstractDefinitionRestartAndParagraphSource() throws IOException {
        final var distinct = XmlOwnerReader.document(
                REVIEW.resolve("word-style-followups/numbering-distinct-abstract.docx"), "word/numbering.xml");
        assertEquals(2, FixturePackage.descendants(distinct, "abstractNum").size());
        final var second = FixturePackage.descendants(distinct, "num")
                                   .stream()
                                   .filter(node -> "2".equals(node.getAttributeNS(W, "numId")))
                                   .findFirst()
                                   .orElseThrow();
        assertEquals("1", FixturePackage.child(second, "abstractNumId").getAttributeNS(W, "val"));
        final var restart = XmlOwnerReader.document(
                REVIEW.resolve("word-style-followups/numbering-explicit-restart.docx"), "word/numbering.xml");
        assertEquals("1", FixturePackage.descendants(restart, "startOverride").getFirst().getAttributeNS(W, "val"));
        final Path direct = REVIEW.resolve("word-style-followups/direct-numbering-shared.docx");
        assertTrue(FixturePackage.descendants(XmlOwnerReader.document(direct, "word/styles.xml"), "numPr").isEmpty());
        assertEquals(List.of("2", "2", "2", "1", "1", "1", "1", "1", "1"),
                FixturePackage.descendants(XmlOwnerReader.document(direct), "numId")
                        .stream()
                        .map(node -> node.getAttributeNS(W, "val"))
                        .toList());
    }

    @Test
    void changedOrMissingSeedsRejectBeforeCreatingDestination() throws IOException {
        final Path seeds = temporary.resolve("seeds");
        Files.createDirectory(seeds);
        Files.copy(REVIEW.resolve("word-style-cases/manifest.json"), seeds.resolve("manifest.json"));
        Files.writeString(seeds.resolve("table-style-numbering.docx"), "changed seed");
        final Path output = temporary.resolve("rejected");
        assertThrows(IOException.class, () -> WordFixtureGenerator.create().followups(seeds, output));
        assertFalse(Files.exists(output));
        final var manifest = (ObjectNode) JSON.readTree(Files.readAllBytes(seeds.resolve("manifest.json")));
        manifest.putArray("cases");
        Files.writeString(seeds.resolve("manifest.json"), JSON.writeValueAsString(manifest));
        assertThrows(IOException.class, () -> WordFixtureGenerator.create().followups(seeds, output));
        assertFalse(Files.exists(output));
    }

    @Test
    void repinnedMalformedControlGeometryAndMissingOutlinesRejectBeforeOutput() throws IOException {
        for (final boolean removeCell : List.of(true, false)) {
            final Path seeds = temporary.resolve("malformed-" + removeCell);
            Files.createDirectory(seeds);
            for (final String name : List.of("plain-direct-body", "conditional-direct-body")) {
                Files.copy(REVIEW.resolve("word-style-followups/" + name + ".docx"), seeds.resolve(name + ".docx"));
            }
            final Path altered = seeds.resolve("plain-direct-body.docx");
            final var packet = new FixturePackage(Files.readAllBytes(altered));
            final var document = packet.xml("word/document.xml");
            final var nodes = FixturePackage.descendants(document, removeCell ? "tc" : "outlineLvl");
            final var removed = nodes.getLast();
            removed.getParentNode().removeChild(removed);
            packet.replace("word/document.xml", document);
            Files.write(altered, packet.bytes());
            final var manifest =
                    JSON.readTree(Files.readAllBytes(REVIEW.resolve("word-style-followups/manifest.json")));
            for (final var entry : manifest.get("cases")) {
                if (entry.get("file").asString().equals("plain-direct-body.docx")) {
                    // Deliberate TEST ONLY repin exercises structural validation beyond the hash guard.
                    ((ObjectNode) entry).put("sha256", DocxOwnership.sha256(Files.readAllBytes(altered)));
                }
            }
            Files.writeString(seeds.resolve("manifest.json"), JSON.writeValueAsString(manifest));
            final Path output = temporary.resolve("malformed-output-" + removeCell);
            assertThrows(IOException.class, () -> WordFixtureGenerator.create().outlineControls(seeds, output));
            assertFalse(Files.exists(output));
        }
    }

    @Test
    void optionalGenerationRequiresAllPathsAndCreatesOnlyFreshInputPackets() throws IOException {
        final String source = System.getProperty("docweave.fixtures.source");
        final String output = System.getProperty("docweave.fixtures.output");
        final String kind = System.getProperty("docweave.fixtures.kind");
        if (source == null && output == null && kind == null) {
            return;
        }
        assertNotNull(source);
        assertNotNull(output);
        assertNotNull(kind);
        assertTrue(List.of("followups", "outline-controls").contains(kind));
        assertFalse(Files.exists(Path.of(output)), "Fixture output must be a fresh directory");
        if (kind.equals("followups")) {
            WordFixtureGenerator.create().followups(Path.of(source), Path.of(output));
        } else {
            WordFixtureGenerator.create().outlineControls(Path.of(source), Path.of(output));
        }
    }
}
