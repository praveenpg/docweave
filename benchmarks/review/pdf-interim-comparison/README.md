# Interim PDF comparison protocol and refreshed scientific run

The current [comparison matrix](../../../docs/pdf-comparison.md) consolidates the completed
native-text, configured-table, configured-OCR, documented-default and native-text performance
runs, plus the narrower independent source-raster audit. Final clean verification passes1,036
tests on each actual Java21/25, with strict public and touched-private Javadocs. The
[final receipt](final-verification.json) records runtime versions, log hashes, active rescoring
tracks and remaining measurement boundaries. No production algorithm or dependency changes.

The frozen plan selects nine previously used development documents: four original modern
scientific PDFs, one complete modern business PDF with two statement regions, two historical
scans and two degraded scans. Eighteen original input files (documents and independent reference
files) are hash-pinned through their original manifests. This is a development comparison,
not a held-out acceptance set. Original scans are now in identical-byte lossless derivative
PDF containers with explicit recipes, output hashes and pixel-identity checks for paired OCR.
The original frozen plan and scientific receipts remain unchanged; later tracks are linked below.

## Execution status

| Scope | Current status |
| --- | --- |
| Four original scientific PDFs, native text/order | Fresh paired execution complete on current DocWeave e557c43 and Unstructured0.27.28 fast |
| Modern business/scientific tables | [Current whole-original run](../pdf-interim-tables/README.md):59 issuer rows/171 scientific anchors per profile, all10 rows agree21/25 |
| Historical scans | [Paired OCR](../pdf-interim-scans/README.md): two original pages/two fixed models, all outcomes retained |
| Degraded scans | Same paired OCR track: two original pages/two fixed models; three of four total sources have XML-sequence-only references |
| Images | [Independent Poppler audit](../pdf-interim-defaults/README.md):33 source-raster placements agree on9 documents; semantic regions/captions/payload quality unmeasured |
| Documented defaults/auto | Separate default APIs executed on9 whole documents; all18 rows agree21/25; dependencies/routing differ |
| Runtime and memory | [Native-text performance](../pdf-interim-performance/README.md):9 successful forks,360 API samples; per-document latency and whole-process RSS retained |
| Held-out acceptance | Not started; unseen selection, labels and configurations must be frozen before extraction |

Default settings and explicitly configured extraction remain separate tracks. Every configured
profile runs uniformly on all selected sources; no per-source best choice or aggregate winner.
Python executes Unstructured only. DocWeave extraction, scoring and cross-runtime verification
use JVM code. Existing scoring rules, labels, failure denominators and old evidence stay intact.

## Fresh scientific results

All four complete original PDFs were re-extracted in both libraries. DocWeave has nine explicit
profiles; Unstructured has one native fast profile, making40 case/profile rows. All40 numeric/hash
rows agree on actual Java21/25. All36 previous DocWeave rows and all28 earlier paired rows are
unchanged, including candidate text hashes. Each profile retains28 paragraphs and111 order pairs.
All upstream extractions succeed. Complete missing/duplicate/pair results remain in the reports.

| Source | Reference paragraphs | DocWeave default lines | DocWeave source soft-hyphens | DocWeave explicit logical assembly | Unstructured fast |
| --- | ---: | ---: | ---: | ---: | ---: |
| PLOS0324609 |9|1|6|7|0|
| PLOS0330904 |12|2|10|12|2|
| eLife90049v1 |4|0|0|0|0|
| PLOS0360958 |3|0|0|0|0|

Assembly preserves21/36 and66/66 selected order pairs on the first two documents; the other
two remain0/6 and0/3. Upstream fast preserves0/36,1/66,0/6,0/3. These strict complete-paragraph
span measurements do not measure full-document text recall, table/image quality, visual
paragraph boundaries or overall PDF accuracy. All sources were previously used in development.

The measured runtime stays Unstructured0.27.28. Fresh official metadata detected0.27.29;
its released PDF source hash is identical and its only additional commit is the already reviewed
legacy XLS fix. See the [separate release review](../upstream-release-0.27.29/README.md).
Source review does not relabel the executed runtime or silently upgrade dependencies/goldens.

## Reproduction

