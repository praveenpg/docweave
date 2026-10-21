# Current whole-original paired table comparison

The next interim slice runs four whole original modern PDFs and retains all five previously
labelled regions: two issuer statement pages and three publisher scientific tables. The fourth
scientific text document has no independent table labels; no table score is manufactured for it.
These sources have already been used in development and are not a held-out acceptance set.

Both pipelines run one fixed explicit configuration on every selected document, without
reference pages/ROIs/transcriptions as extraction input. DocWeave uses source-tagged precedence,
segmented native rows, source-soft-hyphen/ISO-date joins and native-first model fallback at150DPI.
Unstructured0.27.28 uses public hi_res, table inference, YOLOX, its pinned default table weights,
English Tesseract and300DPI. Existing local assets are verified before execution; no downloads,
source patches, per-case best settings or inference-result repairs occur. These are distinct
configured pipelines, not stock defaults, shared-weight inference or equal preprocessing.

| Independent region | Reference kind | DocWeave exact | Unstructured exact |
| --- | --- | ---: | ---: |
| Amazon operations | Ordered nonblank issuer HTML rows |27/27|11/27|
| Amazon balance sheet | Ordered nonblank issuer HTML rows |32/32|0/32|
| PLOS0324609 | Logical row/column/span/text anchors |136/136|72/136|
| PLOS0330904 | Logical row/column/span/text anchors |15/15|0/15|
| eLife90049v1 appendix | Logical row/column/span/text anchors |20/20|0/20|

All four whole-document upstream and native extractions succeed. A zero exact score is not an
extraction failure: it can reflect no qualifying associated table, different topology or text.
The complete reports retain these distinctions, missing/extra keys/rows, unassociated page
tables and invalid predicted HTML structures. Unassociated objects are not automatically false
positives when the page lacks complete object labels. Issuer HTML rows do not label physical PDF
cell borders; publisher logical spans are not physical border/box ground truth. No overall
library ranking, general PDF accuracy, performance advantage or completion claim follows.

## Scoring and provenance

Association is unchanged maximum parent-box IoU>=0.5, stable first tie, using only original
page/ROI geometry after extraction. Upstream PixelSpace frames are independently scaled by
actual layout_width/layout_height into original displayed PDF units; nominal DPI is not used
as a substitute for the actual raster dimensions. HTML is parsed on the JVM into only its
explicit row/column/span anchors; covered slots are never fabricated. Original cell characters,
physical hyphens, Unicode and punctuation stay unchanged. Existing exact publisher/issuer
scorers apply the original normalization rules to both outputs.

Invalid/missing upstream HTML remains a detected table with zero valid structure keys and an
explicit invalid-structure marker; it is not dropped to improve coverage. Failed whole-document
captures must not contain partial successful elements and keep full reference denominators.
The native captures/source frames and all ten comparable score rows agree on actual Java21/25.
Runtime strings and original raw capture hashes are retained separately, not compared as numeric
outcomes. Original source/reference manifests, selected regions, models and exact policies are
frozen in manifest.json. The completed extraction used executed-manifest.json; its descriptive
minimum_table_cells key was a naming error for the existing constructor's maxTableCells=1024
resource bound. The corrected manifest retains that exact executed hash, source/model/policy
identities and value. No extraction setting, label or result was changed by this metadata fix.

Python executes Unstructured only. JVM code captures DocWeave, converts output and scores both
libraries. The upstream runner has bounded per-document child processes and records explicit
failure/timeout outcomes before moving to the next fixed input. Captures, publisher text, raw
logs, PDFs and model binaries stay outside Git. Runtime inventories and numeric/hash evidence
are committed; the older20-image table benchmark and all previous reports remain unchanged.

## Reproduction

1. Verify the frozen original PDFs and issuer/JATS references against their original manifests.
   Build one local cache with the exact aliases listed in cases.tsv and source/reference hashes.
   The issuer SEC HTML initially returned403; one identified-client retry retrieved the exact
   frozen bytes (SHA25648446f28...); reference text and row hashes were not substituted.
2. Compile benchmarks/tools/CaptureNativeModelTables.java at release21 against current reactor
   classes and the validation runtime/test classpath. Keep current target/classes ahead of any
   old locally installed DocWeave artifacts. Run on actual21/25 with tagged.first,
   segmented.rows and source.soft.hyphens=true, native-first, cases.tsv, fresh output directories
   and the frozen detector/structure paths/hashes. Both process the complete original documents.
3. Run benchmarks/tools/capture_unstructured_tables.py with executed-manifest.json, the original
   PDF cache, preinstalled upstream-runtime-assets root and a fresh raw output directory.
   Put the pinned Tesseract on PATH; set TESSDATA_PREFIX, LD_LIBRARY_PATH where needed,
   HF_HOME to the populated local cache, MPLCONFIGDIR, OMP_NUM_THREADS=4 and OMP_THREAD_LIMIT=1.
   Missing resources fail before execution; no models or packages install automatically.
4. Run PdfInterimTableComparisonTest with docweave.pdf.interim.tables.cache/.native/.upstream/
   .report properties on each actual JDK, choosing a fresh report path and that JDK's native
   captures. It revalidates original bytes, aliases, independent labels, runtime/model policies,
   executed manifest identity and original PDF coordinate frames before every score.
5. Run VerifyTableReports.java with manifest, reports21/25, native21/25 directories and a fresh
   receipt. It requires all ten rows,59 issuer-row and171 publisher-anchor references per profile,
   equal complete native table/source metadata and equal metrics. Only runtime/hash identity
   fields are excluded from numeric equality; they remain preserved in the reports.

Owned controls cover merged/overlapping/out-of-row/oversized spans, Unicode/entity/hyphen text,
actual nonuniform raster scaling, duplicate/invalid table retention and failed-output full
denominators. Existing publisher and issuer scoring tests remain intact; strict private docs
also cover the touched reference helpers. This adds validation code, not production algorithms.
