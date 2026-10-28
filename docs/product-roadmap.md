# DocWeave roadmap

DocWeave is a modular Java library for turning documents into structured elements and useful
chunks. Its direction is to improve document fidelity, make extraction behavior predictable,
and support retrieval applications without tying the core library to a service framework or
model provider.

Updated **23 October 2026**. This roadmap describes product direction, not promised release
dates or completion percentages. Capabilities listed as available refer to the current source;
check the [release notes](releases/README.md) for availability in a particular Maven version.

## Where the project stands

| Area | Available foundation |
| --- | --- |
| Native formats | TXT, HTML, Markdown, CSV, TSV, XML, DOCX and PDF partitioning, with format-specific support boundaries |
| Structured output | Immutable elements and metadata, source identifiers, typed diagnostics and compatibility JSON serialization |
| PDF processing | Native text and tables, source-image extraction, rendered page-region crops and opt-in reading-order policies |
| Optional PDF providers | Managed Tesseract OCR and local ONNX layout/table adapters with explicit configuration and resource limits |
| Chunking | Basic and title-aware strategies, character budgets, overlap, structural table splitting and original-element provenance |
| Contextual enrichment | A shared post-chunk service with caller-provided models, separate original/generated content and bounded execution |
| Batch processing | Ordered document batches using bounded virtual-thread concurrency, deadlines and cancellation |
| Packaging | Java 21+, separate Maven artifacts for core, formats and optional services, interface-based APIs and extensive Javadocs; local beta.8 uses io.docweave Java packages |

Availability does not imply complete format fidelity or universal Unstructured parity. The
[PDF comparison](pdf-comparison.md), [DOCX comparison](docx-comparison.md) and other support
documents record measured behavior and limitations. The existing regression baseline is not
an extraction-accuracy percentage.

## Delivery priorities

The sequence below provides a planning order when development resumes. Remaining extraction
improvements and qualification work are currently deferred; publishing this roadmap does not
restart investigations or imply every item is scheduled. The [pending-work index](pending-work.md)
retains the exact status, prerequisites and acceptance boundaries of individual tasks.

### 1. Maintain a dependable beta and contributor experience

Keep installation, examples, public APIs and release behavior consistent as the library evolves.

- Keep Maven modules independently usable, with optional OCR, layout and model dependencies
  outside the core and ordinary format modules.
- Maintain clear getting-started, Spring Boot, OCR, chunking and contextual-enrichment examples.
- Document defaults, unsupported behavior, resource limits and opt-in policies alongside APIs.
- Publish useful reviewed additions with versioned notes, matching source tags and reproducible
  artifacts; preserve existing published coordinates and compatibility information.
- Keep public source and accepted development changes synchronized while preserving incoming
  contributions and excluding private operational material.

**Completion criteria for each release:** documented scope and upgrade impact, relevant Java 21
checks, packaged public-API consumer checks, matching source/version/tag/release notes, and
signed artifacts validated before publication. Additional runtime qualification follows the
release scope; an open research item is not silently declared complete to ship a beta.

### 2. Improve extraction fidelity in supported formats

Prioritize demonstrated defects and independently supported improvements over additional
format-specific heuristics without transferable evidence.

| Focus | Concrete next work | Evidence required |
| --- | --- | --- |
| Scientific PDF reading order | Define the intended ordering of remaining front matter, sidebars and paragraph boundaries before another ordering rule | Owned regressions plus transfer cases; retain characters, correct ordering relationships and existing policy behavior |
| Business PDF tables | Evaluate detector coverage and table ownership on exhaustively labelled financial-report pages, including negative pages | Tight table/cell references, false-positive and false-negative reporting, and producer transfer; distinguish detection failures from reconstruction defects |
| Historical and degraded scans | Evaluate remaining segmentation, contrast and colour-profile cases on suitable original scanned PDFs | Independent line references, explicit OCR/model settings and negative controls; retain unsuccessful evidence and report recognition/order trade-offs |
| DOCX fidelity | Broaden numbering, styles, revisions, supplemental content and nested-table coverage across authoring tools | Independent source or rendering evidence, element ownership checks and explicit diagnostics for unsupported cases |
| HTML, Markdown and TXT | Address specific nested-table, DOM/extension and grouping/classification mismatches | Pinned source examples, focused regressions and unchanged security/visibility boundaries |

**Completion criteria for an improvement:** a reproducible failure, a documented rule, a measured
gain on the stated scope, regression and transfer controls, and clear limitations. Preserve
defaults unless a compatibility change is intentional and documented. Do not repeatedly tune
the same failed approach without a distinct evidence-supported mechanism.

Source-image extraction and final rendered page appearance remain separate contracts. Exact
isolated-image visibility or compositing needs an explicit consumer contract before any new API;
rendering differences alone do not establish an extraction defect.

### 3. Strengthen chunking and retrieval integration

