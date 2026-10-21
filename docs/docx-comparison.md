# Native DOCX reference comparison

These are two owned synthetic documents, compared against public `partition_docx` at Unstructured
commit `347fbbfd3e84e228017f125e5c5dbdc326ef7de2`. They establish specific observations, not full
DOCX parity, real-world accuracy, or superiority across documents.

The reference uses Python 3.13.16, python-docx 1.2.0, spaCy 3.8.16 and the upstream-pinned
`en_core_web_sm` 3.8.0 model. The generator verifies ten selected upstream implementation hashes,
the model wheel SHA-256 and installed model package bytes before extraction. The complete Git
source tree and every dependency file are not independently verified. No parser, classifier or
model is patched or mocked during reference generation.

Both implementations receive identical DOCX bytes. Upstream receives BytesIO with an explicit
metadata filename, `languages=["eng"]`, `include_page_breaks=False` and `infer_table_structure=True`.
DocWeave uses its defaults. These are recorded policies, not a claim of option equivalence: Java
has no NLP language classification or rendered pagination. Neither fixture has header/footer or
page-break content. File timestamps and absolute paths are avoided in the reference input path.

| Fixture | Upstream elements | Java elements | Reviewed observations |
| --- | ---: | ---: | --- |
| structured.docx | 7 | 7 | Five type/text pairs match; all text matches after table whitespace normalization; hyperlink text/URL/code-point offset and indexed parent hierarchy match. |
| docx-content.docx | 5 | 13 | Main paragraph strings and shared merged-table content remain; supplemental content, controls, cached fields and revision/merge handling differ. |

In `structured.docx`, Java uses tabs/newlines for table boundaries and `th` for explicit header
cells; upstream emits space-separated table text and `td` for these header cells. Both retain the
same ordered cell values. Upstream classifies “Final paragraph.” as UncategorizedText; Java emits
NarrativeText under its documented body-paragraph policy. Tests assert these differences instead
of removing them from both outputs. Parent IDs are compared by element positions, retaining
hierarchy while avoiding a requirement that generated IDs match.

In `docx-content.docx`, the pinned default upstream run omits the fixture's inserted “new” revision
text, content-control heading/table, nonempty merge continuation, image reference, VML text box,
cached DATE result, footnote, endnote and comment text. Java retains those items under its default
content policy. Upstream still emits the paragraph prefix “Selected revision: ” and the merged
origin/right cells. Both tables retain the rowspan, while Java appends the continuation text to
the merged origin. The initial heading and body classifications also differ. These observations
apply to this fixture, entrypoint, dependency set and defaults; an upstream custom image provider
or another configuration may produce different output.

`docweave-integration-tests/src/test/resources/docx-native-reference.json` retains complete upstream output, input SHA-256,
options, source/model/dependency provenance, OS, elapsed extraction time and cumulative Linux
process high-water RSS. It does not replace upstream output with Java output. `DocxReferenceTest`
checks input hashes and separately reviewed Java expectations; two comparison tests cover many
individual assertions. Java tests run without Python, the model or upstream installed.

An [initial external corpus](../benchmarks/corpus/docx/README.md) now adds two published NRS
reports with pinned source bytes, aggregate findings and repeatable default/stored-part checks.
All 240 and 419 accepted body-paragraph strings were located in Java output under the documented
whitespace diagnostic; this does not establish ordering, layout or human-approved accuracy.
Both reports share a template; independent human review remains pending. Expand publisher/authoring
diversity, international numbering, revisions/merges and nested tables next. Native references for
the other implemented formats also remain outstanding.
