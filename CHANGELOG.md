# Changelog

## Unreleased

- Honor bounded finite four-corner line-built clips, including skewed paths, during
  native rule traversal. Prevent invisible clipped grids from producing false tables.

- Honor finite affine-transformed rectangle-operator clips during native rule traversal.
  Reject wholly disjoint rule evidence conservatively while preserving visible controls,
  source images and graphics-state restoration.

- Isolate Type3 glyph drawing paths from unfinished caller paths, including restoration
  after cancellation or glyph traversal limits. Glyph strokes no longer consume page paths.

- Report `INPUT_TOO_LARGE` when native Form XObject nesting exceeds PDFBox's existing
  50-level boundary, instead of silently returning an extraction with deeper content
  omitted. Preserve image invocations at the deepest accepted level.

- Bound Type3 glyph-program nesting to 64 active scopes. Reject deeper or cyclic programs
  with `INPUT_TOO_LARGE` rather than a JVM stack overflow; unwind scope state after failure.

- Exclude invisible Type3 glyph drawing commands from native table-rule and shaded-row
  evidence. Preserve ordinary page paint, visible glyphs, source-image accounting and
  shared operator/cancellation limits.

- Preserve per-occurrence PDF image graphics-state alpha, resolved blend mode, soft-mask
  presence and alpha-source flag as separate metadata. Keep normalized PNGs and source
  occurrences unchanged; this records invocation state rather than final page compositing.

- Support frozen original chunk input in the Ollama example so generation preserves
  pre-existing relevance IDs. Add owned PDF/DOCX/HTML corpus preparation and exact collection
  guards; a12query live check improves top1 hits10/12 to11/12, retaining the remaining miss.

- Add a runnable remote/local Ollama contextual example and JVM retrieval capture/replay tools.
  Freeze original identities, queries and model configuration; retain failed/missing queries.
  One tiny owned live-model check improves top1 hits from3/5 to5/5; broader quality is unmeasured.

- Add opt-in cross-format contextual chunk enrichment with caller-owned provider interfaces,
  separate generated context/embedding text, unchanged original chunks and explicit provenance.
  Bound inputs, output context, virtual-thread concurrency and whole-call deadlines; preserve
  cancellation and explicit fail-document/original-only policies without model dependencies.

- Preserve native list boundaries when existing bullet/number markers use Unicode
  whitespace, including nonbreaking spaces. Keep source text and geometry unchanged
  and retain ordinary paragraph joins and existing marker syntax.

- Isolate unfinished native graphics paths across normal, nested and transparency-group
  forms. Preserve caller table rules and clipping instructions, including after failure,
  without resetting page-wide limits or discarding child paint.

- Match Unicode whitespace consistently in explicit repeated PDF margins and body-duplicate
  vetoes. Preserve original text, IDs, geometry and non-whitespace distinctions; keep
  existing repetition, physical-page coverage and first-page body policies.

- Treat equivalent numeric zero rotations consistently in explicit PDF repeated-margin
  classification. Preserve body-duplicate vetoes and exclude nonzero decimal angles
  without floating-point underflow turning them into upright candidates.

- Honor equivalent finite move/line rectangle clips with explicit or implicit closure,
  preventing invisible strokes/fills from becoming native table evidence. Preserve
  deferred clip timing, restored graphics state and unsupported-path fallback.

- Retain ordered transformed source-image corners, preserving mirror, rotation and
  shear independently of enclosing image bounds. Convert corner maps with the PDF
  coordinate service while preserving normalized payloads and source identity.

- Add explicit oriented compound-column text layout, retaining arbitrary-angle labels
  as separate barriers while grouping upright paragraphs. Preserve fractional rotations,
  original source geometry, resource limits and historical layout modes.

- Add compatible read-only binary context views and use them for PDF loading and
  paragraph fingerprints, avoiding extra full-array copies while retaining snapshot
  ownership, password handling and existing input limits. Whole inputs still materialize.

