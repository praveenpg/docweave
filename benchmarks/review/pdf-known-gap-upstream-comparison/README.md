# Known PDF gaps compared with Unstructured

This compares the specific known gaps, retaining their original reference indices and
complete denominators. It adds no extraction changes or revised scores. Existing captures
were freshly checked/scored on Java21; existing expensive OCR/model inference was not repeated.
One new three-page authored image contract control executed the installed upstream crop helper.

## Version and configuration boundary

Measured upstream runtime: **Unstructured0.27.28**, inference1.6.13 with the previously pinned
models/dependencies. Latest official release checked online is
[0.27.33](https://github.com/Unstructured-IO/unstructured/releases/tag/0.27.33), reviewed in
[the separate release/main record](../upstream-main-20261022/README.md).
These are distinct facts: this comparison does **not** establish current0.27.33 output or
claim that a shared symptom has the same internal cause. It covers OSS, not hosted services.

DocWeave observations use the accepted current opt-in section-row/inline-script/wide-gap
configurations, not the rejected PAGE_GUTTERS candidate or a per-source best-of selection.
The paired sources are known development data, not held-out acceptance.

## Business tables: the same missing references

Original NVIDIA eleven-page PDF, all110 issuer HTML rows per profile, unchanged strict
scorer and geometric association. Upstream profiles are both hi_res/YOLOX/300DPI:
its default PubTables checkpoint and separately configured shared-source v1.1-All weights.
DocWeave uses its existing150DPI native-text/provider profiles. Weight sharing does not
make preprocessing, conversion or postprocessing identical.

| Question | Upstream default table checkpoint | Explicit shared-source table checkpoint |
| --- | --- | --- |
| Three structural rows missing from DocWeave YOLOX tables | All3 also absent as exact associated table rows | All3 also absent |
| Those three reference strings anywhere in public document text | All3 present as exact ASCII-whitespace-normalized substrings | All3 present |
| Twelve DocWeave YOLOX title misses | All12 also miss exact table-row presence | All12 also miss |
| Six DocWeave YOLOX split-date-row misses | All6 also miss exact table-row presence | All6 also miss |
| Twenty-one DocWeave YOLOX parenthesis-spacing misses | All21 miss strict presence;16 explained by the narrow spacing diagnostic | All21 miss strict presence;17 explained by spacing |
| Forty-two DocWeave TATR absent/partial-proposal misses |18 appear as exact rows; another4 spacing-only |24 appear as exact rows; another5 spacing-only |

For DocWeave TATR's remaining classes, all9 title references also miss both upstream table
outputs; its8 split-date references yield0 exact upstream default matches and2 explicit
shared-weight matches. Of its15 parenthesis-spacing cases, both upstream profiles explain12
by the same narrow diagnostic; all15 still miss strict exact presence.

The heading/section/numeric row at issue is therefore also a table-membership symptom in
these upstream outputs. Public substring presence does not establish a unique occurrence,
correct page/location, cell boundaries or complete table structure. It avoids calling every
table-row miss total document text loss. Shared failure indices do not prove identical causes.
TATR proposal coverage is **not uniformly shared**: the tested upstream YOLOX pipeline covers
many references missing under the DocWeave TATR profile. Detector/checkpoint choice matters.

Strict complete-row presence remains DocWeave YOLOX68/110, DocWeave TATR36/110,
upstream default42/110 and upstream explicit51/110. Strict ordinal remains0/110 for all.
No additional normalized accuracy score is introduced. The narrow parenthesis diagnostic
consumes exact matches first and only then unmatched one-to-one numeric spacing matches.
It cannot explain other remaining mismatches or certify cell topology.

## Scientific prose: same reference paragraphs, not necessarily same cause

Complete selected publisher abstract/body prose: three original PDF/XML pairs,143 paragraph
references and4942 order pairs. Current explicit DocWeave logical assembly plus nearby-small-glyph
handling uniquely matches113; upstream fast uniquely matches57. All57 are a subset of113:

| Source | References | DocWeave unique | Upstream fast unique | Neither uniquely matches |
| --- | ---: | ---: | ---: | ---: |
| PLOS0324609 |33|28|3|5|
| PLOS0330904 |93|84|54|9|
| eLife90049 |17|1|0|16|
| Total |143|113|57|30|

The remaining30 references are shared unmatched/ambiguous exact paragraphs, including nine
repeated/ambiguous references in the second PLOS paper. Their exact reference indices and
hash agreement are retained. Paragraph-span failure is not proof every word was lost or
that both tools interleave the same sidebar. Source/XML wording, spaces and physical hyphens
remain unmodified. This is not hi_res comparison or independently reviewed visual reading
order; the proposed first-page/sidebar oracle is still needed. Current native-line compound
profile59/143 versus upstream fast57/143 is a different configuration from the113 result.

## Historical/degraded OCR: text comparison, not invented line geometry

Current accepted DocWeave wide-gap grouping has the same recognized text hashes as the
paired native captures, so their public-text comparison remains applicable. All8 source/model
pairs, shared PDF container/model identities and full references are checked again by the
existing JVM scorer. Source resolution, engine and Fraktur byte-identical language alias
bindings stay as documented in [the paired scan protocol](../pdf-interim-scans/README.md).

| Source | Model | DocWeave edits | Upstream ocr_only edits | Reference code points / qualification |
| --- | --- | ---: | ---: | --- |
|1835|German|104|107|1059, declared logical order|
|1835|Fraktur|51|50|1059, declared logical order|
|1879|German|291|300|3049, XML-sequence diagnostic|
|1879|Fraktur|334|334|3049, XML-sequence diagnostic|
|1778|German|419|413|948, XML-sequence diagnostic|
|1778|Fraktur|371|374|948, XML-sequence diagnostic|
|1780|German|103|108|322, XML-sequence diagnostic|
|1780|Fraktur|82|85|322, XML-sequence diagnostic|

Both pipelines retain historical/degraded text errors, with mixed small differences. The
public upstream ocr_only output is partitioned text elements; these are not independently
corresponding OCR line boxes. Therefore this comparison cannot say it has exactly the same
missing page-number, narrow-gutter association, over-split line or unreferenced stamp counts.
The rejected PAGE_GUTTERS rule was never upstream behavior. Do not compare DocWeave line IoU
counts with upstream paragraph boxes or label XML-sequence error as logical whole-page CER.

The upstream [strategy documentation](https://docs.unstructured.io/open-source/core-functionality/partitioning#partition_pdf)
also acknowledges multi-column ordering difficulty for hi_res and recommends ocr_only in
that situation; that is documented guidance, not a measurement on these pages.

## Images: different output contracts, confirmed by a controlled run

One new owned PDF has three pages, each reusing the same opaque red source raster: an
isolated transparency group at alpha0, a group at alpha0.5, and a half-width rectangular
clip. DocWeave emits3 images with identical red PNG payloads, exposing group/clip metadata.
The actual upstream `save_elements` helper renders the same PDF at72DPI and crops3 explicit
known40x40 regions. Java ImageIO checks the resulting JPEGs:

| Paint control | Original DocWeave raster | Actual upstream rendered crop |
| --- | --- | --- |
|Group alpha0|Red|White page/background|
|Group alpha0.5|Red|Pink composite|
|Half-width clip|Red|Red on admitted half, white elsewhere|

Thus the crop path already reflects rendered group opacity and rectangular clipping in
these controls. DocWeave's source pixels intentionally do not; source metadata is not a
rendered-appearance substitute. This is helper execution with authored known regions, **not
model detection**, full partition_pdf hi_res extraction, source-raster count parity, general
blend/soft-mask/occlusion correctness or a test of the11 curved magazine clips. Upstream
region crops do not offer the same per-source-raster clip/group contract being developed
here. The original 94-placement magazine audit cannot be scored as94 upstream figure crops.

## Reproduction, guards and remaining acceptance

`AuditUpstreamNvidiaGaps.java.txt` is the existing pinned scorer launcher with additive
index-only spacing/public-text diagnostics. It retains source, reference, scorer, runtime,
runner and raw capture identity checks. Compile it and the current AuditStatementRowPresence
into a fresh scratch directory and run from the frozen NVIDIA scorer workspace, passing
manifest, source cache, raw capture directory and fresh output. All pre-existing fields in
both newly scored reports are required to equal the historical default/shared results.

Use ScoreScientificReadingCapture with the existing full-body upstream manifest/original
cache/raw captures; its complete three-source result equals the historical report. Use
PdfInterimScanComparisonTest with its original shared-container/native/upstream paths; all16
results equal the historical paired report. Neither operation invokes Python or models.

Compile/run CompareKnownGapReports with repository root, a scratch directory containing
business-default.json/business-shared.json/scientific-upstream.json/scans.json, and a fresh
report. It checks every classified missing row once, complete populations, every scientific
reference hash/unique count, all8 current recognized-text identities and unchanged historical
scores. Relative-path input hashes are recorded in gaps-java21.json. Numeric output only;
original sources, recognized text, PDFs, model binaries and JPEGs remain outside Git.

For the new image control, compile/run CreateImageContractControl into a fresh directory;
execute capture_unstructured_image_contract.py using the already installed pinned upstream
Python environment, then compile/run CheckImageContract there. The Python script executes
Unstructured only; authoring and all scoring/pixel checks use Java. No new dependencies,
models, language downloads, normalization rules or source-specific parameter grids were added.

Independent visual/sidebar labels, current0.27.33 execution, comparable OCR line geometry,
source-image occurrence equivalence and held-out acceptance remain open. The packaged native
table workload already passes; independent operational review is not established by these
accuracy diagnostics. Full production verification was not repeated for this evidence-only
comparison. No release was performed.
