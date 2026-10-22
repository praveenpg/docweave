# PDF comparison: DocWeave and Unstructured

This working matrix records available functionality and fixed measurements. PDF acceptance
remains open: ambiguous headers, degraded recognition, broader fonts/reading order, calibrated
confidence and independent held-out coverage are unresolved. It is not a completion certificate
or an overall accuracy ranking. The final matrix will need refreshed paired measurements when
those acceptance gates close. See the [progress ledger](pdf-progress.md).

## Versions and scope

| Track | Exact scope |
| --- | --- |
| DocWeave measured comparison baseline | Commit `e557c43f6715cdb9b03d045b512b1c6d617e00b9`, Java21 baseline, verified on actual21/25; later additive strategy presets are separately tested, not a rerun of this corpus |
| Later native business correction | Commit `58b465f`, actual Java21/25; three complete known-source PDFs, fixed publisher row references and all ten native policies. Separate from the earlier paired upstream corpus |
| Later managed detector and caption work | Through `75a28e1`, Java21; explicit managed YOLOX, paired physical-scan regression, native alpha filtering and numbered figure-title cues. Full verify1,160 tests passes; earlier paired measurements keep their original versions |
| Public Maven release | `0.1.0-beta.6`, source `fedf209cba9ded60d7ae715e2008ead1a0176274`; later paragraph page fragments, logical assembly, single-column OCR and strategy presets remain unreleased |
| Latest reviewed Unstructured OSS | `0.27.33`, commit `97edfcb538b04abc6c0133e5f28f3be7bc111f81`; measured runtime remains0.27.28 |
| Executed upstream PDF comparison | Unstructured0.27.28, inference1.6.13, transformers4.57.6, CPU PyTorch2.10.0, Python3.12.15; complete dependency/model hashes linked below |
| Historical generic reference outputs | Unstructured0.27.27 at `347fbbfd3e84e228017f125e5c5dbdc326ef7de2`; these goldens are not silently repinned by current source review |

Hosted Unstructured services, commercial model quality, connectors and non-PDF formats are
outside this comparison. The historical [identity manifest](../benchmarks/review/pdf-comparison-20261019/manifest.json)
pins earlier implementation/evidence snapshots. The current [interim plan](../benchmarks/review/pdf-interim-comparison/README.md) fixes nine development documents and records completed versus pending tracks. Reviewing current source and executing reference
outputs are separate activities; see [upstream tracking](upstream-tracking.md).

## Specific remaining gaps: index-level follow-up

The [known-gap comparison](../benchmarks/review/pdf-known-gap-upstream-comparison/README.md)
checks the actual missing references using existing complete Unstructured0.27.28 captures,
fresh JVM scoring and current accepted DocWeave observations. No broad accuracy ranking or
claim about executed0.27.33 output follows from the source/release review.

- All three structural NVIDIA rows missing from DocWeave YOLOX tables also miss both tested
  upstream table profiles. Their reference strings occur elsewhere in upstream public text;
  this is not evidence of total document text loss. Title/split-date reference misses are
  also shared under these configurations. Conversely, upstream YOLOX covers18(default) or
  24(explicit shared weights) of the42 rows missing under DocWeave's TATR proposal profile.
- All57 upstream-fast unique scientific paragraphs are included in DocWeave's explicit113/143
  result. The same30 references remain unmatched/ambiguous in both; nine are repeated source
  references. This establishes shared exact-span symptoms, not identical sidebar ordering causes.
- Configured OCR public-text results retain mixed historical/degraded errors in both tools.
  Current DocWeave text hashes equal the paired captures. Upstream text blocks are not equivalent
  line boxes, so the same gutter/line-IoU failure counts cannot honestly be asserted.
- A new three-page known-region image helper control confirms upstream rendered crops reflect
  group alpha0/0.5 and half-width clipping. DocWeave original raster PNGs remain identical and
  expose source paint metadata separately. This is not a detector comparison or magazine crop oracle.

The packet retains every reference index, full population, source/runtime identities and all
strict scores. It adds comparison evidence, not production extraction changes.

## Functionality matrix

