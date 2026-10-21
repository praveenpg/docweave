# PDF completion gates

See the [current progress ledger](pdf-progress.md) for concrete remaining work. The repeated
85–90% estimate is withdrawn: it had no fixed weighted backlog and is not a completion measure.
Historical percentages below describe earlier reporting, not current readiness.

The [draft beta checklist](pdf-beta-checklist.md) inventories a possible release with documented
limitations. It does not replace these gates or record acceptance of narrower scope.

Completion requires the documented API, Javadoc and validation gates below. Development
verification targets Java21; Java25 verification is reserved for final PDF acceptance or a
release candidate. A narrow heuristic or passing diagnostic does not establish broad support.

The historical remaining-work section combines delivered features and open research-sized items.
Until clarified, the full required backlog remains the completion gate. Image description/caption
generation, interactive forms/annotations and vector-only illustration interpretation were already
outside the initial element model and remain separate capabilities.

| Gate | Current state | Required evidence/work |
| --- | --- | --- |
| Native PDF/text/image/table core | Implemented narrow documented strategies | Keep independent fixed cell expectations and limits/default regressions green. |
| Independent table-image/text labels |20 upstream-train examples evaluated; explicit rule-preferred ONNX composition plus actual-word/cell OCR | Selected composition:20 tables/15 complete positional-span topologies,410 exact positional/span/text cells;0 complete text tables. Paired21/25 evidence in benchmarks/review/pdf-ruled-preference. Development-corpus results, not held-out accuracy/calibration; defaults unchanged. |
| Independent physical-scan coverage | Fixed9-case/20-table evaluation; explicit managed TATR/YOLOX comparison | One-to-one spatial matches11/20 versus12/20, equal dimensions9/20 each, exact anchor/dimension sets3/20 versus2/20. Same OCR-limit case retained. No transcription/cell-geometry oracle or uniformly better detector; historical/degraded acceptance remains open. |
| Figure-caption association | Explicit bounded numbered-label/title/edge strategies and native compound grouping; five observed scientific originals | Column profile19/21 complete bodies, including Scientific Reports5/5; line profiles7/21. Two printed PeerJ physical-hyphen mismatches remain strict failures. Broad physical boxes, semantic association precision and independent held-out negatives remain open. Model-only Caption trial negative. See native-compound evidence and comparison matrix. |
| Unified native/OCR ordering | Implemented; full Java21/25 verification passes | Full Java21/25 and JSON/chunk provenance checks; geometric order is not logical article order. |
| Physical/pixel coordinate output | Implemented; Java21/25 verified | Interface service converts parents/cells with explicit units, DPI, UserUnit and preserved displayed crop/rotation frame. |
| Orientation and deskew | Explicit fixed-angle and conservative small-angle OCR correction verified; explicit automatic quarter-turn strategy and managed OSD adapter verified on Java21/25 | Opt-in transforms with original-frame coordinates, budgets and independent evidence; no silent source edits. |
| Arbitrary-angle native text | Opt-in projected lines implemented/verified | Defaults retain quarter-turn-only failure; original Mozilla45-degree fixture has independent visual/source-matrix labels. Broader arbitrary-layout/complex font evidence remains open. |
| Repeated headers/footers | Conservative opt-in tagging implemented | Owned controls plus original RFC9110 first12-page header/footer labels pass under explicit bands/first-page policy; broader semantic evidence remains open. |
| Font/Unicode and reading layouts | Used-font/map inspector implemented; broader reading layouts partial | Five original PDFBox Unicode fixtures pass explicit presentation-form policy (RAW3/5, selected5/5); observed font inventory and broader semantic/layout regressions remain partial. Fixed two-column geometry is not general reading order. |
| Confidence/calibration | Open | Independent labels, stated domain/sample size, held-out evaluation; never assign an arbitrary probability. |
| Final PDF closure | Open | Reconciled support matrix, all required gates implemented/validated or exclusions explicitly agreed, real OCR/owned/corpus checks, Java21/25 strict Javadocs, a verified source revision and recorded CI results. |

The current table-structure diagnostic remains open: all11 NVIDIA pages under both
frozen model profiles score0/110 strict statement rows. The explicit synchronized
native-gap experiment adds observed rows66→67(TATR) and90→93(YOLOX), with unchanged
association coverage and strict scores. This partial experiment does not justify a
production topology-refinement default or close the modern business-table gate.
Source-first cell word-wrap composition is implemented with1,172 Java21 tests passing;
all six profiles of three pinned scientific originals remain unchanged. See the current
progress ledger for complete evidence and the distinction between functionality and
original-source accuracy gains.