- Stream original-element UTF-8 JSON into provenance compression while enforcing the
  existing16MiB limit before staging later oversized input. Preserve decoded bytes,
  precision, malformed-surrogate behavior and source immutability. Raw compressed
  bytes may differ; final payload/input graphs still materialize.

- Add opt-in `NONZERO_ALPHA_NONEMPTY_CLIP_PAINTED_MODES` native text selection to
  exclude empty inherited form/page clip positions before duplicate suppression and
  source ownership. Preserve source-position budgets, visible duplicates and prior
  policies; textless pages may use only caller-configured OCR.

- Add opt-in `RULED_AND_FINANCIAL_ALIGNED_TEXT` for currency prefixes, accounting
  parentheses and Unicode-minus numeric tokens under existing complete-block native
  alignment and resource checks. Preserve source strings and prior policies; expose
  explicit `aligned_financial_v1` evidence without amount or locale inference.

- Honor single axis-aligned source rectangle clips for native PDF rule and pale-row
  evidence. Apply intersections after path completion and preserve saved/form state,
  original retained geometry and independent text/image policies. General path and
  partial visibility interpretation remain unsupported.

- Reject native rule and pale-row paint provably disjoint from inherited form/page
  clip bounds. Preserve transformed stroke-width/miter allowances, device-dependent
  hairlines and fills enclosing a clip. Retained source geometry stays intact.

- Reject native table strokes, filled-rule centerlines and pale row paint inside
  empty inherited form/page clipping areas. Retain nonempty form behavior and
  restored outer paint; general path clipping and full visibility remain unsupported.

- Add explicit `REPEATED_HEADER_LAYOUT_WITH_PROVIDERS` continuation links for
  provider-declared merged header prefixes. Match exact span topology, normalized
  labels and observed column boundaries within32 logical header slots. Preserve
  prior policies, page-local tables, source text, IDs and coordinates.

- Exclude zero-opacity strokes from native PDF table-rule evidence, preventing
  invisible grids from claiming text as tables. Combined fill/stroke paths retain
  existing supported fill interpretation when strokes are invisible. Preserve
  nonzero-stroke behavior and all measured scientific/NVIDIA regressions.

- Add explicit page-only Tesseract `LOCAL_WHITEPOINT` preprocessing with actual source
  DPI, bounded same-size grayscale preparation and existing admission/total deadlines.
  Preserve defaults, source pixels, coordinate frames and cell preparation. The fixed
  low-contrast1778 scan gains line coverage; other sources regress, so the option is
  never automatic. Document all outcomes and known gray-field/color loss.

- Add opt-in `REPEATED_HEADER_PREFIX_WITH_PROVIDERS` continuation links for two
  through four declared, unmerged provider header rows, capped at32 header cells.
  Preserve exact row/cell matching boundaries and existing source/frame/text limits
  across native, word-OCR and cell-OCR routes. Earlier policies retain their behavior;
  tables, repeated headers, text, IDs and coordinates remain unchanged.

- Add explicit `SOURCE_WORD_WRAPS_AND_ISO_DATES` table-cell joining for native
  provider and source-tagged tables. Retain physical compound hyphens, compose
  source-declared discretionary hyphens and valid wrapped ISO dates, and preserve
  original cell limits and geometry. Whole-table wrap counts remain in original
  chunk provenance. The three-scientific-original regression is unchanged;
  no additional original-source accuracy gain is claimed.

- Add explicit `COLUMN_PARAGRAPHS_COMPOUND_WRAPS` native text grouping. Reconstruct
  complete source lines and preserve physical hyphens while omitting only proven
  generated inter-line separators. Keep existing layouts, source characters, bounds
  and table/OCR behavior. The fixed Scientific Reports column caption check improves
  from4/5 to5/5 complete bodies; two PeerJ physical-hyphen differences remain failures.

