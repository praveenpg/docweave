# Streamed original-element provenance (Java 21)

The default JSON codec now serializes one rounded original at a time through a UTF8 byte
counter into zlib compression. It removes the collection-wide rounded/wire lists and full
JSON-string/UTF8-array staging from `encodeOriginals`. The existing16MiB limit counts complete
uncompressed JSON as bytes are encoded, including field/structural overhead. An oversized
first original fails before later originals are accessed. Public interfaces, precision,
metadata projection, source immutability and the decoded format remain unchanged.

The Writer path intentionally preserves legacy `String.getBytes(UTF_8)` replacement for
unpaired surrogates, including metadata keys/values. A call-local Jackson sequence writer
reuses one serialization context. The byte-counting view borrows the compressor; its outer
resource scope owns zlib completion. Private size failures are recognized through Jackson
IO wrappers and retain the public `IllegalArgumentException` contract. No disk spool or
new I/O dependency is introduced. Individual originals, caller-owned Lists and final
compressed/base64 output still scale; no total heap or process-memory bound is claimed.

## Controlled same-JVM allocation probe

| Owned fixture | Legacy median Java bytes | Current median Java bytes | Reduction |
| --- | ---: | ---: | ---: |
|2MiB ASCII text|10,505,400|58,352|99.44%|
|2MiB synthetic image payload|20,476,608|10,046,144|50.94%|
|1,024 short originals|1,969,592|1,181,192|40.03%|

`ProbeOriginalsAllocations` compares the previous codec from5cebb58 with the current public
service in one Java21 JVM. Each fixture has8 warmups and5 alternating-order samples; every
sample remains in `allocations-java21.json`. Inputs/validation occur outside allocation
intervals. The measurements count **main-thread Java allocated bytes**, excluding native,
retained and peak heap memory. They are not PDF-wide savings or production latency claims.
The short-original case's median time increases0.85→0.97ms in this small probe; the other
medians decrease. Five micro samples do not establish production throughput behavior.

All3 fixtures retain exact decompressed UTF8 JSON bytes before measurement. Repeated outputs
are deterministic within each implementation. Compression bytes match in the two large
fixtures but differ in the short-original case; raw Base64 is not a stable identity, and
decoded elements define compatibility. Report hashes pin this invocation; map ordering and
VM/JIT behavior can vary across separate runs. No fixture or unfavorable sample is dropped.

## Reproduction

Obtain `docweave-json/.../DefaultElementJson.java` from commit5cebb58 into a temporary directory
and replace its class-name occurrences with `LegacyElementJson` (no algorithm changes).
Compile that source and `benchmarks/tools/ProbeOriginalsAllocations.java` against the Java21
reactor runtime classpath, then run from the checkout with the temporary classes first:

```text
dev.docweave.json.ProbeOriginalsAllocations LEGACY_SOURCE.java FRESH_REPORT.json
```

The report pins the transformed source, actually loaded old/current bytecode and probe
source, rather than assuming the classpath matches the file names. No source PDF, model,
external parser or Python dependency participates in these owned allocation measurements.

## Validation and scope

The pre-change control fails because the oversized first original still causes the next
source-list element to be visited. Five new controls pass after streaming: early rejection,
exact16MiB ASCII/multibyte boundaries and+1byte failures, independent precision/escaping/key
projection, malformed-surrogate/empty-list compatibility, and32 concurrent virtual-thread
calls sharing one codec. Complete final Java21 verification passes **1,213 tests**, zero
failures/errors/skips, including22 codec checks, strict public Javadocs and standalone tools.
Five changed source/test/probe files pass private strict documentation checks. The final
public-interface documentation-only update also passes those strict checks.

This adopts the applicable serialization-memory aspect of the newly reviewed Unstructured
main change, with DocWeave's existing byte ceiling. Caller-owned List image retention and
future streaming chunk input remain distinct. It does not change PDF extraction accuracy,
table recognition, chunk boundaries or semantic contextual chunking. Upstream0.27.27 goldens
and executed0.27.28 PDF references remain pinned; no0.27.32 runtime result is claimed.
