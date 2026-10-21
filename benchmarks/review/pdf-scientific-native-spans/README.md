# Independent native scientific span/text regression

The unchanged original `us-030.pdf` scientific engineering table matches **43/43 published
positional-span/text anchors** under all six native policies. Each emits the annotated7x7
rectangular topology, including three row-span2 anchors and one column-span4 anchor.
Closed-grid recognition owns the table first, so this is evidence for existing native
merged-grid support, not an additional success of beta.5's partially ruled inference.

## Frozen original source and labels

[The manifest](manifest.json) pins Camelot commit
`92777b852e00933a5c4b9ba642601784604356cd`, the complete original three-page PDF and original
ICDAR2013 region/cell XML files using byte counts, SHA256 and Git blob identities. Camelot's
`tests/test_lattice.py::test_lattice` independently identifies the original page2 scientific
table. The XML supplies43 rectangular anchors covering49 grid positions, with original
row/column indices and cell text. Labels, source/page/region and protocol were fixed before
DocWeave extraction. No source PDF/XML/transcriptions are redistributed; fetch pinned URLs
into a local cache and verify hashes. Discovery also inspected Camelot data/fixture files;
only the three actual PDF/XML inputs enter this evaluation.

This is an older published scientific benchmark document, not a newly authored business
report, physical scan, held-out estimate or comprehensive domain acceptance set. Model
training overlap is not assessed; no model or OCR runs here. The previously unscored case
was selected to broaden native scientific evidence, not to claim a random sample.

## Protocol

Process the **complete original PDF**, then score source page2/region1. Use default native
limits/options except cell-coordinate metadata and each uniformly selected table mode.
No page copying, rasterization, OCR/model, source repair, text-based candidate selection,
reference-ROI extraction or per-document threshold changes occur.

Original region XML gives bottom-origin bounds x125..486,y121..232. The independently
checked792-unit unrotated/origin-zero page converts this to displayed ROI[125,560,486,671].
ROI is only used after extraction: select maximum-parent-IoU>=0.5 with stable first-index
tie-breaking. It never enters the partitioner. Observed parent IoU is0.9269986175417495.
Cell geometry is not compared with individual annotation boxes; exact topology/text does
not establish exact glyph or painted-border bounds.

Subtract common source row/column minima to obtain zero-based anchor keys while preserving
original rowSpan/columnSpan and all labels. Validate nonoverlapping annotation occupancy
and all49 covered grid positions. Compare exact(row,column,rowSpan,columnSpan) keys and
ASCII-whitespace-collapsed strings. Do not change Unicode/case/punctuation/numeric formatting,
fill missing cells, join fragments or repair headers. Count missing and extra keys separately.
The43-anchor denominator is not mixed with older business CSV-position denominators.

## Results and regression coverage

Each of RULED_ONLY, RULED_AND_ALIGNED_TEXT, RULED_AND_MULTILINE_ALIGNED_TEXT,
RULED_AND_DOUBLE_BORDERS, RULED_AND_HORIZONTAL_TEXT and RULED_AND_PARTIAL_TEXT emits one
associated table:43 observed anchors,43 matching keys,43 exact span/text anchors, zero
missing/extra keys and complete topology/text. Existing capability warnings remain; they
are not detected extraction failures. All six profile records agree on actual Java21/25.

The opt-in Java/JUnit validator
[PdfScientificSpanTest](../../../docweave-validation/src/test/java/dev/docweave/validation/PdfScientificSpanTest.java)
checks pinned identities, unique page/region selection, original spans, geometry association,
HTML/metadata anchor correspondence and normalization. It writes all six diagnostic records
before enforcing the complete externally labelled regression. Unexpected extraction errors
fail the run, rather than becoming a successful metric report. A separate owned parser/control
test rejects external XML entities and overlapping reference spans, checks merged origin
conversion/geometry IoU, and preserves Unicode spaces, case and numeric formatting.

Ordinary builds download nothing and require no Python or external cache. Set BOTH explicit
properties to run the original source regression; the report path must be new:

```sh
mvn -B -ntp -pl docweave-validation -am test \
  -Dtest=PdfScientificSpanTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Ddocweave.pdf.scientific.cache=/path/to/cache \
  -Ddocweave.pdf.scientific.report=/path/to/new-scientific-report.json
```

Actual21/25 full `clean verify` with this original-source opt-in passes924 tests each and
strict Javadocs. `java21.json`/`java25.json` retain text-free counts and geometry;
`identities.json` pins validator/protocol/report/log hashes. Raw inputs remain fetch-only.
This is an evidence-only addition: production behavior/APIs/dependencies are unchanged and
no beta.6 functionality release is warranted by this regression alone. Current business,
partial/borderless scientific headers, real scans, held-out and calibration gates remain open.
