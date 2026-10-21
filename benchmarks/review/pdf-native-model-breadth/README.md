# Broader native/model validation after the recent statement pages

The native/model route is useful but not a universal replacement for native heuristics.
On four previously frozen development cases it matches the scientific table's43 independent
span/text anchors, predicts the wrong row count for two CSV-labelled tables, and explicitly
rejects the rotated procurement PDF. These misses are retained. No production algorithm,
model, threshold, default or reference labels change. Release preparation is deferred.

## Fixed protocol

`manifest.json` pins the unchanged business/statistics and scientific source manifests before
these captures. These are older sources already used elsewhere in validation, not new recent
publications, independent held-out samples or training-overlap-free claims. The original PDF
is processed completely with the explicit native-layout factory and pinned two-graph model,
150DPI/16M pixels/1024 raw anchors, four native threads and KEEP_HIGHER_SCORE structure merges.
No reference ROI, selected page, labels or text enter extraction. Source pages are selected
only afterward; failures elsewhere in a document retain its complete selected denominators.

The same `CaptureNativeModelTables.java` helper runs each original document once. It now
records the existing empty PAGE-raster fingerprint list for comparator compatibility (zero
OCR calls) and correctly describes the **displayed** source width/height on rotated inputs,
including rejected inputs. An initial local capture used raw crop dimensions in that diagnostic
field; it remains local, and the corrected capture was replayed without changing extraction
or scoring. The earlier original-raster/whole-document experiments remain unchanged.

Business/statistics uses the unchanged optional `compare_business_cell_text.py` evaluator
and all944 saved Tabula CSV positions, including blanks. This Python script is development
comparison tooling; no Python implementation or runtime dependency is added to DocWeave.
CSV cells are saved extraction expectations, not physical PDF span labels. No matching shifts,
fragment joining, numeric corrections or geometry-based label changes. Scientific evaluation
uses the existing Java XML/span parser, strict ASCII whitespace normalization, exact zero-based
row/column/rowspan/colspan keys and maximum parent IoU>=0.5. All43 reference anchors remain;
blank/missing/extra anchor distinctions are preserved. The denominators are not pooled.

## Paired Java21/25 results

| Case | Reference | Existing partial-rule native policy | Explicit live native/model route |
| --- | --- | --- | --- |
| financial disclosure |88x5 CSV |440/440 positions |59x5;102/440 positions |
| transport procurement |14x11 CSV |152/154 positions |UNSUPPORTED_OPTION;0/154 positions retained |
| education statistics |50x7 CSV |339/350 positions |51x7;72/350 positions |
| scientific cycle |7x7,43 span/text anchors |43/43 exact anchors |43/43 exact anchors and complete topology |

The native-policy comparison is the uniformly applied existing RULED_AND_PARTIAL_TEXT
experiment, not a new per-case automatic chooser. Its source-pinned evidence is
[here](../pdf-partial-rule-text/README.md) and the independent scientific native control is
[here](../pdf-scientific-native-spans/README.md). The recent two statement pages still have
their separate [59/59 row-content result](../pdf-native-provider-layout/README.md); no old
report is overwritten and these label types must not be combined into an accuracy score.

Financial native/model matches include29 nonempty and73 empty positions. Statistics has72
nonempty matches and no matching blanks. Different predicted row counts mix topology and
recognition/assignment disagreement; the misses do not establish that source text is lost.
No CSV-labelled table has complete matching grid text under this model route. Financial
association IoU0.979740 and statistics0.977962; scientific0.986677. Procurement has declared
page rotation90 and is rejected before any model call by the documented native-layout contract.
Its displayed frame is792x612, not the raw612x792 crop.

Calls are document-wide: financial1 layout/1 model table; procurement0/0; statistics6/6;
scientific3/3. There are no PAGE/CELL OCR calls. Only the fixed selected tables have references;
do not call other model-returned tables correct. Original rendering can differ from earlier
image-only derivatives, so this is not an isolated text-only ablation. Parsed captures and
metrics agree across actual Java21 and25, excluding runtime identity/JSON byte ordering.

## Reproduction and safeguards

Use [the existing live capture command](../pdf-native-provider-layout/README.md#reproduction)
with `benchmarks/review/pdf-business-cell-text/cases.tsv` and its pinned cache for the three
business/statistics cases. Use `scientific-cases.tsv` here with the original scientific cache
for the independent table. Supply new output directories. Then use the unchanged comparator:

```sh
python3 benchmarks/tools/compare_business_cell_text.py \
  benchmarks/review/pdf-business-cell-text/manifest.json /path/to/business-cache \
  /path/to/NEW-business-captures /path/to/NEW-business-score.json --profiles native-live
```

The scientific Java diagnostic does not execute a provider or download resources:

```sh
mvn -B -ntp -pl docweave-validation -am test \
  -Dtest=PdfScientificSpanTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Ddocweave.pdf.scientific.cache=/path/to/scientific-cache \
  -Ddocweave.pdf.scientific.report=/path/to/NEW-native-control.json \
  -Ddocweave.pdf.scientific.model.captures=/path/to/scientific-captures \
  -Ddocweave.pdf.scientific.model.report=/path/to/NEW-model-score.json
```

It validates original PDF/XML bytes and repository blob identities, source page/hash,
layout strategy and pinned model runtime identities, then reuses the native span/text evaluator.
Model misses remain diagnostic observations, never a hardcoded expectation that future changes
must keep failing. Existing six native scientific regressions remain positive acceptance tests.
Default builds require no external files/models. Actual21/25 full clean verification passes938
tests each and strict Javadocs;36 offline tooling guards and pinned formatting pass. The helper's
strict public/private Javadocs also pass on both JDKs. Text-free reports and hashes are committed;
raw PDFs, CSV/XML labels, source text/HTML, models and full captures remain local/fetch-only.

Next implement declared quarter-turn support for the explicit native/model strategy, using
owned cell/span/ownership/resource controls before replaying the unchanged procurement case.
Keep existing heuristics and factory defaults, and retain the financial/statistics model misses.
Approximate PDF feature completion remains85–90%, a planning estimate rather than accuracy.
