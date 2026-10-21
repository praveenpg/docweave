# Original scan structural evaluation

Official cTDaR commit171934db38a9e230e23b87a5afe86f8c83edaa95 supplies the pinned JPEG/XML
pairs in manifest.json. Source: https://github.com/cndplab-founder/ICDAR2019_cTDaR . Historical
annotations describe cell polygons/areas; modern annotations describe content regions. Neither
selected annotation set supplies cell text transcriptions. No OCR text accuracy or calibration
claim follows, and unlike frames must not be compared as if they were the same bounding-box oracle.
Original scans/full annotations/transcriptions are fetch-only, not redistributed.

PdfPhysicalScanCorpusTest embeds each original whole JPEG without crop/deskew/repair, at dimensions
that target original pixels at150DPI. Shared runtime uses explicit Tesseract5.5.3/English/SINGLE_BLOCK
cell configuration. That model is not a historical handwriting model. It reports published row/column/
span counts, duplicate anchor records, emitted anchor positions/spans, ordinary OCR lines, source
Images and explicit TABLE_LIMIT/OCR_LIMIT failures. Duplicate modern header anchors are preserved
as a diagnostic, not silently repaired. Positional anchor overlap alone cannot establish semantic
structure or OCR text correctness. Fresh report creation and exact byte/SHA256/Git blob pins required.

Initial Java25 diagnostic: s003 hits the explicit table-work limit; s004, s101 and t00040 produce
ordinary OCR with one retained Image and no Table. These are measured recognition gaps, not passed
structure accuracy. Worn/skewed/open rules, spread gutters, historical handwriting and scanned
horizontal/merged-heading layouts are outside the current closed raster-grid subset. The test
executes without turning those observed misses into candidate-generated goldens. Engine/parse
errors outside the explicitly reported resource codes still fail evaluation.

Properties: docweave.pdf.scans.cache (directory holding all eight manifest inputs),
docweave.pdf.scans.report (fresh JSON), docweave.tesseract.executable and
 docweave.tesseract.tessdata. No automatic download or engine execution in ordinary CI. The
validation Maven module now explicitly sets java.awt.headless=true, matching PDF/integration
modules, so image rendering does not require X11. Full runtime/evidence reports are saved after
Java21/25 verification. General scan support remains an open completion gate.


## Fixed all-sample coverage

`-Ddocweave.pdf.scans.coverage=ALL_PUBLISHED_SAMPLES` selects all eight annotated sample
JPG/XML pairs under the same pinned official source revision, plus the previously included
historical test00040. `extended-manifest.json` lists exact hashes/bytes/git blobs for all18
assets. Fetch-only cache files are required; tests perform no downloads. The default
`BASELINE` selection still uses its historical four inputs and manifest. Unknown coverage
values fail rather than silently selecting a different dataset.

The additional five sources are selected by the complete published sample directory,
without choosing positives after inspection. Modern document raster provenance does not
necessarily mean a physical scan; historical page photographs/handwriting and modern page
images remain separate domains. No source provides an OCR transcription oracle here.

Extended reports additionally compare every predicted table with every published table:
source table polygon envelopes are compared to predicted table envelopes in original
source-pixel coordinates, with axis-aligned intersection-over-union. This is table-envelope
IoU, not polygon/cell/text accuracy. Exact row/column dimensions, anchor-set equality,
annotated-anchor subset and positional-span intersections are separate fields. Missing
empty-cell annotations and duplicate records remain unchanged; subset matches do not
establish exact complete topology. The comparison matrix retains multiple/duplicate
reference possibilities instead of hiding them behind a best count or relabeling data.

Use existing explicit detection/structure graph properties and optional orientation flag;
the fixed evaluation profile uses150DPI, default resource/score limits and actual PAGE
word OCR. One oversized source may return OCR_LIMIT before OSD/recognition; it remains a
recorded limit outcome rather than an omitted sample or automatic resource increase.


The extended two-model/OSD150DPI evaluations pass on actual Java21 and25 with identical
result entries:12 candidate tables across9 inputs, one OCR_LIMIT before OSD, eight
uncertain/unchanged orientation decisions. At the highest spatial overlap, three tables
match exact published dimensions and anchor sets: historicals0022x5 envelopeIoU0.82558;
moderns1025x4IoU0.89392 and17x4IoU0.90859. Other tables on those pages and other sources
remain missing/partial. There is no transcription oracle or validated per-cell geometry,
so these are topology/envelope positives rather than fully correct table-text extraction.
See extended-summary.json and paired extended-java21.json/extended-java25.json.
