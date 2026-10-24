# Implementation backlog

For product direction and delivery priorities, see the [DocWeave roadmap](product-roadmap.md).
This file retains earlier implementation notes and planning context; current task dispositions
are recorded in [pending work](pending-work.md).

Current work dispositions are consolidated in [pending work](pending-work.md), including parked
PDF improvements and completed items. The [dated deferred-functionality audit](deferred-functionality-audit.md)
is retained as historical evidence; its earlier pending states do not override that index.
Durable validation is migrating to a test-only Java module: DOCX ownership is now
Java/JUnit, including real extraction and historical-summary checks. Saved Word
evidence replay/comparison is also Java/JUnit with raw evidence pins and historical
difference parity. Corpus inventory/legacy review and conditional-style audits are also Java/JUnit. Follow-up/heading fixture generation and structural tests are Java/JUnit as well. Saved-output comparison is Java/JUnit too; remaining Python tests guard actual upstream generation. [Upstream tracking](upstream-tracking.md) confirms the
released reference pin is current and separately records five unreleased main commits. A scoped HTML unknown-wrapper/content-policy fix is implemented; caption/math/DOM differences remain explicit.
Explicit DOCX symbol/import omission diagnostics are now implemented from owned local probes.
The [nested-table contract](nested-table-contract.md) and owned baseline fixtures are ready.
Shared preservation options/budgets, descriptor validation, HTML capture and chunk safeguards are
implemented. Markdown DOM delegation with AST/missing-provider/capability guards is implemented. DOCX preservation is also implemented. Owned cross-format corpus and resource/offset boundary coverage are now expanded. The [allocation/retention profile](../benchmarks/nested-table-memory.md) is complete. Absent/empty HTML spans now bypass numeric parsing; fresh pinned-input measurements confirm reduced
preservation allocation. Inline Unicode append allocation is now isolated and reduced using source UTF-16 slices. Table escaping source slices are implemented and measured. Next broaden independently sourced DOCX
corpus/fidelity coverage beyond the existing template family, retaining provenance and default policies.
Two [official consultation forms](../benchmarks/corpus/docx-forms/README.md) now add different publishers/templates,
stored controls and header/footer variants with passing candidate-only paragraph diagnostics. Four [independently maintained complex samples](../benchmarks/corpus/docx-complex/README.md) now add
nested tables (depths4/2), a main-body textbox and inserted/deleted text with passing ownership,
limited deletion exclusion and opt-in preservation checks. The [omission audit](../benchmarks/review/docx-omission-audit/README.md) attributes their six omission warnings to five textless VML horizontal rules and a missing explicit table-style definition; owned regressions retain those diagnostics. These are external regression fixtures;
representative published-document/authoring-application coverage and human review remain open. An opt-in
[ownership-v1 baseline](../benchmarks/review/docx-paragraph-ownership/README.md) now checks
disjoint paragraph occurrences, body owner order/type and text-box parent links in a documented subset; independent human
rendering review and upstream comparisons remain separate gaps. Unsupported formats/modes reject preservation explicitly. The broader categories below remain long-term planning context.

## Implemented foundations — feature coverage remains incomplete

