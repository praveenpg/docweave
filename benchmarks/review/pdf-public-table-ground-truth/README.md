# Public Unstructured table ground truth: business tables (PDF area 1, P2/P3)

Status: **qualification-only.** No runtime or default change. Frozen protocol: [PROTOCOL.md](PROTOCOL.md) (written
before any output was observed). Inputs: [manifest.json](manifest.json). Counts: [results-counts.json](results-counts.json).
Tool: `benchmarks/tools/EvaluatePublicTableGroundTruth.java`. Java 21.0.8, development `83329f7` (dev).

## Fixtures

These are the policy-preferred publicly supplied Unstructured examples: `unstructured-eval-metrics` at `b148439`,
Apache-2.0, with cell-level table labels (`uns-table-json-v1.0.0`) and page text. They had never been processed by DocWeave.

- **Positive:** an NVIDIA FY24 Q2 statement page with 3 borderless tables (100 nonblank labelled cells, 20 labelled
  rows). It is a different original from the FY26 Q4 development document; same publisher, so it is a transfer page,
  not a new producer.
- **Negative:** a CISA advisory page. Its table label list is empty, and its only font is a Type 3 font without
  ToUnicode, so its native text is undecodable for every extractor (Poppler alike). It needs OCR, which is out of
  scope here.

## Results

Cells are recalled/labelled nonblank cells; rows are exact labelled rows.

| Profile | Tables (pos.) | Cells | Exact rows | Missed by class | Added | Negative page |
|---|---|---|---|---|---|---|
| native ×6 (`RULED_ONLY` … `RULED_AND_HORIZONTAL_TEXT`) | 0/3 | 0/100 | 0/20 | 100 detector | 0 | 0 tables |
| `yolox-provider` | 3/3 | **94/100** | **19/20** | 6 cell segmentation | 1 (see note) | 0 tables |
| `yolox-established` (leading/trailing/split rows) | 3/3 | 84/100 | 17/20 | 16 cell segmentation | 21 | 0 tables |
| `tatr-provider` | 1/3 | 37/100 | 2/20 | 50 detector, 3 outside, 3 segmentation, 7 other | 5 | **1 false table** (17 cells) |
| `tatr-established` | 1/3 | 32/100 | 1/20 | 50 detector, 3 outside, 8 segmentation, 7 other | 15 | **1 false table** (17 cells) |
| pinned Unstructured 0.27.28 `hi_res` (reference, same scorer) | 3/3 | 37/100 | 0/20 | 9 outside, 53 segmentation, 1 other | 87 | not run |

Upstream ran through the repository's unmodified `capture_unstructured_tables.py` with the established profile and
pins:
- **Validation:** every pin passed (version, model resources, source, Tesseract 5.5.3 `9c19bce3…`, English model
  `daa0c97d…`).
- **Environment:** reinstalled from the recorded package list. The spaCy `en_core_web_sm` wheel is not on PyPI and was
  omitted; the table path did not need it.
- **Why it scores lower:** its table cells come from OCR on the table crop, so decimal points are lost (`248` for
  `2.48`), `$` lands in separate columns and a closing parenthesis is truncated.
- **Scope of the comparison:** one page; this is not a general accuracy comparison.

## Classification

1. **Native heuristics: detector miss (known gap).** None of the six native profiles recognizes these borderless
   statements; the YOLOX layout path is needed. This is consistent with the earlier native multi-page statement result.
2. **`yolox-provider`: one structure error, a model/provider limitation.** The pinned TATR v1.1-all structure model
   labels the two-line row "GAAP net cash provided by operating activities" as a projected row header, like the genuine
   "Diluted net income per share" heading. DocWeave renders it full-width (`colspan=6`), so its five values join the
   label cell.
   - Upstream post-processing would also make a projected-row-header prediction full-width. Upstream's default v1.0
     structure checkpoint simply did not predict one there, so DocWeave's handling is compatible and the cause is the
     model output.
   - The frozen `PROSE_ADDED` rule counts that concatenated row as prose because it fits no single labelled cell. It is
     a merged table row, not prose. This is noted here, not rescored.
3. **Opt-in `RowLines.SPLIT_ALIGNED_TWO_LINE_ROWS` depends on the reference convention.**
   - It splits the two-line date headers ("July 30," over "2023") into two rows. Unstructured's labels keep "July 30,
     2023" as one cell, which costs 10 cells and 2 exact rows here (all 20 extra additions are those fragments).
   - The option's earlier gain was measured against publisher HTML references that split those headers.
   - The leading- and trailing-row options changed nothing on this page.
   - Recommendation: do not enable it when Unstructured-compatible header cells are wanted. It remains opt-in, and the
     default is unchanged.
4. **TATR detection: model limitation.** It misses 2 of 3 statements and proposes a false table on the prose-only
   negative page; YOLOX proposes none. This matches the earlier DocLayNet pilot (TATR 8/18/11 TP/FP/FN).
5. **Continuation: not measurable.** Both labelled fixtures are single pages. Cross-page continuation needs a
   multi-page labelled business document (Q1).

## Candidate decision (bounded policy)

No candidate was implemented.

- **Guard considered:** split a full-row provider span when native numeric glyphs fall in two or more provider column
  bands. It would target the one remaining `yolox-provider` row.
- **Why not now:** the evidence is a single row on one page, there is no negative control containing a genuine numeric
  section heading, and the provider's spans are applied before source text is known.
- **Return condition:** a second independently labelled instance of a projected-row-header absorbing value cells,
  plus a control with a legitimate full-width heading that contains numbers.

## Reproduce

Use Java 21 and the `docweave-validation` test classpath (`mvn -pl docweave-validation dependency:build-classpath
-Dmdep.includeScope=test`). Arguments:

```sh
java -cp "$CP" benchmarks/tools/EvaluatePublicTableGroundTruth.java benchmarks/review/pdf-public-table-ground-truth/manifest.json \
  <unstructured-eval-metrics>/simple-data <dir with yolox_l0.05.onnx, detection.onnx, structure.onnx> <NEW report.json>
# upstream: run capture_unstructured_tables.py with a one-case manifest, then
java -cp "$CP" benchmarks/tools/EvaluatePublicTableGroundTruth.java <manifest> <simple-data> <models> <NEW report.json> \
  --upstream nvidia-p006=<capture>/nvidia-p006.json
```

Full local reports (with page text) are identified by SHA-256 in `results-counts.json`.
