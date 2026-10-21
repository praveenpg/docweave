# Used-font/Unicode mapping diagnostics

Optional interface service PdfFontInspector with immutable results and hidden bounded
traversal. Actual source code encounters only; unused font resources excluded, same-name
distinct dictionaries separated, repeated paints count, physical blank-page numbering
preserved. No extracted text, glyph positions or PDFBox objects retained in results.
Source font properties and mapping counters do not establish semantic Unicode accuracy.
Type3 drawing programs are not interpreted as additional text streams.

Six owned controls cover dictionary identity/unused resources/blank pages, explicit
Unicode/U+FFFD/ligature/missing mapping, input/page/glyph/operator/font limits,
malformed input, cancellation/shared virtual-thread reuse, repeated forms with nested
limit propagation, and password/extraction permissions. The form test caught PDFBox
recovery swallowing a checked glyph-limit exception; operatorException now propagates it.

Full actual Java21/25 verify804 tests each, zero failures/errors/skips and strict
Javadocs. Pinned formatter and12 reference guards pass. Real OCR was not replayed in
this batch; prior scanned-table checkpoint independently exercised eight engine families.
A pre-final Java21 run exposed an existing process-test readiness race: a marker could
be counted before its input path was written, then cancellation killed the child with an
empty marker. Test-only publication now uses a closed temporary file plus atomic move;
only final call markers count. No production subprocess/cleanup behavior was changed.
Focused process controls and final full JDK21/25 runs pass.

Three original native PDFs pinned in ../pdf-independent-cells/manifest.json have identical
observed inventories on both JDKs. eu-010:1page/4fonts/1062codes/0unmapped; us-038:
3pages/3fonts/10578codes/1unmapped; eu-011:4pages/7fonts/11428codes/0unmapped.
No U+FFFD mappings observed. Type1/TrueType/Type0 and embedded/nonembedded properties
occur. This is observed inventory, not independent font-correctness labels or proof
that every mapped character is correct. Raw PDFs/text remain fetch-only.
Logs/reports /tmp/docweave-font-final-checked-java21 and java25.

The repeated87% planning figure lacked a defined measurement basis. docs/pdf-completion-plan.md now
marks it unvalidated and reports explicit gates/outcomes; broad table/scan recognition
remains open. No percentage increase or full PDF completion claim follows from this batch.
