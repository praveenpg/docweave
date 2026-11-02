# 0001: Boundary of contextual enrichment

- **Status:** Accepted, option C (2026-11-01). Removal of the library API was approved directly on
  2026-11-01 (see "Amendment: direct removal"); this supersedes the deprecation period and the separate
  removal approval originally recorded below. No release is published by this record.
- **Date:** 2026-11-01; amended 2026-11-01
- **Related work:** windowed document context for large documents in DocWeave Studio, and the
  unmerged library change prepared for it (per-chunk contexts, a longer deadline ceiling, chunk
  overlap metadata).

## Question

Should contextual chunk enrichment remain part of the public DocWeave processing library? The
alternatives are to make it Studio-only, or to move it to a private optional module.

## Current state

Contextual enrichment first appeared in the unpublished 0.1.0-beta.7 candidate. It is published on
Maven Central in 0.1.0-beta.9 (30 October 2026) and 0.1.0-beta.10 (1 November 2026). It ships in
`io.docweave:docweave-chunking` and therefore in `io.docweave:docweave`.

It is a vendor-neutral post-chunking service of about 400 lines including Javadoc, about 220 of them
code:

- it validates every input before any provider call;
- it runs caller-supplied provider calls in a bounded virtual-thread window;
- it applies a whole-call deadline of at most one hour, cancellation and an explicit failure policy;
- it returns the generated context separately from the unchanged original chunk.

It adds no model client, credential handling, prompt, retry or cache. The caller owns all of those.

The service sends one shared document text with every chunk. Large documents need a bounded
context per chunk instead, which Studio's windowed-context work requires. Done in the library, that
work needs three changes:

- a per-chunk context entry point;
- a deadline ceiling above one hour;
- overlap metadata on chunks.

All three would need a library release before Studio could ship. They have been prepared on an
unmerged branch and are held until this decision is made; see "What happens to the prepared library
change".

How many people use the API outside Studio is unknown. Maven Central gives no reliable count of
consumers, and DocWeave Studio is the only known one.

## How Unstructured draws the line (primary sources)

