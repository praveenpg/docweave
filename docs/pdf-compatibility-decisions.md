# PDF compatibility decisions

The previously open source-order, table-membership and transcription-scope decisions
use observable behavior from a pinned Unstructured OSS execution as their compatibility
reference. They do not require a separate human decision. Missing evidence is an explicit
reference-capture task, not a human-approval gate.

## Reference identity and scope

Use the [current evaluation scope](pdf-evaluation-scope.md). New third-party corpora and
qualification beyond relevant public Unstructured checks are deferred. Preserve existing
regression coverage and keep public generative-parser examples separate from pinned OSS outputs.

The existing executed comparison is Unstructured 0.27.28 with inference 1.6.13. The
reviewed release/main identities in `benchmarks/upstream-status.json` are separate from
that executed runtime. A new execution version must be selected and recorded explicitly;
reviewing newer source does not establish newer runtime behavior.

For each case record source hashes, strategy, languages, OCR engine and traineddata,
detector/structure weights, rendering configuration, effective options, raw reference
output and its hash. Distinguish `fast`, `hi_res`, `ocr_only` and `auto`; do not choose
whichever strategy scores best after inspecting output. OSS results are not evidence
of hosted-service behavior.

Existing source-accuracy and operational tests remain in force. Agreement with the
reference is a compatibility observation, not proof of extraction accuracy. Preserve
historical goldens and reference protocols; introduce compatibility expectations as
additive, versioned evidence rather than silently rewriting old scores.

## Decision matrix

| Area | Observable reference behavior | Required acceptance evidence |
| --- | --- | --- |
| Scientific page-1 order | Ordered elements, categories, paragraph grouping and coordinates from the predeclared native-reading strategy | Compare actual sidebar/abstract/body element order and membership on all three originals; retain unresolved order separately from exact text and source/XML presentation differences |
| Financial parentheses | Native text and structured table cell text/HTML under the declared table strategy | Record exact whitespace and cell boundaries; use only frozen, symmetric comparison normalization, retaining the original strict publisher-reference score |
| Historical page numbers | Whether the declared OCR strategy emits the number, its category, page and source box | Capture complete output, including extra/missing text; distinguish structural exclusion from failed recognition |
| Historical colour interpretation | Actual upstream decode/render pixels and OCR input path for the same declared source format | Record RGB/frame hashes and ICC handling; do not infer the reference policy from which DocWeave decode has fewer errors |
| Degraded list columns | Upstream ordering/grouping and boxes within the printed list and surrounding prose | Preserve full-page output, including merged/split lines and extras; distinguish word/line/block granularity rather than assuming different schemas are identical |
| Stamps and ruler marks | Emitted text, categories, boxes and exclusion behavior | Follow the observed extraction scope for the selected strategy; do not infer scope solely from an absent transcription or an OCR miss |
| Apple trailing rows | Whether the two disclosure rows occur inside table elements and their structured cells | `hi_res` with declared table inference; presence anywhere in document text does not prove table membership |
| Amazon trailing row | Whether the diluted-shares row occurs inside the income-statement table and its structured cells | Same fixed table strategy/protocol as Apple; retain both inclusion and exclusion observations |
| Acceptance breadth | Relevant pinned upstream fixtures/cases plus existing original documents | Freeze the case list and source identities before candidate extraction, capture upstream outputs, then run complete JVM comparisons; do not describe known development data as held-out |

## Completion criteria

1. Reuse existing captures only when their source, runtime and effective configuration
   cover the exact question. Aggregate paragraph/row scores alone do not determine
   element order, pixel interpretation or table membership.
2. Capture missing reference behavior with the actual pinned Unstructured runtime.
   Python is appropriate for that execution. DocWeave extraction, tests and scoring
   remain on the JVM. No implicit engine/model download is permitted.
3. Define schema and coordinate mapping before scoring. Retain all cases, failures,
   duplicates and default/explicit-policy distinctions. A typed upstream failure or
   nondeterminism is recorded as such, not turned into an invented expected output.
4. Add owned JVM controls and complete source regressions for any implementation change.
   Preserve caller-selected policies, existing public APIs, resource bounds and typed errors.
5. A case closes when reproducible reference artifacts establish the decision and the
   declared DocWeave compatibility behavior is verified, or when a named unsupported
   capability is explicitly recorded. No human source-acceptance signature is required.

Independent implementation review remains part of integration. Replacing source-policy
decisions with a reference execution does not remove code review or operational gates.

## Recorded reference observations (2026-10-22)

All observations use executed Unstructured 0.27.28 with inference 1.6.13. Manifests, JVM
reports and reproduction steps are in
[the alignment evidence](../benchmarks/review/pdf-unstructured-alignment/README.md). These are
compatibility observations, not source-accuracy results.

| Area | Strategy and capture | Observed reference behavior | DocWeave status |
| --- | --- | --- | --- |
| Scientific page-1 order | Existing `fast` captures, all three originals | Ordered elements, categories and coordinates; author markers inline after names; non-table text presented with all Unicode whitespace collapsed to one ASCII space | Explicit `PdfOptions.InlineScripts.NEARBY_SMALL_GLYPHS` places markers inline; source spaces stay faithful by design |
| Financial parentheses | Existing `hi_res` default and shared table captures; new `fast` capture | Accounting negatives tight, such as `(5,491)`, in native text and in every table cell | Compatible: DocWeave text and cells are tight; the spaced form comes from issuer HTML, whose strict scores are unchanged |
| Apple and Amazon trailing rows | New `hi_res` capture with table inference, fixed YOLOX and default structure weights | Both statement tables end above the target rows, which are emitted only as separate text outside any Table element | Compatible: DocWeave also excludes them; no boundary rule is justified on compatibility grounds |
| Historical colour interpretation | New `ocr_only` capture of the original TIFFs through `partition_image` | The OCR input array equals the stored samples; embedded ICC profiles are not applied | Recognized text is byte-identical to DocWeave's opt-in stored-sample harness for 1835 under both models; the colour-managed container remains the default protocol |
| 1835 page number | Same original-image capture | Emitted as `UncategorizedText` at the reference box under both models; absent from the colour-managed container capture | Inclusion is in scope; the container miss is a pixel-interpretation effect, not filtering |
| Degraded list columns | Original-image and container captures | Original image: list and surrounding prose form one element with no column separation. Container: separate list and title elements | No column split is required for compatibility; reference-quality column work stays parked |
| Stamps and ruler marks | Original-image and container captures | Right-margin marks on 1780 emitted as `Title` or `UncategorizedText`, overlapping no reference line | In scope; DocWeave applies no category or confidence filter |
| Acceptance breadth | New `fast` capture, ten local originals | All succeed; tokens and order compared on the JVM | Recorded as a baseline; all ten are development data, not held-out cases |
