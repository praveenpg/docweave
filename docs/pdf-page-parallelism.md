# Parallel PDF pages

DocWeave 0.1.0-beta.13 processes PDF pages with Java `parallelStream()` on the common fork/join pool. It adds no
workflow setting, custom page executor or sequential fallback. Other formats are unchanged.

Each worker opens its own PDFBox document over the same read-only input bytes and reuses it for that call. Password lookup is performed
at most once per partition call; scoped copies are erased after all page tasks end. Provider callbacks may
arrive out of page order and must obey their existing thread-safety and cancellation contracts.

A batch contains at most the common-pool parallelism plus one pages. Each page retains raw elements and
warnings locally; the caller replays completed batches in page order. This preserves central element IDs,
warning order, reading-order metadata and cross-page table continuation. The cumulative image-byte budget
remains shared and synchronized. Progress counts a page only after ordered emission, not when recognition
merely finishes ahead of the writer. A failed batch does not report progress for its buffered pages.

Caller cancellation interrupts active page callbacks. A processing failure stops siblings, preserves the
first failure and waits for page tasks before releasing input. No per-page task or caller-owned provider
continues after the synchronous partition call returns, provided callbacks honor interruption as required.

The CPU pool size is not a memory budget. Worker-local PDFBox readers add memory overhead, and the
common pool is shared with other callers. Measure elapsed time and peak memory on representative native,
OCR and HI_RES inputs when sizing a shared deployment. OCR process and ONNX
inference limits still apply independently.

Parallel pages ship in 0.1.0-beta.13. Element metadata keeps the writer's key order, as on the sequential path.

## Memory on scanned PDFs (0.1.0-beta.15)

Through 0.1.0-beta.14, each worker's PDFBox document kept every image XObject it had resolved in PDFBox's default
resource cache, with the decoded raster behind a soft reference. A worker document lives for the whole partition
call, so on a scanned PDF the reachable heap grew by roughly one decoded page raster (about 14 MiB for a 200 DPI
letter page) per page processed. A heap dump of a 200-page scan at 25% progress attributed about 873 MiB of the
1,120 MiB live heap (78%) to three worker caches. The JVM clears soft references only when the heap is nearly full,
so the heap ran at its limit and the collector worked harder to keep it there; nothing failed in these runs, but the
heap was full whatever else the JVM had to do.

From 0.1.0-beta.15, the PDF module opens every document with a resource cache that keeps fonts, color spaces,
forms and other resources but not image XObjects. An image is decoded each time a page resolves it, which in OCR
and layout rendering is once per page render. The cache is installed only in place of PDFBox's own default class;
a cache of another class configured through PDFBox's `ResourceCacheFactory`, or no cache, is left unchanged.
Byte sources are now read in place: `DocumentSource.bytes` copies once on construction, and
`DocumentSource.ownedBytes` lets a caller that owns its array skip that copy.

### Measurements

Setup: an isolated runner (4-vCPU QEMU virtual machine) with Studio's Main container and JVM limits (4 CPUs,
8 GiB memory without swap, `-XX:MaxRAMPercentage=50` so a 4 GiB heap, Temurin 25), the Main image with only the
DocWeave jars replaced, Studio's OCR settings (300 DPI, two Tesseract processes) and the tatr-v1 layout models. A
measurement harness calls `DocWeave.partition` directly, one fresh container per run; it does not include a Studio
server's own heap. The "before" runs passed the input with `DocumentSource.bytes` on beta.14, the "after" runs with
`DocumentSource.ownedBytes`, so the figures combine the cache change with the removed input copies; the input copies
account for little (one after-run with `DocumentSource.bytes` peaked at about 1,410 MiB against a mean of 1,334 MiB). The "after"
jars were built from the change before its final review edits, which do not alter behaviour under PDFBox's default
configuration; the released artifact was re-checked with the same 200-page runs.

Inputs are synthetic: the scans are image-only pages (no text layer), 8-bit gray, 200 DPI with every tenth page at
300 DPI; the mixed document combines native text, table, blank, rotated and scanned pages. One document per run:

