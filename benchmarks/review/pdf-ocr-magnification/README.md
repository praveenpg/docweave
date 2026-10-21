# Explicit OCR-only magnification

PdfOcrProvider.upscaled exposes line capability; PdfWordOcrProvider.upscaled retains actual
provider word geometry. Both accept integer multipliers1through8 with explicit per-call
source/enlarged pixel and raw line/UTF-16 budgets. The shared owned frame uses nearest
neighbor replication, forwards PAGE/CELL purpose, and divides returned coordinates by the
integer ratio. Multiplier1 borrows the original image. Source pixels/payloads stay unchanged;
no token boxes or missing text are inferred. No provider is discovered/executed at construction.

Line/word raw limits and invalid/null output remain typed failures. Per-call budget state
and enlarged rasters are isolated across concurrent calls. Source images are not flushed;
owned target rasters are released even on error. The Java2D draw is bounded by pixel policy
and checked for interruption before/after, not forcibly interrupted within the draw. External
providers retain their own process/memory/timeout policies; this is not a total heap sandbox.

Use distinct PAGE and CELL strategies when only cell recognition should be magnified. Pixel-
sized downstream preprocessing sees enlarged pixels too. For example, the core's12-pixel
white cell padding becomes36pixels at3x, so the managed Tesseract adapter's24-pixel edge band
will contain white padding when REMOVE_EDGE_RULES runs afterward. Cleaning before enlargement
is a different policy; the fixed evaluation here uses RETAIN and does not assume equivalence.

The original PAGE words/model, structure geometry, source image,300DPI render, SINGLE_BLOCK
PAGE/CELL and RETAIN borders remain fixed across all20 published train examples. Only CELL
input is magnified3x. This is negative overall:288 exact positional/span/text cells versus336,
737 matched strict tokens versus791,1965 observed versus1765,19tables and zero entirely exact
tables. Seven inputs gain exact cells, ten regress, three are unchanged. Positive/negative
cases must remain together; training overlap is unknown and no default change is justified.
The development one-cell decimal recovery is not a representative accuracy oracle.

Set `docweave.pdf.pubtabnet.cellScaleFactor=3` with the existing provider-cell local engine/model/
source/cache/fresh-report properties. It requires PROVIDER_LAYOUT_CELLS and records the scale,
independent line strategy and exact underlying CELL model hash. Absent/default1 retains the
shared provider (unless separate cell weights are explicitly selected) and original report fields.
Raw source/model binaries are fetch-only; runtime performs no downloads.

Full actual Java21/25 verify passes884tests each with strict Javadocs and ten installed-engine
families, including new real line/actual-word magnification controls. Java21 runs3x/all20;
its entries exactly match focused Java25. Full Java25 default/unscaled replay reproduces
all20 historical entries, with new scale/independent-strategy fields absent. Per-table
layout dimensions/anchors/positional matches/source-center diagnostics are unchanged.
Tests run from an isolated source snapshot to exclude a concurrent batch refactor;
source-sha256.json identifies the ten matching implementation/test files. No defaults,
historical sources/goldens or budgets are changed. A wrong-directory trial was interrupted
before acceptance; only the source-verified retry is retained here.