- Add opt-in `IMAGE_EDGE_ALIGNED_NUMBERED_TITLES` for captions whose label
  punctuation is horizontally spaced or whose raster aligns with the caption's
  right edge. Preserve existing policies and thresholds. The observed Scientific
  Reports column-profile check improves from2/5 to4/5 complete caption bodies;
  all source text and strict misses remain unchanged.

- Preserve provider-declared PDF header prefixes through shared structural table
  chunking for native glyphs, word OCR and cell OCR. Keep source HTML unchanged,
  honor header repetition and size limits, and retain whole-source header metadata
  in provenance rather than attaching it to structural/text-only fragments.

- Add opt-in `IMAGE_ADJACENT_NUMBERED_TITLES` for capitalized bare numbered
  figure titles and colon-delimited captions. Retain existing cue policies,
  geometry, source characters and default behavior. On the fixed four-original
  scientific development population, the column profile classifies 14/16 complete
  caption bodies; two printed word-wrap differences remain strict text failures.

- Add separate `IMAGE_ADJACENT_HORIZONTAL_LABELS` caption cues for source Unicode
  horizontal separators, retaining the original ASCII policy and identical geometry.
  Preserve source characters, strict text comparisons and distinct policy provenance.
  The fixed column-profile check now classifies all 10 complete publisher caption bodies;
  strict whole-label equality and presentation-only differences remain separate metrics.

- Add explicit `IMAGE_ADJACENT_LABELS` native figure-caption classification through
  `PdfOptions.withFigureCaptions`. Preserve source text, geometry, order and default types;
  require a strict numbered label, bounded proximity and unique raster association.
  Keep ambiguous/rotated/vector-only and unlabelled output as ordinary text. Existing
  constructors remain available and all immutable copy methods retain the new policy.

- Add explicit `NONZERO_ALPHA_PAINTED_MODES` native PDF text selection. Exclude
  zero-alpha text before duplicate suppression and native/OCR selection while
  preserving glyph budgets, positive-opacity fill/stroke text and existing defaults.
  Record distinct provenance; clipping, soft masks and occlusion remain outside the policy.

- Add a paired JVM scan regression for explicit TATR/YOLOX selection, retaining
  all nine source cases and 20 published tables. Spatial matches improve from
  11 to 12 while exact dimensions/unique-anchor matches decline from 3 to 2;
  preserve the existing default and typed OCR failure without source-specific routing.

- Add explicit managed YOLOX page detection with existing TATR structure recognition
  through `OnnxTableLayout.createWithYoloX`. Reuse bounded session admission, deadlines,
  native cancellation and cleanup; validate fixed exported schemas, all raw values,
  candidate/work limits and class-agnostic suppression. Models remain caller-supplied;
  defaults and existing TATR output are unchanged. Raw query policies now allow up to16,128.
- Preserve complete detector/structure provenance when a valid long score representation
  would otherwise exceed the provider's256-character limit; no graph identity or policy is truncated.

- Add explicit `INLINE_SCRIPT_RUNS` native cell text interpretation for provider and
  source-tagged tables. Keep nearby superscript/subscript runs in source word order
  without changing characters, spans, cell geometry or ordinary text. Preserve original
  cell limits and ambiguous/rotated fallback; retain whole-source glyph counts in chunk provenance.

- Add opt-in `REPEATED_HEADER_WITH_PROVIDERS` continuation links for eligible native,
  word-OCR and cell-OCR provider tables. Require an explicitly declared single-row
  unmerged header, consistent columns and matching provider/text family. Preserve all
  page-local text, HTML, IDs and geometry; existing continuation policies are unchanged.

- Enforce image pixel limits against the normalized extent of active image masks,
  including crossed width/height expansions. Preserve mask precedence and reference-only
  extraction; expose actual PNG dimensions separately when they differ from source dimensions.