## Progress reporting correction — 2026-10-17

The previously repeated87% was a subjective historical planning estimate, without a fixed weighted
backlog or measured remaining effort. It is not a defensible measure of readiness for broad scanned
tables and must not be repeated as measured progress.
Report concrete delivered capabilities and independent outcomes; any future overall estimate must
state its scope/basis and uncertainty. Test counts and diagnostic reports do not close recognition gaps.

Of the ten capability/evidence gates above (excluding final closure), three delivered subsets are
implemented and verified: native core, geometric native/OCR ordering and coordinate conversion.
Five remain partial: published table-image support, orientation/deskew, arbitrary-angle native evidence,
repeated-margin evidence and font/Unicode/reading layouts. Two remain open: demonstrated physical-scan
table coverage and confidence calibration. These unequal gates are not a percentage of engineering effort.
In particular, RULED has one merged topology success among20 published table crops; ALIGNED_WORDS
has zero on that set, despite its passing owned controls. Optional ONNX layout now produces14 complete positional-span topologies on that set, but zero fully exact text tables. Physical-scan table coverage remains unproven.
No general PDF-completion claim or predictable time/credit budget follows from this breakdown.

Raw third-party scans/annotations/transcriptions are fetch-only, not redistributed.


## Functional support and quality acceptance — 2026-10-17

The implementation now has explicit strategies for native text/ruled and aligned tables/raster
images, textless and mixed-image OCR, actual-word and provider-layout tables with cell OCR,
source-frame unit conversion/order, quarter-turn estimation and manual/small-angle correction,
paragraph/two-column/oriented native text, presentation-form handling and repeated-margin tagging.
Separate PAGE/CELL strategies, bounded crop context and explicit magnification add configuration
choices; they do not establish higher extraction accuracy. Semantic article order, general complex
script/font repair, calibrated confidence and broad noisy-scan fidelity remain unproven/open.

Current evidence makes the distinction concrete:

| Observation | What it establishes | What it does not establish |
| --- | --- | --- |
| Original20-image CELL profile:19 tables,14 complete span topologies,336 exact cells,0 entirely exact tables | Reproducible scoped layout/OCR behavior with published labels | General scanned-table quality or production readiness |
| Alternative cell weights322 exact cells; context4 gives320;3x recognition gives288 | Fixed uniform strategies have measurable gains and regressions; defaults should remain | A safe automatic enhancement rule or calibrated score |
| All1230 text-box labels retained:454 fit actual rounded crops;131 lack matching positional/span anchor | Geometry risk can be measured independently of OCR strings | Exact glyph loss, a semantic table failure count or permission to repair annotations |
| Original RFC9110 first12 pages:33 headers/24 footers under explicit policy | One independent publisher's source-preserving margin labels | General semantic header/footer recognition |
| Five PDFBox Unicode cases:RAW3/5 exact, explicit presentation policy5/5 | A narrow Unicode policy with unchanged source geometry | Universal bidi, font-map repair or logical reading order |
| Original Mozilla45-degree word retained with source-matrix label | Independent arbitrary-angle regression | General article-layout or tight glyph-box correctness |

Feature coverage and recognition quality are separate acceptance criteria. The open gates
remain unresolved until the target document domain, independent labels and acceptance metrics
are defined and evaluated. Additional options, tests and parameter searches do not substitute
for held-out review or establish PDF completion.

The historical2026-10-17 official upstream audit reported0.27.28/main1b02b4d189c260ed9b1aca29583203e233d853cb,
six commits after the historical0.27.27 pin. At that checkpoint the PDF/OCR change was
agent-load error-cause propagation, not a new extraction algorithm. Source metadata is retained in
benchmarks/review/pdf-ocr-magnification/upstream-status.json. Historical goldens remain unchanged.

## Missing-anchor audit — 2026-10-17

The fixed geometry profile now classifies all131 missing valid labeled positional/span keys:
97 have no predicted table (one image),24 have different spans at the same origin, six have
origins covered by another anchor, and four extend outside the predicted grid. All prior
geometry metrics remain identical on Java21/25. These exclusive symptoms are not causal
model-error labels: shifted axes can produce span mismatches. The97 valid labels are part
of100 published anchors in that image, including three without text-box labels.

