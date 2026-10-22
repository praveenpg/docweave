# Enclosing transparency-group context for source images (opt-in)

`pdf_image_graphics_state` describes the state inside the image's current content stream.
When an image is painted inside a transparency group XObject, PDFBox 3.0.8's
`processTransparencyGroup` resets blend mode to Normal, alpha constants to 1 and the soft mask
to None before interpreting the group, so the internal image correctly reports 1/Normal while
the group itself is composed under the caller's state. That enclosing state was not recorded.

## Contract

New record component `PdfOptions.includeImageGroupContext` (default `false`) with
`withImageGroupContext(boolean)`. A 15-argument constructor preserves the previous canonical
signature; all older constructors and copy methods retain the new value or default it to false.

When selected, each Image gains `pdf_image_group_context`: an immutable list ordered outermost
to innermost, one immutable map per enclosing painted transparency group, snapshotted at that
group's `Do` operator before PDFBox's reset:

- `nonstroking_alpha`, `blend_mode`, `has_soft_mask`, `alpha_is_shape` - the caller's state, with
  the same vocabulary as `pdf_image_graphics_state`.
- `isolated`, `knockout` - the group's `/I` and `/K` attributes; PDF default `false` when absent
  (PDFBox also reads a malformed non-boolean entry as `false`).

An empty list means no enclosing painted group. Ordinary forms add no entry. Only
`showTransparencyGroup` (painted groups dispatched by `DrawObject`) contributes; soft-mask
groups use PDFBox's separate `processSoftMask` route, which this traversal never invokes.
The stack is pushed before `super.showTransparencyGroup` and popped in `finally`, inside the
existing path suspension; depth is bounded by the existing 50-level form limit and operator
budget. No geometry is added, so coordinate conversion leaves the scalar list untouched.
`pdf_image_graphics_state`, PNG samples, occurrences and order are unchanged.

Alpha constants are source provenance, not effective or final opacity: soft masks, blend and
backdrop dependence, alpha-as-shape, isolation, knockout and later occlusion are not combined.

Finding: group entry does not reset the alpha-source flag (neither does the PDF specification
text PDFBox quotes), so an internal image legitimately inherits `alpha_is_shape: true`. This is
existing current-stream behavior and is retained.

## Owned controls

`PdfImageGroupContextTest` (8 methods):

- Existing .5/Multiply form/group/direct sequence: internal maps unchanged (.5/Multiply,
  1/Normal, .5/Multiply); contexts `[]`, `[.5/Multiply]`, `[]`; all other metadata identical to
  the unselected run.
- Nested groups (outer .5/Multiply; inner .25/Screen, explicit `/I true /K true`): ordered two
  entries for the inner image; an outer-group image after the inner group has one entry.
- One group invoked twice (.5/Multiply, 0/Normal): per-occurrence contexts, empty for a later
  direct image; list and entry maps reject mutation.
- Group inside an ordinary form and form inside a group (`/I true`): exactly one entry each;
  page state restored for a later direct image.
- Controlled diagnostic: opaque red raster in non-isolated, non-knockout groups at alpha 0 and
  .5. PDFBox rendering leaves the alpha-0 pixel white and changes the .5 pixel; both
  occurrences and identical PNG payloads are retained. No general visibility claim follows.
- Invocation soft mask with alpha-source flag: reported as descriptors; the mask group's own
  raster is not emitted (one occurrence).
- Self-recursive group: typed `INPUT_TOO_LARGE` from the existing depth limit; tight operator
  budget: typed `INPUT_TOO_LARGE`; a later independent extraction yields an empty context.
- Defaults, retained constructors and copy-method independence from `includeImageClipBounds`.

`PdfImageGroupContextIntegrationTest`: ElementJson round trip and pixel/point conversion keep
the context, identity and PNG payload unchanged.

## Original-source controls

`VerifySourceImageCornerMaps` and `VerifySourceFigureImages` gained an optional
`--group-context` argument; default invocations and report shapes are unchanged.

- Magazine default: 94 occurrences, 4/4 frozen affine references (max differences .560, .405,
  .597, .614 points against the unchanged 2-point tolerance).
