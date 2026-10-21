# Frozen multi-page statement transfer: another publisher

This is the first DocWeave extraction of this original NVIDIA release. The publisher serves
both the original11-page PDF and matching HTML. The PDF is untagged, produced by Qt4.8.6,
with595x842 original pages and zero rotation. It is not a locally printed/reconstructed PDF.
Raw originals and Poppler renders remain outside Git; URLs, byte counts and SHA256 identities
are frozen in `manifest.json`.

Independent Poppler text inventory and rendered original pages4–7 selected the first three
complete core statements before DocWeave extraction. Income spans pages4/5, balance spans5/6,
and cash flows span6/7. All six original-page ROIs are frozen. References are complete direct
publisher HTML tables2/3/4:28/33/49 nonblank rows,110 per configuration. HTML cell/span styling
is not a physical PDF geometry label. Issuer title/header rows and every failed case remain.

The existing JVM evaluator now optionally accepts fixed `regions` for a complete statement.
Each region independently selects maximum parent-box IoU>=.5, with stable first-output ties.
No reference text enters extraction or association. Observed rows concatenate in declared
original-page order, without joining broken rows, removing repeated headers or deduplicating
boundary content. Missing fragments retain their observations and full reference denominator;
partial row matches cannot mark the statement complete unless every region associates.
The complete original PDF is extracted once per configuration, with default resource limits.

All10 native configurations plus the existing source-tagged and new logical-envelope factories
run uniformly on frozen production commit `b0b42d46c5a71b802bba90d311a6c03cabdd065e`.
The manifest records its abbreviated identity; `scorer-regression.json` records the full one.
There is no production tuning in this run. Because the source has no structure tree, the tagged
factories exercise their native fallback; this is not positive logical-tag generalization.

On actual Java21 and25, all36 case/configuration observations agree. Complete-document processing
succeeds and ordinary native text is present, but no table associates in any of the72 page-region
observations. All12 configurations therefore score0/110 structured rows. This is a measured
native multi-page statement-recognition gap, not a zero-text result or a general accuracy estimate.
OCR, model layout and Unstructured were not executed on this source.

All124 prior single-page business observations replay unchanged on both runtimes, including
Walmart105/105 on its explicit logical profile and earlier59/59,114/114,113/113 native results.
Four new JVM controls cover original-page ordering, geometry-only ties, out-of-region content,
partial captures, repeated rows, typed failures, fixed denominators and invalid/mutable regions.
`VerifyMultiplePageRows.java` checks paired results, all cases/regions and arithmetic. Seven
corrupted paired-report controls reject missing cases/regions, changed denominators, false
completion, false region coverage, wrong pages and duplicate observations.

Both actual JDK clean full builds pass1,109 tests with zero failures/errors/skips and strict public
Javadocs. Strict private Javadocs for the evaluator/verifier and the pinned formatter pass.
The frozen scorer hash matches final bytes. No production API/dependency/default, release,
tag, GitHub release or Central publishing change.

To reproduce with verified originals in a local cache and a fresh report path:

```sh
mvn -B -ntp -pl docweave-validation -am test \
  -Dtest=PdfCurrentBusinessRowsTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Ddocweave.pdf.current.business.cache=/path/to/originals \
  -Ddocweave.pdf.current.business.manifest=benchmarks/review/pdf-unseen-business-nvidia/manifest.json \
  -Ddocweave.pdf.current.business.report=/path/to/fresh-report.json
```

Run the source verifier on paired Java21/25 reports using the project's runtime classpath;
its four arguments are manifest, reports21/25 and a fresh receipt path. Do not overwrite historical
reports when testing a new extraction policy. A new policy needs a separately frozen protocol.