- Preserve solid fill and transparent mask pixels in opt-in stencil image PNG payloads,
  including inline images, reused masks and inherited form paint. Explicitly label
  pattern-stencil mask samples; retain reference-only output, intrinsic ordinary image
  colors and pixel/cumulative-byte limits. No new dependencies or public API changes.

- Keep source-table caption and non-text reference counts scoped to whole tables.
  Omit them from structured/text-only chunk fragments while preserving original
  metadata in provenance; caption text remains separate native output.

- Retain source-tagged table structure when explicit same-page captions sit outside
  the table text envelope. Validate bounded caption references and ownership conflicts;
  leave caption glyphs to native fallback without copying them into cells or generating
  caption text. Unsupported, overlapping or reused references retain whole fallback.

- Resolve positive Table-owner cell spans from bounded revision-zero attribute classes.
  Later class/attribute-array entries take precedence; direct cell attributes override
  class spans. Preserve whole fallback for malformed values, unsupported namespaces
  and revisions, with no inferred span inheritance or table geometry.

- Add explicit `LOGICAL_CELL_TEXT_ENVELOPES` geometry for source-tagged PDF tables.
  Preserve unrelated gap text as ordinary output while retaining authored cells and
  blank source slots without invented coordinates. Reject unclaimed text inside real
  cell text envelopes; preserve existing ownership, overlap, resource and rotation rules.

- Accept bounded, resolved presentation attribute classes on source-tagged PDF
  tables, rows and cells when they supply no row/column spans. Preserve direct span
  validation and whole-table fallback for unresolved, malformed, revisioned or
  topology-bearing classes; do not infer styling or geometry from class attributes.

- Add explicit `PRESERVE_LINE_BREAKS` for native provider-owned and source-tagged
  table cells. Retain distinct upright baselines as LF and escaped HTML `<br>` while
  preserving geometry, spans and original cell budgets. Structural chunks retain the
  multiline source in original-element provenance and omit source-only preservation
  claims from normalized chunk metadata. Defaults and OCR behavior remain unchanged.

- Add optional logical-envelope geometry for source-tagged PDF tables. Preserve authored
  blank rows and columns without inventing empty-cell coordinates; require disabled cell
  coordinates, upright source text and exclusive nonblank text ownership inside the real
  text envelope. Existing factories retain supported-axis validation and ordinary fallback.

- Support explicit glyph-free page-content references outside source-tagged table cells.
  Require unique same-page declarations, zero retained glyphs and no alternate/form content;
  retain original cell topology, glyph ownership and coordinates. Bound auxiliary references
  at4,096 per table and expose their accepted count. Missing, text-bearing or ambiguous
  references retain whole-table fallback; no empty-cell geometry is fabricated.

- Complete a clipped shaded-table header line only when every independent source header
  span supports the same baseline. Bound extension by the painted header pitch; reject
  unrelated ink, incomplete span coverage and competing baselines. Original source text,
  coordinates and ownership limits remain intact; generic header padding stays unchanged.
  Allow repeated lines belonging to the same complete header span while retaining overlap
  and conflicting-span rejection.

- Preserve tall bounded native cell paint before row merging, and apply the shaded-row
  aspect-ratio check to the merged row envelope. This fixes dropped numeric-cell paint
  on wrapped financial rows. Isolated block fills still provide no row evidence; source
  path/color/opacity/blending checks and existing paint/work/table limits remain in force.

- Add opt-in `RULED_AND_SHADED_ROWS_INLINE_SCRIPTS` for complete small raised/lowered
  glyph runs in shaded native body cells, including compact numeric padding. Reject
  duplicate/overlapping runs and competing body baselines without partial assignment.
  Retain original text, source ownership, coordinates and pregrouping character limits;
  previous policies remain unchanged. Exhaustive table-policy switches need the new value.

