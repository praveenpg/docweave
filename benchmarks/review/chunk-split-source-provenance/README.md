# Bound repeated oversized source provenance

A source-informed safeguard addresses serialized output amplification when one large
source becomes many narrative or table chunks. The reviewed upstream main change
[cb82b550](https://github.com/Unstructured-IO/unstructured/commit/cb82b5503695cd6c97b4fe2c85842851b815edd2)
uses a strict 1,000,000-character source threshold. This is an unreleased main behavior
at this review, separate from official release 0.27.32 and the executed 0.27.28 comparisons.

When original embedding is enabled, DocWeave now waits until actual fragment multiplicity
is known. A single source exceeding 1,000,000 combined Unicode code points of raw text,
`text_as_html` and `image_base64` omits `orig_elements` from each resulting chunk **only
when there is more than one chunk**. Each affected chunk reports:

```json
{
  "orig_elements_omitted": "oversized_split_source",
  "orig_elements_source_characters": 1050896
}
```

The size shown is illustrative; the actual source total is emitted. This intentionally
removes embedded full-source reconstruction from those chunks. Keep the original partition
result when it is needed; caller-owned source elements are unchanged. Text, structural HTML,
cell ownership, chunk counts, overlaps and continuation metadata retain their normal policy.
Small sources, exactly-at-threshold sources, unsplit large sources, multi-source groups and
explicit skip-table-chunking retain existing contracts. Disabled embedding adds no omission
marker. Markers from earlier chunks are dropped and recomputed when rechunking. The existing
16 MiB codec limit still applies wherever provenance is retained.

## Owned measurements

A deterministic valid 512x512 RGB PNG has 1,050,056 base64 characters. The same image is
attached to one narrative source and one authoritative-HTML table, each chunked at 120
characters. Original inputs and all text/HTML output hashes match across runtimes.

| Owned case | Chunks before/after | Serialized JSON bytes before -> after | Explicit omission markers |
| --- | ---: | ---: | ---: |
| Narrative | 8 /8 | 8,439,602 ->2,546 | 8 |
| Structured table | 6 /6 | 6,334,867 ->6,019 | 6 |

This reduction comes from the explicit provenance omission, not improved compression or
an extraction accuracy gain. The previous JVM implementation already encoded provenance
once per pre-chunk and shared that String among fragments. The measurements establish
serialized-output reduction, **not** equivalent retained-heap savings, a whole-parser heap
bound, peak-RSS performance or behavior on an independent PDF corpus.

Compile [ProbeSplitSourceProvenance](../../tools/ProbeSplitSourceProvenance.java) on Java 21.
For the baseline, compile `DefaultChunker.java` from development `ebaae9d` into an isolated
first classpath entry; use current production classes for the candidate. Run the same probe
with one fresh report path under each runtime. Then invoke the probe with baseline, candidate
and a third fresh path to verify source image/text identities, complete unique cases,
unchanged chunk counts/text/HTML, explicit omission and smaller actual serialized output.
Historical JARs must be isolated before a build overwrites them.

[Before](before-java21.json), [after](after-java21.json), [comparison](comparison.json) and
[receipt](receipt.json) preserve the numeric evidence. Six owned Java methods exercise actual
basic/by-title chunk production, Unicode/exact boundaries, actual single-chunk separator
consumption, structural/text fallback, provenance decode restoration, multi-source retention,
pass-through, disabled embedding and stale marker removal. Existing cancellation and table
regressions also pass. Source review and these owned tests do not establish full upstream parity.
