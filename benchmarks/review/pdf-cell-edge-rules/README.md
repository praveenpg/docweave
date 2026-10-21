# Explicit cell-edge rule preprocessing

The optional managed Tesseract adapter exposes immutable
`CellBorders.RETAIN` (historical default) and explicit `REMOVE_EDGE_RULES`.
Only TABLE_CELL inputs are eligible. Cells at least64x64pixels are scanned in their
first/last24rows and columns; lines with at least80percent dark occupancy are
whitened in an owned same-size RGB copy. Dark is alpha>=128 and RGB sum<384.
No crop/rescale/source-coordinate change or text-dependent parameter selection occurs.
Meaningful edge rules/underlines can be removed; short/weak/skewed rules may remain.
No recognition or confidence guarantee follows from enabling this heuristic.

Owned controls check unchanged source pixels and interior rules, tiny/sparse/transparent
inputs, the exact density boundary, interruption, immutable option-copy behavior and
real Java subprocess PAGE/CELL PNG differences with unchanged actual-word bounds.
The installed engine control uses the same authored serif six-cell grid at all four
quarter turns, with known independent-of-OCR layout coordinates. Both historical
RULED/RETAIN and PROVIDER_LAYOUT_CELLS/REMOVE_EDGE_RULES must extract all six exact
cells, outside marker and one original image with restored source coordinate bounds.
The previous unmodified generic-cell result ITEM COUNT BLUE is retained in the
[orientation manifest](../pdf-orientation/manifest.json), not overwritten as a success.

The published-corpus comparison uses the same20 original train examples, graph hash,
engine/models, whole-image PDF embedding,300DPI and SINGLE_BLOCK PAGE/CELL segmentation
as the previous [provider-cell checkpoint](../pdf-provider-cell-ocr/README.md).
Only explicit `docweave.pdf.pubtabnet.cellBorders=REMOVE_EDGE_RULES` changes.
No detector or orientation estimator is enabled in this corpus profile. Source images,
transcriptions and models remain fetch-only; model-training overlap is unknown.
No per-case parameter tuning, transcription repair, oracle crop or sample exclusion.
Historical RETAIN baseline:19tables,14complete positional-span topologies,336exact
positional-span-text cells, zero entirely exact text tables. Outcomes must retain
positive and negative changes separately; more exact cells do not establish general
or held-out-model accuracy.

Reproduce with the installed executable/tessdata properties, a fresh engine report,
`docweave.tesseract.orientation.enabled=true` for the owned grid checks, and the
PubTabNet cache/report/dpi300/tableDetectionPROVIDER_LAYOUT_CELLS/layoutModel/layoutSha256
properties. Set pageSegmentation and cellSegmentation to SINGLE_BLOCK, and cellBorders
to REMOVE_EDGE_RULES. The runtime does not fetch or discover models/executables.


Full actual Java21 and25 verification passes853 tests each with strict Javadocs,
ten installed-engine families and the complete20-image new-policy profile. Paired
corpus entries and owned observations match across runtimes (runtime identifiers differ).
The fixed corpus tradeoff is334exact cells versus336with RETAIN; matched tokens793
versus791 and observed tokens1781versus1765. Both have19tables/14complete positional-span
topologies/zero exact text tables. Four input entries change; only PMC4003957_018_00.png
changes exact-cell count, from11to9. All changes are retained in comparison.json and
paired reports. The heuristic is useful for the authored ruled-grid control and remains
opt-in; this corpus result is not presented as an accuracy improvement.