- **Open-source chunking.** The
  [`unstructured/chunking`](https://github.com/Unstructured-IO/unstructured/tree/main/unstructured/chunking)
  package contains basic and by-title chunking only (`base.py`, `basic.py`, `dispatch.py`, `title.py`).
- **Embeddings.** The repository also ships embedding encoders for several providers
  (`unstructured/embed`). Its [overview](https://docs.unstructured.io/open-source/introduction/overview)
  nevertheless lists "No support for generating embeddings in the core Unstructured open source offering".
- **How the overview positions the library.** It calls the open-source library "designed as a starting
  point for quick prototyping and has limits". It lists "No support for Unstructured's enrichment types
  such as image descriptions, table descriptions, and named entity recognition (NER)", and lists advanced
  chunking strategies and built-in LLM integrations as Pipelines/API features (open source: No). Contextual chunking is not named
  in either list.
- **Where contextual chunking is documented.** It appears only as a Platform workflow chunker node,
  [`chunk_by_contextual`](https://docs.unstructured.io/api-reference/workflow/nodes/enhancement/chunker-chunk-by-contextual),
  with a per-chunk document window (`context_char_limit`, default 15,000 characters). Its
  [announcement](https://unstructured.io/blog/contextual-chunking-in-unstructured-platform-boost-your-rag-retrieval-accuracy)
  presents it as a Platform feature "currently available to select customers", using a modified version
  of Anthropic's contextual-retrieval prompt.

From these sources we infer that Unstructured treats contextual chunking as product functionality and
keeps open-source chunking structural. None of the sources states that boundary for contextual chunking
in so many words. DocWeave targets compatibility with Unstructured's open-source behavior, so
enrichment goes beyond that reference scope. That is an observation, not a reason on its own.

## Options

| | A. Keep as is, and extend it in the library | B. Keep a frozen generic primitive; Studio owns policy | C. Move enrichment to Studio (recommended) |
|---|---|---|---|
| **Architectural fit** | The library gains model-orchestration features that no other part of it has | Same primitive as today | The library stays structural (partition, chunk, serialize); model orchestration lives with model management |
| **Standalone users** | Keep a ready-made service, gaining large-document support | Keep today's service, without large-document support | Lose the service. The pattern is small and Apache-2.0 licensed, so it is easy to copy |
| **Large-document work** | Delivered through the library: change, release, then adoption | Not solved. The one-hour cap and shared-document design remain, so Studio writes its own executor anyway. B is effectively C plus a frozen public copy | Delivered in Studio, with no library release in its path |
| **Maintenance** | Every Studio context change needs a library change, a release and adoption | Two implementations to keep consistent, or a public API that falls behind | One implementation, released with Studio. Studio already uses the same bounded virtual-thread pattern for extraction, so reimplementing it is low-risk |
| **Adoption** | The feature stays visible. A model-neutral Java enrichment service is something Unstructured's open-source library lacks | Same | The library becomes narrower. Removing an API that shipped in two published betas within days signals instability to early adopters. Public reproducible evidence (retrieval replay and evaluation harnesses) loses its subject code |
| **Differentiation** | A public differentiator for the library, although the parts that set products apart (prompts, windows, model revisions, provenance, evaluation) live in Studio | Same as A, without growth | Enrichment becomes a Studio capability, consistent with the reference product's boundary |
| **Compatibility cost** | None now; grows with every release that extends the API | None now | Deprecation, then removal, of published beta API. External use is unknown |

A private optional module is a variant of B or C. It adds a second private release train for about
220 lines of code and gives no benefit over keeping the code in Studio.

## Decision

**Option C**, accepted by the owner. Contextual enrichment becomes Studio functionality, and the public library keeps
structural processing. The deciding reason is ownership: prompts, windows, model calls and workflow deadlines belong
together, and Studio must be able to evolve them without a processing-library release. Supporting factors:

- large-document support needs a Studio executor under both B and C;
- under A, every change to enrichment is coupled to the library's release cycle;
- the parts that set the product apart already live in Studio.

1. **Overlap metadata stays in the library.** Recording how many leading code points of a chunk
   repeat its predecessor is a chunking fact, useful to any consumer that shows neighbouring chunks.
   It does not depend on this decision.
2. **Studio implements its own enrichment types and executor,** at parity with the library (see
   "Studio parity requirements"). The windowed contexts are then built on that executor.
3. **The library API is deprecated first and removed later** (see "Compatibility and migration plan").
   Superseded: the API is removed directly in the next release (see "Amendment: direct removal").

Choose option B instead if keeping a public enrichment API matters more for adoption than avoiding an
early removal. In that case, freeze the API: it gains no further features, and Studio still implements
its own executor for large documents.

## What happens to the prepared library change

The unmerged library change implements per-chunk contexts, a seven-day deadline ceiling and chunk
overlap metadata. Under each option:

- **C or B:** split out the overlap metadata, together with the development-version bump, into its own
  change for an ordinary library release. Close the per-chunk contexts and deadline parts.
- **A:** review, merge and release the change as prepared.

**Outcome (option C):** the overlap metadata and development-version bump were merged as an ordinary library
change; the per-chunk contexts and deadline parts were closed.

## Amendment: direct removal (2026-11-01)

The owner approved removing the contextual-enrichment API directly, because it is a beta API. No deprecation
release, waiting period or further approval is required. This supersedes step 3 of the compatibility and migration
plan below ("no earlier than one further release and 30 days after the deprecation is published") and the
statement that removal needs a separate approval after the deprecation period. The rest of this record, including
the boundary decision and the Studio parity requirements, stands.

Consequences:

- The deprecation (`@Deprecated(since = "0.1.0-beta.11", forRemoval = true)`) was merged to the development branch
  but never published. The next release removes the API instead of deprecating it; it shipped in 0.1.0-beta.12
  (0.1.0-beta.11 was an internal-only build, never published to Maven Central). 0.1.0-beta.10 is the last published version that contains it.
- The removal follows the scope listed in step 3 below, plus `ContextualMigrationRecipeTest`, which existed only to
  keep the copy-the-pattern recipe complete while the API was in the library. `ChunkCancellation` stays, because
  ordinary chunking uses it; overlap metadata stays.
- `docs/contextual-chunking.md` is the permanent removal and migration page. It points standalone users to the
  0.1.0-beta.10 sources (Apache-2.0) or to DocWeave Studio.
- **Consumers.** Studio production code already uses its own enrichment types (Studio parity, step 1). Its
  library-differential parity test still compiles against the library types, so Studio must drop or freeze that
  test when it adopts the release that removes the API; it pins an earlier library version until then. The Studio
  SDK does not reference the removed types.

## Inventory (published or in the repository at the time of the decision)

- **Public API** (`io.docweave.chunking`, module `docweave-chunking`):
  - `ContextualChunker` (`create`, `enrich`);
  - `ChunkContextProvider` (`Request`, `Context`);
  - `ContextualChunk` (`Outcome`, `embeddingText()`);
  - `ContextualChunkOptions` (`FailurePolicy`, `defaults()`).
- **Implementation and Javadoc:** `DefaultContextualChunker`, and the enrichment paragraph in the
  `io.docweave.chunking` `package-info.java`.
- **Modules and dependencies:**
  - Shipped in `docweave-chunking` and the `docweave` convenience artifact.
  - No dedicated dependency: `docweave-chunking` depends only on core, JSON and jsoup.
  - `docweave-batch` does not use it.
- **Tests:**
  - `ContextualChunkerTest` (chunking);
  - `ContextualChunkingIntegrationTest` and the `PublicApiTest` entries (integration tests);
  - in validation: `ContextualRetrievalReplay`, `ContextualRetrievalReplayTest`,
    `ContextualRetrievalEvaluation`, `ContextualRetrievalEvaluationTest` and `OllamaContextExampleTest`.
- **Examples and tools:** `examples/OllamaContextDocument.java`, and in `benchmarks/tools`:
  `CaptureOllamaRetrieval.java`, `EvaluateContextualRetrieval.java` and `PrepareContextualCorpus.java`.
- **Evidence (historical; kept unchanged):** `benchmarks/review/contextual-chunking-service/`,
  `contextual-ollama-cross-format/` and `contextual-ollama-owned/`.
- **Documentation:**
  - `docs/contextual-chunking.md`, which several of the pages below link to;
  - `README.md` (one paragraph, still calling the feature a "snapshot");
  - `docs/roadmap.md` (contextual TODO section);
  - `docs/product-roadmap.md` (feature row, examples, evaluation item);
  - `docs/pending-work.md` (N6);
  - `docs/pdf-comparison.md` (chunking row);
  - `docs/pdf-support.md` and `docs/pdf-progress.md` (status mentions);
  - `CHANGELOG.md` (0.1.0-beta.9 entries; historical, kept);
  - `docs/releases/0.1.0-beta.7.md` (historical, kept).
- **Known consumer, DocWeave Studio:**
  - its model layer implements `ChunkContextProvider`: model, fixture and registry providers, and
    prompt construction from `ChunkContextProvider.Request`;
  - its context node uses `ContextualChunker` and `ContextualChunkOptions`, including
    `ContextualChunkOptions.defaults()` for jobs admitted before its configurable limits;
  - `ContextualChunk` is part of its stage values, checkpoints, output and presentation serializers,
    group reports and Embed/Extract inputs;
  - its architecture and models documentation names these types.

## Studio parity requirements (if B or C is approved)

Studio's replacement must preserve observable behavior. Specifically:

- **Stored data.**
  - Checkpoint and output field names are Studio's own already. The outcome enum names (`ENRICHED`,
    `PROVIDER_FAILED`, `INVALID_CONTEXT`) and the element encoding must stay identical.
  - Every checkpoint written by the library-based version must still be readable. Keep the library
    records' invariants for new values:
    - an `ENRICHED` result has context;
    - context text is not blank;
    - provider and model identities are 1–256 code points.
  - Reading back stored data must accept everything previously written. The library records never
    allowed a missing model identity, so no stored data lacks one.
  - A round-trip test runs against checkpoints and outputs written by the library-based version.
- **Legacy limits.** Jobs admitted before configurable limits use the library defaults: 100,000 document,
  10,000 chunk and 2,000 context code points, and 1,000 chunks. Studio freezes these as its own constants.
- **Prompt input.** Prompt rendering for template `studio-context-v1` stays byte-identical, because its
  digest and template version are recorded in provenance.
- **Execution semantics.** Studio keeps all of the following:
  - the validation order;
  - preflight before any provider call;
  - the bounded concurrency window;
  - result order and original identity;
  - the monotonic whole-call deadline;
  - both failure policies;
  - cancellation without partial results;
  - the mapping of timeout, cancellation and provider failures to its node failure codes.
- **Documentation.** Studio's architecture and models pages are updated to name Studio's own types.

## Compatibility and migration plan (if C is approved)

1. **Studio first, with no library change needed.** Studio adds its own enrichment types and executor,
   meeting the parity requirements, and switches its Context node to them. The windowed contexts are
   built on top.
2. **Library deprecation release** (planned as the next release; superseded by the removal in 0.1.0-beta.12):
   - **Annotations.** Mark the four types `@Deprecated(since = "<that version>", forRemoval = true)`, each
     with an `@deprecated` Javadoc tag giving the migration path. The library's strict Javadoc build must
     pass. The library's own tests, examples and tools that still use the types suppress removal warnings
     explicitly.
   - **Migration page.** `docs/contextual-chunking.md` becomes a migration page stating:
     - that the API is deprecated, and the release in which it is removed;
     - that copying the pattern is permitted under Apache-2.0;
     - that DocWeave Studio provides enrichment as a product feature.
   - **Other docs.** Update the README paragraph, removing "snapshot", the roadmaps, pending-work N6, the
     PDF comparison row, the PDF support and progress status mentions, and the package Javadoc.
   - **Records.** Add a CHANGELOG entry, and state the deprecation and its timeline in the release notes.
3. **Library removal release.** This comes no earlier than one further release and 30 days after the
   deprecation is published. (Superseded: removal was approved directly for the next release; see "Amendment:
   direct removal".) It removes:
   - the four types, `DefaultContextualChunker` and the package Javadoc paragraph;
   - `ContextualChunkerTest`, `ContextualChunkingIntegrationTest` and the `PublicApiTest` entries;
   - the retrieval replay and evaluation validation;
   - the Ollama example and its test;
   - the benchmark tools.

   `docs/contextual-chunking.md` stays as a permanent migration and history page, because historical
   evidence and earlier release notes link to it. Historical evidence and earlier release notes stay as
   they are, and published artifacts stay immutable. The CHANGELOG and release notes name the removed
   types.
4. **Overlap metadata** ships in an ordinary library release whatever is decided here.

Each step needs explicit owner approval and goes through the normal PR, review and release process.
No publication boundary changes as part of this record.

## Follow-up work once decided

**Under C:**
- Studio enrichment types and executor, at parity.
- Studio windowed contexts.
- Library overlap metadata.
- Library deprecation (merged, not published; superseded by direct removal).
- Library removal (approved directly; see "Amendment: direct removal").

**Under B:** the same, except that the deprecation and removal are replaced by freezing the library API
and documenting the freeze.

**Under A:** review and release the prepared library change.

## Numbering

This is the first library decision record. Later records go in `docs/decisions/` with the next number.
