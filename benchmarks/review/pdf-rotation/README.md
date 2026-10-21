# PDF rotation and coordinate-frame review

The native strategy now accepts quarter-turn pages and text. These owned controls and independently
maintained PDFBox samples validate a bounded contract, not general PDF/OCR/layout or human parity.
No third-party PDF, saved text or full extraction is committed.

## Independent inputs

The manifest pins PDFBox3.0.8 commit9286e47d89d6877005c9d2d0f2fd38793a62519a, with source URLs,
bytes, Git blob SHA1 and SHA256 for two regression PDFs and their saved text expectations. The
commit identity and project notice are linked in the earlier pdf-reading review. Original author,
application and per-file redistribution provenance are not asserted. Downloads remain cache-only.

| Sample | Pages with native output | LINES elements | PARAGRAPHS / COLUMN_PARAGRAPHS elements |
| --- | ---: | ---: | ---: |
| PDFBOX-2984-rotations.pdf | 6 | 12 | 12 |
| rotation.pdf | 2 | 4 | 2 |

All three modes match each sample's saved PDFBox text order after Unicode whitespace/BOM removal.
JSON round-trip preserves the emitted metadata and text; layout modes preserve baseline character
inventory. The first sample covers90/180/270degree page rotation and text-matrix/CTM variations;
the second includes a normal page and90degree landscape page. The report retains displayed page
dimensions and normalized rotation. This narrow result does not validate arbitrary-angle text,
font outlines, clipping, tagged/bead reading order or physical pixel-coordinate accuracy.

The earlier poems-beads/crop-box samples were replayed against the final code in reading-recheck.json.
They retain84/45/40elements by mode and their previous character-inventory preservation, ligature
representation and saved article-order differences. Historical pdf-reading/review.json remains unchanged.

## Owned geometry controls

Eight public-facade tests cover the following, with constant displayed expectations:

- Page rotations0/90/180/270, negative/repeated rotations and a large valid integer angle. Text,
  ruled cells, images and page dimensions share one crop-relative displayed frame. An offscreen
  PDFBox renderer independently checks a constant red image location/dimensions. This is a second
  renderer code path, not a claim of cross-vendor rendering or human review.
- Crop offsets, inherited rotation and UserUnit2 metadata, without multiplying source coordinates.
- All16page/text quarter-turn combinations and separate mixed-orientation runs, including orientation
  metadata and approximate bounds. Sideways glyphs belong to their ruled cell once.
- Source rotation that cancels page rotation still supports ordinary paragraphs/table ownership.
  Sideways text inside an aligned numeric candidate prevents its heuristic acceptance and survives
  as ordinary text with the same default elements.
- Non-quarter-turn/skewed/mirrored text, invalid page angles and nonpositive UserUnit fail explicitly. The
  earlier unsupported-page fixture now uses45degrees;90degrees is implemented.

The PDF module test JVM sets java.awt.headless=true for offscreen controls. Production does not
alter the caller's desktop/headless settings and does not render these controls or require a VM.

## Reproduction

Fetch the four pinned manifest URLs into a cache using the recorded filenames and verify hashes.
Choose fresh report destinations; the Java test revalidates the bytes and snapshots binary input:

```sh
mvn -B -ntp -pl docweave-validation -am test \
  -Dtest=PdfRotationCorpusTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Ddocweave.pdf.rotation.cache=/tmp/docweave-pdf-rotation-corpus \
  -Ddocweave.pdf.rotation.report=/tmp/fresh-pdf-rotation.json
```

Both properties are required together. The fresh report is written before native-inventory/JSON
assertions. Saved text comparisons are diagnostic, distinct from required source-preservation checks;
this recorded run matches them, but this is not an automatic guarantee for future cases. Source and
runtime-class/JAR hashes identify the actual implementation. Default CI never downloads/replays the
external cache; owned controls run normally. /tmp caches and logs are temporary.

Coordinates remain unscaled PDFUserSpace, with a top-left displayed crop origin. Width/height swap
on quarter-turn pages. pdf_user_unit converts these units to physical points; no implicit pixel/DPI
scaling occurs. Rotation/crop metadata is explicit where non-default. Axis-aligned bounding boxes
are approximate, not complete oriented glyph/image transforms. See docs/pdf-support.md.
