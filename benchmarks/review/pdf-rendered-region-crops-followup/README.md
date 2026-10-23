# Rendered page-region crops: review follow-up

This follow-up addresses three review findings, and one later sub-pixel sampling finding, against the rendered-crop candidate in
[pdf-rendered-region-crops](../pdf-rendered-region-crops/README.md), and runs one bounded
renderer-agreement investigation. The original candidate reports and schedule are kept unchanged
as history.

## Findings and fixes

### 1. Outward rounding (P2)

**Before:** a fixed `1e-6` was added before `floor` and subtracted before `ceil`. That could move
edges inward. At 72 DPI, a request of 40..40.0000001 was rejected as having no pixels;
39.9999999..41 returned 40..41; and 40..41.0000001 returned 40..41.

**After:** each edge is the exact `BigDecimal` product of the requested double and PDFRenderer's
float scale, floored or ceilinged exactly. Point requests are divided exactly by UserUnit. There is
no decimal tolerance. The three cases now return 40..41, 39..41 and 40..42 on both axes.

The owned tests check, on both axes:

- exact integer edges;
- edges one ULP from an integer;
- narrow requests inside a single pixel;
- partial intersection at the raster edge;
- an empty region beyond the raster, which is still `UNSUPPORTED_OPTION`;
- the reported actual extents;
- the 150 DPI case, still pixels 83..167.

### 2. Retained previous page raster (P2)

**Before:** one PDFRenderer served every page. PDFBox 3.0.8 keeps a strong `pageImage` field, and
`flush()` does not release pixel storage. The previous page raster therefore stayed reachable
while the next page was allocated.

**After:** each planned page is rendered in its own method frame, by a renderer created for that
page alone, with all of that page's requests copied there. Neither the renderer nor the raster is
referenced after the frame ends.

An owned test uses a package-private renderer factory, not a public hook. With five requests over
pages 2, 1, 1, 3, 2, it records exactly three renderers, each with one render call, for pages
0, 1 and 2 in that order. Results keep request order. No GC-timing or RSS assertion is made.

The review probe still reports that PDFBox retains its own last page image; that is PDFBox
behaviour the fix works around. No absolute heap bound is claimed.

### 3. Renderer raster ceiling (P3)

**Before:** a valid 1000-unit page with UserUnit 50 at 72 DPI (50000x50000 pixels), with caller
budgets of 2.5 billion, passed preflight. PDFBox then refused it, and the service reported
`MALFORMED_DOCUMENT`.

**After:** the preflight rejects any raster above `Integer.MAX_VALUE` pixels as `IMAGE_LIMIT`
before rendering, using the same float products and floors as PDFRenderer. Each dimension is
then at most `Integer.MAX_VALUE`, so the `int` casts are safe, and the running total of at most
`maxPages` rasters fits in a `long`. The owned test also renders an ordinary page under the same
large budgets.

### 4. Sub-pixel page extents under `BOX_4X` (P2, found in follow-up review)

**Before:** output and supersampled rasters each use PDFRenderer's `max(floor(extent * scale), 1)`.
The earlier argument that `floor(4x) >= 4 floor(x)` guarantees complete sample blocks ignored
that minimum-one clamp. When a page axis is narrower than one output pixel, the output axis is 1
but the supersampled axis may be only 1, 2 or 3 samples, and the 4x4 block read escaped as an
unchecked `ArrayIndexOutOfBoundsException`. At 72 DPI, 0.5x0.5 and 0.01x10 pages failed under
`BOX_4X`; 1x1 and 2x2 pages and the `RENDERER` default were unaffected.

**After:** each output row reads only the samples that exist in the rendered raster, and absent
samples in a block use the selected background: opaque white for `WHITE`, alpha 0 for
`TRANSPARENT`. Source edge pixels are not repeated, the output grid and coordinates are
unchanged, and no larger page raster is allocated; budgets still charge only the raster PDFBox
actually allocates. The row buffer is sized from the existing samples, so a very short page cannot
allocate more than its rendered width.

The reviewer's reproductions now return 1x1, 1x10, 1x1 and 2x2 crops under both samplings. The
four new owned tests cover:

- half-by-half, half-width, half-height and quarter-by-quarter red pages, with the analytical
  coverages 4/16, 8/16, 8/16 and 1/16 over white and transparent backgrounds;
- a nonunit quarter-width page with UserUnit 2 under all four rotations, where `USER_SPACE` and
  `POINTS` requests return identical PNGs;
- 0.01x10 and 10x0.01 blank pages, with the same crop size as `RENDERER` and pure background;
- a resource preflight in which a page budget of exactly the four rendered samples passes and
  three samples fail with `IMAGE_LIMIT`.

## Unchanged guarantees (rerun on the fixed code)

- **Review probes:** the edge probe now reports the expected intervals and `IMAGE_LIMIT`, and the
  rotation probe passes. That probe covers all eight requests over rotations 0/90/180/270, crop
  origin 30/50, UserUnit 3, and USER_SPACE/POINTS.
