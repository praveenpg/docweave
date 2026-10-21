# Empty inherited form clips cannot define native tables

Form bounding boxes and page crops already constrain PDFBox's inherited clipping
area. The stored-geometry visitor previously retained strokes/fills even when that
area was empty. An owned zero-area form therefore created a false Table although
its independently rendered pixels exactly matched a no-grid source.

Both stroke retention and supported rectangle-fill interpretation now require a
nonempty inherited clip. This also excludes pale row evidence inside an empty clip.
No new paths, clip intersections, thresholds, inference or public APIs are introduced.
Nonempty clips retain their existing behavior. Explicit path clipping is still not
interpreted, and partial/final rendered visibility is not established. Image-occurrence
and raw native-text policies remain independent of rule evidence.

## Owned controls

Two Java test methods cover four displayed rotations with nonzero crop and UserUnit2.
The negative matrix combines three inherited clip controls (zero-area form bounds,
form bounds wholly outside the crop, nested empty parent) with three paint families
(stroke, dark thin fill, fill/stroke):36 complete source pairs. Every negative first
requires exact rendered RGB equality to the no-grid source, then no Table and every
original text value retained. Visible forms and later visible paint after leaving
an empty form retain the same native table text across all paint families/rotations.
Additional0/180-degree pale-row controls retain three visible bands and reject all
three empty-scope variants, again with exact no-grid rendered equality.

The initial helper compilation omitted the required PdfOptions argument; corrected
before-fix execution fails the intended empty-form/stroke false-Table assertion.
The production fix passes the13-test focused suite with existing fill/opacity controls.
The full Java21 build passes **1,193 tests, zero failures/errors/skips**, including
strict public Javadocs. The added pale-row assertions subsequently pass the same13-test
focused suite; production behavior is unchanged between these runs. Strict private
Javadocs and pinned formatting pass. Java25 remains deferred; no new real OCR calls.

## Original regression and reproduction

All five fixed scientific originals under all three existing native profiles retain
exact complete element/ID/text/metadata/warning fingerprints. Compare
`scientific-java21.json` with `../pdf-stroke-opacity/new-results-java21.json` using
HashNativePartitionResults and JVM JSON tree equality. Only canonical map ordering
is applied; timing/envelope diagnostics are outside these fingerprints and original
source bytes remain independently pinned.

Both full11-page NVIDIA replays succeed. All six statement result records and both
document summaries exactly equal `../pdf-stroke-opacity/nvidia-java21.json`, retaining
strict0/110. The frozen8c4e990 scorer/class override and every original source/render/
model capture identity guard remain intact. No score/reference repair occurs.

Run PdfEmptyFormClipTest, PdfStrokeOpacityTest and PdfFilledRuleTest on Java21, then
full Maven verify. The original replay commands/pins are the same as
../pdf-stroke-opacity and ../pdf-synchronized-model-rows. No raw PDFs/text/renders
are redistributed. This is demonstrated rule-evidence correctness, not an original-
source recall gain or full clipping/rendered-layout guarantee. No release/publication.
