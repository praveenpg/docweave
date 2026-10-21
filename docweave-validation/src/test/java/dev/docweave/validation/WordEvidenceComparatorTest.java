package dev.docweave.validation;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Historical comparisons and deliberately synthetic mutation tests; no Word measurements are generated here. */
class WordEvidenceComparatorTest {
    private static final Path REVIEW = Path.of("benchmarks/review");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final WordEvidenceComparator comparator = WordEvidenceComparator.create();

    private record Fixture(JsonNode manifest, JsonNode candidate, JsonNode observations, String hash) {}

    private Fixture synthetic() throws IOException {
        final Path packet = REVIEW.resolve("word-style-cases");
        final byte[] manifestBytes = Files.readAllBytes(packet.resolve("manifest.json"));
        final var manifest = JSON.readTree(manifestBytes);
        final var candidate = JSON.readTree(Files.readAllBytes(packet.resolve("candidate.json")));
        final var observations = JSON.createObjectNode();
        final String hash = DocxOwnership.sha256(manifestBytes);
        observations.put("manifest_sha256", hash);
        observations.put("word_version", "TEST ONLY");
        observations.put("word_build", "TEST ONLY");
        observations.put("platform", "synthetic mutation tests, not Word evidence");
        final var cases = observations.putArray("cases");
        for (final var source : candidate.get("cases")) {
            final var item = cases.addObject();
            item.put("file", source.get("file").asString());
            item.put("sha256", source.get("sha256").asString());
            item.set("cells", JSON.valueToTree(comparator.candidateCells(source)
                                              .stream()
                                              .map(WordEvidenceComparator.Cell::json)
                                              .toList()));
        }
        return new Fixture(manifest, candidate, observations, hash);
    }

    private WordEvidenceComparator.Comparison compare(final Fixture fixture) throws IOException {
        return comparator.compare(fixture.manifest(), fixture.candidate(), fixture.observations(), fixture.hash());
    }

    private static ObjectNode firstCell(final Fixture fixture) {
        return (ObjectNode) fixture.observations().get("cases").get(0).get("cells").get(0);
    }

    @Test
    void historicalDifferencesExactlyMatchBothSavedPythonComparisons() throws IOException {
        for (final String name : List.of("word-style-cases", "word-style-followups")) {
            final var packet = REVIEW.resolve(name);
            final byte[] manifest = Files.readAllBytes(packet.resolve("manifest.json"));
            final String observation =
                    name.equals("word-style-cases") ? "word-repeat-observations.json" : "word-observations.json";
            final var comparison = comparator.compare(JSON.readTree(manifest),
                    JSON.readTree(Files.readAllBytes(packet.resolve("candidate.json"))),
                    JSON.readTree(Files.readAllBytes(packet.resolve(observation))), DocxOwnership.sha256(manifest));
            assertTrue(comparison.differenceCount() > 0, "Historical candidates deliberately predate measured fixes");
            assertEquals(JSON.readTree(Files.readAllBytes(packet.resolve("word-comparison.json"))),
                    JSON.valueToTree(comparison.json()));
        }
    }

    @Test
    void matchesAndDifferencesAlwaysRemainPendingHumanReview() throws IOException {
        final var fixture = synthetic();
        final var match = compare(fixture);
        assertEquals(0, match.differenceCount());
        assertEquals(WordEvidenceComparator.STATUS, match.json().get("status"));
        final var cell = firstCell(fixture);
        cell.put("outline_level", (cell.get("outline_level").asInt() + 1) % 10);
        final var difference = compare(fixture);
        assertEquals(1, difference.differenceCount());
        assertEquals(WordEvidenceComparator.STATUS, difference.json().get("status"));
        assertFalse(difference.json().containsKey("passed"));
        assertFalse(difference.json().containsKey("approved"));
        assertThrows(UnsupportedOperationException.class, () -> match.cases().clear());
        assertThrows(UnsupportedOperationException.class, () -> difference.cases().getFirst().differences().clear());
    }

    @Test
    void manifestIdentityCaseHashesAndApplicationMetadataAreRequired() throws IOException {
        for (final String field : List.of("manifest_sha256", "word_version", "word_build", "platform")) {
            final var fixture = synthetic();
            ((ObjectNode) fixture.observations()).put(field, "");
            assertThrows(IOException.class, () -> compare(fixture), field);
        }
        final var fixture = synthetic();
        ((ObjectNode) fixture.observations().get("cases").get(0)).put("sha256", "0".repeat(64));
        assertThrows(IOException.class, () -> compare(fixture));
        final var badCandidate = synthetic();
        ((ObjectNode) badCandidate.candidate().get("cases").get(0)).put("sha256", "0".repeat(64));
        assertThrows(IOException.class, () -> compare(badCandidate));
    }

