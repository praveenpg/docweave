# PDF support and remaining milestones

This page describes the development checkout unless a section names a released version.
New options in [Unreleased changes](../CHANGELOG.md#unreleased) require a snapshot build;
they are not part of the published beta.6 dependency in the [getting-started example](../README.md#getting-started).


## Form nesting and explicit source-limit failures (unreleased)

Native graphics traversal accepts at most 50 nested Form XObject invocations, including
transparency groups. Invoking a 51st form or reaching that depth through a cycle throws
`INPUT_TOO_LARGE`. This makes PDFBox's existing form-depth boundary explicit: extraction
no longer returns success after that operator silently omits deeper source content.

An image inside the 50th form remains eligible; image XObjects do not consume another
form level. Operator budgets, source-image limits, cancellation and form-local path
restoration remain active. The limit is a fixed traversal boundary, not a new configurable
option or a guarantee of complete rendered visibility. It is independent of the 64-level
Type3 character-program limit described below.

## Invisible Type3 glyphs and native table evidence (unreleased)

Drawing operators inside an invisible Type3 character program (`Tr 3`, PDFBox
`RenderingMode.NEITHER`) no longer supply native stroked or filled table rules or
shaded-row evidence. Visible Type3 programs retain their own painting operators;
other text modes are not interpreted as ordinary-font fill/stroke restrictions.

The suppression is scoped to the glyph program and its nested content. Ordinary page
rules remain eligible after the glyph, even if the text state still has rendering mode3.
Source text selection and source image occurrence accounting remain unchanged. All
operators still consume the shared work budget, and cancellation/errors restore the
surrounding paint scope. At most 64 Type3 character programs may be active simultaneously;
the 65th, including a cyclic font program, fails with `INPUT_TOO_LARGE` instead of
continuing toward JVM stack exhaustion. The shared operator budget can reject a source
earlier. The nesting counter unwinds after cancellation or failure. This correction does
not implement general font visibility, clipping, optional-content or final page compositing.

## Source-image graphics state (unreleased)

Every native source Image includes `pdf_image_graphics_state` alongside its geometry:

```json
{
  "nonstroking_alpha": 0.25,
  "blend_mode": "Multiply",
  "has_soft_mask": false,
  "alpha_is_shape": false
}
```

The values describe the graphics state interpreted by PDFBox at that particular image
invocation. Ordinary form inheritance and save/restore scopes are retained. Blend mode
is the resolved PDF mode name, not an evaluation of the pixels it will produce.
`has_soft_mask` reports a graphics-state soft mask; it is separate from the image's
intrinsic mask, which the existing normalized PNG decoder may already apply.
`alpha_is_shape` records the graphics-state alpha-source flag.

These properties are present with or without image bytes. They do not change normalized
PNG samples, source occurrence counts, placements, source text or resource limits.
Zero-alpha occurrences remain source Images. Do not interpret `nonstroking_alpha` as
an intrinsic PNG alpha channel or automatically apply the mask twice.

This map describes the current stream's state, not the complete page-compositing chain.
Transparency groups reset their internal alpha/blend/mask values before group contents
are interpreted; enclosing group opacity is not multiplied into this image map.
Clipping, backdrop interactions, group isolation/knockout, occlusion and final visible
pixels require separate rendering. JSON serialization and coordinate conversion retain
this map unchanged. The field is additive snapshot metadata, not part of beta.6.

## Ordered source-image placement corners (unreleased)

Every native source Image now carries an additional `pdf_image_coordinates` map.
The original `coordinates` map stays the same enclosing axis-aligned rectangle;
the new map preserves rotation, reflection and shear that rectangle cannot express.
Its `system`, `layout_width`, `layout_height` and four `points` use the same
crop-relative, displayed top-left page frame as other PDF geometry. PDF UserUnit
is recorded separately and does not scale native PDFUserSpace positions.

`point_order=source_unit_square_00_10_11_01` identifies each original image-unit
corner before placement. These labels are deliberately retained under reflections,
page rotation and coordinate conversion; do not sort corners by screen position.
For a decoded PNG with its usual top-left raster origin, the corresponding pixel
corners are source01,11,10,00. Normalized PNG bytes/dimensions remain unchanged;
this map does not rotate or shear the payload itself. Repeated/form/inline image
occurrences each retain their own actual invocation transform.

`PdfCoordinateConverter` converts this additional map together with ordinary
and table-cell coordinates. For example, `PdfCoordinateConverter.create().toPixels(image, 150)`
retains corner identity while expressing positions in virtual150DPI page pixels.
Conversion to millimeters/points applies source UserUnit exactly once; physical-unit
round trips can have ordinary floating-point roundoff. Malformed maps and nonfinite
conversion results are rejected, and the original element remains immutable.

This is source-placement geometry, not a visible-pixel polygon or rendered page crop.
Clipping, alpha/compositing, soft-mask page-image roles, occlusion and vector-only figures
remain separate concerns. The new field is additive metadata, available on the development
branch rather than the published beta. Existing text, IDs, table output, image bounds,
resource budgets and reference/payload selection remain unchanged. See the
[owned and independent affine evidence](../benchmarks/review/pdf-source-image-corners/README.md).

## Explicit rendered page-region crops (unreleased)

`PdfPageCropRenderer` is a separate service for the final rendered appearance of explicit page
rectangles. It does not run during partitioning and never changes elements, source-image counts,
order, PNG payloads, coordinates or clip/group metadata:

```java
var crops = PdfPageCropRenderer.create(PdfPageCropOptions.defaults())
        .render(DocumentSource.of(path), List.of(new PdfPageCropRequest(1, 40, 40, 80, 80)));
byte[] png = crops.getFirst().png();
```

PDFBox renders the whole page, including its transparency groups, soft masks, clipping, blend
modes and paint order. Each crop then copies the requested rectangle from that page raster, so
group alpha 0 yields background, alpha 0.5 blends and a clip admits only its region. A crop can
also include text, vector paint, neighboring images and later overpainting. It is not a source
image, an isolated asset, a figure detector or proof that a source image is visible: a fully
hidden image can have a valid background-only crop.

Requests use the displayed partition frame: crop-box-relative, top-left origin, page quarter
turns applied. They take unscaled `USER_SPACE` (the `PDFUserSpace` element system) or physical
`POINTS`; pixel input is not accepted. The raster scale is `dpi * UserUnit / 72` pixels per user
unit, and the page raster size follows PDFRenderer's float products and floors. Requested edges
round exactly outward to whole pixels. Each edge is the exact product of the requested value and
the float render scale (divided exactly by UserUnit for points), with no decimal tolerance. A
positive-width overlap with any pixel column or row therefore includes it, and an edge exactly on
a pixel boundary adds nothing. The result is intersected with the page raster. Each crop reports its
actual pixel origin and size, its actual extent in request units, whether the request was
clipped, and its rotation, UserUnit, resolution, background and renderer identity.

Lossless PNG is the only format. `WHITE` uses an opaque RGB page; `TRANSPARENT` keeps alpha over
an unpainted backdrop. File sources are read once into a bounded snapshot. Byte sources are
copied, and decoded text sources are rejected. Each distinct page is rendered exactly once per
call, by its own PDFRenderer. PDFRenderer keeps its last page image, so a renderer is never reused:
once a page's crops are copied, neither the renderer nor its raster is referenced, and the next
page's raster is allocated without the previous one being reachable. This is not a heap bound.

Request count, distinct pages, per-page and total rendered pixels, and crop pixels are all checked
from page geometry before rendering (`IMAGE_LIMIT`). That includes PDFRenderer's hard ceiling
of 2,147,483,647 pixels per page raster, even when a caller's long budget is larger. PNG bytes are
charged during encoding.
Missing pages and regions without rendered pixels fail with `UNSUPPORTED_OPTION`. Invalid
coordinates fail at request construction. Interruption is checked before and after each page
render and during copying and encoding. PDFBox does not interrupt a render already in progress,
and these limits do not bound parser, font or decoder intermediates.

The result is PDFBox's compositing, not a renderer-independent guarantee. Java2D applies
non-rectangular clip paths, including glyph clips, without antialiasing, so those edges are hard
at one sample per pixel. The explicit `PdfPageCropOptions.Sampling.BOX_4X` renders at four times
the output scale and averages each 4x4 block, with alpha-weighted color, into one output pixel.
The output grid, rectangles and metadata are unchanged. The rendered raster has about sixteen
times the pixels and is charged against page-pixel limits. At 150 DPI an A4 page exceeds the
default 16M page budget, so `BOX_4X` there needs explicitly larger budgets. On pages smaller
than one output pixel in either axis, samples outside the actual supersampled page raster use
the selected white or transparent background. Source edge pixels are not repeated, the output
grid remains unchanged, and no larger page raster is allocated for this padding. The default
remains `RENDERER`. See the
[rendered region evidence](../benchmarks/review/pdf-rendered-region-crops/README.md) and the
[review follow-up](../benchmarks/review/pdf-rendered-region-crops-followup/README.md).
A [fractional-placement control](../benchmarks/review/pdf-remaining-diagnoses/README.md)
attributes the remaining PDFium differences to renderer policy. PDFium and Poppler use a
rounded-up page grid, snap axis-aligned image and rectangle edges to whole pixels, and antialias
glyph-clip edges. `BOX_4X` approximates the antialiasing but keeps fractional placement. No
renderer-specific snapping option is provided.

## Explicit empty-clip native text selection

To exclude positive-alpha source text inside an empty inherited form/page clipping area:

```java
var options = PdfOptions.defaults().withTextRendering(
        PdfOptions.TextRendering.NONZERO_ALPHA_NONEMPTY_CLIP_PAINTED_MODES);
var partitioner = PdfPartitioner.create(options);
```

This adds an inherited empty-area check to the existing painting-mode/positive-alpha
filter. It runs before duplicate suppression and source ownership, while every source
position still counts against `maxGlyphsPerPage`. A later visible duplicate survives.
All previous policies and defaults retain their behavior. Only caller-configured OCR may
run when the resulting page is textless; OCR output is not marked as native-filtered text.
Native elements use `nonzero_alpha_nonempty_clip_painted_modes_v1` provenance.

This does not test individual glyph containment in nonempty clips, explicit path clipping,
partial visibility, soft masks, occlusion or optional-content visibility. It preserves
those unsupported source positions rather than claiming full rendered visibility. See
[owned controls and complete original regressions](../benchmarks/review/pdf-empty-text-clips/README.md).

## Zero-opacity native table strokes

Stroked rules with zero stroke opacity do not supply native table geometry. For a
combined fill/stroke path, supported thin/dark fill evidence may still contribute
when its stroke is invisible. Positive-opacity stroke interpretation remains as
before. An affected invisible-grid input can now retain ordinary native text instead
of a false Table. Source text, operator/work limits and default policies remain.
This does not interpret general path/partial clipping, dashes, soft-mask/compositing effects or final
occlusion, and is not a rendered-visibility/redaction guarantee. See
[owned pixel proof and original regressions](../benchmarks/review/pdf-stroke-opacity/README.md).

## Empty inherited form clipping

Native table-rule and pale-row evidence excludes empty inherited form/page clipping
areas. This handles zero-area form bounds, bounds entirely outside the page crop
and inherited nested empty forms. Later visible form/page paint retains its original
state. General path clips and partial intersection geometry remain unsupported;
this correction is not general rendering, occlusion or redaction-aware extraction.
Original image-occurrence and raw native text policies remain independent. See
[empty-form clipping validation](../benchmarks/review/pdf-empty-form-clips/README.md).

Inherited clipping also rejects rule/pale-row paint whose complete enclosing bounds
are provably disjoint from the inherited clip's displayed enclosing rectangle.
Stroke allowances include transformed widths and miter extents; device-dependent
hairlines retain historical evidence. Fills are tested as complete rectangles, so
an enclosing fill is not lost just because its individual edges lie outside a clip.
Retained coordinates remain original; overlap is not a visibility guarantee. See
[disjoint-paint validation](../benchmarks/review/pdf-disjoint-form-clips/README.md).

## Single rectangular path clipping

Equivalent move/line rectangles with explicit or implicit closure are also supported.
A fixed six-vertex candidate admits only a finite axis-aligned rectangular or empty
area; concave/compound/curved/excessive paths retain conservative fallback. See the
[line-built rectangle correction](../benchmarks/review/pdf-line-rectangle-clips/README.md).

A single source `re` rectangle whose transformed edges remain exactly axis aligned can
now establish an explicit clipping area. Its intersection takes effect when the current
path finishes painting or is discarded. Existing saved graphics states and form scopes
restore the prior area. Subsequent table-rule and pale-row evidence uses the same empty/
provably-disjoint checks described above; intersecting evidence keeps its original geometry.

This subset includes degenerate empty rectangles, crop/UserUnit/quarter-turn frames and
restored outer paint. Compound paths, curves, skewed rectangles and excessive line
paths remain unsupported. It does not trim partial strokes or
change independent native-text/source-image policies. See
[owned controls and complete original regressions](../benchmarks/review/pdf-explicit-rectangle-clips/README.md).

## Explicit page background normalization

Optional Tesseract providers can select `TesseractPreprocessing.LOCAL_WHITEPOINT`
with an actual source DPI. The page-only policy preserves dimensions/source pixels
and shares existing admission, pixel and total deadline bounds; cell OCR and earlier
factories retain their behavior. The fixed1778 low-contrast source gains line
coverage, while other sources regress. This is explicit grayscale preparation,
not automatic enhancement or a broad historical/degraded acceptance result. See
[configuration and tradeoffs](ocr-tesseract.md#explicit-page-background-normalization)
and [complete evidence](../benchmarks/review/pdf-scan-background-experiment/README.md).

## Explicit table-cell compound wraps

Use `PdfNativeLayoutOptions.CellTextJoin.SOURCE_WORD_WRAPS_AND_ISO_DATES` when
native table-cell lines should retain a physical compound hyphen without the
inter-line joining space. For source-tagged tables:

```java
final var partitioner = PdfPartitioner.createWithTaggedTables(
        PdfOptions.defaults().withTableCellCoordinates(true),
        PdfNativeLayoutOptions.CellTextJoin.SOURCE_WORD_WRAPS_AND_ISO_DATES);
```

The same enum can be passed through `PdfNativeLayoutOptions.withCellTextJoin` for
native layout-provider tables. For example, `one-` followed on the next supported
baseline by `way` becomes `one-way`. This is an explicit compound-continuation
interpretation: geometry cannot establish word semantics. The policy requires
unique upright glyph endpoints, letter adjacency, bounded line distance and no
explicit source whitespace at the boundary. Ambiguous endpoints, numeric ranges,
punctuation and unsupported frames retain the existing joining behavior.

Valid wrapped ISO dates also join. Source-tagged cells additionally honor the
existing unambiguous U+00AD source hints, which take precedence over keeping a
physical hyphen. Provider-only cells have no tagged discretionary hints. No
arbitrary hard hyphen is deleted, and cell ownership, topology, coordinates and
original prejoin character limits remain unchanged. Default factories and older
policies retain their behavior.

Changed tables declare `pdf_table_cell_text_policy=source_word_wraps_and_iso_dates_v1`.
Positive physical-boundary counts use `pdf_table_retained_hyphen_wraps`; existing
ISO-date and discretionary-hyphen counters remain separate. Whole-table counts
stay on unsplit tables and in original-element chunk provenance, and are omitted
from structural and text-only fragments.

[Validation evidence](../benchmarks/review/pdf-cell-word-wraps/README.md) covers
both extraction routes, source-space/rotation/limit fallbacks and chunk provenance.
All six profiles of the three pinned scientific originals remain unchanged; this
addition does not establish an original-source accuracy gain or recover NVIDIA rows.

## Native cell superscripts and subscripts

Use `PdfNativeLayoutOptions.CellTextJoin.INLINE_SCRIPT_RUNS` with native provider or
source-tagged tables to group nearby smaller glyph runs with an unambiguous body
baseline inside the existing cell. For example:

```java
final var layoutOptions = PdfNativeLayoutOptions.defaults().withCellTextJoin(
        PdfNativeLayoutOptions.CellTextJoin.INLINE_SCRIPT_RUNS);
final var partitioner = PdfPartitioner.createWithNativeLayout(
        PdfOptions.defaults(), layoutOptions, tableLayoutProvider);
```

This explicit geometric interpretation can retain `alpha12beta` when the two digits
are painted above or below the body baseline. Runs are capped at eight glyphs and
must satisfy the existing bounded size, adjacency and unique-baseline checks. Distant,
overlapping or ambiguous runs and rotated/mixed source directions retain historical
grouping. Original characters, cell bounds, source ownership and text limits remain
intact; no mathematical markup, Unicode rewriting, date joining or hyphen removal is
performed. Ordinary text and OCR are unchanged. Affected tables carry
`pdf_table_cell_text_policy=native_inline_script_runs_v1` and a whole-table
`pdf_table_inline_script_glyphs` count. Split chunks omit the whole-table count while
retaining it in original-element provenance. This is owned-source behavior coverage;
broad scientific-table accuracy remains unmeasured.

## Explicit provider-table continuation links

`PdfOptions.TableContinuation.REPEATED_HEADER_WITH_PROVIDERS` extends the existing
ruled-table linking policy to provider tables with one explicitly declared, unmerged
header row. Use it with native-layout, word-layout or separate cell-OCR extraction:

```java
final var options = PdfOptions.defaults().withTableContinuation(
        PdfOptions.TableContinuation.REPEATED_HEADER_WITH_PROVIDERS);
```

Both pages must have matching source frames, provider provenance, recognition family,
normalized header labels and consistent column boundaries. Each header has two through
32 cells; body text must be nonblank. Existing adjacent-page isolation and bottom/top
page-edge checks still apply. Unknown, stacked and merged headers, jagged column
geometry and nonzero OCR orientation corrections supply no provider linking evidence.
The default and `REPEATED_HEADER` retain their previous behavior. Links add only
`pdf_table_continuation`; each page's table, repeated header, text, HTML, IDs and cell
coordinates remain intact. No table rows are repaired or combined, and learned header
quality is not established by this feature.

To compare declared unmerged prefixes of two through four header rows, select the
separate `REPEATED_HEADER_PREFIX_WITH_PROVIDERS` policy:

```java
final var options = PdfOptions.defaults().withTableContinuation(
        PdfOptions.TableContinuation.REPEATED_HEADER_PREFIX_WITH_PROVIDERS);
```

The prefix is capped at32 original header cells, and a nonblank body must follow it.
Each normalized header cell must be nonempty; a numeric year is eligible when its
column's combined header contains alphabetic evidence. Existing per-column512-unit
and aggregate4096-unit signature limits still apply to combined column labels.
Matching retains the individual row/cell boundaries, so moving text between two
header cells cannot create a match just because their concatenation is equal.
Merged header anchors, inconsistent boundaries, unknown declarations, corrected OCR
frames and differing text routes/provenance remain ineligible. Single-row prefixes
retain the previous matching behavior. The older provider policy still excludes
multi-row prefixes. This adds links only, without dropping/repeating/merging source
rows or claiming table recognition accuracy. See
[owned validation](../benchmarks/review/pdf-provider-prefix-continuation/README.md).

For explicitly declared merged header prefixes, use:

```java
final var options = PdfOptions.defaults().withTableContinuation(
        PdfOptions.TableContinuation.REPEATED_HEADER_LAYOUT_WITH_PROVIDERS);
```

This includes the earlier single-row behavior and supports two through four declared
header rows with horizontal or vertical merges wholly inside the header. The cap is
32 **logical header slots**, including covered positions. Every atomic column edge
must be observed, and every anchor, including body anchors, must agree exactly with
those edges. Matching retains both normalized text and the exact declared row/column
span topology. Equal expanded labels with different merges cannot establish a link.
Expanded slots count toward the existing512-unit combined-column and4096-unit total
text limits. Blank header slots, spans crossing into the body, missing internal edges,
corrected OCR frames and differing recognition families/provenance remain ineligible.
Earlier policies retain their restrictions. These are page-local continuation links,
not header inference or a measured improvement in model recognition accuracy. See
[owned integration evidence](../benchmarks/review/pdf-provider-merged-continuation/README.md).

For the proposed beta scope and release prerequisites, see the [draft PDF beta checklist](pdf-beta-checklist.md).
It does not close the remaining milestones below.

PDF is the current development priority. Further DOCX enhancements are paused; existing DOCX
behavior and regression tests remain maintained. PDF completion includes tables and images, not
only text. The first native milestone below does not complete the full PDF requirement.

## Native milestone

Install `io.docweave:docweave-pdf:0.1.0-beta.8` for PDF only. It depends on core and Apache PDFBox
3.0.8, without POI, jsoup, CommonMark or Jackson. The all-formats convenience artifact includes it.
Core stays JDK-only. Parser discovery uses the existing ServiceLoader strategy registry.

```java
var result = io.docweave.DocWeave.create().partition(java.nio.file.Path.of("report.pdf"));
```

The initial implementation provides:

- Binary file/byte loading, signature/filename detection, one-based page numbers and deterministic IDs.
- Native quarter-turn text as separate UncategorizedText lines; opt-in paragraph/column processing is described below.
  Headings and languages are not inferred.
- Approximate crop-relative bounding boxes, top-left origin, `coordinates.system=PDFUserSpace`.
  Coordinates use PDF user-space units, before UserUnit scaling; they are not pixel coordinates.
- Multiple disconnected, axis-aligned ruled tables per page, with at least two atomic rows and two
  atomic columns each. Collinear border segments join within half a user-space unit; duplicates and
  reversed stroke directions are tolerated. Disconnected decorative rules do not veto valid tables.
- Thin dark rectangular fills can supply rule centerlines as well as stroked paths. A fill must be one
  single-subpath axis-aligned rectangle: four explicit edges or three explicit edges closed by
  the PDF fill operator. There must be no curves, thickness must be >0 and <=1 unscaled unit,
  length >=4 and >=8 times thickness. Each longitudinal cap extends by half the thickness (<=0.5)
  for separately painted junctions; the existing0.5-unit grid tolerance is unchanged. Only device
  Gray/RGB/CMYK and ICC colors with every converted RGB channel <=127, nonstroking alpha >=0.99,
  normal blend and no soft mask qualify. Large backgrounds, compound/curved/skewed paths,
  transparent/white/pale fills are excluded. This is geometry extraction, not rendered visibility:
  partial/general-path clipping and overpainting can still make source rules misleading.
  Empty inherited form/page clips supply no rule or pale-row evidence. Filled-and-stroked
  paths with positive stroke opacity retain the historical stroke path; invisible strokes
  can fall back to independently supported fill evidence without duplicate centerlines.
- Closed outer borders are required. Missing complete interior edges join atomic slots; only rectangular
  merged regions with no leftover internal borders are accepted. HTML emits `rowspan`/`colspan` on the
  row-major anchor once, retains empty cells and has no inferred headers. Table text owns each glyph
  once, including multiline merged cells. `pdf_table_method` is `complete_ruled_grid` for unmerged
  grids and `ruled_grid_with_spans` otherwise.
- Open perimeters, partially covered cell edges, L-shaped merges and overlapping/nested table bounds
  are rejected as table candidates; their native text remains ordinary output. A fully missing border
  can represent either an intentional merge or a damaged table: this heuristic cannot infer author
  intent. Decorative closed grids can also be false positives. No general borderless/scanned/multi-page
  recognition or rendered clipping/visibility/dash-pattern interpretation is claimed.
- Painted raster-image occurrences, including repeated resources, inline images and form XObjects,
  with dimensions and placement boxes. The default emits references through page/placement metadata,
  without bytes or invented captions. No external assets are fetched. Vector illustrations are not images.
- Opt-in decoded PNG/base64 payloads, preserving pixels rather than original stream encoding.
- Page/glyph/operator/table/pixel/cumulative-PNG budgets, shared input/element limits, cooperative
  cancellation and thread-safe reuse. A fixed per-page ceiling of 2,000,000 table operations bounds
  stroke comparisons, edge scans, topology validation and glyph-ownership probes; exceeding it fails
  with TABLE_LIMIT, even if caller-selected row/cell limits would permit the grid. Virtual threads belong to the existing bounded batch service;
  a synchronous parser does not create an extra executor.

For custom image/limit policies, configure the public service interface through the registry:

```java
import io.docweave.DocWeave;
import io.docweave.partition.*;

var pdf = PdfPartitioner.create(new PdfOptions(
        1000, 250_000, 100_000, 16_000_000, 16 * 1024 * 1024, true, TableLimits.defaults()));
var processor = DocWeave.create(PartitionerRegistry.builder().register(pdf).build());
```

With payloads enabled, Image metadata contains `image_base64` and `image_mime_type=image/png`.
`image_width` and `image_height` are source raster dimensions. Repeated occurrences are separate
Image elements and each payload counts toward the cumulative byte budget.

An image mask can enlarge the normalized PNG beyond the main source raster. Pixel
preflight checks the larger width and height of the source image and the active
image-mask plane, then checks the returned PNG raster again. A soft mask takes
precedence over a hard mask; color-key arrays and unused mask streams do not add an
image plane. This applies to masks decoded with an image, not page compositing groups.
`maxImagePixels` still describes one resulting raster extent, not the sum of same-size
color and alpha planes. Reference-only extraction does not decode these planes.

When PNG dimensions differ from `image_width`/`image_height`, metadata additionally
records `image_payload_width` and `image_payload_height`; the original fields and
placement coordinates retain source meaning. Image payloads previously accepted with
oversized masks may now fail `IMAGE_LIMIT` under the same caller pixel ceiling. Internal
codec allocations remain outside a hard process-memory guarantee. See the
[mask-extent controls and original regression](../benchmarks/review/pdf-image-mask-limits/README.md).

Solid-color stencil image payloads use the occurrence's current nonstroking fill and
retain transparent mask pixels, including inline masks and inherited form paint.
The PNG has `image_stencil=true` and
`image_pixel_policy=stencil_solid_fill_v1`. Reusing one mask with different fills
produces independently painted payloads. Inverted standard mask decode values remain
supported. Reference-only extraction still performs no image decoding or added stencil
metadata work.

Pattern-painted stencils retain decoded monochrome mask samples and report
`image_pixel_policy=stencil_mask_samples_v1`; they are not rendered pattern previews.
PNG payloads are source-image pixels, not rendered page crops: page clipping, placement
transforms, global graphics-state opacity/blending and pattern composition are not
baked into them. Ordinary raster colors/alpha remain intrinsic to the image. Per-image
pixel preflight and cumulative PNG limits still apply; corrected colored/transparent
payloads can encode to a different byte size. See the
[stencil regression and packaged before/after checks](../benchmarks/review/pdf-stencil-payloads/README.md).

Every successful document reports PDF_EXTRACTION_LIMITATION. With default native-only extraction, image-only pages additionally warn
that OCR was not performed. This is not a detector for every scanned region: pages with some native
text can still contain unread image text. Non-quarter-turn text requires an explicit oriented text-layout selection;
mirrored/degenerate text, password-required documents without configured credentials, prohibited
content extraction and nested-table preservation requests fail explicitly. An empty-password PDF
may open when permissions permit. Explicit credential lookup is described below. Malformed input
fails with MALFORMED_DOCUMENT; third-party recovery does not establish PDF conformance.

## Password-protected documents

Attach an explicit `PdfPasswordProvider` to any built-in PDF strategy:

```java
PdfPasswordProvider passwords = filename -> {
    var value = System.getenv("DOCWEAVE_PDF_PASSWORD");
    return value == null ? null : value.toCharArray();
};
var pdf = PdfPartitioner.create(PdfOptions.defaults()).withPasswords(passwords);
var processor = DocWeave.create(PartitionerRegistry.of(List.of(pdf)));
```

`withPasswords` returns a new service and preserves the selected native, source-tagged,
provider-layout, OCR, cell-recognition and orientation policies. It does not mutate the
original strategy or transfer ownership of external providers. Default discovery remains
native-only without configured credentials. The provider must be thread-safe for concurrent
documents; the source filename is a nullable lookup hint. Configuration performs no lookup.

An ordinary empty-password open is attempted first. Lookup runs at most once when that open
requires a password, or when an encrypted document's empty-password permissions prohibit
extraction. The returned array must be fresh: ownership transfers to DocWeave and it is
cleared after the attempt, including failure/cancellation. Return null if unavailable.
There is no credential cache, password guessing, retry loop or interactive prompt.
PDFBox requires a temporary String, so array clearing does not guarantee JVM/library erasure.

PDFBox's actual access permissions remain authoritative. A valid user password cannot override
an extraction prohibition; a valid owner password can grant owner access under PDFBox's standard
security handler. No permission flags are changed or security removed. Missing/wrong credentials,
lookup failure and prohibited extraction use `UNSUPPORTED_OPTION`; malformed PDFs retain
`MALFORMED_DOCUMENT`. Provider error text/causes are omitted from processing failures, while
interruption remains `InterruptedIOException` with the flag preserved. All input, page, glyph,
operator, image, table and output limits remain active. The provider owns its own lookup deadline;
direct synchronous parsing still has no hard termination guarantee.

Font diagnostics use the same policy with
`PdfFontInspector.create().withPasswords(passwords).inspect(bytes)`; the lookup hint is null
because that API receives bytes without a filename. Custom service implementations retain source
compatibility through an optional default method and may override it; the default explicitly
rejects unsupported configuration.

JVM regression controls cover real AES128/AES256 documents, AES256 Unicode credentials,
permissions, independent native/tagged/provider/OCR output and geometry, font inspection,
credential clearing, cancellation, concurrent per-document lookup and existing limits.

## Optional scanned-page OCR integration

### Convenience strategy presets (unreleased)

`PdfPartitioner.builder()` exposes the additive `PdfStrategy` presets below. Existing factories,
ServiceLoader discovery and native-only defaults are unchanged. These are DocWeave routing
contracts; matching names do not imply Unstructured output or fallback parity.

| Strategy | Routing | Required providers | Default OCR resolution |
| --- | --- | --- | --- |
| `FAST` | Native text/tables and original source images; never invokes OCR/layout providers | None | No OCR rendering |
| `AUTO` | Native text per page; OCR only pages without nonblank native text, including blank pages | OCR optional; without it, native output and textless-page warnings remain |150DPI|
| `OCR_ONLY` | OCR every whole page, replacing native text/table emission and retaining source images | `PdfOcrProvider` |150DPI|
| `HI_RES` | OCR every whole page with actual words and explicit provider table topology; retain source images | `PdfWordOcrProvider` and `PdfTableLayoutProvider` |300DPI|

```java
final var pdf = PdfPartitioner.builder()
        .strategy(PdfStrategy.AUTO)
        .ocrProvider(myOcrProvider)
        .build();
final var processor = DocWeave.create(PartitionerRegistry.of(List.of(pdf)));
```

Use `TesseractOcr.create(TesseractOptions.defaults())` for an explicitly installed line provider,
or `TesseractOcr.createWithWords(...)` when actual word boxes are required. HI_RES additionally
requires `.tableLayoutProvider(myLayoutProvider)`; the optional local ONNX adapter is one
implementation, and an application may supply another. No engine, language pack, model download,
discovery or provider call occurs during builder construction or `build()`. Managed providers
remain caller-owned and must outlive all concurrent calls. Built services snapshot a mutable,
non-thread-safe builder and remain reusable when their providers are thread-safe.

`.ocrOptions(PdfOcrOptions.defaults().withDpi(200))` overrides resolution and retains configured
budgets/reading order. Presets own page selection: AUTO chooses TEXTLESS_PAGES; OCR_ONLY and
HI_RES choose ALL_PAGES. HI_RES also selects PROVIDER_LAYOUT. Other table policies remain
available through OCR options and the existing low-level factories. FAST ignores optional OCR/
layout settings and never invokes them. Other inconsistent or unused provider settings fail
at build time, including missing required engines, line-only engines for word-based tables,
or a layout provider without a matching table policy. AUTO with explicit OCR settings also
requires an OCR provider, rather than silently ignoring that configuration.

AUTO tests native-text presence, not recognition quality; it does not repair a bad searchable
layer, infer languages, OCR mixed-page images or retry failed recognition. Choose IMAGE_REGIONS
through the existing factory for mixed-page masking. OCR_ONLY/HI_RES deliberately replace
searchable text layers. HI_RES provides table layout assistance, not semantic title/figure/
caption classification or automatically better results. A caller-selected lower DPI is allowed;
the name is a preset, not a quality guarantee. Existing provider/render/output failures propagate
without changing strategies or silently using native output.

`PdfPartitioner.create(PdfOptions, PdfOcrOptions, PdfOcrProvider)` explicitly selects caller-supplied
OCR on pages with no nonblank native glyphs. The default factory and ServiceLoader provider remain
native-only. The default OCR selection is `TEXTLESS_PAGES`: a mixed document can use native extraction
on one page and OCR on another, but a page containing native text is not OCRed. Blank pages also reach
the provider. `IMAGE_REGIONS` explicitly extends this to raster-image areas on mixed pages, as below.
The new explicit `ALL_PAGES` selection recognizes every whole page and replaces native text/table
emission, retaining source images and `pdf_ocr_selection=all_pages_v1` on recognized elements.
Native glyph grouping and its glyph budget are unused on this path; document/permission checks,
graphics operator/source-image limits, rendered-pixel limits and raw OCR output budgets remain.

The public functional interface receives a borrowed RGB `BufferedImage` and returns pixel-bounded
`PdfOcrProvider.Line` values in provider reading order. Implementations must be thread-safe, honor
interruption, leave the raster unchanged, retain no reference to it and keep the returned list stable.
Provider-side languages, process limits, timeouts and allocation bounds belong to the implementation.
No OCR engine is bundled, installed, discovered or started by this integration.

`PdfOcrOptions.defaults()` selects 150 DPI, 16 million rendered pixels, 10,000 returned lines and
one million returned UTF-16 units per page. Rendering size is conservatively checked before rendering
and checked against actual dimensions afterward. Output is validated before OCR text emission.
`OCR_LIMIT` identifies raster/output budget failures; `OCR_TIMEOUT` identifies managed provider deadline expiry; `OCR_FAILURE` identifies provider exceptions
(with their cause retained) and invalid provider output. Cancellation preserves `InterruptedIOException`.
These checks cannot bound allocations inside a provider, PDFBox or image decoders.

OCR emits ordinary text with `pdf_text_method=ocr`, `pdf_ocr_dpi`, page metadata and displayed
PDFUserSpace coordinates. Conversion uses actual raster dimensions, preserving crop and quarter-turn
frames without assuming exact DPI rounding. Image elements remain separate and precede OCR lines on
these pages. On mixed pages, all existing native/image elements precede the appended OCR lines.
Native line/table heuristics are not run over OCR lines. Separate opt-in raster ruled-table recognition
is described below; optional unified geometric native/OCR order is documented below. An empty recognition result is valid.
The limitation warning remains, but a page sent to OCR does not say OCR was not performed.

Owned integration tests use deterministic supplied providers, including rendered image content,
rotation, budgets, errors, cancellation and shared virtual-thread calls. They establish integration
behavior, not OCR accuracy. An optional [managed Tesseract adapter](ocr-tesseract.md) now supplies real OCR with bounded
process admission, pipe capture, timeouts, languages and owned scanned-text validation. It must be
added/configured explicitly; no system-wide OCR installation is performed by this library.

## Mixed native/scanned-image regions

Select the conservative mode explicitly; the original four-argument `PdfOcrOptions` constructor and
`defaults()` retain textless-page behavior:

```java
var ocrPolicy = PdfOcrOptions.defaults()
        .withSelection(PdfOcrOptions.Selection.IMAGE_REGIONS);
var pdf = PdfPartitioner.create(PdfOptions.defaults(), ocrPolicy, provider);
```

Native textless pages still use whole-page OCR. On a page with native glyphs, this mode selects
painted raster-image enclosing rectangles intersecting the displayed crop. It does not classify
scans versus logos/photos. Pages with no image region do not invoke the provider. It renders the
page once, copies selected image rectangles onto a white canvas of the same dimensions, then masks
native glyph boxes with a two-user-unit guard band. Provider coordinates retain the full displayed
page frame; there is no per-region crop origin to reconstruct. The full render and masked raster
coexist temporarily. Image references/payloads and native tables/text remain unchanged.

Returned lines must be fully contained in one clipped image rectangle and disjoint from every
relevant padded native glyph box. Ambiguous lines crossing region boundaries or native text are
excluded whole rather than split. Identical text/box line records are emitted once; identical text
at different positions remains. Repeated/overlapping images are copied onto one canvas and recognized
in one provider call, preserving separate Image elements without per-placement OCR duplicates.
There is no fuzzy text matching or semantic ownership inference. Approximate font metrics, complex
image CTMs, clipping and hidden OCR layers can suppress useful text or leave visual content outside
the approximate masks; native glyphs take precedence regardless of their quality/visibility.

Accepted lines add `pdf_ocr_selection=image_regions_v1` and `pdf_ocr_region_count` (distinct clipped
pixel rectangles). Exclusions produce a count-only `PDF_EXTRACTION_LIMITATION` warning. Raw provider
line/character/raster validation occurs before filtering, so exclusions cannot bypass output limits.
No source/OCR strings are included in exclusion diagnostics.

`maxRegions` defaults to 256 painted placements on a mixed page, counted before deduplication. A
fixed shared ceiling of 2,000,000 geometry probes covers selection/masking/line ownership; a separate
64,000,000 cumulative pixel-visit ceiling covers background fill, selected rectangle copies and
native rectangle fills. Work is checked before allocating the masked canvas or entering the affected
drawing operations. Both limits fail with `OCR_LIMIT`, without fallback. These are work guards,
not peak-heap, codec-internal or end-to-end latency bounds. Cancellation preserves the interrupt flag.

Owned tests cover default compatibility, exact native-element preservation, raster masks, duplicate
and ambiguous line exclusions, distinct/overlapping/clipped images, crop/UserUnit/quarter-turn frames,
limits, cancellation and shared virtual-thread calls. Opt-in real-engine tests cover a mixed PDF and
a pinned independent handwritten scan with an owned native heading added. External OCR output is
recorded by count/hash, not asserted against an approved transcription; this is integration evidence,
not handwriting accuracy or general PDF parity. See `benchmarks/review/pdf-mixed-ocr/`.

## Scanned ruled tables and rectangular merges

Enable the bounded subset explicitly; default OCR remains ordinary text:

```java
var ocrPolicy = PdfOcrOptions.defaults()
        .withTableDetection(PdfOcrOptions.TableDetection.RULED);
// Also select IMAGE_REGIONS when native text and scanned images share a page.
var pdf = PdfPartitioner.create(PdfOptions.defaults(), ocrPolicy, provider);
```

The detector scans rendered RGB pixels for uninterrupted dark horizontal/vertical runs of at least
64 pixels, with all channels at most 80. Adjacent runs with endpoints within two pixels coalesce into
rule bands at most eight pixels thick. Existing bounded ruled-topology recognition then requires
closed grids with at least two atomic rows and two atomic columns. Atomic intervals must be at least
32 pixels wide and 24 pixels high. Completely absent interior dividers may form rectangular merged
cells; partial/open/nonrectangular/skewed grids retain ordinary OCR. This subset has no adaptive
binarization, perspective correction, borderless recognition, photographic classification or author-intent
inference. Thresholds are in rendered pixels, so DPI/contrast/noise can change recognition. Clear-looking
unsupported layouts can remain ordinary OCR, and unrelated graphics can still form false-positive grids.

Each prospective anchor cell is cropped across its full row/column span, five pixels inside its
outer rule centerlines, and given a twelve-pixel
white margin, without scaling, before invoking the configured OCR provider with `InputKind.TABLE_CELL`.
Whole-page and native-masked calls use `InputKind.PAGE`; the default contextual overload delegates to
the original method so existing providers/lambdas remain valid. No engine-specific segmentation is
mandated by PDF integration. Nonblank returned lines
join with spaces in provider order. The shared `PdfOptions.tableLimits()` controls rows, columns,
atomic slots and per-anchor Unicode code points. Each merged anchor is OCRed once; its covered
slots do not create extra calls or placeholder text. Empty anchor positions remain empty `td` elements;
`rowspan`/`colspan` describe merged extents, without inferring semantic headers. Headers are not
invented and text is HTML-escaped. Entirely blank cell recognition retains ordinary page OCR instead
of erasing the candidate. Provider/limit failures abort the document without silent fallback.

Accepted tables emit `Table`, `text_as_html`, `pdf_text_method=ocr` and
`pdf_table_method=scanned_complete_ruled_grid_v1` for unmerged grids or
`pdf_table_method=scanned_ruled_grid_with_spans_v1` for merged grids, with displayed PDFUserSpace bounds converted from
actual raster dimensions. Source Image elements remain. Their interiors are cleared before the
remaining-page OCR call, and returned ordinary lines intersecting accepted table bounds are excluded.
This gives table cells exclusive ownership of their content. Table output precedes remaining ordinary
OCR lines; native/image output still precedes both. Neither this order nor rule-centerline boxes claim
semantic reading order or exact rendered outlines. Per-cell coordinates are not emitted.

On mixed pages, the entire grid must fit one selected image rectangle and avoid padded native glyph
bounds. Otherwise it remains on the ordinary masked-image OCR path. Native content wins by geometry;
cell inference never incorporates native text into a second table.

`maxTableCells` defaults to 256 prospective cell OCR calls per page, shared across candidate grids,
checked before any provider call. The same ceiling also caps each grid's atomic-slot allocation before
merging; nine slots with six anchors still require `maxTableCells >= 9`. Aggregated OCR-call admission
across grids counts actual anchors. Old four/six-argument constructors preserve table detection `NONE`.
The page's `maxLines` and `maxCharacters` now aggregate cell and remaining-page OCR; inserted cell/table
joining spaces also count. Raw results are checked before ownership filtering. Topology matrices use
the smaller of the native table cell budget and the cell-call ceiling before allocation.

Additional fixed scanned-stage guards: 64 million pixel visits across both detector scans, 4096 raw
runs per orientation and 4096 final segments, the shared 2-million-operation cap-normalization/topology/ownership budget,
and 64 million projected cell-fill/copy/table-clear pixel visits. Exhaustion raises `TABLE_LIMIT`;
OCR returned-output/render limits retain `OCR_LIMIT`. All crop work is planned before starting cell
OCR; cell rasters are flushed after each synchronous call. Engine timeouts apply per call and restart
for each cell. There is no aggregate page OCR deadline, codec/peak-heap limit or forced real-time
cancellation guarantee. Limit document concurrency and cell calls for large workloads.

Owned controls cover escaped/empty cell positions, leading zeros, fallback, independent
grids, coordinate conversion, mixed native ownership, all resource policies, cancellation, concurrency,
JSON round-trip and structural table chunking. Real Tesseract 5.5.3 recognizes four owned raster-table
cells and surrounding prose exactly once. This is a synthetic regression control, not independent
scanned-table accuracy; evidence is under `benchmarks/review/pdf-scanned-tables/`.

## Rotation and coordinate normalization

Quarter-turn page rotations and text baselines are supported, including inherited/signed/repeated
page rotation. Text is grouped in its own upright direction before its bounds are mapped to the
shared displayed crop frame. Rules, image placements and glyph ownership use that same transform.
A page rotated 90/270 degrees swaps coordinates.layout_width/layout_height. Text that appears horizontal
after source/page rotations cancel can participate in table/paragraph/column processing. Sideways
runs remain separate ordinary text and are barriers for horizontal layout/aligned numeric heuristics;
inside a ruled cell, their text is retained once in the cell. No string reversal is used to simulate rotation.

Non-default source transforms add metadata:

| Field | Meaning |
| --- | --- |
| pdf_page_rotation | Normalized clockwise page angle 0/90/180/270; omitted at 0 |
| pdf_text_rotation | Clockwise text angle on the displayed page; omitted when horizontal |
| pdf_crop_box | Effective unrotated crop bounds `[left,bottom,right,top]` in absolute source units; emitted for nonzero crop origin |
| pdf_user_unit | Physical points per source user-space unit; omitted at 1 |

Coordinates retain system=PDFUserSpace and unscaled source units, with top-left origin after page
rotation/cropping. Multiplying by pdf_user_unit (default 1) gives physical points, not pixels. There
is no implicit UserUnit/DPI multiplication; native table/layout tolerances stay in source units.
Image payloads remain decoded source rasters; placement boxes do not describe the full image
CTM/shear/reflection or raster orientation. Bounding boxes approximate glyph metrics and enclose
rotated runs; they do not render clipping, font outlines, visibility or vertical-writing semantics.

Page rotation must be an integer multiple of 90 within Java int range. A text baseline more than
0.01 degrees away from a quarter turn, or a mirrored/degenerate text transform, fails UNSUPPORTED_OPTION, rather than trusting PDFBox's coarse
direction classification. Invalid/nonpositive/nonfinite UserUnit or invalid crop dimensions fail
MALFORMED_DOCUMENT. PDFBox recovery still does not establish general PDF conformance. Arbitrary
angles, true vertical/RTL writing and automatic physical/pixel coordinate output remain outside scope.

The [rotation review](../benchmarks/review/pdf-rotation/README.md) records owned transformed scenes,
offscreen rendered image checks, independent saved text matches and retained earlier reading-order
limitations. No Windows VM is required.

## Opt-in native borderless subset

Default and existing seven-argument PdfOptions construction remain RULED_ONLY. To enable the
additional heuristic explicitly:

```java
var options = PdfOptions.defaults()
        .withTableDetection(PdfOptions.TableDetection.RULED_AND_ALIGNED_TEXT);
var processor = DocWeave.create(PartitionerRegistry.builder()
        .register(PdfPartitioner.create(options)).build());
```

Ruled tables own their glyphs first. The remaining native runs can form a table only when they have:

- At least three rows and two columns, with the same nonempty cell count in every row.
- Baselines within two units per row. Row pitch can differ from the first pitch by at most
  `max(2 units, 15% of first pitch)`. For each row, with first-cell glyph height `h`, pitch must
  lie in `[max(4, 1.2*h), min(40, max(18, 5*h))]` PDF user-space units.
- Left or right edges aligned within two units across all rows, including the first row, in each
  column. Neighboring column envelopes must have at least 12 units of whitespace.
- Cells no longer than 80 Unicode code points, and at least one column whose entire body (all rows
  after the first) matches ASCII numeric-like syntax: optional sign, digits separated by dots/commas,
  optional trailing percent. This is syntactic evidence, not number/locale parsing. Original strings,
  leading zeros and escaped HTML text survive unchanged. The first row still emits td, not th.
- No intersecting retained source stroke or image placement, or unrelated ordinary run inside the
  candidate bounds. A gap above 40 units or a single-run paragraph separates proximity blocks;
  ragged multi-column blocks are rejected whole, without searching smaller subgroups inside them.

The result uses `pdf_table_method=aligned_text_heuristic` and `pdf_table_evidence` with rows, columns,
zero-based numeric_columns and policy=aligned_numeric_v1. These are source-table evidence fields,
not probabilities or chunk dimensions. The numeric evidence excludes the first row without declaring
it a semantic header. Every page processed in this mode reports a heuristic limitation warning;
rejected candidates remain identical ordinary elements, with their existing IDs/text/metadata.
The same TableLimits and per-page work ceiling apply; exhausting a limit fails explicitly.

This heuristic can still misclassify aligned numbered lists, side-by-side numeric text or multiple
adjacent logical tables. Single-run separators can also split a partially populated table. It does
not infer empty cells, merged borderless cells, all-text tables, arbitrary alignments,
currency/date syntax, scanned tables or semantic table boundaries. It does not imply full
Unstructured hi_res/auto/fast strategy parity. Corpus and human-layout evidence remain pending.

### Explicit financial token syntax

`RULED_AND_FINANCIAL_ALIGNED_TEXT` keeps the same complete-block alignment, pitch,
column-gap, source-barrier, ownership and resource checks while widening only numeric
body-token classification. It accepts `$`, `€`, `£` or `¥` prefixes, optional horizontal
space/NBSP/narrow-NBSP currency padding, balanced accounting parentheses and Unicode minus.
Examples include `(10.00)`, `$20.00`, `($10)` and `−12.5%`. It preserves the original text,
leading zeros and punctuation; it does not parse amounts or validate locale grouping.

```java
var options = PdfOptions.defaults()
        .withTableDetection(PdfOptions.TableDetection.RULED_AND_FINANCIAL_ALIGNED_TEXT);
var partitioner = PdfPartitioner.create(options);
```

Bare currencies, malformed parentheses, footnotes, dates, exponents, missing-value dashes
and embedded prose are not numeric evidence. All nonempty body cells in at least one
column must qualify. The first row still emits `td`, not a claimed semantic header.
Metadata identifies `pdf_table_evidence.policy=aligned_financial_v1`, and heuristic
warnings remain. Existing ruled/aligned/multiline policies and OCR detection are unchanged.
This run-based strategy remains incompatible with native-first composition requiring exact
glyph ownership. No empty-cell, multiline-financial or semantic boundary inference is added.

Owned accounting/currency positives and rejection controls pass. Complete NVIDIA replay
still scores0/110; syntax alone does not fix its larger structural gap. See
[the frozen financial-policy evaluation](../benchmarks/review/pdf-financial-aligned-native/README.md).

### Narrow multiline extension

`RULED_AND_MULTILINE_ALIGNED_TEXT` explicitly adds a conservative wrapped-cell strategy;
`RULED_ONLY` and `RULED_AND_ALIGNED_TEXT` keep their existing behavior. It retains historical
single-line candidates and considers complete proximity blocks separated by gaps above40 units.
A wrapped block requires an unwrapped first row and at least two body rows, each with the same
number of anchor runs plus exactly one continuation in its first column. No selective wrapped
subtable is searched inside a rejected block.

The continuation must be unrotated, contain a letter, start within2 units of the anchor's left edge,
start below its anchor's baseline, and end at least12 units before the second column. Its baseline
gap must be6–14 units, agree across body rows within1 unit, and be at most40% of anchor-row pitch.
The ordinary numeric/alignment/pitch/80-code-point policy and TableLimits apply to combined cell
text. All source runs, strokes and images are checked against the full union bounds, including
below the final anchor. These constraints are extraction heuristics and can still misclassify lists.

Accepted cells join both lines with one space, escape HTML normally, own each source run once,
and use union text bounds when cell coordinates are enabled. Evidence policy is
`aligned_numeric_multiline_v1`; single-line evidence remains `aligned_numeric_v1`. No invented
empty cells, arbitrary columns wrapping, uneven wrapping, merged cells, OCR change or borderless
continuation links are included. Rejected wrapped candidates retain ordinary text.

Owned positive/negative controls validate this narrow policy. Optional pinned Schools/AnimalSounds
native regressions compare unchanged historical output; they provide no positive multiline oracle
or semantic accuracy estimate. Run `PdfMultilineCorpusTest` with paired
`docweave.pdf.multiline.cache` (existing pinned corpus directory) and
`docweave.pdf.multiline.report` (fresh JSON filename). No automatic downloads or engine execution.
Published ICDAR2013 multiline cell-position annotations are now evaluated separately in
`benchmarks/review/pdf-independent-cells/`: eu-010 (11x2) and us-038 (8x2) have wrapped headers;
eu-011 (5x6) is a partially ruled control. `PdfIndependentCellsTest` verifies six pinned PDF/XML
inputs and records whole-native-document table shapes and exact normalized positional text matches.
Run with paired `docweave.pdf.independent.cells.cache` and a fresh
`docweave.pdf.independent.cells.report`. No automatic downloads, annotation crops or OCR.
Diagnostics retain missed/partial reconstructions, rather than treating body-only extraction as a
complete table. These ruled/partially ruled cases do not establish positive borderless multiline
support. Independent positive borderless examples and broader reviewed layout labels remain pending.

## Opt-in paragraph and two-column layout

`PdfOptions.TextLayout.LINES` remains the default, including the previous seven/eight-argument
constructors. Native text can be grouped explicitly:

```java
var options = PdfOptions.defaults().withTextLayout(PdfOptions.TextLayout.COLUMN_PARAGRAPHS);
var processor = DocWeave.create(PartitionerRegistry.builder()
        .register(PdfPartitioner.create(options)).build());
```

PARAGRAPHS retains geometric top/left order and joins adjacent ordinary lines only with similar
heights (within 25 percent), left edges within 4 units, nonoverlapping baselines (half-unit tolerance),
and a vertical gap at most `max(4, 2*max glyph height)`. The previous line must be at least 40 units
wide and half as wide as the next. Indents, bullets/numbered starts (including Unicode whitespace after the existing
markers), larger gaps, font-size changes,
tables and images terminate groups. Joined text uses one space, retains hyphens and punctuation,
and stays UncategorizedText; no heading/language/NLP inference or dehyphenation occurs. Group bounds
are the union of native line bounds; pdf_text_layout=paragraphs_v1 and pdf_line_count describe the
source paragraph, not an eventual chunk fragment.

COLUMN_PARAGRAPHS first attempts one fixed gutter per page. Exactly two native runs on paired
baselines (within 2 units) with at least 24 units of whitespace provide gap evidence, inset 8 units.
An interval sweep selects a maximally supported gutter, requiring at least 3 pairs and 80 percent
agreement. Text must remain at least 4 units on either side. Spanning text and all tables/images
separate bands; a band needs at least 3 lines per side, vertically overlapping column extents and
no overlap with adjacent barrier bounds. Accepted bands read left top-to-bottom, then right, with
paragraph grouping within each column. Text gets pdf_reading_order=two_column_ltr_v1, one-based
pdf_column and page-local pdf_reading_band identifiers. Structural content/metadata remain unchanged;
central element IDs can change when the selected mode changes grouping or sequence.

`COLUMN_PARAGRAPHS_COMPOUND_WRAPS` keeps those same groups and reading order, and
optionally omits the generated separator after a letter-final physical hyphen when
the next native line starts with a letter. Each complete line must reconstruct
exactly from uniquely bounded original glyph objects before interpretation. Explicit
edge whitespace, mismatched/overlapping ownership, numeric/punctuation boundaries and
unsupported rotated frames retain the historical separator. A baseline-sorted glyph
index bounds source lookup; ownership checks share the existing native geometry work
counter and its `TABLE_LIMIT` failure rather than raising its ceiling. Physical
hyphens and all source characters remain; this is an explicit compound-word assumption,
not dictionary-based word detection or semantic dehyphenation. Tables, OCR and already
source-owned tagged paragraphs retain their independently selected text policies.

```java
var options = PdfOptions.defaults()
        .withTextLayout(PdfOptions.TextLayout.COLUMN_PARAGRAPHS_COMPOUND_WRAPS)
        .withFigureCaptions(PdfOptions.FigureCaptions.IMAGE_EDGE_ALIGNED_NUMBERED_TITLES);
var partitioner = PdfPartitioner.create(options);
```

Changed native groups record `pdf_native_word_wrap_policy=retained_hyphen_wraps_v1`
and `pdf_native_retained_hyphen_wraps`. Bounds, group counts and source line counts
remain unchanged; text-derived IDs can change where a generated space is omitted.
Existing modes and defaults retain their output. The fixed five-original development
check improves column-profile complete caption bodies18/21→19/21, including Scientific
Reports4/5→5/5. Two PeerJ printed-hyphen differences remain strict failures. All15
source/profile preservation checks and18 image-negative observations pass. See the
[native compound-wrap evidence](../benchmarks/review/pdf-native-compound-wraps/README.md)
for exact scope and character-preservation boundaries.

Weak/ambiguous bands fall back to geometric order with conservative paragraph grouping. Page
boundaries always separate processing. A fixed 2M per-page layout-visit ceiling fails LAYOUT_LIMIT;
this is separate from table budgets and is not a parser/heap/latency bound. Every non-default layout
page reports a heuristic limitation warning. Table recognition precedes text layout, so aligned
numeric cells and ruled-table contents are not grouped as paragraphs.

This does not determine logical article/tag/bead order or guarantee newspaper/poem/RTL/three-column
reading order. Short headings, list wraps, changing gutters, floating images and overlapping glyphs
can defeat grouping; clipped/hidden text and source rendering remain unresolved. The independent
[PDFBox corpus review](../benchmarks/review/pdf-reading/README.md) records native inventory preservation
and retained differences against saved PDFBox order. It is not human or Unstructured PDF parity.

## Remaining PDF quality gates

The [current evaluation scope](pdf-evaluation-scope.md) follows observable Unstructured OSS
behavior and relevant public examples. Broader corpus/producer/ICC qualification below is
deferred, not a current completion blocker or an implied passing result.

The native, OCR, word-OCR and local layout-provider implementations are available.
The items below describe unresolved quality gates, rather than missing providers or
a percentage of PDF support. Passing owned controls does not close original-document
or independent human-review gates.

| Gate | Implemented subset | Remaining evidence or behavior |
| --- | --- | --- |
| Modern business tables | Ruled/filled rules, rectangular merges, source-tagged tables, numeric/financial alignment, provider cells and adjacent-page links | Complete rows outside detector crops, source-versus-model header boundaries, noisy/text-only tables and broader continuation remain unresolved. The complete NVIDIA reference population and failed refinement controls remain retained. |
| Scientific reading order | Native/oriented lines, paragraph and compound-column layouts, source-tagged paragraphs, figure-caption association | Complete-body paragraph fidelity and asymmetric sidebar order remain unresolved. Physical wrap hyphens and reference whitespace differences are separate from lost/interleaved text. The sidebar investigation is parked; do not replace missing references or relax scoring to close it. |
| Historical scans | Explicit language/model selection, managed OCR, quarter-turn correction, deskew and source-frame word geometry | Recognition and physical reading order vary across originals. Earlier language/model and segmentation comparisons are retained; no universal winning profile or automatic engine selection is established. |
| Degraded scans | Explicit raster correction, white-point preprocessing and OCR magnification | Improvements are source-dependent; the tested white-point/sparse composition regressed several originals and is not promoted. Broader held-out transcription and calibration remain open. |
| Source images | Actual occurrence accounting, normalized PNGs, mask/stencil handling, invocation graphics state, ordered affine placement corners, opt-in conservative clip bounds and enclosing group context; a separate explicit rendered page-region crop service | Source payloads and placements do not reproduce final compositing, clipping, occlusion or generated descriptions. Ordered-corner checks do not establish rendered-pixel equality. Rendered crops show PDFBox's final page appearance for requested rectangles. They do not isolate or classify source-image visibility, and they establish no general renderer parity. |
| Independent acceptance and operations | Owned regressions, pinned original-source comparisons, bounded parser callbacks, packaged consumer checks | Independent human review, broader authoring-tool diversity, calibrated confidence and production heap/latency evidence remain open. PDFBox/codecs may allocate or delay cancellation before callbacks; limits are not a total decompression/heap bound. |

The [quality backlog](pdf-quality-backlog.md) records parked approaches and return conditions.
Use one proposed change and one focused validation pass per investigation. Park a
failed attempt with its evidence and next bounded action, address other issues,
then return; do not repeat unsuccessful threshold/model grids. Preserve original
reference populations, failed/missing cases, historical metrics and explicit provider
configuration. Report implementation gains separately from validation-only work.

Interactive annotations/forms and vector-only drawings remain outside the initial
element model. Semantic image description is a separate future capability. Further
DOCX work and Python-test migration remain separate backlog items. Cross-format contextual
enrichment is implemented; broader retrieval acceptance and ongoing public-source synchronization
are separate from the PDF quality gates. Java21 is the development test
baseline; final Java25 verification remains deferred to PDF completion/final candidate.

### Separately retained source table captions (unreleased)

Explicit source `Caption` children outside rows can coexist with a source-tagged table
when their page references are valid and their displayed text is outside the table's
cell text envelope. Caption glyphs remain available to normal native fallback and are
not copied into table cells. This does not generate caption descriptions or classify
the retained text as `FigureCaption` elements. Accepted tables record the original
caption reference count in `pdf_table_caption_references`.

The subset permits at most 16 caption nodes and 4,096 caption references per table.
Missing, repeated, cross-page, glyph-free, alternate-text or overlapping caption
content retains whole-table fallback. Caption/cell identities are reserved across
source candidates for conflict detection, then only table cell glyphs are claimed.
Original character, table, structural work and cancellation limits remain in force.
See the [independent captioned-table check](../benchmarks/review/pdf-tagged-reference-tables/README.md).

### Attribute classes on source-tagged tables (unreleased)

Source-tagged tables also accept named attribute classes when every class resolves
through the original root ClassMap to span-free attribute dictionaries with explicit
owners. This supports presentation classes without interpreting their styles. The
subset accepts revision-zero names only, at most 64 names per source element and
128 attribute dictionaries per class, under the shared structural work budget.
On cells, positive integer Table-owner RowSpan/ColSpan attributes can come from these
classes. Later class/attribute-array values take precedence; direct cell attributes
override class values. Span attributes are not inherited from parent elements.
Classes with spans on table/row nodes, namespaced spans, missing names, aliases,
duplicate names, malformed definitions and revisioned associations retain whole-table fallback. Direct spans,
source text ownership, cell geometry and empty-cell rules retain their existing
validation. Invalid class values retain fallback even if a direct attribute could
override them. This subset does not implement general attribute inheritance or namespace/revision resolution;
see the [AMD transfer evidence](../benchmarks/review/pdf-unseen-business-amd/README.md).

### Source logical cells with separately retained gap text (unreleased)

Use the explicit cell-envelope geometry when source-authored table cells should be
retained alongside unrelated ordinary text in gaps inside the enclosing table bounds:

```java
var partitioner = PdfPartitioner.createWithTaggedTables(
        PdfOptions.defaults().withTableCellCoordinates(false))
        .withTaggedTableGeometry(PdfTaggedTableGeometry.LOGICAL_CELL_TEXT_ENVELOPES);
```

The original source roles, spans and cell references supply the logical grid. Every
nonblank glyph center inside a nonempty cell's real text envelope must belong to the
table. Unclaimed glyphs in gaps remain ordinary output; no text is removed, classified
as hidden, attached to a guessed cell or copied into the table. The parent rectangle
encloses the table's actual cell text and may also enclose separate ordinary gap text.
Empty authored rows/columns retain their source slots without invented coordinates.

This option requires source-table extraction, disabled cell coordinates and upright
source-owned text. Missing/reused references, blank tables, cell-interior contamination
and overlapping source-table envelopes retain whole fallback. Existing cell/table/work
and interruption limits apply. Configuration copies retain the selected geometry.
Accepted tables use `pdf_table_geometry_policy=source_logical_cell_text_envelopes_v1`.
This is a logical source representation; it does not establish rendered visibility,
physical cell borders or semantic reading order. Defaults and `LOGICAL_TEXT_ENVELOPE`
retain their original exclusive-parent behavior. Exhaustive switches on the public
geometry enum must account for the new value.

On the frozen original AMD source this explicit option emits all 120 referenced rows,
with 81 exact ordinal text matches; source gap text remains separately present. All
42 earlier observations remain unchanged. See the
[complete development evidence](../benchmarks/review/pdf-unseen-business-amd/README.md).

### Preserve multiline native table cells (unreleased)

Provider-owned and source-tagged table cells can retain distinct upright native text
baselines with the explicit `PRESERVE_LINE_BREAKS` cell text policy:

```java
var layoutOptions = PdfNativeLayoutOptions.defaults()
        .withCellTextJoin(PdfNativeLayoutOptions.CellTextJoin.PRESERVE_LINE_BREAKS);
var partitioner = PdfPartitioner.createWithNativeLayout(
        PdfOptions.defaults(), layoutOptions, tableLayoutProvider);

// Source-tagged tables use the same policy without a model or OCR provider.
var taggedPartitioner = PdfPartitioner.createWithTaggedTables(
        PdfOptions.defaults(), PdfNativeLayoutOptions.CellTextJoin.PRESERVE_LINE_BREAKS);
```

Cell text retains LF between distinct native baselines. `text_as_html` renders these
breaks as escaped cell text separated by `<br>`, including within existing row/column
spans. Horizontally separated runs on one baseline retain a space. The existing
two-point baseline grouping tolerance applies; this is geometric source evidence,
not reconstruction of a table's intended rows or semantic reading order.

Changed tables record `pdf_table_cell_text_policy=native_line_breaks_v1` and
`pdf_table_multiline_cells`. Cell bounds, spans, original glyph ownership and table
row counts stay intact. Original pre-joining cell character limits apply. Mixed or
rotated provider text and non-upright tagged cells keep historical space joining.
This choice does not join wrapped dates or discretionary hyphens. Ordinary text,
heuristic native tables and OCR output retain their existing behavior.

The policy preserves evidence that would otherwise be flattened, such as a model
cell containing separate month/day and year lines. It does not fix the model's
merged header row or improve the frozen NVIDIA strict row score. The original
detector experiment and its limitations are retained in the
[native-text replay evidence](../benchmarks/review/pdf-nvidia-proposal-structures/README.md).
Existing constructors/defaults remain space-separated; exhaustive switches on the
cell text enum need to account for the additional value.
JSON round trips retain LF and `<br>`. Structural table chunking still normalizes
cell whitespace; its original-element provenance retains the extracted multiline
table. Select `skipTableChunking(true)` when chunks must keep the original table
representation directly. Normalized chunks omit the source-only line-preservation
policy/count metadata; those values remain with the original table in provenance.

## Upstream observation

The pre-implementation online check detected released Unstructured 0.27.28 at
`1b02b4d189c260ed9b1aca29583203e233d853cb`. The new commit since the previous review adds OCR agent
load-error cause propagation; it does not implement a native PDF table algorithm. Source review and
freshness evidence are in `benchmarks/review/pdf-native/`. Historical references remain pinned to
0.27.27; the existing strict online freshness gate intentionally reports that release drift until
references are explicitly reviewed/regenerated. Default offline regression checks remain applicable.

## Ruled-table expansion validation

Owned public-facade tests cover side-by-side and stacked tables, disconnected decoration, empty cells,
horizontal/vertical/two-dimensional merges, multiline cell ownership, reversed/duplicated/segmented
strokes, partial/open/L-shaped rejection, nested-bound overlap rejection, a rejected component alongside
an accepted table, atomic-slot budgets and geometry work exhaustion. JSON and real structural chunking
retain merged content exactly once. Limits count atomic slots before merging, not just emitted anchors.
The earlier partial-stroke regression now ends mid-cell rather than at a column boundary: an edge absent
across one complete cell is now a supported rowspan, while a genuinely partial edge remains rejected.
These are owned Java policy tests, not independent rendered-document or upstream PDF accuracy results.

Owned borderless tests cover opt-in/default differences, explicit HTML/evidence, right-aligned numeric
strings, leading zeros/escaping, separate blocks, text/prose/short/ragged/misaligned/uneven rejection,
image/stroke barriers, ruled coexistence, structured limits, old-constructor policy, 16 virtual-thread
shared calls and many-block work exhaustion. A real PDF-to-JSON-to-structural-chunking test retains
accepted table text exactly once. These add local-contract coverage without independent accuracy claims.

Independent rendered-table evidence is recorded in
[the pinned Tabula corpus review](../benchmarks/review/pdf-table-corpus/README.md).
The selected 6×10 Schools crop preserves structure at 200/300 DPI, with 55/60 and 59/60
whitespace-normalized cell matches respectively. AnimalSounds' open grid retains ordinary OCR.
These optional local checks use published extraction expectations, not human-approved physical scans;
cell discrepancies remain diagnostic. Default hosted verification does not execute the external engine.


Merged raster geometry reuses the native bounded topology parser. Painted rule caps are trimmed inward
to observed perpendicular centerlines by at most four rendered pixels; strokes are never extended
across gaps. Missing dividers are additionally checked for attached short dark stubs that the
64-pixel discovery threshold would otherwise omit. Eight contiguous dark samples after a five-pixel
junction allowance reject the candidate. These probes share the existing 2-million-operation budget.
Text touching a junction can conservatively reject a valid merge; tiny/faint/detached remnants or a
fully missing damaged divider can still look like an intentional merge. This is geometric inference,
not recovery of original document cell intent. Independent physical-scan validation remains pending.

Seven owned merged-grid PDF tests cover horizontal/vertical/combined spans, full-interior pixel markers,
empty anchors/HTML escaping/multiline output, rule thicknesses 1/2/3/8, broken/stub/L/open rejection,
quarter-turn geometry, mixed/native ownership, blank-recognition fallback, prospective/raw limits and
concurrent reuse. JSON and structural chunking replay both unmerged and merged scanned tables.
Optional actual Tesseract controls and external unmerged-corpus rechecks are documented in
[the merged-table evidence](../benchmarks/review/pdf-scanned-merged/README.md).

## Optional per-cell geometry

Enable the extension for both native and explicitly configured OCR tables through the shared PDF policy:

```java
final var options = PdfOptions.defaults().withTableCellCoordinates(true);
final PdfPartitioner nativePdf = PdfPartitioner.create(options);
// The same options apply to PdfPartitioner.create(options, ocrOptions, provider).
```

The default is false, including the historical seven/eight/nine-argument constructors. Immutable
`withTableDetection` and `withTextLayout` copies preserve this setting. There is no additional OCR
execution, recognition retry or duplicated cell text when geometry is enabled.

Each recognized Table receives a `pdf_table_cells` object with these version1 fields:

| Field | Meaning |
| --- | --- |
| `version` | Schema version, currently1 |
| `geometry` | `rule_centerlines` for native/OCR ruled tables; `text_bounds` for aligned-text tables |
| `row_count`, `column_count` | Displayed table topology dimensions, including occupied merged slots |
| `cells` | Row-major anchor descriptions, including blank anchors, excluding slots covered by another anchor |

Each anchor has zero-based `row_index`/`column_index`, positive `row_span`/`column_span` and `coordinates`.
There is one description per actual anchor, matching HTML td encounter order. Rotating a page can
exchange rows/columns and span directions; these indexes describe displayed topology rather than an
unrotated source ordering. For example, one anchor from a 400×320 displayed page may be:

```json
{
  "row_index": 0,
  "column_index": 0,
  "row_span": 2,
  "column_span": 1,
  "coordinates": {
    "system": "PDFUserSpace",
    "layout_width": 400.0,
    "layout_height": 320.0,
    "points": [[40.0, 20.0], [190.0, 20.0], [190.0, 260.0], [40.0, 260.0]]
  }
}
```

Coordinates share the parent Table's crop-relative, displayed top-left frame, point order and unscaled
PDF user-space units. Page rotation/crop normalization has already happened; do not apply it again.
Parent `pdf_user_unit` describes physical points per unit and is not multiplied into coordinates.
Ruled bounds enclose the whole merged cell at rule centerlines, including blank cells. OCR bounds
are converted from the actual full-page raster width/height ratios, accounting for integer rendering
rounding; they are not original image pixels, OCR line boxes or the inset/padded cell raster. Aligned
text uses approximate run bounds and does not invent column borders across whitespace. Bounds inherit
the parent extraction's clipping/visibility limitations; they are not visible glyph outlines or
calibrated confidence measurements.

Generation shares existing table dimensions/atomic-slot and fixed geometry-work limits, with one
additional bounded operation per anchor. This opt-in extension increases retained metadata; those
limits remain extraction budgets rather than a peak-heap or codec sandbox. No cell-specific element IDs,
transcription corrections or text copies are introduced.

JSON serialization and compressed original-element provenance preserve the extension. A whole Table
or explicit table pass-through retains it. Structural TableChunk splits and text-only split fallback
remove it from fragment metadata because whole-table coordinates do not describe a fragment; original
coordinates remain in `orig_elements` when provenance is enabled. Fragments retain their existing
`table_cell_fragments` ownership indexes instead of invented clipped pixel boxes. Composite chunks
drop this unrecognized extension under the existing policy. With provenance disabled, split fragments
carry no source cell geometry.

Verification and actual-engine/independent-crop evidence are recorded in
[the cell-coordinate review](../benchmarks/review/pdf-cell-coordinates/README.md).

## Optional adjacent-page table links

```java
final var options = PdfOptions.defaults()
        .withTableContinuation(PdfOptions.TableContinuation.REPEATED_HEADER);
final PdfPartitioner nativePdf = PdfPartitioner.create(options);
```

`NONE` is the default, including the historical seven-through-ten-argument constructors. Other option
copies preserve the selected policy. The same policy works with explicitly configured OCR ruled tables;
it neither enables OCR nor requires/emits cell-coordinate metadata implicitly. Aligned/borderless table
continuations remain unsupported by this policy.

A continuation candidate must meet all these conservative conditions:

- Exactly one emitted Table on each immediately adjacent physical page. Blank pages and pages with
  multiple/ineligible tables break the chain.
- Closed ruled topology, two through32 columns, an unmerged first row and nonblank body content.
- Every first-row label is nonblank and contains a Unicode letter; at least two labels are distinct.
  Labels are compared case-sensitively after Unicode whitespace collapse and NFC normalization.
  Both raw and normalized labels are capped at512 UTF-16 units each and4096 total. Unsupported or
  oversized headers retain normal extraction without a link; no fuzzy OCR correction occurs.
- Identical displayed page size, crop/rotation/UserUnit properties and recognition family. Native
  and OCR tables do not cross-link. Every observed column boundary must agree within two unscaled
  PDF user-space units; body rows/merges may differ.
- The preceding table reaches the bottom20% of its page and the next begins in the top20%. Nonblank
  extracted narrative after the prior table or before the next table blocks a link. Running headers
  and footers can therefore conservatively prevent matching. Source images alone are not narrative
  barriers; unrecognized text/image content is not classified semantically.

Matching never merges Elements, HTML or coordinates, marks semantic headers, removes repeated rows,
or changes extraction IDs/order. Only continuation parts receive `pdf_table_continuation`:

| Field | Meaning |
| --- | --- |
| `version` | Currently1 |
| `policy` | `adjacent_repeated_header_v1` |
| `root_element_id`, `root_page_number` | Original first Table and physical page in this chain |
| `previous_element_id`, `previous_page_number` | Original Table on the immediately preceding page |
| `part_index` | One for the first continuation, increasing through a chain; root is implicitly zero |

The root remains unmodified and carries no speculative group marker. Links are backward references to
real IDs assigned by the central extraction sink, not UUIDs or future IDs. Resolve them against original
partition Elements. Mismatches break a chain; a later matching pair can start a new root. Repeated labels
and aligned rules are heuristic evidence, not proof of document-level semantic identity or a calibrated
confidence score. Different logical tables can still share the same layout and labels.

The optional path buffers one pending page, capped at4096 elements and the remaining document
`maxElements`, before central emission. Exhaustion raises `ELEMENT_LIMIT`. It retains only one capped
previous signature between pages. The disabled path emits directly. All page flushes/matching scans
check cancellation, source evidence construction uses the existing table-work limit, and source rasters
are released before page flush. These are bounded extraction controls, not a total heap/codec sandbox.

JSON and `orig_elements` preserve source links. Table pass-through preserves their original IDs and
links. Normal chunking generates new IDs, so continuation metadata is removed even from an unsplit
whole-Table chunk; when provenance is enabled, its original Table retains the source references.
Structural/text-only fragments and composites likewise carry no dangling continuation IDs. Geometry
retains its separate contract described above.

Owned multi-page PDF, JSON/chunk and actual Tesseract evidence is recorded in
[the continuation review](../benchmarks/review/pdf-table-continuation/README.md). Independently reviewed
semantic continuation accuracy, repeated/multilevel merged headers, missing headers, variable source
frames, native/OCR transitions and broader borderless continuation policies remain pending.

## Independent thin-fill regression

Published ICDAR eu-010 at the pinned Tabula corpus commit paints borders as separate thin filled
rectangles, while its header background uses a large ICC-colored fill. Earlier extraction discarded
all fills and the aligned heuristic emitted only10 body rows. Conservative thin-fill centerlines now
allow the original whole PDF to produce the complete11x2 ruled table with22/22 positional cell-text
matches, including the wrapped header, under both RULED_ONLY and multiline aligned mode. The
optional PdfIndependentCellsTest now gates that fixed published expectation. No annotation crop,
source modification, fixture-specific correction or historical report/golden rewrite is used.

Evidence is in benchmarks/review/pdf-filled-rules/. Owned controls cover multiline header HTML,
cell bounds, crop/UserUnit/all quarter turns, explicit closed line rectangles, device/ICC colors,
0.125/0.5/1-unit rules, white/pale/transparent/wide/curved/compound/skew/unpainted negatives,
a five-unit missing segment, structured limits and concurrent reuse. Existing operator/work budgets
apply. The two other independent layouts (us-038 nested outer rules and eu-011 horizontal rules)
remain unsupported; this does not establish general rule repair or positive borderless support.

## Opt-in native double rules

`PdfOptions.TableDetection.RULED_AND_DOUBLE_BORDERS` enables a conservative normalization pass
before closed ruled-grid recognition. It does not enable aligned-text inference or scanned-rule
normalization; the default and existing aligned modes retain their behavior.

The pass pairs only isolated adjacent rule axes separated by more than the historical0.5-unit
near-duplicate tolerance and at most2.5 unscaled units, with comparable source extents (within2.5).
Triple/chain clusters are not coalesced. Glyph bounds or image boxes intersecting a pair's band
and source extent, including the0.5-unit endpoint halo, veto it. Both original source axes must
have continuous coverage after eligible perpendicular junction mapping; unsupported gaps abandon
normalization for the entire page. Movement is limited to pair/extent halos, with both lines
mapped to the pair midpoint. Closed borders, rectangular merges, overlap/nesting rejection and
table/work/operator budgets still apply; TableLimits count the resulting normalized atomic slots.

Enabled pages warn that narrow empty bands may be intentional cells. Tables carry
`pdf_table_rule_policy=double_border_v1`; optional cell coordinates describe normalized midpoint
rule boundaries. Approximate glyph boxes, ignored vector art, clipping and overpainting remain
limitations. This is an explicit heuristic, not broad damaged-grid repair or author-intent certainty.

Pinned published us-038 now reconstructs the complete8x2 table with16/16 positional normalized
cell texts, including its wrapped header, in the new mode. Ordinary modes retain their7x2 body-only
result. eu-010 remains complete11x2/22 cells; eu-011 horizontal rules remain diagnostic. Reports,
owned rejection/resource controls and source fingerprints: benchmarks/review/pdf-double-borders/.

## Opt-in horizontal-rule text tables

`PdfOptions.TableDetection.RULED_AND_HORIZONTAL_TEXT` adds a narrow native strategy after closed
ruled ownership. Three consecutive rule groups must supply a top rule, header separator and lower
support. Top and separator each need2–32 sequential source segments (length>=12), joins within1.5
units, and corresponding inferred column boundaries within1 unit. Header height8–40, body height
16–800; the next lower rule must match outer extent within2 and paint at least85% of the span.
Partial lower support is not described as a closed grid or a complete cell border.

The whole envelope must contain one complete upright header baseline and at least two complete
body baselines, one native run per nonempty cell,<=80 code points each, letters in every header
label, and at least one numeric-like entire body column. Body pitch[max(8,1.2*glyph height),40]
allows max(2 units,15%) variation; header-to-body gap independently8–min(40,2*body pitch). Glyphs
must stay0.5 units clear of rule/column boundaries. Other intersecting strokes, partial raster
intersections, incomplete rows and overlapping candidates reject inference. An enclosing raster
placement may remain as a separate Image; clipping/overpaint/semantic background classification
is not resolved, so this explicit policy remains heuristic and can misclassify decorated layouts.

Tables emit escaped td text, method `horizontal_rule_text_heuristic`, evidence policy
`horizontal_segmented_numeric_v1`, actual text-union parent bounds and optional text_bounds cell
coordinates. Source segmentation is column evidence, not a claim of painted vertical borders.
No inferred empty/merged/multiline cells, arbitrary all-text tables, rotated displayed tables,
scanned-rule normalization or ruled continuation links are included. Existing TableLimits, shared
work/cancellation, JSON and chunk provenance contracts remain. Rejected cases keep ordinary output.

Pinned whole ICDAR eu-011 now produces5x6 with30/30 positional normalized cell-text matches in the
new mode, splitting header labels that ordinary gap grouping joins. Existing modes keep prior
behavior; eu-010 and double-mode us-038 gates remain. Published inputs/expectations unchanged;
no annotation crops or fixture-specific repairs. Evidence: benchmarks/review/pdf-horizontal-tables/.

## Unified native/OCR page order

`PdfOcrOptions.withReadingOrder(GEOMETRIC)` explicitly buffers one bounded page of native and
OCR elements, then stably sorts by displayed minimum y followed by minimum x. The default
SOURCE_ORDER preserves native output followed by provider OCR output. The former eight-argument
constructor remains and chooses the default; immutable selection/table copies retain ordering.

Enabled source elements carry `pdf_reading_order=page_top_left_v1` and one-based page-local
`pdf_reading_index`, including Images. Missing/invalid geometry sorts last with stable encounter
order. This overrides any native fixed-column ordering; paragraph grouping/column classifications
remain source attributes. It is not logical article, RTL, arbitrary-angle or general newspaper order.
It does not execute an OCR provider when page selection does not otherwise require recognition.

The same4096 pending-element and remaining maxElements caps as continuation buffering apply;
exhaustion fails ELEMENT_LIMIT. Header-signature construction and linking remain independently
opt-in. IDs are generated only when the sorted page flushes, so continuation links reference real
prior source IDs. Recognition text/HTML/images are preserved. JSON retains source indexes; normal
chunks drop pdf_reading_index (it is not a chunk index), source pass-through and orig_elements retain
it. The policy string remains provenance, not a new chunk-position guarantee.

Original cTDaR scan structural diagnostics are in benchmarks/review/pdf-physical-scans/. Those
annotations contain no text oracle; observed misses and explicit work-limit failures remain open
scan support gaps. The active closure gates are tracked in docs/pdf-completion-plan.md.


## Explicit physical and pixel coordinate conversion

`PdfCoordinateConverter.create()` exposes a stateless interface for post-extraction geometry conversion:

```java
final var converter = PdfCoordinateConverter.create();
final var pixels = elements.stream().map(element -> converter.toPixels(element, 300)).toList();
```

`toPoints`, `toMillimeters`, `toPixels(element, dpi)` and `toUserSpace` convert parent coordinates
and every `pdf_table_cells.cells[*].coordinates` anchor consistently, including cell-only whole-table
chunks. Systems are `PDFPoints`, `PDFMillimeters`, `PDFPixels` and `PDFUserSpace`. Physical points
are source user units multiplied by `pdf_user_unit` (absent means one); millimeters multiply physical
points by25.4/72, and pixels byDPI/72. Each PDFPixels coordinate map stores its own `dpi` for reversible
conversion. These virtual page pixels do not describe embedded-image asset pixels or promise to
match an OCR render raster's integer rounding.

Crop-relative top-left origin and displayed quarter-turn normalization are already applied; conversion
never applies crop or rotation again. Source `pdf_crop_box`, `pdf_rotation`, `pdf_user_unit`, actual
`pdf_ocr_dpi` and image asset dimensions stay unchanged. Negative/off-page positions remain valid.
Identity, text, HTML, topology/spans and continuation references are preserved. Chunk fragment geometry
remains governed by the existing removal/provenance policy. Convert before or after chunking as needed;
conversion does not rewrite compressed `orig_elements` source snapshots.

Unknown/non-PDF systems, malformed four-point geometry, nonpositive dimensions/UserUnit/DPI and
nonfinite arithmetic throw IllegalArgumentException. No geometry returns the original element; all
converted results take immutable metadata snapshots. Conversion invokes neither rendering nor OCR.


## Repeated running headers and footers

`PdfMarginClassifier.create().classify(elements, physicalPageCount)` explicitly tags repeated
ordinary text as Header/Footer after extracting one complete PDF. Supply the actual physical count,
including blank pages; do not substitute the number of pages with extracted text. Default partitioning
is unchanged. This post-extraction service never removes or reorders elements, text or geometry, and
preserves source IDs. It does not invoke OCR; it also accepts ordinary OCR text with displayed geometry.

Policy `repeated_margin_v1` requires identical case/punctuation/numbers after whitespace reduction,
at least three distinct pages and60% of physical pages, and normalized left/right/top/bottom positions
varying by at most2% of page extent. A candidate fits wholly inside the top/bottom8% of page height,
is at most4% tall, contains letters and has at most160 normalized UTF-16 units (640 raw units).
Numeric/changing page numbers, rotated text, tables/images/list items, duplicate same-text margin
occurrences on any page, repeated body text and inconsistent positions are left unchanged. Existing
explicit Header/Footer categories are preserved. A repeated heading near a page edge can still be a
false positive; this is opt-in geometric evidence, not a semantic or calibrated-confidence claim.

Metadata `pdf_margin_classification` records policy, source_type, matching_pages and physical_pages.
It survives direct JSON serialization and remains in chunk source provenance; composite chunks drop
that source-only extension. Unit/DPI conversion works before or after tagging. Source lists are limited
to100,000 elements and supplied physical counts to10,000 pages; cancellation is checked while traversing.
Invalid page references, mixed supplied filenames/non-PDF MIME types and malformed candidate coordinate
maps fail explicitly. Missing geometry and structural categories remain untouched. The caller must supply
one document: identical/missing filenames cannot independently prove common document identity.


## Explicit OCR raster correction

A provider can apply a known clockwise correction before recognition:

```java
final var options = PdfOcrOptions.defaults();
final var corrected = PdfOcrProvider.rotated(provider, -7.5, options);
final var partitioner = PdfPartitioner.create(PdfOptions.defaults(), options, corrected);
```

`rotated` is an opt-in interface factory with a hidden immutable decorator. It neither estimates the
angle nor starts a provider until recognition. Correction applies to both PAGE and TABLE_CELL purposes.
Exact quarter turns use nearest-neighbor rotation; other finite angles use bilinear resampling on an
expanded white canvas. Each result rectangle is inverse-transformed, intersected with the original
raster and enclosed by an axis-aligned source-frame box. Predictions wholly in added padding disappear;
partially intersecting predictions get clipped envelopes. These approximate envelopes do not recover
original glyph outlines. Provider order and text of retained predictions are preserved.

The borrowed input is never modified/flushed. Temporary rasters are released even on provider failure.
Source and expanded canvases must individually fit maxRenderPixels before allocation, and raw returned
lines/characters must fit the decorator's per-call limits; integration retains aggregate page limits.
Cancellation is checked around graphics/provider work and between output lines. Graphics operations
and provider allocations remain cooperative/resource limitations, not a process sandbox. Invalid
provider results fail OCR_FAILURE; resource failures use OCR_LIMIT. A shared decorator relies on the
underlying provider's concurrent-call contract.

Returned bounds already refer to the original supplied raster, so PDF crop, quarter-turn rotation,
UserUnit and table-cell ownership are not applied a second time. Retain the explicit correction angle
with application configuration/evaluation provenance: the provider line contract carries no per-line
transform metadata. This corrects OCR input only: native text, original Image elements, source pixels,
scanned rule detection/topology and page geometry are unchanged. Separate explicit automatic
quarter-turn/small-angle strategies are documented below; general arbitrarily rotated scanned-table
detection remains unsupported. Owned deterministic controls plus
explicit installed-English Tesseract tests cover fixed corrections; they do not establish independent
physical-scan accuracy or calibrated confidence.


### Open rule components and table resource limits

Native and rendered ruled detection tests the connected component's four outer borders before
applying structured row/column/cell caps. An incomplete outer perimeter is unsupported ordinary
geometry and does not cause TABLE_LIMIT merely because prospective slots exceed those caps.
Perimeter probes retain the shared fixed work/cancellation limit; topology matrices are allocated
only after a closed perimeter passes all existing structured limits. Closed oversized grids still
raise TABLE_LIMIT. This improves ordinary OCR fallback for noisy/open scans without repairing missing
rules or recognizing additional table structures. Original cTDaR_s003 now emits ordinary OCR and its
source Image; neither published table is recognized, and no text transcription oracle exists.


## Opt-in arbitrary-angle native lines

`PdfOptions.defaults().withTextLayout(PdfOptions.TextLayout.ORIENTED_LINES)` retains arbitrary-angle
native lines in addition to the existing quarter-turn subset. Defaults and historical paragraph modes
retain their explicit quarter-turn-only failure. Positive-determinant text transforms with finite
positive advance/height are required; mirrored/degenerate or unrepresentable metrics still fail.
PDFBox's decoded Unicode is retained; no font/Unicode repair, bidi semantics or hidden-text filtering
is inferred.

Source text-matrix/end positions are already crop-relative in an unrotated bottom-left frame. Baselines
are grouped by angle rounded to0.01 degrees, projected into upright line coordinates, and passed through
the existing two-unit baseline and space/large-gap grouping policy. Result boxes are inverse-projected
and transformed once into the displayed crop/quarter-turn frame. Enclosing rectangles are approximate,
not glyph outlines; tight slanted lines, skewed font matrices and complex scripts need broader evidence.
`pdf_text_rotation` is the continuous clockwise displayed angle and `pdf_text_geometry` is
`projected_baseline_v1`. Source UserUnit stays separate until explicit coordinate conversion.

Projected lines retain ordinary text type and geometric top/left order. They are not joined into
paragraphs. Their bounds veto intersecting native ruled-table ownership and double-border collapse;
unrelated closed grids remain eligible. When any arbitrary-angle line exists, optional aligned,
multiline-aligned and horizontal-text table inference conservatively falls back for that page rather
than assigning unmodeled angled glyphs to inferred cells. Default ruled inference on other pages is
unchanged. This does not support arbitrarily rotated native table structure.

Arbitrary-angle native text counts as native content for textless-page OCR selection. Explicit mixed
image OCR masks its projected envelope with the existing guard band/pixel/work limits, preventing
native/OCR duplication. The same maxGlyphsPerPage and cancellation limits apply. Direct JSON/unit
conversion and rounded chunk source provenance retain the geometry policy/angle. Owned analytical,
multidirectional, crop/quarter-turn, closed-table barrier and mixed-ownership controls are not broad
independent arbitrary-layout accuracy evidence.


## Combined oriented lines and upright column paragraphs (unreleased)

```java
var options = PdfOptions.defaults().withTextLayout(
        PdfOptions.TextLayout.ORIENTED_COLUMN_PARAGRAPHS_COMPOUND_WRAPS);
var partitioner = PdfPartitioner.create(options);
```

This explicit development mode combines the existing compound-aware upright column strategy
with arbitrary-angle line retention. The same fixed gutter, grouping limits, source-preserving
physical hyphens and quarter-turn/crop/UserUnit transforms apply. Every nonzero projected
angle, including fractional degrees, remains a structural barrier: it cannot enter an ordinary
paragraph or supply native table-cell glyphs. Tables, source images and source-owned paragraphs
retain their existing barriers. Source text, projected coordinates and geometry provenance stay
unchanged; grouping earlier lines can change ordinal-derived element IDs. On documents without
angled positions, output is identical to `COLUMN_PARAGRAPHS_COMPOUND_WRAPS`.

This is not semantic reading order or an automatic fallback. The earlier modes still reject
unsupported source orientations. Full-document scientific-magazine evaluation keeps their three
failures visible and compares the existing oriented-line mode separately. Image-placement
mismatches remain open; see the [fixed comparison](../benchmarks/review/pdf-oriented-columns/README.md).
This enum is not part of published beta.6.


## Conservative automatic small-angle OCR deskew

`PdfDeskewEstimator.create(maxPixels).estimate(image)` exposes bounded projection evidence without
invoking OCR. `PdfOcrProvider.deskewed(provider, options)` applies an accepted angle to PAGE calls using
the existing inverse-bound rotation decorator. TABLE_CELL calls are forwarded unchanged; scanned
rule detection and table topology are not deskewed. This projection policy does not estimate quarter turns; the explicit automatic estimator is documented below.
Caller configuration, estimator measurements and engine/model identity should be retained externally
when reproducible preprocessing provenance is needed; provider lines carry no transform metadata.

The policy samples the whole raster at up to1000 pixels per axis, composites transparency against white,
and considers RGB channels<=100 dark. Fewer than200 ink samples or<0.2% ink is insufficient;>120,000
samples or>25% ink is ambiguous without projection. Nonprobabilistic scores count ordered equal-row
ink pairs over81 angles from-10 to10 degrees in0.25-degree steps. Acceptance requires an interior
peak at least0.5 degrees from zero,>=15% score improvement over zero,>=5% improvement over candidates
at least1 degree away, and at least two concentration bands separated by four analysis rows at25%
of peak row count. These fixed heuristic gates are not fitted/calibrated probabilities. Dense/busy,
blank/sparse, upright/near-zero, boundary and insufficiently separated inputs return zero correction;
zero scores mean projection was not performed. Pixel-grid sampling and resampling introduce error.

Source raster pixels are bounded before analysis; sampled scans are capped at1M visits and projection
work at82x120,000 points plus bounded histograms. Cancellation is checked by row and every1024 projection
points. Ambiguous PAGE calls use zero-degree correction to retain uniform output/raster validation;
they may allocate a same-sized temporary RGB canvas. Accepted expanded canvases remain subject to
maxRenderPixels and raw output limits. The source raster remains untouched. TABLE_CELL forwarding
retains the underlying provider's policies and the integration's aggregate page budgets.

Owned text controls, ambiguity/graphics/transparency/budget/cancellation/concurrency cases and explicit
installed Tesseract raster/PDF controls establish this small-angle subset only. Drawings, unusual line
spacing, vertical scripts, photos and page layouts can produce misleading peaks; an accepted measurement
is not proof of text orientation or improved recognition. Broad independent deskew/physical-scan/table
accuracy and calibrated confidence remain open completion gates.


## Independent published table-image labels

`PdfPubTabNetCorpusTest` optionally evaluates all20 pinned PubTabNet examples (1380 published anchors)
against their supplied HTML/span/text labels. They are upstream training examples and publisher-provided
article table crops, not original physical scans or proof of held-out engine training data. Inputs are
fetch-only. A whole-image, no-repair resolution comparison exposes both recognition and OCR misses;
no output becomes a new reference or a claim of general accuracy. See
[the corpus evidence](../benchmarks/review/pdf-pubtabnet/README.md) for attribution, metrics and profiles.

At explicit300DPI, one independent21x4 merged table matches69/69 anchor positions/spans and all69
published nonempty text centers fall inside corresponding cell envelopes. Installed English OCR matches
52/69 exact normalized cell strings; no full shape-plus-text match is claimed. Other examples remain
unrecognized as structured tables. The existing150DPI default stays unchanged. These observations add
independent merged-topology evidence but leave broad physical-scan/borderless/text accuracy and
probability calibration open.


## Optional actual-word OCR geometry

`PdfWordOcrProvider` extends the existing line SPI with `recognizeWords(image, kind)`.
Each immutable `WordLine` contains the original line and complete ordered `Word` values.
Joining token texts with one space must reconstruct the line exactly, and each positive,
finite token rectangle must fit the line envelope. Coordinates use the supplied raster's
top-left pixel frame. These are provider boxes, not glyph outlines or confidence scores.

```java
final PdfWordOcrProvider provider = TesseractOcr.createWithWords(options);
final var lines = provider.recognizeWords(image, PdfOcrProvider.InputKind.PAGE);
```

The optional Tesseract adapter retains real TSV word coordinates in one subprocess per
request. It shares the same admission, deadline, cancellation, raster, PNG, stdout,
line and character limits as line recognition. Nonblank tokens also bound token count
by the character budget. Line-only calls retain their existing text/order and do not
allocate token collections. Constructing a provider neither downloads nor starts an engine.

Existing line-only providers remain valid. This capability is currently available on the
raw Tesseract adapter; rotation/deskew decorators expose only the line SPI and do not
promise detailed geometry. Detailed output alone does not enable additional PDF table
inference or change extraction defaults. Future table ownership must consume actual
words explicitly and preserve words outside accepted cells.


## Explicit aligned-word scanned tables

Select `PdfOcrOptions.TableDetection.ALIGNED_WORDS` with a `PdfWordOcrProvider`
to infer narrow borderless numeric tables from one PAGE recognition call:

```java
final var ocr = PdfOcrOptions.defaults()
        .withTableDetection(PdfOcrOptions.TableDetection.ALIGNED_WORDS);
final var partitioner = PdfPartitioner.create(
        PdfOptions.defaults().withTableCellCoordinates(true), ocr,
        TesseractOcr.createWithWords(engineOptions));
```

This is a separate strategy from `RULED`; it does not run anchor-cell OCR or combine
closed-rule and word inference. A line-only provider produces `UNSUPPORTED_OPTION`
when the selected OCR page is processed. Defaults remain `NONE`.

Actual word boxes are projected using the actual page/raster ratios into displayed
user-space units. Bottom ordinates within2 units form rows; nonoverlapping words
separated by less than12 units form a cell run. The existing aligned-numeric detector
then requires at least3 rows, uniform column count and row pitch, consistent column
edges, at least12 units between columns, and at least one column numeric in every
body row. Long dark raster rules are ambiguity barriers under the existing bounded
run scanner. There is no missing-rule repair, merge, empty-cell, multiline-cell,
handwriting, currency/date or semantic layout inference. Short/faint rules and graphics
are not reliably classified; opt-in alignment can still produce false positives.

Accepted cells emit escaped all-td HTML, `scanned_aligned_words_heuristic_v1`, explicit
numeric-column evidence and optional `pdf_table_cells` with `text_bounds` geometry.
Cell bounds are text envelopes, not invented border polygons. Table continuation
links are not inferred for this strategy. Shared raw line/character budgets apply
before filtering; generated table separators are charged conservatively in addition
to source line text. `maxTableCells` caps total accepted anchors, and structured table,
raster scan and geometry-work limits remain active.

Ownership records original word indices. Unaccepted source lines remain byte-for-byte
unchanged. If a source line contains accepted table words plus outside words, contiguous
unowned word sequences retain source order and actual enclosing geometry. Mixed-page
native exclusions apply to eligible words, the complete table envelope and ordinary
remainders. No inferred table masks or drops an entire spanning source line.

Optional PubTabNet evaluation accepts `docweave.pdf.pubtabnet.tableDetection` alongside
the existing uniform DPI/page-mode properties. The historical closed-rule positive
merged-topology gate applies to `RULED`; word-strategy results remain independently
measured diagnostics, with historical reports/goldens preserved.


## Used-font and Unicode-mapping diagnostics

`PdfFontInspector` is an optional interface service independent of element extraction:

```java
final var inventory = PdfFontInspector.create().inspect(pdfBytes);
for (final var font : inventory.fonts()) {
    System.out.println(font.name() + ": " + font.unmappedGlyphs());
}
```

The hidden implementation traverses actual source character-code encounters, including
repeated form paints, without retaining document text, positioned glyphs or PDFBox
objects in the result. Unused font resources are excluded. Distinct source dictionaries
with the same font name remain separate; one-based identifiers follow first encounter.
Page membership uses physical page numbers, including preceding blank pages.
Invisible text-layer encounters also count; this is not an inventory of visible shapes.
Type3 glyph drawing programs are not interpreted as additional text streams.

Each immutable font entry reports source name/subtype, embedded/damaged/vertical and
standard14 properties, declaration of a `ToUnicode` entry, total glyph-code encounters,
null/empty mappings and mappings containing U+FFFD. A ligature mapping still represents
one source-code encounter. Missing ToUnicode is legal for many simple fonts, and a
nonempty mapping may come from a font encoding/fallback. Declared maps may be wrong;
plausible but incorrect Unicode, visual glyph identity, substitution fidelity, language,
reading order and semantic accuracy are not diagnosed by these counts. No mapping is
repaired or substituted by DocWeave and no extraction defaults are changed.

Defaults cap encoded input at64MiB and distinct used fonts at256, with the existing
PDF page/operator/glyph limits. `create(pdfLimits, maxFonts, maxInputBytes)` exposes
these policies; layout/image options in `pdfLimits` are irrelevant to inspection.
Borrowed input bytes must remain stable during the synchronous call and are not
modified or retained afterward. Limits do not bound PDFBox intermediate/font parsing
or decompression allocations or system fallback-font resolution. No page is rasterized.
Encryption/extraction permissions are respected, interruption is cooperative, and
nested-form errors propagate rather than being swallowed by PDFBox recovery.

Optional `docweave.pdf.fonts.cache` and `.report` properties inventory the three pinned
original native PDFs already used for independent cell validation. Input hashes are
checked; output reports use a fresh path. Their observed mapping counters are diagnostics,
not published font-correctness labels or proof that all document text is accurate.


## Explicit complete-layout provider SPI

`PdfTableLayoutProvider` supplies complete table geometry independently of OCR and any
model runtime. Select `PdfOcrOptions.TableDetection.PROVIDER_LAYOUT` and the four-argument
`PdfPartitioner.create(pdfOptions, ocrOptions, words, layout)` factory. Both providers
are caller-owned thread-safe interfaces and borrow the same rendered/native-masked
raster. There is one PAGE word call and one layout call; no anchor-cell OCR or implicit
model discovery/download occurs. Supply any model adapter separately;
this SPI alone does not establish model extraction accuracy.

Provider `Table` values declare atomic rows/columns, a source-pixel envelope, bounded
provenance and rectangular `Anchor` values with explicit row/column spans. Arbitrary
anchor encounter order is accepted, then emitted in row-major order. Validation checks
all raw layouts before mixed-page selection: raster/envelope containment, nonoverlapping
geometric interiors and logical slots, physical row/column order, complete coverage,
disjoint table envelopes and table/aggregate-anchor/work limits. No atomic slot matrix
is allocated: disjoint in-range rectangular coverage plus exact summed slot area proves
completeness. Invalid claims produce `OCR_FAILURE`; table ceilings produce `TABLE_LIMIT`.

Actual word centers use half-open anchor bounds for unique assignment. Words may extend
past a predicted edge; these are provider geometry estimates, not perfect character
segmentation. Empty cells and rectangular merges remain explicit, and multiple source
lines assigned to one cell join with one space. The caller's cell-text budget and shared
raw/inserted-text budgets apply before output. A layout with no assigned words emits
no Table. Unowned source words survive through the same remainder helper used by
ALIGNED_WORDS; unaccepted lines preserve original text/order and mixed native exclusions.

Tables emit escaped HTML with rowspan/colspan, `provider_layout_words_v1`, explicit
`pdf_table_layout_provenance`, and optional `pdf_table_cells` geometry marked
`provider_rectangles`. Coordinates use the actual raster-to-displayed-page ratios once.
Continuation links and calibrated confidence are not inferred. Existing factories,
ruled/aligned strategies, OCR defaults and dependency isolation remain unchanged.

## Optional local ONNX table structure

Add `io.docweave:docweave-layout-onnx:0.1.0-beta.8` explicitly to use
`io.docweave.layout.OnnxTableLayout`. Neither PDF nor the convenience artifact brings
ONNX Runtime transitively. There is no ServiceLoader registration, graph discovery,
model download or implicit native execution. The application supplies a trusted standalone
ONNX graph and its expected SHA256, owns the provider and closes it after partitioning.

```java
var options = OnnxTableOptions.defaults(localGraph, expectedSha256);
try (var layout = OnnxTableLayout.create(options)) {
    var ocr = PdfOcrOptions.defaults()
        .withTableDetection(PdfOcrOptions.TableDetection.PROVIDER_LAYOUT);
    var partitioner = PdfPartitioner.create(pdfOptions, ocr, wordProvider, layout);
    var processor = DocWeave.create(PartitionerRegistry.of(List.of(partitioner)));
    var result = processor.partition(pdfPath);
}
```

The supported graph contract is FLOAT `pixel_values[1,3,H,W]`, INT64
`pixel_mask[1,H,W]`, FLOAT `logits[1,Q,7]` and `pred_boxes[1,Q,4]`, with published
TATR labels table/column/row/column-header/projected-row-header/spanning-cell/background.
Dynamic metadata dimensions are checked against actual output tensors. Preprocessing
uses max-height/max-width800, aspect-preserving floor sizing, two-pass antialiased
bilinear8-bit RGB, white alpha composition, ImageNet normalization and an all-valid mask.
This is the explicitly documented cross-domain export profile in
[model feasibility evidence](../benchmarks/review/pdf-table-model-feasibility/README.md),
not a claim of full parity with Microsoft's original inference pipeline.

A bounded local read verifies graph identity before session creation from bytes. The graph
is read once into an exact-size, read-only off-heap snapshot. That snapshot is hashed and is
the buffer handed to ONNX Runtime, so loading does not commit Java heap for the whole graph,
and ONNX Runtime makes no further JNI copy. It counts against the JVM's direct-memory limit,
which defaults to the maximum heap size.

Managed sessions disable ONNX Runtime's per-session CPU arena and memory-pattern
preallocation. The arena would otherwise keep activation regions resident after each run and
hold one region per simultaneous run; outputs are unchanged. On the frozen packaged provider
study (two admitted calls, YOLOX detection and financial TATR structure, Apple original,
`-Xmx512m`), child VmHWM fell from 1.76–1.84 GiB to 1.28–1.39 GiB with identical outputs. See
[ONNX memory evidence](../benchmarks/review/pdf-onnx-memory/README.md). These measurements are
single-host observations, not a total process-memory bound.

Inputs,
raw query count, aggregate prospective atomic slots, row/column dimensions, geometry work,
admission concurrency and total call duration have explicit limits. Deadlines include
queueing/preprocessing/native inference/postprocessing. A daemon timer cooperatively
terminates native runs on caller interruption/deadline; cleanup excludes concurrent
run-options release. Native session closure waits for active calls and leaves the shared
runtime environment open. These policies do not sandbox arbitrary native graphs or bound
all initialization/native allocations: only trusted compatible graphs are supported.

Geometry-only refinement suppresses overlapping row/column estimates and forms shared
axis boundaries. Complete noncrossing rectangular spans merge atomic slots; conflicting
merges and overlapping table envelopes are rejected. Empty cells remain explicit. Column
headers are not assigned semantic HTML header tags. Model scores are thresholds, not
calibrated confidence. Whole-page detection and cell-specific OCR use the separate explicit
policies below; automatic orientation is a separate explicit estimator documented below. This structure model is intended for table images.

ONNX Runtime1.23.2 packages Linux x64/aarch64, macOS x64/aarch64 and Windows x64 native
libraries; local execution has been validated on Linux x64 only. Java25 applications
should explicitly allow native access (`--enable-native-access=ALL-UNNAMED` on the
classpath); Java21 accepts the same option. No Windows VM is required for this work.

The optional published corpus can select `docweave.pdf.pubtabnet.tableDetection=PROVIDER_LAYOUT`,
with explicit `.layoutModel`/`.layoutSha256`, existing engine/cache properties and a fresh
report. It evaluates whole rendered PDF images against the unchanged published HTML/text
labels. Candidate counts alone are not extraction accuracy; see the ONNX evidence report.


### Explicit anchor-cell OCR

`PdfOcrOptions.TableDetection.PROVIDER_LAYOUT_CELLS` uses the same four-argument
partitioner factory and geometry validation, then one `TABLE_CELL` recognition per
eligible row-major anchor. The one PAGE actual-word call remains necessary for ordinary
remainder ownership; it is not repeated. Both modes remain opt-in and use the supplied
recognizer/layout interfaces. Model/runtime and OCR defaults remain unchanged.

Cell crops round source bounds outward, add12 white pixels on each side and retain
estimated boundary pixels. Each crop is copied into an owned RGB image and released
after recognition; the borrowed source remains unchanged. All eligible projected crops
are preflighted before the first CELL call, subject to `maxRenderPixels` and64M projected
fill/copy pixel visits per page. Raw provider-anchor limits bound cell calls. PAGE and CELL
outputs share line/UTF-16 budgets; Unicode cell limits and inserted-space accounting apply.
The source-word assignment is also validated conservatively before refinement.

CELL text is authoritative, including blank cells; source PAGE tokens within an emitted
table's anchors are removed from ordinary output. If all refined cells are blank, no
Table is emitted and PAGE words survive unchanged. A layout can also be populated by
CELL recognition when PAGE words were empty. Tables use `provider_layout_cells_v1`;
provider provenance, span HTML and source-frame cell coordinates remain available.
No fallback selector invents a confidence score or silently chooses between PAGE and
CELL texts. Engines may perform worse on individual crops. Measured tradeoffs, render
resolution and engine segmentation must accompany any accuracy claim.

### Whole-page detection before structure

The explicit alternative `OnnxTableLayout.createWithYoloX` uses a compatible caller-supplied
YOLOX graph for page detection and the existing TATR graph for child table structure:

```java
final var detection = OnnxTableOptions.yoloXDefaults(localYoloXGraph, yoloXSha256);
final var structure = OnnxTableOptions.defaults(localStructureGraph, structureSha256)
        .withMergeConflicts(OnnxTableOptions.MergeConflicts.KEEP_HIGHER_SCORE);
try (final var layout = OnnxTableLayout.createWithYoloX(detection, structure)) {
    final var partitioner = PdfPartitioner.createWithNativeTablesFirst(
            PdfOptions.defaults().withTableDetection(PdfOptions.TableDetection.RULED_AND_PARTIAL_TEXT),
            new PdfNativeLayoutOptions(150, 16_000_000, 1024), layout);
    final var processor = DocWeave.create(PartitionerRegistry.of(List.of(partitioner)));
    final var result = processor.partition(DocumentSource.of(pdfPath));
}
```

The detector requires FLOAT `images[1,3,1024,768]` and `output[1,16128,16]`, eleven
published classes and table class8. It uses unnormalized RGB, top-left114 padding,
pixel-center bilinear uint8 rounding and white alpha composition. This JVM profile
does not assert bitwise OpenCV/Unstructured parity. All classes participate in
inclusive-area NMS0.1 before table selection and clipping. Raw probabilities and
coordinates are validated even for discarded background queries. The detector's
default threshold is strictly greater than0.25 for objectness times strongest class
score; structure options retain their own threshold. Use `yoloXDefaults` rather than
ordinary256-query defaults for the fixed16,128-query detector. At most2,048 pre-NMS
candidates and the shared detector/crop/topology work budget are admitted. The
existing crop, slot, native-thread, admission, deadline and lifecycle limits apply.

The managed implementation reproduces all11 fixed original-page proposal sets and
both complete detector/structure geometry profiles from the earlier experiment.
This makes the alternative available to applications; it does not improve the
unchanged NVIDIA0/110 strict statement-row result or establish broad model accuracy.
Models are not bundled, downloaded or discovered, and the default detector is unchanged.
See [managed detector evidence](../benchmarks/review/pdf-managed-yolox/README.md).
The [paired scan check](../benchmarks/review/pdf-managed-yolox-scans/README.md)
retains nine original inputs and 20 published tables: YOLOX gains one spatial
match but loses one exact dimensions/unique-anchor match. Detector choice remains
explicit; neither candidate count nor this limited study establishes a better default.

The two-options `OnnxTableLayout.create(detectionOptions, structureOptions)` factory owns
separate three-label detection and seven-label structure sessions. Explicit pinned local
graphs and expected hashes are required for both. The detector label order is table,
rotated-table, background. Production uses the same explicit max-height/max-width800
bilinear profile for both stages. The detector's published legacy shortest/longest800
processor differs in integer rounding on some inputs; this is an explicitly selected
profile, not untouched-published-processor parity.

```java
var detection = OnnxTableOptions.defaults(localDetectorGraph, detectorSha256);
var structure = OnnxTableOptions.defaults(localStructureGraph, structureSha256);
try (var layout = OnnxTableLayout.create(detection, structure)) {
    // Supply layout to the existing four-argument PDF partitioner factory.
}
```

The full pipeline shares the shorter configured deadline, one geometry-work counter and
the stricter row/column/aggregate prospective-slot policies, capped at2M atomic slots.
Each session retains its own admission/native-thread/model-byte/input/query limits.
At most32 positive detections are accepted before duplicate suppression; remaining
partial overlaps are ambiguous and both are dropped. Ordinary detections receive10
source pixels of context clipped to page edges. All crop pixel budgets are checked
before child inference, with64M projected fill/copy visits across the page. Each owned
RGB crop is released after inference; the borrowed page is unchanged.

Child envelopes and anchors translate once by the actual clipped integer crop origin.
Both graph hashes appear in bounded provenance. Any overlapping final table envelopes
are dropped together, retaining ordinary PAGE words. Native closure stops parent
admissions before releasing child sessions; partial initialization cleans up the first
session even when the constructing thread is already interrupted.

Rotated-table detections currently remain unclaimed for ordinary PAGE-word fallback;
automatic page orientation uses the separate explicit estimator documented below. Detection does not improve OCR
by itself or establish calibrated confidence. The optional corpus properties
`.detectionModel` and `.detectionSha256` enable the two-model factory under either
`docweave.pdf.pubtabnet` or `docweave.pdf.scans`; both must be supplied together.
The physical-scan profile preserves its historical RULED/150DPI defaults and adds
explicit `.tableDetection`/`.dpi` selection. Independent candidate/topology/text outcomes
must accompany any quality claim; physical-scan completeness remains unproven.


## Explicit automatic quarter-turn estimation

`PdfOrientationEstimator` is a caller-owned thread-safe strategy, injected through
`PdfPartitioner.createOriented`. Existing factories retain their prior behavior. The optional
Tesseract adapter and usage are described in [Tesseract orientation](ocr-tesseract.md#explicit-quarter-turn-orientation).
Accepted0/90/180/270-degree corrections precede PAGE OCR, ruled/aligned tables and explicit
provider-layout inference on a shared upright raster. Original crop/page rotation/UserUnit
coordinates are restored once for parents and cells; native text and extracted image payloads
retain their existing behavior. Mixed-page ownership is checked in the original raster frame.

Raw scores and abstentions are explicit metadata, not probabilities. This capability does not
close independent physical-scan quality or confidence-calibration gates. Generic provider-cell
crops retain border pixels; an authored ruled fixture produced missing cell text on that path,
while the border-removing RULED strategy extracted all six cells at every quarter turn.
No global segmentation, crop or confidence default was changed to hide that limitation.


## Optional managed cell-edge preprocessing

The explicit Tesseract provider can select `CellBorders.REMOVE_EDGE_RULES` for
TABLE_CELL inputs, including PROVIDER_LAYOUT_CELLS. It preserves the borrowed source,
PAGE input, image payload and coordinate dimensions; long dense dark rows/columns near
cell edges are whitened in a same-size owned copy before encoding. RETAIN remains the
default. See [policy, resources and limits](ocr-tesseract.md#optional-cell-edge-rule-removal).

This solves the authored six-cell generic-grid control at all four quarter turns. It is
not a general table OCR improvement: the fixed20-image300DPI structure-only CELL profile
changed from336 to334exact positional-span-text cells, despite token matches rising
from791 to793. The earlier unmodified and new-policy outcomes are both retained; no
transcription or table geometry was repaired. Independent text/table quality and
confidence-calibration gates remain open.


## Word-preserving OCR correction decorators

`PdfWordOcrProvider.rotated(words, clockwiseDegrees, limits)` and
`PdfWordOcrProvider.deskewed(words, limits)` retain the actual-word capability required by
ALIGNED_WORDS and explicit provider-layout table modes. The existing `PdfOcrProvider`
factories keep their line-only capability contract. Implementations share one owned
rotation/inverse-frame helper and keep all per-call state local.

Fixed correction applies to PAGE and TABLE_CELL calls, forwarding the purpose unchanged.
Source and expanded rasters are checked before allocation. Raw line/UTF-16 limits apply
before white-margin tokens are discarded. Actual token polygons are inverse-transformed
and clipped to the original raster; no token positions are invented from a line string.
Retained line text is reconstructed from retained token strings in encounter order. The
line envelope also encloses its restored word boxes to absorb floating-point differences
when parent/child polygons meet the same edge. Text and word geometry stay immutable.

Automatic small-angle correction applies to PAGE only; TABLE_CELL calls pass through to
the underlying word provider, whose limits still apply alongside core aggregate budgets.
Uncertain PAGE evidence uses zero correction and the same bounded output validation.
This does not deskew table detection/topology pixels or establish logical reading order,
recognition accuracy or calibrated confidence. Whole-pipeline quarter-turn estimation
continues to use `PdfPartitioner.createOriented` with an explicit orientation estimator.

Owned controls cover all quarter turns,45-degree polygon clipping, raw budgets before
clipping, bad outputs, expanded raster preflight, cancellation/reuse,200seeded edge cases,
virtual-thread sharing and original-frame provider-table integration. Fetch-only official
Tesseract rotation/transcription fixtures additionally exercise real actual-word correction
with their supplied published angles; those are explicit-angle controls rather than
automatic orientation/calibration or physical-scan accuracy evidence.


## Explicit running-margin bands and first-page body handling

Matching keys collapse Unicode whitespace, including NBSP, narrow spaces and Unicode
line separators; original element text, IDs and coordinates stay untouched. Case,
punctuation, numbers and zero-width non-whitespace characters remain significant.
Body-duplicate vetoes use the same key. Numeric zero rotation is recognized across
JSON-compatible number types; tiny nonzero BigDecimal angles cannot underflow into
upright eligibility. Reclassify original untagged output to use these fixes; explicit
Header/Footer elements remain preserved and idempotent.

`PdfMarginClassifier.create(PdfMarginOptions.defaults())` preserves the original
8percent bands and all body-occurrence vetoes. Callers may explicitly choose separate
header/footer fractions and allow matching body occurrences only on physical page1:

```java
var policy = PdfMarginOptions.defaults().withBands(0.08, 0.16).withFirstPageBodyText(true);
var tagged = PdfMarginClassifier.create(policy).classify(elements, actualPhysicalPageCount);
```

Fractions must be finite, greater than0 and no more than0.2. The flag does not identify
a semantic cover page, and body occurrences on later pages still veto classification.
Support, coverage, text/height/position and ambiguity constraints remain unchanged.
Configured tags record `repeated_margin_configured_v1`, both fractions and the flag in
`pdf_margin_classification`; default metadata retains `repeated_margin_v1`. Neither
policy removes or merges text, regenerates IDs, changes coordinates or reorders elements.

The independent first12 whole RFC9110 pages retain439 source elements. Default policy
misses all57 labelled margins; footer-band configuration alone finds12; the explicit
first-page allowance plus the wider footer band finds33headers and24footers with no
extra tags. This is source-labelled evidence for one technical publisher, not general
semantic cover/header/footer recognition or confidence calibration. See
[running-margin evidence](../benchmarks/review/pdf-margins/README.md).


## Explicit native presentation-form expansion

`PdfOptions.defaults().withUnicodeNormalization(PdfOptions.UnicodeNormalization.PRESENTATION_FORMS)`
expands only Alphabetic/Arabic presentation scalars U+FB00..FDFF and U+FE70..FEFF with
per-scalar NFKC before native glyph grouping/table assembly. RAW remains the default,
including all previous PdfOptions constructors. Option-copy methods retain the selected
policy. Source glyph coordinates stay unchanged; normalized text participates in ordinary
output IDs and table-character limits. Native text/table metadata records
`pdf_native_unicode_policy=presentation_forms_nfkc_v1`. Images and OCR text are unchanged.

This avoids blanket compatibility conversion: mathematical supplementary letters, micro
signs, circled/fullwidth forms and combining sequences outside those blocks remain decoded
as supplied. It does not reconstruct bidi order, apply contextual Arabic font repairs,
trim decompositions or fix incorrect font mappings. Selected-policy retained output has
an additional16million UTF-16 unit ceiling per page, checked before builder appends and
across glyphs, with cancellation every1024input units. Font decoding can allocate before
callbacks; source glyph/operator limits and existing PDFBox resource caveats still apply.

Five pinned original PDFBox Unicode/ligature fixtures with saved expectations produce
three exact text correspondences in RAW mode and five under the explicit policy, with
source element geometry/types retained. Reference UTF-8 BOM handling is transport only;
no extracted PDF characters are stripped. This is maintained-reference correspondence,
not broad Unicode/bidi/font correctness. See [Unicode evidence](../benchmarks/review/pdf-unicode/README.md).


## Independent native slanted-text evidence

An opt-in validation test now checks the original Mozilla pdf.js `rotated.pdf`, pinned by
revision, SHA256, bytes and Git blob identity. Visual inspection and its original text
matrices independently label one45-degree word (315 degrees clockwise in displayed
coordinates). ORIENTED_LINES preserves that word and the full case-sensitive scalar
inventory; finite source-frame bounds, JSON round trip and repeatability are checked.
The default strategy continues to reject unsupported angles explicitly. This is one
upstream regression fixture, not a logical-order or tight glyph-box accuracy oracle.
See `benchmarks/review/pdf-native-angle/README.md` for fetch-only source and invocation.


## Explicit multilingual scan evaluation

The optional original-scan harness accepts `docweave.pdf.scans.languages=chi_sim,eng`,
an ordered comma-separated list of installed Tesseract models. It uses the existing
explicit multilingual engine API, validates identifiers and records exact model hashes.
Absent this property, historical English-only selection/report entries remain unchanged.
This separates an explicit source-language configuration from the older English-only
structural diagnostic; the cTDaR XML still supplies no transcription oracle. No language
inference, automatic download or recognition guarantee is added. See
`benchmarks/review/pdf-multilingual-scans/README.md` for the fixed profile and limitations.


## Independent PAGE and CELL OCR strategies

`PdfPartitioner.createWithCellOcr(nativeOptions, ocrOptions, pageWords, layout, cellLines)`
selects separate recognition dependencies for PROVIDER_LAYOUT_CELLS. PAGE recognition
still requires actual provider word boxes, preserving ordinary text outside accepted tables.
Each accepted padded anchor crop goes to the line-only `cellLines` strategy. It may use
its own explicit installed model, language list or segmentation policy; it needs no word
capability and no fabricated token boxes. Existing factories continue to reuse one provider.

`createOrientedWithCellOcr` additionally accepts a whole-pipeline orientation estimator.
The page-word and layout strategies receive the corrected whole raster; the cell recognizer
receives crops from that same frame. Emitted parents/cells are restored once to original
source coordinates, and source image placements remain unchanged. Selecting another table
mode with these factories fails during construction, before any provider is executed.

All strategies remain caller-owned and must support concurrent calls and honor interruption.
Construction performs no model discovery/download or execution. Crop preflight, raw PAGE/CELL
line/character budgets, table text limits, blank-cell fallback and transactional source-word
ownership are shared through the existing pipeline. Engine/model-specific admission, process,
timeout and allocation limits remain each provider's responsibility; this composition adds
no global native-process sandbox or calibrated recognition guarantee.

Owned integration controls cover independent call routing and actual PAGE words, empty cells
and outside text, all four quarter-turn source bounds, crop preflight, aggregate raw blank-line
limits, interruption/failure identity and20 concurrent virtual-thread documents. The explicit
installed-engine control additionally runs the same known six-cell serif table at all four
quarter turns with separate PAGE RETAIN and CELL REMOVE_EDGE_RULES providers, retaining the
older shared-engine and RULED controls. These authored positives are not general scanned-table
accuracy evidence.


The optional PubTabNet harness can select a different installed English CELL model directory
with `docweave.pdf.pubtabnet.cellTessdata`, while retaining PAGE weights and inferred geometry.
It requires PROVIDER_LAYOUT_CELLS and fingerprints the separate model. The fixed20-image
`tessdata_best` comparison is negative overall (322 exact cells versus336), so no default
changes follow. See `benchmarks/review/pdf-cell-models/README.md` for both gains and losses.


## Independent text-envelope versus cell-crop coverage

The optional `.pdf.pubtabnet.geometry.*` profile isolates structure-model geometry using all20
original images and1230 published text boxes, including missing positional-span anchors in
the denominator. It distinguishes continuous cell rectangles from the actual floor/ceil source
crops and uses the PDF drawing transform. Paired Java21/25 evidence finds454 boxes fully inside
rounded crops and131 with no matching anchor. These are rectangle diagnostics, not glyph or
OCR accuracy; source labels/render rounding remain distinct. See
`benchmarks/review/pdf-text-envelope/README.md`. No crop geometry or extraction defaults change.


## Explicit bounded provider-cell context

`PdfOcrOptions.withCellContextPixels(4)` adds optional rendered-pixel context for
PROVIDER_LAYOUT_CELLS. Values0through16 are accepted; every former constructor/default
retains0. Context is clamped to the provider table before outward rounding and existing
white padding. Original reported parent/cell rectangles stay unchanged; positive selections
record pdf_cell_ocr_context_pixels and policy table_clamped_input_pixels_v1. Other modes
ignore the setting. Expanded crops retain existing preflight pixel/work and output limits.

The fixed20-image evaluation is negative overall:320 exact cells versus336 at context0,
with2improved/6regressed/12unchanged inputs. Source context can collect neighboring content;
geometry-envelope coverage alone does not establish an OCR improvement. This remains an
explicit caller-validated option, with no default change. See
`benchmarks/review/pdf-cell-context/README.md` for controlled pixel/budget/rotation checks
and the complete evaluation profile.


## Explicit OCR magnification with source-frame output

`PdfOcrProvider.upscaled(provider, 3, limits)` adds line-only nearest-neighbor integer
magnification; the corresponding PdfWordOcrProvider factory retains actual provider words.
Multipliers1through8 are explicit, with source/enlarged raster checks before allocation,
raw output validation, unchanged purpose/text and inverse source coordinates. Use separate
PAGE/CELL factories to magnify only cell OCR while keeping PAGE words/layout pixels fixed.
Source images are never modified or released by the decorator. Per-call limits do not bound
all native-provider memory or Java2D latency. Pixel-sized downstream policies see enlarged pixels.

The fixed20-image3x profile is negative overall (288 exact cells versus336, seven improved/
ten regressed/three unchanged); no default change follows. Local decimal recovery is not
proof of general recognition improvement. See `benchmarks/review/pdf-ocr-magnification/README.md`
for contracts, native/owned controls, fixed preprocessing interactions and preserved tradeoffs.

## Explicit handling of conflicting model merges

`OnnxTableOptions.withMergeConflicts(OnnxTableOptions.MergeConflicts.KEEP_HIGHER_SCORE)`
keeps an accepted higher-score rectangular merge and discards an entire overlapping lower-score
proposal. Atomic cells left outside accepted merges remain ordinary cells. Slot ownership is
preflighted before any assignment, so discarded proposals cannot leave partially merged cells.
Equal-score conflicts, nonrectangular selections, ambiguous axes and overlapping final tables
still reject the table. Model scores order proposals; they do not estimate extraction correctness.

The default REJECT_TABLE and former constructor remain conservative. All copy methods preserve
the explicit selection, and selected table provenance includes merge_conflicts=keep_higher_score.
The policy runs under the existing query, prospective-cell, two-million geometry-step and total
call budgets. Preflight work for discarded proposals is charged; this option can consume more
geometry work than default rejection. No source pixels, OCR tokens, table text or annotations
are consulted to select the winning merge.

The motivating development-selected image and actual Unstructured comparison are documented
in `benchmarks/review/pdf-upstream-table/README.md`. Upstream avoids complete rejection on the
same decoded objects but still produces an incomplete reference topology. Fixed-corpus evaluation
of the new strategy is separate from that upstream comparison; neither establishes general
recognition quality or upstream parity.

## Combining observed raster rules with model layouts

`PdfTableLayoutProvider.preferRuled(model, tableLimits, ocrOptions)` composes the existing
closed-rule detector with an explicit caller-owned provider. It validates both complete raw
layout sets, prefers ruled tables over positively overlapping model tables, and retains all
disjoint model tables. Model errors and malformed claims still fail; overlaps are not clipped
into invented cells. No rules means the model's layout values/order/provenance are preserved.

Rule anchors describe source interiors inset five pixels from observed rule centerlines,
matching the supported thickness bound and direct ruled-cell OCR. Their provenance records
`raster_ruled_interiors_v1;inset_pixels=5;precedence=ruled_first_v1`. The enclosing table keeps
its observed outer rule-centerline bounds. These cell coordinates describe interiors rather
than full painted cell extents. Unowned source words remain eligible for ordinary OCR output.

The facade reads borrowed pixels without copying, changing, flushing or retaining the raster.
Its per-call detector/validation/selection state is isolated for concurrent calls. Existing
pixel scans, two-million composition geometry steps, structured dimensions and a combined raw
anchor ceiling apply, including discarded model proposals. Integration applies its own existing
validation budget as well. Secondary deadlines/resource limits remain the secondary provider's;
the facade checks caller interruption but cannot impose a hard native execution deadline.
Keep ownership of any managed model and close it separately after all composed calls finish.

This is an explicit strategy choice. A closed drawn grid does not prove table semantics, and
whole overlapping model proposals are suppressed rather than repaired. Independent corpus
evaluation is needed for the caller's document types; existing default factories are unchanged.


The explicit rule-preferred provider composition was also checked against all nine pinned
cTDaR scan inputs on Java21 and25. All per-input structural diagnostic records match
the control:12 candidate tables, one OCR_LIMIT input and three exact dimension/anchor-set
matches at the highest table-envelope overlap. No additional scan gain is demonstrated.
See [the paired evaluation](../benchmarks/review/pdf-ruled-scans/README.md); the sources
have no text-transcription oracle, so this is not a scan text-accuracy result.

## Explicit native text rendering modes

### Optional native figure captions (unreleased)

Use the explicit geometry strategy when labelled native captions immediately follow
one substantial raster image:

```java
var pdf = PdfOptions.defaults()
    .withTextLayout(PdfOptions.TextLayout.COLUMN_PARAGRAPHS)
    .withFigureCaptions(PdfOptions.FigureCaptions.IMAGE_ADJACENT_LABELS);
var service = PdfPartitioner.create(pdf);
```

`NONE` remains the caption default, including every previous constructor. The strategy
classifies an existing upright native text unit beginning with an English
`Fig/Figure N.` label. It neither creates text nor merges caption continuations;
line layout can classify only a leading caption line, while paragraph layout may
retain a complete caption in one unit. Source text, bounds, sequence and image
payloads remain unchanged. Central element IDs reflect the selected type.

Eligible images are at least36x18 displayed points. The caption unit must start
0–24points below the image, have a left edge within12points, and overlap at least
half the narrower horizontal extent. Multiple eligible images or cues prevent
classification. Units with rotation metadata, unsupported labels or more than1024
UTF-16 units retain their ordinary type. Pages exceeding4096 output units,
128eligible images or256cues retain ordinary types whole. Existing shared geometry
work and cancellation limits remain active. These thresholds are heuristics, not
confidence estimates or semantic correctness guarantees.

Classified elements record `pdf_figure_caption_policy=image_adjacent_label_v1` and
`pdf_figure_caption_image_bounds` as left/top/right/bottom displayed source points
on the element's page. Tables and OCR output do not enter this native strategy.
It does not interpret Alt/ActualText as a caption, recognize vector-only figures
or classify unlabelled captions. Existing default output is unchanged.

On the fixed three-publisher-original check, all10complete captions remain present
as text. The column profile improves from0/10 to4/10 complete classified captions;
all four eLife units also exactly equal their publisher label plus full caption,
without extra text. All six PLOS captions remain unclassified. The line profiles
classify leading units but retain0/10 complete-caption matches. All18 original
logo/Alt/ActualText negative controls pass. This is a limited development result,
not general caption-box or image-association accuracy. See the
[complete evidence](../benchmarks/review/pdf-native-figure-captions/README.md).

`IMAGE_ADJACENT_HORIZONTAL_LABELS` separately recognizes tabs and Unicode
Space_Separator characters in the label cue, including the U+2002 EN SPACE used
after the numbered labels in the two PLOS originals. It keeps those characters
in output and uses exactly the same image geometry, work caps and ambiguity rules.
Line/paragraph separators and empty caption bodies do not satisfy this cue.
Selected elements record `image_adjacent_horizontal_label_v1`; the original ASCII
policy and its provenance remain unchanged.

Use `.withFigureCaptions(PdfOptions.FigureCaptions.IMAGE_ADJACENT_HORIZONTAL_LABELS)`
to select it. On the fixed development population, the column profile now
classifies all 10 complete caption bodies; line profiles classify six complete
PLOS captions and four partial eLife units. Full all-text results remain exact,
and all 18 logo/Alt/ActualText negative controls pass. Strict entire label-plus-caption
equality remains 4/10: six publisher label punctuation/EN-space differences are
retained and separately diagnosed without repairing source text or awarding strict
credit. No extra caption-body text is accepted by that diagnostic. This does not
establish general caption geometry or association accuracy. See the
[complete horizontal-separator evidence](../benchmarks/review/pdf-horizontal-figure-captions/README.md).

An additional explicit policy accepts a numbered label followed by a capitalized
title without a period, or a colon followed by a nonblank body:

```java
var options = PdfOptions.defaults()
    .withFigureCaptions(PdfOptions.FigureCaptions.IMAGE_ADJACENT_NUMBERED_TITLES)
    .withTextLayout(PdfOptions.TextLayout.COLUMN_PARAGRAPHS);
```

This English title-format heuristic uses the same bounded, unique image relation
and horizontal separators. Lowercase unpunctuated prose references remain ordinary
text. Provenance is `image_adjacent_numbered_title_v1`; text, punctuation and image
geometry remain unchanged. It does not reconstruct line-wrap words or guarantee
semantic caption association. Existing policies and defaults remain available.

On the fixed four-original development population, the column profile classifies
14/16 complete publisher caption bodies, up from10/16 with the horizontal policy.
All six PeerJ leading caption units classify; two bodies still fail strict matching
because the PDF prints word-wrap hyphens absent from publisher XML. Line profiles
classify7/16 complete bodies, retaining partial-unit failures. All12 original
case/profile text/reference trees and18 image-negative observations are unchanged.
See the [numbered-title evidence](../benchmarks/review/pdf-numbered-title-captions/README.md).

`IMAGE_EDGE_ALIGNED_NUMBERED_TITLES` is a separate opt-in alternative that accepts
horizontal space before the ordinal's period/colon and either a left- or right-edge
image alignment. It retains the12-point edge tolerance,0..24-point vertical gap,
half-narrower-width overlap, substantial-image sizes, caps and unique relation rules.
For example, use `.withFigureCaptions(PdfOptions.FigureCaptions.IMAGE_EDGE_ALIGNED_NUMBERED_TITLES)`.
Provenance is `image_edge_aligned_numbered_title_v1`. No source text, geometry or
caption continuation is reconstructed; original policies/defaults remain unchanged.

On a new publisher's frozen-code transfer, the earlier numbered-title policy
classifies2/5 complete bodies in the column profile. After that source becomes a
development case, the explicit edge-aligned strategy classifies4/5, with one full-text
strict miss retained. Across all five now-observed originals, columns classify18/21
complete bodies and line profiles7/21. All15 full-text/reference trees are unchanged,
the previous policy reproduces all15 records exactly and18 fixed image-negative
records remain exact. This is development coverage, not independent association
precision or general PDF accuracy. See [the complete edge-alignment study](../benchmarks/review/pdf-edge-aligned-captions/README.md).

The next development beta adds an opt-in native text selection policy:

```java
var pdf = PdfOptions.defaults().withTextRendering(PdfOptions.TextRendering.PAINTED_MODES);
var processor = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(pdf))));
var result = processor.partition(Path.of("report.pdf"));
```

Import `io.docweave.DocWeave`, `io.docweave.partition.PdfOptions`, `PdfPartitioner`,
`PartitionerRegistry`, `java.util.List` and `java.nio.file.Path`.
This API is a development addition after0.1.0-beta.1, not part of that published artifact.

`ALL` remains the default, including when using every previous PdfOptions constructor.
`PAINTED_MODES` excludes PDF text rendering modes3 (neither fill nor stroke) and7 (clip only).
Fill, stroke, fill/stroke and their painting-and-clipping variants remain eligible. The filter
runs before duplicate suppression, native table text assignment and OCR page selection.
Every visited source position still consumes the glyph ceiling, including filtered text.
Retained native text/tables describe the policy as `pdf_native_text_rendering_policy=painted_modes_v1`;
OCR output and image references do not receive that native-policy label.

The unreleased `NONZERO_ALPHA_PAINTED_MODES` option additionally excludes text
when every enabled paint operation has zero effective graphics-state alpha.
Fill-only text uses nonstroking alpha; stroke-only text uses stroking alpha;
fill-and-stroke remains eligible when either is positive. Positive fractional alpha,
including very small values, is retained without a visibility threshold. The
filter runs before duplicate suppression and marked-content capture, after charging
the source glyph budget. Eligible native elements record
`pdf_native_text_rendering_policy=nonzero_alpha_painted_modes_v1`.
Use `pdf.withTextRendering(PdfOptions.TextRendering.NONZERO_ALPHA_PAINTED_MODES)`
to select it explicitly. Existing `ALL` and `PAINTED_MODES` behavior is unchanged.

Invisible text often provides a useful searchable OCR layer on scanned pages. Selecting this
policy deliberately removes that layer and can make a page eligible for explicitly configured
textless-page OCR; it does not discover or invoke an OCR provider by itself. With no OCR provider,
a filtered page can have no text. Image placements and native rule geometry remain available.

These are graphics-state selection policies, not a visible-text renderer. Under `PAINTED_MODES`, transparent,
white-on-white,
occluded, clipped or hidden optional-content text can still be extracted. It does not apply
redactions or prove sensitive content was removed from a PDF. Source bytes are unchanged.
The alpha-aware option still does not evaluate soft masks, transparency-group compositing,
white-on-white paint, clipping, occlusion or optional-content visibility.
Owned regression PDFs cover all8 modes, exact overlapping hidden/painted copies, discarded
position budgets, legacy construction/copies, explicit OCR selection and retained transparent/
clipped painting-mode text. This is no new broad-scan accuracy claim.
See the [alpha-policy controls and complete original-document regression](../benchmarks/review/pdf-native-text-alpha/README.md).

## Fixed detector-window evaluation

A [nine-input, paired-JDK detector-window experiment](../benchmarks/review/pdf-tiled-regions/README.md)
compares full-page detection with fixed upper/lower65%-height windows. It recovers one modern
table region but loses an existing strict topology match and fails to recover the retained
historical-page case. The unfused tile pool has two exact topology matches versus three for
the whole-page control. It is rejected as a replacement; no production policy/default or
beta2 artifact changes. This is diagnostic evidence, not a new PDF feature or completion gate.

## Explicit compact dark raster grids

`PdfTableLayoutProvider.ruled(limits, ocrOptions, RuledProfile.COMPACT)` explicitly recognizes
closed dark grids with uninterrupted runs>=32pixels and atomic cells>=16x12pixels. STANDARD
uses the historical64-pixel runs and32x24cells. These are fixed borrowed-raster pixel values,
not DPI-adaptive sizes. All pixel/run/work/dimension/anchor limits remain. Colored/open/skewed
or ambiguous grids are not repaired. Existing three-argument preferRuled and direct RULED
recognition retain STANDARD; word-layout barrier extraction also remains unchanged.

```java
final var ocr = PdfOcrOptions.defaults()
        .withTableDetection(PdfOcrOptions.TableDetection.PROVIDER_LAYOUT_CELLS);
final PdfTableLayoutProvider layout = PdfTableLayoutProvider.ruled(
        TableLimits.defaults(), ocr, PdfTableLayoutProvider.RuledProfile.COMPACT);
// Use with an explicit actual-word provider and separate cell OCR provider:
final PdfPartitioner partitioner = PdfPartitioner.createWithCellOcr(
        PdfOptions.defaults().withTableCellCoordinates(true), ocr, pageWords, layout, cellOcr);
```

The rule-only strategy needs no ONNX model or native runtime. To combine observed grids with
an explicit caller-owned secondary, use the four-argument preferRuled overload with COMPACT.
It validates both raw sets and counts discarded anchors before overlap precedence, preserving
secondary failures and lifecycle ownership. Service APIs remain interfaces; profile enums are
immutable values and implementations remain hidden.

Both profiles use five-pixel ceil/floor cell interiors, so compact interiors can be tiny and
lose useful glyph pixels. Positive geometry is not OCR accuracy; no border inset or OCR scale
is silently changed. Compact provenance appends `rule_profile=compact_dark_v1`, propagated as
`pdf_table_layout_provenance`. Defaults and their provenance are unchanged. The fixed9-source
150DPI production comparison improves rule-only topology0to2 exact tables, without additional
resource failures; those were already model successes. See the
[scoped production evidence](../benchmarks/review/pdf-compact-ruled/README.md). Broad PDF quality
and held-out acceptance remain open; this is not general scan accuracy or upstream parity.

## Implicit closure of native filled rules

PDF fill operators close open subpaths, as specified in the
[Adobe PDF reference, filling](https://opensource.adobe.com/dc-acrobat-sdk-docs/pdfstandards/pdfreference1.4.pdf).
For one three-edge subpath, DocWeave now supplies that implicit closing edge before applying
the existing strict thin-rectangle, color, opacity and grid tests. It does not close an open
stroke, join compound subpaths, infer a missing table border or relax geometry tolerances.
Subpath state resets after painting/discarding; compound fills remain unsupported even when
their combined recorded edges happen to outline a rectangle. Filled-and-stroked behavior
remains on the existing stroked-edge path.

On the frozen original procurement PDF this recovers15 source-painted border centerlines
and one14x11 table instead of14x2 and14x8 fragments.152/154 saved CSV positions match; two
extra-space differences remain. The financial disclosure case retains440/440, and the
statistics control still emits no Table. These are small development-corpus results, not
general accuracy or a complete physical-span oracle. See
[the production evidence and controls](../benchmarks/review/pdf-implicit-filled-rules/README.md).

## Explicit partially ruled tables with multilevel headers

Available starting with published0.1.0-beta.5; this option is absent from0.1.0-beta.4.

`PdfOptions.TableDetection.RULED_AND_PARTIAL_TEXT` retains closed-grid recognition first, then
opts into a separate native strategy through the existing partitioner interface:

```java
var options = io.docweave.partition.PdfOptions.defaults()
        .withTableDetection(io.docweave.partition.PdfOptions.TableDetection.RULED_AND_PARTIAL_TEXT)
        .withTableCellCoordinates(true);
var registry = io.docweave.partition.PartitionerRegistry.of(java.util.List.of(
        io.docweave.partition.PdfPartitioner.create(options)));
var result = io.docweave.DocWeave.create(registry).partition(java.nio.file.Path.of("report.pdf"));
```

This narrow strategy requires three consecutive horizontal rule groups: one full-width top
interval, and matching separator/lower partitions with2–32 original sequential intervals.
Each column must be at least12 unscaled units wide, joins and upper extents must agree within
0.5 units, and separator/lower boundary lists must match exactly. Header height is12–96 units;
body height is16–800 units. No missing paint is silently repaired. Partial vertical body paint
is allowed only on supported column boundaries, within the body envelope. Other intersecting
rules, diagonal/header dividers, any intersecting raster placement, nonupright text, crossing
body glyphs and intersecting candidate envelopes reject recognition. Clipping/overpainting and
semantic author intent are not determined. Unpainted column and row areas remain inferences.

Two to eight header baselines precede at least two complete body baselines. Each header line
retains its source text once; its enclosing supported columns determine an inferred colspan.
Overlapping header anchors on the same baseline reject the candidate, and uncovered header
positions become explicit empty anchors. This can mistake adjacent unseparated labels for a
spanning label; it is not semantic header understanding. Every header baseline needs letter
evidence. Header strings are capped at80 code points per spanned column, while the caller's
`maxCellCharacters` still applies to the complete anchor. Body cells require exactly one nonblank
line of at most80 code points in every position. Missing body cells, wrapped bodies and tables without a complete numeric body
column are unsupported. Body pitch must be8–40 units, at least1.2 times the first glyph height,
with tolerance max(2 units,15% of pitch). At least one full body column must match the existing
ASCII numeric syntax; no placeholder, footnote, locale or number correction is applied.

Output uses `pdf_table_method=partial_rule_text_heuristic`, evidence policy
`partial_segmented_multilevel_numeric_v1` and an explicit inferred `header_rows` count. With
cell coordinates selected, `pdf_table_cells.geometry=inferred_cell_bounds`: column edges follow
supported rule partitions, row edges follow the envelope/separator and text-gap midpoints.
These are neither glyph bounds nor fully observed painted cells. Header anchors emit colspan
once; no covered-cell placeholders or rowspans are fabricated. Empty header anchors retain their
inferred areas. The parent bounds use the supporting source-rule envelope. Coordinates remain
crop-relative, unscaled displayed PDFUserSpace, with UserUnit reported separately. Automatic
page rotation, scanned OCR and continuation linking are not added by this policy.

Rejected text stays ordinary output. Accepted glyphs are owned once, all raster elements remain,
and standard table/input/glyph/operator/output/work budgets and interruption checks still apply.
The existing default and all earlier table modes are unchanged. There is no external engine,
model, network access, new executor or dependency. The optional batch service can already run
independent documents on bounded virtual threads.

The [fixed production evidence](../benchmarks/review/pdf-partial-rule-text/README.md) records
owned controls and all six uniformly applied native profiles on the three unchanged external
PDFs. That small development set is not a held-out accuracy or general table-recovery guarantee.

## Explicit provider geometry with native text

Development milestone beta.6 adds `PdfPartitioner.createWithNativeLayout(PdfOptions,
PdfNativeLayoutOptions, PdfTableLayoutProvider)`. The service borrows a full-page RGB raster
for caller-supplied complete layout geometry and uses original native glyphs as cell text.
The optional ONNX adapter can provide that geometry without a Tesseract dependency. There
is no automatic model download/discovery or OCR call. The application owns and closes any
managed provider after all extraction calls finish.

```java
// layout is an explicitly configured, thread-safe PdfTableLayoutProvider.
var nativePolicy = new io.docweave.partition.PdfNativeLayoutOptions(150, 16_000_000, 1024);
var pdf = io.docweave.partition.PdfPartitioner.createWithNativeLayout(
        io.docweave.partition.PdfOptions.defaults().withTableCellCoordinates(true),
        nativePolicy, layout);
var processor = io.docweave.DocWeave.create(
        io.docweave.partition.PartitionerRegistry.of(java.util.List.of(pdf)));
var result = processor.partition(java.nio.file.Path.of("report.pdf"));
```

Policy defaults are150DPI,16M render pixels and256 raw provider anchors;1024 above is an
explicit caller choice. On native-text pages provider layout replaces native table heuristics,
including when a provider returns no tables. Unclaimed native glyphs remain ordinary text,
all image placements remain, and empty tables claim no text. Textless pages retain ordinary
image output and no-OCR warnings without invoking the native-layout provider. This factory
does not combine native layout with scanned-page OCR or infer reading order/continuations.

The full provider output is validated for raster containment, complete slot ownership,
nonoverlapping geometry and raw anchor/table/work ceilings before glyph assignment. Glyph
centers belong to half-open provider cells; existing native spacing/baseline grouping is
applied inside each cell and wrapped lines join with a space. Case, numbers, punctuation,
currency spacing and Unicode remain subject to the explicitly selected native policies only.
Nonempty tables commit ownership once and emit spans/blank anchors from the supplied topology.
Metadata uses `pdf_table_method=provider_layout_native_glyphs_v1`, `pdf_text_method=native`,
`pdf_native_layout_dpi` and provider provenance. Optional cells use `provider_rectangles` in
the same displayed unscaled PDFUserSpace as parents; geometry is not a physical-cell oracle.

Declared page and native-baseline quarter turns now share the rendered displayed frame.
Glyph ownership maps through the existing crop/rotation geometry; cell text groups baseline
directions separately and orders their enclosing lines by displayed top/left. This preserves
native strings, but does not infer semantic order within mixed-orientation cells. Arbitrary
native angles remain unsupported by this factory and fail rather than silently falling back. Crop offsets and UserUnit are
preserved in the existing coordinate contract. Rendering is pixel-limited before allocation
and checked afterward, with raster release on success/failure. Source glyph/operator/input,
structured text/slot/work/output limits and interruption remain active. Provider errors or
invalid complete topology use the shared `OCR_FAILURE` diagnostic even without recognition;
render limits use `TABLE_LIMIT`. Provider-specific timeout codes/causes remain intact. There
is no new total-document deadline or guarantee on PDFBox intermediate allocations.

The [live production evidence](../benchmarks/review/pdf-native-provider-layout/README.md)
records a full-original-document run on both Java21/25. Both fixed issuer statement tables
match59/59 HTML row-content references, compared with42/59 PAGE OCR and44/59 CELL OCR on
selected-page raster derivatives. These are different extraction representations on the same
sources, not controlled inference-only comparisons or general accuracy. Physical span labels,
held-out breadth, historical/degraded scans and the broader PDF gates remain open.

## Explicit native-first table composition

Development code provides `PdfPartitioner.createWithNativeTablesFirst(PdfOptions,
PdfNativeLayoutOptions, PdfTableLayoutProvider)`. Select this factory explicitly to keep native
tables under the supplied native policy and use provider layout for disjoint remaining tables.
Native accepted tables and exact detector-unclaimed glyphs are staged before output; ownership
is never guessed from a table envelope. Provider text uses only those original unclaimed glyphs.
Source images and remaining ordinary native text are emitted once.

```java
var nativeOptions = io.docweave.partition.PdfOptions.defaults()
        .withTableDetection(io.docweave.partition.PdfOptions.TableDetection.RULED_AND_PARTIAL_TEXT)
        .withTableCellCoordinates(true);
var pdf = io.docweave.partition.PdfPartitioner.createWithNativeTablesFirst(
        nativeOptions, new io.docweave.partition.PdfNativeLayoutOptions(150, 16_000_000, 1024), layout);
```

`layout` is an explicitly configured caller-owned, thread-safe provider, such as the optional
ONNX adapter. The service performs no model download, implicit discovery, OCR or lifecycle
transfer. Textless pages retain images/no-OCR diagnostics without calling this provider.
Native-only and provider-only factories and defaults are unchanged.

Every raw provider candidate is validated before overlap selection. Any positive interior
intersection with an accepted native table rejects the entire provider table; exact edge contact
is allowed. Rejected overlap never removes unclaimed native text. Provider errors, invalid topology
and resource limits abort extraction even when native tables are available. This is conservative
geometry precedence, not confidence calibration or a guarantee the preferred native result is
semantically better. Model candidates are not split, shifted, repaired or chosen using text.

Accepted native anchors plus **all raw provider anchors**, including overlapping candidates that
will be discarded, must fit `maxTableAnchors`. Thus a larger explicit budget may be needed than
for provider-only extraction. Native overflow fails before rendering/provider invocation. Native
recognition, full provider validation, overlap selection and glyph assignment share one page work
counter. Individual table slot/character limits, rendering/source/output/cancellation limits and
managed provider deadlines retain their contracts. There is no new document-wide deadline or
peak-memory guarantee.

Closed-rule, double-border, horizontal-rule and partial-rule native policies support this exact
glyph staging. `RULED_AND_ALIGNED_TEXT` and `RULED_AND_MULTILINE_ALIGNED_TEXT` are run-based and
are rejected at construction by this factory; their earlier native factories remain available.
Quarter-turn page/baseline transforms, crop offsets and UserUnit use the existing displayed frame.
Arbitrary native angles remain unsupported by this model composition. Accepted native continuation
signatures are preserved; model tables do not invent source-rule continuation evidence.

Native tables retain their method/cell geometry, while admitted provider tables retain
`provider_layout_native_glyphs_v1`. Both record
`pdf_table_selection_policy=native_first_disjoint_v1`. Optional native cells remain source-rule
or explicitly inferred geometry; model cells remain provider rectangles. No physical-span oracle
or calibrated probability is added.

The [fixed paired evidence](../benchmarks/review/pdf-native-first/README.md) preserves financial
440/440, procurement152/154 and statistics339/350 saved CSV positions, recent59/59 issuer row
content and scientific43/43 exact span/text anchors under one uniform configuration. Those are
separate reference types on development sources. Underlying model row errors, broader recent
sources, original scans, degraded inputs and held-out acceptance remain open.

## Provider-declared header-row evidence

A provider table has optional bounded top-header evidence through
`PdfTableLayoutProvider.Table.headerRows()`. Use the six-argument constructor to supply it;
the original five-argument constructor remains and defaults to0. A declaration must be between0
and the atomic row count. Zero/absent means unknown or not declared, not proof of no header.
It is a contiguous prefix of provider rows, independent of text contents or displayed page turns.

Native-text provider layout, native-first disjoint model tables, word OCR and cell OCR retain
positive declarations in element metadata:

```java
int headerRows = ((Number) tableElement.metadata()
        .getOrDefault("pdf_table_header_rows", 0)).intValue();
```

`pdf_table_header_policy` identifies `provider_declared_prefix_v1`. Positive declarations with
no anchor crossing into body rows also populate format-neutral `table_leading_header_rows`, allowing the shared structural chunker to
repeat the declared prefix when `repeatTableHeaders` is enabled and the header fits the budget.
No source cell/row is merged and source HTML remains unchanged. Carried headers in later
chunks use the existing chunker HTML representation. Whole-source PDF header counts/policy
are omitted from fragments, with originals retained when `includeOriginalElements` is enabled;
fragment header metadata describes the actual fragment. Text-only fallback carries no header
claim. Zero/unknown declarations and disabled repetition never infer repeated headers.
An anchor may span across the header
boundary; the source declaration remains, but its chunk repetition hint is omitted to avoid
duplicating body text. The declaration does not split it or certify a whole-anchor role. Models/caller providers
can be wrong, so the count is neither a calibrated confidence nor independently verified semantics.
Native rule tables do not invent provider header metadata.

The optional ONNX provider retains class3 column-header evidence when an object covers at least
half of an aligned row's vertical extent. It stops at the first unqualified row starting at row0;
it never fills missing leading rows or skips a gap to promote later headers. Existing membership,
score and topology policies remain. Alignment comparisons consume the existing shared work/deadline
budget. Detector crop translation preserves the logical prefix without shifting row indices.

This is deliberately narrower than Microsoft's header alignment, which can extend a lower detected
header upward. There is no complete postprocessor parity claim. Header-role evidence supplies context
for later token-assisted refinement; the rejected gap-only repair remains disabled and the known
education row mismatch remains. See [controls and compatibility](../benchmarks/review/pdf-provider-prefix-continuation/README.md).

The public record now has six components: source record patterns need the additional component,
and reflection/serialization/equality/hash/string users must account for the new shape. Previously
compiled construction through the five-argument descriptor and its accessors remains supported.
Available starting with published beta.6; beta.5 remains unchanged.

## Wrapped ISO dates in native provider cells

Native provider table extraction can explicitly join a complete ISO date split after
hyphens across two or three native text lines:

```java
var nativeLayout = new PdfNativeLayoutOptions(150, 16_000_000, 1024)
        .withCellTextJoin(PdfNativeLayoutOptions.CellTextJoin.WRAPPED_ISO_DATE);
var partitioner = PdfPartitioner.createWithNativeTablesFirst(
        pdfOptions, nativeLayout, layoutProvider);
```

`SPACE_SEPARATED` remains the default. The opt-in policy requires the entire cell to
form a strict valid `yyyy-MM-dd` date, with every nonfinal line ending in an existing
ASCII hyphen. It permits only ASCII digits/hyphens, two or three upright native runs,
left edges within two displayed user-space units, and distinct baselines more than
two units apart. It preserves invalid calendar dates, breaks inside digit groups,
extra text, same-baseline fragments and rotated runs. For example, native lines
`2025-01-` and `02` become `2025-01-02`. It does not join arbitrary hyphenated words
or infer that separate logical cells belong together. This is an explicit narrow
interpretation of text already owned by one provider cell, not semantic certainty.

Only provider-owned native cells are affected, including model cells admitted by
native-first composition. Accepted native heuristic tables, ordinary text and OCR
retain their existing policies. Source cell/parent geometry, spans, source claims,
rendering and provider budgets are unchanged. The historical unmodified cell text
must fit `maxCellCharacters` before joining; shortening cannot evade that limit.
Changed tables record `pdf_table_cell_text_policy=wrapped_iso_date_v1` and
`pdf_table_joined_iso_dates`. The count describes the whole source table; chunk
fragments omit it, while original-element provenance preserves it. Changed table
text/HTML and their derived element IDs may differ. Unchanged tables add no fields.

The fixed 2025 publisher-JATS scientific table improves116/136 to131/136 exact
logical span/text anchors, with nine exact headers and unchanged16x9 geometry.
Both runtime replays retain the original reference and all misses; publisher
logical spans are not automatically physical PDF border annotations. All20 other
fixed selected captures remain unchanged, including the two failing synthetic
wrapped-header controls. See the [date policy evidence](../benchmarks/review/pdf-native-iso-dates/README.md).
This is a scoped native text improvement, not a general multiline-header repair,
OCR improvement or overall PDF completion claim.

Compatibility: `PdfNativeLayoutOptions` gains a fourth record component. The
original three-argument constructor/accessors remain, with the historical default;
a formerly compiled consumer is verified on Java21/25. Source record patterns
must include the new component. Reflection, record serialization and consumers of
equality/hash/string shape must account for it. No package or Java baseline changes.

## Explicit source-tagged tables

`PdfPartitioner.createWithTaggedTables(options)` first reads supported PDF structure-tree
`Table`/`TR`/`TH`/`TD` roles and direct row/column spans. Original page-stream marked-content
identifiers supply exact retained native glyphs. Accepted tables preserve source header
markup and spans; unsupported candidates fall back whole to the selected native detector.
This factory needs neither a layout model nor an OCR engine. Existing factories are unchanged.

```java
var pdf = PdfPartitioner.createWithTaggedTables(
        PdfOptions.defaults().withTableCellCoordinates(true),
        PdfNativeLayoutOptions.CellTextJoin.WRAPPED_ISO_DATE);
```

For caller-owned model fallback, use
`PdfPartitioner.createWithTaggedTablesFirst(options, nativeLayoutOptions, layoutProvider)`.
The order is source tags, glyph-owning native tables, then disjoint provider tables.
The provider still runs and all its candidates remain subject to validation and combined
anchor/work limits, including candidates discarded for overlap. Run-based aligned-text
native modes are unsupported in this composition. The provider's lifecycle remains the
application's responsibility.

This narrow contract requires one original page, explicit complete nonoverlapping slots,
resolvable page MCIDs and uniform native direction within each cell. Role aliases and
current-revision direct attributes are supported. Class-dependent spans, nested tables,
cross-page ownership, ambiguous/missing identifiers and form-scoped content fall back whole.
Link annotations do not supply text; no annotation action or external destination is followed.
Structure-element ActualText is not used as a substitute for native content. Content-stream
ActualText follows the existing pinned PDFBox extraction policy, including soft-hyphen removal
and replacement of subsequent native positions by empty text. Tag ownership validates the
exact expected substitution before accepting retained positions; later ambiguous diacritic
mutations still reject the whole table. This adds no alternate-text repair to ordinary extraction.

Optional `pdf_table_cells` geometry is labelled `tagged_text_bounds`; these are native text
envelopes, not physical borders. Empty source cells require independent nonempty single-row
and single-column support throughout the grid. Their midpoint geometry is labelled
`tagged_inferred_cell_bounds`; unclaimed text inside an inferred empty slot rejects the whole
candidate. No cell ownership is assigned by those inferred rectangles. Whole-table
`pdf_table_tagged_empty_cells` counts are removed from chunk fragments and retained in
original-element provenance. No empty-row/column or arbitrary rotated-empty-cell inference.

For source-authored logical grids with wholly blank rows or columns, an additional explicit
policy can retain the grid without locating those empty slots:

```java
PdfPartitioner partitioner = PdfPartitioner.createWithTaggedTables(PdfOptions.defaults())
        .withTaggedTableGeometry(PdfTaggedTableGeometry.LOGICAL_TEXT_ENVELOPE);
```

This requires `includeTableCellCoordinates` to be false; incompatible configuration fails
before source access. Output retains authored rows, columns, spans and empty HTML cells,
and uses only the enclosing nonempty native text bounds for the parent table. It publishes
no cell rectangles and identifies the policy as `source_logical_text_envelope_v1` in
`pdf_table_geometry_policy`. Every nonblank glyph center inside that envelope must already
belong to the source table; unclaimed ink rejects the whole candidate. Entirely blank tables,
non-upright displayed text and ambiguous/missing source ownership retain native fallback.
All original structure, glyph, table and work budgets remain applicable. This does not infer
physical borders, blank-cell positions or semantic table accuracy. Existing factories retain
`SUPPORTED_CELL_AXES`; password and source-paragraph copies preserve the selected policy.

Accepted tables carry `pdf_table_method=tagged_structure_native_glyphs_v1` and
`pdf_table_tagged_scope=single_page_mcid_v1`. Positive source header prefixes carry
`pdf_table_header_policy=source_structure_prefix_v1`. Source tags can be incomplete or wrong;
recognizing their logical grid does not establish reliable glyph ownership or extraction
accuracy. See [development evidence](../benchmarks/review/pdf-tagged-tables/README.md).

The [resolved-text development replay](../benchmarks/review/pdf-tagged-resolved-text/README.md)
records the distinction between structure-element and content-stream ActualText. Source
replacement strings can be wrong; this policy preserves existing decoded output rather than
certifying the publisher's alternate text or inventing character-level replacement geometry.

## Explicit segmented horizontal rules with body row spans

Use `PdfOptions.TableDetection.RULED_AND_SEGMENTED_ROWS` to retain closed-grid and
partial-rule detection, then try a separate native strategy for segmented horizontal
rules. No OCR or model is needed for accepted native tables. Native-first and
source-tagged/native-first factories also support this explicit policy.

```java
var options = PdfOptions.defaults().withTableCellCoordinates(true)
        .withTableDetection(PdfOptions.TableDetection.RULED_AND_SEGMENTED_ROWS);
var pdf = PdfPartitioner.create(options);
```

Matching top and bottom source paint must identify two through32 abutting column
intervals, each at least12 unscaled source units wide. Every atomic row boundary
must paint the first column; gaps are4 through100 units. At least one interior
full-width divider and one partial divider are required. Missing separators in
other columns define inferred rectangular row spans. Horizontal column merges are
unsupported. Every inferred anchor needs contained nonblank native text, including
multiline text; no empty-anchor guessing or semantic header declaration is made.

The whole maximal component is rejected for incompatible/overlapping paint,
crossing/angled/rotated text, raster or interior vertical barriers, source bounds
outside the page, unsupported empty anchors, an immediately following unruled
first-column continuation or a preceding complete text row. Close tables/captions
can therefore retain ordinary fallback. Rejected glyphs remain unclaimed. Separate
components with overlapping envelopes both fall back before claims are committed.

Output uses `pdf_table_method=segmented_rule_text_heuristic`, evidence policy
`segmented_horizontal_row_spans_v1` and optional `inferred_cell_bounds` geometry.
Painted horizontal ordinates/endpoints support these inferred cells; no vertical
borders or semantic merges are asserted. `header_rows=0` means no header evidence
was inferred. Existing table, glyph, source, output, interruption and shared2M page
work limits remain. Native-first raw-provider validation/anchor limits still apply.

The [paired development evidence](../benchmarks/review/pdf-segmented-row-spans/README.md)
recovers20/20 publisher logical span/text anchors in one eLife table, versus12/20
with model geometry. All22 other selected regions remain unchanged on bothJDKs.
This is a source-supported heuristic for a narrow pattern, not general table fidelity.

## Source-declared discretionary word wraps

Use `PdfNativeLayoutOptions.CellTextJoin.SOURCE_SOFT_HYPHENS_AND_WRAPPED_ISO_DATE` with
`createWithTaggedTables` or `createWithTaggedTablesFirst` to join native word wraps whose
source structure explicitly labels the physical hyphen as a soft hyphen. This opt-in policy
also retains wrapped ISO-date handling; provider-only cells receive date joining alone.

```java
var pdf = PdfPartitioner.createWithTaggedTables(
        PdfOptions.defaults().withTableCellCoordinates(true),
        PdfNativeLayoutOptions.CellTextJoin.SOURCE_SOFT_HYPHENS_AND_WRAPPED_ISO_DATE);
```

A hint must be a structure-element `ActualText` string of exactly U+00AD owning exactly
one original page MCID and one retained `-` glyph. Any competing ancestor or descendant
`ActualText`, multiple references or multiple glyphs disqualifies it. The glyph must uniquely
end a native run, with letters on both sides of a nearby separate-line break in upright text.
Other hard hyphens, arbitrary alternate strings, same-line dashes and ambiguous breaks stay
unchanged. This uses original source semantics, without a dictionary or publisher reference.

Original cell character limits apply before shortening, all glyphs retain their original
ownership, and table geometry is unchanged. Changed tables carry
`pdf_table_cell_text_policy=source_soft_hyphens_and_wrapped_iso_date_v1` and
`pdf_table_joined_soft_hyphens`; whole-table counts are removed from chunk fragments while
original-element provenance retains them. Existing policies and default factories are unchanged.

Optional Tesseract callers can select explicit engine binarization through
`TesseractOcr.create(options, TesseractThresholding.SAUVOLA)` or the word-capable overload.
This preserves original raster coordinates and existing process/resource limits; no PDF
factory automatically changes preprocessing. See [OCR configuration](ocr-tesseract.md#explicit-internal-binarization).

Explicit source-DPI factories support known rendered raster resolution without resampling.
The [fixed scan replay](../benchmarks/review/pdf-scan-source-resolution/README.md) preserves
exact source/render RGB identity and independent pixel-coordinate scoring. Mixed recognition
outcomes retain existing defaults and leave historical/degraded acceptance open.
## Explicit source-tagged paragraphs

Development addition after the beta.6 candidate: use
`options.withPageBoundary(PdfTaggedParagraphOptions.PageBoundary.PAGE_FRAGMENTS)` to retain
supported portions of source paragraphs that span consecutive original pages. The default
`SINGLE_PAGE` policy keeps its original whole-paragraph fallback behavior.
Each fragment retains original page coordinates and `pdf_source_paragraph_ordinal`, plus
one-based `pdf_source_paragraph_fragment` and `pdf_source_paragraph_fragments`. The latter
counts source plans, including fragments later rejected by native ownership/geometry checks;
it is not a promise that all fragments were accepted. Rejected fragments retain ordinary text.
Word-wrap joining never crosses pages. Page order remains geometric, so these links do not
remove running headers, sidebars or repair general logical article order. Original character
limits apply independently to each page fragment, and paragraph-count bounds apply per page.
Backward, repeated or skipped page transitions remain unsupported. The options record gains
a fourth component; its original three-argument constructor remains with `SINGLE_PAGE`.
Record patterns, reflection, serialization and equality/hash/string shape consumers need review.

Configure a native partitioner with `withTaggedParagraphs(PdfTaggedParagraphOptions.defaults())`
to group supported source paragraph tags. To additionally join source-declared discretionary
word wraps, select `PdfTaggedParagraphOptions.WordWrap.SOURCE_SOFT_HYPHENS` through
`withWordWrap`. Physical hyphens remain unchanged by default.

The additional opt-in `WordWrap.SOURCE_LINE_WRAPS` includes those source-declared soft
hyphens and omits generated spacing after a physical ASCII hyphen at a validated native
line boundary. The physical hyphen stays in the text. This is a compound-word interpretation,
not proof of the author's intended word: enable it only when appropriate for the document.
Explicit source spaces, ambiguous glyph endpoints, numeric/punctuation boundaries and large
baseline gaps retain their original spacing. It never joins across paragraphs or page fragments.
Original character budgets apply before shortening. Existing policies keep their behavior.
Exhaustive `WordWrap` switches must handle the additional enum value.

This policy records `pdf_source_word_wrap=source_line_wraps_v1`,
`pdf_source_joined_soft_hyphens` and `pdf_source_retained_hyphen_wraps` on grouped paragraphs.
The separate paragraph assembler accepts this policy but retains its normal single-space
join between pages; it does not infer a physical-hyphen continuation across pages.

Grouping requires complete, unique, single-page native glyph ownership and one upright
column. Source-tagged tables take precedence. Unsupported paragraphs fall back to ordinary
geometric extraction; paragraph tags do not establish general logical page order. OCR,
provider layout and run-based aligned-table policies cannot be combined with this option.
Original character limits apply before joining. See the
[measurement and limitations](../benchmarks/review/pdf-tagged-paragraphs/README.md).

### Explicit shaded native table rows (unreleased)

For financial-style tables with alternating pale row backgrounds and subtotal rules, select:

```java
var options = PdfOptions.defaults()
        .withTableDetection(PdfOptions.TableDetection.RULED_AND_SHADED_ROWS)
        .withTableCellCoordinates(true);
var partitioner = PdfPartitioner.create(options);
```

Closed ruled grids keep first ownership. The additional native-only strategy requires at least
four matching full-width row-paint envelopes and three independently spaced matching numeric
subtotal-rule groups. Same-color per-cell rectangles may form one row envelope across small
horizontal gaps; contained overpaint creates no extra row. Label-column-only paint may delimit
section rows. Source paint is never promoted into closed-grid borders. Header spans, multiline
labels and blank section cells are inferred from original native positions.

The strategy supports displayed upright text. Competing column patterns, overlapping table
candidates, crossing visible glyphs, raster/vertical-rule barriers and nonnumeric body fields
retain ordinary fallback. Boundary-crossing blank padding remains unowned; it cannot move cell
edges or manufacture header spans. Numeric text is preserved, including currency and accounting
parentheses; this policy does not repair spaced digits, recognize mathematical semantics or
guarantee rendered visibility/clipping. Pale single-rectangle paint must be nearly opaque with
normal blending and no soft mask. Curved, compound, white/dark and filled-and-stroked backgrounds
do not supply row evidence. At most4,096 eligible rectangles are retained per page, with the
shared page work/cancellation counter and original table/cell bounds still enforced.

Tables record `pdf_table_method=shaded_row_text_heuristic` and evidence policy
`painted_row_bands_numeric_rules_v1`. Optional cells use `inferred_cell_bounds`; the metadata
does not claim observed borders or semantic merges. Defaults retain their earlier behavior.
Exhaustive `TableDetection` switches must handle the additional enum value. The
[fixed three-statement experiment](../benchmarks/review/pdf-unseen-business-apple-2026/README.md)
records both successes and the remaining balance-sheet failure.

### Source-tagged non-text references (unreleased)

Explicit source-tagged-table extraction can retain page-stream MCID references outside
TR/TD/TH cell ownership when each is uniquely declared and contains zero retained glyphs.
This supports non-text source content alongside authored rows. References must belong to
the table's single original page, remain disjoint from cell references and have no alternate
text, form/object stream or nested MCR children. Missing/redeclared identifiers and even
whitespace glyph content reject the complete candidate. Auxiliary references are capped
at4,096 per table and use existing source work/cancellation bounds.

These references claim no glyphs and supply no cell geometry. Source roles, spans, native
characters, images and original cell coordinates remain. Accepted tables record
`pdf_table_tagged_nontext_references` only when positive. Empty-cell geometry still needs
independent support; accepting non-text references does not waive that guard. See the
[fixed-source experiment](../benchmarks/review/pdf-tagged-nontext-content/README.md).

### Shaded cell-paint boundaries (unreleased)

For two independently painted header levels, a top line already crossing the inferred
boundary can be completed only if every first-level source span supports one consistent
baseline. The extension is at most half the level pitch, capped at twelve points. A
missing span, crossing glyph, competing baseline or unrelated visible ink in the new strip
retains the earlier boundary. Wholly separate headings above the table are not pulled in.
This source-supported correction changes neither generic padding nor source characters.

Shaded-row paint eligibility retains bounded individual cells (minimum width48 points,
height3–60 points) before joining matching source fills. The resulting row envelope must
be at least four times as wide as tall. Applying that row check after coalescing preserves
tall numeric-cell backgrounds on wrapped rows; an isolated block remains insufficient.
Existing source path/color/opacity/blending checks, the4,096-rectangle cap and numeric-rule
support requirements remain. This correctness fix applies to each explicit shaded policy;
it does not add a default strategy or infer closed borders.

### Explicit overlaid digit padding (unreleased)

Select `PdfOptions.TableDetection.RULED_AND_SHADED_ROWS_COMPACT_PADDING` through
`PdfOptions.withTableDetection(...)` when a native shaded table contains shifted space
glyphs over adjacent digit advances. This separate policy applies only to numeric body
cells; the earlier shaded policy continues to preserve those spaces.

One ASCII space must overlap two uniquely adjacent upright ASCII digits. Their baselines
and heights must agree within half a point, their advance boxes must overlap by more than
0.01 point and at most a quarter of the smaller width, and the space must lie within their
horizontal union with a vertical shift greater than one and at most two points. Duplicate
endpoints, competing pairs and repeated padding are rejected. Ordinary spaces, NBSP,
actual digit gaps, headers and labels are preserved. This geometric interpretation does
not infer digits or repair arbitrary numbers.

Original cell character limits are checked before projection. Source glyph membership
and coordinates remain intact. Tables use evidence policy
`painted_row_bands_compact_padding_v1`; `overlaid_digit_padding_glyphs` records omissions
when positive. Work and cancellation limits still apply. Exhaustive table-policy switches
must handle the additional enum value. See the
[development comparison](../benchmarks/review/pdf-overlaid-digit-padding/README.md).

### Complete small script runs in native tables (unreleased)

`PdfOptions.TableDetection.RULED_AND_SHADED_ROWS_INLINE_SCRIPTS` retains the compact
padding option and groups complete short raised/lowered runs in body cells. This avoids
attaching only part of a small marker such as `(1)`. Headers retain earlier grouping.

Runs have at most eight adjacent upright nonblank glyphs, with baseline/height differences
within half a point and height at most three quarters of the median positive nonblank cell
height. A nearby body position must have median body height, a vertical shift greater than
two points and at most eighty percent of body height, and a bounded horizontal gap. Duplicate
or overlapping positions, crossed body ink and competing baselines reject the complete run.
Every original character and coordinate remains intact; only comparison baselines change.
Original pregrouping cell character limits, page work and cancellation bounds still apply.

Evidence policy is `painted_row_bands_inline_script_runs_v1`. Defaults and earlier table
policies retain their original behavior; exhaustive switches need the new enum value.
This interpretation does not classify footnotes, reconstruct math or establish semantic
reading order. See the [fixed development experiment](../benchmarks/review/pdf-small-script-runs/README.md).

### Explicit source-owned inline scripts (unreleased)

Small raised or lowered glyphs can otherwise become independent geometric lines, causing a
complete tagged paragraph to fall back. Enable the independent interpretation explicitly:

```java
var policy = PdfTaggedParagraphOptions.defaults()
        .withInlineScripts(PdfTaggedParagraphOptions.InlineScripts.NEARBY_SMALL_GLYPHS)
        .withWordWrap(PdfTaggedParagraphOptions.WordWrap.SOURCE_LINE_WRAPS)
        .withPageBoundary(PdfTaggedParagraphOptions.PageBoundary.PAGE_FRAGMENTS);
var partitioner = PdfPartitioner.create(PdfOptions.defaults()).withTaggedParagraphs(policy);
```

The median native glyph height identifies smaller candidates. A candidate must be horizontally
adjacent to body text, have a bounded vertical shift and fit one body baseline; competing
baselines retain historical grouping. All glyph identities, characters and original coordinates
remain intact. This does not interpret mathematical semantics, synthesize script markup or
reorder untagged text. Complete source ownership, single-column validation, original line-joined
character bounds and shared page work/cancellation limits still apply.

Accepted paragraphs record `pdf_source_inline_scripts=nearby_small_glyphs_v1` and
`pdf_source_inline_script_glyphs`. Fragment assembly requires the same policy throughout;
original per-fragment counts and coordinates remain in part provenance. The options record now
has five components. Original three/four-argument constructor descriptors remain, both using
`InlineScripts.PRESERVE`; record patterns, reflection and serialized/equality/string shapes
need review. See the [uniform original-source experiment](../benchmarks/review/pdf-inline-scripts/README.md).

### Explicit native inline-script runs (unreleased)

Untagged native text groups glyphs into lines by baseline, so a raised affiliation or citation
marker more than two units off its body baseline becomes its own line, usually placed before the
names it belongs to. Enable the existing whole-run cell rule for ordinary native text explicitly:

```java
var options = PdfOptions.defaults()
        .withTextLayout(PdfOptions.TextLayout.COLUMN_PARAGRAPHS)
        .withInlineScripts(PdfOptions.InlineScripts.NEARBY_SMALL_GLYPHS);
var partitioner = PdfPartitioner.create(options);
```

A run of at most eight nonblank upright glyphs, each at most three quarters of the page's median
ordinary-text height and joined by small advances, joins the one body line that supports the whole
run. This is the rule of `PdfNativeLayoutOptions.CellTextJoin.INLINE_SCRIPT_RUNS`. Competing
baselines, overlapping positions, longer runs and distant small text keep historical grouping.
Source characters and glyph bounds are retained. Regrouped elements enclose their member glyphs,
so their bounds may change; no script markup is generated. The policy applies after native table staging, so native,
tagged and provider table cells keep their own policies. Pages whose upright frame is displayed
rotated are unchanged. Each run is compared only with body glyphs in its own baseline band, in
original order, which equals the whole-input rule. Work shares the native page counter and its
`TABLE_LIMIT` failure. Elements record `pdf_native_inline_script_policy=nearby_small_glyph_runs_v1`.
`PRESERVE` remains the default; the options record gains an `inlineScripts` component and every
earlier constructor is kept.

Against pinned Unstructured 0.27.28 `fast` page-1 elements, compared in upstream's whitespace
presentation, the policy locates one more upstream element on each of the three development
papers and on a fourth original, with no added reversed pairs. Strict complete-body paragraph
presence rises from 58, 58 and 59/143 to 60, 60 and 61/143 for the three native layouts. The
first, strict-text page-1 prediction failed because the source keeps narrow no-break spaces that
upstream presents as ASCII spaces; see the
[Unstructured alignment evidence](../benchmarks/review/pdf-unstructured-alignment/README.md).

### Explicit side-stream blocks (unreleased)

On article first pages, a metadata sidebar set in smaller type often sits beside the abstract
on a different baseline grid. The fixed-gutter column detector needs paired baselines, so it
rejects the page and the sidebar lines alternate with abstract lines. Select explicit blocks:

```java
var options = PdfOptions.defaults()
        .withTextLayout(PdfOptions.TextLayout.COLUMN_PARAGRAPHS)
        .withSideStreams(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS);
```

On pages without an accepted gutter, ordinary upright lines are clustered by left edge (4-unit
tolerance) and the largest cluster is the main stream. Another cluster's lines lying wholly at
least 4 units outside the main stream's horizontal extent form a side stream when there are at
least three, their median height is below 0.9 of the main stream's, and their vertical extent
spans at least three main-stream lines. A cluster is rejected as row-bound when at least half
of those lines share a baseline (within 2 units) with a main-stream line, as statement labels
share baselines with their values; a prose sidebar runs on its own baseline grid. Side lines
are grouped with the existing paragraph rule among themselves only, never across an intervening
image, table or text item that horizontally overlaps them, and each block is emitted at its
first line. A side line still ends
any adjacent main-stream group, so main-stream grouping and order are unchanged. Blocks record
`pdf_reading_order=side_stream_blocks_v1`. Characters and glyph bounds are kept; enclosing
element bounds change where side lines join. `LINES` layouts, accepted column bands, tables,
images and rotated text are unaffected. Work shares the layout page budget and its
`LAYOUT_LIMIT` failure. `PRESERVE` is the default.

The rule is geometric, not semantic. Against pinned Unstructured 0.27.28 page-1 order, the
policy fixes every reversed pair on the frozen target sidebar block and adds no reversed or
unmatched pair on four papers. Strict paragraph totals are unchanged. Across 13 additional local
control PDFs, including statements and scientific articles, measured row association equals the default. Value-less
section headings at their own indent can still carry the side-stream tag as single lines,
without any change of text, order or membership. See the
[side-stream evidence](../benchmarks/review/pdf-sidebar-blocks/README.md) and its
[review follow-up](../benchmarks/review/pdf-sidebar-blocks/followup/README.md).

#### Same-row sidebar ties (unreleased)

A side block is emitted at its first line, so when that line starts a fraction of a unit below
the top of a main-stream line on the same visual row, the main line comes first. Select the
whole-element tie rule explicitly:

```java
var options = PdfOptions.defaults()
        .withTextLayout(PdfOptions.TextLayout.COLUMN_PARAGRAPHS)
        .withSideStreams(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_TIES);
```

The policy first produces exactly the `SMALLER_TYPE_BLOCKS` elements. In column-paragraph
layouts (including oriented column paragraphs), one in-order pass then
moves a side-stream element back one position, past its immediate predecessor, only when the
predecessor:

- is ordinary upright native text (not a side element, source paragraph, rotated text, table or
  image);
- holds one original line (`pdf_line_count` absent or 1), so a joined paragraph never moves even
  when its last line shares the row;
- lies wholly to the right of the side element (side right ≤ predecessor left);
- contains the side element's top in its vertical extent (predecessor top ≤ side top <
  predecessor bottom).

Each side element moves at most once. Elements are moved intact: text, bounds, metadata,
membership and the relative order of all other elements are unchanged, and nothing is split,
joined, dropped or added. Accepted column bands and `LINES` layouts are unaffected, and the pass
shares the layout page budget, cancellation and `LAYOUT_LIMIT` failure.

On the four development papers, against pinned Unstructured 0.27.28 page-1 order with both
column layouts and both inline-script choices, reversed pairs go from 3 to 1 on next, 19 to 17
on holdout (20 to 18 with inline scripts), and stay at 6 on recent and 43 on eLife, with no
correct pair lost and no pair newly unmatched. Recent has one move with no measured benefit.
The remaining next reversal is the earlier frozen row-tie target, which needs a paragraph split
and is out of scope. Strict paragraph results equal `SMALLER_TYPE_BLOCKS`. Across 17 pinned
control PDFs (181 pages), the column layouts move elements only on page 1 of recent, next and
holdout, with every row-association pair unchanged. Plain `PARAGRAPHS` uses the existing
`SMALLER_TYPE_BLOCKS` placement without the extra tie pass; review found that applying the pass
there separated a row pair, so the pass is scoped to column-paragraph layouts. The rule came
from post-hoc development evidence and is not held-out accuracy. See the
[row-tie policy evidence](../benchmarks/review/pdf-sidebar-row-tie-policy/README.md).

#### Same-row sidebar paragraph fragments (unreleased)

Whole-element ordering cannot place a sidebar between two lines of one paragraph. When a side
block's first line starts on the row of the last line of a multi-line main paragraph, the explicit
fragment policy represents that order with two marked fragments:

```java
var options = PdfOptions.defaults()
        .withTextLayout(PdfOptions.TextLayout.COLUMN_PARAGRAPHS)
        .withSideStreams(PdfOptions.SideStreams.SMALLER_TYPE_BLOCKS_ROW_FRAGMENTS);
```

Output equals `SMALLER_TYPE_BLOCKS_ROW_TIES`, except for that case. There the paragraph becomes
two elements, its earlier lines and its last line, with the side block between them. The tie
uses the row-tie geometry (that line's top ≤ side top < its bottom; side wholly to its left).
A split happens only when the first fragment's text, one space and the last line's text equal
the unsplit paragraph exactly, so a retained hyphen wrap is never split.

Both fragments carry:

- `pdf_reading_order_fragment_policy=row_tie_fragments_v1`;
- `pdf_paragraph_fragment_group`, a page-local ordinal;
- `pdf_paragraph_fragment_index`, 1 or 2;
- `pdf_paragraph_fragment_count`, 2.

To restore the row-tie paragraph, join each group's fragments with one space and take the union
of their bounds. Consumers that need whole paragraphs must rejoin or choose another policy. The
policy applies to column-paragraph layouts only; plain `PARAGRAPHS` keeps `SMALLER_TYPE_BLOCKS`
placement.

**Measured results:**

- **Line order:** against pinned Unstructured 0.27.28 page-1 order on the four development papers,
  every column profile goes from 6 to 0 reversed pairs on recent and from 1 to 0 on next.
  Holdout (17/18) and eLife (43) are unchanged, with no correct pair lost or newly unmatched.
- **Reconstruction:** across 17 pinned control PDFs, every layout and both inline-script choices
  (204 source profiles), rejoined output equals the row-tie output exactly. One fragment group
  occurs per column profile, on page 1 of recent and next only.
- **Strict paragraphs:** results, measured without rejoining, are unchanged at 58/59/60/61 of 143.
- **Row association:** without rejoining, one next row pair goes from separated to kept, and
  nothing regresses.
- **Existing policies:** default, `SMALLER_TYPE_BLOCKS` and row-tie outputs are byte-identical to
  before (612 hashes).

These are post-hoc development results, not held-out accuracy. See the
[parked-items evidence](../benchmarks/review/pdf-parked-items-2026-10-22/README.md).

### Explicit logical paragraph assembly (unreleased)

To make complete linked page fragments contiguous, apply the separate `PdfParagraphAssembler`
interface to the extracted element list:

```java
var policy = PdfTaggedParagraphOptions.defaults()
        .withWordWrap(PdfTaggedParagraphOptions.WordWrap.SOURCE_SOFT_HYPHENS)
        .withPageBoundary(PdfTaggedParagraphOptions.PageBoundary.PAGE_FRAGMENTS);
var partitioner = PdfPartitioner.create(PdfOptions.defaults()).withTaggedParagraphs(policy);
var processor = DocWeave.create(PartitionerRegistry.of(java.util.List.of(partitioner)));
var extracted = processor.partition(java.nio.file.Path.of("article.pdf"));
var paragraphs = PdfParagraphAssembler.create().assemble(extracted.elements());
```

Each multi-page fragment carries `pdf_source_paragraph_id`, derived from the original PDF
bytes' SHA256 and source paragraph ordinal. Different document bytes remain separate even
when filenames match. This is a content identity, not proof of trustworthy source tagging.
Only complete fragment sets in original consecutive-page order with compatible text policies
are joined. Incomplete, conflicting or malformed sets pass through unchanged.

Assembly places one paragraph at its first fragment and retains every intervening element in
relative order. It inserts one ASCII space without trimming, normalizing or removing hyphens.
It does not change the partitioner's page-level output or establish general article order.
The aggregate has `page_numbers` and `pdf_source_paragraph_parts`, preserving original element
IDs, complete metadata and half-open code-point offsets for each part. It has no fabricated
single-page number or bounding box; the original frames remain in each part's metadata.

`PdfParagraphAssemblyOptions` bounds input elements, actual fragments per source identity and
assembled UTF16 units per paragraph. All input text plus inserted spaces additionally has a
fixed16-million-unit ceiling. Budget breaches fail the whole call without partial results;
interruption preserves the caller's flag. The assembler is reusable and runs no I/O or providers.
See the [fixed scientific evaluation](../benchmarks/review/pdf-scientific-paragraph-assembly/README.md).


### Source table reference counts through chunking

`pdf_table_caption_references` and `pdf_table_tagged_nontext_references` describe
references attached to the complete original source table, not text included in a
fragment. Whole-table output retains both counts. Structured and text-only table
fragments omit them; encoded original-element provenance retains the original values
when enabled. Caption text remains separate native output and is never copied into
table fragments. This does not infer a caption-to-chunk association.

### Form-local native graphics paths

Normal, nested and transparency-group form streams receive independent unfinished
path storage. Returning from a form restores the caller path and pending clipping
instruction, including when child processing fails. Collected paint and page-wide
operator/image budgets remain shared. This prevents invisible child paint from
erasing caller table rules; it does not add transparency compositing or general
rendered-visibility inference. See the [owned render-equivalence evidence](../benchmarks/review/pdf-form-paths/README.md).

### Type3 glyph path isolation (unreleased)

Type3 character programs now receive an independent mutable drawing path, as form
XObjects already do. A glyph stroke cannot paint or discard an unfinished caller path.
The caller's path, pending clip and path-construction state are restored after normal
completion, cancellation or a glyph traversal failure. Accumulated image/rule evidence
and shared operator budgets retain their existing scope.

An owned regression previously observed two segments painted by a glyph stroke when
only its own segment should have painted. It now observes one glyph segment followed
by the preserved caller segment when the caller strokes. The existing failure control
also verifies preservation of a pending caller segment after cancellation and operator
exhaustion. This addresses native graphics traversal, not text rendering or complete
rendered-pixel fidelity. See the [evidence](../benchmarks/review/pdf-type3-path/README.md).

### Affine-transformed rectangle clips (unreleased)

A single finite `re` rectangle remains a bounded parallelogram under an affine source
transform. Native graphics traversal now retains that clip, including skewed rectangle
operators, under both winding rules. The existing conservative inherited-clip envelope
can reject wholly disjoint rules. It does not trim partially clipped rules or establish
visibility inside the enclosing rectangle. General skewed line-built paths, compound
paths and curves retain their previous unsupported behavior.

Owned tests pair invisible skew-clipped grids with raster-identical no-grid pages,
exercise stroke/fill/combined paint and all four rotations with a crop and UserUnit,
and verify later visible paint after the form scope restores. Existing visible skew,
mirror, scale and unsupported-path controls remain. Source-image occurrences and payloads
are not filtered or cropped by this change. See the
[clip evidence](../benchmarks/review/pdf-skewed-rectangle-clips/README.md).

### Packaged native resource observations

The [initial operational probe](../benchmarks/review/pdf-native-resources/README.md)
retains two complete-original default-native attempts under a fixed 256 MiB JVM heap cap.
The business release succeeds; the magazine rejects arbitrary-angle text under default
policy. The rejected run remains visible and does not count as full-document throughput.
Single-process timing, heap-pool peak sums and Linux high-water RSS are diagnostic
observations, not minimum memory requirements, concurrent capacity or total parser bounds.

The separate [oriented-profile observation](../benchmarks/review/pdf-native-oriented-resources/README.md)
confirms that the same complete magazine succeeds with the existing explicit `ORIENTED_LINES`
policy under the same heap cap, retaining 94 source images. The default rejection remains
in its original report; this confirms configuration rather than a new algorithm or text-quality score.

### Bounded line-built skewed clips (unreleased)

A single finite four-corner line path can now supply native rule clipping, including
skewed paths equivalent to affine-transformed rectangle operators. Explicit closure and
the clipping operator's implicit closure retain their source meaning; an explicit return
to the first point is accepted. The source winding rule is retained. Candidate storage
stays fixed at six points; curves, multiple subpaths and larger general polygons remain
outside this subset. Historical axis-aligned rectangles and known-empty areas remain supported.

This corrects false tables formed from a grid hidden by a line-built skewed clip.
Owned pairs compare equivalent rectangle-operator and line-built sources under both
winding rules, three paints and four page rotations with crop/UserUnit metadata. Subsequent
visible paint retains its table after leaving the clipped form. As before, only wholly
disjoint evidence is rejected using a conservative inherited-clip envelope; this is not
partial rule trimming, final compositing or a broad source-corpus accuracy claim.
See [the extraction regression](../benchmarks/review/pdf-line-skewed-clips/README.md).

### Source-boundary-aware partial-table headers (unreleased)

The explicit `RULED_AND_PARTIAL_TEXT` strategy separates header groups when a supported
column edge lies inside a source gap greater than twice the larger font-reported space
width (minimum one unit). This avoids generic 12-unit line grouping joining adjacent
column headings. Continuous text and ordinary word spaces retain spanning-header inference;
widely spaced titles remain potentially ambiguous. Other policies and defaults are unchanged.

The complete retained statistics original improves from 339/350 to 350/350 saved CSV
positions: headers 24/35 to 35/35, body 315/315 unchanged, same 50-by-7 bounds. This is
known-source development evidence, not independent span truth or general table accuracy.
All other 17 case/profile records in the three-document/six-policy regression remain equal.
See [header evidence](../benchmarks/review/pdf-partial-header-boundaries/README.md).

### Bounded two-rectangle compound clips (unreleased)

Native rule traversal now retains a path made from exactly two finite rectangle operators,
including their affine transforms, and applies its original nonzero/even-odd winding rule.
The clip takes effect after its path paints or ends, with existing graphics-state/form
restoration. A third subpath invalidates this bounded subset; larger compound paths and
curves remain unsupported. Filled-rule recognition still requires one eligible subpath.

Two identical rectangles cancel under even-odd clipping. Their hidden grid previously
produced a false Table; it now leaves the original ordinary labels unclaimed. Nonzero
winding retains the visible grid, and paint after leaving the form retains its table.
Owned cases cover both behaviors across four rotations and three paint modes, plus a
three-rectangle fallback control. Conservative inherited envelopes still do not trim
partial rules or determine visibility inside clip holes. Source-image payloads and final
compositing remain unchanged. See [compound-clip evidence](../benchmarks/review/pdf-two-rectangle-clips/README.md).

### Historical magnification pilot: mixed outcomes

A targeted [fixed 2x pilot](../benchmarks/review/pdf-historical-upscale-pilot/README.md)
uses the existing public magnification decorator on the complete 1835 scan with both
pinned models, source 300 DPI and recognition 600 DPI. Fraktur character edits improve
51 to 45, while German regresses 104 to 112 against the same 1,059-character ordered
reference. Both still miss one line. This does not recommend magnification generally or
change defaults. The result is parked with both outcomes preserved; no parameter sweep
or new runtime functionality follows.


### Explicit native leading numeric rows (unreleased)

`PdfNativeLayoutOptions.withLeadingRow(LeadingRow.SOURCE_NUMERIC_ROW)` can recover one
complete native numeric row immediately above a validated provider table. Existing
constructors and defaults retain `PROVIDER_ONLY`. The source-based inference requires
an upright common baseline, at least three unmerged first-row columns, no declared
header prefix, a label and numeric values fitting every column. Ambiguous/incomplete
rows and competing table ownership retain the provider table. The original anchors and
provider provenance remain; metadata identifies the added inferred row and bounds.
All existing budgets still apply, including added anchors, with full topology revalidation.
This adds a record component; reflective schemas should be reviewed on upgrade.

The complete retained NVIDIA original improves exact row occurrence presence from
35/110 to 36/110 under TATR and 66/110 to 67/110 under YOLOX. Both gains recover the
same missing page-7 numeric row. Strict ordinal equality remains 0/110; titles, section
boundaries and PDF/HTML presentation differences remain unresolved. This is a scoped
known-source improvement, not a percentage-complete claim or general table accuracy.
See [leading-row evidence and usage](../benchmarks/review/pdf-native-leading-row/README.md).


### Oversized split-source provenance (unreleased)

PDF tables and other extracted elements can carry large image/HTML payloads. Chunking a
singleton source over 1,000,000 combined raw text/HTML/image code points into multiple
fragments now omits repeated `orig_elements` with explicit reason/size metadata. Chunk
text, structural HTML and cell ownership are unchanged. Preserve the original partition
result when full reconstruction is needed. The retained owned PNG/table probe reduces
serialized table output from 6,334,867 to 6,019 bytes for the same six fragments; this is
an output-amplification safeguard, not an extraction accuracy or peak-memory claim.
See [scope, upgrade impact and measurements](../benchmarks/review/chunk-split-source-provenance/README.md).

The [scientific continuation attempt](../benchmarks/review/pdf-sidebar-continuation-attempt/README.md)
and [1778 sharpening pilot](../benchmarks/review/pdf-scan-sharpen-pilot/README.md) remain parked:
the former has no exact-paragraph gain; the latter has mixed model outcomes. Neither adds
a production policy or changes defaults.


### Explicit native leading section rows (unreleased)

`PdfNativeLayoutOptions.LeadingRow.SOURCE_NUMERIC_OR_SECTION_ROW` additionally permits
one colon-terminated section label immediately above a provider table. The other leading
cells must be empty. The first body row must contain a single label run and complete ASCII
numeric values on a common upright baseline, optionally preceded by one `$`, `£` or `€`.
Separated currency/digit runs on that same baseline are supported. Alignment compares
source origins including indentation-space glyphs on the corresponding baseline, within
two displayed units. Multiple body baselines, missing values, overlapping table ownership
and the existing numeric policy's unsupported topology retain the original table.

```java
final var layoutOptions = PdfNativeLayoutOptions.defaults()
        .withLeadingRow(PdfNativeLayoutOptions.LeadingRow.SOURCE_NUMERIC_OR_SECTION_ROW);
```

Pass these options to the existing native-layout factory with a caller-supplied provider.
`PROVIDER_ONLY` remains the default; `SOURCE_NUMERIC_ROW` retains its existing contract.
The new enum member requires exhaustive switches to be updated when recompiling. It adds
no record components or dependencies. Metadata identifies the combined inference policy as
`source_leading_numeric_or_section_row_v1`; original anchors and provider provenance stay
intact, and added anchors count against normal limits. An aligned colon heading above
numeric content can still be semantically ambiguous: this is explicit geometry-based
inference, not proof that the source author considered the text a table row.

The complete NVIDIA source/capture replay improves YOLOX exact complete-row presence
67 -> 68/110, recovering the page-6 section label. TATR remains 36/110 and strict ordinal
scores remain 0/110 under both profiles. A separate four-column owned PDF transfers the
behavior at a different rendering resolution; it is not a second independent publisher
benchmark. See [section-row evidence](../benchmarks/review/pdf-native-section-row/README.md).


### Explicit native trailing rows from whitespace cells (unreleased)

`PdfNativeLayoutOptions.TrailingRows.SOURCE_CELL_SPACER_ROWS` appends source lines below a
provider table while they repeat the table's own whitespace cell lattice. Some producers, such
as HTML-to-PDF converters, paint an explicit space glyph for every empty or padding cell. They
do so at fixed column positions, including in visually blank rows.

The lattice is the set of whitespace glyph positions repeated by every nonempty provider row,
within 0.1 displayed units. At least two positions are required. Lines below the table are read
in baseline order, and the first line that breaks any of these conditions ends the scan:

- the line repeats every lattice position;
- every glyph is upright;
- every glyph lies within the table's horizontal extent.

Blank lattice lines are crossed but never become rows. Each visible line becomes one row, and
each of its native runs must fit inside one of the last provider row's unmerged columns.
Footnotes, separate notes and aligned numbers that lack the lattice stay ordinary text. A
conflicting, rotated or column-crossing extension keeps the provider table.

```java
final var layoutOptions = PdfNativeLayoutOptions.defaults()
        .withLeadingRow(PdfNativeLayoutOptions.LeadingRow.SOURCE_NUMERIC_OR_SECTION_ROW)
        .withTrailingRows(PdfNativeLayoutOptions.TrailingRows.SOURCE_CELL_SPACER_ROWS);
```

Leading-row inference, if also selected, runs first. Original anchors, row indices and
provenance are unchanged, and added anchors count against `maxTableAnchors`. Accepted tables
carry `pdf_table_trailing_boundary_policy` (`source_trailing_cell_spacer_rows_v1`) and
`pdf_table_added_trailing_rows`. `PROVIDER_ONLY` remains the default.

`PdfNativeLayoutOptions` gains a sixth record component, `trailingRows`. All earlier
constructors are kept and select `PROVIDER_ONLY`. Code that calls the canonical constructor
or deconstructs the record must account for the new component.

On the complete NVIDIA replay, together with the section-row policy, YOLOX exact complete-row
presence rises 68 → 71/110. The three recovered rows are exactly the classified structural
misses: the page-5 balance-sheet heading and the two page-7 rows below a blank gap. No row is
lost and no extra row appears. TATR stays at 36/110, and strict ordinal stays 0/110 for both
profiles.

This is source-structure inference, not semantic proof of membership. It does nothing for
producers that paint no whitespace cells. See
[trailing-row evidence](../benchmarks/review/pdf-trailing-cell-spacer-rows/README.md).


### Explicit physical-line provider rows (unreleased)

`PdfNativeLayoutOptions.RowLines.SPLIT_ALIGNED_TWO_LINE_ROWS` splits a provider table row into two
rows when the row's cells hold two aligned physical source lines. A typical case is a date header
printed as month and day over year, which a provider proposes as one row.

All of these must hold:

- every cell of the row that owns visible native glyphs (at least two cells) holds exactly two
  upright physical lines;
- the first lines of all those cells end within two displayed units of each other, and the second
  lines likewise;
- every first line ends above every second line.

Whitespace-only glyphs, such as a producer's empty-cell spacers, are ignored. The row is cut
midway between the lowest first-line bottom and the highest second-line top. Later rows move down
by one, and a declared header prefix that covers the row grows by one. Rows reached by or holding
a vertical span are never split.

```java
final var layoutOptions = PdfNativeLayoutOptions.defaults()
        .withRowLines(PdfNativeLayoutOptions.RowLines.SPLIT_ALIGNED_TWO_LINE_ROWS);
```

The split runs after leading- and trailing-row inference and is validated again by the existing
topology checks. Added anchors count against `maxTableAnchors`. Accepted tables carry
`pdf_table_row_line_policy` (`aligned_two_line_rows_v1`) and `pdf_table_split_rows`.
`PROVIDER_ROWS` remains the default. `PdfNativeLayoutOptions` gains a seventh record component,
`rowLines`; earlier constructor signatures are kept, so existing six-argument calls remain
compatible. Record deconstruction patterns and consumers that inspect the canonical record
schema must account for the new component.

**NVIDIA replay** (together with the section-row and spacer-row policies):

- YOLOX exact complete-row presence rises 71 → 77/110 and TATR 36 → 38/110;
- the gained rows are exactly the classified split date-header rows;
- no reference row is lost, and unmatched observed rows fall;
- strict ordinal stays 0/110.

**Transfer** (retained Apple and Amazon provider captures): 5 rows split, characters are
unchanged, and every split is a two-line period header or a body row the provider had merged.

This is a physical-line rule, not semantic header detection: a deliberately two-line single row
is also split. The first frozen wording counted whitespace spacer glyphs as lines and split
nothing. The whitespace exclusion is a disclosed amendment made after that result. See the
[parked-items evidence](../benchmarks/review/pdf-parked-items-2026-10-22/README.md).

### Conservative image clip bounds (unreleased)

```java
final var options = PdfOptions.defaults().withImageClipBounds(true);
final var partitioner = PdfPartitioner.create(options);
```

Each native source Image optionally carries `pdf_image_clip_bounds`:

| Field | Meaning |
| --- | --- |
| `classification` | `EMPTY`, `BOUNDED` or defensive `UNBOUNDED` for the interpreted invocation clip envelope |
| `bounds` | Present only for `BOUNDED`; four displayed `PDFUserSpace` points and page layout dimensions |
| `excludes_image_placement` | True only when the tracked conservative envelope is empty or disjoint from the image placement; false leaves visibility unresolved |

An ordinary unclipped page has a bounded crop envelope. Unsupported curved/larger-compound
clips can leave a wider envelope than the actual clip. An overlapping rectangle is not an
exact supported clip shape and does not prove visible image pixels. Transparency-group
composition, soft-mask alpha, intrinsic pixel transparency and later overpainting remain
separate. Source occurrences and normalized payloads are retained even when exclusion is
reported. This option adds metadata; it does not render, crop or filter images.

`PdfCoordinateConverter` converts nested `bounds` in the same frame as placement geometry;
empty/unbounded classifications carry no coordinates. Existing option constructors select
false and all copy methods preserve the selection. The added `PdfOptions` record component
changes reflective record schemas, generated equality/hash/toString shapes and serializers
that inspect record components; review those consumers when upgrading.

Verification retains all 94 magazine occurrences and all four frozen affine references.
Both the original 18-slot qualified-figure schedule and an additive 24-slot schedule preserve
payload hashes and outcomes with the option enabled. These are regression checks; no
original-document pixel-fidelity improvement or closure of the compositing gate is claimed.
See [image-clip evidence](../benchmarks/review/pdf-image-clip-bounds/README.md).


### Enclosing image transparency-group context (unreleased)

```java
final var options = PdfOptions.defaults().withImageGroupContext(true);
final var partitioner = PdfPartitioner.create(options);
```

When selected, every native source Image carries `pdf_image_group_context`, an immutable
outermost-to-innermost list of enclosing painted transparency-group invocation snapshots.
An empty list means no enclosing painted group. Each entry contains:

| Fields | Meaning |
| --- | --- |
| `nonstroking_alpha`, `blend_mode`, `has_soft_mask`, `alpha_is_shape` | Interpreted caller graphics state before the group's internal reset |
| `isolated`, `knockout` | Group attributes interpreted by PDFBox; absent and malformed nonboolean values both resolve to false |

The existing `pdf_image_graphics_state` retains its current-stream meaning. A group's
internal image can correctly report `1 / Normal` while its enclosing group is invoked
under reduced alpha or Multiply blending. Ordinary forms do not add group entries.
Auxiliary soft-mask groups are not directly painted groups and do not become additional
source Images. Alpha-as-shape is not reset by group entry; source state is preserved.

These snapshots are provenance, not effective opacity or final rendering. Their alpha
constants are not multiplied into PNG samples or flattened across groups. Backdrop,
soft-mask contents, group color space, isolation/knockout effects and later occlusion
remain outside this feature. Group entries are plain scalar metadata and survive JSON
round trips and coordinate conversion without unit scaling. Clip bounds are separately
selected with `withImageClipBounds(true)`; the options can be used together.

The default is false and previous constructors remain available. Adding the sixteenth
`PdfOptions` record component changes canonical/reflective schemas and record-derived
serialization/equality/hash/toString behavior; review those consumers on upgrade. Every
copy method preserves independent group-context and clip-bounds selections.

The complete 28-page magazine retains all 94 occurrences and all four frozen affine
references. Five occurrences now expose additional group context: one on page 8 with
invocation alpha approximately 0.57, and four on pages 11/20 with Multiply blending,
while their internal image alpha/blend remain 1/Normal. Every other image metadata field
and identity remains unchanged. This is a real source-metadata gap filled, not a new
rendered-pixel accuracy result. See [the evidence](../benchmarks/review/pdf-image-group-context/README.md).


### Explicit OCR wide-gap line splitting (unreleased)

```java
final var tesseractOptions = TesseractOptions.defaults()
        .withLineSplitting(TesseractOptions.LineSplitting.WIDE_GAPS);
```

Use these options with the existing explicit Tesseract provider configuration. The default
`TESSERACT_LINES` preserves engine line identities. `WIDE_GAPS` divides a recognized line
when the horizontal gap between consecutive words exceeds three times that line's tallest
word box. It preserves recognition order, words and source frames; it changes returned line
boundaries and boxes, including detailed word-line grouping. It is not a new OCR model,
recognition improvement or automatic column detector. The setting applies to the provider's
returned lines and may split a genuine tab-stop/table-cell row; choose it explicitly.

All emitted fragments count against `maxLines`. Fragment accumulation checks the remaining
allowance before retaining excess fragments; all new scans check the shared deadline and
interruption. Existing character limits remain conservative. The new 14th `TesseractOptions`
component retains the prior constructor but changes canonical/reflective record schemas
and record-derived serialization/equality/hash/toString behavior.

On the complete retained 1879 scan, both fixed models improve associated lines 49 -> 50/50,
missing 1 -> 0, without changing recognized text. On degraded 1778, associations improve
17 -> 26/38 (German) and 18 -> 27/38 (Fraktur), while extra fragments increase 11 -> 17
and 10 -> 12. The 1835 and 1780 outcomes are unchanged. These are geometric association
gains, not character-recognition gains; local line errors do not charge every extra fragment.
See [complete outcomes and limitations](../benchmarks/review/pdf-ocr-wide-gap-lines/README.md).


### Explicit OCR printed-rule line splitting (unreleased)

```java
final var tesseractOptions = TesseractOptions.defaults()
        .withLineSplitting(TesseractOptions.LineSplitting.WIDE_GAPS_AND_PRINTED_RULE);
```

This applies `WIDE_GAPS` and also splits page lines at one printed vertical column rule found in
the page pixels:

- **Rule evidence:** a pixel at least eight luminance levels darker than the mean of the pixels two
  columns to its left and right.
- **Rule:** the single longest vertical run of such pixels, with gaps of up to two rows bridged,
  inside the central 10–90% of the recognized-word extent. It is accepted only if it is at least
  eight median word heights long.
- **Split:** a recognized line overlapping the run vertically is split at the rule when it has
  words on both sides and no word box crosses the rule.

Table-cell calls never use the rule. Words keep their order and text, extra lines count against
`maxLines`, and the pixel scan checks the call deadline.

On the degraded 1778 scan, the rule is found at x≈968 and associated lines rise 27 → 31/38 under
both pinned models. Missing lines fall 11 → 7, extra predictions fall (13 → 11, 11 → 9) and joint
character errors fall (364 → 311, 319 → 260). XML-sequence edits are unchanged, and XML-order
reversed pairs rise 30 → 43 as matched pairs grow.

The 1835, 1879 and 1780 controls and two unused transfer pages (1778/0066, 1780/00002) give
output identical to `WIDE_GAPS`. On 1780 the detector accepts the scanner border as a rule but
makes no split. The runtime option reproduces the evaluated rule exactly on all 12 observations.

This is a heuristic for faint printed column rules. It does not detect unruled columns, and an
independent labelled two-column scan has not been tested. See the
[parked-items evidence](../benchmarks/review/pdf-parked-items-2026-10-22/README.md).

### Explicit OCR guarded rule columns (unreleased)

```java
final var tesseractOptions = TesseractOptions.defaults()
        .withLineSplitting(TesseractOptions.LineSplitting.WIDE_GAPS_AND_GUARDED_RULE_COLUMNS);
```

This keeps `WIDE_GAPS_AND_PRINTED_RULE` unchanged and adds a separate policy with two additions:

- **Guard:** the found rule is used only if at least three Tesseract lines overlapping the run
  vertically have a word ending at or left of it, and at least three have a word starting at or
  right of it. Otherwise the output equals `WIDE_GAPS`, which rejects a scanner border that has
  text on one side only.
- **Column order:** lines whose vertical centre lies inside the accepted run are emitted left of
  the rule first, then right of it. Lines before the band keep their place, and later lines follow
  in their original order.

Text, words, boxes and line counts equal the printed-rule output whenever the rule is accepted;
only the order changes.

Results on the degraded 1778 scan, under both pinned models (no-rule baseline → printed rule →
guarded columns):

| Metric | No rule | Printed rule | Guarded columns |
|---|---|---|---|
| Associated lines | 27/38 | 31/38 | 31/38 |
| XML-order reversed pairs | 30 | 43 | 31 |
| XML-order correct pairs | 321 | 422 | 434 |
| XML-sequence edits | 413 / 374 | 413 / 374 | 342 / 296 |

The guard rejects the 1780 scanner border. The 1835, 1879 and 1780 controls and the two transfer
pages give output identical to `WIDE_GAPS`.

These measurements replay retained word captures through the public TSV parser with the page
rasters. The engine was not re-run in that session. The order is a two-column reading assumption
for ruled degraded scans, not semantic reading-order detection, and no independent two-column scan
has been tested. See the
[parked follow-up evidence](../benchmarks/review/pdf-parked-followup-2026-10-22/README.md).

### Curved clipping envelopes (unreleased)

```java
final var options = PdfOptions.defaults()
        .withImageClipBounds(true)
        .withCurvedClipEnvelopes(true);
```

The explicit curved-clip option accumulates the transformed path endpoints and Bezier
control points, then intersects their enclosing rectangle into the graphics visitor's
interpreted clip state. This encloses the true curve, including multi-subpath winding
cases, but is not the actual shape. Existing save/restore and form/Type3 path isolation
apply. Invalid/nonfinite extent falls back to the existing conservative behavior.

This affects image clip metadata and native rule eligibility in the graphics traversal,
including when image metadata is not selected. The separate native-text visitor's explicit
path-clip filtering is unchanged. It does not add pixel cropping, actual curved rendering,
soft-mask composition or occlusion. The default remains false and allocates no new path
extent arrays. Existing constructors remain available; the 17th PdfOptions record component
changes canonical/reflective schemas and record-derived serialization/equality/hash/text.

All 94 retained magazine occurrences remain, with exactly 11 page-8 clip envelopes tighter,
83 unchanged, none widened and no newly proven exclusions. Other image metadata remains
unchanged. Seven owned controls cover the image behavior, source frame/form restoration,
control-point over-approximation and independently selected native-rule filtering. See
[the full evidence](../benchmarks/review/pdf-curved-clip-envelopes/README.md).


### Existing scientific inline-script configuration evidence

The retained complete scientific evaluation improves from 108/143 to 113/143 exact
paragraphs when the existing tagged-paragraph configuration additionally selects
`PdfTaggedParagraphOptions.InlineScripts.NEARBY_SMALL_GLYPHS`, alongside SOURCE_LINE_WRAPS,
PAGE_FRAGMENTS and explicit PdfParagraphAssembler assembly. Preserved pairs are
3,864/4,942; unmatched/duplicated references remain. This is configuration evidence,
not newly implemented reading order, lexical repair or held-out accuracy. Untagged fallback
and publisher/PDF text differences remain. See [the complete diagnosis](../benchmarks/review/pdf-scientific-diagnosis/README.md).

The [business miss classification](../benchmarks/review/pdf-business-miss-classification/README.md)
identifies reference parenthesis spacing separately from titles, date-header presentation
and actual proposal coverage. The strict original scores remain unchanged; no reference
normalization decision or extraction repair is implied by that diagnostic.