| Document | Strategy | Runs (before / after) | Peak heap MiB | Live heap after full GC MiB | Container peak MiB | Elapsed s |
|---|---|---|---|---|---|---|
| 200-page scan, 15 MB | OCR_ONLY | 3 / 3 | 3,989 → 1,334 (−67%) | 2,907 → 141 (−95%) | 4,365 → 1,669 (−62%) | 211 → 213 |
| 200-page scan, 15 MB | HI_RES | 3 / 3 | 4,076 → 1,055 (−74%) | 2,908 → 140 (−95%) | 5,150 → 1,964 (−62%) | 313 → 313 |
| 400-page scan, 30 MB | OCR_ONLY | 2 / 2 | 4,016 → 1,899 (−53%) | 3,798 → 166 (−96%) | 4,405 → 2,232 (−49%) | 423 → 414 |
| 400-page scan, 30 MB | HI_RES | 2 / 2 | 4,070 → 1,285 (−68%) | 2,115 → 165 (−92%) | 5,312 → 2,308 (−57%) | 618 → 629 |
| 160-page mixed, 4 MB | OCR_ONLY | 1 / 1 | 2,508 → 1,147 (−54%) | 810 → 131 (−84%) | 2,827 → 1,404 (−50%) | 180 → 180 |
| 160-page mixed, 4 MB | HI_RES | 1 / 1 | 2,365 → 958 (−60%) | 811 → 131 (−84%) | 3,442 → 1,861 (−46%) | 256 → 256 |
| 400-page native text, 1 MB | FAST | 1 / 1 | 118 → 121 | 141 → 100 | 329 → 411 | 2 → 2 |
| 400-page native text, 1 MB | AUTO | 1 / 1 | 124 → 142 | 125 → 128 | 350 → 374 | 2 → 2 |

Values are means (the 200-page rows from the first machine). After-run peak heap ranged 1,280–1,518 MiB for the
200-page OCR_ONLY scan across both machines and 1,654–2,144 MiB for the 400-page one; live heap after full GC varied
by under 4 MiB. "Live heap after full GC" is
the largest of three explicit full collections at 25%, 50% and 75% of pages. Before the change it grew with the page
count until the heap was nearly full; after it stays flat. Once the heap is full, soft references are cleared, so a
"before" checkpoint can fall again: the 400-page scans dropped (OCR_ONLY from 3,798 MiB at 50% to 1,959 MiB at 75%),
and their "before" values understate the retention. The text-only rows have one run each and no estimate of run-to-run
variation; this change does not touch their path, and their differences should not be read as an effect.

The 400-page scan exceeds Studio's default 25 MiB upload limit and 250-page layout limit and was run with those
limits raised. No run ran out of memory, before or after. Before the change the 200- and 400-page HI_RES runs made
275–1,300 collections per document against 150–310 after (the mixed document: 126 against 140); total pauses stayed
below 1% of elapsed time in both builds, and elapsed time did not change measurably. Re-decoding an image when a
page is rendered replaces a cache lookup that on scans almost never hit, because each page has its own image.

The 200-page runs were repeated on a second, identical virtual machine with the same results. Element output was
identical (every element's JSON, compared field by field) across both builds, both machines, all strategies and all
repetitions.

Four copies of the 200-page scan partitioned at once in one JVM, under the same limits (one run per build and
strategy). The first document's output was identical to a single run; the other three had the same element counts.

| Strategy | Peak heap MiB | Live heap after full GC MiB | Container peak MiB | Elapsed s | Collections (total pause) |
|---|---|---|---|---|---|
| OCR_ONLY | 4,054 → 2,054 (−49%) | 1,469 → 245 (−83%) | 4,393 → 2,387 (−46%) | 738 → 740 | 841 (2.5 s) → 243 (0.9 s) |
| HI_RES | 4,089 → 1,388 (−66%) | 3,560 → 238 (−93%) | 5,207 → 2,323 (−55%) | 978 → 962 | 3,243 (9.1 s) → 456 (1.8 s) |

Before the change the four documents also completed, at the heap limit, with soft references cleared to make room
(which is why the OCR_ONLY "before" live value is lower than a single document's). After it, the heap peaked at about
half its limit. Elapsed time is bound by OCR processes and CPUs, not memory, and did not change.

Limits of these numbers: synthetic gray scans, mostly at 200 DPI, on virtual machines, with few repetitions (one for
the mixed, native-text and concurrent runs); peak heap is sampled, not exact. Real colour or 300 DPI scans, and
renders near the 24-megapixel limit, raise per-page memory. Transient page rasters for OCR and layout rendering,
about 55% of all allocation, remain; they scale with the common-pool parallelism and DPI, not with the page count.
Size a shared deployment for the number of documents processed at once and their resolution, not only their length.
