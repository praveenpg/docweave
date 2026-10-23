> **Superseded in part by [two attempts per item](attempts/README.md).** That pass added opt-in runtime
> options for P1 (paragraph fragments), P2 (physical-line provider rows) and P5 (printed-rule OCR line
> splitting). The bounded-review findings below are retained as history.

# Parked PDF items P1–P9: bounded investigations (2026-10-22)

Evaluation only, from development HEAD `890629c` (runtime `cf83cb2`). **No runtime, API, default,
reference or historical report changed.** No OCR, ONNX, upstream process, download or external
service ran. Every check reads retained evidence and local originals. Item IDs follow
[pending work](../../../docs/pending-work.md). Where a hypothesis was tested, it was written
down (`freeze-*.md`) before its measurement.

| ID | What was done | Result | Disposition |
|---|---|---|---|
| P1 | Classified every residual reversed pair under `SMALLER_TYPE_BLOCKS_ROW_TIES` by owning output element; computed a lower bound over all whole-element permutations. Simulated one distinct, threshold-free mechanism (side-column continuity). | recent 6 and next 1 are at the bound: no whole-element order can fix them. Holdout 17/18 and eLife 43 are orderable in principle, but continuity breaks 103–139 correct pairs. | Parked: contract incompatibility plus a rejected candidate |
| P2 | Cross-checked DocWeave's current rows (section row + spacer rows) against pinned upstream hi_res row presence on all 110 NVIDIA references. | YOLOX has no row that upstream finds and DocWeave misses. Every TATR row in that situation is an absent or partial detector proposal. | No structural counterexample on current sources; depends on Q1 |
| P3 | Re-read the spacer-applicability census and the upstream membership decisions. | Apple and Amazon are excluded by upstream too; no non-NVIDIA source has the lattice premise or labels. | Optional; parked until an input exists |
| P4 | Tested an intent-based ICC alternative (frozen), pixels only. | The scanner profile's A2B0, A2B1 and A2B2 tables are byte-identical; the tested Java profile-header intent change produced identical pixels. | Rejected; no source-supported alternative remains without Q2 and OCR |
| P5 | Re-read the retained geometry and failure classes; checked the capture schema. | Gap, printed-rule (global and local) and alignment classes are already rejected. Captures hold line and word boxes only, with no engine block data to replay. | Parked; no distinct mechanism without new execution and labels |
| P6 | Froze scanner-border masking (T6); measured it pixel-only and against replayed captures. | Border bands exist on 1835 and 1780. No labelled line touches them, but no retained prediction lies in them either. | Rejected: no saved border predictions to remove |
| P7 | Reviewed the image metadata and crop contracts. | No consumer contract chooses isolated-asset appearance; the decisions needed are listed below. | Future design, not a defect |
| P8 | Probed a combination the tests don't cover (`BOX_4X` × `TRANSPARENT` at partially covered edges) against hand-derived values. | All four edge pixels match the analytic values, including straight (not premultiplied) colour. | No defect; no fix |
| P9 | Located the two cells and dumped the native glyphs. | The source paints a whitespace glyph (U+0009, 1.76 units wide) on **both** sides of each `&`. The saved CSV keeps the leading separator and drops the trailing one. | Reference discrepancy; no extraction change |

## P1: residual scientific order

`ClassifyResidualLineOrder` (in `benchmarks/tools`) reuses the unchanged `CompareUpstreamLineOrder`
population and outcome function. Each located atom is assigned to the output element that holds
it. Classes:

- `INTRA_ELEMENT`: both atoms sit in one element.
- `INTERLEAVED`: the two elements' atoms are expected in both directions.
- `ORDERABLE`: every expectation between the two elements points one way.

The **lower bound** is the intra-element reversals plus, for each element pair, the smaller of
its two direction counts. No permutation of intact elements can do better
(`p1-residual-classes-java21.json`).

| Paper (page 1) | Reversed, row-tie policy | Classes | Whole-element lower bound |
|---|---:|---|---:|
| recent | 6 | 6 interleaved: a 2-line main paragraph and a 6-line side block | 6 |
| next | 1 | 1 interleaved: a 2-line main paragraph and a 1-line side element | 1 |
| holdout | 17 (18 with inline scripts) | all orderable | 0 |
| eLife | 43 | all orderable, between left-margin and main-column elements; none is side-tagged | 0 |

Every column-layout profile gives the same counts.

**recent and next: contract incompatibility, not a missing rule.** The side element begins inside
the second line of a two-line main paragraph (recent: side top 559.7, paragraph 544.2–564.9; next:
side top 530.8, paragraph 515.4–536.1). Upstream reads the side text between that paragraph's
lines. Matching it requires splitting an indivisible paragraph, which the current element contract
forbids. The archived splitting candidate was not revived.

