# Rectangular merged scanned tables

The explicit `PdfOcrOptions.TableDetection.RULED` strategy now recognizes rectangular merged anchors
inside closed dark axis-aligned raster grids. It reuses the existing bounded native topology parser;
no model, dependency, automatic OCR registration or public constructor was added. Default table
recognition remains NONE. Open/partial/nonrectangular or ambiguous grids retain ordinary OCR.

The source must expose at least two atomic rows and columns with intervals >=32×24 rendered pixels.
OCR runs once for each anchor over its full row/column extent, with the existing five-pixel border inset
and twelve-pixel white padding. Covered atomic slots produce no duplicate calls or placeholder text.
Blank anchors remain empty td elements; HTML emits rowspan/colspan only for spans >1. Multiline text
joins in provider order, with escaped HTML and shared raw-output budgets. Accepted merged metadata is
`pdf_table_method=scanned_ruled_grid_with_spans_v1`; unmerged metadata remains
`scanned_complete_ruled_grid_v1`. Neither carries per-cell coordinates or semantic header inference.

Grid matrices retain the existing preallocation cap: `min(tableLimits.maxCells, maxTableCells)` atomic
slots per grid. Across eligible grids, actual anchor OCR calls share maxTableCells. A 3×3 grid with
six anchors therefore still requires maxTableCells>=9. Native dimensions/field policies, raw page
line/character limits including joining spaces, per-cell raster limits, fixed run/pixel/work ceilings,
cancellation, mixed/native ownership and table whiteout remain enforced. All-blank recognition leaves
the source raster intact for ordinary OCR.

Raster strokes have painted caps that extend past the junction center. The first real-engine control
exposed that these protrusions incorrectly appeared to be partial dividers. The raster detector now
trims caps inward to observed perpendicular centerlines by <=4 pixels; it never extends across gaps.
Comparison work shares the fixed 2-million-operation budget. Short attached remnants on missing
interior dividers are also rejected: eight uninterrupted dark samples after a five-pixel junction
allowance. At most three centerline-neighbor pixels are read per sample, under the same work budget.
This is conservative geometry: text touching a junction can cause fallback, while smaller/fainter/
detached remnants or an entirely missing damaged edge can still resemble an intentional merge.
There is no deskew, adaptive threshold, borderless scan recognition or document-intent recovery.

Seven new owned PDF tests cover horizontal, vertical and combined spans; full-interior colored pixel
markers; empty anchors, multiline text, escaping and leading zeros; accepted rule thicknesses1/2/3/8;
short stubs, L-shapes and open borders; a quarter-turn table box; native/mixed ownership; prospective
atomic and shared raw/field budgets; blank recognition fallback; and four concurrent virtual-thread
partitions. The previous erased-divider control now correctly recognizes a colspan alongside its
second independent table. Genuine partial-divider controls remain rejected. Integration checks run
both unmerged and rowspan scanned tables through JSON and structural TableChunk splitting, with no
lost or repeated text.

`java21-merged-control.xml` and `java25-merged-control.xml` run actual Tesseract5.5.3/English with
explicit SINGLE_BLOCK on an owned image-only 3×3 grid: one multiline2×2 merged anchor, five other
cells, and raster prose. Both match exact expected HTML/text once and retain the source Image.
These controls use owned drawing/font expectations, not independent merged physical scans. Existing
installed textless/mixed/unmerged/blank-cell controls also pass in the full runs. Runtime/model package
provenance remains [the earlier record](../pdf-ocr/runtime-and-packages.json); no executable/model/PDF
binaries are committed or automatically downloaded.

`java21-corpus-recheck.json` and `java25-corpus-recheck.json` replay the previous pinned independent
Schools 6×10 crop at300DPI/SINGLE_BLOCK, retaining59/60 cell matches and the same spacing mismatch
at index42. AnimalSounds remains ordinary OCR with no Table. The existing manifest and source rights/
expectation boundaries are [unchanged](../pdf-table-corpus/README.md). These unmerged inputs are
regression evidence, not independent merged-table accuracy, approved physical-scan transcriptions,
human reading-order fidelity or Unstructured parity. Historical reports/goldens are preserved.

Full verification on actual Java21 and25 passed719 Java tests with zero failures/errors/skips, strict
Javadocs,12 reference-tool guards, the pinned formatter and diff check.

Replay using the existing executable/tessdata/fresh-report properties in the adapter guide; the new
optional installed control writes `<report>.merged-tables.xml`. The independent recheck uses the
preceding corpus cache/report/DPI/segmentation properties. Report paths must be fresh. Ordinary
hosted CI runs owned PDF/subprocess controls without a real OCR engine or external corpus. No Windows
VM is needed. Temporary caches and runtime installations must be recreated when unavailable.

Official upstream refresh remains release/main0.27.28; known0.27.27 historical reference drift stays
explicit in upstream-status.json, with no automatic pin/golden rewrite. Next recommended milestone:
per-cell geometry metadata for native and OCR tables, using documented page/pixel coordinate frames
and spans. Broader independently reviewed merged/noisy physical scans remain required. Approximate
historical PDF feature-plan completion: **70%**, a planning estimate rather than extraction accuracy or spend.