- Add opt-in `RULED_AND_SHADED_ROWS_COMPACT_PADDING` to omit narrowly supported
  overlaid ASCII digit padding in shaded native numeric cells. Preserve original glyph
  membership, coordinates and preprojection limits; report omitted glyph counts. Ordinary
  spaces, ambiguous geometry, headers and labels retain their original text. Existing
  policies and defaults remain unchanged; exhaustive table-policy switches need the new value.

- Add opt-in `PdfOptions.TableDetection.RULED_AND_SHADED_ROWS` for upright native tables
  with repeated pale row/cell paint, matching numeric subtotal rules and complete numeric rows.
  Preserve section rows, wrapped labels, inferred header spans, cell coordinates and unrelated text.
  Keep defaults unchanged; ambiguous paint/content falls back without partial ownership. Existing
  table/work/cancellation limits apply, with a4,096-rectangle page cap for this policy. Exhaustive
  table-policy switches must handle the added enum value.

- Add explicit tagged-paragraph `InlineScripts.NEARBY_SMALL_GLYPHS` for nearby smaller
  superscript/subscript glyphs with one unambiguous body baseline. Preserve native characters,
  original coordinates, source ownership and pre-grouping budgets. Defaults and existing
  three/four-argument constructors retain historical grouping. The options record gains a fifth
  component; record patterns, reflection, serialization and equality/string-shape consumers need review.
  Paragraph assembly requires consistent inline-script policies across fragments.

- Add opt-in tagged-paragraph `WordWrap.SOURCE_LINE_WRAPS`: retain physical ASCII hyphens
  while omitting generated spaces at unambiguous letter-to-letter native line wraps.
  Preserve explicit source spaces, existing policies, pre-join budgets and page boundaries;
  expose separate soft-hyphen and retained-hyphen counts. This interpretation is caller-selected.

- Add interface-based `PdfPartitioner.builder()` and `PdfStrategy.FAST`, `AUTO`, `HI_RES`,
  `OCR_ONLY` presets alongside existing factories. AUTO prefers native pages with optional
  textless-page OCR; OCR_ONLY recognizes every page without native-text duplication; HI_RES
  requires word OCR and explicit table layout, defaulting to300DPI. Preserve caller resource
  ownership, image placements, bounded rendering/output and failure propagation. No downloads,
  language guessing, semantic figure classification or automatic error fallback.
- Add explicit `PdfOcrOptions.Selection.ALL_PAGES` and immutable `withDpi(int)` configuration.
  Existing defaults/constructors stay unchanged. Exhaustive enum switches must handle the new
  selection. Forced OCR bypasses native glyph grouping while retaining graphics/document/OCR
  budgets; output records `pdf_ocr_selection=all_pages_v1`.

- Add explicit `PdfParagraphAssembler` for complete document-bound source paragraph fragments.
  Preserve all intervening elements and original page metadata in part provenance; join with
  one space, without cross-page dehyphenation. Keep partitioner defaults and page order unchanged.
  Bound elements, fragments, paragraph text and aggregate text; incomplete/conflicting groups
  retain their original occurrences.

- Add explicit Tesseract `PageSegmentation.SINGLE_COLUMN` (PSM4) for caller-known single-column
  text with variable sizes. Keep AUTO defaults and independent TABLE_CELL segmentation;
  no automatic layout inference or new dependency. Exhaustive enum switches need the new case.

- Add explicit `PdfTaggedParagraphOptions.PageBoundary.PAGE_FRAGMENTS` for consecutive-page
  source paragraphs. Keep original page coordinates, shared source ordinal and one-based
  fragment index/planned count. Validate each page fragment independently; retain unsupported
  fragments as ordinary text. Defaults stay single-page, with no cross-page word joining or
  global logical-order repair. The original three-argument options constructor remains;
  record patterns/reflection/serialization must account for the fourth component.

## 0.1.0-beta.6 — published 2026-10-19

- Add opt-in source-tagged native paragraphs with unique glyph ownership, bounded original
  text and explicit source-soft-hyphen joining. Preserve defaults and geometric fallback.
  Two tagged scientific development documents improve exact paragraph matches from3/21
  to16/21; an untagged third remains0/4. All15 fixed source/profile results agree on Java21/25.

