# Equivalent line-built rectangle clipping

Before this fix, `re W n` clips native rule evidence, but an equivalent rectangle
written with move/line/close operators does not. The new independently authored
control renders identically under PDFRenderer while the old native extraction
incorrectly emits a table from invisible rules. The corrected output retains the
six original text elements, matching the rectangle-operator counterpart exactly.

Recognize a single finite axis-aligned line-built rectangle with explicit, implicit
or redundant closing return. At most six raw vertices are retained in a fixed48-byte
page-local buffer. Ordinary move/line paint allocates no additional GeneralPath;
only an actual clipping instruction constructs the bounded candidate. A bounded
Area check admits a rectangle or provably empty area, rejecting concave, skewed,
curved, compound and excessive paths. Clip changes remain deferred until path
completion; graphics-state/form restoration and retained rule geometry stay intact.
No partial rule trimming, general path clipping or rendered-visibility claim.

Two new owned methods exercise100 exact raster-equivalent pairs and12additional
restored-paint controls: empty/disjoint clips, stroke/fill/fill-stroke/shaded paint,
all four displayed rotations, nonzero crop/UserUnit, even-odd, implicit closure,
redundant closing return, mirror and scale. Existing unsupported-path and inherited
clip controls remain passing. Initial red test demonstrates false table output;
after the fix12focused clipping methods pass. Full Java21 verify passes1233tests
with0failure/error/skip and strict public Javadocs. Changed private Javadocs and
scoped clang-format23 checks pass. Java25 remains deferred.

All15 complete scientific fingerprints (including every original image corner map)
and all39 full NVIDIA observations remain exact to the source-image-corner baseline.
These are regression checks, not new original-document accuracy gains. No new
source/model runs, thresholds, dependencies, public APIs, publication or visibility
changes.

Reproduce with PdfEmptyFormClipTest and PdfEmptyTextClipTest, then Java21 mvn verify.
The two added methods author/render/extract all controls without external binaries,
fixtures or downloads. The complete-source regression uses the same pinned originals
and profiles as pdf-source-image-corners. Numeric receipt pins baseline/report/source
identities; raw working reports and logs remain outside Git.