See benchmarks/review/pdf-missing-anchors/. This separates structure rejection from OCR
recognition before a policy change is considered. A temporary object-deletion experiment
suggested conflicting merge predictions reject the missing table; dropping one query locally
is not a validated correction. Default conservative rejection and historical references remain.

## Merge-conflict handling and upstream comparison — 2026-10-17

A pinned actual Unstructured comparison now answers the failed-table question: same-object
postprocessing and shared-source-weight recognition emit99 anchors versus100 published,
while the public hi_res PDF profile emits a partial7x10/70-cell table. Neither is entirely
correct. Source inspection alone was insufficient. See benchmarks/review/pdf-upstream-table/.

An explicit KEEP_HIGHER_SCORE policy now discards whole conflicting lower-score rectangular
merges after ownership preflight; conservative default rejection remains. Fixed20-image
Java21 results improve19 to20 emitted tables and336 to369 exact positional/span/text cells.
Other19 measurement records are identical; complete span topologies remain14 and entirely
exact text tables remain0. This addresses a rejection mechanism, not general recognition
quality or calibration. Final paired Java25 evidence is recorded in
benchmarks/review/pdf-merge-conflicts/. Original open completion gates remain.

## Fixed20 actual upstream comparison — 2026-10-17

The same-object and shared-weight upstream profiles each have eight complete span topologies
on20 scientific-table crops, versus14 for explicit DocWeave structure/CELL and14 for the
historical detector/structure profile. Public upstream hi_res300DPI with pinned default model
identities gives14 nonempty tables across11 inputs, zero completely matching span topologies.
All20 inputs/1380 reference anchors remain; all40 Java21/25 captures match. No source repairs
or per-case tuning are applied. One case has better shared-weight upstream key matches
(63 versus54); it remains in the evidence. No profile yields a wholly exact structure/text table.

See benchmarks/review/pdf-upstream-all20/. This supports a scoped topology advantage, not
general PDF/OCR accuracy, model-training independence or commercial-service parity. Different
weights/preprocessing/crops/OCR/parsers are documented, and the older detector result is
explicitly historical. Broad original gates and acceptance target remain open.

## Observed-rule/model composition — 2026-10-17

The explicit preferRuled interface strategy now combines closed observed grids with disjoint
model tables after full raw-layout validation. With the same20 original images/graph/engine
profile and uniform policy, complete span topology improves14 to15, exact cell strings369
to410, and matching positional/span keys1280 to1295. The retained upstream counterexample
now has all69 reference spans and52 exact cell strings; other19 measurements are unchanged.
Valid labeled missing keys reduce35 to20. Every result remains in the denominator, and no
table is entirely text-exact. Actual21/25 paired records match;899-test full verification and
strict Javadocs pass on both, with default replay preserved. See
benchmarks/review/pdf-ruled-preference/ for ownership/resource controls and source pins.

This validates a scoped opt-in improvement, with broad real-scan/complex-native/held-out/
calibration/acceptance-target gates still open. No overall PDF-completion claim follows.


## Domain priority — 2026-10-18

Development and acceptance evidence follow this priority order:

1. Modern business and scientific PDFs.
2. Historical scans.
3. Degraded scans.

This answers the domain-priority question above. Numeric acceptance thresholds and held-out
quality targets remain unspecified; the order does not waive existing completion gates.
Scientific-table evidence does not establish business-document coverage.

An initial alternative-detector diagnostic on identical rendered rasters matches12/12 modern
table regions with pinned upstream YOLOX, versus10/12 with the current TATR detector. Historical
results favor TATR (2/8 versus0/8, including one source pixel limit). These are one-to-one
axis-aligned envelope matches at IoU>=0.5, not complete structures or text accuracy. See
[the scoped comparison](../benchmarks/review/pdf-alternate-detectors/README.md).
The next experiment should validate the modern regions through the existing structure stage
before a JVM detector adapter or default change is proposed. Broad held-out, native-layout,
OCR and calibration gates remain open.


## Alternative detector structure-stage result — 2026-10-18

