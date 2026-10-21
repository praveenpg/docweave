# Owned cross-format live contextual retrieval

One bounded run on2026-10-20: three distinct authored fictional sources (business PDF,
business DOCX, scientific HTML),9original chunks and12source-supported questions.
PrepareContextualCorpus fixes all questions/anchors in source code, then partitions
actual native files and freezes the resulting original chunks and relevance IDs
**before generation or embedding**. These are authored integration labels, not
independent human-reviewed real-world ground truth. No new public-source corpus or
Unstructured comparison is implied.

The native PDF remains1chunk; DOCX and HTML each produce4. Existing example title-aware
settings stay unchanged (1500max,1000new-after,0combine). Similar business facts provide
cross-document distractors. All9chunks participate in each of12rankings; no per-file
search filter or post-hoc relevance adjustment.

An initial attempt repartitioned the sources during generation, which produced fresh
random element IDs. It was rejected before embedding/scoring. Neither source similarity
nor generated answers were used to map those IDs onto references. The corrected explicit
`--frozen-chunks` path generates directly against the pre-generation chunk artifacts.
All9serialized original chunks match exactly; source/query files pass their pre-generation
SHA256 check. The rejected run remains local and contributes no replacement scores.

Models are the same installed `qwen3.8:latest` and `embeddinggemma:latest` as the
[first live receipt](../contextual-ollama-owned/README.md), with exact digests recorded
there and in the report. Generation config unchanged: temp0,thinkfalse,context32768,
output256,concurrency1.9/9contexts succeeded.30embedding inputs (9original,9contextual,
12queries) in batches16/14, `truncate:false`. Query vectors are reused unchanged across
conditions. Cosine ranking, source-ordinal ties; all model tags/digests checked.

| Original population | Unenriched | Contextual |
| --- | --- | --- |
| Questions retained |12|12|
| Successful / failed / missing |12 /0 /0|12 /0 /0|
| Top1 hits |10/12|11/12|
| Macro Recall@1 / MRR@1 / hit rate@1 |0.8333333|0.9166667|

`corpus-4` improves; `corpus-5` (Harbor Materials annual revenue) remains a miss in
both conditions. No prompt/model/label/chunk tuning or retry after scoring. This is
a local measured improvement, not general retrieval accuracy or PDF extraction gain.
Generated context remains unverified data distinct from original citations. Independent
human review and a larger permitted corpus remain open.

Numeric report and owned questions are committed. Native source files, complete frozen
chunks, generation reports and raw vectors remain in local evidence; the numeric hashes
pin their original identities but cannot reconstruct omitted artifacts alone.

Reproduce with JDK21 and the classpath documented in
[contextual chunking](../../../docs/contextual-chunking.md):

```sh
java --class-path "$task_cp" benchmarks/tools/PrepareContextualCorpus.java fresh-corpus
# For each cedar.pdf / harbor.docx / lumen.html, with your installed model/server:
java --class-path "$task_cp" examples/OllamaContextDocument.java --frozen-chunks \
  fresh-corpus/cedar.pdf-frozen.json http://OLLAMA_HOST:11434 CHAT_MODEL \
  fresh-corpus/cedar.pdf-context.json
# Repeat the same generation command for harbor.docx and lumen.html, then:
java --class-path "$task_cp" benchmarks/tools/PrepareContextualCorpus.java --collect fresh-corpus
# Use the compiled capture/replay tools from the contextual documentation:
java -cp "/tmp/docweave-context-tools:docweave-validation/target/test-classes:$task_cp" \
  CaptureOllamaRetrieval fresh-corpus/aggregate-context.json fresh-corpus/queries.json \
  http://OLLAMA_HOST:11434 EMBEDDING_MODEL 1 fresh-corpus/retrieval
```

Fresh partition runs create new source IDs; freeze their questions/chunks together.
Never substitute a second partition run for the frozen inputs. Model regeneration is
not asserted deterministic. Default Maven tests never contact the external model.
