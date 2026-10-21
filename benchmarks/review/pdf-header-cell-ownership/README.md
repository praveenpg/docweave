# Native glyph ownership in unchanged header grids

This is a diagnostic, not an extraction refinement or a semantic header oracle.
`manifest.json` freezes the protocol against the preceding span audit. The helper
reads the unchanged original us-002 PDF and existing page-1/page-3 model captures;
it does not invoke models, read reference labels or modify anchors. Reports retain
all atomic cells, including empty cells and body rows. Glyph counts are PDFBox
nonblank native text positions, not Unicode character or word counts.

Run after building the reactor, using its runtime classpath:

```sh
javac --release 21 -cp "$classpath" -d "$classes" benchmarks/tools/AuditHeaderCellOwnership.java
java -cp "$classes:$classpath" dev.docweave.partition.AuditHeaderCellOwnership \
  "$original_pdf" "$original_page_capture_directory" "$new_report"
```

The capture directory must contain original-frame.json, crops.tsv and
crop-0-anchors.tsv from CaptureDetectedTableInput's original-pdf mode. The tool
checks PDF identity, upright zero-origin unit-scaled frame, original render size,
fixed150DPI, a single crop, consistent complete atomic boundaries,1024-cell bound,
and shared native work/cancellation limits. Fresh output is mandatory. It rejects
unsupported frames rather than silently applying another rotation. Native centers
are scaled into crop pixels, translated once, then assigned to half-open atomic
cells. Baselines remain source points. No decoded source strings are exported.

## Observations and decision

Actual Java21 and25 reports agree after JSON parsing. Page1 has native text in
both atomic rows1/2 of every column3–6: respective counts8/7,8/6,8/6,8/6. Each
cell has a single baseline,232.72 or244.84 source points. Thus the split rows are
not empty, duplicate-free placeholders that can simply be deleted. Center
ownership alone does not establish whether two populated cells should be merged.

The page3 control is less aligned: atomic row1 contains multiple native baselines
in columns2,3,5,6 (ranges154.48–177.76 or166.12–177.76). Its declared model header
prefix therefore does not isolate a clean one-baseline-per-subheader structure.
A provider-declared header role is model evidence, not independent semantic truth.
Neither per-cell occupancy nor a multi-baseline flag is a safe repair rule here.

No extraction defaults, labels, row indices, spans, text or quality scores changed.
Prior independent2/214 and208/215 remain untouched and were not rescored. These
numeric ownership reports do not prove source reading order or text correctness.
No new beta release is warranted by this diagnostic alone.

Next, obtain an independently defined header-span evaluation set with both true
wrapped labels and true stacked subheaders before choosing a text-aware repair.
Do not tune a merge to these two pages. Modern business/scientific breadth,
historical/degraded scans and the final Unstructured comparison remain open.
