# Explicit cell segmentation evidence

The optional OCR strategy now receives `PdfOcrProvider.InputKind.PAGE` or `TABLE_CELL` through a default
interface overload. Existing one-argument implementations/lambdas still work. Tesseract uses immutable,
call-local `CellSegmentation.AUTO` (PSM3), `SINGLE_BLOCK` (PSM6), or `SINGLE_LINE` (PSM7) for cells, and
always PSM3 for page/masked-region/direct one-argument calls. One provider retains its shared admission
limit; no mode guessing, retries, separate engines, dictionaries or source-specific correction.

Sources and policy assumptions follow the [official Tesseract guide](https://tesseract-ocr.github.io/tessdoc/ImproveQuality.html#page-segmentation-method).
The original ten-argument options constructor defaults to AUTO. All existing limits, borrowed-image
ownership, typed failures, cancellation and raw cell/page budgets still apply.

The [preceding manifest](../pdf-table-corpus/manifest.json) pins the independent Tabula Schools PDF
and published cell expectations; its crop, synthetic empty-column exclusion, rights and scope limits
remain unchanged. These image-only derivatives are rendered native PDFs, not physical scans. All
runs use actual Tesseract5.5.3, the same English model and PDFBox3.0.8; executable/model/source/report
hashes are retained. Runtime/native package provenance remains in [the earlier runtime record](../pdf-ocr/runtime-and-packages.json).

| Runtime | Cell mode | DPI | Visible structure | Matching normalized cells | Mismatch indexes |
| --- | --- | --- | --- | --- | --- |
| Java21 | SINGLE_BLOCK | 200 | 6×10 | 55/60 | 3,5,7,42,51 |
| Java25 | AUTO | 200 | 6×10 | 55/60 | 3,5,7,42,51 |
| Java25 | AUTO | 300 | 6×10 | 59/60 | 42 |
| Java25 | SINGLE_BLOCK | 300 | 6×10 | 59/60 | 42 |
| Java25 | SINGLE_LINE | 300 | 6×10 | 57/60 | 26,42,46 |

Every mode retains AnimalSounds' ordinary OCR/no Table fallback because its grid is open. Text
matches collapse whitespace only. Reports intentionally retain hashes and mismatch indexes, not
third-party cell text; recognition differences remain diagnostic rather than perfect-accuracy gates.
The remaining 300-DPI discrepancy is an inter-letter whitespace boundary: pixels are present but
Tesseract joins the separated letters. Block and line modes do not fix it. Single-line mode additionally
hallucinates text in two empty cells (indexes26,46), so defaults are unchanged. No claim of better
corpus accuracy, human transcription approval, general noise robustness or Unstructured parity.

Owned real-engine `*-owned-cells.xml` controls on Java21/25 check SINGLE_BLOCK on a two-line raster
and a blank raster. They preserve both exact lines and return no text for blank input. Deterministic
subprocess tests alternate page/cell calls concurrently on one provider, inspect actual process mode
arguments, check legacy direct calls, immutable copies/null rejection and output-limit propagation.
PDF controls verify explicit cell/page dispatch, multiline joins and legacy lambda delegation. The
full suite has710 Java tests with zero failures/errors/skips, strict Javadocs,12 Python guards and a
pinned formatter check. Hosted verification does not execute the real engine or this external corpus.

Replay with the [preceding corpus command](../pdf-table-corpus/README.md), adding
`-Ddocweave.pdf.tables.segmentation=AUTO`, `SINGLE_BLOCK` or `SINGLE_LINE`. Use a fresh report for every
run. The existing full real-engine properties also create `<report>.cells.xml` for the owned multiline/
blank control. No input, model or runtime download occurs in library/test code. Temporary caches
and runtimes are external prerequisites, not repository artifacts.

The optional official upstream refresh still observes release/main0.27.28 at
`1b02b4d189c260ed9b1aca29583203e233d853cb`; known historical0.27.27 freshness drift remains explicit,
with no automatic pin/golden rewrite. See upstream-status.json.

Next recommended task: merged-cell support in conservative scanned ruled tables, reusing bounded
native topology while preserving empty positions/spans and ownership. Broader human-reviewed scanned
corpora remain necessary. Approximate full PDF completion: **67%**, a planning estimate rather than
an accuracy or spend measure.
