# Remaining PDF quality diagnoses (2026-10-22)

This records one bounded investigation per open area that did **not** produce a promoted runtime
change: scientific reading order, historical OCR recognition, degraded-scan columns and
rendered-crop residuals. Each investigation tested a single hypothesis with one focused
validation. Every hypothesis was frozen before its decisive run.

No runtime option, default, reference, threshold or score changed. The only code change here is a
diagnostic, opt-in decode property in the historical test harness, described below. Probe sources
are kept as `.java.txt` files; text-bearing outputs stay private.

## 1. Scientific reading order: blocked on reviewed input

**Baseline reproduced.** The frozen four-profile replay of all three PLOS/eLife papers gives:

- 58/143 exact paragraphs for lines and column paragraphs;
- 59/143 for compound columns;
- 108/143 assembled;
- 3,679/4,942 pairs preserved for assembled output;
- 0 reversed pairs.

The result arrays equal `pdf-scientific-diagnosis/baseline-java21.json` exactly.

**Trace.** `ProbeScientificMisses.java.txt` prints the first divergence of every missing
paragraph against the exact JATS text. The scorer requires a paragraph's exact text to appear
once in the joined output, so paragraph splits do not matter but interleaving does.

- **Assembled profile (10 misses):**
  - four explicit-space `self- ` hyphens, the documented source ambiguity;
  - five displaced superscripts, covered by the existing opt-in `NEARBY_SMALL_GLYPHS`;
  - one retained `improve- ment` break.

  No sidebar interleave remains in tagged PLOS output.
