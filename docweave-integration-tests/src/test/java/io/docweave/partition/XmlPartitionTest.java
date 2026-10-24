package io.docweave.partition;

import io.docweave.DocWeave;
import io.docweave.batch.BatchProcessor;
import io.docweave.core.Element;
import io.docweave.core.ElementType;
import io.docweave.json.ElementJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class XmlPartitionTest {
    private final DocWeave processor = DocWeave.create();

    private PartitionResult parse(final String xml) throws Exception {
        return processor.partition(DocumentSource.text(xml, "document.xml"));
    }

    private List<String> texts(final PartitionResult result) {
        return result.elements().stream().map(Element::text).toList();
    }

    private void failure(final String xml, final PartitionOptions options, final PartitionException.Code code) {
        assertEquals(code, assertThrows(PartitionException.class,
                                   () -> processor.partition(DocumentSource.text(xml, "document.xml"), options))
                                   .code());
    }

    private PartitionOptions policy(final XmlOptions options) {
        return PartitionOptions.builder().xmlOptions(options).build();
    }

    @Test
    void mixedContentPreservesOrderWithoutDescendantDuplication() throws Exception {
        final var result = parse("<root>Before <b>bold <i>inner</i> tail</b> after<empty/> final</root>");
        assertEquals(List.of("Before", "bold", "inner", "tail", "after", "final"), texts(result));
        assertEquals(List.of("root", "b", "i"), result.elements().get(2).metadata().get("xml_path"));
        assertEquals(2, result.elements().get(2).metadata().get("category_depth"));
        assertTrue(result.elements().stream().allMatch(e -> e.type() == ElementType.UncategorizedText));
        assertEquals("application/xml", result.elements().getFirst().metadata().get("filetype"));
    }

    @Test
    void namespacesUseExpandedNamesAndPreserveAttributeValues() throws Exception {
        final var result =
                parse("<r xmlns='urn:root' xmlns:p='urn:props'><p:item p:id='007' id='plain'>🧵 café</p:item></r>");
        final var metadata = result.elements().getFirst().metadata();
        assertEquals(List.of("{urn:root}r", "{urn:props}item"), metadata.get("xml_path"));
        assertEquals(Map.of("{urn:props}id", "007", "id", "plain"), metadata.get("xml_attributes"));
        assertEquals("🧵 café", result.elements().getFirst().text());
    }

    @Test
    void cdataEntitiesCommentsAndProcessingInstructionsRemainLiteralContent() throws Exception {
        assertEquals(List.of("a<b>&c & 🧵 end"),
                texts(parse("<r><![CDATA[a<b>&c]]> &amp; &#x1F9F5;<!-- ignored --><?unused value?> end</r>")));
        // XInclude is ordinary markup, never resolved.
        assertTrue(parse("<r xmlns:x='http://www.w3.org/2001/XInclude'><x:include href='file:///missing'/></r>")
                        .elements()
                        .isEmpty());
    }

    @Test
    void whitespaceDefaultsAndXmlSpaceInheritanceAreExplicit() throws Exception {
        assertEquals(List.of("one two"), texts(parse("<r>  one\n two  </r>")));
        assertEquals(List.of("  one\n two  "),
                texts(processor.partition(DocumentSource.text("<r>  one\n two  </r>", "r.xml"),
                        policy(new XmlOptions(20, 100, true, false)))));
        assertEquals(List.of("  a  ", "  b  ", "c d", "  e  "),
                texts(parse("<r xml:space='preserve'>  a  <b>  b  </b><c xml:space='default'> c\n d </c>  e  </r>")));
        assertTrue(parse("<r xml:space='preserve'>  \n </r>").elements().isEmpty());
    }

    @Test
    void attributesCanBeOmitted() throws Exception {
        final var result = processor.partition(
                DocumentSource.text("<r id='1'>value</r>", "r.xml"), policy(new XmlOptions(20, 100, false, false)));
        assertFalse(result.elements().getFirst().metadata().containsKey("xml_attributes"));
    }

    @Test
    void rejectsDtdInternalEntitiesAndExpansion() {
        for (final String xml : List.of("<!DOCTYPE r><r/>", "<!DOCTYPE r [<!ENTITY e 'secret'>]><r>&e;</r>",
                     "<!DOCTYPE r [<!ENTITY a 'text'><!ENTITY b '&a;&a;'>]><r>&b;</r>")) {
            failure(xml, PartitionOptions.defaults(), PartitionException.Code.UNSAFE_XML);
        }
    }

    @Test
    void externalFileAndNetworkDeclarationsAreRejected(final @TempDir Path dir) throws Exception {
        final Path secret = dir.resolve("secret.txt");
        Files.writeString(secret, "must not appear");
        for (final String target : List.of(secret.toUri().toString(), "http://127.0.0.1:9/forbidden")) {
            failure("<!DOCTYPE r SYSTEM '" + target + "'><r>data</r>", PartitionOptions.defaults(),
                    PartitionException.Code.UNSAFE_XML);
            failure("<!DOCTYPE r [<!ENTITY e SYSTEM '" + target + "'>]><r>&e;</r>", PartitionOptions.defaults(),
                    PartitionException.Code.UNSAFE_XML);
            failure("<!DOCTYPE r [<!ENTITY % e SYSTEM '" + target + "'>%e;]><r/>", PartitionOptions.defaults(),
                    PartitionException.Code.UNSAFE_XML);
        }
    }

    @Test
    void malformedXmlReturnsSanitizedTypedFailure() {
        for (final String xml : List.of("", "<r>", "<r/><s/>", "<r>&unknown;</r>", "<r secret='PRIVATE'><s></r>",
                     "<r a='1' a='2'/>", "<p:r/>")) {
            final var error = assertThrows(PartitionException.class, () -> parse(xml));
            assertEquals(PartitionException.Code.MALFORMED_DOCUMENT, error.code());
            assertFalse(error.getMessage().contains("PRIVATE"));
        }
    }

    @Test
    void depthNodesAndEmissionsHaveIndependentLimits() throws Exception {
        assertEquals(List.of("ok"), texts(processor.partition(DocumentSource.text("<r><s>ok</s></r>", "r.xml"),
                                            policy(new XmlOptions(2, 2, false, true)))));
        failure("<r><s><t/></s></r>", policy(new XmlOptions(2, 10, false, true)),
                PartitionException.Code.XML_DEPTH_LIMIT);
        failure("<r><s/><t/></r>", policy(new XmlOptions(3, 2, false, true)), PartitionException.Code.XML_NODE_LIMIT);
        failure("<r><s>one</s><s>two</s></r>", PartitionOptions.builder().maxElements(1).build(),
                PartitionException.Code.ELEMENT_LIMIT);
    }

    @Test
    void declarationAndExplicitRoutingAndFilenameOverrideWork() throws Exception {
        assertEquals(DocumentFormat.XML,
                processor.partition(DocumentSource.text("<?xml version='1.0'?><r>value</r>")).format());
        assertEquals(DocumentFormat.XML, parse("<p>XML even with an HTML tag</p>").format());
        assertEquals(DocumentFormat.XML, processor
                                                 .partition(DocumentSource.text("<r>explicit</r>"),
                                                         PartitionOptions.builder().format(DocumentFormat.XML).build())
                                                 .format());
        assertEquals(DocumentFormat.TXT, processor.partition(DocumentSource.text("<r>ambiguous</r>")).format());
    }

    @Test
    void declarationEncodingAndBomAndUtf16SignaturesDecodeCorrectly() throws Exception {
        for (final String encoding : List.of("ISO-8859-1", "UTF-16LE", "UTF-16BE", "UTF-32LE", "UTF-32BE")) {
            final String xml = "<?xml version='1.0' encoding='" + encoding + "'?><r>café</r>";
            final var result = processor.partition(DocumentSource.bytes(xml.getBytes(Charset.forName(encoding)), null));
            assertEquals(DocumentFormat.XML, result.format());
            assertEquals(List.of("café"), texts(result));
        }
        assertEquals(List.of("🧵"), texts(processor.partition(DocumentSource.bytes(
                                            "\ufeff<r>🧵</r>".getBytes(StandardCharsets.UTF_8), "r.xml"))));
    }

    @Test
    void validatesOptions() {
        assertThrows(IllegalArgumentException.class, () -> new XmlOptions(0, 1, false, true));
        assertThrows(IllegalArgumentException.class, () -> new XmlOptions(257, 1, false, true));
        assertThrows(IllegalArgumentException.class, () -> new XmlOptions(1, 0, false, true));
        assertThrows(NullPointerException.class, () -> PartitionOptions.builder().xmlOptions(null).build());
    }

    @Test
    void idsAndMetadataRoundTripAreStable() throws Exception {
        final var elements = parse("<r id='001'><s>🧵</s><s>again</s></r>").elements();
        assertEquals(elements, parse("<r id='001'><s>🧵</s><s>again</s></r>").elements());
        assertEquals(elements, ElementJson.create().read(ElementJson.create().write(elements)));
    }

    @Test
    void interruptedCallPropagatesCancellation() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(java.io.InterruptedIOException.class, () -> parse("<r>value</r>"));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void reusableStrategySupportsVirtualThreadBatches() throws Exception {
        final var inputs = IntStream.range(0, 30).mapToObj(i -> "<r><s>value " + i + "</s></r>").toList();
        final var results = BatchProcessor.create().mapOrdered(inputs, 4, this::parse);
        for (int i = 0; i < inputs.size(); i++) {
            assertEquals(List.of("value " + i), texts(results.get(i)));
        }
    }
}
