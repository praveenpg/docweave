# Form-local graphics path isolation

An owned PDF reproduces a lost table when an invisible form paints between the
caller constructing and stroking its grid. Moving the same invocation after the
stroke produces identical PDFBox-rendered pixels but previously different tables.
The renderer saves/restores caller paths; the extraction visitor previously shared
mutable unfinished paths across child streams.

`PdfFormPathTest` supplies 32 exact raster-equivalent pairs: four page rotations,
normal/transparency dispatch, empty/visible-grid/nested children and interrupted
caller rectangle clips. Cropping and UserUnit are present. Whole public extraction
outputs equal their independent counterpart; visible child tables are retained.
Separate cancellation and operator-limit controls verify restoration in both
routes and that entering a child does not reset the page-wide operator budget.
The pre-fix owned test failed with six text elements instead of the parent Table.

The fix transfers only unfinished path ownership, restoring it in `finally`.
Accumulated segments/images and resource counters remain shared; PDFBox retains
ownership of graphics-state and resource scopes. No source-specific defaults,
thresholds, new public API, dependency or transparency compositor were introduced.

Java21 full reactor verification: 1,242 tests, zero failures/errors/skips. Fourteen
focused methods, private Javadocs and scoped formatting pass. JVM comparisons of
all 15 existing scientific fingerprints and 39 NVIDIA records are exact, including
source-image corners. These are preservation checks, not new original-document
accuracy claims. Numeric receipts and source hashes are in `java21.json`; fixtures
are authored in Java and require no private downloads or Python test dependencies.