    @Test
    void missingDuplicateAndUnexpectedCasesAreRejectedForEveryInputRole() throws IOException {
        for (final String role : List.of("manifest", "candidate", "observations")) {
            for (final String mode : List.of("missing", "duplicate", "unexpected")) {
                final var fixture = synthetic();
                final var root = switch (role) {
                    case "manifest" -> fixture.manifest();
                    case "candidate" -> fixture.candidate();
                    default -> fixture.observations();
                };
                final var cases = (ArrayNode) root.get("cases");
                if (mode.equals("missing")) {
                    cases.remove(cases.size() - 1);
                } else if (mode.equals("duplicate")) {
                    cases.set(cases.size() - 1, cases.get(0).deepCopy());
                } else {
                    ((ObjectNode) cases.get(cases.size() - 1)).put("file", "unexpected.docx");
                }
                assertThrows(IOException.class, () -> compare(fixture), role + ":" + mode);
            }
        }
    }

    @Test
    void missingDuplicateOutOfRangeAndNonIntegralCoordinatesReject() throws IOException {
        final List<Consumer<ArrayNode>> mutations = List.of(cells
                -> cells.remove(cells.size() - 1),
                cells
                -> cells.set(cells.size() - 1, cells.get(0).deepCopy()),
                cells
                -> ((ObjectNode) cells.get(0)).put("row", true),
                cells
                -> ((ObjectNode) cells.get(0)).put("column", 0.5),
                cells
                -> ((ObjectNode) cells.get(0)).put("row", -1),
                cells -> ((ObjectNode) cells.get(0)).put("column", 3));
        for (final var mutation : mutations) {
            final var fixture = synthetic();
            mutation.accept((ArrayNode) fixture.observations().get("cases").get(0).get("cells"));
            assertThrows(IOException.class, () -> compare(fixture));
        }
    }

    @Test
    void invalidPropertyValuesAndWrongAnchorsReject() throws IOException {
        final List<Consumer<ObjectNode>> mutations = List.of(cell
                -> cell.put("outline_level", 10),
                cell
                -> cell.put("outline_level", true),
                cell
                -> cell.put("outline_level", 0.5),
                cell
                -> cell.put("outline_level", Long.MAX_VALUE),
                cell
                -> cell.putNull("list_string"),
                cell -> cell.put("list_string", 1), cell -> cell.put("text", "wrong anchor"));
        for (final var mutation : mutations) {
            final var fixture = synthetic();
            mutation.accept(firstCell(fixture));
            assertThrows(IOException.class, () -> compare(fixture));
        }
    }

    @Test
    void numberingAndBodyOutlineControlsRemainObservable() throws IOException {
        final var fixture = synthetic();
        for (final var candidate : fixture.candidate().get("cases")) {
            final var cells = comparator.candidateCells(candidate);
            if (candidate.get("file").asString().equals("table-style-numbering.docx")) {
                assertTrue(cells.stream().allMatch(cell -> !cell.listString().isEmpty()));
            } else if (candidate.get("file").asString().equals("direct-override-control.docx")) {
                assertTrue(cells.stream().allMatch(cell -> cell.outlineLevel() == 9 && cell.listString().isEmpty()));
            }
        }
    }

    @Test
    void codePointOffsetsHandleSupplementaryPrefixesAndRejectAmbiguousAnchors() throws IOException {
        final var candidate = JSON.createObjectNode();
        candidate.put("file", "unicode😀.docx");
        final var table = candidate.putArray("elements").addObject();
        table.put("type", "Table");
        final var values = new java.util.ArrayList<String>();
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                values.add("unicode😀-r" + row + "c" + column);
            }
        }
        final String text = "🧵 " + String.join("\t", values);
        table.put("text", text);
        final var range = table.putObject("metadata").putArray("docx_table_style_paragraphs").addObject();
        range.put("start_index", 2);
        range.put("end_index", 2 + values.getFirst().codePointCount(0, values.getFirst().length()));
        range.put("outline_level", 2);
        assertEquals(2, comparator.candidateCells(candidate).getFirst().outlineLevel());
        assertEquals(9, comparator.candidateCells(candidate).getLast().outlineLevel());
        table.put("text", text + "\t" + values.getFirst());
        assertThrows(IOException.class, () -> comparator.candidateCells(candidate));
    }

    @Test
    void controlledInstrumentRejectsUnsupportedGridShapeMissingTablesAndInvalidRanges() throws IOException {
        final var fixture = synthetic();
        ((ObjectNode) fixture.manifest().get("cases").get(0)).put("rows", 4);
        assertThrows(IOException.class, () -> compare(fixture));
        final var missingTable = synthetic().candidate().get("cases").get(0);
        ((ObjectNode) missingTable).putArray("elements");
        assertThrows(IOException.class, () -> comparator.candidateCells(missingTable));
        final var badRange = synthetic().candidate().get("cases").get(0);
        final var table = badRange.get("elements").get(0);
        final var range = ((ObjectNode) table.get("metadata")).putArray("docx_table_style_paragraphs").addObject();
        range.put("start_index", -1);
        range.put("end_index", 1);
        assertThrows(IOException.class, () -> comparator.candidateCells(badRange));
    }

    @Test
    void portableFilenamesRejectTraversalAndWindowsDriveSyntax() throws IOException {
        for (final String name :
                List.of("../outside.docx", "..\\outside.docx", "C:outside.docx", ".docx", "wrong.txt")) {
            final var candidate = synthetic().candidate().get("cases").get(0);
            ((ObjectNode) candidate).put("file", name);
            assertThrows(IOException.class, () -> comparator.candidateCells(candidate));
        }
    }
}
