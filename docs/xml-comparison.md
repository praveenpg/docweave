# Native XML reference comparisons

Five public `partition_xml` executions at upstream commit
`347fbbfd3e84e228017f125e5c5dbdc326ef7de2` cover three owned synthetic XML inputs. The retained
references use Python 3.13.16 and the recorded lxml/NLP dependency set. The generator verifies
15 selected implementation hashes and the pinned English model wheel/installed package bytes
before import. This verifies selected files, not the complete upstream tree or all dependencies.
No parser/classifier is patched or mocked during reference generation.

Inputs are supplied through BytesIO with explicit metadata filename and `languages=["eng"]`.
The entity fixture declares one short local string and has no external target, recursive
expansion, XInclude resolution or network request. This comparison does not test arbitrary entity
handling or change Java's rejection policy.

| Case | Upstream result | Java contract |
| --- | --- | --- |
| structured-default | Five Title elements; child text precedes parent text and the trailing “after.” is absent | Six UncategorizedText runs, in source order, with paths/attributes and preserved CDATA whitespace. |
| structured-keep-tags | One UncategorizedText element containing the complete original XML text | Java has no raw-tag-return option; default XML extraction emits decoded text runs. |
| structured-xpath | One Title containing Café & tea | Java has no XPath-selection option; default extraction traverses the complete document. |
| mixed-order | inside 🧵, then Before with trailing space | Java emits Before, inside 🧵, after., End. in source order. |
| internal-entity-policy | No elements on this bounded, non-resolving path | Java rejects the DTD with UNSAFE_XML. |

## Ordered content and metadata

The upstream end-event iterator yields an element's leading `.text` after its children and does
not emit child tail strings in these fixtures. Java flushes text at start/end boundaries, keeping
mixed content in source order without duplicating descendants. The comparison records reordered
or omitted strings rather than normalizing the two streams into an artificial match.

Java uses UncategorizedText for generic XML because arbitrary vocabularies do not imply universal
heading/paragraph semantics. The retained upstream NLP classifications are Title for the default
structured fixture. Shared Unicode, CDATA code text and the Café & tea item text are checked
separately from category/order differences. Source MIME/filename metadata and JSON round trips
remain covered.

Java emits namespace-expanded xml_tag/xml_path values and element-local xml_attributes, preserving
the item identifier 007. These are not present on the upstream output. Java's xml_path metadata is
a source-location list, unrelated to upstream's XPath-selection argument. The mixed fixture also
checks explicit whitespace preservation and attribute omission. xml:space="preserve" retains the
code block's whitespace even under Java defaults.

## Option and entity boundaries

Raw-tag return and XPath selection are unsupported Java modes, recorded here as boundaries, not
new options promised by the comparison. Callers needing vocabulary-specific extraction can
replace the XML strategy through PartitionerRegistry. Java's generic extraction continues to
forbid DTDs and custom/external entities; the entity reference case never weakens that contract.
Existing local XML tests independently cover malformed input, external declarations, depth/node
limits, encoding and cancellation; this reference set does not establish parity for those cases.

## Repeat and scope

```sh
python benchmarks/tools/generate_xml_references.py \
  --upstream /path/to/pinned/unstructured \
  --model-wheel /path/to/en_core_web_sm-3.8.0-py3-none-any.whl \
  --output /tmp/xml-native-reference.json
```

Use supported Python 3.11–3.13 with recorded dependencies. Review before replacing checked-in
references. `xml-native-reference.json` retains full upstream outputs, input hashes, exact options
and model/source/dependency provenance. Four Java comparison methods cover the five runs entirely
offline. No production-library changes were required.

The initial synthetic native-reference pass now covers TXT, HTML, Markdown, CSV, TSV, XML and
DOCX. It is not comprehensive option coverage, a representative corpus, independent human
approval or full parser parity. Broader corpus diversity and operational deadlines remain work.