Character-based chunking and contextual enrichment already exist. The next work expands their
contracts and evidence rather than rebuilding them.

- Add optional token-budget chunking with a pinned tokenizer, an explicit dependency boundary,
  and documented overlap and table behavior.
- Broaden structural-table comparisons for merged spans, header repetition and strict HTML budgets.
- Evaluate contextual enrichment on independently labelled retrieval data beyond the small
  integration checks already recorded.
- Preserve original text, identifiers and provenance separately from generated context, with
  explicit provider selection, deadlines, concurrency and failure policy.

**Completion criteria:** deterministic tokenizer/version behavior, boundary and Unicode coverage,
preserved table/text ownership, and reproducible retrieval comparisons with declared datasets and
denominators. Do not make general retrieval-quality claims from a small model demonstration.

### 4. Expand format coverage through separate modules

| Candidate | Initial contract to define | Implementation boundary |
| --- | --- | --- |
| PPTX | Slide order, titles, text, tables, images and slide metadata | Separate partitioner module; explicit handling of charts, notes, groups and unsupported content |
| XLSX | Sheet/cell order, types, table structure, merged cells and formula-value policy | Separate module with bounded workbook traversal; no implicit formula execution |
| Email, EPUB and RTF | Content ordering, attachments or embedded resources, and source provenance | Each format needs its own contract, permitted fixtures and typed unsupported behavior |
| Legacy Office formats | Conversion requirements, fidelity boundaries and optional dependencies | Evaluate separately from native modern Office parsing; no hidden external-tool installation |

PPTX and XLSX are the first planned format additions. The remaining candidates are longer-term
options, not incomplete implementations of currently supported partitioners.

**Completion criteria per format:** public service/SPI integration, a separately consumable artifact,
bounded parsing, secure external-resource handling, meaningful fixtures and documented omissions.

### 5. Improve tooling and operational predictability

- Design an optional CLI over the existing public services, with stable JSON/error output and
  explicit provider selection.
- Profile representative large documents and improve allocation, retained memory or cancellation
  only where measurements justify the change.
- Broaden packaged workload qualification for latency, memory, concurrency and interruption.
- Local beta.8 package migration from `dev.docweave` to `io.docweave` is completed and installed
  locally: Java 21 verification passes 1,461 tests, strict Javadocs and standalone packaged-consumer
  checks. The parent/core artifacts are published; remaining beta.8 modules are local. [Migration guidance](package-migration.md) covers
  imports and custom SPI providers. JPMS remains future work; no module descriptors are introduced.
- Consider optional URL/source adapters without enabling implicit resource fetching in parsers.

**Completion criteria:** standalone consumer checks, documented resource accounting and limits,
preserved immutable ownership, typed errors and no new framework dependencies in core. Process
measurements on one host do not establish a universal memory bound.

## Cross-cutting quality commitments

- **Public contracts:** interfaces for services, hidden implementations, immutable values and
  comprehensive Javadocs. Compatibility and default changes must be explicit.
- **Java baseline:** Java 21 without preview features; use virtual threads where bounded blocking
  concurrency benefits from them. Additional JDK validation belongs to appropriate release gates.
- **Tests and comparisons:** JVM-native library tests and evaluation. Python remains appropriate
  for running Unstructured reference extraction and its generator guards.
- **Reference clarity:** pin the evaluated Unstructured version/configuration and distinguish
  released references, execution environments and unreleased source changes.
- **Qualification:** expand independently labelled documents and held-out cases when appropriate
  originals, redistribution rights and labels are available. Do not relabel development data as held-out.
- **Provider boundaries:** external OCR/models stay optional and explicit; no automatic downloads,
  silent provider substitution or unexpected model/network calls.
- **Honest results:** publish scoped measurements, diagnostics and unresolved cases. A passing
  regression suite is not a universal fidelity, visibility or accuracy guarantee.

## DocWeave and Studio

The library remains usable without a running service, database or web UI. **DocWeave Studio** is a
separate application for workflow editing, job management and model configuration; its authentication,
workspaces, UI and connector orchestration have their own roadmap. Those application concerns do not
add Spring Boot or React dependencies to the library.

## Detailed planning and contribution entry points

- [Pending work](pending-work.md): concrete remaining tasks, parked improvements and qualification prerequisites.
- [Implementation backlog](roadmap.md): earlier detailed implementation notes and planning context.
- [PDF support](pdf-support.md) and [PDF evaluation scope](pdf-evaluation-scope.md): current contracts and evidence boundaries.
- [Contextual enrichment](contextual-chunking.md): the implemented cross-format service and its limitations.
- [Modules](modules.md), [compatibility](compatibility.md) and [release notes](releases/README.md): integration and version-specific behavior.

Start a proposed contribution with the affected contract, a representative input or owned fixture,
expected output and focused acceptance criteria. This keeps feature work reviewable without
weakening existing evidence or silently expanding scope.