The follow-up [structure diagnostic](../benchmarks/review/pdf-detector-structures/README.md)
finds no exact-topology gain on the prioritized four modern inputs: both detectors yield two
exact structures among12 references. YOLOX emits12 modern structures versus10, but the extra
regions still have topology differences. Historical exact successes fall from one to zero;
overall TATR3 versus YOLOX2 exact structures among20 references/9 retained inputs. All114
numeric files per JDK match, and TATR controls reproduce the previous whole-page structures.
No adapter/default change or beta is justified. Next evaluate the existing observed-rule/model
composition on whole-page modern inputs before adding a new detector. This remains fixed-set
diagnostic evidence, not held-out accuracy or business-document acceptance.


## Whole-page rule composition and resolution — 2026-10-18

The [paired resolution comparison](../benchmarks/review/pdf-whole-page-ruled-composition/README.md)
retains all9 original inputs/20 references at150 and300DPI. At150DPI no observed grid is accepted;
composed and model-only layouts are identical, reproducing the earlier end-to-end no-gain check.
At300DPI rules recognize two modern s102 tables already topology-exact in the model. Modern
model/composed results remain10tables/2exact; matching positional/span keys246to251 reflects
one improved and one regressed s104 grid. Historical source limits rise1to4 under the unchanged
16M ceiling, losing the s002 success. No default change/new beta or broad quality gate closure.
All306numeric captures match on actual21/25;24tooling guards and standalone strict docs pass.
Next audit modern misses by rule extraction/topology stage, including compact/colored/open grids,
before an explicit new policy. Existing scientific-crop positives remain scoped; held-out,
business-document, native-layout, OCR and calibration gates stay open.


## Raster-rule stage audit — 2026-10-18

The [isolated source-pinned audit](../benchmarks/review/pdf-raster-rule-audit/README.md) identifies
s102's two rejected closed grids as minimum-cell-size failures. A fixed dark compact prototype
(run32/minimum column16/row12, other ceilings unchanged) recognizes both at150DPI with exact
17x4/68-anchor and5x4/20-anchor topology, no model/OCR. All9inputs/20refs remain; same original
source limit and no additional historical limits/tables. This is an improvement over rules-only,
not additional successes over the existing model pipeline. Chromatic variants add resource-limit
failures without further exact topology. A cell-size-only/old-run64 candidate fails an authored
broken-border control and is explicitly rejected. Final dark compact passes seven authored controls
on both JDKs;231numeric/control files/JDK match,26tooling guards pass. No production/default change
or beta yet. Next implement an explicit compact rule strategy through the interface facade, keep
historical defaults/limits, and validate the real implementation on the same controls/corpus before
preparing a beta milestone. Colored/open layouts, held-out/business/native/OCR/calibration remain open.


## Explicit compact production strategy — 2026-10-18

The compact dark profile is now an explicit immutable value behind public interface factories;
implementations stay hidden and historical factories/direct RULED/barrier extraction remain
STANDARD. Production replay on all9 sources/20 refs yields two exact150DPI rule-only topologies
versus zero, no additional limits, with all66files/JDK identical. Actual cell interiors match
predicted ceil/floor boundaries with the same5px inset; six owned controls include tiny padded
cell crops through a real PDF and separate stub PAGE/CELL providers. Full actual21/25 verification
passes911tests/strictdocs;28tooling guards pass. See
[the production evidence](../benchmarks/review/pdf-compact-ruled/README.md).
This supports a scoped beta3 milestone, not a new model-pipeline success or OCR accuracy claim.
Next broaden modern-document coverage with independent business PDFs and retain remaining colored/
open/native-reading/OCR/held-out/calibration gates; existing exact/topology planning estimates are
not measured readiness. Candidate preparation and public publication remain distinct states.

## Business-page coverage gate — 2026-10-18

The first frozen external business-page set now has two business cases (594 saved CSV
positions) plus a separate public-statistics control (350 positions). Actual Java21/25
runs with fixed150DPI COMPACT rule-only layout and real PAGE/CELL OCR configuration
emit no tables: each layout call returns zero candidates, so no cell OCR runs. This is
a layout-coverage failure for this explicit strategy, not a measured text-recognition
comparison or a general PDF accuracy score. Reference labels are upstream native CSV
expectations, not human-reviewed scan/span ground truth; printed PDFs were rasterized
whole-page without reference-ROI crops. See [the protocol and retained negative results](
../benchmarks/review/pdf-business-cell-text/README.md).

