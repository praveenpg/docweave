# Per-image invocation clip-bounds classification (opt-in)

Bounded improvement for the source-image fidelity gate: an explicit, opt-in
`pdf_image_clip_bounds` metadata map per image occurrence, reusing the existing bounded
rule-clip envelope (`PdfInheritedRuleClip`) that native rule/table filtering already relies
on. It never claims final page visibility. It only ever asserts the negative case -
that an image contributes zero pixels - and only when that is geometrically certain.

## Reproduced contract boundary

Before this change, `pdf_image_coordinates` describes source placement and
`pdf_image_graphics_state` describes invocation paint state; neither describes what a
supported clip operator admitted at invocation time, and nothing in the public contract
distinguished an image that was geometrically excluded from one that was merely painted
under some clip. `PdfInheritedRuleClip` already computes a conservative enclosing
rectangle of the current clip for native rule/table filtering, but that computation was
never exposed for images, and clip() silently ignores curved/larger-compound clip
operators (treating them as no additional restriction) rather than narrowing anything -
confirmed by reading `PdfPageGraphics.clip(int)`/`endPath()` and `PdfInheritedRuleClip`.

## Proposed algorithm and API

New opt-in record field `PdfOptions.includeImageClipBounds` (default `false`, preserving
every historical field/order/value) with wither `withImageClipBounds(boolean)`. When
enabled, `PdfPageGraphics.drawImage` adds `pdf_image_clip_bounds`:

- `classification`: `"EMPTY"` (the tracked clip area is proven empty - nothing paints),
  `"UNBOUNDED"` (non-finite transformed geometry; a defensive fallback already present
  in `PdfInheritedRuleClip`, not reachable from an ordinary authored PDF), or `"BOUNDED"`
  (a finite conservative enclosing rectangle, including the ordinary case of no clip
  operator at all, which is simply the full crop box).
- `bounds` (only for `BOUNDED`): the same `{system, layout_width, layout_height, points}`
  shape as the existing `coordinates` field, so `PdfCoordinateConverter` converts it
  through the identical frame instead of leaving mixed units after conversion.
- `excludes_image_placement`: `true` only for `EMPTY`, or a `BOUNDED` envelope proven
  disjoint from the image's own placement rectangle (reusing
  `PdfInheritedRuleClip.intersects(List<Segment>)`, already used for rule filtering).
  `false` for `UNBOUNDED` and any overlapping `BOUNDED` envelope - both stay explicitly
  open; this is the "unknown" side of the classification, by construction rather than by
  a separate flag. An unsupported ancestor clip is simply never intersected, so the
  reported envelope can be wider than the true clip; it is never narrower, so
  `excludes_image_placement` is never a false positive.

Zero new Area intersections, no per-page rasterization and no extra allocation when the
option is off. Group composition, soft masks, intrinsic sample alpha and later opaque
overpainting remain outside the classification, exactly as before.

## Owned controls (`PdfImageClipBoundsTest`, 15 methods; `PdfImageClipBoundsIntegrationTest`, 1 method)

- Default policy omits the map; every historical field stays present.
- Fully unclipped invocation reports `BOUNDED` at the full crop box, not excluded.
- A disjoint rectangular clip proves exclusion; an overlapping one does not.
- `Q` restores the full-crop envelope for a later unclipped invocation in the same page.
- An inherited page-level clip is visible, unchanged, inside a later form's own image.
- The same embedded raster drawn twice under different clips is classified independently.
- Transfer control: nonzero crop origin (10,20) plus a 90-degree rotation transforms the
  envelope exactly like `pdf_image_coordinates` already does; checked against an
  independently hand-derived expectation, not the production geometry helper.
- Negative control: an unsupported curved clip is ignored, not applied narrower, even
  when its real shape would have excluded the image were it honored.
- Negative control: an ignored curve followed by a supported rectangle reports only the
  rectangle's effect - the documented "unsupported history followed by a supported clip"
  case from the investigation brief.
- Negative control: a nested transparency-group soft mask still produces a sound,
  non-crashing `BOUNDED`/not-excluded result without asserting anything about the mask's
  own alpha compositing.
- Negative control: later opaque overpainting over an already-drawn image never changes
  that earlier image's own clip-bounds metadata - this is invocation-time only, never a
  final-visibility or occlusion oracle.
- `EMPTY` from two sequentially intersected disjoint rectangles passes through every
  `PdfCoordinateConverter` target unchanged (no invented coordinates).
- A `BOUNDED` map's nested `bounds` round-trips through points/pixels/millimeters back to
  the original PDFUserSpace numbers, and scales exactly (144 dpi vs the implicit 72)
  like the existing image-corner conversion test does.
- Both the outer classification map and the nested `bounds` map reject mutation.
- The cross-module integration test confirms `pdf_image_clip_bounds` survives
  `ElementJson` encode/decode and chained physical coordinate conversion unchanged.

Every number in `PdfImageClipBoundsTest` was independently hand-derived from the
authored rectangle/crop/rotation coordinates before running the test, the same
discipline `PdfImageCornersTest` already uses; all 15 methods passed on the first run.

## Initial candidate verification (retained historical record)

`sha256sums.txt` pins the four changed production files and the two new test files.
`candidate.patch` is the clean diff of the four changed production files (the two new
test files are included separately as `.java.txt` copies, since they are new/untracked).

Focused: `PdfImageClipBoundsTest` (15/15), `PdfImageClipBoundsIntegrationTest` (1/1), the
existing `PdfImageGraphicsStateTest`/`PdfImageCornersTest`/`PdfImageMaskBudgetTest`/
`PdfStencilImageTest`/`PdfEmptyFormClipTest`/`PdfEmptyTextClipTest` (31/31, unchanged) and
`PdfImageGraphicsStateIntegrationTest` (1/1, unchanged) all pass.