**Holdout and eLife.** These residuals are pairwise orderable, but upstream's order runs against
the page's top-to-bottom geometry:

- On holdout, side block 33 starts at 502.8, below main elements 28–32 (426.5–500.4), yet upstream
  places it before them, right after side block 27.
- On eLife, upstream follows neither row-major nor column-major order. It puts a right-column
  heading (top 427.9) before a left-column block starting at 473.7. Yet it also puts left-column
  items (tops 402.6 and 622.8) before right-column paragraphs that start above them (378.0 and
  480.9).

One distinct candidate with no thresholds was frozen before simulation (`freeze-p1.md`,
addendum A). It keeps barrier-free side blocks consecutive, using the existing barrier test.
`SimulateSideContinuity` gives `p1-side-continuity-simulation-java21.json`, which has preserved
whole elements and main order in every profile:

| Paper | Reversed before → after | Correct → reversed |
|---|---|---:|
| recent | 6 → 139 | 139 |
| next | 1 → 103 | 103 |
| holdout | 17 → 117 (18 → 140 with inline scripts) | 111 (134) |
| eLife | unchanged (no moves) | 0 |

**Rejected.** As frozen, no gap or pitch threshold was added to rescue it; such a threshold would be
fitted to geometry already seen.

**Next prerequisite.** Fixing recent or next needs a separately designed representation that can
place a side element inside a main paragraph (for example, explicit paragraph fragments with
continuation provenance) and a decision on that contract. The holdout and eLife residuals need an
independently supported reading-order signal; reproducing upstream's block grouping is not one.

## P2: business headers and continuation

`p2-upstream-row-crosscheck.txt` compares row presence per reference row. The sources are the
current section-row + spacer-row report (`../pdf-trailing-cell-spacer-rows`) and the pinned upstream
hi_res default report (`../pdf-nvidia-upstream-hires`).

| Profile | Statement | DocWeave | Upstream | Present upstream only |
|---|---|---:|---:|---|
| YOLOX | income / balance / cash | 21 / 27 / 23 | 10 / 20 / 12 | none |
| TATR | income / balance / cash | 10 / 13 / 13 | 10 / 20 / 12 | 2 / 10 / 6, all absent or partial proposals |

Titles and split date-header rows are missed by upstream as well. The parenthesis-spacing rows are
a reference presentation issue (`../pdf-business-parenthesis-diagnostic`). No structural error
backed by both the PDF and pinned upstream remains on current sources. A structure rule cannot
recover an absent detector proposal. **Next:** Q1's labelled financial pages, not another sweep.

## P3: non-NVIDIA trailing rows

There was no new input.

- AMD, FRX and Walmart paint no whitespace cells (`../pdf-trailing-cell-spacer-rows/spacer-applicability.txt`).
- Apple and Amazon rows are excluded upstream, with label and value emitted separately
  (`../pdf-unstructured-alignment`), so DocWeave's current exclusion is compatible.

**Prerequisite:** a naturally truncated table on another producer, with a distinct membership
signal, independent table labels and a transfer document. The signal must be frozen before
validation.

## P4: 1835 page number

The frozen alternative (`freeze-p4.md`) was the relative-colorimetric intent of the same scanner
profile. In `p4-profile-tags.txt`:

- The 1835 and 1778 scans share one profile (`scnr`, Lab PCS).
- Its A2B0, A2B1 and A2B2 tags are byte-identical (221,806 bytes, hash `ffd1a25b…`).

The tested Java conversion path produced identical pixels after changing the profile header from perceptual to relative-colorimetric intent (`p4-intent-probe.txt`). This rejects H4 under its frozen pixel criteria; it does not prove that every CMM configuration or rendering intent must behave identically.
For reference, page-number ink minimum Y is 85 under the managed decode and 63 from stored samples.

The remaining options are already rejected: stored samples (German 104→107, Fraktur 51→63) and
2x magnification (mixed). **Prerequisite:** Q2, an original ICC-bearing scanned PDF with
transcription, plus authorization to run OCR for both pinned models.

## P5: degraded columns

- Gap rules, page and uncrossed gutters, global and local-ink printed rules, and recurring alignment
  (x≈1024, and gap-gated x≈996) are already rejected; see `../pdf-bounded-passes-e-f`,
  `../pdf-ocr-column-alignment-evaluation/review`, `../pdf-post-validation-diagnoses` and
  `../pdf-unstructured-alignment`.
