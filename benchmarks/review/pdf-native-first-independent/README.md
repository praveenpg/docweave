# Independent native-first structural breadth

Two previously unscored original documents were selected by ordinal fixture order from the
pinned Camelot ICDAR2013 US collection: us-001 and us-002, including **all four** published
regions. The source selection, byte/repository identities, label counts and geometry were frozen
in manifest.json before candidate execution. These historical public-statistics and education-
finance documents expand structural breadth; they do **not** close recent business/scientific
coverage or establish held-out population accuracy. No production behavior changed in this step.

## Fixed results

| Reference region | Expected shape | Observed shape | Exact span/text anchors | Missing keys | Extra keys |
| --- | --- | --- | --- | --- | --- |
| disability-first |26x11 |26x11 |260/278 |11 |1 |
| disability-second |13x11 |13x11 |88/127 |22 |2 |
| education-first |32x8 |33x8 |2/214 |40 |51 |
| education-second |32x8 |32x8 |208/215 |4 |11 |

All four regions are geometrically associated (IoUs0.981–0.993) and extraction reports SUCCESS.
None has complete span/text or complete topology. All selected tables use
provider_layout_native_glyphs_v1: native partial-rule detection supplies no accepted replacement
for these regions. Native-first precedence cannot repair model geometry when no native table
exists. Identical selected-page source identities and predictions on actual Java21/25 confirm
runtime repeatability, not quality. Denominators remain separate and every miss stays visible.

The weakest region has an additional predicted row. A post-run **diagnostic only** comparison
at hypothetical constant row offsets gives0 matches for-1,2 at0 (the actual score),201 at+1.
This supports investigating header/row topology; it does not justify shifting source labels,
postprocessing predictions against references, declaring201 correct or claiming a model fix.
Other topology/text differences remain even in that hypothetical comparison. No thresholds,
models, native policies or source labels were tuned after results.

## Protocol and integrity

The production capture uses the unchanged uniform native-first PARTIAL_TEXT policy,150DPI,
16M render pixels,1024 combined anchors, four model threads and pinned detector/structure graphs
from [the prior composition protocol](../pdf-native-first/README.md). Both complete originals
run before page selection. No reference ROI/page/text enters extraction. Selected-page outcomes
retain whole-document failures. Runtime counters are document-wide and repeated per selected
region; do not sum those repeats. Unlabelled pages/tables are not declared correct.

Original XML defines independent rectangular span/text keys. Missing end-row/end-col attributes
mean the start index (unit span); the source bytes are preserved. Zero/one-based origins are
normalized by subtracting the common minimum, as in the existing scientific evaluator. Sparse
original annotations remain sparse, without creating blank cells. Overlapping/invalid anchors
fail validation. Association selects maximum parent-box IoU>=0.5, first prediction on ties,
without inspecting text. Strings use ASCII whitespace collapse only; case, punctuation and
Unicode remain unchanged. No row shifting, fragment joining or missing-empty credit applies.

The opt-in Java evaluator validates source hashes, repository blobs, region geometry, expected
shape/anchor counts, selected-page identities and pinned model hashes before emitting fresh
numeric-only reports. It invokes no engine and downloads nothing. The ordinary guard covers
omitted end indices plus existing overlap/entity/normalization checks. Existing scientific
capture scoring shares the same association/span comparer; original historical reports remain.

## Reproduce

Fetch the six originals from manifest.json into an explicit cache and verify their hashes.
Compile CaptureNativeModelTables.java with the project runtime classpath. Invoke its unchanged
seven arguments (cache, cases.tsv, NEW output directory, detector path/hash, structure path/hash)
plus trailing native-first. Use -Djava.awt.headless=true on a host without a display server.
An initial local attempt omitted headless mode and failed during AWT display initialization;
its output was retained locally and a fresh headless directory used. This was a harness setup
failure, not a source result. No source cases were removed.

Then run Java verification with explicit cache/capture/new-report properties:

```sh
mvn -B -ntp verify \
  -Ddocweave.pdf.independent.cache=/absolute/source-cache \
  -Ddocweave.pdf.independent.captures=/absolute/capture-directory \
  -Ddocweave.pdf.independent.report=/absolute/NEW-report.json
```

Ordinary verification leaves this optional source scorer inactive. Repeat on actual Java21/25;
models/captures remain external to Maven. Reported exact matches are measured diagnostics,
not positive accuracy assertions. A successful test run checks protocol integrity, not that
these four tables are correct. Numeric reports/identities are committed; source PDFs, XML text,
rendered pages, generated table text/HTML, captures and model graphs stay fetch-only/local.

Actual Java21/25 full clean verification passes950 tests per runtime and strict Javadocs.
Pinned whole-repository Java formatting and diff checks pass. Prior scientific controls retain
43/43 exact anchors after comparer reuse; historical reports are not overwritten.

Next: diagnose the education-first extra row against independent source geometry without
changing these labels or selecting thresholds per document. Recent business/scientific source
coverage and historical/degraded scan text gates remain open. Release preparation stays deferred.