- **eLife (16 misses, native only):**
  - 10 diverge first at a source NBSP (`et al.`, `Figure 1`, `95% CI`);
  - 4 at physical line-end hyphens;
  - 1 at an explicit-space hyphen;
  - 1 at a page-1 sidebar interleave (paragraph #1). That paragraph also runs across the page-1
    running footer to page 2, a separately parked furniture class.
- **Native compound columns, PLOS:** most misses are retained physical line-end hyphens. The
  native path keeps them by design. Sidebar interleaves affect the abstracts, but recent PLOS
  #2 and #3 and next PLOS #2 would still fail at a retained hyphen at the same break. Recent
  PLOS #1 and next PLOS #1 and #3 diverge directly at interleaved first-page text. They are the
  only paragraphs where an ordering fix could register on this metric, and only if no later
  presentation difference remains in them.

**Disposition: blocked.** This explains why the two earlier sidebar algorithms gained nothing.
The brief requires independently reviewed page-1 reading order before a third grouping
algorithm, and that review is unavailable; this pass does not invent one.

Missing input: a human-reviewed line order for the three page-1 sidebars, as specified in
question 1 of the packaged-study review packet. An exact-paragraph improvement could then come
only from the three directly interleaved abstracts. A line-order metric would be needed to show
any other ordering correction.

## 2. Historical OCR: the 1835 page number is a decode effect

**Baseline reproduced.** All four observations under the frozen settings give 1835 German
104/1059 and Fraktur 51/1059 character edits, with 21/22 lines associated. The default rerun
equals the baseline in every metric and hash except the container PDF bytes, which are not
byte-deterministic across runs. Render-RGB equality is the guarantee.

**Trace.** The missed line is the isolated page number "19" (reference box 645,320–689,356).

1. The pinned engine, run directly on the source TIFF, recognizes it at exactly that box with
   confidence 96, under both models.
2. DocWeave's TSV parser drops nothing. The difference is in the pixels: the 1835 TIFF embeds a
   scanner ICC profile (`OS12000_mG`, perceptual intent).
3. Java's ImageIO decodes the TIFF through that profile into sRGB, and the frozen harness
   deliberately copies those color-managed values into its container. The conversion lightens
   the page-number ink: darkest value 54 → 71, mean 138 → 160. 6.1% of all page pixels differ.
4. On the converted pixels the engine misses "19". On the stored samples, DocWeave's own wrapper
   returns it at the reference box under both models (`historical-1835-decode-probe.txt`).

The 1778 page shares the same profile, 1780 carries Adobe RGB, and 1879 is untagged gray.

**Hypothesis and validation.** The hypothesis was that OCR of the stored samples, ignoring the
profile, recovers the line without harming recognition. It was tested through a new opt-in
harness property, `docweave.pdf.historical.stored.samples=true`, which applies only to
non-sRGB, profile-tagged three-band rasters. Untagged and gray sources keep the default decode
byte for byte. An owned unit test covers both branches.

| Observation | Associated lines | Joint line errors | Character edits |
| --- | --- | --- | --- |
| 1835 German | 21 → **22**/22 | 103 → 107 | 104 → 107 |
| 1835 Fraktur | 21 → **22**/22 | 50 → 63 | 51 → 63 |
| 1879 German (control, untagged) | 49 → 49 | 14 → 14 | identical text hash |
| 1879 Fraktur (control, untagged) | 49 → 49 | 60 → 60 | identical text hash |

(`historical-stored-samples-java21.json`)

**Disposition: parked.** Stored samples recover the page number but worsen body recognition for
both models. Fraktur worsens most, 51 → 63 edits. This is not a promotable change.

The page-number miss is a source color-interpretation effect, not a DocWeave recognition,
filtering or association defect. Whether the evaluation protocol should OCR stored samples or
color-managed pixels is a protocol decision for review. The default protocol is unchanged.

Next distinct action: decide that protocol question, together with question 3 of the review
packet (page-number scope). Any runtime handling of ICC-tagged scans belongs at the
PDF-image-color-space level, and should be evaluated on real scanned PDFs rather than on these
derivative containers.

## 3. Degraded-scan columns: uncrossed-gutter criterion parked

**Frozen criterion.** `degraded-gutter-criterion-frozen.txt` (SHA-256 `8ead1730…19f2`) was frozen
before any output was inspected. It is a different design from the rejected page-gutter
attempt.

A gutter x is accepted only if at least three Tesseract lines have a gap of at least the median
word height containing x, **and no recognized word anywhere in those lines' vertical extent
crosses x**. That crossing requirement is the evidence against words crossing a proposed gutter.

**Diagnostic, all 8 source/model observations** (`degraded-gutter-diagnostic.txt`):

| Source | Candidates | Rejected by crossing words | Accepted |
| --- | ---: | ---: | --- |
| 1879 German / Fraktur | 77 / 76 | 77 / 76 | none |
| 1835, both models | 0 | 0 | none |
| 1780, both models | 0 | 0 | none |
| 1778, both models | 24 | 23 | x = 1023, support 4 |

The single-column negatives hold: the crossing requirement removes every 1879 candidate,
the class that sank the earlier attempt. But on 1778 the accepted x lies inside the right column,
between the leading ditto dashes and the names. It is not the column boundary.

The real boundary is the printed vertical rule at about x = 964, and it is rejected for two
reasons (`degraded-gutter-rule-crossings.txt`):

- OCR reads the rule as a one-character token in the list region: German at y = 351, Fraktur at
  y = 611.
- The supporting lines' extent reaches into the single-column prose below the list.

**Disposition: parked before implementation.** Splitting at x = 1023 would produce within-row
oversplits, so no production option was written.

Next distinct hypothesis: use the printed vertical rule itself as the column signal. That means
pixel evidence of a long vertical ink line, plus treating one-character tokens aligned with it as
separators. The vertical extent would be limited to the ruled region.

## 4. Rendered-crop residuals: legitimate renderer policy differences

**Frozen control.** `crop-placement-control-frozen.txt` (SHA-256 `0f3cfddd…eecb2`) was frozen
before rendering. One amendment, made before any rendering, corrected the glyph scan window.

The control page has three red components, each with fractional placement and non-integer
scale:

- an 8×8 image;
- a vector rectangle;
- an image drawn under a 60-point text clip.

It was rendered at 150 DPI by four renderers:

- PDFBox `RENDERER` and `BOX_4X`, through the public crop service;
- Poppler `pdftoppm` 26.10.0;
- PDFium, through Unstructured 0.27.28's own `convert_pdf_to_image`, run offline with no model.

**Results** (`crop-placement-control-analysis.txt`):

| Effect | Predicted coverage | PDFBox `RENDERER` | PDFBox `BOX_4X` | Poppler | PDFium |
| --- | --- | --- | --- | --- | --- |
| Page raster size | – | 416×416 | 416×416 | 417×417 | 417×417 |
| Image right-edge pixel | 0.333 | 0 | 0.251 | 1 | 1 |
| Image bottom-edge pixel | 0.208 | 0 | 0.251 | 1 | 1 |
| Rectangle left-edge pixel | 0.375 | 1 | 0.498 | 0 | 0 |
| Rectangle exact right edge (x = 320) | 0 | 0 | 0 | 0 | 1 |
| Glyph-clip partially covered pixels | – | 0 | 433 | 418 | 469 |

Three distinct effects separate cleanly:

- **Page grid.** PDFBox floors the page raster; Poppler and PDFium use the next pixel, so their
  scales differ slightly.
- **Placement.** Poppler and PDFium snap axis-aligned image bounds outward to whole pixels.
  PDFium also snaps rectangle fills and extends an exact edge by a pixel. PDFBox gives fractional
  coverage under `BOX_4X`, and snaps the other way under `RENDERER`.
- **Edge smoothing.** PDFium and Poppler antialias glyph-clip edges. `BOX_4X` approximates that
  (433 vs 469 partial pixels); `RENDERER` does not (0).

JPEG delivery is a further, separate layer on the delivered track.

**Disposition: diagnosis returned, no runtime change.** The remaining PDFium residuals (26 and
41), plus the extra JPEG-track residuals (17 and 24), are consistent with legitimate
pixel-snapping and page-grid policies. Region 41 is a vector and text logo, where rectangle and
edge snapping apply. Region 26 is dominated by glyph-clip edges.

Emulating PDFium's snapping would be a renderer-specific coordinate bias. The brief rules that
out solely to raise this development score. `BOX_4X` agreement and the default are unchanged.
The control's PDF and PNGs are synthetic and kept outside Git; their hashes are in
`crop-placement-control-identities.sha256`.