Use the existing verified scientific source cache and pinned installed upstream runtime.
No dependencies or models are automatically installed. Choose fresh output paths/directories.
Run the following for each JDK21/25, setting JAVA_HOME/PATH to that actual JDK:

```sh
mvn -B -ntp -o -Dmaven.repo.local=/tmp/docweave-m2 \
  -pl docweave-validation -am test \
  -Dtest=PdfScientificReadingOrderTest,StandaloneSourceCompatibilityTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Ddocweave.pdf.scientific.reading.cache="$SCIENTIFIC_SOURCE_CACHE" \
  -Ddocweave.pdf.scientific.reading.manifest=benchmarks/review/pdf-scientific-paragraph-assembly/manifest.json \
  -Ddocweave.pdf.scientific.reading.report="$NEW_DOCWEAVE_REPORT"
```

The nine-profile extraction manifest excludes the separately executed upstream profile.
Capture upstream with the ten-profile scientific-manifest.json in this directory:

```sh
MPLCONFIGDIR=/tmp/docweave-upstream-mpl HF_HUB_OFFLINE=1 TRANSFORMERS_OFFLINE=1 \
  "$PINNED_UNSTRUCTURED_PYTHON" benchmarks/tools/capture_unstructured_reading.py \
  benchmarks/review/pdf-interim-comparison/scientific-manifest.json \
  "$SCIENTIFIC_SOURCE_CACHE" "$NEW_UPSTREAM_CAPTURE_DIRECTORY"
```

Compile benchmarks/tools/ScoreScientificNativeComparison.java at release21 against the
current validation test/runtime classpath; put current reactor target/classes and validation
target/test-classes before dependencies. Run dev.docweave.validation.ScoreScientificNativeComparison
with the ten-profile manifest, original source cache, upstream captures, fresh DocWeave report
and NEW combined report. Repeat JVM scoring on both actual JDKs. The existing standalone test
compiles the launcher and runs owned failure/missing/duplicate/source-identity controls.

Run ../pdf-scientific-reading/VerifyReports.java with the ten-profile manifest, combined21,
combined25, literal dash and NEW receipt. It requires complete40-row coverage, paired equality
and all original denominators. VerifyInterimEvidence.java then takes repository root, raw
capture directory, installed upstream pdf.py and NEW receipt; it verifies original manifest
hashes, all prior rows, runtime/source pins and raw capture identities. Its report paths are
fixed to this evidence directory, so use a separate checkout/copy for reproduction rather than
overwriting committed historical reports. Strict private Javadocs pass on both actual JDKs.

upstream-runtime.json records installed package versions; receipt.json binds its exact raw
runtime inventory, capture hashes, runner and installed PDF source hash. Raw publisher text,
originals, models and captures remain outside Git. No production API/default/dependency changes.

## Remaining comparisons and measurement boundaries

Current modern table inference, historical OCR and degraded OCR executed in that order, with
configurations/model hashes frozen before execution. Keep alternative settings as separate
uniform experiments, never a best-of score.
Use source-page/geometry association independent of recognized text and preserve extras.
Publisher table HTML supplies logical span/text or ordered row labels only where already
reviewed; it is not physical PDF border/box ground truth. PAGE XML without declared reading
order cannot establish logical CER/WER; retain XML-sequence distances as qualified diagnostics.

For images, the completed independent source-raster audit and rendered detected figure crops
need separate metrics. Semantic figure regions/captions/payloads remain unlabelled. Native-text
performance has fixed host/thread/CPU/heap policies, separate first-pass/warm samples and full
process RSS; OCR/model performance, including external engine processes, is not measured.
Confidence calibration requires independent reviewed labels.
A later acceptance round must use unseen sources selected independently of either output.

Earlier scientific-slice validation: five scientific/standalone controls pass on each actual JDK21/25;
three reviewed upstream identity/freshness controls also pass on each JDK. Existing owned
scorer controls retain extraction-failure denominators and reject changed sources, duplicate
or missing observations and failed captures with successful text. The receipt verifier and
strict private doclint run on both JDKs. No production code or dependency changed, so the
previous full1,026-test baseline applied at that checkpoint. Later paired tracks include new
owned failure/parser/default controls; their final expanded verification is recorded in the
linked final receipt. Captured native OCR is not presented as rerun during final rescoring.
