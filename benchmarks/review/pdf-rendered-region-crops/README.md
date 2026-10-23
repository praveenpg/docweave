# Explicit rendered page-region crops

## Gap and contract

Source-image extraction returns each image's own decoded pixels, together with placement, clip
and group metadata. It does not return the final page appearance: an opaque red PNG inside a
group painted at alpha 0 stays red, and clip metadata gives bounds, not clipped pixels. The
[known-gap comparison](../pdf-known-gap-upstream-comparison/README.md) recorded this against
the Unstructured 0.27.28 crop helper. That helper renders the page and crops authored regions.

`PdfPageCropRenderer` adds that as a separate, explicitly requested derivative. Its value types are
`PdfPageCropOptions`, `PdfPageCropRequest` and `PdfPageCrop`, and the implementation is hidden. Each
distinct page is rendered once with PDFBox, and every request on that page is copied out as a
lossless PNG. Partition output, source-image counts and order, PNG payloads, coordinates and
clip/group metadata are unchanged. No element gains a field, and no source occurrence is
filtered or classified by appearance. A crop is the final page rectangle, so it can contain text,
background, neighboring paint and later overpainting. It is not an isolated source image or a
visibility verdict.

## Frozen controls (public API)

The original `image-contract.pdf` (SHA-256 `caf9ffba...c437`) has three pages, each with an 8x8 opaque
red raster placed at 40,40 with size 40x40. The requests are left 40, top 40, right 80, bottom 80,
rendered at 72 DPI. The gates and sample points are the existing JVM upstream-crop checks.

| Control | Source PNG (unchanged) | DocWeave crop (5,20) / (35,20) | Captured upstream crop |
| --- | --- | --- | --- |
| Group alpha 0 | Red | (255,255,255) / (255,255,255) | (255,255,255) / (255,255,255) |
| Group alpha 0.5 | Red | (255,127,127) / (255,127,127) | (254,128,129) / (254,128,129) |
| Half-width clip | Red | (255,0,0) / (255,255,255) | (254,0,0) / (255,255,255) |

All crops are 40x40 pixels and all gates pass. The three source PNGs keep SHA-256 `fc413db8...b27`,
and source elements are equal before and after rendering. The upstream values are its JPEG output;
DocWeave's are lossless, so no byte equality is implied.

## Owned controls (`PdfPageCropRendererTest`, 11 methods)

Expected values are analytical, with a declared tolerance of ±3 per channel. Samples skip
boundary pixels.

- The frozen controls above are re-authored in the test, including the frozen source-PNG hash.
- A multicolor quadrant source inside an ordinary form, two nested transparency groups and a
  clip. Each quadrant appears in its own position, and the clipped strip is white.
- Later black overpainting is visible in the crop. Both source occurrences are still reported.
  Three requests in mixed order cover two pages. A total-render budget of exactly two page
  rasters passes, and one pixel less fails, so each shared page is rendered only once. Results
  keep request order.
- Crop-box origin (30,50) with `/Rotate 90`: the partitioner's own displayed image box, used as
  the request, returns the clockwise-rotated quadrants in their displayed positions.
- UserUnit 2: the raster is twice the user-space size. `USER_SPACE` and `POINTS` requests select
  identical pixels and report their extent in their own units.
- At 150 DPI, edges round outward: 40..80 becomes pixels 83..167. Page rasters follow PDFRenderer's
  float floors (200x120 gives 416x249, not 250). A partial request is intersected and flagged
  `clipped`. Out-of-page regions and missing pages fail with `UNSUPPORTED_OPTION`.
- Malformed requests and options, null or empty request lists, text sources, oversized input and
  malformed PDFs are rejected with the appropriate exception or typed code.
- Request, page, page-pixel, total-pixel, crop-pixel and encoded-byte limits fail with
  `IMAGE_LIMIT`. A 14,400-unit square page at 600 DPI is rejected from geometry before any raster
  is allocated.
- Pre-interrupted calls fail with `InterruptedIOException` and keep the interrupt flag.
- A file source is snapshotted per call. Open descriptors stay bounded across 40 successful and
  failing calls.
