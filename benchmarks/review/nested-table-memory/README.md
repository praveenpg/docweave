# Owned nested-table memory packet

Local Corretto25/G1 observations from2026-10-15; see [method/results/reproduction](../../nested-table-memory.md).
These are synthetic allocation and intrusive process live-heap observations, not browser/Word evidence,
exact retained sizes, peak heap or performance promises.

- inputs/: exact owned HTML/Markdown/DOCX source bytes and SHA256 manifest, reused in every run.
- allocation-1/2/3.json: three fresh-JVM trials,10 warmups and10 calling-thread measurements per case.
- heap.json: separate JVM, three full-GC before/held/released observations per case.
- stacks.json: separate JVM,20 recorded calls, sampled calling-thread weights after excluding first sample.
- summary.json: medians/ranges derived from the saved raw reports; allocation median is of trial means.

Reports pin actual runtime JARs, input manifest, probe source and flags. Raw JFR files remain in the
local /tmp/docweave-nested-memory-final-stacks directory and are not committed; their hashes and
extracted ranked stack data are saved. Re-recorded weights/JFR files need not match. Original reports
remain historical even after code/runtime changes. ProfileNestedTableMemory.java never overwrites an
existing output directory and rejects altered pinned inputs.
