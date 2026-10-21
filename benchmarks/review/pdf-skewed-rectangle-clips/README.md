# Affine-transformed rectangle clipping

The owned counterexample skews a small rectangle-operator clip away from an otherwise
valid grid. Its full-page render equals the no-grid control, yet native extraction
previously emitted a false Table because the explicit skewed clip was ignored.

A finite source `re` is now retained after affine transformation. The existing inherited
clip-envelope check rejects only wholly disjoint rule evidence, with its conservative
stroke allowance unchanged. It does not trim partial intersections or establish visibility
inside the envelope. General skewed line-built, compound and curved paths remain unsupported.
Source image accounting and normalized payloads are unchanged.

## Validation

The new owned method covers 24 disjoint cases: three paint modes, both winding rules and
four rotations with a crop and UserUnit. None emits a false Table. Another 24 controls
verify that subsequent visible paint survives form-state restoration. Existing visible
skew, scale, reflection and unsupported-path controls also pass.

The initial red test first establishes exact PDFBox raster equality, then fails because
the false table is present. The initial focused set passes 26 methods after the fix.
The final full Java 21 reactor, including both winding rules, passes 1,281 tests with
no failures, errors or skips. Changed production/test sources pass private strict Javadocs.

The JVM [Poppler probe](../../tools/VerifyRectangleClipPixels.java) independently checks
22 complete-source RGB pairs, including eight new skewed-clip pairs (both winding rules
and all four rotations, stroke paint). All 22 match exactly at cropbox/72 DPI. The probe
uses an explicitly supplied executable, fresh scratch/report paths and a 30-second
subprocess deadline. Its additional paint-mode coverage remains the PDFBox-owned test,
not an independent Poppler claim. See [the renderer report](poppler-java21.json).

The probe extension followed full verification; the standalone Java 21 compiler test,
private strict probe docs and scoped formatting were then checked separately. Reproduce
using the existing probe recipe in [rectangle clipping](../pdf-explicit-rectangle-clips/README.md),
with the current test-author classes on the explicit runtime classpath. Raw sources/renders
stay outside Git. Receipts contain fingerprints and numeric results only. This evidence
is scoped to the owned source programs, not broad corpus accuracy or image compositing.