- Crops are content-immutable, with defensive copies in and out and content-based
  equality/hash. `toString` omits binary data. Results are deterministic and the list is
  unmodifiable. A `TRANSPARENT` background keeps alpha 0 for a hidden group and about 128 for
  alpha 0.5.
- Encrypted sources require the explicit `withPasswords` lookup.

## Magazine transfer (frozen before measurement)

`transfer-schedule.json` (SHA-256 `dcc60bf3...bacdc`) was written before any magazine crop was
rendered. It covers the original 28-page magazine (SHA-256 `fcbbb6b3...3497`) and selects two
populations:

- every occurrence with a nonempty group context;
- every occurrence whose clip bounds change under curved-clip envelopes.

That selected 5 group-context and 11 curved-clip occurrences, matching the documented
populations: 16 regions on pages 8, 11 and 20. They were rendered in one call at 150 DPI with
default limits. Each request is the occurrence's displayed `coordinates` box. Poppler `pdftoppm`
26.10.0 independently rendered the same pages and pixel rectangles. The agreement tolerance was
frozen at a mean absolute channel difference ≤ 12.

- All 16 regions rendered, with none failed. Every crop matched its expected pixel rectangle.
- **14/16 agree with Poppler** (differences 3.5 to 10.5). These include occurrence 31: a soft-masked
  image in a group painted at alpha 0.57 (difference 3.47).
- Occurrences 40 (12.04) and 41 (22.45) on page 11 are outside the tolerance. Both are small
  vector/text logos inside Multiply-blend groups. Their mean colors agree within about 5 levels, and
  the differences are concentrated on antialiased glyph and shape edges. They are retained as
  failures; the tolerance was not changed.
- All 94 source PNG payload hashes are identical before and after cropping.

Complete per-region values are in `transfer-java21.json`. Binary crops, Poppler output and
comparison images are kept outside the repository.

The source regressions on the candidate build pass:

- Magazine: 94 occurrences and 4/4 affine references, both by default and with group context.
- Qualified figures: 18/18 and 24/24 slots, both by default and with group context.

All six reports are canonically identical (key-sorted JSON) to the retained historical reports
in `pdf-curved-clip-envelopes` and `pdf-image-group-context`.

## Verification

Before the full build, the focused image suites passed on the candidate: 77 tests across the new
suite and the existing clip, group, corner, graphics-state, mask, stencil, rotation, coordinate
and password suites.

A single full Java 21 `mvn -o verify` then passed **1,359 tests** (the 1,348 accepted baseline
plus 11 new), with zero failures, errors or skips and strict Javadocs. The pinned clang-format
23.1.1 was applied to the new files only. No dependency was added; PDFBox 3.0.8 was already
present.

## Resource, ownership and coordinate policy

- **Source and resource ownership:** File sources are read through the bounded file reader and
  closed before parsing. Byte sources are copied. The document, renderer, page raster and encoder
  buffers belong to one call, and nothing is cached across calls. The service is stateless and
  thread-safe; each call owns its PDFBox objects.
- **Pre-render checks:** All request, page, pixel and crop checks run before the first render. The
  page-raster prediction reproduces PDFRenderer, and a mismatch after rendering is an error.
- **What is not bounded:** PDFBox parser, font, decoder and blend-mode intermediates, including a
  temporary ARGB page for top-level blend modes. A render already in progress is not interrupted.
- **Supported page geometry:** quarter-turn rotations only; other rotations reuse the
  partitioner's `UNSUPPORTED_OPTION` rule. UserUnit is applied; nonzero crop-box origins are
  handled by the displayed frame.
- **Optional content:** follows PDFBox's `EXPORT` destination.

## Limits

- Rendering is PDFBox's compositing. This shows agreement on the controls and transfer regions,
  not general blend-mode, soft-mask, knockout or optional-content correctness, or parity with
  another renderer. Small edge-dense crops differ from Poppler beyond the frozen tolerance.
- Crops are page rectangles, not curve-shaped masks or source isolation. Overlapping paint is
  included.
- Only PNG output and two backgrounds are offered, and requests cannot be given in pixels.
- No element attachment, detection, Unstructured 0.27.33 execution, or hosted-service comparison
  is included.
- The magazine is known development data and the 16 regions are not page labels. Independent
  acceptance remains open.
