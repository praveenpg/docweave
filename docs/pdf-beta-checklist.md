# PDF beta scope and release checklist

The original scope assessment below is historical, based on production code through `227c4a0`.
Use the versioned [release notes](releases/README.md) for current release additions/status.
This checklist does not close the original PDF completion gates or establish acceptance of exclusions.
The development POM remains `0.1.0-SNAPSHOT`; beta.4, beta.5 and beta.6 are published.

## Proposed user-facing scope

DocWeave is a Java21+ Maven library for PDF text, raster images and documented table subsets.
It has no Spring Boot dependency or Python production runtime. Public services use interfaces;
Java packages remain `dev.docweave` while Maven coordinates use `io.docweave`.
Java21 and25 are verified runtimes, not a claim that every optional native adapter works on
all operating systems. A Windows VM and Microsoft Word are not needed for PDF extraction.

| Capability | Default or explicit selection | Boundary to publish with the feature |
| --- | --- | --- |
| Native text | Default native-only parser; optional paragraph/column policies | Approximate line geometry; geometric ordering is not general article reading order. Font maps, hidden/clipped text and complex scripts can affect results. |
| Native tables | Default conservative closed axis-aligned ruled grids, including rectangular spans | Open/damaged borders, nested/overlapping tables and ambiguous merges can fall back to ordinary text. Decorative grids can be false positives. |
| Other native table strategies | Explicit aligned-text, horizontal-rule and double-rule options | Documented constrained subsets; no general borderless-table guarantee. |
| Raster images | Default placement references; optional decoded PNG/base64 payload | No image captions or interpretation; vector-only illustrations are outside this image model. Payloads consume cumulative budgets. |
| OCR | Explicit caller provider or separate Tesseract artifact | Defaults never invoke OCR. Textless-page selection and mixed-image-region selection differ; choosing one does not establish coverage of every scanned region. |
| Scanned tables | Explicit ruled/word-aligned/provider strategies; optional local ONNX adapter | No broad noisy-scan/handwriting guarantee. Geometry and cell text can be wrong even when a Table is emitted. Model files are caller supplied. |
| Model composition | Explicit higher-score merge policy and optional rule preference | Defaults remain conservative. Raw scores order proposals; they are not probabilities of correct extraction. |
| Coordinates | Optional cell geometry, units conversion and original-frame transform mapping | Bounds are geometric approximations, not glyph outlines or a text-correctness oracle. |
| Orientation/deskew | Explicit strategies, with optional Tesseract OSD | Can abstain; the fixed scan corpus has eight orientation abstentions. No silently lowered score threshold. |
| Multi-page tables | Explicit conservative adjacent-page links | Links do not provide general semantic continuation or a fully merged cross-page table. |
| Resource handling | Input/page/glyph/pixel/table budgets, deadlines and cancellation | Typed failures remain possible. Parser/codec allocations and cancellation delays prevent a total heap/latency guarantee. |

Detailed options and failure/fallback contracts remain in [PDF support](pdf-support.md).
Annotations/forms, caption generation and vector-illustration interpretation are separate
capabilities outside the initial model. Broad noisy-scan quality, complex native semantics,
held-out validation and calibration remain open in [the completion gates](pdf-completion-plan.md).
A beta would need to state these limits explicitly rather than imply full PDF completion.

## Dependencies and installation contract

| Use case | Artifact | Additional setup |
| --- | --- | --- |
| PDF only | `io.docweave:docweave-pdf` | Core/PDFBox dependencies; excludes other format parsers and optional OCR/model adapters. |
| Managed local OCR | `io.docweave:docweave-ocr-tesseract` | Explicit installed executable and traineddata; caller controls languages/timeouts. |
| Local model layout | `io.docweave:docweave-layout-onnx` | Explicit supported ONNX graphs and expected hashes; includes ONNX Runtime dependency. |
| All ordinary formats | `io.docweave:docweave` | Convenience dependency includes other format dependencies; optional OCR/model adapters remain separate. |

Use an explicitly published version of these coordinates; source builds use `0.1.0-SNAPSHOT`.
Consult the release notes for availability of newer opt-in features.
The [module guide](modules.md) and [OCR guide](ocr-tesseract.md) describe setup. No automatic
engine/model downloads, graph discovery or execution is introduced for the beta. Models and
training data have their own provenance/licenses; project licensing does not cover them.

## Required versioned release notes

Starting with beta.6, add reviewed notes under [docs/releases](releases/README.md) before preparation,
then create the annotated version tag and matching GitHub prerelease after candidate verification
and before Central upload. Use the template, link the changelog and include verification/limitations below.
Update prepared/validated/publishing/published status only after confirmation.

## Evidence to carry into release notes

| Evidence | Observed result | What it does not establish |
| --- | --- | --- |
| Full verification of unchanged production snapshot |899 tests, zero failures/errors/skips, strict Javadocs on actual21/25; explicit installed-engine evidence recorded separately | A fresh release build, every native platform or full Unstructured parity. |
| [Fixed20 scientific table crops](../benchmarks/review/pdf-ruled-preference/README.md) | Explicit rule/model composition:20 tables,15 complete positional/span topologies,410 exact positional/span/text cells, zero wholly exact text tables | Held-out accuracy, a percentage applicable to full documents, or default-profile results. Training overlap is unknown. |
| [Fixed nine cTDaR inputs](../benchmarks/review/pdf-ruled-scans/README.md) |12 candidate tables; three exact dimension/anchor-set matches at highest envelope overlap; one OCR_LIMIT input | Text accuracy: no transcription oracle. Modern rasters and historical photographs are different domains. |
| [Detector-resolution experiment](../benchmarks/review/pdf-detector-resolution/README.md) |1200-pixel trial adds no exact topology matches; candidates fall12to9; rejected | An improvement or a new shipped setting. Production remains max800. |
| [Actual upstream comparison](../benchmarks/review/pdf-upstream-all20/README.md) | Pinned Unstructured OSS evaluated on the same fixed20 source images under documented distinct profiles | General library ranking, commercial-service equivalence or identical OCR/preprocessing pipelines. |