- Java 21+ Maven build, immutable metadata, compatibility JSON codec.
- Basic/by-title character chunking, text-only table splitting, and structural HTML row/cell/text splitting.
- Ordered literal custom text separators with Unicode fallback and visible-delimiter retention.
- Configurable table isolation, mixed text/table composites, structural singleton handling and validated pass-through conflicts.
- Strict structural text/HTML budgets, source-cell ownership metadata, header repetition and exact table pass-through.
- 41 real upstream comparisons on supported Python 3.13: 32 exact normalized matches, nine documented differences, source-hash checks and dependency provenance.
- Bounded virtual-thread file batches and failure/interruption tests.
- Opt-in per-task/overall batch deadlines with typed causes, independent failure observation and nonblocking cancellation requests.
- TXT/HTML native partitioners, stable IDs, heading/list hierarchy, tables and Unicode link offsets.
- Opt-in automatic TXT grouping and Unicode min/max partition sizing with structural merge barriers.
- Source detection, strict encoding, typed diagnostics/errors, bounded document batches.
- Public service/builder interfaces, hidden implementations and doclint-enforced Javadocs.
- Injectable partitioner strategy registry and shared document-confined emission context.
- CommonMark AST extraction, opt-in combined Markdown/HTML DOM extraction, and CSV/TSV quoted-record tables with typed policies and shared table budgets.
- Secure streaming XML, namespace-aware metadata, text-order extraction and typed depth/node limits.
- DOCX source-order paragraphs/tables, heading/list hierarchy, Unicode links, merged cells and bounded package preflight.
- Two synthetic native DOCX references and two published report comparisons, with verified pinned NLP model, input hashes and explicit diagnostic/review limits.
- Three native HTML/Markdown references with shared semantic checks and explicit dialect/visibility/metadata differences.
- Eight native TXT/CSV/TSV runs with baseline text matches and explicit grouping/size/header/string/MIME differences.
- Five native XML runs with ordered-text/namespace/attribute checks and explicit XPath/raw-tag/entity-policy boundaries. Initial native evidence now covers all seven implemented formats.
- DOCX notes/comments, text boxes, image references/optional bounded bytes, structured controls/cells/grid gaps, nonempty/legacy merges, nested links, stored revisions, cached fields and common numbering metadata.

## PDF implementation and deferred improvements

The current PDF implementation includes native text, table extraction, source images, optional
OCR/layout providers, rendered page crops and explicit reading-order policies. The latest
reviewed implementation passes 1,461 Java 21 tests. This establishes the recorded regressions,
not universal extraction accuracy or full upstream parity.

Remaining PDF improvements, independent qualification and other deferred capabilities are
recorded in the [complete backlog](pending-work.md). All unresolved items are deferred for
later selection; no further PDF experiments or feature expansion are active. Preserve the
existing regression suites and failed evidence when work resumes. Further DOCX and other
format enhancements also remain deferred until a new task is selected.

## High-priority TODO: cross-format contextual chunk enrichment

- [x] Add optional contextual chunking across supported file types through the shared
  chunking pipeline. This is a cross-format capability, including PDF, DOCX, HTML,
  Markdown and plain text, with suitable handling for structured/table content.
  The explicit post-chunk service is implemented; the remaining PDF quality gates are retained.
- [x] Expose an interface-based context provider that can use a caller-selected hosted
  or local LLM to generate concise, document-aware context for each chunk. Apply it
  after existing chunk splitting; avoid coupling the feature to individual partitioners
  or requiring a particular model vendor.
- [x] Preserve original chunk text, source identifiers and provenance separately from
  generated context. Offer an explicit contextualized representation for embedding;
  record generated-context provenance and keep enrichment opt-in.
- [x] Define bounded document/context inputs, concurrency, cancellation, timeouts and
  explicit failure behavior. Document model cost, privacy and context-window limits.
- [x] Add JVM-native cross-format tests for provider behavior, source preservation and cancellation.
- [x] Add JVM replay/capture evaluation against unenriched chunks, with frozen identities and failure denominators.
- [ ] Extend the tiny owned live-model integration check to an independently labeled corpus before general quality claims.

