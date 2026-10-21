# Public API conventions

Expose processing services and mutable builders as interfaces. Static `create()` factories return the built-in package-private implementations. Application code should depend on contracts such as `DocWeave`, `Chunker`, `ElementJson` and `DocumentBatch`; concrete implementations are internal details.

Keep immutable records and enums public for values: sources, elements, options, diagnostics and results. Checked exceptions remain classes because Java exceptions must extend Throwable. Nested source records are permitted implementations of the sealed `DocumentSource` value contract. Collection/byte-array inputs must be snapshotted defensively.

Document every public type, factory and method with meaningful Javadocs. Include behavior, defaults, parameter/return meanings, validation, exceptions, thread safety, resource limits and compatibility differences. Use examples for entry points. Do not claim upstream parity where only heuristic behavior is implemented.

`mvn verify` generates API HTML with `-Xdoclint:all` and fails on warnings/errors. Public-service/interface visibility has a regression test. Add documentation checks alongside implementation changes rather than leaving them to publication.

The built-in services are reusable across calls; builders are mutable and caller-owned. Per-document parser state is isolated. Virtual threads handle document tasks containing blocking I/O, while their outstanding-task window bounds CPU/memory pressure. Cancellation requires cooperative interruption. Integer-window/zero-deadline calls observe failures in input order; optional deadline calls observe worker failures/timeouts independently.

## Runtime parser strategy contract

Every format parser implements `Partitioner`. Its format key is stable and its implementation must be stateless or thread-safe. `NativePartitionEngine` resolves a strategy from an injected `PartitionerRegistry` for each invocation. Adding/replacing a parser strategy does not add a parser-construction branch to the engine.

```mermaid
flowchart LR
    Source[Local source] --> Loader[Bounded loading and decoding]
    Loader --> Registry[PartitionerRegistry lookup]
    Registry --> Parser[Partitioner strategy]
    Parser --> Context[PartitionContext emission]
    Context --> Result[Elements and diagnostics]
```

`PartitionContext` is a synchronous document-confined emission interface. It supplies decoded text or defensive binary snapshots/options and enforces common element metadata/IDs, output budgets and interruption. Parsers can refer to earlier emitted elements for hierarchy and add warnings. Emissions after completion fail. Filename/filetype are reserved source fields; custom metadata cannot override them.

The registry builder includes native TXT/HTML/Markdown/CSV/TSV/XML/DOCX strategies; registering the same format intentionally replaces its strategy. Built registries snapshot builder state. `of(...)` creates a registry with exactly the supplied strategies and rejects duplicate keys. Runtime lookup can be implemented dynamically by a custom registry as long as its capabilities and returned strategies remain consistent.

HTML DOM walker state is created per invocation. It is an implementation helper, while `HtmlPartitioner` itself is the stateless strategy. `TxtPartitioner` keeps all state local to its method. Batch services accept an injected processor/chunker so the same strategy configuration is used for concurrent raw-file processing.

The current format value model and detector cover TXT, HTML, Markdown, CSV, TSV, XML and DOCX. New format support will extend that model/detector alongside a strategy; the current registry is not a general string-keyed MIME plugin system.

This interface/factory migration intentionally replaces earlier prototype constructors such as `new Chunker()` with `Chunker.create()`. There is no published compatibility guarantee for those prototype APIs.

## DOCX composition

The hidden `DocxPartitioner` composes `DocxArchiveVerifier` and a document-local POI reader/extractor. Archive verification owns expansion/XML budgets; `DocxParagraphPolicyResolver` interprets inherited styles; `DocxParagraphText` owns normalization/link offsets; `DocxTableExtractor` owns cell structure; `DocxBodyExtractor` owns source-order dispatch and heading/list hierarchy. POI types do not appear in public contracts. `DocxOptions` is an immutable value record.

`PartitionContext.text()` is available for decoded text sources; `binaryContent()` supplies a defensive copy for binary sources. Accessing the wrong payload kind fails explicitly. The default binary accessor keeps existing text-only custom contexts source-compatible. Loading and registry dispatch stay independent of extraction. Unsupported future formats still require format-model/detector support.

