# Financial/statistics native-model row audit

The row mismatch already exists in model geometry before native glyph assignment. This audit
isolates stage counts and compares existing native/model row intervals without changing model
settings, output strings, reference labels or production code. It is a diagnosis, not an
extraction improvement or closure of the row-quality gate.

## Fixed source and actual Java21/25 findings

The source/profiles remain the [frozen business/statistics set](../pdf-business-cell-text/manifest.json)
and the [quarter-turn native-model captures](../pdf-native-provider-turns/README.md).
Both CSV sources have **zero entirely blank reference rows**, so dropping blank CSV rows does
not explain the model row counts. The CSVs remain saved extraction expectations, not physical
row/span annotations. Model class scores are not calibrated extraction confidence.

| Stage | Financial | Statistics |
| --- | --- | --- |
| Reference/native comparison rows |88 |50 |
| Thresholded positive row objects |67 |55 |
| Excluded by table membership |0 |0 |
| Removed by duplicate suppression |8 |4 |
| Emitted model rows |59 |51 |
| Columns |5 |7 |
| Existing exact CSV positions |102/440 |72/350 |

Removing duplicate suppression alone cannot restore88 financial rows: only67 positive row
objects reach topology processing. These diagnostics receive objects **after** class selection,
score thresholding and clipping. They do not distinguish model/background predictions from
below-threshold queries, prove that suppression is wrong, or justify changing a threshold.
Statistics is also not simply one extra row that can safely be deleted.

The geometry-only row-band comparison shows why index shifting would conceal disagreements:

- Financial: all88 native row centers fit59 model bands;29 model bands contain multiple native
  centers and one contains none. One model band contains three native centers.
- Statistics: the first model band contains centers from five native header bands; five other
  model bands have no native-row center. All50 native centers are retained in the diagnostic.

Native bands are recovered only from single-row anchor bounds in existing captures. Financial
native geometry is closed-grid source-rule geometry; statistics uses explicit inferred partial-rule
geometry. These are **diagnostic comparisons**, not newly reviewed physical-row annotations.
A model band without a native-row center is not necessarily an empty text cell or a false positive.
No sequence alignment, candidate text, label repair or changed accuracy denominator is used.

## Stage capture and safeguards

`CaptureDetectedTableInput.java` adds an explicit `original-pdf page sha256` mode. It loads the
pinned original PDF but renders **only the declared source page** at150DPI. This isolates the
existing detector/crop/structure pipeline; it is not another whole-document quality run. It
supplies no reference ROI/text to inference. Original-mode policies match the existing four-thread
TATR detector/structure graphs and KEEP_HIGHER_SCORE structure merges. Input16MiB, rendered16M
pixels, fixed model query/work/deadline limits and fresh outputs remain enforced. Raster/native
sessions are closed on errors. The existing JPEG mode and its policy remain available; a bounded
input-byte guard is also applied to that diagnostic mode.

The actual financial structure crop1053x1435 becomes587x800 model input; statistics1097x1130
becomes776x800. These measurements establish resizing, not that resizing is the cause or that
higher render DPI would fix the result. Both already use the model's800-pixel maximum side.
Positive-object TSVs contain only labels/class scores/numeric rectangles. No logits/source text
or cropped source images are committed.

For original-mode diagnostics only, the tool reflectively replays the existing private membership/
suppression functions with unchanged constants and a shared bounded work counter. This avoids
copying or changing production policies or exposing a diagnostic public API. A changed private
method signature fails the audit explicitly; this is not a stable application API. The additional
preprocessing/policy replay is diagnostic work, so no production latency/deadline benchmark follows.

`AuditCapturedRowBands.java` reads successful single-table captures in identical displayed frames,
recovers only single-row-anchor intervals, and counts native centers in half-open model intervals.
Unrecoverable row counts/unassigned centers are retained rather than invented or matched by text.
Inputs are bounded to16MiB each and center comparisons use the existing shared work/cancellation
ceiling. Owned controls check exact half-open boundary assignment, combined native rows and retained
unmatched centers. The ordinary standalone-source test compiles both tools for release21 and
runs the band controls without external models/files.

`manifest.json` was frozen before model captures; exact additional row-band input bytes and source
hashes are recorded in `identities.json`. The row-band diagnostic was developed after observing
head counts; it is explanatory development evidence, not a pre-registered held-out accuracy test.
Paired JDK reports preserve all counts/geometry, and all comparison denominators remain in their
original reports. Earlier pilot captures remain local rather than overwriting final evidence.

## Reproduction

Compile the tools with release21 and current PDF/ONNX/Jackson dependencies, then:

```sh
java --enable-native-access=ALL-UNNAMED -Xmx1g -Djava.awt.headless=true \
  -cp "$classes:$classpath" dev.docweave.layout.CaptureDetectedTableInput \
  /path/to/original.pdf /path/to/NEW-stage-output \
  /path/to/detection.onnx 0f625baa8849eff26e265ce08a2f6b1479e73f52edd5adc09799c7e7fcc689b9 \
  /path/to/structure.onnx ff1d19a18cb5f041c1692ff681f891a5180f9430d68a71d2cf7a0a2e8e55b990 \
  original-pdf SOURCE_PAGE PDF_SHA256
java -cp "$classes:$classpath" dev.docweave.partition.AuditCapturedRowBands --self-test
java -cp "$classes:$classpath" dev.docweave.partition.AuditCapturedRowBands \
  /path/to/native-partial.json /path/to/model-native-live.json /path/to/NEW-bands.json
```

Financial is original page1; statistics page2. Use source SHA256 identities from the unchanged
manifest. Captured source documents, labels, JSON HTML/text and graphs remain local/fetch-only.
Committed stage/frame/object geometry and band reports are text-free. Actual Java21/25 full
clean verification passes940 tests/Javadocs,36 tooling guards and pinned formatting. Both helpers
also pass strict public/private Javadocs on each JDK. No model is executed by ordinary builds.

## Implementation consequence

Next implement an explicit **native-first table composition**: retain accepted native topology,
and use model layout for disjoint tables not already owned by native extraction. It must be a
caller-selected strategy with overlap/ownership/resource/error controls, not a text-based per-case
chooser or an unsupported default change. This preserves existing native strengths (financial440/440,
statistics339/350) while retaining model coverage where native extraction found no Tables (recent
issuer statements59/59 rows). These are separate reference types and do not establish a general
routing guarantee. This audit alone has not improved extraction or closed the quality gate.
Release preparation stays deferred; no new beta milestone/publication action is needed for this audit.
