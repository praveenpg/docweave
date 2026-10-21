# Native statistics-table geometry audit

The original statistics control is **partially ruled**, not borderless. Its selected page
retains1529 native glyphs and334 ordinary runs,126 painted rule segments and no raster
images. Existing ruled, aligned, multiline-aligned and horizontal detectors all return zero
candidates. This is an extraction-strategy coverage gap, not missing native text or an OCR failure.

## Fixed source and diagnostic scope

Use the unchanged `us-020.pdf`, page2 of6, pinned in the
[business source manifest](../pdf-business-cell-text/manifest.json):
SHA256 `d884d32bd2a1d620e0c7b5446e4d0b58ba67dab04e884839b2640b9a35749e99`.
The original saved CSV expectation is50x7/350 positions; that expectation and its evaluation
ROI do not enter this diagnostic. No raw PDF, CSV, transcription or glyph strings are committed.

[AuditNativeTableGeometry.java](../../tools/AuditNativeTableGeometry.java) invokes the actual
production glyph-line and table detectors using defaults. It requires upright source text,
zero page rotation and no accepted ruled grid, so no prior ruled-table ownership needs to
be reconstructed. It hashes the complete original PDF before loading it, selects only the
requested page for this geometry audit, and refuses to overwrite a report. The earlier
[complete-document extraction](../pdf-implicit-filled-rules/README.md) remains the evidence for
full-document outcome and the unchanged0/350 comparison. This audit is not a new corpus score.

## Observed necessary-condition failures

The aligned strategy groups a49-baseline proximity block beginning at121.587 and ending at636.133.
Its first three rows contain two runs each; the fourth contains six and the remaining45
contain seven each. The first row-count mismatch is zero-based row3. This violates the
strategy's complete-block uniform-column prerequisite, before numeric/alignment/barrier
acceptance. No sliding subtable search is performed. Other groups are singletons. The
multiline strategy also returns zero; its supported complete header plus paired first-column
continuations is not general multilevel-header recognition. The audit does not invent a
multiline rejection reason or expose text.

Source horizontal paint occurs at just three ordinates:

| Source y | Original horizontal intervals | Gap from previous |
| --- | --- | --- |
|104.07001 |One full-width interval |— |
|152.367 |Seven sequential intervals |48.296997 |
|639.807 |Seven sequential intervals |487.44 |

All three span x36..558.72003. The current horizontal strategy requires2–32 original
intervals in both upper rules with matching partitions; the first rule has one interval.
It also caps the upper-rule gap at40 units, below the observed48.297. Either necessary
condition excludes this triple. Increasing the gap alone therefore cannot solve it. There
are111 vertical source segments, but the ruled detector emits no closed grid. The committed
numeric reports retain horizontal intervals and block metrics; they do not claim that
unpainted separators or closed cells exist. No geometry is joined or synthesized.

## Reproduction and validation

With the existing core/PDF/PDFBox/Jackson validation classpath and original source cache:

```sh
javac --release 21 -cp "$DOCWEAVE_CLASSPATH" -d /tmp/statistics-audit-classes \
  benchmarks/tools/AuditNativeTableGeometry.java
java -cp "/tmp/statistics-audit-classes:$DOCWEAVE_CLASSPATH" \
  dev.docweave.partition.AuditNativeTableGeometry /path/to/cache/us-020.pdf 2 \
  d884d32bd2a1d620e0c7b5446e4d0b58ba67dab04e884839b2640b9a35749e99 \
  /tmp/statistics-new.json
```

Actual Java21/25 produce byte-identical `java21.json` and `java25.json`. The launcher compiles
for release21, passes strict public/private Javadocs and the reactor standalone-source
compatibility check on both runtimes. Identity mismatch and existing-output checks fail
without creating/replacing a report. Production/defaults/dependencies and reference metrics
are unchanged; this evidence-only milestone warrants no new beta. The last full library
verification remains915 tests per JDK from the beta4 correction.

Next design an explicit strategy for partially ruled tables with multilevel headers, first
using owned independent geometry/text controls for ambiguous headers, missing cells, prose,
intervening images, resource limits and cancellation. Keep the source-backed inference
contract separate from fully painted closed grids, preserve current defaults and test the
same frozen documents before proposing a functionality beta. Modern business/scientific,
physical-scan, held-out and confidence-calibration gates remain open. Approximate85–90%
PDF feature coverage is a subjective planning estimate, not recognition accuracy/readiness.
