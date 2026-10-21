# Opt-in native/OCR table cell coordinates

`PdfOptions.withTableCellCoordinates(true)` enables version1 `pdf_table_cells` metadata. The previous
seven/eight/nine-argument constructors default false; other option copies preserve the flag. Public
service APIs remain interfaces, the immutable record remains the configuration value, and the shared
PdfTableCells writer is hidden. Default extraction and cell-recognition policies remain unchanged.

The envelope contains geometry (`rule_centerlines` or `text_bounds`), displayed row/column dimensions
and row-major anchors. Each anchor has zero-based indexes, positive spans and the same PDFUserSpace
coordinate format as its parent. Blank anchors are included, covered slots omitted, and no text is
copied. Native ruled cells use complete border-centerline extents; aligned cells use actual text-run
bounds. OCR uses actual displayed-page/raster ratios rather than nominal DPI, original source image
pixels or inset/padded crop dimensions. Crop/rotation/UserUnit are not applied twice. Coordinate
precision inherits rule detection, text metrics and existing clipping/visibility limitations.

Five owned PDF tests independently check explicit source-rule rectangles at all quarter-turns with
nonzero crop/UserUnit2; a blank merged-table cell; aligned-text indexes and gaps; OCR at137DPI with
nonintegral page sizes and integer/half-pixel raster-center consistency; old constructors/copy/default
output/limits; and concurrent native reuse. Integration controls enable geometry for native and OCR
merged/unmerged tables, check exact JSON round-trip, whole-table/pass-through retention, structural
splits, text-only fallback, original-element provenance and provenance-disabled removal. Whole-table
geometry is deliberately not copied onto fragments. Generation shares the existing table/work limits
and checks cancellation through the same counter, with one operation per anchor.

Local real Tesseract5.5.3/English SINGLE_BLOCK controls on Java21/25 retain exact owned merged text,
span HTML, one source image and ordinary prose, and now verify six anchor descriptions and the known
2×2 merged source-rule rectangle in displayed PDF units. `*-merged-control.xml` records the checks.
These controls use an owned raster, not independently transcribed physical scans or human-reviewed
bounding boxes. Model/native package provenance is retained in the earlier pdf-ocr runtime record.

`*-corpus-recheck.json` replays the pinned independent Tabula Schools crop at300DPI/SINGLE_BLOCK with
the metadata option enabled, checking its6×10 shape/60 one-slot anchor descriptions and coordinate
system while retaining59/60 text matches and the same index42 spacing mismatch. AnimalSounds remains
ordinary OCR/no Table. Manifest, source rights, fixed crop and published-expectation limitations remain
[unchanged](../pdf-table-corpus/README.md). No third-party full text/binary/model, golden rewrite,
independent cell-bound accuracy score or Unstructured PDF execution is claimed. Corpus geometry
checks assert structure/schema, not independent bounding-box ground truth.

The installed controls use existing explicit executable/tessdata/fresh-report properties and emit
`<report>.merged-tables.xml`. The independent cache/report/DPI/segmentation properties remain the same;
new evidence paths must be fresh. Ordinary hosted CI runs deterministic owned controls without real
OCR or external-corpus downloads. Existing installed textless/mixed/unmerged/blank controls were also
replayed locally. Temporary caches are not part of the reproduction artifact set.

Official upstream observation remains release/main0.27.28 with the historical0.27.27 freshness drift
explicit in upstream-status.json, without repinning historical expectations. Full verification passed 724 Java tests on Java 21 and 25, zero failures/errors/skips, strict Javadocs,
12 reference-tool guards, pinned formatting and diff checks. Next recommended bounded
milestone: conservative multi-page table continuation links that preserve page-local tables and their
geometry. Broader human-reviewed merged/noisy physical-scan evidence remains pending. Approximate full
PDF completion: **73%**, a planning estimate rather than extraction accuracy or spend.