## Structural-table chunking composition

`Chunker` and `ChunkOptions.Builder` remain public interfaces with hidden implementations. Immutable options add repeatTableHeaders/skipTableChunking. The hidden `HtmlTableModel` validates geometry and owns normalized source cells; immutable `TableFragment` synchronizes text, HTML and source-coordinate metadata; per-call `StructuredTableSplitter` handles packing and fallback boundaries. `DefaultChunker` retains staging, metadata/provenance and element emission. No mutable table state is shared across calls, and no partition-format types leak into the chunking model.

## DOCX supplemental content

`DocxContentOptions` is an immutable policy value, reached through `DocxOptions.withContentOptions(...)`; the original seven-argument DocxOptions constructor remains available with content defaults. Public service APIs remain interfaces and POI types stay internal. `DocxBodyView` composes ordered typed views from stored XML (including content controls/revisions); `DocxParagraphText` owns normalized inline text and reference/drawing anchors; `DocxTextAccumulator` shifts offsets when cells/merges/nested tables combine; `DocxImageExtractor` bounds optional package bytes without external fetching; `DocxNumberingResolver` owns stored-part-local counters/label formatting. `DocxBodyExtractor` alone emits semantic elements and reference relationships. All mutable state is document-confined.

## Markdown extraction policy

`MarkdownOptions.RawHtml` selects the default AST extraction (`PRESERVE_TEXT` or `SKIP`)
or opt-in combined DOM extraction (`EXTRACT_DOM`). The latter renders the already depth-checked
CommonMark tree, including optional pipe tables and raw HTML, then reuses the document-local HTML
walker. It keeps the original `PartitionContext`, so IDs, source MIME/filename, output limits and
cancellation remain centralized. No new public parser service or mutable shared parser state is
introduced. HTML visibility/depth/header policies apply, as do Markdown table budgets to both
rendered and raw tables, including nested tables before flattening. DOM-mode metadata follows the
HTML contract; AST-only image references, code-info and alignment metadata are omitted.

## TXT grouping and sizing

The stateless TXT partitioner composes document-local `TxtParagraphGrouper` and
`TxtPartitionSizer` stages. Grouping owns line/paragraph boundaries; sizing owns classification,
bullet removal, soft minimum merging and hard Unicode maximum splitting. Both emit through the
existing context, preserving central IDs, element limits and interruption checks. `TxtOptions` is
an immutable value policy exposed by the existing interface-based options builder. The previous
`PartitionOptions` constructor delegates to default TXT sizes for source compatibility. Defaults
preserve historical boundaries. Automatic grouping and minimum merges are documented heuristics,
not guarantees of rendered structure or upstream NLP parity.

## Custom chunk boundaries

`ChunkOptions.Builder.textSplittingSeparators(List<String>)` snapshots an ordered literal policy.
The immutable record copies and validates direct-constructor lists too; the original constructor
retains newline/space defaults. Hidden `TextChunkSplitter` owns oversized text splitting for both
chunking strategies and text-only table fallback, keeping staging and structural-table policies
separate. Visible delimiters remain within the left fragment's code-point budget; whitespace
delimiters retain existing consumption/overlap behavior. Structural table splitting does not use
this policy. No public implementation class or external regex engine is introduced.

## Table isolation configuration

`ChunkOptions.Builder.isolateTable(false)` relaxes staging and by-title combining barriers. The
existing immutable options record retains both prior constructor shapes with isolation enabled.
A group with exactly one table still uses structural processing; a mixed group uses the existing
composite path. Metadata consolidation drops table geometry/HTML/extraction-method fields from
mixed groups, with original elements retaining source structure when provenance is enabled.
Overlap prefixes are cleared when a table enters a pending group or when combining creates a
table-bearing group; outgoing overlap is also suppressed. Pass-through conflicts are rejected
in the value constructor, making both builders and direct construction enforce the same contract.

## Batch deadline policy

