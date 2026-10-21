# Owned live contextual retrieval check

One bounded real-model integration run on2026-10-20, Java21 client and Ollama0.40.5
on a remote Apple Silicon test server. No model installation or hosted API.
The source is an authored fictional report, not third-party material. The five questions
and original chunk relevance IDs were frozen before embedding/ranking; they were authored
after context generation and are not an independently blinded retrieval benchmark.

Generation: installed `qwen3.8:latest`, server-reported digest
`47951e4a666180bc2aedfc93c169472f2de783696fe196efbf063f5730ecf163`.
The server reports27.8B parameters, Qwen3.5 family, MLX/NVFP4. Preserve that installed
name; it is not a claim that an identically named public model tag exists.
Three original title-aware chunks, three successful contexts, original source text and
IDs retained exactly. Generated prefixes were reviewed against this small owned source;
that review is not an automated factuality guarantee.

One generation at a time, temperature0, thinking disabled, configured context32768,
output256 tokens, HTTP2min, whole enrichment10min. Actual prompt/evaluation tokens:
296/50,313/45,291/62. Prompts1245/1305/1227 UTF8 bytes. Model allocation/performance is
not a library requirement or measured production throughput.

Embedding: installed `embeddinggemma:latest`, digest
`85462619ee721b466c5927d109d4cb765861907d5417b9109caebc4e614679f1`.
All11 inputs (3original,3contextual,5queries) were embedded in one batch with
`truncate:false`. Queries were embedded once and reused identically. Every source chunk
participated, cosine ranking with source-ordinal ties, no reference-dependent selection.
Full rankings and raw vectors remain in the private run artifacts. No tuning or rerun
was performed after inspecting these scores.

| Original population | Unenriched | Contextual |
| --- | --- | --- |
| Queries retained |5|5|
| Successful / failed / missing |5 /0 /0|5 /0 /0|
| Top1 hits |3/5|5/5|
| Macro Recall@1 / MRR@1 / hit rate@1 |0.6 /0.6 /0.6|1 /1 /1|

See `java21.json` for original identities, captured-frame hashes and every query outcome.
Only owned source/questions and numeric evidence are committed. LAN addresses, raw
model reports and vectors are omitted. Their original SHA256 identities remain in the
report; this numeric receipt alone cannot reconstruct the omitted artifacts.

This is a live integration demonstration on one tiny fixture. It establishes neither
corpus-level retrieval accuracy nor a general gain, PDF extraction improvement, or
Unstructured parity. A larger independently labeled cross-format corpus remains open.
The source is TXT; PDF/other parser composition has separate JVM integration controls.

Reproduce the path with the explicit commands in
[contextual chunking](../../../docs/contextual-chunking.md), using an installed server
and fresh generation/capture output. Exact model regeneration is not promised.
