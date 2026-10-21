# Ordered source-image corners

Every native source Image now retains `pdf_image_coordinates` as additive metadata,
independently of its unchanged enclosing `coordinates` rectangle. Four points retain
source image-unit identities00,10,11,01 after the actual invocation transform and
crop/display rotation. Mirroring and shear are no longer lost in the public geometry.
This describes source placement, not clipping, visible pixels, soft-mask compositing,
page crops, vector figures or human figure interpretation. No new dependency, option,
service implementation type, OCR/model execution or default text/table strategy.

The existing `PdfCoordinateConverter` interface now converts this map as well as
ordinary and table-cell coordinates, preserving point order and source attributes.
Physical conversion uses PDF UserUnit once. The original element stays immutable;
malformed/nonfinite geometry is rejected. PNG decoding/bytes are unchanged; source
unit01,11,10,00 correspond to normalized PNG top-left,top-right,bottom-right,bottom-left.
See [the field contract and usage](../../../docs/pdf-support.md#ordered-source-image-placement-corners-unreleased).

Three new owned JUnit methods verify six affine transforms (ordinary, reflected,
quarter-turned, sheared, vertically reflected and off-page) across four displayed
page rotations, nonzero crop and UserUnit2, in both reference/PNG modes. Independent
analytical corner and enclosing-bound expectations, original colored PNG pixels,
repeated inherited forms, inline raster invocation, reversible physical conversion,
malformed-map rejection and deep immutability are covered. Millimeter round trips
allow1e-10 floating-point roundoff per scalar; original pixel/non-geometric metadata
stays exact. The first test expectation incorrectly demanded bitwise equality for
millimeter round trips and was corrected without changing production conversion.

Before rebuilding, the old compiled28c1827 production output was captured across
all five complete original scientific PDFs and three uniformly applied native
layouts (15observations). All outcomes succeed. The baseline has zero corner maps;
the candidate has93 across these profiles. After excluding only the explicitly
added top-level element-metadata field, every original element ID/type/text/order,
other metadata field, warning code/message and stable result envelope field is
identical. Elapsed duration is excluded. Whole-element hashes intentionally change
where image metadata grows; historical full hashes are not rewritten or called exact.
Numeric source hashes, modes, counts and before/after fingerprints remain here.
The fingerprint procedure sorts all map keys recursively, preserves all list order
and exact JSON scalars, and hashes UTF-8 Jackson serialization with SHA256. The
legacy envelope includes elements, warnings, format, encoding and input bytecount.
Only `pdf_image_coordinates` is omitted from that legacy element metadata. Neither
text nor any nested provenance or other metadata is normalized.

The complete original28-page Immunology News magazine adds94source-corner maps.
All four earlier unmatched affine placements are checked against the previously
frozen independent Poppler26.10.0/Cairo SVG matrices/dimensions/parent translations,
now in corner identity order, not only their envelopes. Each has exactly one matching
original occurrence; all4pass the original2point per-coordinate tolerance. This is
retrospective geometry evidence, not held-out pixel fidelity. The earlier106reference
inventory and its mask/affine discrepancies are not relabelled or rewritten. The
[original numerical references](../pdf-image-mask-affine-audit/affine-reference.json)
and raw SVG identity remain unchanged; the raw magazine/SVG are outside Git.

All39 full NVIDIA frozen-scorer observations remain exact to the prior oriented-column
baseline. This is table/text regression protection, not a new row fidelity gain.
Full Java21 `mvn verify` passes1,229 tests with0failure/error/skip and strict public
Javadocs/standalone-source compile. Six changed Java files also pass strict private
Javadocs and scoped pinned formatting. Seventeen focused image/conversion/mask tests
pass. Java25 stays deferred to PDF completion. No publication, tag or visibility change.

Reproduce the independent check by compiling `VerifySourceImageCornerMaps.java`
with release21 and current module runtime dependencies, then run it with original
magazine PDF, existing affine-reference.json and a fresh report. It hashes the
original source, processes the complete document with explicit ORIENTED_LINES,
requires all94occurrences and all4reference slots, preserves corner identities,
and does not fit transforms or select the nearest match. Owned tests are in
PdfImageCornersTest; full reactor verification covers conversion consumers.

## Packaged consumer check

A separate Java21 process compiles/runs against only the current built core/PDF JARs
and PDFBox3.0.8, pdfbox-io3.0.8, fontbox3.0.8 and commons-logging1.4.0. There are six
library JARs and no reactor target/classes or test dependencies. Public ServiceLoader
provider discovery processes one owned PDF with a mirrored and a sheared occurrence
of a two-color raster. Both ordered corner maps convert exactly to144DPI and back,
retaining complete element identity/metadata and the default reference-only policy.
The process verifies Jackson3, jsoup, POI and CommonMark are absent. Numeric receipt
consumer-java21.json pins all six artifact names/bytes/SHA256 and actual Java version.
This reuses the already built0.1.0-SNAPSHOT JARs; it is not a new signed/installed beta.7
candidate or Central consumption check, and does not claim an extraction quality gain.