`BatchOptions` is an immutable value record (window, task duration, batch duration). Services remain
interfaces with hidden implementations; `BatchTimeoutException` is the public typed failure cause,
with scope, input index and configured budget. Existing method signatures remain available. New
default interface overloads delegate zero-deadline policies to existing implementations and reject
enabled deadlines unless implemented, preserving source compatibility without silently dropping
budgets. Built-in raw-document, element-file and generic batches implement the overloads.

Legacy calls retain their close-and-wait executor behavior. Enabled budgets select a hidden
per-invocation `DeadlineBatchRunner`: bounded virtual-thread submissions, input-order result futures,
a separate first-failure signal and a single daemon platform timer. Each task schedules its alarm
from worker entry; the batch alarm starts after validation/snapshotting. Completed task alarms are
cancelled/removed, and a monotonic elapsed check also prevents late successful results from hiding
a task deadline when the timer is delayed. Concurrent failure/deadline races report the first
observed signal; this is not a deterministic ordering among simultaneous failures.

Timed failure/interruption cancels pending futures and requests shutdown without closing/waiting
indefinitely. Operations ignoring interruption can outlive the call; virtual threads cannot safely
be forcibly stopped, resources may remain held, and side effects are not rolled back. The window
is per invocation, not a global limit across retained noncooperative operations. Results remain
staged in memory. Task budgets span every stage of the operation, not separate resetting budgets
for loading/partitioning/chunking. Snapshot/validation work is outside the overall budget. Direct
synchronous partition/chunk calls remain untimed.


### Incremental batch delivery

Interface methods `BatchProcessor.forEachOrdered`, `DocumentBatch.partitionAndChunkEach` and
`ElementFileBatch.chunkEach` provide lazy, bounded input-order delivery on the caller thread.
The hidden runner reuses virtual-thread task/deadline machinery without accumulating outputs.
The caller owns and closes input sources; callbacks are sequential and provide backpressure.
Delivered prefixes are not rolled back. Failures are independently observed even without deadlines;
cleanup interrupts without indefinite waiting. Blocking caller-side iteration/delivery can delay
batch deadline observation. Per-document materialization remains. This is a local JVM execution
contract, not upstream streaming/timing parity. Existing custom implementations must opt in by
overriding the new default methods, which explicitly reject unsupported streaming.


### Cooperative processing checkpoints

Chunking uses a hidden package-local cancellation helper, avoiding checked exceptions on the public
Chunker interface. It throws standard CancellationException while preserving the flag. Checks cover
entry/return, staging/combining, fragment emission, literal splitting, Unicode normalization/trim,
metadata consolidation and structural table loops. Unicode normalization scans code points directly
instead of allocating a complete code-point array; it polls every 1,024 visited code points.
Native partitioning retains its existing InterruptedIOException contract and adds checks around source
and third-party parser boundaries, Word inline text and image-description traversal. External library
calls and bulk operations remain indivisible; these checkpoints do not guarantee cancellation latency.


### Saved-element JSON resource policies

`JsonReadOptions` is an immutable value record accepted by interface factories `ElementJson.create`
and `ElementFileBatch.create`. Default reads now enforce 16 MiB encoded bytes, 100,000 elements,
container depth 128 and 16 Mi UTF-16 units per string. This intentionally rejects formerly unbounded
saved inputs; callers can select larger explicit limits. Native PartitionOptions remain independent.
The hidden bounded-input helper checks actual reads rather than trusting file size alone. Jackson
is configured per codec without global state and decodes one element object at a time; root arrays,
exact supported fields, metadata shapes, strict file UTF-8 and rejection of trailing documents remain.
Lists and individual metadata snapshots still materialize. Byte/count errors are IllegalArgumentException;
parser constraints use Jackson exceptions. File interruption becomes InterruptedIOException, string
interruption CancellationException, with flags preserved. Writes and fixed provenance policies are
separate; an unbounded write need not fit default read limits. No upstream resource-policy parity
or heap/time bound is claimed.


### DOCX numbering definition lookup