- **Transfer launcher:** the original launcher, unchanged, gives a report equal to the original
  candidate report as a parsed JSON tree. That covers the contract gates, all 16 pixel
  rectangles, crop pixel hashes, Poppler 14/16 and all 94 source PNG hashes. The corrected
  rounding changed none of these rectangles, so the cached upstream captures remain valid.
- **Source regressions:** magazine 94 occurrences with 4/4 affine references, plus figures 18/18
  and 24/24, each by default and with group context. All six reports equal the historical
  reports as parsed JSON.

## Renderer-agreement investigation (bounded, opt-in)

**Inspection.** At 8x magnification, region 26 (page 8) shows glyph-shaped edges that are hard in
PDFBox and antialiased in both Poppler and PDFium. PDFBox's default hints do enable antialiasing.
The likely cause is that Java2D applies a non-rectangular `Graphics2D` clip, such as a glyph clip,
without antialiasing. A plus-or-minus one pixel shift scan showed no uniform offset across regions.

**One diagnostic before freezing.** On region 26 alone, 4x box supersampling changed the mean
difference against PDFium from 21.14 to 15.09, and against Poppler from 9.65 to 5.96.

**Frozen policy.** `supersample-policy.json` (SHA-256 `a02b5094...102c`) was written before the
full pass:

- render at exactly 4x the float scale;
- produce each output pixel as the rounded mean of its 4x4 block;
- keep the 1x rectangles unchanged;
- apply it uniformly, with no per-region choice;
- promote it only if the analytical gates pass and no track's agreement count falls.

**Result.** All three contract gates pass. The current column reproduces the review's counts.

| Track (same 16 regions, unchanged MAE ≤ 12) | Current `RENDERER` | `BOX_4X` |
| --- | --- | --- |
| Poppler 26.10.0 PNG | 14/16 | 16/16 |
| Unstructured 0.27.28 / PDFium pre-JPEG PNG | 11/16 | 14/16 |
| Unstructured delivered JPEG | 7/16 | 12/16 |

Every region's difference falls on all three tracks, except region 42. That region rises slightly
on each track (Poppler 7.26 to 7.40, PDFium 6.57 to 6.86, JPEG 8.70 to 8.81) but stays within the
tolerance.

These regions remain outside the tolerance under `BOX_4X`:

- **PDFium:** 26 (15.09) and 41 (16.06).
- **JPEG:** 17 (15.87), 24 (14.90), 26 (29.58) and 41 (20.20).

Complete values are in `supersample-diagnostic.json`.

`box4x-public-java21.json` scores the promoted public option and reproduces the diagnostic
exactly, with identical pixel rectangles. At 150 DPI the 4x A4 raster (about 34.8M pixels)
exceeds the default 16M page budget and is rejected with the typed `IMAGE_LIMIT`. The scoring
therefore uses explicit 40M page and 120M total budgets.

**Promotion.** The policy is an explicit `PdfPageCropOptions.Sampling.BOX_4X`; `RENDERER` stays the
default and is unchanged. These are agreement counts between renderers, none of which is labelled
truth. They are not accuracy figures, completion percentages or a renderer ranking. The remaining
PDFium differences are unexplained; the next distinct hypothesis is image placement and pixel
snapping in PDFium versus PDFBox's exact affine.

## Verification

- **Owned suite:** `PdfPageCropRendererTest` grows from 11 to 19 methods. The first fixes added
  four: rounding, renderer lifecycle, raster ceiling and `BOX_4X` sampling. The last of these
  includes an analytical 25% coverage column, (255,191,191), at clip edge x = 60.25. The sub-pixel
  fix added four more, described above.
- **Focused run:** the crop suite plus the group-context and curved-clip suites pass, 34 tests.
- **Reports after the sub-pixel fix:** the original transfer launcher's default report and the
  public `BOX_4X` report are each equal, as parsed JSON trees, to `transfer-java21.json` and
  `box4x-public-java21.json`. All 32 reused upstream PNG and JPEG reference hashes verify. The
  edge, rotation, alpha-coverage and `BOX_4X` rotation probes pass.
- **Strict doclint:** `javadoc -private -Xdoclint:all -Werror` on all five crop sources passes with
  an empty log.
- **Formatting:** pinned clang-format 23.1.1, applied to touched files only. Four pre-existing
  unrelated formatter findings are left alone.
- **Full build:** after the first three fixes, one Java 21 offline `mvn verify` passed 1,363 tests
  (1,359 plus 4) in 44.9 s. After the sub-pixel fix, one more passes **1,367 tests** (1,363
  plus 4), with zero failures, errors or skips. BUILD SUCCESS in 47.0 s.

## Files

- `supersample-policy.json`: the frozen policy.
- `supersample-diagnostic.json`: per-region current versus `BOX_4X` values for every track.
- `box4x-public-java21.json`: the public-option score.
- `SupersampleTrack.java.txt` and `ScoreBox4xPublic.java.txt`: the exact JVM launchers. The class
  inside `ScoreBox4xPublic.java.txt` is named `PublicBox`.

Binary crops, upstream captures, probe outputs and logs remain outside Git.
