# Unstructured source review:2026-10-20

Official GitHub latest release remains0.27.30 at08d407ea. Main07d978b adds six commits;
the baseline0.27.27 fixture reference is now14commits behind reviewed main. The read-only
freshness check correctly failed on that new main identity before source triage. Historical
goldens and executed0.27.28 PDF comparisons remain pinned. Main's0.27.31/0.27.32-dev changelog
entries do not mean a new official release was measured.

| Change | DocWeave assessment |
| --- | --- |
| DOCX numbered headings/outline levels/section-only paragraphs | Record semantic followup for after PDF; do not silently rebaseline DOCX goldens. |
| Spooled upload rewind/no copy | Python stream contract differs from JVM Path/Bytes; current `BoundedFileInput` retains size-limited complete bytes. No in-place spool parity claim. |
| OCR-only one-page rendering and owned-image release | JVM `PdfPageOcr` already renders one page with a fresh renderer and flushes owned/masked images in `finally`; no new total heap guarantee. |
| Chunk empty-image payload/provenance memory | Public JVM chunking accepts caller-owned Lists. Avoiding an internal reference cannot release caller payloads; streaming input is a separate future API. Current provenance codec materializes a rounded collection plus whole JSON/UTF8 before enforcing16MiB. Streamed serialization is an actionable followup. |
| Guarded XLSX worksheet reader and dense-sheet memory | Outside currently supported formats; keep PDF priority. The later dense-sheet commit removes NetworkX from runtime extras; no JVM dependency change follows. |

The review reads the exact six-commit deltas, relevant source/tests and existing JVM
ownership/codec contracts. It does not execute the new Python runtime or relabel prior
benchmarks. `freshness-observation.json` records official response URLs/hashes/ETags;
`delta-identities.json` pins exact commit/file coverage. Follow-up implementation and its
JVM tests must be recorded separately, rather than classifying a source review as adoption.

The final selected Java21 upstream check passes3 tests after triaging the additional XLSX
commit detected during review. `verified-observation.json` records the final independent
fetch. Maven Central's official PDF metadata confirms latest/release0.1.0-beta.6; the saved
metadata supports the corrected README publication statement. No deployment, tag, version
or historical reference changes occur.

The subsequent [JVM provenance implementation](../pdf-originals-streaming/README.md) adopts
the applicable serialization-memory followup under the existing16MiB byte ceiling. Its
allocation/compatibility tests are separate from this source-review receipt; caller-owned
List input/image retention still differs from upstream's streaming-iterable contract.
