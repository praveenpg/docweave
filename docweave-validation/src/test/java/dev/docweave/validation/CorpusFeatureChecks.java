package dev.docweave.validation;

import dev.docweave.DocWeave;
import dev.docweave.core.Element;
import dev.docweave.partition.DocumentSource;
import dev.docweave.partition.DocxOptions;
import dev.docweave.partition.NestedTableOptions;
import dev.docweave.partition.PartitionOptions;
import dev.docweave.partition.PartitionResult;
import org.w3c.dom.Document;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Additional opt-in corpus checks; no rendering, glyph, cell-order or general revision-state oracle. */
final class CorpusFeatureChecks {
    /** Prevents accidental construction of a stateless development-only utility. */
    private CorpusFeatureChecks() {
    }

    /** Validates bounded manifest counts rather than coercing strings, booleans or overflowing numbers. */
    static int count(final JsonNode contract, final String field, final int minimum, final int maximum)
            throws IOException {
        final var node = contract.get(field);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt() || node.asInt() < minimum
                || node.asInt() > maximum) {
            throw new IOException("Invalid corpus feature count: " + field);
        }
        return node.asInt();
    }

    /** Checks explicitly reviewed positive source counts before any candidate extraction. */
    static void source(final DocxCorpusSource.Snapshot snapshot, final JsonNode contract) throws IOException {
        if (!contract.has("required_source_features")) {
            return;
        }
        final var features = contract.get("required_source_features");
        if (!features.isObject()) {
            throw new IOException("Corpus source features must be an object");
        }
        for (final var property : features.properties()) {
            final String name = property.getKey();
            final int expected = count(features, name, 1, 1000000);
            final int observed = name.equals("maximum_table_depth") ? snapshot.maximumTableDepth()
                                                                    : snapshot.body().getOrDefault(name, 0);
            if (observed != expected) {
                throw new IOException("Corpus source feature differs from manifest: " + name);
            }
        }
    }

    /**
     * Selects distinct normalized main-body delText fragments of at least 24 code points that do not
     * occur in accepted source owners. Text-box and ambiguous repeated/short fragments are excluded.
     * Selected text stays in memory; serialized diagnostics contain only counts and SHA-256 values.
     * @param document independent bounded source DOM
     * @param owners accepted body owners from the independent ownership oracle
     * @return immutable qualifying fragments; absence is a limited negative-content check
     */
    static List<String> deletedFragments(final Document document, final List<DocxOwnership.Owner> owners) {
        final String accepted = DocxOwnership.normalize(
                String.join(" ", owners.stream()
                                         .filter(owner -> owner.story() == DocxOwnership.Story.BODY)
                                         .flatMap(owner -> owner.paragraphs().stream())
                                         .toList()));
        final var selected = new TreeSet<String>();
        final var nodes = document.getElementsByTagNameNS(XmlOwnerReader.WORD, "delText");
        for (int index = 0; index < nodes.getLength(); index++) {
            final var node = nodes.item(index);
            boolean deleted = false;
            boolean body = false;
            for (var parent = node.getParentNode(); parent != null; parent = parent.getParentNode()) {
                if (!XmlOwnerReader.WORD.equals(parent.getNamespaceURI())) {
                    continue;
                }
                if ("txbxContent".equals(parent.getLocalName())) {
                    break;
                }
                deleted |= List.of("del", "moveFrom").contains(parent.getLocalName());
                if ("body".equals(parent.getLocalName())) {
                    body = true;
                    break;
                }
            }
            final String text = DocxOwnership.normalize(node.getTextContent());
            if (body && deleted && text.codePointCount(0, text.length()) >= 24 && !accepted.contains(text)) {
                selected.add(text);
            }
        }
        return List.copyOf(selected);
    }

    /** Checks only body output; accepted stored headers/footers cannot cause a spurious deleted-text hit. */
    static Map<String, Object> deletedText(
            final List<String> fragments, final List<Element> elements, final int minimum) {
        final String body = DocxOwnership.normalize(String.join(
                " ", elements.stream()
                             .filter(element -> element.metadata().getOrDefault("docx_part", "body").equals("body"))
                             .map(Element::text)
                             .toList()));
        final var unexpected = fragments.stream().filter(body::contains).map(DocxOwnership::textHash).toList();
        return Map.of("scope",
                "Distinct main-body deleted fragments >=24 code points, absent from accepted source; not full revision fidelity",
                "fragments_checked", fragments.size(), "unexpected_fragment_sha256", unexpected, "checks_passed",
                fragments.size() >= minimum && unexpected.isEmpty());
    }

    /**
     * Uses the public opt-in mode and checks root identity/text/metadata/warnings plus flat descriptor count.
     * Descriptor schema/range constraints are enforced independently by existing model/owned tests.
     * @param input pinned defensive byte snapshot shared with the baseline
     * @param options same document policy used for the baseline, before adding preservation
     * @param baseline genuine default-flatten extraction
     * @param expectedDescriptors independently reviewed source count, scoped to this corpus contract
     * @return aggregate comparison only; no raw table text or metadata payload is serialized
     * @throws IOException on real preservation extraction failure
     */
    static Map<String, Object> preservation(final CorpusReview.Input input, final DocxOptions options,
            final PartitionResult baseline, final int expectedDescriptors) throws IOException {
        final var preserved = DocWeave.create().partition(DocumentSource.bytes(input.bytes(), input.filename()),
                PartitionOptions.builder()
                        .docxOptions(options)
                        .nestedTableOptions(NestedTableOptions.preserveMetadata())
                        .build());
        int descriptors = 0;
        final var roots = new ArrayList<Element>();
        for (final var element : preserved.elements()) {
            final var metadata = new LinkedHashMap<>(element.metadata());
            final var nested = metadata.remove("nested_tables");
            if (nested instanceof List<?> values) {
                descriptors += values.size();
            }
            metadata.remove("nested_tables_version");
            roots.add(new Element(element.elementId(), element.type(), element.text(), metadata));
        }
        final boolean rootsEqual = baseline.elements().equals(roots);
        final boolean warningsEqual = baseline.warnings().equals(preserved.warnings());
        return Map.of("root_elements_equal", rootsEqual, "warnings_equal", warningsEqual, "nested_descriptors",
                descriptors, "expected_nested_descriptors", expectedDescriptors, "checks_passed",
                rootsEqual && warningsEqual && descriptors == expectedDescriptors);
    }
}
