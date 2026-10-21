# Independent rendered-table validation

This fetch-only corpus pins Tabula Java commit `2cdf3b4fd3f7e921dca8cc6814cdd9316be40f0f`.
The manifest records immutable URLs, byte counts, SHA-256 and Git blob SHA-1 for the inputs.
PDFs, raster derivatives, cell text and model binaries are not redistributed here. Tabula's repository
license does not establish original authorship or per-file redistribution rights for every PDF.

`schools.pdf` page 1 is rendered with PDFBox 3.0.8, including its 90-degree page rotation.
A fixed displayed-space crop starts at (16, 53) with size 748 × 65 PDF units; integer pixel bounds
truncate after multiplying by DPI/72. It includes the first six visible rows. The published
`schools.json` provides expected cells from those rows. Its first, synthetic empty column lies outside
the visible grid and is explicitly excluded: the expected shape is 6 rows × 10 columns. This selection
is independent of DocWeave's output. Raster pixels are embedded in an image-only PDF and passed
through the configured OCR partitioner. The reference is Tabula's saved extraction expectation,
not a new human-approved transcription. These are rendered native PDFs, not physical scans.

`AnimalSounds.pdf` page 1 has horizontal separators and no vertical rules. It is rendered in full;
the explicit RULED strategy must retain ordinary OCR instead of inventing a table. Its text oracle is
not used to claim recognition accuracy. A small recognition sanity check requires the word Cat.

The opt-in `PdfScannedTableCorpusTest` checks source identities, one source image, the complete
6×10 structure and the negative control. Cell text comparisons collapse whitespace only; differences
are diagnostic and **do not fail the structural regression test**. Reports retain mismatch indexes,
joined-cell hashes and source/model/executable identities without copying document text. A green
build therefore does not mean every cell was recognized correctly. Default hosted CI runs the
configuration guard only, with no network, PDF cache or installed OCR requirement.

Local Tesseract 5.5.3 / English model results:

| Render/OCR DPI | Structure | Cells matching published expectation | Negative control |
| --- | --- | --- | --- |
| 200 | 6×10 | 55/60 | Ordinary OCR, no table |
| 300 | 6×10 | 59/60 | Ordinary OCR, no table |

Java 21 and 25 replays are recorded separately. At 300 DPI the remaining differing zero-based
cell index is 42; no source-specific correction or golden rewrite was made. At 200 DPI differing
indexes are 3, 5, 7, 42 and 51. This tiny selected crop cannot establish general table accuracy,
DPI optimality, noisy-scan robustness or Unstructured parity. The library's default DPI remains 150;
these narrow rows need explicit higher DPI to meet the conservative minimum cell-height guard.

To replay, fetch each manifest input into a caller-owned cache with its `filename`, then run a full
reactor verify (or select this test with `-pl docweave-validation -am test`,
`-Dtest=PdfScannedTableCorpusTest -Dsurefire.failIfNoSpecifiedTests=false`), adding:

```text
-Djava.awt.headless=true
-Ddocweave.pdf.tables.cache=/path/to/cache
-Ddocweave.pdf.tables.report=/fresh/path/report.json
-Ddocweave.pdf.tables.dpi=300
-Ddocweave.tesseract.executable=/path/to/tesseract
-Ddocweave.tesseract.tessdata=/path/to/tessdata
```

Both corpus properties are required together; reviewed DPI values are 200 and 300. Reports are
created exclusively, never overwritten. Full verification with the existing engine properties also
requires the existing fresh `-Ddocweave.tesseract.report` base path, as described in the adapter guide.
The executable must find its trusted native libraries; local extracted-package provenance is in
[the runtime record](../pdf-ocr/runtime-and-packages.json). No automatic downloads are implemented.

Next recommended task: diagnose the retained cell OCR discrepancies and evaluate explicit cell
segmentation against a broader permitted corpus before merged/noisy/borderless expansion. Approximate
full PDF completion remains **65%**, a planning estimate rather than measured extraction accuracy.
