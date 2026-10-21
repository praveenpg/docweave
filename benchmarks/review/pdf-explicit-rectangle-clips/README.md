# Explicit rectangular clipping: Java 21

The graphics visitor now honors one axis-aligned source rectangle clipping path. Previously,
explicitly empty/disjoint clips could leave invisible grid and shaded-row evidence behind.
The existing conservative geometry checks reject empty areas and provably disjoint paint;
coordinates of retained evidence remain unchanged. No public API, dependency, option or
text/image policy changes.

The implementation accepts only a single source `re` operator with finite axis-aligned
transformed edges. The rectangle is tracked independently from displayed rule coordinates,
so crop/rotation frames cannot be mixed. Its intersection is deferred until painting or
`n` completes. Existing PDFBox graphics-state save/restore and form scope control lifetime.
Curved, compound, skewed and separately assembled line-path clips remain unsupported.
Partial intersection is not trimmed and does not establish complete rendered visibility.

`PdfEmptyFormClipTest` adds explicit empty and disjoint rectangle controls to the inherited
clip suite: six invisible scopes x three paints x four quarter turns = **72 exact RGB
no-grid pairs**, with crop and UserUnit2. All retain six ordinary native words and no Table.
Pale-row comparisons at0/180 degrees also prove RGB equality and zero retained bands.
Visible/restored evidence retains exact table text in **96 cases**. A separate owned operator
control verifies deferred `W S` painting, a subsequent empty clip, and restored outer paint.
Existing thick-stroke, transformed width, hairline and enclosing-fill controls remain.

The pre-fix run first passes the shaded no-grid pixel comparison, then fails with three
bands where zero are expected (`/tmp/docweave-explicit-clip-before21.log`). The final focused
suite passes8 tests. Full Java21 `mvn verify` passes **1,196 tests**, zero failures/errors/skips,
including strict public Javadocs. Private strict Java21 docs cover changed production and test methods.

All five scientific originals across all three profiles retain identical complete-element,
ID, text, metadata and warning fingerprints: **15/15 exact**. Frozen full NVIDIA replay
retains all eleven pages, six statement records and two document summaries, recursively
identical to the prior disjoint-form correction. Strict rows remain0/110 for both profiles;
this native correctness fix does not solve the model structure-quality gap. Existing source,
model, reference and scorer pins remain, including frozen8c4e990 scorer implementation.
See sibling disjoint-form evidence for original identities and replay instructions.

## Rejected dash hypothesis

Before this fix, a proposed valid `[0,100]` flat-cap dash rejection was tested against
PDFBox3.0.8 rendering. Exact no-grid pixel equality failed: its PageDrawer clamps zero dash
lengths to0.062 to avoid JVM defects. Therefore that rendered evidence did not support the
proposed filter; **no dash filter was integrated**. The source/API behavior discrepancy is
retained as an open rendering question, not treated as a proven invisible-grid correction.
No threshold retuning or assertion weakening was used to manufacture a pass.

## Transformed clips and second renderer

An additional owned test checks six visible clipping programs (even-odd, mirrored,
nonuniformly scaled, compound, curved and skewed), three paints and four rotations:
**72/72 further exact RGB/table-text comparisons pass**. Compound/curved/skewed paths
exercise conservative unsupported behavior, not new recognition. Only the test helper
and independent benchmark change after the production correction. The focused six-test
class and private strict Javadocs pass; production's1,196-test full verification remains
the applicable baseline, with this additional test validated separately.

`VerifyRectangleClipPixels` uses explicitly supplied Poppler, not PDFBox rendering,
for **14/14 exact full-page RGB comparisons**: eight no-grid comparisons (two explicit
clips x four rotations) and six visible-grid transform/path comparisons at0degrees.
`poppler-java21.json` retains renderer/executable/source identities and every pair.
No binary fixture or renderer dependency is added to library artifacts or ordinary tests.

Compile the benchmark with `docweave-pdf/target/test-classes` plus the existing test/runtime
classpath, then run from the checkout:

```text
dev.docweave.partition.VerifyRectangleClipPixels /absolute/path/to/pdftoppm FRESH_SCRATCH FRESH_REPORT
```

The fixed renderer protocol is cropbox,72DPI,single-page PNG; no source ROI or sample-pixel
selection. Each subprocess has a30second deadline with diagnostics retained on disk.
This independent evidence is scoped to these owned programs, not source-image fidelity,
general clipping, corpus accuracy or a global PDF completion percentage.

The later full build exposed a standalone-compilation dependency on the owned test author.
The benchmark now resolves that explicit runtime helper by reflection, matching other
standalone probes. A second14/14 Poppler run is retained as
`poppler-standalone-java21.json`; the original report and receipt remain unchanged.
The final combined build passes1,200 Java21 tests, including the standalone compiler guard.
