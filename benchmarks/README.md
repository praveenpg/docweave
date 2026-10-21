# Compatibility benchmark scaffold

The comparator consumes two saved element arrays; it does not invoke parsers. The Java library can now load/chunk saved element arrays, and generated upstream chunking cases run as Java tests. This is development tooling, not a Python runtime dependency for DocWeave.

Run the test-only Java comparator from the repository root with Java 21 or later:

```sh
mvn -B -ntp -pl docweave-validation -am test \
  -Ddocweave.compare.reference=/local/reference.json \
  -Ddocweave.compare.candidate=/local/candidate.json \
  -Ddocweave.compare.report=/tmp/fresh-comparison.json
```

All three properties are required together. The report requires a fresh path; differences are
written before JUnit fails. Invalid inputs fail without a comparison report. This Maven route
uses Maven's success/failure status. It needs no Python, Windows VM or document parser invocation.

For the historical CLI exit codes, first compile `docweave-validation` and generate `DOCWEAVE_CP`
using the [runtime classpath instructions](../docs/modules.md#build-tests-and-launchers), then:

```sh
java --class-path "docweave-validation/target/test-classes:$DOCWEAVE_CP" \
  dev.docweave.validation.SavedOutputComparisonCli reference.json candidate.json
```

The CLI prints JSON to stdout and uses exit codes 0 for identical schema-valid outputs, 1 for
differences, and 2 for invalid input (diagnostic on stderr). Use the platform's classpath separator
(`:` on Linux/macOS, `;` on Windows). The comparator is development-only and is not a published
runtime artifact or production API. The retired Python utility and its five tests are replaced
by Java/JUnit, including captured historical parity vectors and real CLI process tests.

Differences include IDs, metadata and additional fields. Object key order is ignored; array
order matters. JSON numbers/booleans retain Python equality semantics, including integer versus
binary floating-point comparisons. Inputs are strict UTF-8 JSON with the historical required
envelope; current production enum/metadata restrictions are not imposed on saved upstream data.
Text similarity joins element text with blank lines and preserves SequenceMatcher behavior with
no junk/popularity filtering, Unicode code points and directional tie-breaking. It is a diagnostic,
not OCR accuracy or a quality score. The algorithm can require quadratic work on repetitive text,
as could the former utility. Category sequence equality does not establish correct segmentation
or reading order. A human-reviewed evaluator is still required.

## Reference generation

In an isolated environment installed from the pinned upstream checkout, run `partition(filename=..., **options)` and serialize `[element.to_dict() for element in elements]` as UTF-8 JSON. Save options, commit, installed dependency versions, OCR/model versions, OS, elapsed time, and peak memory alongside the output. Initial synthetic native references now cover all seven implemented formats, with the scopes and differences described below.

Run DocWeave on the same bytes and options when its parsers exist. Compare output without overwriting references. For chunking, feed identical serialized elements to both implementations to isolate chunking from parser differences. UUIDs and compressed provenance bytes differ; the Java golden tests normalize UUIDs and decode provenance before comparing.

Fixtures are synthetic and owned by this project. `manifest.json` records inputs and intended cases; it does not contain expected parser output.

## Regenerate upstream chunking goldens

With the pinned checkout on disk and a reference Python environment containing the dependencies recorded in `docweave-integration-tests/src/test/resources/chunking-goldens.json`:

```sh
python benchmarks/tools/generate_chunking_goldens.py \
  --upstream /path/to/pinned/unstructured \
  --output docweave-integration-tests/src/test/resources/chunking-goldens.json
mvn test
```

All three current chunking reference files were executed on Python 3.13.16, within upstream's supported 3.11–3.13 range. The generator rejects unsupported runtimes and verifies five selected implementation files against `benchmarks/tools/reference-source-hashes.json` before importing upstream. This guards those pinned files, not every file in the checkout; it does not independently verify the entire Git tree. OCR/model dependencies are unnecessary for these element-only cases. Telemetry is disabled during reference runs.

## Structural-table references

Generate the independent structural comparison file with the same pinned environment:

```sh
python benchmarks/tools/generate_chunking_goldens.py --tables-only \
  --upstream /path/to/pinned/unstructured \
  --output /tmp/table-chunking-goldens.json
```

Review before replacing `docweave-integration-tests/src/test/resources/table-chunking-goldens.json`. Four cases match upstream fields exactly after documented normalization. Four retain upstream HTML-budget overflow and assert DocWeave's intentional stricter segmentation/content-conservation policy. They are comparative evidence, not eight exact-parity cases. These cases were rerun on Python 3.13.16; their normalized expected outputs are unchanged from the earlier run.

## Chunk option comparisons

```sh
python benchmarks/tools/generate_chunking_goldens.py --options-only \
  --upstream /path/to/pinned/unstructured \
  --output /tmp/chunk-options-goldens.json
```

Review before replacing `docweave-integration-tests/src/test/resources/chunk-options-goldens.json`. The 16 cases cover custom
separators and table isolation: 11 match normalized upstream outputs and five assert documented
Java differences while retaining the untouched reference output and separately reviewed
`java_expected` policy output. Differences cover visible-delimiter retention, literal versus regex
matching, mixed-table HTML metadata and overlap barriers (two cases).

At the pinned commit, the public basic/by-title functions do **not** accept
`text_splitting_separators`. Separator cases therefore use the real internal options/chunking
entrypoints; each case records `reference_entrypoint`. Table-isolation cases use the public
functions. This is evidence about internal separator behavior, not a supported public upstream API.
No upstream implementation is patched or mocked during generation.

All 41 reference comparisons run in Java without Python: 32 compare exact normalized supported
outputs; nine assert explicit differences (four structural HTML budgets and five new option
policies). These are synthetic element-only comparisons, not native parser or corpus parity.
Generator guard tests need only the Python standard library:

```sh
python3 -m unittest discover -s benchmarks/tools -p 'test_*.py'
```

## DOCX content fixture

With Java 25 and runtime dependencies on DOCWEAVE_CP, regenerate the owned fixture using:

```sh
java --class-path "$DOCWEAVE_CP" benchmarks/tools/CreateDocxContentFixture.java benchmarks/fixtures/docx-content.docx
java --class-path "$DOCWEAVE_CP" examples/PartitionDocument.java benchmarks/fixtures/docx-content.docx /tmp/docweave-docx-content.json
```

It contains structured cells, nonempty merges, footnotes/endnotes, a comment, a one-pixel image, a VML text box, a cached field and inserted/deleted text. It exercises the local extraction contract and now has a retained native upstream comparison.

## Native DOCX references

```sh
python benchmarks/tools/generate_docx_references.py \
  --upstream /path/to/pinned/unstructured \
  --model-wheel /path/to/en_core_web_sm-3.8.0-py3-none-any.whl \
  --output /tmp/docx-native-reference.json
```

Use supported Python 3.11–3.13 and install the exact dependencies recorded in
`docweave-integration-tests/src/test/resources/docx-native-reference.json`. The model is a separate upstream-pinned wheel;
its SHA-256 must equal `1932429db727d4bff3deed6b34cfc05df17794f4a52eeb26cf8928f7c1a0fb85`.
The generator verifies the wheel, installed model bytes and ten selected source files before
importing upstream. It does not install packages or download models. Dependencies/models are
development-only; Java tests and the production library need none of them.

Review generated output before replacing the checked-in reference. See
[reviewed DOCX comparison](../docs/docx-comparison.md) for the seven-versus-seven and five-versus-13
results, measured differences, exact options and scope limitations. These two synthetic fixtures
do not establish parser parity or real-world accuracy.

## Published DOCX corpus

The [initial external corpus](corpus/docx/README.md) adds two published NRS reports with source
URLs, Open Government Licence attribution, input hashes, repeatable reference/review tools and
aggregate findings. Binaries and full extraction outputs remain in a local cache. This is
XML diagnostic review, with independent human review pending; both reports share one
template family. Default Maven tests remain offline and need no external corpus files.

## Native HTML/Markdown references

Three owned inputs now retain real public upstream HTML v1/Python-Markdown outputs and explicit
Java comparisons, including raw-HTML preserve/skip/DOM policies. See
[the markup comparison report](../docs/markup-comparison.md) for options, measured differences
and the generator command. Generation verifies selected source/model bytes and records actual
dependency versions; Java tests run entirely offline. This is synthetic reference evidence,
not full dialect, HTML v2, real-world or human-reviewed parity.

## Native TXT/CSV/TSV references

Eight retained public-entrypoint runs cover four owned inputs, including TXT grouping/size
keywords and aligned first-row/data-only CSV/TSV policies. See
[the text comparison report](../docs/text-comparison.md) for exact differences and regeneration.
Plain TXT matches this reference, while grouping/classification, numeric inference, quoted control
characters, header tags and TSV MIME labels are explicitly different in selected cases. Java tests
run offline; reference generation verifies source/model bytes and records the actual pandas/NLP
dependency versions. No broad option/parser parity is inferred.

## Native XML references

Five real public-entrypoint runs cover three owned inputs: namespaced/mixed content, raw-tag
return, XPath selection and one bounded local-entity declaration. See
[the XML comparison report](../docs/xml-comparison.md) for observed ordering/category/metadata
differences and unsupported Java modes. Java retains source order and mandatory DTD rejection.
Reference generation verifies selected source/model bytes; Java comparisons run entirely offline.
This completes the initial synthetic pass across implemented formats, not comprehensive parity.

A separate [decoded-text allocation probe](text-source-allocation.md) measures isolated source-loading allocations and records its scope and reproduction command.

[Decoder and partition memory probes](decoder-and-partition-memory.md) compare decoding choices, source/full-partition allocations and intrusive live-heap observations with results held/released.

The [classification allocation investigation](classification-allocation.md) adds a sampled JFR stack profiler and records the selected optimization with independent thread-allocation measurements.

The [element emission investigation](emission-allocation.md) extends allocation stacks to provider/hash/hex/metadata work and records document-local digest reuse against a fresh modular baseline.

The [bounded hash investigation](bounded-hash-allocation.md) records streaming identity encoding, short-message promotion, Unicode carry/replacement checks and an isolated full-element emission probe.

## Retained external source anchors

The [source-only excerpt notice](SOURCE_EXCERPT_ATTRIBUTION.md) consolidates publisher
attribution for13existing DOCX/form text anchors. The [scoped primary-source review](review/excerpt-attribution/README.md)
verifies the exact sources, links and licence statements; it does not relicense external
content under the implementation's Apache license or close all source-publication rights.
