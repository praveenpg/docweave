# Model/OCR on frozen current-business statements

The existing explicit model pipeline recovers a table on each selected statement page,
where all six native modes returned no Tables. PAGE matches42/59 issuer HTML rows exactly;
CELL matches44/59, adding209 cell recognition calls. Neither table has complete exact row
content. These are two development cases from one issuer, not broad accuracy or held-out results.
No production algorithm, API, dependency, default or release version changes.

## Frozen protocol

The [native source manifest](../pdf-current-business-rows/manifest.json), original PDF,
issuer HTML, fixed source pages/ROIs,27/32 row references and normalization remain unchanged.
`manifest.json` pins that manifest and the existing detector, structure, English model and
engine identities before capture. `cases.tsv` supplies only source identity/page, never
reference text or table ROI, to the unchanged `CaptureBusinessCellText.java` launcher.

Unlike native full-original-document extraction, this experiment deliberately rasterizes
one selected page at150DPI into an image-only one-page PDF, then runs real PAGE OCR/model
layout and optional CELL refinement. Parent coordinates retain the displayed original frame;
element page1 is derivative page1, corresponding to original page7 or10. Original page size
is612x792, rotation0, raster1275x1649. This is not direct original-document model extraction
or evidence about physical historical/degraded scans.

The two pinned TATR graphs use existing defaults, four native threads and explicit
KEEP_HIGHER_SCORE structure merges. English Tesseract5.5.3 uses PAGE AUTO and CELL
SINGLE_BLOCK, with existing budgets, maxTableCells1024 and no cell context. No threshold,
resolution, text normalization, border policy or table-selection tuning was performed.
Model provenance and resource contracts are documented in the
[earlier model experiment](../pdf-business-model-layout/README.md).

## Paired Java21/25 results

| Case | Reference rows | Observed rows, either profile | PAGE exact rows | CELL exact rows | Extra CELL calls | Parent IoU |
| --- | --- | --- | --- | --- | --- | --- |
| operations |27 |27 |18 |20 |125 |0.972067 |
| balance-sheet |32 |32 |24 |24 |84 |0.981204 |

Every case/profile succeeds, emits one associated table, and uses one PAGE recognition and
one model-layout call. No missing or extra nonblank row positions. Equal row counts do not
establish exact physical cells, spans or box accuracy: issuer HTML presentation cells remain
content references only. Exact ordinal comparison preserves currency spacing, punctuation,
case and numbers; no fuzzy search, row shifting, fragment joining or reference repair.
All59 rows remain in each profile denominator. Remaining mismatches may combine recognition,
cell segmentation and presentation spacing; they are not all established OCR errors.
CELL helps only operations in this tiny set and does not establish a universal winner.

## Reproduction and evidence

Keep downloaded documents/models and raw captures outside Git. Compile the existing launcher
with `javac --release21` and the current core/PDF/OCR/ONNX development classpath. Run it with:

```sh
java -Xmx1g -Djava.awt.headless=true --enable-native-access=ALL-UNNAMED \
  -cp "$classes:$classpath" dev.docweave.partition.CaptureBusinessCellText \
  /path/to/source-cache benchmarks/review/pdf-current-business-model/cases.tsv \
  /path/to/NEW-captures /path/to/tesseract /path/to/tessdata \
  /path/to/detection.onnx 0f625baa8849eff26e265ce08a2f6b1479e73f52edd5adc09799c7e7fcc689b9 \
  /path/to/structure.onnx ff1d19a18cb5f041c1692ff681f891a5180f9430d68a71d2cf7a0a2e8e55b990
```

Then run the Java evaluator (no engine invocation or automatic download):

```sh
mvn -B -ntp -pl docweave-validation -am test \
  -Dtest=PdfCurrentBusinessRowsTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Ddocweave.pdf.current.business.cache=/path/to/source-cache \
  -Ddocweave.pdf.current.business.report=/path/to/NEW-native-report.json \
  -Ddocweave.pdf.current.business.model.captures=/path/to/captures \
  -Ddocweave.pdf.current.business.model.report=/path/to/NEW-model-report.json
```

The evaluator validates source bytes, reference hashes, source page/frame, runtime identities
and model strategy. Report paths must be new. Default tests require no external inputs.
`java21.json`, `java25.json` and `identities.json` retain only text-free counts, identities and
geometry. Raw OCR text/HTML stays local. Both runtime reports have equal metrics and parsed capture content; raw JSON
byte hashes differ because object-key serialization order differs. The full builds pass928 tests each, including strict Javadocs and the optional source
evaluation. All36 offline tooling guards, the pinned formatter and diff checks pass.

The first launcher attempt rejected a different cached structure graph by SHA256 before
creating captures. Reusing the intended pinned graph succeeded; no pin was changed.
This is validation of existing functionality and does not warrant a beta release.

Next investigate using model geometry with original native text to avoid raster OCR losses,
with owned subtotal/section/wrapped-label regressions before expanding real-source evidence.
Approximate PDF feature completion remains85–90% as a planning estimate, not extraction accuracy.

The [offline native-text probe](../pdf-native-model-probe/README.md) subsequently reuses this
exact frozen geometry with original native glyphs:59/59 exact row content on these two cases.
That feasibility result does not add a production model/native-text integration or alter these
OCR measurements.