Reference: [Unstructured Platform contextual chunking](https://unstructured.io/blog/contextual-chunking-in-unstructured-platform-boost-your-rag-retrieval-accuracy).
The [explicit shared service](contextual-chunking.md)
is implemented in the snapshot; a three-chunk/five-query live-provider check passes.
Larger independent retrieval evaluation remains open.

## Retained backlog: close gaps in implemented capabilities

A format being available does not mean its upstream feature set is complete. Passing tests validate
implemented contracts; extraction parity and omitted-content handling require additional evidence.

1. Expand structural-table upstream comparisons and reviewed real-world corpus, particularly header
   policies, strict HTML-budget segmentation and span reconstruction. Structural splitting,
   repeated headers and skip-table-chunking are implemented; full upstream parity is unverified.
2. DOCX validation/finer fidelity: expand real-world and upstream comparisons for supplemental
   content, complex revision/merge interactions, independent Word-authored/rendered conditional-style validation and complex conflicting style overrides, international
   numbering and picture/font bullets. Notes/comments/images/text boxes, structured controls/cells,
   gaps, legacy/nonempty merges, nested links and explicit field/revision policies are implemented.
3. Existing text-format gaps: Markdown DOM fidelity/reference comparisons and extension expansion;
   TXT grouping/classification comparisons against upstream and reviewed documents;
   review nested HTML table fidelity.
4. Remaining chunk policies: token-based limits after choosing a pinned tokenizer; expand option
   comparisons beyond the current synthetic separator/isolation cases.
5. Compatibility evidence: expand the two published DOCX report comparisons across publishers and
   authoring tools, obtain independent human review, and broaden native-parser references beyond
   the initial synthetic cases across all seven implemented formats. Chunker references now run
   on supported Python 3.13 with selected-source verification.
6. Operational gaps: further per-document lower-memory processing and broader cancellation coverage; decoded Text loading now avoids full UTF-8 round trips, TXT discovers lines lazily, and stable-file loading avoids growth/final-copy arrays, and verified ASCII UTF-8 decoding avoids a full intermediate character buffer; retain interface-based public
   services, explicit unsupported behavior and strict Javadocs for every addition.

Keep intentional boundaries separate: generic XML has no universal heading semantics; DOCX does
not provide reliable rendered pagination; external entity/resource fetching is excluded. These are
not defects to fix by inventing metadata or enabling external access.

## Subsequent format expansion

PPTX/XLSX remain future work. PDF is the current development priority; its initial native module is
implemented alongside optional managed OCR and local ONNX layout/table providers. Broader
quality gates remain in [PDF support](pdf-support.md#remaining-pdf-quality-gates). The shared oversized-table
chunking blocker is resolved.

## Parsing sequence

1. TXT and HTML foundation complete; continue quality/compatibility improvements.
2. Markdown, CSV/TSV and secure generic XML foundations complete.
3. Office: DOCX foundation complete. Continue with PPTX/XLSX using Apache POI; keep slide/sheet metadata explicit and do not infer DOCX pagination.
4. PDF: native PDFBox foundation, opt-in paragraphs and fixed two-column order implemented; quarter-turn page/text normalization implemented; broaden layout/table fidelity and independent validation.
5. OCR: provider interfaces, managed Tesseract processes, explicit languages, timeout and coordinate conversion implemented; broaden independent historical/degraded-scan quality evidence.
6. Layout: optional local ONNX preprocessing/postprocessing, OCR/native ownership and table reconstruction implemented; complete the retained original-document fidelity and independent acceptance gates.

## Packaging and operations

- [ ] Maintain reviewed source synchronization and preserve incoming contributions.
  Align future release SCM links and tags with the public source revision.

Maven coordinates now use `io.docweave:docweave`, based on the `docweave.io` domain. Local beta.8 migrates Java packages from `dev.docweave` to `io.docweave`; consumers must update imports and rebuild (see [migration guidance](package-migration.md)). The project uses Apache License2.0; namespace registration and published versions are documented in the release guides. Core, each implemented format, JSON, chunking and batch services are separate Maven artifacts; all-formats convenience and integration-test modules are implemented. Optional OCR and layout modules are implemented; a CLI remains future work. Publish only after an explicit release decision. Keep service frameworks outside core. Extend current native diagnostics with PDF/OCR strategies, finer metrics and lower-memory processing. Bounded saved-element JSON reads, optional batch execution deadlines, bounded incremental result delivery and finer chunking/native traversal cancellation checkpoints, shared Unicode scan checks and lazy TXT line discovery are implemented; direct synchronous operations remain untimed.

## Corpus growth

Expand from synthetic fixtures to 200–500 permitted documents: multi-column PDFs, mixed scans/native pages, rotated text, multilingual material, merged-cell tables, headers/footers, missing fonts, malformed inputs, large workbooks, nested lists, and attachments. Maintain a separate held-out human-reviewed set.

Independent DOCX semantic-style validation now has a [six-case Word review packet](../benchmarks/review/word-style-cases/README.md). Word observations have been collected; all16 comparator-supported cases match the current implementation and four heading controls have policy regressions. Human review remains pending. Saved evidence is rechecked in private CI. A bounded seven-file external search found no conditional numbering/outline definitions.
