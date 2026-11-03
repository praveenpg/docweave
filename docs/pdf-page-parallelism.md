# Experimental parallel PDF pages

This branch processes PDF pages with Java `parallelStream()` on the common fork/join pool. It adds no
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
OCR and HI_RES inputs before adopting the experiment in a shared deployment. OCR process and ONNX
inference limits still apply independently.

Parallel pages ship in 0.1.0-beta.13. Element metadata keeps the writer's key order, as on the sequential path.
