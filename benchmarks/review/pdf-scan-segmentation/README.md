# Uniform scan segmentation comparison

The frozen selection applies all three existing Tesseract page modes to all four previously
selected historical/degraded scans, with both pinned German models:24 complete OCR calls
per JDK. No source, model or mode is dropped and no per-page winner routing is introduced.
Source identities, original references, Unicode policy, geometry associations and exact
whole-scan decoded RGB pixels match the preceding baselines. Training overlap is unknown;
these are development diagnostics, not held-out recognition accuracy.

| Year | Model | Page mode | Associated reference lines | Exact associated text | Missing reference | Extra predictions | Validated whole-page character edits |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1835 | deu | AUTO | 21 | 0 | 1 | 0 | 104 |
| 1835 | deu_latf | AUTO | 21 | 4 | 1 | 0 | 51 |
| 1835 | deu | SINGLE_BLOCK | 22 | 1 | 0 | 2 | 140 |
| 1835 | deu_latf | SINGLE_BLOCK | 22 | 6 | 0 | 2 | 139 |
| 1835 | deu | SPARSE_TEXT | 22 | 1 | 0 | 1 | 106 |
| 1835 | deu_latf | SPARSE_TEXT | 22 | 6 | 0 | 1 | 104 |
| 1879 | deu | AUTO | 49 | 42 | 1 | 1 | — |
| 1879 | deu_latf | AUTO | 49 | 18 | 1 | 1 | — |
| 1879 | deu | SINGLE_BLOCK | 49 | 42 | 1 | 1 | — |
| 1879 | deu_latf | SINGLE_BLOCK | 49 | 18 | 1 | 1 | — |
| 1879 | deu | SPARSE_TEXT | 48 | 34 | 2 | 14 | — |
| 1879 | deu_latf | SPARSE_TEXT | 48 | 15 | 2 | 14 | — |
| 1778 | deu | AUTO | 18 | 0 | 20 | 9 | — |
| 1778 | deu_latf | AUTO | 18 | 4 | 20 | 9 | — |
| 1778 | deu | SINGLE_BLOCK | 8 | 0 | 30 | 32 | — |
| 1778 | deu_latf | SINGLE_BLOCK | 8 | 1 | 30 | 32 | — |
| 1778 | deu | SPARSE_TEXT | 25 | 0 | 13 | 44 | — |
| 1778 | deu_latf | SPARSE_TEXT | 25 | 4 | 13 | 44 | — |
| 1780 | deu | AUTO | 9 | 1 | 1 | 9 | — |
| 1780 | deu_latf | AUTO | 9 | 3 | 1 | 9 | — |
| 1780 | deu | SINGLE_BLOCK | 1 | 0 | 9 | 28 | — |
| 1780 | deu_latf | SINGLE_BLOCK | 1 | 0 | 9 | 28 | — |
| 1780 | deu | SPARSE_TEXT | 9 | 1 | 1 | 40 | — |
| 1780 | deu_latf | SPARSE_TEXT | 9 | 3 | 1 | 40 | — |

Reference line counts are22 (1835),50 (1879),38 (1778) and10 (1780). Whole-page edit
counts have a1059-code-point denominator for1835 only. The other source PAGE files do not
declare complete line order: retain their XML unchanged and report xml_sequence_* distances
as diagnostics, not validated logical reading-order CER/WER. Local reference-line errors
include missing lines but do not charge extra predictions; do not present them as page CER.
Unmatched predictions can include real decoration, manuscript or acquisition material outside
the printed-text labels and are not automatically hallucinations.

There is no uniformly better mode. On the low-contrast1778 page sparse mode increases
associations18 to25, but extra predictions rise9 to44. Single-block mode reduces associations
there to8 and on the stained1780 page9 to1. The1835 Fraktur model gains two exact associated
lines under alternative modes, but validated whole-page edits worsen51 to139(single block)
or104(sparse) because complete output and order matter. On1879 the German model's exact
associated lines fall42 to34 in sparse mode. Automatic remains the unchanged default.

The harness now accepts optional per-profile page_segmentation names, selecting only the
existing TesseractOptions enum values. Absent fields preserve AUTO; explicit invalid modes
fail rather than dropping a profile. Each result records the actual mode, with MIXED_EXPLICIT
at report level for a mixed manifest. No production API, dependency or extraction default
changes. All eight AUTO cases preserve preceding baseline text, geometry and raster metrics
(excluding random derivative-PDF IDs and the newly explicit mode field).

Reproduce with the historical harness properties and this manifest; the cache contains all
four original XML/TIF pairs. OMP_THREAD_LIMIT=1 and explicit trusted executable/model paths
remain required. No reference regions or transcripts enter the OCR provider. All borrowed
scan pixels are retained through the explicit derivative-container protocol.

Next evaluate a separately predeclared explicit adaptive threshold policy on the same scans;
do not switch segmentation or models per page based on these development scores. Preserve
this complete grid as evidence and keep independent line-order review on the return queue.

Both actual Java21/25 full clean builds pass994 tests with24 real OCR calls each. All24
paired text/geometry/raster results agree, and all8 AUTO baseline cases remain unchanged
on each JDK. Strict private Javadocs pass on both. No default or production behavior changes.
