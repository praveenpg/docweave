# External DOCX corpus: initial review

The separate [official forms corpus](../docx-forms/README.md) adds two different
publishers/templates with candidate-only stored-text diagnostics. This historical
NRS/upstream comparison remains unchanged. Review commands now require a fresh
output path; Maven runs current Java classes directly.

This is an initial set of two publicly published National Records of Scotland (NRS) reports,
not synthetic fixtures. Both use the same report family/template; neither is a representative
DOCX corpus. Review here means XML/contract diagnostics. Independent
human review is pending and is recorded as such in the manifest. Do not report these checks as
human-approved ground truth or an extraction accuracy score.

Sources and attribution:

- [North Lanarkshire Council and Licensing Board, October 2025](https://www.nrscotland.gov.uk/publications/progress-update-review-north-lanarkshire-council-and-licensing-board-october-2025/)
- [Scottish Qualifications Authority progress update review](https://www.nrscotland.gov.uk/publications/progress-update-review-scottish-qualifications-authority/)

Contains public sector information licensed under the [Open Government Licence v3.0](https://www.nationalarchives.gov.uk/doc/open-government-licence/version/3/).
© Crown Copyright, National Records of Scotland. Source pages exclude graphic assets and other
stated exceptions from that licence. Source binaries, graphics and full extracted reports stay
in the local cache and are not committed. `manifest.json` records publisher URLs, licence notice,
byte sizes, SHA-256, review status and selected contract checks. No association or endorsement by
the publisher is implied.

## Observed results

Both source files were parsed with the pinned upstream public `partition_docx` entrypoint and the
same Python/model/source verification used for the two synthetic references. `review.json` retains
reference provenance, raw-reference SHA-256, candidate partition-source fingerprint, Java version
and aggregate measurements. Raw upstream and candidate output remain local and can be regenerated.

| Document | Upstream elements | Java defaults | Java with stored parts | Body paragraphs checked | Java unmatched | Upstream unmatched |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| North Lanarkshire | 50 | 58 | 60 | 240 | 0 | 12 |
| SQA | 52 | 61 | 63 | 419 | 0 | 10 |

Java emits two and three tables respectively, without warnings in either mode. Default Java
output excludes header/footer parts. Enabling stored parts adds one Header and one Footer per
file. Upstream emits a Footer but no Header for these runs. Java retains eight Titles in each
report; upstream classifies their text differently. SQA also produces an image reference in Java;
no pixel bytes are retained in this review.

The paragraph diagnostic checks whether each nonblank accepted body XML paragraph's text occurs
somewhere in concatenated output after whitespace normalization. It handles tab/break boundaries
and excludes deleted/moved-from text. It does not check multiplicity, order, parent ownership,
layout or correctness of categories. A mismatch can reflect changed spacing, a footnote marker,
field/TOC handling or omitted text; it is not automatically a content-loss finding. The current
files have no nested paragraph/text-box paragraphs; expand the baseline before applying it to
such inputs. Most upstream unmatched paragraphs here concern table-of-contents text. Tests also
require selected report text, minimum table counts and expected stored-part counts.

No production defect was established by this small review. Synthetic tests remain necessary for
international numbering, revision selection, controls, nonempty merges and supplemental content.
Prioritize documents from different publishers/applications and independent human review next.

## Repeat the review

Download the manifest's exact `download_url` values to a local cache using the specified filenames.
Both tools reject source bytes that differ from manifest hashes. A changed publisher download
requires a new review and manifest version, not silently refreshed expectations. The tools do not
fetch documents automatically.

```sh
python benchmarks/tools/generate_docx_references.py \
  --upstream /path/to/pinned/unstructured \
  --model-wheel /path/to/en_core_web_sm-3.8.0-py3-none-any.whl \
  --fixtures /path/to/local/cache \
  --manifest benchmarks/corpus/docx/manifest.json \
  --output /tmp/docweave-corpus-reference.json

mvn -pl docweave-validation -am test \
  -Ddocweave.corpus.manifest=benchmarks/corpus/docx/manifest.json \
  -Ddocweave.corpus.cache=/local/cache \
  -Ddocweave.corpus.baseline=substring \
  -Ddocweave.corpus.reference=/tmp/docweave-corpus-reference.json \
  -Ddocweave.corpus.report=/tmp/new-docweave-corpus-review.json
```

Run from the project root. Reference generation requires supported Python and the
recorded native/NLP dependencies. Review now runs directly in Maven/Java; no Python,
launcher subprocess or runtime-classpath file is required. Historical Python/JAR
fingerprints remain in the old snapshot. Review new diagnostics separately rather
than overwriting historical evidence. Default Maven tests need no corpus downloads.

A follow-up [conditional-style audit](../docx-styles/README.md) reuses these reports alongside three
pinned Apache POI samples. It distinguishes unchanged-source inventories from controlled derivative
probes; these original reports have no conditional semantic definitions. The initial upstream review
above remains a historical snapshot, separate from the new candidate-only style audit.

The Python inventory/reviewer are retired; current diagnostics use Java/JUnit.
Upstream reference generation above still executes the real pinned Python package.
Omit docweave.corpus.reference for candidate-only diagnostics. Legacy substring
presence is not ownership, multiplicity, reading-order or rendering evidence.
