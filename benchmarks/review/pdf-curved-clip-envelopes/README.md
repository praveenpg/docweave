# Explicit curved-clip envelopes

## Reproduced gap

The opt-in `pdf_image_clip_bounds` envelope ignores curved clip paths: `PdfPageGraphics.clip`
supports only straight rectangle/line constructs, so an image cropped by a circle or rounded
rectangle reports the enclosing page as its envelope. An independent diagnostic engine
(`ClipKindProbe.java.txt`, PDFBox callbacks only, tracking clip kinds through save/restore and
form/group scopes) tallies the clip constructs in effect for every direct image occurrence of the
28-page magazine (`independent-clip-kind-tally.txt`): 81 straight, 11 curved (all on page 8),
2 unclipped; 94 total.

## Candidate (frozen before original-source measurement)

New 17th `PdfOptions` record component `curvedClipEnvelopes` (default `false`) with
`withCurvedClipEnvelopes(boolean)`; a 16-argument constructor preserves the previous canonical
signature and every copy method carries the value. When selected, a curved clip path contributes
the axis-aligned rectangle enclosing all of its transformed path points and Bezier control points.
A cubic segment lies inside the convex hull of its start and control points, so this rectangle
contains the true clip region under either winding rule. It is intersected into the existing
PDFBox clip state, which gives correct q/Q, form, transparency-group and Type3 scoping without a
parallel stack; the path extent is part of the suspended path state across forms/glyphs.

The option is separate from image metadata because graphics-based native rule filtering
also sees the tighter conservative clip. Integration review confirmed the native-text visitor
has separate state; its explicit-path text filtering is unchanged. Unselected output is unchanged.

## Results

Magazine, explicit ORIENTED_LINES with clip bounds, with versus without the option
(`magazine-curved-envelopes-java21.txt`):

- 94 occurrences before and after; every metadata field other than `pdf_image_clip_bounds`
  identical at every index.
- Exactly the 11 independently identified curved-clip occurrences (page 8) receive tighter
  envelopes; 83 unchanged; 0 widened; 0 newly proven exclusions.
- Total BOUNDED envelope area over all occurrences: about 10.76M → 5.46M square user units.

This is metadata fidelity (a tighter sound envelope), not rendered-pixel accuracy, and no
visibility claim: overlap still does not prove visible pixels.

Frozen default controls on the candidate build: magazine 94 occurrences with all four affine
references passing the unchanged 2-point tolerance (default and `--group-context`); qualified
figures 18/18 and 24/24 under default, `--clip-bounds` and `--group-context`.

## Owned controls (`PdfCurvedClipEnvelopeTest`, 6 methods)

Hand-computed expectations: a cubic circle ignored by default and bounded by its control
rectangle when selected; disjoint curved clip proving exclusion only when selected; a bulging curve
whose control points exceed the curve, with a PDFBox rendering showing a point inside the envelope
but outside the curve unpainted (supplementary; shared machinery); restore/form scoping;
crop (10,20,200,140) plus 90-degree rotation transfer; straight-only documents byte-identical with
and without the option, and option defaults/copies.

Full Java 21 `mvn verify`: 1,340 tests (1,334 baseline + 6), zero failures/errors/skips
(`full-verify-java21.log`). Pinned clang-format 23.1.1 on owned hunks.

## Limits

The envelope is a rectangle, not the curve; rounded corners and circles remain over-approximated.
No compositing, soft-mask, backdrop, knockout or occlusion semantics. Larger straight compound
clips remain ignored as before. Exclusion is proven only when the rectangle is disjoint from the
image placement.


## Integration review

Seven owned methods now cover the original six cases plus native rule evidence independently
of image metadata selection: disjoint curve clipping removes eight source grid segments only
under opt-in. The earlier claim about shared empty-clip text filtering was corrected; that
visitor is separate and is not modified. No new path extent arrays are allocated by default.

The complete final magazine replay reproduces 94 occurrences, 11 tighter/83 unchanged,
zero widened/zero newly proven exclusions and unchanged other image metadata. Current default
corner controls retain all four references; complete 18-/24-slot figure controls pass.
The combined OCR/curve/tool Java 21 verification passes **1,348 tests**, zero failures/errors/skips.
Changed private methods pass strict doclint and scoped pinned formatting. A subsequent
source-comment clarification changes no behavior. Fresh reports/receipt use the integrated
prefix; original candidate and test counts remain historical evidence. No new pixel-fidelity
or native-text filtering gain is claimed.
