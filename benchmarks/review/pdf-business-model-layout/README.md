# Explicit model layout on the frozen business-page set

The existing two-graph model provider recovers one table on each of the three pages where
COMPACT rule-only extraction emitted none. It does **not** recover complete correct table
text on any page. Cell refinement improves the procurement case but has mixed results
across the fixed set. No production algorithm, default, model weight or artifact version changes.

## Fixed experiment

The [source manifest and rule-only control](../pdf-business-cell-text/README.md) remain
unchanged: two older business PDFs and one separately labeled education-statistics control,
944 published CSV positions in total,594 business-only. These are upstream saved native
CSV extraction expectations, not reviewed physical-scan transcriptions or complete span labels.
Whole selected pages are rasterized, not reference table rectangles. No current business
or scientific sample was added, and no training overlap or held-out status is established.

`manifest.json` pins the source manifest identity and two previously validated cached graphs
before inference. The public `OnnxTableLayout.create(detection, structure)` factory uses
maxHW800 bilinear/ImageNet preprocessing, ordinary-table detection then structure,0.5 fixed
class threshold and explicit KEEP_HIGHER_SCORE structure merges. Detector merge policy remains
its unused default REJECT_TABLE. Both sessions have one admitted call, four native threads,
30-second per-pipeline limit,256MiB graph limit,16M pixels and256 queries; aggregate detection/
structure resource contracts remain production behavior. Source/revision/export provenance:
[detection](../pdf-page-detection/README.md), [structure](../pdf-merge-conflicts/README.md).
Model scores are not calibrated extraction confidence.

All original rendering, English Tesseract5.5.3, PAGE AUTO/PSM3, CELL SINGLE_BLOCK/PSM6,
retained borders, budgets and evaluation rules are held fixed. Explicit maxTableCells1024
remains a uniform caller policy, not the default256 claim. There is no model/rule composition,
rotation estimator, magnification, cell context, text-based chooser or reference-ROI crop.
The only layout change from the frozen control is the explicit model dependency. PAGE-word
and CELL profiles independently run that same layout. The model is managed with try-with-resources
and closed after all captures, including failures; the launcher neither downloads resources
nor changes ordinary Maven test execution.

## Actual Java21 and25 outcomes

| Case | Reference grid | Emitted grid, both profiles | PAGE exact positions | CELL exact positions | Additional CELL calls |
| --- | --- | --- | --- | --- | --- |
| financial-disclosure |88x5 |60x5 |49/440 |39/440 |296 |
| transport-procurement |14x11 |14x11 |65/154 |88/154 |154 |
| education-statistics (control) |50x7 |51x7 |67/350 |67/350 |357 |

All six case/profile runs succeed per JDK. Each has one PAGE recognition, one layout call,
one returned/emitted/ROI-associated table, and no unmatched tables. Parent IoUs are0.982507,
0.922386 and0.978724 respectively. The original compact strategy returned no table for any
of these pages. All944 reference positions remain in each profile's denominator.

Only procurement has the reference row/column dimensions; dimension agreement does not
establish exact merged-cell structure. Financial/statistics row mismatch means positional
text misses mix topology and recognition errors. Do not label all mismatches as OCR mistakes.
No table has fully exact grid text under either profile.

Business-only totals: PAGE114/594 positions versus CELL127/594. Nonempty positions are
66/420 versus97/420; matching empty positions decrease48/174 to30/174 in financial disclosure,
explaining why total-position and nonempty-position changes differ. With the separate statistics
control included, PAGE181/944 versus CELL194/944 positions; nonempty133/756 versus164/756,
empty48/188 versus30/188. These scoped CSV comparisons are not general accuracy scores.
CELL adds450 recognition calls for the two business cases,807 with the statistics control.
No latency, throughput, total heap/native memory or end-to-end deadline benchmark is claimed.

The results support explicitly selecting a model where rule-only layout misses. They do not
support a universal PAGE/CELL winner, automatic case selection, new default threshold or
PDF completion. This uses existing functionality and is not a new beta functionality milestone;
the signed local beta.3 candidate is unchanged and no publication occurs.

## Evidence and reproduction

`java21.json` and `java25.json` contain only geometry/counts/identities and mismatch-position
hashes, not document text or HTML. The source pages/CSV labels, raster derivatives and full
captures remain local/fetch-only. `identities.json` records model/runtime/tool identities,
paired comparisons and a fresh replay of the original five-argument rule-only control.
The old control evidence is preserved; the current launcher merely adds an optional model branch.

Use the [original capture/comparison commands](../pdf-business-cell-text/README.md), with
current ONNX adapter classes and its native-runtime dependency in the development classpath.
Append these four explicit model arguments to the Java launcher:

```sh
/path/to/detection.onnx 0f625baa8849eff26e265ce08a2f6b1479e73f52edd5adc09799c7e7fcc689b9 \
/path/to/structure.onnx ff1d19a18cb5f041c1692ff681f891a5180f9430d68a71d2cf7a0a2e8e55b990
```

Supply a new output directory, the pinned local engine/model files and the existing source
`cases.tsv`. On Java25, `--enable-native-access=ALL-UNNAMED` can be supplied for the explicit
classpath ONNX runtime. Run the **unchanged comparator** against the original source manifest;
never substitute CSV/ROI information into inference. The capture helper's release21 compilation,
strict public/private Javadocs and reactor standalone-source guard pass on actual21/25.
All34 existing offline benchmark-tool guards pass without starting an OCR engine.

Next establish native extraction results on these original text-based PDF pages before
working on row segmentation. This will separate a practical native extraction gap from
recognition loss introduced by deliberately rasterizing documents that already contain text.
Current/scientific coverage and independent physical-scan text labels remain separate open gates.
