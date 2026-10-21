# Separate PAGE-word and CELL-line strategies

Additive PdfPartitioner.createWithCellOcr/createOrientedWithCellOcr factories accept
independent actual PAGE words, complete layout and line-only cell recognition. Both
require PROVIDER_LAYOUT_CELLS; ignored cell configurations fail before execution.
The hidden orchestrator retains distinct dependencies. It does not wrap a line engine
as a word-capable provider or change word-preserving decorator semantics. Existing
factories retain the original shared recognizer and unchanged metadata/limits.

Four new integration controls exercise routing, blank cells and ordinary outside-word
ownership; all four quarter-turn source parent bounds; shared raw blank-line budgets,
padded-crop preflight and interruption/cause identity; and20 concurrent virtual-thread
documents. The real Tesseract orientation control retains RULED/shared-cell profiles
and adds separate PAGE RETAIN and CELL REMOVE_EDGE_RULES line-only routing at all four
source turns. Each owned serif table must contain all six exact cells, outside marker,
one original Image and restored source bounds. No independent scan-accuracy claim follows.

Full Java21/25 verify passes874tests each with strict Javadocs and ten installed-engine
families. Paired owned orientation observations match except runtime identifiers. Other
installed families are retained by fresh reports alongside /tmp/docweave-separate-cell-full-
Historical verification details remain scoped to the recorded benchmark receipt.
and docs/pdf-support.md. No corpus/model/default/golden changes accompany this feature.