Next compare an explicit model layout strategy on these same frozen pages, retaining
the rule-only control and fixed resources. Modern scientific coverage, genuinely current
business samples, independent physical-scan text labels and held-out acceptance remain
open. This evidence adds no production functionality and requires no new beta candidate.

## Model-layout business comparison — 2026-10-18

The same frozen pages now run through the existing two-graph ONNX provider with
actual English PAGE/CELL engines and fixed resources. Each page emits one associated
table, compared with zero under the retained compact-rule control. Procurement matches
the14x11 CSV dimensions and improves65/154 to88/154 positions with cell OCR. Financial
disclosure predicts60 rather than88 rows; its total positional matches fall49/440 to39/440.
The separately labeled statistics control predicts51 rather than50 rows and stays67/350.
No fully exact text table is produced. Business-only totals114/594 versus127/594 mix
empty/nonempty positions and CSV row differences; they are not general recognition accuracy.

All metrics/raster fingerprints and full local capture semantics agree on actual21/25;
the original rule-only CLI control replays unchanged numerically on both JDKs. See the
[fixed model protocol and detailed counts](../benchmarks/review/pdf-business-model-layout/README.md).
This evaluates existing functionality, changes no production defaults and adds no beta milestone.
Next establish native extraction on the original text-based pages to distinguish actual
native gaps from rasterization/OCR loss, then address row segmentation with independent labels.

## Original native business baseline — 2026-10-18

All five existing native table policies were frozen before running the same complete
original PDFs, with no copying/rasterization/OCR/model and evaluation only on the same
selected pages. Financial disclosure matches440/440 saved CSV positions with88x5
dimensions; its432 emitted anchors include merges. Procurement splits into14x2 and14x8
fragments rather than the complete14x11 reference, giving0/154 under unchanged geometric
association/positional evaluation. Statistics emits no Table, giving0/350 structured
positions; neither zero means zero extracted document text. Every native policy gives
the same result on actual21/25. General capability warnings remain, including on the
fully matching financial case. See [the native protocol and geometry](
../benchmarks/review/pdf-business-native/README.md).

Native totals440/594 business-only positions demonstrate why original native extraction
needs separate evidence from deliberately rasterized OCR. They do not establish universal
native superiority, independently correct spans or broad PDF accuracy. Next audit procurement
source strokes/grid components, preserving the exact financial case and existing negatives
before changing any algorithm. This step changes no production behavior or beta version.

## Native implicit-fill correction — 2026-10-18

The procurement split was caused by omitted **source-painted** rule centerlines:15 black
three-edge filled subpaths form rectangles through PDF's implicit closing edge. The graphics
visitor previously required four recorded edges. It now recognizes only a single subpath's
implicit rectangle, with existing thickness/color/alpha/closed-grid/work/budget gates intact.
It does not bridge missing rules or close stroked paths. Compound fills remain excluded.

On the frozen complete original PDFs, all five native policies now give one14x11 procurement
table with152/154 saved CSV positions, preserve financial440/440 and retain the statistics
miss. The two procurement differences are extra spaces relative to the saved reference; no
text correction or scoring relaxation is introduced. See [the correction evidence](
../benchmarks/review/pdf-implicit-filled-rules/README.md). This is a scoped beta4 functionality
milestone, not general PDF completion or a claim that beta4 has been published. Broader
current-business/scientific, original scan text, borderless and held-out gates remain open.

## Partially ruled statistics-table diagnosis — 2026-10-18

The [source geometry audit](../benchmarks/review/pdf-statistics-native-geometry/README.md)
shows that the statistics control is partially ruled, rather than borderless:1529 glyphs,
334 ordinary runs,126 painted segments, no images and no accepted table under existing
strategies. A49-baseline aligned block changes from2 to6 to7 runs per row; the horizontal
strategy sees a single full-width upper rule followed by seven-part rules, with an upper
rule gap48.297 exceeding its40-unit contract. No thresholds, ownership, text, labels or
production behavior change. Actual21/25 diagnostic reports are byte-identical. Next design
an explicit partially ruled/multilevel-header strategy with independent owned positives
and ambiguity/resource/cancellation negatives before changing extraction. Current closed-grid
and aligned defaults must remain; this diagnosis adds no beta or completion claim.

## Explicit partial-rule multilevel-header strategy — 2026-10-18