- Add explicit `PdfPasswordProvider` configuration to built-in PDF partitioners and font inspectors.
  Preserve selected extraction strategies, actual access permissions and resource limits; use one
  caller-owned lookup per locked document and clear transferred arrays after opening attempts.

- Add explicit source-DPI overloads for Tesseract line/word providers. Supply the actual
  rendered raster resolution without resizing pixels; existing factories preserve engine defaults.
  Resolution can affect adaptive thresholding and recognition; no accuracy improvement is implied.
- Retain a uniform24-case source-resolution scan replay on Java21/25 with exact original/rendered
  RGB checks and independent coordinate conversion. Adaptive recognition improves on some cases
  and regresses on others; historical evidence and default policies remain unchanged.

- Add explicit native-text/provider-layout PDF extraction through `PdfPartitioner.createWithNativeLayout`
  and immutable `PdfNativeLayoutOptions`. Use caller-owned layout geometry with original native glyphs,
  preserve unclaimed text/images, validate complete topology and retain render/table/work/output limits.
- Recover both frozen current-business statement tables with59/59 exact issuer HTML row-content
  matches in live full-original-document extraction. This small development result does not establish
  physical-span accuracy, general layout accuracy or PDF completion. Defaults remain unchanged.
- Support declared page/native-baseline quarter turns in native provider layout using the existing
  displayed crop frame. Recover the previously rejected rotated procurement source as14x11 with
  152/154 saved CSV positions; preserve the recent59/59 row and scientific43/43 span/text results.
- Add explicit `PdfPartitioner.createWithNativeTablesFirst` composition: retain accepted native topology
  and exact ownership, validate all model candidates, and admit only disjoint model tables under combined
  anchor/work budgets. On the fixed set retain financial440/440, procurement152/154, statistics339/350,
  recent59/59 row content and scientific43/43 span/text anchors with one uniform configuration.
- Preserve provider-declared top header-row evidence in native and OCR table metadata. ONNX retains
  its bounded class3 contiguous prefix without merging rows/cells or rewriting HTML. The former
  provider-table constructor remains; the record gains a component (record-pattern/reflection impact).
- Add opt-in wrapped ISO-date joining for provider-owned native cells. Preserve original text limits,
  topology, geometry, native ownership and defaults; retain whole-table change counts in provenance.
  On one fixed2025 publisher table improve116/136 to131/136 exact logical span/text anchors.
  `PdfNativeLayoutOptions` retains its three-argument constructor but gains a record component.
- Add opt-in source-tagged PDF tables with explicit source headers/spans and exact page-MCID
  glyph ownership, whole-candidate fallback and optional native/model composition. Defaults
  remain unchanged; source tags do not establish general table accuracy.

- Validate source-tag glyph ownership after known existing PDFBox content-stream ActualText
  substitutions. On the fixed publisher table recover all136 logical span keys and135/136
  exact span/text anchors; retain9/9 headers and the remaining word-wrap mismatch.
- Add opt-in `RULED_AND_SEGMENTED_ROWS` source-rule inference for column-local body row spans.
  Preserve closed/partial precedence, exact glyph ownership and whole-candidate fallback.
  Recover20/20 logical span/text anchors in one eLife table, versus12/20 with model geometry.
- Add opt-in source-declared soft-hyphen joining for tagged native table cells, retaining
  original ownership, geometry and pre-join limits. Require one explicit discretionary glyph
  and an unambiguous separate-line word boundary; retain untagged and conflicting alternatives. Recover136/136 and15/15 exact logical span/text
  anchors in the two fixed PLOS tables, preserving all21 other selected regions on both JDKs.
