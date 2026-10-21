# Normalized image-mask pixel bounds

PNG pixel preflight now accounts for the active image mask's larger width and height.
Previously a two-pixel image with a64-pixel soft mask succeeded at maxImagePixels=2.
PDFBox combines planes at their maximum width and height, so the actual PNG could
exceed the caller's ceiling. The correction also checks the returned raster before
encoding. It adds no dependency, decoder resampling or public API signature.

| Owned source geometry | Normalized PNG | Rejected ceiling | Accepted exact ceiling |
| --- | --- | ---: | ---: |
| Base2x1, active mask64x1 |64x1|2 and63|64|
| Base2x16, active mask16x2 |16x16|32 and255|256|

Both cases run with soft and hard masks. The crossed-axis case demonstrates why
checking either plane's pixel count separately is insufficient: each has32 pixels,
but their normalized rectangle has256. The byte budget remains cumulative per
document; pixel preflight describes one resulting extent rather than summing planes.

## Metadata and compatibility

image_width/image_height remain the original main-image dimensions. When normalized
PNG size differs, image_payload_width/image_payload_height record the actual encoded
raster size. Placement coordinates and source dimensions retain their meaning.
Reference-only extraction performs no mask decoding or payload-size metadata work.
Previously accepted over-ceiling payloads now fail with typed IMAGE_LIMIT.

A soft mask takes precedence over a hard mask. A non-stencil hard-mask stream is not
an extra image plane, and color-key arrays do not allocate another plane. Solid stencil
paint decoding does not composite additional masks. The preflight follows these
backend choices instead of traversing unused or nested resources. Supporting image
planes use opaque decoding, so arbitrary recursive mask-graph traversal is not added.
Page graphics-state compositing groups and rendered-pattern previews remain outside
source-image PNG extraction. Bounds on declared extents and returned PNGs do not
establish a hard memory bound on arbitrary internal codec allocations.

## Validation

Five JVM controls exercise both mask types, exact boundaries, combined-axis growth,
preflight before an unsupported mask filter is decoded, soft-mask precedence over an
unused malformed hard mask, ignored non-stencil streams, same-size alpha without
double charging, color-key transparency, unchanged reference geometry and actual PNG
size metadata. The first fixture draft conflated active malformed hard masks with
ignored hard-mask streams; a distinct private MaskMode enum corrected fixture intent
without relaxing expected IMAGE_LIMIT failures.

Full actual Java21 verification passes **1,136 tests**, zero failures/errors, with
strict public Javadocs and the original-source opt-in enabled. Strict private Javadocs
also pass. The formatter and diff check pass. Java25 remains deferred.

The new PdfImageMaskOriginalsTest reuses the three frozen issue PDFs and preceding
candidate captures from [the original stencil study](../pdf-external-stencils/README.md).
All five pages process successfully; all186 PNGs and complete saved public element/
warning captures remain exactly equal after parsing, with **no excluded fields**.
The source inventory still contains187 entries and one unmatched auxiliary raster;
the new report binds that preceding inventory rather than rerunning Poppler or
removing the mismatch. This is original-source regression, not new external accuracy
for differently sized masks, semantic figures or page-composited pixels.

Ordinary builds use no source cache or installed engine. To repeat the optional JVM
regression, explicitly supply all three properties and a fresh report path:

```sh
mvn -B -ntp -pl docweave-validation -am test \
  -Dtest=PdfImageMaskOriginalsTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Ddocweave.pdf.image.masks.originals=/path/to/verified-originals \
  -Ddocweave.pdf.image.masks.baseline=/path/to/prior-candidate-captures \
  -Ddocweave.pdf.image.masks.report=/path/to/fresh-report.json
```

Use the same installed JDK for strict encoded-PNG regression. The test verifies original
SHA256/byte counts, baseline manifest/JAR identities, current loaded implementation,
all source cases and complete saved captures. Every typed failure is recorded before
enforcing equality, without reference repairs or output normalization. Serialization
roundtrip only matches the baseline's JSON number representation. Raw source bytes,
publisher text and PNG payloads remain outside Git. No release/Central action occurs.