The opt-in `RULED_AND_PARTIAL_TEXT` strategy now recognizes the frozen statistics control as
one50x7 native table:339/350 saved CSV positions, including315/315 body positions and24/35
header positions. All11 remaining mismatches are in the inferred header. Financial440/440,
procurement152/154 and all historical five-mode records remain. Source runs determine explicit
heuristic spans; cell bounds are labelled inferred, rather than observed complete painted cells.
See [the contract and paired evidence](../benchmarks/review/pdf-partial-rule-text/README.md).

Actual21/25 full verification passes922 tests/Javadocs with seven independent owned test
methods; earlier native oracle eu-010 stays22/22 under the new mode. Explicit bounds, work,
limits, cancellation, raster barriers, option copies and8 concurrent calls are covered.
This is a proposedbeta5 functionality milestone. It closes one scoped recognition gap, not
semantic header correctness, physical-scan fidelity, held-out accuracy or final PDF completion.

## Original scientific native span/text regression — 2026-10-18

The [frozen original scientific case](../benchmarks/review/pdf-scientific-native-spans/README.md)
adds externally labelled native merged-grid evidence: the original3-page us-030.pdf yields
43/43 exact positional-span/text anchors on page2 under all six uniform native modes.
Published7x7 topology includes three row-span2 and one column-span4 anchors. Closed-grid
recognition handles this source first; this is not a new partial-rule inference success.
Reference ROI comes from original XML, selection is geometric, and no raw source/labels are
redistributed. Actual21/25 reports agree. No production behavior or beta version changes.
This historical scientific source does not close genuinely current-business, partial/borderless
header, physical-scan, held-out or calibrated-confidence gates.

## Recent issuer financial-statement native baseline — 2026-10-18

The [current-business protocol](../benchmarks/review/pdf-current-business-rows/README.md)
now pins an original2026 earnings release PDF and corresponding issuer-authored SEC HTML.
Two fixed pages contain27 operations and32 balance-sheet content rows. Under all six native
modes the complete14-page PDF processes successfully, but neither selected page emits a Table;
ordinary native text remains present. All59 reference rows remain per mode. Publisher HTML is
an independent row-content reference, not PDF physical cell/span ground truth. Source pages,
ROIs, references and policies were fixed before candidate extraction; no raw data is redistributed.

Source audits show horizontal subtotal rules, varying run counts, section/wrapped rows and
no vertical rules/accepted closed grid on the selected pages. This exposes an open current-
business native layout gap, not zero text extraction or measured OCR failure. Next compare
existing model/OCR layout on the same frozen pages before selecting a financial-table strategy;
keep this native control and all earlier scientific/business outcomes. Validation alone adds
no new beta or overall quality/completion claim.

## Comparison matrix at PDF completion

The final PDF acceptance review includes a DocWeave-versus-Unstructured comparison matrix. Include native text/fonts, tables and spans, images, OCR, reading order,
rotations, historical scans and degraded scans. Separate capability availability from measured
quality and operational requirements. Pin both versions/models/settings and use identical
source documents and independent references; preserve misses and mark untested areas explicitly.
Do not infer general parity from owned controls or small development corpora.

## Native/model breadth and release deferral — 2026-10-18

Release preparation for this milestone is deferred. Its functionality and draft notes
remain available for a later release; no version tag, prerelease or Central publication
was created at this checkpoint. The
[broader fixed evaluation](../benchmarks/review/pdf-native-model-breadth/README.md) retains the
scientific43/43 span/text result but shows native/model financial102/440 and statistics72/350
CSV positions, versus the existing partial-rule native440/440 and339/350. Wrong model row
counts remain open. Procurement is rejected before inference because its declared rotation90
is outside the new factory's supported scope; all154 positions remain in the denominator.
Next address declared quarter turns with owned controls and unchanged-source replay. These
are older development sources, not broader held-out/current scientific or scan acceptance.

## Native provider quarter turns and progress correction — 2026-10-18

The repeated85–90% estimate lacked a defined measurement basis and is withdrawn; use the
[explicit progress ledger](pdf-progress.md) rather than an unsupported global percentage.
The [quarter-turn milestone](../benchmarks/review/pdf-native-provider-turns/README.md) closes
the source-rotation rejection on the frozen procurement PDF: native/model extraction now
returns14x11 with152/154 CSV matches. Owned controls cover all16 page/baseline combinations
and preserve displayed cells/spans, images and unclaimed text with crop/UserUnit. Recent
59/59 issuer rows and scientific43/43 anchors remain; financial102/440 and statistics72/350
model misses remain open. No release preparation is resumed by this milestone.