- Add typed optional Tesseract5 binarization overloads for line and actual-word OCR providers.
  Preserve the existing options record, lazy construction, engine defaults, call-local segmentation,
  shared admission and all raster/process/output budgets; expose Otsu, adaptive Otsu and Sauvola.
- See [draft release notes](docs/releases/0.1.0-beta.6.md). Not yet prepared or published; annotated
  source tag and GitHub prerelease notes are required before the next Central upload.

## 0.1.0-beta.5 — published

- Add explicit `RULED_AND_PARTIAL_TEXT` native detection for partially ruled tables with
  multilevel headers, complete numeric body rows and clearly labelled inferred cell areas/spans.
  Preserve historical modes, defaults, source text, resource limits and per-document ownership.
- Recognize the fixed statistics control as one50x7 table, with remaining source/CSV header
  differences retained. See [the contract](docs/pdf-support.md#explicit-partially-ruled-tables-with-multilevel-headers)
  and [evidence](benchmarks/review/pdf-partial-rule-text/README.md). This is not general layout
  or scanned-table accuracy. See [release notes](docs/releases/0.1.0-beta.5.md); Published to Maven Central.

## 0.1.0-beta.4 — published

- Honor implicit PDF fill closure for a single three-edge rectangular subpath before existing
  thin dark-rule validation. Keep open strokes, triangles, curves, compound fills and broken
  grid rejection boundaries; preserve native geometry, budgets and per-document isolation.
- Recover the frozen procurement PDF as one14x11 native table (152/154 saved CSV positions)
  instead of two fragments; preserve the financial440/440 result. No OCR or model is needed.
  Two spacing differences and the separate statistics table miss remain open.

Cumulative with beta3's compact raster strategies and beta2's native rendering-mode policy.
Published to Maven Central. See
[the contract](docs/pdf-support.md#implicit-closure-of-native-filled-rules) and
[validation evidence](benchmarks/review/pdf-implicit-filled-rules/README.md).

## 0.1.0-beta.3 — locally prepared candidate

- Add explicit interface-based rule-only layout and compact rule/model composition using
  `PdfTableLayoutProvider.RuledProfile.COMPACT`. Preserve historical defaults, source ownership,
  geometry/work/anchor budgets, fixed five-pixel interiors and secondary lifecycle/error behavior.
- Recognize two exact compact dark-grid topologies at150DPI on the fixed nine-input comparison,
  without a model. This is not general OCR/scan accuracy; small interiors can omit glyph pixels,
  and colored/open/skewed/ambiguous grids remain unsupported.

Cumulative with beta2's native rendering-mode policy. This is development code, not a published
release. See [the contract](docs/pdf-support.md#explicit-compact-dark-raster-grids) and
[production validation](benchmarks/review/pdf-compact-ruled/README.md).

## 0.1.0-beta.2 — locally prepared candidate

- Add explicit native `PdfOptions.TextRendering.PAINTED_MODES` to exclude nonpainting PDF
  text rendering modes3 and7 before duplicate suppression, table text assignment and OCR
  page selection. Native output records the selected policy; filtered positions still
  consume the page glyph budget.
- Preserve default extraction of invisible searchable text and all earlier PdfOptions
  constructors. The new option can discard useful OCR search layers; it does not determine
  actual visibility through clipping, transparency, occlusion or optional-content layers.

This is development code, not a published release. See the [feature contract](docs/pdf-support.md#explicit-native-text-rendering-modes)
and [validation evidence](benchmarks/review/pdf-text-rendering/README.md).

## 0.1.0-beta.1

The recorded Maven Central deployment546ca93a-841b-4faa-bbdb-99a2c9dcfacf **PUBLISHED**
on2026-10-18. Initial beta includes documented native PDF text, table and raster-image
subsets, optional caller-configured OCR/layout adapters, other supported document formats,
chunking and batch services. See the [published beta scope](docs/pdf-beta-checklist.md);
remaining broad PDF quality gates remain open in the [completion plan](docs/pdf-completion-plan.md).