| PDF capability | DocWeave development snapshot | Unstructured0.27.28 public path | Comparison boundary |
| --- | --- | --- | --- |
| Runtime/integration | Maven Java21 baseline; usable as explicitly configured Spring beans | Python library | Historical paired21/25 runs retained; new Java25 verification deferred until PDF completion. Scoped native-text latency/RSS below is not an OCR/model or cost benchmark |
| Native text | PDFBox extraction; explicit paragraph/column/font, quarter-turn and zero-alpha paint policies | `fast` native-text strategy | Alpha selection is not rendered visibility or redaction; general text/category/order parity remains unproven |
| OCR selection | Caller providers; additive FAST/AUTO/HI_RES/OCR_ONLY presets plus textless/mixed-region/full-page policies | `auto`, `hi_res`, `ocr_only` strategies | DocWeave presets are unreleased; default routing, capability and dependency contracts differ |
| OCR backends/languages | Generic provider interfaces; optional external Tesseract adapter and caller-installed language models | Configured OCR agent and language packs | Packaged backend sets differ; no multilingual accuracy ranking |
| Native table strategies | Explicit ruled/merged, partial-rule, aligned, segmented-row and shaded-row strategies; optional compact padding and whole-script grouping with bounded glyph ownership | Public structured-table path uses hi-res inference | Strategy availability does not establish borderless/general table fidelity |
| Source-authored tables | Explicit single-page TH/TD/spans using validated page MCIDs; bounded attribute classes, disjoint captions, optional logical cell envelopes and native multiline cells | Source-tagged parity not measured; public table flow reviewed as model inference | Untagged, mixed-page or ambiguous structures can fall back whole |
| Model-backed scanned tables | Optional managed TATR or explicit YOLOX detector with TATR structure; page/actual-word/cell OCR routes | Hi-res table inference with optional HTML structure | Models remain caller-supplied. Detector coverage and row/header fidelity differ; fixed scan tradeoffs below do not justify an automatic winner |
| Table HTML/spans | Escaped HTML, rectangular spans, optional cell geometry; declared provider prefixes reach structural chunking | Table text and optional `text_as_html` | Owned native/word/cell controls verify repeated headers and body preservation; general header/text fidelity remains open |
| Table continuation | Explicit adjacent-page links, including declared single-header and explicit bounded header-prefix provider tables | No new paired continuation measurement | Links preserve page-local tables; they do not reconstruct arbitrary multi-page statements |
| Images | Original raster placements; optional bounded PNG/base64; solid stencil paint/alpha and labelled pattern-mask samples | Hi-res detected Image/Table region crops to files or payload | Placed raster extraction and detected rendered-region crops are different contracts |
| Native figure captions | Opt-in English numbered-label/image adjacency, Unicode horizontal separators and capitalized bare titles | Generic hi-res Caption model class; no paired complete-caption score on these sources | Fixed four-original development results below; no automatic description generation or broad caption-association precision claim |
| Vector illustrations | No standalone vector-illustration interpretation/caption generation in the initial image model | Detected image-region crop path; vector semantics not measured | No figure-understanding or caption-quality comparison |
| Reading order | Explicit geometric/native/OCR ordering policies; semantic order remains partial | Model/native/OCR strategy-dependent output | No broad complex-layout reading-order benchmark |
| Coordinates | Source/display transforms, crop/UserUnit/quarter-turn controls; optional table cells | Element coordinate metadata | Units/frames and physical bounds must be compared explicitly |
| Interactive forms | Outside initial element model | Optional `extract_forms` path in pinned API | Form-key/value extraction not evaluated here |
| Chunking | Core/table chunking with HTML budgets and original-element provenance | Partition/chunking integration | Opt-in shared contextual enrichment is implemented in the snapshot; retrieval evaluation remains open and generic goldens retain their historical pin |
| OCR image controls | Explicit page/cell segmentation, cell-edge policy, typed binarization and opt-in page whitepoint normalization | Engine/agent configuration is a different surface | No claim that preprocessing choices are interchangeable or universally better |
| Limits/diagnostics | Explicit page/glyph/operator/raster/table/work/process budgets and typed failures | Different dependency/exception contracts | Limits are API policies, not a measured memory/security/performance ranking |
| Confidence | No calibrated probability of correct extraction | No calibration measured in this study | Raw model/OCR scores cannot establish accuracy percentages |

