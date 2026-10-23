# Bounded DocLayNet proposal pilot

This is a detector baseline, not a runtime improvement or an Unstructured comparison.
The pinned single-shard study completed 48 observations on 24 pages from 20 original
files: 12 annotated-positive and 12 dataset-negative pages, with 19 Table annotations.
Both detectors completed without errors. No thresholds, sources or model weights were changed after inference.

| Detector | Matched tables (TP) | Unmatched proposals (FP) | Missed tables (FN) | FP on 12 negative pages |
|---|---:|---:|---:|---:|
| TATR | 8 | 18 | 11 | 11 |
| YOLOX | 16 | 1 | 3 | 0 |

YOLOX is better on this pilot. The numbers measure proposal overlap with DocLayNet's
annotation policy, not cell/text fidelity or general accuracy. They do not establish
an improvement over a previous DocWeave version, or superiority to Unstructured.

## Scope and admission

[Dataset card](https://huggingface.co/datasets/docling-project/DocLayNet-v1.2/blob/0daf93102e2efce76c3e11a274a5e0d0969391d3/README.md),
[official project](https://github.com/DS4SD/DocLayNet).
Data revision: `0daf93102e2efce76c3e11a274a5e0d0969391d3`, file
`data/test-00004-of-00006.parquet`; CDLA-Permissive-1.0 as recorded by the dataset.
The full dataset was not downloaded. The shard is 266,503,202 bytes; the isolated
DuckDB JDBC 2.0.0.0 reader is 79,362,050 bytes. Together with small metadata/license
requests, acquisition stayed below 512 MiB. No reader was added to a Maven POM and
no model weights were downloaded. Raw PDFs/PNGs and the reader remain outside Git.

The shard contains 833 pages from 30 originals: 94 laws/regulations, 552 manuals,
187 patents. It contains **no financial reports**, and the extracted PDFs have no
producer identity. Therefore this does **not** close financial/business coverage or
producer-transfer requirements. Training overlap with either graph is unknown.
The 24 pages are now development data, not held-out acceptance.

Selection: four positives and four negatives per category; prefer least-used original,
then filename, page number and page hash; at most two pages per original. Selection
was frozen before inference. Sorted original-filename parity creates two disjoint
document groups; these are reporting groups, not evidence of distinct producers.
The table category is 9; COCO xywh boxes are converted to left/top/right/bottom.
A negative means no Table annotation under this dataset's policy. It does not prove
that every evaluator would agree no tabular content exists.

Each embedded PDF's hash was checked against the inventory; each is a one-page,
unrotated, UserUnit-1, origin-zero page whose extent agrees with its metadata.
Every label is within the annotation frame (a one-pixel tolerance is admitted and
clipped). PNG dimensions are 1025 square. A paired contact sheet was inspected with
labels over both the supplied PNG and PDFBox render; coordinate alignment agrees.
RGB differences between renderers/resampling are diagnostic, not an accuracy score.

## Frozen method

See [protocol.json](protocol.json), [selection.json](selection.json),
[receipt.json](receipt.json) and [summary.json](summary.json).
The importer and evaluator source hashes were frozen before inference and verified
unchanged afterwards. The compiled-class receipt was recorded after the run and
strict recompilation; it is not a separately timestamped pre-run class archive.
Exact source snapshots and acquisition logs are retained in the private companion.

- Render the selected PDF at 300 DPI with PDFBox. Run the existing managed detector,
  single call at a time, with its published adapter defaults (TATR 0.5; YOLOX >0.25).
- Score ordinary-table proposals **before** crop padding/containment admission and
  structure recognition. Rotated/other objects are recorded but not ordinary proposals.
  This isolates detection and is not an end-to-end table extraction measurement.
- Map source-raster rectangles to the 1025-square annotation frame independently on
  each axis. Match one-to-one at IoU >=0.5 using maximum-cardinality augmenting paths;
  adjacency order is overlap descending then reference index. Duplicate proposals are FP.
- An errored page retains all references as FN; FP would be unknown, not zero.
  No page errored. Each graph ran once on each selected page, without retries.
- Child JVM ceiling: 600 seconds; heap 768 MiB, direct memory 512 MiB, at most four
  native threads. Sampled peak RSS: TATR 1,269,992 KiB, YOLOX 1,576,780 KiB;
  durations approximately 6.1 and 17.8 seconds. These are observations, not universal bounds.

There were two pre-inference tooling failures: DuckDB needed more than a 128 MB
reader limit (384 MB passed), and `/usr/bin/time` was unavailable. A local process
wrapper recorded `/proc` VmHWM instead. Neither failure consumed a detector observation.
No OCR, structure model, upstream reference, threshold sweep or runtime candidate ran.

## Remaining misses and next bounded work

YOLOX's three misses are two annotations on `basic-english-language-skills.PDF`
(original page index 10) and one on `7049044_AdobeOCR.pdf` (index 0). The latter also
has its only FP. TATR's failures include ordinary text on negative pages and missed
patent annotations. Source page indexes are those supplied by the dataset.

An offline overlay review of both failing pages was completed using the cached
predictions (no further inference). The manual misses are shaded teaching examples
labelled as Table; the patent miss is a separate two-line reference block. The patent
FP encloses a visually aligned classification-code list not labelled Table. These
are real disagreements with the dataset, but the manual/list boundaries also expose
annotation-policy choices. No label was removed or score changed. The overlay source
and outputs remain private. No production candidate is justified from these two
post-measurement examples alone. Do not tune thresholds on this pilot or claim that
geometry establishes an adapter defect. Financial-report labels, known-producer transfer, original ICC scan
coverage and independently held-out acceptance remain separate open qualifications.

## Validation and replay

Three standalone JVM tools live in `benchmarks/tools/`:
`PrepareDocLayNetPilot` (package `dev.docweave.validation`),
`EvaluateDocLayNetProposals` and `SummarizeDocLayNetPilot`
(package `dev.docweave.layout`). The evaluator intentionally uses package-private
model adapters to measure detection without changing public APIs.

Compile with Java 21 against the reactor runtime classpath and, for export only,
the isolated JDBC JAR. All three pass `-Xlint:all -Werror -Xdoclint:all/private`.
Run evaluator with argument `controls` to check duplicate, negative, missing-prediction,
inclusive-IoU-boundary and non-greedy matching cases without loading any model.
Replay stored boxes without inference:

```sh
java -cp "$tools_classes:$runtime_cp" dev.docweave.layout.SummarizeDocLayNetPilot \
  selection.json tatr.json yolox.json
```

The JVM replay reproduces every per-page TP/FP/FN and the aggregate counts.
Pinned clang-format 23.1.1 and `git diff --check` pass on the new tools/evidence.
No production or Maven dependency changed, so the full reactor and Java 25 suite
were not repeated for this evaluation-only addition.
