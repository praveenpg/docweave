# Proven disjoint inherited rule paint

The earlier empty-clip correction cannot reject paint outside a nonempty form clip.
An added source control has a nonempty20x20 inherited clip entirely away from every
grid rule/pale row. Its rendered RGB exactly matches the no-grid source, but the
previous visitor retains three pale bands. Native text can also be claimed by its
invisible grid. The corrected fixture and pre-fix failure are retained separately
from an initial fixture edit that had not yet assigned the new bounding box.

`PdfInheritedRuleClip` now encloses the existing inherited clip in the same displayed
page frame as original rule geometry. Source/output float boundaries round outward.
Stroke candidates use a conservative allowance for transformed line width, caps and
miter joins; nonuniform transforms are included. Each straight candidate is rejected
only if its envelope is wholly outside that enclosing clip rectangle. Zero-width
hairlines and nonfinite/unsupported metrics retain historical evidence. Empty clips
remain rejected. No new clip intersections or rasterization enter extraction.

Supported fills/pale bands compare their complete original rectangular envelope,
not separate edges or the derived centerline. Thus a fill completely enclosing the
clip remains eligible. Retained evidence keeps its original coordinates; no edge is
trimmed, joined, moved or inferred. Explicit path clips, dashes, compositing/occlusion
and full rendered visibility remain outside the contract. Intersecting enclosing
rectangles do not prove that paint is visible inside a nonrectangular/partial clip.

## Owned validation

The complete grid matrix now has48 exact no-grid RGB pairs: four inherited scopes
(empty bounds, outside crop, nested empty, nonempty disjoint) by three paint families
by four rotations, with nonzero crop and UserUnit2. Every negative retains source text
without a Table. Pale-row controls cover0/180degrees; all four invisible variants
have zero bands and exact no-grid RGB, while visible paint retains three bands.

Sixty positive grid/restoration configurations retain the same native table text.
Additional controls prove actual rendered stroke width crosses a clip edge even
when the centerline lies outside, under four rotations and nonuniform scaling.
Device-dependent hairline fallback remains explicit. A fill enclosing the clip keeps
its pale-row evidence despite no individual boundary edge intersecting that clip.
New segment filtering checks cooperative interruption every256 candidates, within
the existing bounded source path/operator traversal. No public API/default or resource
ceiling changes occur.

Focused15 tests pass; final full Java21 verification passes **1,195 tests with zero
failures/errors/skips**, including public strict Javadocs. Private strict Javadocs and
pinned formatting pass. Java25 is deferred; no new real OCR calls are claimed.

All15 pinned scientific element/ID/text/metadata/warning fingerprints are exact versus
../pdf-empty-form-clips/scientific-java21.json. Both complete11-page NVIDIA profile
replays retain every source/render/capture pin and frozen8c4e990 scorer; all six
statement records and both document summaries are exact, strict0/110 unchanged.
Original timing/envelope diagnostics are outside those scientific fingerprints.

Run PdfEmptyFormClipTest, PdfStrokeOpacityTest and PdfFilledRuleTest, then Java21
Maven verify. Original replay instructions/pins are unchanged from the preceding
empty-form and stroke-opacity studies. Numeric reports are included; raw source PDFs,
text and renders remain outside Git. No external recall gain, full clipping guarantee,
release or publication is asserted.