The historical upstream reference remains0.27.27; later review observed0.27.28. The opt-in
freshness guard reported release drift, not a pass. Release notes must preserve that distinction
until a deliberate reference review. Do not silently replace historical comparison goldens.

Review source provenance and external-input redistribution terms before
distributing additional documents or benchmark excerpts.

## Checklist before designating a beta

- [x] Inventory supported/default/explicit behavior and known limitations in this draft.
- [x] Link scoped measurements, retained failures and negative experiments.
- [x] Confirm Java21 target and separate PDF/OCR/layout artifact boundaries in the current POMs.
- [x] Inspect local artifacts: core/PDF/layout base classes use major version65; ONNX artifact contains the existing Microsoft TATR notice.
- [ ] Review and record the beta scope, including which open quality gaps remain documented limitations. Drafting this checklist does not establish scope acceptance.
- [ ] Review public API/default/metadata/error contracts for the chosen version; define how beta compatibility changes will be communicated.
- [ ] Select the intended released module set and dependency closure. Integration/validation modules already declare deploy skip; verify that behavior in the release build. The all-formats artifact needs its format dependencies available at the same version.
- [ ] Run clean full Java21/25 verification and strict Javadocs on the actual candidate commit; separately run explicit native OCR/model checks with pinned resources. Retain reports and fresh CI results.
- [x] Smoke-test a standalone PDF-only consumer from locally installed snapshot artifacts on Java21/25; verify ServiceLoader discovery, native text and dependency isolation.
- [ ] Extend consumer checks to optional OCR/layout and representative supported/unsupported inputs from the actual candidate release artifacts.
- [ ] Review supported operating-system claims against actual adapter/platform evidence. Do not convert Linux native checks into cross-platform guarantees.
- [ ] Refresh official upstream drift review and decide how to handle the known reference gap without rewriting old evidence.
- [ ] Reconcile candidate release notes, README examples and this checklist against the exact tested defaults and artifacts.

## Publication prerequisites

Local audit: [repository-audit.json](../benchmarks/review/pdf-beta-readiness/repository-audit.json).
It records repository/POM inspection and cached artifact hashes, not a publication dry run. The later
[license/metadata verification](../benchmarks/review/release-metadata/README.md) records the
project license, added URLs, inherited metadata and rebuilt artifact checks.
[Local archive/consumer verification](../benchmarks/review/release-artifacts/README.md) covers
the later source/Javadoc attachment profile.

- [x] Declare Apache License2.0 in the root LICENSE/POM and package META-INF/LICENSE in JAR modules.
- [x] Inventory the resolved25-artifact runtime union and embedded notices; package the attribution guide and known missing license texts, with Java21/25 archive checks. See [the scoped review](../benchmarks/review/third-party-audit/README.md).
- [ ] Review repository evidence/excerpts and external-input rights before public source distribution; a runtime metadata inventory is not blanket redistribution clearance.
- [x] Add the project URL and SCM metadata; preserve the same repository URL in child POMs.
- [x] Declare developer name Praveen Govindan and GitHub identity praveenpg, without an email address. Name/description/coordinates already exist. Central requires these metadata categories, license information and source/Javadoc artifacts for ordinary JARs. [Central requirements](https://central.sonatype.org/publish/requirements/)
- [x] Configure and verify opt-in source/Javadoc attachment for all library modules using release-artifacts.
- [ ] Configure and verify signatures and checksums for the actual release bundle (opt-in signing and local digest rehearsal are available; real signatures/final bundle remain pending). [Central requirements](https://central.sonatype.org/publish/requirements/)
- [x] The `io.docweave` namespace status was recorded as **Verified** in Central Portal on2026-10-18; this is not a fresh authenticated Portal check. [Namespace registration](https://central.sonatype.org/register/namespace/)
- [ ] Configure and locally review a release profile/bundle; publishing-plugin configuration does not generate all required artifacts by itself. [Maven publishing guide](https://central.sonatype.org/publish/publish-portal-maven/)
- [ ] Select a release version and review the exact signed artifact set before any upload.
- [ ] Review repository visibility and approve the release before publication. Scope review, tagging, upload and publication are separate release steps.

## Current release status

The2026-10-18 registration record states beta0.1.0-beta.1 passed Portal validation and entered
PUBLISHING. The recorded namespace status is Verified, and public-key retrieval/signature
verification succeeded. Earlier unchecked release-preparation entries below/above retain
their draft scope; see [current publishing status](publishing.md) for later completed stages.
The broader PDF functional acceptance gates remain open.

## Decision recorded by this draft

The implementation is a candidate for evaluation as a documented PDF beta. It is not ready
for Central publication with the remaining metadata and artifact-packaging gaps, and the broader PDF completion
gates remain open. Creating this checklist does not increase extraction completeness.
The approximate85–90% figure is only feature-planning context, not an accuracy or readiness score.