- The reference gaps in the 1778 rows run from 0.52 to 6.97 line heights.
- The retained captures hold only line and word boxes, with no engine block or paragraph
  identifiers. The one untested mechanism class, engine page-layout segmentation, therefore cannot
  be replayed.

Upstream does not split the 1778 list either. **Prerequisite:** an unused, labelled two-column
degraded scan, plus authorization to run pinned OCR with a fixed DPI protocol.

## P6: degraded preprocessing

T6 (`freeze-p6.md`) masks edge bands whose mean luminance falls below the global Otsu threshold.
That defect is independently observable and was untouched by the rejected transforms.

| Scan | Masked fraction | Labelled lines touching mask | Retained predictions in mask |
|---|---:|---:|---:|
| 1835 | 0.285 | 0 | 0 |
| 1879 | 0 | 0 | 0 |
| 1778 | 0 | 0 | 0 |
| 1780 | 0.152 | 0 | 0 |

**Rejected under the frozen rule:** the retained captures contain no border predictions for direct removal. A secondary effect on interior recognition or segmentation was not tested; measuring it would require OCR execution and a separately stated hypothesis. **Next:** reopen only with
a different observed defect that is linked to specific errors.

## P7: isolated image appearance

This is not a defect. Source placement, source PNGs and page-region crops have separate, documented
contracts (`docs/pdf-support.md`). No consumer has chosen an isolated-asset output. A future
contract must first decide:

- isolated source contribution versus final visibility;
- how clips, soft masks (image and graphics state), group alpha and blend, isolated and knockout
  groups, overpaint and repeated invocations are represented;
- vector-only content;
- render budgets.

Each claimed operation needs its own fixture. Until a consumer selects one, it stays a future design.

## P8: crop renderer

`p8-edge-alpha-probe.txt` uses a 20.25-unit red fill (one quarter of an edge pixel covered) and a
20.5-unit blue fill (half covered), at 72 DPI. The hand-derived `BOX_4X` values all match:

| Background | Quarter-covered red edge | Half-covered blue edge |
|---|---|---|
| `WHITE` | `ffffbfbf` | `ff8080ff` |
| `TRANSPARENT` | `40ff0000` (straight colour, not premultiplied) | `800000ff` |

`RENDERER` fills the edge pixel, as PDFBox's own fill does; that is documented policy. Together with
the earlier 280-case frame probe, no defect was reproduced. **No fix.**

## P9: procurement whitespace

`p9-ampersand-probe.txt` covers both ampersands on the selected page. Each is preceded and followed
by a glyph whose Unicode mapping is U+0009 (width 1.76; the font's space width is 4.97), then a word.

The source therefore encodes separators symmetrically. Native extraction and Poppler keep both
sides; the saved CSV keeps the leading one and drops the trailing one. That CSV omission is a
reference-serialization difference, not a whitespace-assembly bug.

The 152/154 result and the original CSV are preserved. If exact CSV parity is ever required, the
reference correction must be additive and versioned, and must come from an independent
source-policy review.

## Reproduction

The JVM tools compile with `javac --release 21 -Xlint:all -Werror -Xdoclint:all/private` against the
validation classpath:

```sh
java -cp "TOOLS:$cp" dev.docweave.validation.ClassifyResidualLineOrder SOURCES CAPTURES \
  benchmarks/review/pdf-scientific-full-body/upstream-manifest.json,benchmarks/review/pdf-scientific-paragraph-holdout/manifest.json \
  COLUMN_PARAGRAPHS+SIDE_STREAMS_ROW_TIES,COLUMN_PARAGRAPHS_COMPOUND_WRAPS+SIDE_STREAMS_ROW_TIES,COLUMN_PARAGRAPHS+INLINE_SCRIPTS+SIDE_STREAMS_ROW_TIES,COLUMN_PARAGRAPHS_COMPOUND_WRAPS+INLINE_SCRIPTS+SIDE_STREAMS_ROW_TIES,COLUMN_PARAGRAPHS+SIDE_STREAMS NEW.json
java -cp "TOOLS:$cp" dev.docweave.validation.SimulateSideContinuity SOURCES CAPTURES NEW.json
```

- `SOURCES` and `CAPTURES` are the private scientific originals and the pinned Unstructured 0.27.28
  `fast` captures.
- Reruns from the formatted final sources reproduce both JSON reports byte for byte.
- The `*.java.txt` probes are standalone diagnostics. Their arguments appear in the private return
  packet, together with input hashes, logs and the text-bearing geometry dumps.

These are development findings on known data. They are not accuracy or held-out results.