Full: `mvn verify` across the complete reactor passes with zero failures/errors/skips.
`full-verify-java21.log` is the complete run. Summed per-module rollups:
**1,324 tests**, against the handoff's documented **1,308**-test baseline - exactly the
16 tests this change adds (15 + 1), with every pre-existing test unchanged. Strict public
Javadocs and standalone source-tool compilation are included in `verify` and passed.

The complete 28-page magazine original still emits all **94** source image occurrences,
and all **4** frozen independent Cairo affine corner references still pass their
unchanged two-point tolerance (`affine-reference-java21.json`), confirming the default
(`includeImageClipBounds=false`) path is unaffected.

The qualified three-source Figure manifest's own frozen tool
(`VerifySourceFigureImages`/`qualified.json`, `expected_case_profiles: 18`) currently
fails with "Incomplete qualified population" independently of this change: `git log`
confirms `qualified.json`'s freeze commit (`6df7618`) predates commit `d88cf5b`
("Add explicit inherited empty-clip filtering for native PDF text"), which added a
fourth `PdfOptions.TextRendering` value, growing the real profile count for 3 sources x
2 factories to 24. This is a pre-existing drift between a frozen manifest and the current
enum, not introduced or repaired here (repairing it is out of this change's scope and
would rewrite a historical goldens file without authorization). As a direct substitute,
all three qualified sources were extracted under default options outside the frozen
tool: each still emits exactly one image (`expected_images: 1`), unchanged.

## Limits

- `UNBOUNDED` is a defensive fallback inherited from `PdfInheritedRuleClip`; no owned
  control reaches it, since ordinary authored-PDF geometry does not produce non-finite
  transformed clip corners. It is exercised indirectly by that record's own existing
  callers (rule/stroke filtering), not newly tested here.
- This is still a conservative enclosing-rectangle envelope, not an exact clip shape for
  non-rectangular supported clips (arbitrary four-corner line-built quadrilaterals), and
  never a page-compositing or occlusion oracle. `BOUNDED`-and-overlapping and `UNBOUNDED`
  both leave visibility genuinely open; callers must not read them as "visible."
- The qualified-figures frozen tool/manifest drift above is reported, not fixed.
- No model/engine download, OCR run, public synchronization, release tag or Central
  publication is part of this change. Raw PDFs and transcriptions stay outside Git.


## Integrated review and completed original controls

The reviewed implementation adds one source-preservation/option-copy test to the original
16 new methods. The image clip suite now has 16 methods and the integration suite one,
for 17 new methods in total. Full Java 21 verification passes **1,325 tests**, zero
failures/errors/skips. The focused existing/new PDF image-and-clip population passes 47
methods. Strict private-member Javadocs pass for all changed classes and the standalone tool.
A documented explicit no-argument converter constructor resolves a pre-existing private
Javadoc warning without changing construction behavior. Its final compilation and relevant
regression checks are recorded separately after that documentation-only adjustment.

The original qualified-source manifest is unchanged. Its verifier now uses the explicitly
frozen `profiles` list rather than enumerating a subsequently expanded runtime enum. It
also checks profile uniqueness, both declared factories and complete schedule cardinality.
The original **18/18 slots** pass both with default behavior and `--clip-bounds` enabled.
A separately derived manifest retains the same sources/reference expectations and includes
the newer rendering policy for **24/24 slots**, again under both option states. A JVM
comparison requires complete result arrays, including PNG hashes, to match exactly between
option states. This closes the candidate's substitute-only validation gap without rewriting
historical references. Failed initial verifier behavior remains documented above.

The full original magazine still retains **94/94 occurrences and 4/4 affine references**.
That geometric regression check uses the default policy and does not establish clipped-pixel
or composite rendering correctness. The three original logo sources exercise opt-in behavior
through both factories and each declared/current rendering policy.

`integration-receipt.json` binds final source/test hashes, verification logs and the additive
numeric reports. Original candidate files, test-source snapshots and initial log/hash receipt
are retained separately; their 1,324-test counts describe that earlier candidate. Final
reports have distinct names. No raw document, screenshot or model is added.

### Usage and upgrade considerations

```java
final var options = PdfOptions.defaults().withImageClipBounds(true);
final var partitioner = PdfPartitioner.create(options);
```

The default remains false. Existing constructors are retained; the new record component
still changes reflective/canonical record schemas and record-derived serializers. Copy
methods preserve the opt-in. Nested clip coordinates convert with the existing converter.
UNBOUNDED remains a defensive fallback without a dedicated authored-source control; it is
not evidence that all malformed/nonfinite geometry is safe. No final-visibility guarantee,
image crop API, semantic description or full compositing implementation is added.


To reproduce the completed figure controls, compile the current `VerifySourceFigureImages`
against the Java 21 reactor classpath. Run it with the unchanged qualified manifest, original
cache and a fresh report, then repeat with a new report and final `--clip-bounds` argument.
Repeat both runs with this directory's `current-qualified.json` for the additive 24-slot
schedule. Copy `comparator-source.txt` to `CompareFigureControls.java` in a scratch directory;
run it as a Java 21 source launcher with pairs of reports followed by their required counts:
`BEFORE18 AFTER18 18 BEFORE24 AFTER24 24`. It requires exact complete result-array equality,
including payload hashes. The original input/reference hashes and per-profile inventory
checks remain enforced by the verifier before a report is accepted.