## Native/model row-stage diagnosis — 2026-10-18

The [fixed row audit](../benchmarks/review/pdf-native-row-audit/README.md) locates the mismatch
before native text assignment: financial67 positive row objects become59 after8 suppressions;
statistics55 become51 after4; no table-membership loss. Native-band center diagnostics show
merged/coarse financial intervals and collapsed statistics header intervals plus different body
splits. No model/text/threshold/source-label changes or quality gain are claimed. Next implement
an explicit native-first composition that preserves accepted native tables and admits disjoint
model tables, with owned overlap/ownership/error/resource controls. Release remains deferred.

## Explicit native-first composition — 2026-10-18

The [implemented native-first route](../benchmarks/review/pdf-native-first/README.md) preserves
accepted native topology with exact detector ownership, then admits only disjoint model tables
after full raw validation. A uniform partial-rule native policy plus the existing model now
gives financial440/440, procurement152/154 and statistics339/350 CSV positions, while retaining
recent59/59 row-content and scientific43/43 span/text results. Nine owned controls cover overlap,
source preservation, provider failures and combined anchor/work budgets. Existing factories and
defaults are unchanged. Run-based aligned modes remain separate from this glyph-owning composition.
The underlying model-only row misses, held-out breadth and scan/calibration gates remain open.
Release preparation is still deferred. Next broaden independent modern source coverage.

## Independent native-first source check — 2026-10-18

The [ordinal-source evaluation](../benchmarks/review/pdf-native-first-independent/README.md)
retains all four XML-labelled regions from two previously unscored historical native PDFs.
Exact span/text results260/278,88/127,2/214,208/215 expose provider topology/text gaps; no complete
topology result. All use model topology because native detection supplies no replacement.
No production change or broad gate closure is claimed. Diagnose the32-versus33-row education
case next without label shifts; independent recent-source and scan gates remain required.
Release remains deferred.

The [education header audit](../benchmarks/review/pdf-education-row-audit/README.md) localizes
its weak2/214 result to an extra model header row:33 decoded/member/retained rows, no suppression
loss, versus32 in the independent XML. Five two-line header text envelopes cross model rows1/2;
all30 body-row centers follow at reference+1. The second table control has32 rows and same-index
body centers. Scores/labels remain unchanged. Next experiment with an explicit conservative
native-text-assisted multiline-header policy and adversarial controls; do not globally merge
headers or count this diagnosis as quality closure. Release remains deferred.

The [native header-wrap experiment](../benchmarks/review/pdf-header-wrap-experiment/README.md)
rejects a spacing/style/rule-only merge policy. Its fixed2x glyph-height gap misses the target;
legitimate body pairs have the same or smaller normalized gap and also pass the other cues.
ActualJava21/25 numeric replays agree. No policy is promoted, labels shifted, scores improved or
quality gate closed. Investigate model-declared header regions/token-assisted structure instead,
retaining these ambiguous pairs as negative controls. Release stays deferred.

## Provider header-role context — 2026-10-18

The [header-prefix implementation](../benchmarks/review/pdf-provider-header-prefix/README.md)
retains bounded model-declared top headers through native inference/crop translation and PDF native,
word and cell OCR metadata. Rows, spans, HTML/text and chunking stay unchanged; no ambiguous wrap merge
is introduced. Original constructor/accessors remain, with documented record-shape upgrade impact.
Replay trained header evidence next, then assess token-assisted refinement with stacked-header/data
negative controls. Existing quality misses remain; no release preparation or broad quality closure.

The [trained-header replay](../benchmarks/review/pdf-header-evidence-replay/README.md) completes
all10 fixed regions/7 original PDFs with exact restored graph pins on Java21/25. Six model tables
retain declarations; four native tables keep unknown evidence. The weak education header declares3
rows versus2 in the control; this supplies context, not a safe merge or quality repair. CSV/scientific/
independent scores remain; issuer HTML403 prevents a fresh59-row content check, with both PDFregions
retained. Next audit header spans/native token ownership while preserving stacked/data negatives.
Release remains deferred; broader quality gates stay open.
