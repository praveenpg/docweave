# Uniform single-line cell OCR evaluation

All20 original PubTabNet train images use the same300DPI render, structure-only pinned
ONNX model, English engine/model, SINGLE_BLOCK PAGE recognition and RETAIN borders as
[the provider-cell baseline](../pdf-provider-cell-ocr/README.md). Only CELL segmentation
changes uniformly to SINGLE_LINE. No source crop/repair, parameter selection by image,
model/detector/orientation change or sample exclusion is introduced. Source/model/engine
hashes and individual positive/negative changes are retained in paired reports/comparison.

Java21 and25 focused corpus evaluations pass and have identical result entries.
Single-line recognition is worse in aggregate:289 exact positional/span/text cells versus
336 with SINGLE_BLOCK,745 matched strict tokens versus791,1825 observed tokens versus1765.
Both emit19 tables/14 complete positional-span topologies/zero fully exact text tables.
Topology dimensions/anchors/positional-span counts are unchanged per emitted table.
Defaults remain SINGLE_BLOCK. This is a negative diagnostic, not an accuracy improvement.
The fixed train examples are not established as held out from either model's training.

Reproduce the provider-cell baseline command with a fresh report path and
`-Ddocweave.pdf.pubtabnet.cellSegmentation=SINGLE_LINE`; keep pageSegmentation=SINGLE_BLOCK,
dpi=300,cellBorders=RETAIN,tableDetection=PROVIDER_LAYOUT_CELLS and the same local
structure graph/engine/model hashes. Runtime performs no downloads or discovery.

Production is unchanged from4b39e94 (870-test full strict-Javadoc verification on bothJVMs).
These paired focused evaluations additionally execute the real engine/model on all20inputs.