DocxNumberingDefinitions separates per-document cached definition lookup from DocxNumberingResolver's
stored-part-local counters and DocxParagraphPolicyResolver's paragraph inheritance. Link resolution is
iterative, cycle-safe and interruptible, capped at 128 hops per reference/inheritance chain. Effective
levels apply inner then outer instance overrides; pStyle matching chooses the closest paragraph style.
No new public implementation classes or mutable service state are exposed. Heading and list emissions
share numbering metadata generation; unresolved references retain text and surface explicit diagnostics.


### Default paragraph-style selection

DocxParagraphPolicyResolver now caches the declared default paragraph style and effective numbering
definitions per document view, and is reused by body and table extraction instead of recreated per
paragraph. It selects only absent pStyle references, preserving explicit references and direct
formatting precedence. The hidden Policy value records whether selection was implicit; emission
exposes that distinction through docx_style_source=default. Flag scanning checks interruption,
commits the cache only after completion, and synthesizes no default by name. Cyclic basedOn chains
terminate using the existing visited-style set. No public service implementation is exposed.


### Document-default paragraph properties

The hidden paragraph policy resolver caches a read-only reference to existing docDefaults/pPrDefault
paragraph properties. It fills only unresolved outline/numbering values after direct/named style
resolution, then lets higher-precedence style-level associations replace the base list level. It
never invokes POI getters that synthesize absent document defaults. Policy records carry an immutable
list of effective source property names for body/table emission as docx_document_defaults. This
preserves separate responsibilities for inheritance, counter progression and semantic emission,
without introducing another public implementation API. Only outlineLvl, numId and ilvl are consumed.


### Table-style semantic paragraph layers

The hidden DocxTableParagraphStyles resolves selected/default table styles independently of paragraph
inheritance, counter state and cell geometry. It returns immutable ordered layer lists; missing,
wrong-type, cyclic or overlong references return no partial layers. Cell extraction passes layers to
the existing paragraph policy resolver between named styles and document defaults. The resolver
tracks effective table-source properties separately from docDefaults, clearing base-level attribution
when a higher paragraph-style association wins. Nested extraction resolves each table independently.
Conditional XML is inspected through DOM because POI lite omits the conditional type implementation;
existing typed-copy support reads wholeTable pPr without adding a full-schema dependency. Positional conditions are selected by the separate logical-region resolver. New source ranges use the
existing offset accumulator, and metadata consolidation removes those ranges from chunks while
preserving original-element provenance. Public service APIs remain unchanged interfaces.


### Conditional table region resolution

DocxTableRegions owns bounded preflight geometry and look/band/cnf membership; it has no semantic
emission or numbering-counter responsibility. Source rows/cells are adapted once and reused by region
preflight and extraction. Merge footprints are completed before paragraph processing. Region matching
is interruptible and selects immutable membership sets; the definition resolver orders active layers
independently of XML storage order. Paragraph inheritance remains unchanged above these layers.
Source ranges use stable ordered region names; unknown semantic condition types retain diagnostics.
All helpers are package-private and no new public service API is introduced.

Maven module boundaries now separate the JDK-only core, individual format strategies, JSON,
chunking and batch services. Public parser contracts and the module discovery SPI are interfaces;
actual parsers remain hidden. Small public no-argument provider bridges are required for JDK
ServiceLoader discovery. See [module architecture](modules.md) for dependency/loader boundaries,
optional Markdown-to-HTML service behavior and the current classpath-only/split-package limitation.

Deterministic native IDs are now generated by a hidden document-confined helper. Each document
lazily owns one reusable digest engine; there is no global or ThreadLocal digest pool. Identity
formatting preserves the original concatenated UTF-8 message and first32 lowercase SHA-256 hex
digits. Metadata still takes a deep immutable snapshot, and failed element validation leaves the
sequence unchanged. Separate document calls, including virtual-thread calls on a shared facade,
do not share mutable digest state.

Identity encoding now delegates to a hidden document-confined Utf8Digest. Large logical messages
stream through fixed character/byte buffers; the encoder carries surrogates across append boundaries.
A size-limited small-message path avoids scratch setup for short documents. Reset/finally handles
partial updates without poisoning a later identity. Ownership/concurrency/public APIs are unchanged.
