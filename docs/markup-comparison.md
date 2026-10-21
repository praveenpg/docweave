# Native HTML and Markdown reference comparisons

Three owned synthetic inputs were executed against public HTML/Markdown entrypoints at upstream
commit `347fbbfd3e84e228017f125e5c5dbdc326ef7de2`. References use Python 3.13.16, Python-Markdown
3.11.1, Beautiful Soup 4.15.0, nh3 0.3.7 and the same pinned English NLP model as the DOCX runs.
Actual dependencies are recorded in `docweave-integration-tests/src/test/resources/markup-native-reference.json`.

The generator verifies 16 selected pinned implementation hashes and the model wheel/installed
package bytes before upstream import. It does not verify the whole Git tree or every dependency
file. No upstream parser/classifier is patched or mocked. Sources are supplied as decoded UTF-8
text with explicit metadata filename and `languages=["eng"]`; no URLs/assets are fetched.
HTML parser version is explicitly `v1`, the pinned public default. Markdown explicitly enables
Python-Markdown's `tables` and `fenced_code` extensions. HTML v2 is not covered here.

| Input | Upstream elements | Java elements | Measured scope |
| --- | ---: | ---: | --- |
| structured.html | 6 | 6 | Same types and non-table text; indexed heading/list hierarchy, link labels/URLs and ordered table cells match. |
| structured.md | 8 | 8 | Same types and first six text strings; list nesting, code trailing newline/metadata, table representation and later parent metadata differ. |
| mixed-html.md | 6 | 4 preserve / 3 skip / 5 DOM | Raw HTML policy, Unicode link offsets, mixed headings, hidden content and classification differ explicitly. |

## Shared structure and deliberate boundaries

For structured HTML, both implementations preserve the heading/list sequence and table header
cells. Java emits tabs/newlines in table text and compacts away row-group wrappers; upstream emits
space-separated table text with thead/tbody wrappers. Tests compare ordered cell values, row and
header counts separately, while asserting that text representations differ. List category_depth
is zero for Java's top-level list items and one in this upstream reference. Link labels/URLs
match; Java additionally emits Unicode code-point offsets absent from the upstream HTML output.

For structured Markdown, the two-space-indented item is nested by CommonMark Java, while
Python-Markdown treats it at the preceding top list level. Java anchors its parent to the first
list item; upstream anchors it to the section heading. Java retains the code block's trailing
newline, code-language metadata and heading parent; upstream trims that trailing newline and does
not retain those metadata fields in this run. Java retains pipe-table alignment metadata and the
string values 00100/00200. Both extract the same ordered cells; table serialization/text separators
differ. This is evidence for these syntaxes and library versions, not Markdown-dialect parity.

For mixed Markdown/HTML, Java defaults to literal HTML preservation with one warning. Inline HTML
markup remains literal around the processed Markdown label; raw block content remains literal.
SKIP omits the raw block/tags and loses the raw anchor's link relationship, retaining its displayed
Markdown text. EXTRACT_DOM emits a single semantic DOM stream, including the HTML heading/link,
while removing explicitly hidden and script content. Its Unicode link offset is seven code points.
The upstream v1 pipeline emits the hidden paragraph here, and classifies short prose differently.
Upstream emphasis metadata is also present where Java does not expose equivalent annotations.
These are observed extraction policies; no browser CSS/layout or HTML v2 behavior is inferred.

## Evidence and repetition

The saved file retains complete upstream outputs, input SHA-256, exact options, source hashes,
model verification and dependency versions. Java tests validate the original input bytes and
separately reviewed shared/different behaviors, without substituting candidate output for the
reference. `MarkupReferenceTest` runs without Python, NLP models, upstream or network access.

```sh
python benchmarks/tools/generate_markup_references.py \
  --upstream /path/to/pinned/unstructured \
  --model-wheel /path/to/en_core_web_sm-3.8.0-py3-none-any.whl \
  --output /tmp/markup-native-reference.json
```

Use supported Python 3.11–3.13 with recorded dependencies, then review changes before replacing
checked-in references. Three synthetic inputs do not establish real-world quality, full option
coverage, malformed-input parity or independent human review. Expand to permitted web documents,
more dialects/visibility cases and upstream HTML v2 before broader claims.
