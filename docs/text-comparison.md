# Native TXT, CSV and TSV comparisons

Eight real public-entrypoint runs at upstream commit
`347fbbfd3e84e228017f125e5c5dbdc326ef7de2` cover four owned synthetic input files. They use Python
3.13.16, pandas 3.0.6, Python dateutil 2.9.0.post0 and the verified English NLP model from the
previous parser comparisons. All actual dependency versions are retained in
`docweave-integration-tests/src/test/resources/text-native-reference.json`; this is one recorded dependency set, not a claim
that every upstream dependency combination behaves identically.

The generator verifies 14 selected upstream source files and the model wheel/installed package
bytes before import. Four new parser/cleaner sources were independently checked against the
pinned GitHub files. This does not verify the complete Git tree. No parser or classifier is
patched or mocked during reference generation. Inputs use BytesIO, an explicit metadata filename
and `languages=["eng"]`, avoiding filesystem modification dates and automatic language detection.

| Cases | Upstream observations | Java comparison |
| --- | --- | --- |
| plain-default | Three elements | Same text, categories and supplementary Unicode. |
| text-auto | Seven elements | Java defaults emit four; AUTO emits six with different wrap/boundary heuristics. |
| text-no-grouper | Eight elements | Java LINE_BREAKS matches text order/strings here, but categories differ. |
| text-size-keywords | Three elements, including text over 30 code points | Java TxtOptions(10,30) emits nine bounded elements and retains all non-boundary text. |
| csv-header-false/true | One table each | Same ordered string content after explicitly accounting for control-character escaping and numeric inference; header semantics differ. |
| tsv-header-false/true | One table each | Corresponding quoted-tab/newline and number/header differences; MIME labels also differ. |

## TXT boundaries

The basic Unicode prose fixture matches exactly in type/text; it does not validate classifiers on
other text. On the policy fixture, upstream automatic grouping keeps the wrapped sentence as two
elements and classifies some short prose differently. Java BLANK_LINES combines the initial
heading/prose run until a blank line; AUTO isolates the heading and joins the wrapped sentence.
Both automatic pipelines join the indented list continuation here. Upstream with
`paragraph_grouper=False` produces the same strings as Java LINE_BREAKS, but the NLP categories
and deterministic Java categories differ.

The pinned public TXT function accepts `min_partition=10,max_partition=30` through kwargs, yet
produces unchanged output on plain.txt, including paragraphs longer than 30 code points. Java
applies its documented hard maximum after grouping. Tests retain and assert the unchanged
reference rather than pretending these options are equivalent. This case does not independently
establish upstream minimum-size behavior on short fragments; Java's soft minimum merging remains
a local contract with its own tests.

## CSV/TSV boundaries

Upstream defaults to `include_header=False`, making the first record data. Java defaults to
FIRST_ROW headers; comparisons explicitly align Java's FIRST_ROW/NONE modes with the two upstream
runs. All first-record labels remain visible in these runs. With include_header=True, pandas infers
the identifier column as numeric and upstream emits 123/456 rather than 00123/00456. Java keeps
values as strings in both modes. When the first record is data, its `id` label keeps that column
string-valued in this reference environment and leading zeros survive upstream too.

Upstream HTML/text contains literal backslash-n/backslash-t sequences for the fixtures' quoted
line breaks/tabs. Java retains the actual control characters in cell values. Tests first assert
that distinction, then decode only those known escapes to compare the other ordered cell values.
This is a fixture-specific comparison, not a universal escape-decoding rule. Empty CSV cells,
commas, doubled quotes, ampersands and literal angle-bracket text are checked as cell values.

Java marks header cells with th; the retained upstream table HTML uses td for all cells even with
headers enabled. Table plain-text separators/HTML serialization differ; both retain three ordered
rows and the expected cell counts. TSV metadata uses text/tsv upstream and
text/tab-separated-values in Java. These differences are explicit test assertions, not silently
removed metadata. Ragged records, malformed quoting, alternate encodings and large limits are
covered locally but not by this small native-reference set.

## Repeat and scope

```sh
python benchmarks/tools/generate_text_references.py \
  --upstream /path/to/pinned/unstructured \
  --model-wheel /path/to/en_core_web_sm-3.8.0-py3-none-any.whl \
  --output /tmp/text-native-reference.json
```

Use supported Python 3.11–3.13 with the recorded dependencies. Review output before updating
checked-in references. The file retains full upstream output, source/input/model hashes and exact
options. Four Java regression methods exercise all eight reference runs plus the selected Java
policies, entirely offline without Python/upstream/models installed. This is synthetic behavior
evidence, not full parser parity or independent human review. Native XML comparisons remain next.