- Magazine `--group-context`: same 94 occurrences and 4/4 references; every occurrence's other
  metadata is identical to the default run. Depth histogram: 89 at depth 0, 5 at depth 1. Those
  five report internal 1.0/Normal, but their enclosing group is alpha .570/Normal (page 8) or
  1.0/Multiply (pages 11 and 20) - the missing context this change makes visible. This is
  source provenance, not a rendered-pixel accuracy result.
- Qualified figures: 18/18 historical slots and 24/24 current slots match, default and
  `--group-context`; one PNG hash (`5398a47e6c01...`) throughout; context depth 0.

## Verification

Full Java 21 `mvn verify`: 1,334 tests (1,325 baseline + 9 new), zero failures/errors/skips,
including strict Javadocs and standalone tool compilation (`full-verify-java21.log`).
`candidate.patch` contains the four modified files and the two new tests; `sha256sums.txt`
pins them. The pinned formatter was not applied: it requires clang-format 23.1.1 and the
installed version is 23.1.3. Only an unchanged pre-existing string literal exceeds 120 columns.

## Limits

- No compositing: group alpha/blend is not multiplied into pixels or flattened into an
  effective opacity; backdrop, isolation and knockout semantics are reported, not applied.
- Soft-mask contents, luminosity/alpha mask results and the group's color space are not exposed.
- Isolated/knockout values follow PDFBox's boolean reading; a malformed entry is
  indistinguishable from an absent one.
- Group BBox clipping is reflected only by the separate opt-in clip-bounds envelope.
- The alpha-0 rendering check uses PDFBox, which shares implementation machinery; it is a
  controlled diagnostic only.


## Integration review

The final implementation was formatted with the pinned clang-format **23.1.1** from the
existing local tool cache; the earlier formatter limitation above describes the initial
candidate only. Changed sources/tests/tools pass scoped formatting and strict private
Javadocs. Snapshot capture now occurs inside the existing path-restoration try/finally,
with a captured flag ensuring only a successfully pushed entry is popped. This also
restores caller path state if snapshot creation fails before group interpretation.

The existing option-copy test now covers every copy method. The cross-module integration
control enables both group context and clip bounds, checking that JSON/coordinate
conversion retains source context while the independent geometry feature coexists.
These strengthen the existing nine new methods without changing the method count.

The refreshed magazine report retains all five nonempty contexts with occurrence indices,
page numbers and the separate internal image maps, alongside the complete depth histogram.
The opt-in replay also checks image identity/type/text and every other metadata entry.
The page-8 group alpha is 0.5700070261955261 as interpreted from source; 0.57 in the summary
is rounded. Four page-11/20 images retain caller Multiply while their internal maps are
Normal. All 94 occurrences and four frozen affine references remain.

Refreshed 18- and 24-slot qualified-source group-context runs retain complete populations
and source payload expectations. Historical manifests/reports are unchanged. Final source
hashes, verification logs and numerical captures are bound in `integration-receipt.json`.
The original candidate patch, source-hash receipt and initial verification log remain
historical artifacts. This completes an explicit source-context addition; it does not
close final compositing/occlusion or broader independent PDF acceptance.

### Reproduction

Compile current `VerifySourceImageCornerMaps` and `VerifySourceFigureImages` with Java 21
against the current reactor runtime classpath. The former accepts original magazine PDF,
frozen affine reference, fresh output and optional `--group-context`; that flag re-extracts
with context and verifies all other metadata and image identities remain unchanged.
The latter accepts qualified manifest, original cache, fresh output and optional
`--group-context`; run once with the original 18-slot manifest and once with the additive
24-slot manifest under `pdf-image-clip-bounds/current-qualified.json`. All source hashes,
reference tolerances and declared populations remain enforced. No new engine/model call
or Python evaluation is required.


Final reviewed Java 21 `verify` passes **1,334 tests**, zero failures/errors/skips.
The focused current image/clip/group population passes 51 PDF methods. Strict private
Javadocs and scoped pinned formatter checks pass. No Java 25 checks, OCR/model execution,
new dependency or artifact publication is included. The earlier local snapshot installation
only populated the local Maven cache; it is not a Central release.