Upstream strategy/table/image/form statements are based on the pinned
[`partition_pdf` source](https://github.com/Unstructured-IO/unstructured/blob/1b02b4d189c260ed9b1aca29583203e233d853cb/unstructured/partition/pdf.py)
and [official partitioning documentation](https://docs.unstructured.io/open-source/core-functionality/partitioning).
DocWeave's exact contracts and exceptions are in [PDF support](pdf-support.md),
[OCR configuration](ocr-tesseract.md) and the public interfaces/Javadocs.

## Executed paired table comparison

All rows below use the same20 original scientific table crops, embedded whole in PDFs,
with1380 published logical anchors. They are upstream training examples, not held-out
acceptance data. Complete topology requires exactly one table with every expected
row/column/span key; it does not imply correct text or physical cell borders.

| Evaluated profile | Inputs with a nonempty table /20 | Complete single-table topology /20 | Entirely exact topology and text /20 |
| --- | --- | --- | --- |
| Historical DocWeave explicit merge policy plus cell OCR | 20 | 14 | 0 |
| Historical DocWeave detector/structure plus page words | 19 | 14 | 0 |
| Upstream postprocessing of identical decoded objects | 20 | 8 | 0 |
| Upstream recognizer using shared source weights | 20 | 8 | 0 |
| Public Unstructured hi-res with pinned default model identities | 11 | 0 | 0 |

These are the retained [fixed20 results](../benchmarks/review/pdf-upstream-all20/README.md),
not a new execution of today's entire source tree. The historical Java capture/merge checkpoint
is `bb15f2b5a23844acc28531ba8bcbc6a92bcc5464`; the default capture and explicit-policy score
paths are distinguished in that evidence. Public upstream produced14 nonempty tables across11
inputs, including duplicate-table cases; extras remain counted. That profile explicitly uses
Tesseract and pinned local default model identities, not every stock runtime setting. The
shared-weight path reduces model differences but retains preprocessing/parser/OCR differences.

DocWeave has better measured topology under these configurations, not a demonstrated universal
advantage. One retained counterexample has54 matching keys for the historical explicit Java
profile versus63 for shared-weight upstream on `PMC4003957_018_00.png`. A later explicit
[rule-preferred Java policy](../benchmarks/review/pdf-ruled-preference/README.md), commit
`cbcd298a99ea541e5970511afb4f6fa4bf35261d`, reaches15/20 complete topologies and410 exact
positional/span/text cells, retaining zero wholly exact text tables. That later policy is a
separate Java regression result, not a newly paired upstream execution. None of these counts
is a global PDF accuracy or work-completion percentage.

Model revisions, weight hashes, source hashes, selected settings and the complete Python lock
are in the [runtime/model manifest](../benchmarks/review/pdf-upstream-table/manifest.json),
[dependency lock](../benchmarks/review/pdf-upstream-table/requirements.freeze.txt) and
[full20 comparison](../benchmarks/review/pdf-upstream-all20/comparison.json).

## Current whole-original table comparison

The [current paired table run](../benchmarks/review/pdf-interim-tables/README.md) processes four
complete modern PDFs with one fixed explicit configuration per library. All extractions succeed.
DocWeave uses source/native precedence and native-first model fallback at150DPI; upstream uses
public hi_res with its pinned default table weights, YOLOX and English Tesseract at300DPI.
These are different configured pipelines, not stock defaults or equal preprocessing.

| Independent labelled region | DocWeave exact | Unstructured exact | Reference kind |
| --- | ---: | ---: | --- |
| Amazon operations |27/27|11/27|Ordered issuer rows|
| Amazon balance sheet |32/32|0/32|Ordered issuer rows|
| Original recent PLOS table |136/136|72/136|Logical span/text anchors|
| Second recent PLOS table |15/15|0/15|Logical span/text anchors|
| eLife90049 body-span table |20/20|0/20|Logical span/text anchors|

The three table results use explicit source/native precedence, segmented rows and source-soft-
hyphen/ISO-date joining. All21 other fixed regions stay unchanged on both JDKs in that
[source replay](../benchmarks/review/pdf-source-soft-hyphens/README.md). Logical publisher
labels are not physical cell-border ground truth. These small development outcomes do not
establish broader modern-business/scientific readiness. Current paired scoring preserves original
labels/normalization, geometry-only association and all missing/extra/failure denominators.
All ten score rows and complete native table/source metadata agree on actual Java21/25.

## Historical and degraded OCR

The [paired OCR run](../benchmarks/review/pdf-interim-scans/README.md) uses four identical-byte
lossless PDF containers, the same Tesseract executable and two fixed German model bindings.
Native AUTO/ENGINE_DEFAULT and public upstream ocr_only retain their different preprocessing/
grouping paths. Fraktur uses byte-identical pinned weights through an explicit supported upstream
frk alias; silent English fallback is rejected. All eight source/model extractions succeed in
both libraries and all sixteen outcomes agree when scored on actual Java21/25.

| Source | Model | DocWeave character edits | Unstructured character edits | Reference/order qualification |
| --- | --- | ---: | ---: | --- |
| 1835 Arnim/Goethe |German|104|107|1059 code points; declared logical order|
| 1835 Arnim/Goethe |Fraktur|51|50|1059 code points; declared logical order|
| 1879 Bebel |German|291|300|3049 code points; XML sequence only|
| 1879 Bebel |Fraktur|334|334|3049 code points; XML sequence only|
| 1778 Bürger, degraded |German|419|413|948 code points; XML sequence only|
| 1778 Bürger, degraded |Fraktur|371|374|948 code points; XML sequence only|
| 1780 Besuch, degraded |German|103|108|322 code points; XML sequence only|
| 1780 Besuch, degraded |Fraktur|82|85|322 code points; XML sequence only|

Lower is better within a source/model. Only one source declares logical line order; the other
distances mix recognition and XML ordering and are not logical reading-error rates. No source/
model best-of selection or aggregate winner is reported. All prior native line/raster controls
remain unchanged. Training overlap and independent held-out calibration remain unknown.

A [separate source-image review of1879 order](../benchmarks/review/pdf-reviewed-historical-order/README.md)
now supplies a complete50-line order without changing its XML or the table above. Under that
explicitly separate reference, DocWeave/Unstructured0.27.28 character edits are9/20 for German
and55/57 for Fraktur, each over3,049 code points. Existing prediction hashes and all16 historical
paired results remain unchanged. This qualifies an observed-source measurement; it is not a new
extraction improvement, blind evaluation or overall winner. Other undeclared orders stay qualified.

The [scan mode study](../benchmarks/review/pdf-scan-segmentation/README.md) finds no uniformly
better segmentation. The [threshold study](../benchmarks/review/pdf-scan-thresholding/README.md)
finds a scoped1835 improvement with Sauvola (Fraktur51 to44 character edits over1059 code
points), but worse results on degraded pages. Defaults remain unchanged. Undeclared reference
line order, unlabelled page artifacts and unknown training overlap remain qualified explicitly.

## Later native business development results

A later DocWeave-only business experiment uses a new issuer and three complete original
statement pages, with114 publisher HTML row references fixed before first extraction. The new
explicit shaded-row strategy matches77/114 rows (40/40 operations,0/37 balance,37/37 cash flows),
versus0/114 under each earlier native policy. The balance failure remains in the denominator.
[Paired evidence](../benchmarks/review/pdf-unseen-business-apple-2026/README.md) preserves every
baseline observation. Unstructured was not rerun on this source; no relative ranking follows.

Subsequent compact-padding, whole-script, tall-cell-paint and clipped-header corrections
retain that historical result separately. Under one uniform explicit native policy,
`RULED_AND_SHADED_ROWS_INLINE_SCRIPTS`, the latest paired Java21/25 results are:

| Complete original development PDF | Exact publisher row content | Boundary |
| --- | --- | --- |
| Apple July2026, three statements |114/114| Same issuer/layout family used during development |
| Apple April2026, three statements |113/113| Original frozen-code transfer retained before later development |
| Amazon Q4 2025 release, two statements |59/59| Previously observed other issuer; original14-page document processed |
| Walmart FY26 Q4 complete filing, three statements |0/105| Frozen-code first extraction of another issuer; original59-page publisher PDF, explicit64MiB input budget |

The [final correction evidence](../benchmarks/review/pdf-clipped-header-bounds-v2/README.md)
retains all80 case/profile observations, including unsuccessful older policies, unchanged
references and earlier-source regression checks. Both full builds pass1,098 tests. These
are exact row-content results, not physical cell/span truth or independent held-out accuracy.
No new upstream run or general library ranking is implied; the earlier paired comparison
keeps its original source, provider, strategy and runtime identities.

The subsequent [Walmart native check](../benchmarks/review/pdf-unseen-business-walmart/README.md)
and [source-tagged factory check](../benchmarks/review/pdf-walmart-source-tags/README.md)
retain all failures: neither path recognizes the three selected statements as tables.
Ordinary native text remains. This newly exposed gap qualifies the earlier perfect
development-source matches; source-tag presence alone does not establish supported topology.
OCR/ONNX and Unstructured have not been executed on this source.

A later [explicit source-logical policy](../benchmarks/review/pdf-logical-tagged-tables/README.md)
recovers all three Walmart statements:29/29 operations,40/40 balance-sheet and36/36 cash-flow
rows on both Java21/25. It retains the authored blank rows/columns using original nonempty
text bounds and publishes no cell coordinates. All113 earlier business observations and52
scientific observations remain unchanged; both clean full builds pass1,105 tests. The new
profile has0 exact rows on the other three development PDFs, so this is a complementary
caller-selected source-tag policy, not a uniformly better native policy or automatic routing.
Physical blank-cell geometry and an upstream comparison on Walmart remain unmeasured.

An additional [frozen-code NVIDIA check](../benchmarks/review/pdf-unseen-business-nvidia/README.md)
covers an original untagged11-page release with complete income, balance and cash-flow statements
spanning pages4/5,5/6 and6/7. All12 native/source-tagged fallback configurations have0/110 exact
structured rows, with native text present and no associated tables in the six selected regions.
Both JVMs agree; all124 earlier business observations remain unchanged. This is a newly measured
multi-page table gap. No model/OCR/Unstructured run or relative ranking is implied by this result.

The subsequent [fixed native-first model run](../benchmarks/review/pdf-nvidia-native-model/README.md)
associates3/6 source regions and emits66 observed rows across those associations, but retains
0/110 strict ordinal matches and no complete statement. Actual Java21/25 results agree across
all39 case/configuration records; all36 earlier NVIDIA and124 prior business records remain
unchanged. Model geometry association alone does not establish correct semantic table recovery.
There is still no OCR or Unstructured run on this source, and no production default change.

The later [managed YOLOX pipeline](../benchmarks/review/pdf-managed-yolox/README.md)
makes the alternative detector available through a bounded public provider. Its
cached original-page replay retains all33 stage observations exactly. The earlier
YOLOX proposal experiment associates6/6 labelled regions versus3/6 for TATR, but
strict statement rows remain0/110. A
[native baseline inventory](../benchmarks/review/pdf-model-cell-baselines/README.md)
finds multiline cells in both date headers and wrapped labels/notes; splitting every
two-baseline cell into rows is not justified. No upstream ranking follows.

On the [fixed nine physical-scan cases](../benchmarks/review/pdf-managed-yolox-scans/README.md),
both detectors use the same structure model, OCR and rendering. All20 published
tables remain in the denominator, including the typed OCR-limit case:

| Measurement | TATR | YOLOX |
| --- | ---: | ---: |
| Emitted tables |12|14|
| One-to-one spatial matches, IoU >=0.5 |11/20|12/20|
| Matched tables with equal dimensions |9/20|9/20|
| Equal dimensions and published unique anchor sets |3/20|2/20|

These anchor sets are topology annotations, not an OCR transcription oracle.
Better detector coverage here accompanies a lost historical topology positive;
defaults remain unchanged. These are Java-only detector comparisons, separate
from the earlier paired historical/degraded OCR text measurements.

## Later source-tagged development checks

The newer Java21-only [tagged-table evidence](../benchmarks/review/pdf-tagged-reference-tables/README.md)
retains all74 anchors on seven original source variants (three distinct layouts). Each
of the three source-tagged policies now preserves41/41 labelled header roles, versus
36/41 before caption support. Native-only output retains0/41 roles; topology alone does
not establish semantic headers. These are external validation examples, not business
accuracy or PDF-UA compliance. Unstructured was not run on this set.

On existing business development sources, explicit logical cell envelopes retain
Walmart105/105 and AMD81/120 strict publisher-HTML row matches. All39 AMD nonexact rows
are ASCII-spacing/dash differences in a separate post-hoc diagnostic; they remain
strict mismatches. See [complete results and boundaries](../benchmarks/review/pdf-business-row-presentation/README.md).
A [fixed NVIDIA score-cutoff check](../benchmarks/review/pdf-nvidia-structure-cutoffs/README.md)
retains0/110 at0.5 and0.25; more permissive scores are not a demonstrated repair.
No new upstream comparison or overall ranking follows from these DocWeave-only runs.

A [standalone packaged consumer](../benchmarks/review/pdf-tagged-api-consumer/README.md)
verifies the new source-tagged public API with exactly six runtime JARs on Java21.
Class-defined spans are exercised with an owned source; independent original class-span
accuracy evidence remains open. Java25 checking is deferred until PDF completion.

## Original scientific paragraph comparison

Later DocWeave-only work adds an explicit source-owned inline-script policy. On the same four
development originals, combining it with physical-wrap treatment and logical assembly yields
8/9,12/12,0/4 and3/3 strict paragraph matches. All44 earlier policy rows remain unchanged.
The [paired experiment](../benchmarks/review/pdf-inline-scripts/README.md) preserves all source
identities/denominators and records52 matching case/profile results across Java21/25. This
does not rerun or relabel the upstream runtime, and reused cases are not independent acceptance.

A later [native-only comparison](../benchmarks/review/pdf-scientific-native-comparison/README.md)
executes Unstructured0.27.28 `fast` on the same four original PDFs and scores both libraries
with the unchanged JVM paragraph evaluator. DocWeave source is `50bc8cb`; all six configurations
are retained in the report. These exact text-span counts do not measure general text recall,
visual paragraph segmentation, table fidelity or overall library quality.

| Source | Full reference paragraphs | DocWeave default lines | DocWeave source soft-hyphens | Unstructured fast |
| --- | ---: | ---: | ---: | ---: |
| PLOS0324609 | 9 | 1 | 6 | 0 |
| PLOS0330904 | 12 | 2 | 10 | 2 |
| eLife90049v1 | 4 | 0 | 0 | 0 |
| PLOS0360958 | 3 | 0 | 0 | 0 |

All four upstream extractions succeed. All28 combined DocWeave/upstream profile rows agree
when scored on Java21/25; missing, duplicated and failed paragraphs remain in the28-paragraph/
111-pair denominators per profile. Neither path matches a complete selected paragraph in the
newest source. This is development evidence; broader reading-order acceptance remains open.

## Refreshed interim comparison

The [current scientific run and broader protocol](../benchmarks/review/pdf-interim-comparison/README.md)
re-extract all four original scientific PDFs using DocWeave e557c43 and pinned Unstructured0.27.28.
All40 case/profile rows agree on Java21/25; all36 prior DocWeave rows and all28 earlier paired
rows remain unchanged. Explicit logical assembly adds7/9 and12/12 exact paragraph matches on
the two older PLOS cases, compared with source-soft-hyphen6/9 and10/12; both remaining sources
stay0/4 and0/3. Default lines remain1/9,2/12,0/4,0/3; upstream fast remains0/9,2/12,0/4,0/3.
These are selected-paragraph diagnostics, not document accuracy or overall library rankings.

A later [difference audit](../benchmarks/review/pdf-scientific-text-differences/README.md)
re-extracts the default-lines and explicit-assembly subset on production9fd30c7 and confirms all
eight original strict scores/candidate text hashes unchanged. All56 paragraph/profile observations
stay visible, including10 missing/ambiguous-anchor cases. Uniquely anchored edit traces preserve
Unicode/physical characters and do not become new accuracy scores or repair the strict matches.
No upstream, table or OCR comparison is rerun or relabelled by that diagnostic.

The broader frozen plan includes one complete business PDF/two statement regions, two historical
and two degraded scans alongside the four scientific originals. Table, OCR, defaults and scoped
performance executions are now complete in the linked evidence. Later explicit caption
development results appear below; broader figure association, reading-order and independent
held-out acceptance remain separate open work.

Official freshness checking found0.27.29 after this run was pinned. Its only additional commit
is an already reviewed legacy XLS fix; PDF source hash and dependency manifests are unchanged.
The [release review](../benchmarks/review/upstream-release-0.27.29/README.md) advances source-review
currency while preserving measured0.27.28 runtime and historical0.27.27 goldens.

## Defaults and source images

Source-image invocation metadata also retains alpha, resolved blend mode, graphics-state
soft-mask presence and alpha-source flag. These properties do not flatten PNGs against
the page or implement the full enclosing transparency-group chain. No paired upstream
state/pixel comparison or general rendered-image fidelity score is claimed.



The [default behavior run](../benchmarks/review/pdf-interim-defaults/README.md) retains all18
outcomes across nine whole documents and both libraries, with equal summaries on Java21/25.
DocWeave without an OCR provider returns an image and OCR-not-performed diagnostic for scans;
upstream auto returns recognized text using the installed default English OCR environment.
This routing difference is separate from the configured German recognition scores above.

An independent Poppler inventory agrees with all33 DocWeave ordinary source-raster placements:
28 across four scientific PDFs, one business placement and four scan images. Agreement checks
original page, pixel dimensions and duplicate multiplicity; masks stay separate. This is not
semantic figure/caption, image payload or bounding-box accuracy. Upstream default output does
not expose these source-raster objects here; its hi-res detected regions have a different
contract. A33-versus-zero image-quality ranking would be invalid.

Later [original PNG regression](../benchmarks/review/pdf-external-stencils/README.md)
retains186 decodable payloads against187 independent Poppler entries, including one
unmatched auxiliary soft-mask-form raster. Mask roles and placement multiplicity
remain separate; the discrepancy is not discarded. Owned solid-stencil controls
demonstrate paint/alpha handling, while those originals exercise unchanged pattern
fallback. Neither result measures whole-page compositing or semantic figure quality.

## Later native figure-caption development

The [numbered-title study](../benchmarks/review/pdf-numbered-title-captions/README.md)
uses four complete scientific originals and16 publisher main-body caption references.
All were previously evaluated development cases. Every policy uses the same source
bytes, geometry bounds and exact ASCII-whitespace-only scorer:

| Uniform profile | Unicode-horizontal period-label policy | Numbered-title policy |
| --- | ---: | ---: |
| Native lines, complete bodies classified |6/16|7/16|
| Native column paragraphs, complete bodies classified |10/16|14/16|
| Source-tagged tables / native lines, complete bodies classified |6/16|7/16|

All six PeerJ leading caption units classify with numbered titles, but two full
bodies still differ from XML through printed word-wrap hyphens; they retain no
strict text credit. Partial line units also receive no complete-body credit.
All12 original full-text/reference observations remain exact. All18 image-negative
observations remain caption-free with unchanged PNGs; these are three metadata
variants of one logo layout, not18 distinct negative documents. Source label/body
whole-unit equality remains separate from complete-body presence.

A [whole-page Caption-class model diagnostic](../benchmarks/review/pdf-caption-model-regions/README.md)
also processes every page of the same27-page PeerJ original with fixed YOLOX settings.
It emits one generic Caption proposal, with none on the known figure-caption pages.
This is not an execution of Unstructured's complete partitioning pipeline and does
not rank the libraries. The model trial stays negative; no model-caption API or
rescued per-source thresholds were introduced. Broad physical caption boxes,
unlabelled/vector illustrations and independent association precision remain open.

A subsequent [frozen-code Scientific Reports transfer](../benchmarks/review/pdf-caption-scirep-transfer/README.md)
retains all five new-publisher main-body captions. The numbered-title policy
classifies2/5 complete bodies under columns and0/5 under lines; complete all-text
presence is4/5 for every profile. After this source becomes development data, a
separate [edge-aligned policy](../benchmarks/review/pdf-edge-aligned-captions/README.md)
improves its column result to4/5 without source/reference repair. It accepts spaced
label punctuation and either aligned image edge at unchanged tolerances. The fifth
body remains a strict miss; line-profile complete-body credit stays0/5. The earlier
four-original result and frozen transfer are not rewritten. This adds no paired
Unstructured caption measurement or held-out precision claim.

The subsequent [native compound-wrap policy](../benchmarks/review/pdf-native-compound-wraps/README.md)
retains physical hyphens while omitting generated spaces only after complete native
line reconstruction. On these now-observed development originals, Scientific Reports
columns improve4/5→5/5 complete bodies, yielding19/21 across five originals. PeerJ's
two physical-hyphen differences remain strict failures. All15 source/profile pairs
retain geometry, unit types/order, declared metadata accounting and ordered characters
after excluding only ASCII space; source-space controls are separately owned tests.
This is an explicit source interpretation, not relaxed scoring or an upstream rerun.

## Scoped native-text performance

The [performance run](../benchmarks/review/pdf-interim-performance/README.md) covers five modern
originals, three independent serial forks per runtime, two warmup and five measured passes.
JVM verification retains all360 API samples and checks every output hash against each library's
separate default capture. Host/resource limits, first-pass samples, full ranges and raw numeric
observations are retained. Median measured whole-document API milliseconds,15 samples per cell:

| Source | DocWeave Java21 | DocWeave Java25 | Unstructured0.27.28 fast |
| --- | ---: | ---: | ---: |
| PLOS0324609 |63.30|58.89|1092.91|
| PLOS0330904 |48.13|47.71|860.43|
| eLife90049v1 |80.38|78.38|3815.67|
| PLOS0360958 |84.43|85.30|1475.32|
| Amazon Q4 2025 |49.06|47.19|2007.35|

DocWeave has lower latency under these native-text conditions. Both paths are in-memory,
CPU-affined and sequential, but emit different semantic categories/image inventories. This
does not measure OCR/model performance or establish equal semantic work. Median whole-fork
peak RSS is496.84MiB on21,506.31MiB on25 and690.46MiB upstream. JVM heap512MiB versus Python's
standard GC/installed PDF stack are different resource policies. RSS includes process lifetime
and shutdown; it is neither retained memory nor per-document memory. No cost ranking follows.

## What remains before a final comparison

1. Preserve this interim development baseline and refresh it whenever the evaluated production
   configuration or upstream runtime changes. Current execution does not constitute acceptance.
2. Obtain independently reviewed line-order and held-out labels for required acceptance domains;
   separate geometry, topology, text, reading order and confidence metrics.
3. Resolve required ambiguous-header/scan/font/layout gaps or explicitly agree narrower scope;
   retain existing regressions, model/runtime identities and supported limits.
4. Refresh this matrix with final commits/configurations and regression/release evidence.
   A beta release still requires its reviewed version tag, release notes and publication workflow.

Only the explicitly linked executions are measured. No commercial-service or general-library
ranking is implied. The open [completion gates](pdf-completion-plan.md) remain authoritative.

## Later Java-only background normalization

The explicit page-only Tesseract whitepoint policy is evaluated separately from the
historical paired library comparison. All four fixed scans and both models remain;
low-contrast1778 line associations improve17→31/38 and18→31/38, while1780 drops9→8/10
and1835 character edits slightly worsen. Extra predictions rise on1778. Full managed
Java21 verification passes1,185 tests including8 actual OCR calls. This is not an
Unstructured ranking or general enhancement guarantee; see
[complete outcomes](../benchmarks/review/pdf-scan-background-experiment/README.md).
