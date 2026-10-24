# Contextual chunk enrichment

This optional snapshot API runs after ordinary basic/title-aware chunking, across all
supported partitioners. It requires `docweave-chunking` (also available through the
convenience artifact) and Java21. No model vendor SDK, network call, provider discovery
or engine download is added to the default pipeline.

Unstructured's [Platform feature](https://unstructured.io/blog/contextual-chunking-in-unstructured-platform-boost-your-rag-retrieval-accuracy)
adds generated document-aware context before embedding. DocWeave provides an explicit
caller-owned generation contract; it does not reproduce a hosted prompt, model,
caching policy or the published retrieval scores. This is separate from changing
chunk boundaries, title-aware grouping, overlap or late embedding pooling.

## Usage

Keep the existing chunking settings and supply document text deliberately:

```java
import io.docweave.chunking.Chunker;
import io.docweave.chunking.ChunkOptions;
import io.docweave.chunking.ChunkContextProvider;
import io.docweave.chunking.ContextualChunker;
import io.docweave.chunking.ContextualChunkOptions;

var chunks = Chunker.create().byTitle(elements,
        ChunkOptions.builder().maxCharacters(1500)
                .combineTextUnderCharacters(0).build());

// Fixed caller-supplied context for a runnable integration example; no LLM is invoked.
// Replace this callback with your local/hosted model client's document-aware generation.
ChunkContextProvider provider = request -> new ChunkContextProvider.Context(
        "Acme annual financial report for fiscal year 2026.",
        "caller-supplied-context", "fixed-context-example");

var enriched = ContextualChunker.create(provider).enrich(
        documentContextText, chunks, ContextualChunkOptions.defaults());
for (var result : enriched) {
    var original = result.original();
    String textForEmbedding = result.embeddingText();
    // Index textForEmbedding; retain original.elementId(), original.text() and metadata for citations.
}
```

A model-backed provider receives `request.documentText()`, `request.chunkText()` and
zero-based `chunkIndex()`/complete `chunkCount()`. Use those strings as explicit model
inputs and return `Context(text, providerId, modelId)`. Model/client configuration,
credentials, tokenizer/context-window checks, transport timeouts and provider/client
shutdown stay with the caller. A local model works through exactly the same interface.
The service supplies text only; it never serializes image bytes, element metadata or
compressed original-element provenance into a provider request. The caller decides
which document text may be shared and must supply the matching document for the chunks.

Generated text is separate, unverified enrichment. Original immutable Elements are
retained by identity, including IDs, text, table HTML, geometry and `orig_elements`.
`embeddingText()` explicitly prepends context with two newlines; original chunk limits
and original citations do not include that generated prefix. Do not serialize the
embedding text as if it were original source text. Original JSON codecs remain unchanged.

## Limits and failures

Defaults accept100,000 document code points,10,000 code points per chunk,2,000 context
code points per result and1,000 complete chunks. All input is checked before any
provider call; oversize input fails without truncation. Supplementary Unicode characters
count once. These are character budgets, not token, cost or total-heap guarantees.
Provider-side output allocation occurs before returned context can be checked.

Four provider calls may be in flight per invocation, using a bounded Java21 virtual-thread
window; results retain input order. Simultaneous invocations have separate windows,
so callers also bound application-wide concurrency and remote model spend. No retries
or hidden cache are added. A single30-second monotonic deadline covers the invocation,
including input validation, scheduling and waiting. Empty accepted input invokes no provider.

The immutable options record permits explicit bounds,1..64 concurrent calls, a positive
deadline up to one hour and one of two failure policies:

- `FAIL_DOCUMENT` (default): provider exceptions or invalid/null/oversize context cause
  `IOException`; the original cause is retained and no partial result list is returned.
- `KEEP_ORIGINAL`: those per-chunk failures return the unchanged original with
  `PROVIDER_FAILED` or `INVALID_CONTEXT`, absent context and exact original embedding text.
  Error messages and model output are not inserted into source metadata.

Timeout always throws `TimeoutException`; caller/provider cancellation always throws
`CancellationException`, regardless of fallback policy. Caller interruption remains set.
Pending work is cancelled on failure, timeout or cancellation. A provider must cooperate
with interruption and enforce its own transport deadlines: an uncooperative request can
continue after this service returns and may still incur provider charges. The service
never closes caller-owned clients. It deliberately avoids waiting executor shutdown that
would defeat the public deadline.

## Validation and remaining quality work

JVM controls cover original identity/Unicode, complete-input preflight, provider/null/
oversize failures, original-only fallback, bounded virtual-thread execution, cancellation,
peer cancellation and an intentionally uncooperative provider. Actual PDF, DOCX, HTML,
Markdown, TXT, XML, CSV and TSV partitioners compose with both chunk strategies; original
JSON bytes/provenance, table HTML and code whitespace stay intact. No live model or
new Python dependency is needed for these controls.

These tests establish API/ownership behavior, not factual model output or general retrieval
quality. The JVM replay and explicit live example below add a small owned integration
check. A larger independently labeled corpus comparison remains open before general
quality claims. Keep failed/missing contexts in evaluations; do not substitute successful
outputs or attribute Unstructured's reported improvements to DocWeave.

