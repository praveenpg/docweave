# Source mask roles and affine-placement diagnosis

This retrospectively diagnoses the complete scientific-magazine image discrepancy,
without modifying its106-reference inventory,2-point tolerance, captures or scores.
The original reported comparison remains94 emitted/90 matched/16 missing/4 unmatched.
No extraction algorithm or measured recognition quality changes in this audit.

The bounded JVM source-graph audit finds94 directly invoked PAGE images and12 image
invocations in explicitly activated SOFT_MASK groups. Every one of the94 emitted page
placements corresponds one-to-one to a direct source invocation within0.001point float
rounding tolerance. This diagnostic shares PDFBox/source-coordinate code with extraction;
it is not an independent pixel/visibility oracle. Auxiliary mask invocation is different
from a separate page-image element. The full106-invocation graph stays in the report.

All four emitted placements that failed the independent HTML-rectangle comparison are
on page8 and involve non-axis-aligned/mirrored transforms. A separate Poppler26.10.0
Cairo SVG export exposes the raster dimensions, six-number affine matrices and containing
group translation. Applying all four corners yields one uniquely corresponding emitted
placement per case under the unchanged two-point edge tolerance. The pinned numeric SVG
reference and all four results are retained. Cairo may re-encode/composite images and
round raster bounds: this establishes envelope consistency, not pixel identity or an exact
recovery of arbitrary clip shapes. No nearest match, content repair or tolerance tuning.

These findings explain why the original comparison does not establish16 lost page images
or four bad extractor placements. They do NOT rewrite it as a newly perfect106/106 score.
Mask pixel fidelity, page compositing/visibility, clipped physical figure boundaries and
broader held-out image/caption quality remain separate open evidence requirements.

## Reproduction and controls

Use exact source/reference/capture pins from `pdf-caption-large-negatives`. Compile
`AuditPdfMaskImages.java` and `VerifyAffineImagePlacements.java` with the Java21 reactor
classpath. Run the mask tool with source PDF, original source-bearing reference, original
extraction capture and fresh output. It validates source/reference/extraction identities;
limits source to16MiB/128pages,500koperators per page,1024 image invocations and16 nested
mask groups, rejects active mask cycles and unsupported inline images, and preserves
parsing/cancellation failures. Only invoked XObjects are counted; unused resources are not.
Mask counts describe activated source groups, not general rendering execution counts.

Owned controls author two direct image occurrences plus one luminosity-mask image and
one unused image resource; verify role counts, operator rejection, cancellation and fresh
post-cancellation traversal. The first authoring control omitted the mandatory transparency
Group dictionary; it failed before original inference and was corrected without changing
its expected roles. The separate affine verifier checks independent analytical quarter-turn
and mirror corners before processing the four observed-source cases. It accepts reference,
original extraction and fresh output paths. Pixel/model/raw source data remain outside Git.

Private strict Javadocs, pinned formatting and JVM standalone source compatibility pass.
The last production baseline stays1,224 passing Java21 tests; no redundant full reactor or
Java25 run is claimed for these diagnostic tools. No dependency, defaults, release, tag,
publication or repository visibility changes occur.
