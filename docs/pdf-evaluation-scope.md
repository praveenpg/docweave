# PDF evaluation scope

The current scope follows observable Unstructured OSS behavior and relevant publicly documented
checks. Use the pinned executed reference (Unstructured0.27.28/inference1.6.13) and existing
regressions. A newer reference version needs an explicitly recorded baseline.

Unstructured publishes an evaluation framework with representative PDFs and text/table ground
truth. It also describes an internal corpus that is not fully public. This supports using
labeled examples, but does not establish that DocLayNet, DuckDB or any particular corpus size
is required for OSS compatibility.
[Official evaluation framework](https://github.com/Unstructured-IO/unstructured-eval-metrics).

## Current work

- Preserve owned JVM regressions, public API checks, typed errors and relevant existing source
  comparisons. Python remains appropriate for executing the pinned upstream reference.
- Prefer relevant publicly supplied Unstructured examples and their source labels. Inspect label
  completeness and coordinate/schema conventions before scoring; a missing annotation does not
  prove a table-free page. Report unsupported measurements rather than inventing source truth.
- Keep published evaluation outputs separate from actual pinned OSS captures: the published
  framework also concerns generative parsers, and its outputs are not automatically the
  Unstructured0.27.28 OSS result. Do not port the entire framework or its VLM machinery.
- Fix demonstrated compatibility or correctness defects with focused controls and review.
  Preserve source characters, existing defaults, grouping/ownership contracts and failed evidence.

## Deferred qualification

DocLayNet acquisition, a DuckDB/Parquet reader, new third-party detector corpora, broad
independent producer/held-out qualification and new ICC scan acquisition are deferred. The
previous corpus transfer/model-execution proposal is superseded for this scope. These are
not current completion blockers; they remain future qualification work, not passed tests.

Do not pursue additional OCR/column quality beyond the observed reference solely to close
those broader gates. ALIGNMENT_COLUMN_V1 and the paragraph-breaking sidebar-tie candidate
remain rejected. Preserve their evidence and the stable production contracts.

An unavailable public example is a named resource limitation. It is not justification to
expand into another dataset or add dependencies. Do not claim universal accuracy, complete
upstream parity, private-corpus equivalence or guarantees from missing qualification.

## Explicit bounded backlog evaluation

On 2026-10-22 the deferred single-shard DocLayNet pilot was explicitly requested and
completed: see [results](../benchmarks/review/pdf-doclaynet-pilot/README.md). This exception
does not expand the compatibility target or authorize an unlimited corpus/model study.
The isolated JDBC reader is tooling only; production dependencies are unchanged. The shard
lacks financial reports and producer metadata, so broader qualification remains open.
