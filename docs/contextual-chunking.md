# Contextual chunk enrichment (removed)

> **Removed in 0.1.0-beta.12**, the next Maven Central release after 0.1.0-beta.10 (0.1.0-beta.11 was an
> internal-only build, never published to Maven Central).
> `ContextualChunker`, `ChunkContextProvider`, `ContextualChunk`, `ContextualChunkOptions` and their implementation
> are no longer part of `io.docweave:docweave-chunking` or the `io.docweave:docweave` convenience artifact.
> Contextual enrichment is DocWeave Studio functionality; the processing library keeps structural partitioning,
> chunking and serialization. See the [decision record](decisions/0001-contextual-enrichment-boundary.md).

The API was a beta feature. It was removed directly, without a separate deprecation release. The last published
version that contains it is **0.1.0-beta.10**; 0.1.0-beta.9 also contains it.

## What the removed API did

`ContextualChunker` ran after ordinary basic or title-aware chunking. For every chunk it called a caller-supplied
`ChunkContextProvider` with the document text, the chunk text and the chunk position, and returned a
`ContextualChunk` holding the unchanged original chunk, the generated context and an embedding text that combined
the two. `ContextualChunkOptions` bounded document, chunk and context sizes, the chunk count, per-call concurrency,
a whole-call deadline and the failure policy. The service added no model client, prompt, credentials, retry or
cache; callers owned all of those.

## Migration

Choose one before upgrading past 0.1.0-beta.10:

- **Use DocWeave Studio.** Its Context enrichment node provides model management, prompts, large-document
  windows, provenance and deadlines as a product feature.
- **Keep the pattern in your application.** The service is small and Apache-2.0 licensed. Take exactly these six
  files from `io/docweave/chunking/` in the published `io.docweave:docweave-chunking:0.1.0-beta.10` sources JAR
  (or the `v0.1.0-beta.10` source tag), place them in one package of your own and change their `package` line:
  - `ChunkContextProvider.java`, `ContextualChunk.java`, `ContextualChunkOptions.java` and `ContextualChunker.java`
    (the public API);
  - `DefaultContextualChunker.java` (the implementation);
  - `ChunkCancellation.java` (the package-private cancellation check the implementation uses).

  They need only `io.docweave:docweave-core` (for `Element`) and Java 21. Keep the Apache-2.0 notice requirements
  of the copied files. Alternatively write the equivalent: validate every input before any model call, run calls in
  a bounded virtual-thread window, keep results in input order with the original chunks, apply an explicit failure
  policy and one deadline for the whole call, and store generated context separately from the original text.
- **Stay on 0.1.0-beta.10** until you migrate. Published artifacts remain unchanged.

Code that imports any of the removed types fails to compile against the new release; there is no runtime
fallback.

## Chunk overlap metadata

Chunking itself is unaffected. Chunk overlap is recorded in the `overlap_prefix_characters` chunk metadata
(`Chunker.OVERLAP_PREFIX_CHARACTERS`), which consumers that show neighbouring chunks together, or that build their
own context windows, can use.

## Historical evidence

The retrieval checks recorded while the API existed stay unchanged as historical records. They reference the
removed example and tools, which remain available in the 0.1.0-beta.10 source:

- [service validation](../benchmarks/review/contextual-chunking-service/README.md);
- [owned Ollama integration check](../benchmarks/review/contextual-ollama-owned/README.md);
- [PDF/DOCX/HTML live check](../benchmarks/review/contextual-ollama-cross-format/README.md).

These small fixtures verified the live path; they never established corpus-level accuracy, general retrieval gains
or parity with Unstructured's Platform feature.