## Run with a local or remote Ollama server

The runnable [Ollama example](../examples/OllamaContextDocument.java) uses Java21's
HTTP client and the caller-owned provider interface. It does not install models.
Use a reachable server and a model already listed by its `/api/tags` endpoint.
The server may run on another machine; replace `OLLAMA_HOST` below with its IP/name.
Requests send document text to that server.

From the repository root, with JDK21 active:

```sh
mvn -B -ntp -pl docweave-validation -am test-compile \
  org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath \
  -DincludeScope=test -Dmdep.outputFile=target/contextual-retrieval-classpath.txt
read -r task_cp < docweave-validation/target/contextual-retrieval-classpath.txt
java --class-path "$task_cp" examples/OllamaContextDocument.java \
  /path/to/document.pdf http://OLLAMA_HOST:11434 INSTALLED_CHAT_MODEL context-run.json
```

Choose a fresh output filename. The report retains original chunks separately from
context, embedding text, model tag/digest and actual token usage. The example uses
one request at a time, `think:false`, temperature0, a32768 context-window setting,
256 generated tokens, a two-minute HTTP deadline and a ten-minute enrichment deadline.
It rejects incomplete generation and changed model digests. These character/byte
budgets do not prove that every document fits a model's tokenizer/context window.
An image-only PDF needs an explicitly configured OCR provider before this text-only
context stage; the chat example does not perform OCR.

## Compare retrieval using frozen questions

Use independent questions and relevance labels against the **original** chunk IDs.
Author and freeze these before requesting embeddings or inspecting rankings:

```json
[
  {
    "id": "question-1",
    "text": "What revenue did the company report for 2026?",
    "relevant_chunk_ids": ["ORIGINAL_CHUNK_ID"]
  }
]
```

Compile the JVM capture and replay tools, then use a separately installed embedding
model. The chat and embedding models serve different roles:

```sh
mkdir -p /tmp/docweave-context-tools
javac --release 21 \
  -cp "docweave-validation/target/test-classes:$task_cp" \
  -d /tmp/docweave-context-tools examples/OllamaContextDocument.java \
  benchmarks/tools/CaptureOllamaRetrieval.java benchmarks/tools/EvaluateContextualRetrieval.java
java -cp "/tmp/docweave-context-tools:docweave-validation/target/test-classes:$task_cp" \
  CaptureOllamaRetrieval context-run.json queries.json \
  http://OLLAMA_HOST:11434 INSTALLED_EMBEDDING_MODEL 1 fresh-retrieval-run
```

This captures complete original and contextual rankings against all source chunks,
using the same query vectors and embedding model/digest, cosine similarity and
source-ordinal ties. `truncate:false` rejects input overflow. Neither condition gets
query rewriting, source filtering, retries or relevance-dependent ranking. The fresh
run directory contains frozen sources/queries/context, raw vectors with input hashes,
manifest, captures and a numeric report. Keep private document/server information out
of public commits. Failed embedding runs retain every query in their failure report.

The offline scorer can replay existing captures without model access:

```sh
java -cp "/tmp/docweave-context-tools:docweave-validation/target/test-classes:$task_cp" \
  io.docweave.validation.EvaluateContextualRetrieval \
  fresh-retrieval-run/manifest.json fresh-retrieval-run \
  fresh-retrieval-run/baseline.json fresh-retrieval-run/contextual.json replay-report.json
```

The manifest pins original chunks, queries and context artifact by SHA256, with one
embedding identity and retrieval configuration. Captures must carry those exact
identities. Unknown/duplicate IDs, changed labels/artifacts/configuration and existing
report output are rejected. Missing/failed queries score zero and remain in the full
reference denominator. The report provides per-query and macro Recall@k, MRR@k and
hit rate@k; it does not assess factual correctness of generated context.

A first real-model owned integration check used three chunks and five frozen questions:
unenriched top1 hit rate3/5, contextual5/5, no missing/failed questions. This small
fixture verifies the live path and records a local improvement; it does not establish
corpus-level accuracy, general retrieval gains or parity with Unstructured.

For labels fixed **before generation**, use the
[owned cross-format corpus preparation tool](../benchmarks/tools/PrepareContextualCorpus.java).
Partitioning creates fresh IDs: freeze actual chunks and questions together, then pass
`--frozen-chunks frozen.json` before the usual server/model/output arguments to the
Ollama example. The frozen JSON contains `document_text` and `original_chunks` in the
existing ElementJson wire format. It avoids repartitioning; the same character limits,
model identity checks, fresh-output requirement and failure behavior still apply.

The follow-up [PDF/DOCX/HTML live check](../benchmarks/review/contextual-ollama-cross-format/README.md)
retains9chunks and12pre-generation questions against a shared search population:
top1hits10/12 unenriched versus11/12 contextual, no missing/failure cases. One revenue
question remains a miss in both. This extends live integration evidence, not independent
human acceptance or a general quality claim.
