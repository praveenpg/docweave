# Optional Tesseract OCR

Add `io.docweave:docweave-ocr-tesseract:0.1.0-beta.8` explicitly alongside your PDF application.
It depends on `docweave-pdf` and its native dependencies; it adds no Java library dependencies of its
own. It is deliberately excluded from the `docweave` convenience artifact and automatic discovery.
Install a trusted Tesseract 5 executable and the selected language models separately, following the
[official installation guide](https://tesseract-ocr.github.io/tessdoc/Installation.html).

```java
import io.docweave.DocWeave;
import io.docweave.ocr.TesseractOcr;
import io.docweave.ocr.TesseractOptions;
import io.docweave.partition.*;

var ocr = TesseractOcr.create(TesseractOptions.defaults());
var ocrPolicy = PdfOcrOptions.defaults(); // textless pages; opt into IMAGE_REGIONS for mixed pages
var pdf = PdfPartitioner.create(PdfOptions.defaults(), ocrPolicy, ocr);
var processor = DocWeave.create(PartitionerRegistry.builder().register(pdf).build());
var result = processor.partition(java.nio.file.Path.of("scan.pdf"));
```

The unreleased convenience builder also accepts `PdfStrategy.AUTO`, `FAST`, `OCR_ONLY` and
`HI_RES`. For example, replace the factory above with:

```java
final var pdf = PdfPartitioner.builder()
        .strategy(PdfStrategy.OCR_ONLY)
        .ocrProvider(ocr)
        .ocrOptions(PdfOcrOptions.defaults().withDpi(300))
        .build();
```

This recognizes every whole page and replaces native text emission, including searchable OCR
layers, while retaining original image placements. AUTO instead preserves native pages and
recognizes only textless pages. HI_RES requires `TesseractOcr.createWithWords(...)` plus an
explicit table-layout provider and defaults to300DPI. Engine/language installation stays explicit;
the presets do not download models or infer the document language. See the
[complete strategy contract](pdf-support.md#convenience-strategy-presets-unreleased).

Defaults resolve `tesseract` on the OS executable search path, select English, and retain the installed
model directory. Use the immutable `TesseractOptions` record for an explicit executable, tessdata
directory, ordered languages and limits. Language identifiers allow names such as `eng`, `deu`,
`chi_sim` and `script/Latin`. The adapter passes a joined language argument using `+`; identifiers
cannot contain options, whitespace or parent directory traversal. Paths are trusted application
configuration, not values to accept from a document. Neither executables nor models are fetched
at construction or recognition time.

The language list also supports an explicit ordered combination such as
`List.of("deu_latf", "deu")`; the corresponding local traineddata files are both required.
Order can affect recognition. The [fixed model-composition study](../benchmarks/review/pdf-scan-language-composition/README.md)
reduces one1835 source's character edits51->43 but loses one exact associated line, with
mixed outcomes elsewhere. This is an existing configuration capability, not automatic font/
language detection or a recommended universal replacement for a single model.

The adapter invokes the executable directly through `ProcessBuilder`, without a shell. It selects
LSTM (`--oem 1`), caller-selected page segmentation (default automatic without orientation detection, `--psm 3`) and TSV output
(`-c tessedit_create_tsv=1`). The [official command-line guide](https://tesseract-ocr.github.io/tessdoc/Command-Line-Usage.html)
describes languages, segmentation and TSV word geometry. Native OpenMP thread usage is limited with
`OMP_THREAD_LIMIT=1`; this is not a cap on every native thread or native allocation.

TSV word rows are joined with one space per word within each page/block/paragraph/line key. Lines
retain first-encounter order and their bounds enclose their words. The adapter itself provides no confidence filtering,
semantic category inference, deskew, RTL reconstruction, table recognition, spelling correction or
calibrated accuracy score. Strict UTF-8/schema, word geometry and output budgets are validated before
returning immutable line values. PDF integration supplies displayed crop coordinates and page metadata;
see [PDF support](pdf-support.md).

## Resources and failures

Defaults admit two simultaneous calls per provider instance, with a 60-second deadline including
admission wait, 16 million input pixels, 32 MiB temporary PNG bytes, 4 MiB stdout TSV, 64 KiB stderr,
10,000 lines and one million joined UTF-16 units per call. Separate instances have separate admission
limits. Image encoding and parsing are included in admission. An admitted process has two virtual
threads draining stdout and stderr concurrently. Stderr is counted and discarded, never included in
exceptions. Output overflow terminates the process immediately and raises `OCR_LIMIT`.

A private temporary directory holds one PNG; it is removed on success or failure. The adapter does
not save TSV or stderr files. Temporary-file permissions follow JDK/OS facilities; Windows ACLs and
secure disk erasure are not provided by the adapter. PNG encoding uses a memory-backed ImageIO
stream without changing global cache settings. Input/output byte checks are not a heap guarantee.

`OCR_FAILURE` retains startup, nonzero exit, encoding or TSV parsing causes, without adding OCR text
or stderr to diagnostics. Missing models/executables fail explicitly, with no silent native fallback.
`OCR_TIMEOUT` identifies admission or processing deadline expiry. Interruption preserves the flag
and uses `InterruptedIOException`. The same deadline is checked cooperatively during PNG writes and
TSV traversal; ImageIO, filesystem and OS startup/termination can delay checks. Visible descendants
and the direct process receive forced termination on exit/failure; detached/reparented subprocesses
are outside this guarantee. Use a trusted direct Tesseract executable, not an arbitrary process tree.

The process limit does not bound callers waiting for admission or PDF pages rendered before entering
the provider. Use the existing bounded document batch service to bound document-level concurrency.
The library provides no total-memory sandbox or strict end-to-end wall-clock guarantee.

## Validation

Default Maven verification runs owned Java subprocess controls for arguments, process failures,
malformed UTF-8/TSV, stdout/stderr floods, timeouts, interruption, temporary cleanup and admission
reuse, plus bounded TSV geometry/grouping tests. These controls do not perform OCR.

Real OCR is opt-in and requires all three properties together. Supply fresh report paths:

```sh
mvn -B -ntp -pl docweave-ocr-tesseract -am test \
  -Ddocweave.tesseract.executable=/absolute/path/to/tesseract \
  -Ddocweave.tesseract.tessdata=/absolute/path/to/tessdata \
  -Ddocweave.tesseract.report=/tmp/docweave-real-ocr-fresh.xml
```

`TesseractInstalledTest` creates an owned two-line raster and image-only PDF, checks exact text in both,
retained image output and explicit missing-language failure. Its fresh XML evidence records input,
executable and English-model hashes, font, Java and OS. With no properties, it returns without invoking
an engine; ordinary CI neither installs nor runs Tesseract. Linux 5.5.3 local evidence is saved under
`benchmarks/review/pdf-ocr/`. This narrow synthetic control is not independent scanned-document accuracy,
Unstructured parity, or Windows/macOS execution evidence. The Windows Word VM is not required.

`TesseractMixedInstalledTest` additionally recognizes an owned mixed page with native text inside
and outside its overlapping image placements. It requires the same three real-engine properties and
writes a second fresh report at `<report>.mixed.xml`. To exercise the pinned independent handwritten
scan with an owned native heading added, also provide `-Ddocweave.tesseract.mixed.pdf=/path/to/ccitt4-cib-test.pdf`.
Byte-size, Git blob and SHA256 pins are checked before use; the test never fetches it. Evidence and
scope: `benchmarks/review/pdf-mixed-ocr/`. Default CI returns without starting an OCR engine.

Opt-in mixed-page masking and geometric deduplication are described in [PDF support](pdf-support.md).
The PDF integration can separately opt into `PdfOcrOptions.TableDetection.RULED` to detect closed
rendered grids with rectangular merges and invoke this provider once per anchor cell, then for remaining page content. Native/table
ownership, aggregate page budgets and pixel thresholds are documented in [PDF support](pdf-support.md).
`TesseractTableInstalledTest` uses the same real-engine properties and writes `<report>.tables.xml`,
checking exact owned cell text/HTML, retained source image and surrounding prose without duplication.
Ordinary hosted CI does not invoke any real-engine control.

Rectangular merged scanned tables now retain `rowspan`/`colspan` and full anchor interiors.
Broader reviewed physical-scan/layout/table coverage remains pending; cell coordinates are implemented in the PDF layer.
owned controls and selected independent rendered crops do not establish general OCR parity.


## Explicit table-cell segmentation

PDF integration now calls `PdfOcrProvider.recognize(image, InputKind)` with `PAGE` for whole-page/native-masked
rasters and `TABLE_CELL` for padded interiors. The default overload delegates to the original abstract
one-argument method, so existing providers and lambdas remain valid. Providers can override the overload
without inferring purpose from raster dimensions. Ownership, pixel coordinates and shared raw-output
budgets apply to both kinds. Cells can be blank or multiline.

Select a cell mode explicitly when creating Tesseract options:

```java
final var engine = TesseractOcr.create(TesseractOptions.defaults()
        .withCellSegmentation(TesseractOptions.CellSegmentation.SINGLE_BLOCK));
```

`AUTO` retains the original PSM 3 default; `SINGLE_BLOCK` uses PSM 6 for a uniform, possibly multiline
block; `SINGLE_LINE` uses PSM 7 only when the caller knows a cell is a single line. These names follow
[official layout assumptions](https://tesseract-ocr.github.io/tessdoc/ImproveQuality.html#page-segmentation-method).
Page/native-masked-region calls and direct one-argument calls retain PSM3 unless PageSegmentation is
explicitly selected. The original ten-argument constructor and former eleven-argument cell constructor
preserve automatic page mode. Modes are immutable and passed per call, with a shared
provider admission limit rather than separate engines or mutable mode switching.

No mode is automatically selected or retried, and no mode guarantees improved accuracy. In the pinned
Schools crop, AUTO and SINGLE_BLOCK both match 59/60 cells at 300 DPI; the remaining mismatch joins two
separately spaced letters. SINGLE_LINE also invents text in two blank cells and matches 57/60. At 200 DPI,
AUTO and SINGLE_BLOCK both match 55/60. The default is retained. These are diagnostic published-expectation
comparisons, not human-approved scans or general accuracy scores; see
[the segmentation evidence](../benchmarks/review/pdf-cell-segmentation/README.md).

The optional real-engine `TesseractCellInstalledTest` uses the existing executable/model/fresh-report
properties. It checks owned multiline and blank cell rasters with SINGLE_BLOCK and writes
`<report>.cells.xml`. Ordinary CI performs no engine execution. No OCR model or runtime is bundled.


`TesseractMergedTableInstalledTest` uses the same real-engine properties and writes
`<report>.merged-tables.xml`. It checks an owned raster 3×3 atomic grid containing one multiline
2×2 merged anchor and five other cells, exact span HTML, retained source image and ordinary prose
exactly once. It configures SINGLE_BLOCK explicitly; the adapter and PDF defaults are unchanged.
See [the merged-table evidence](../benchmarks/review/pdf-scanned-merged/README.md).

Enable `PdfOptions.defaults().withTableCellCoordinates(true)` in the configured partitioner to obtain
versioned `pdf_table_cells` metadata for OCR tables. Full cell/merged extents use displayed unscaled
PDFUserSpace, converted from actual rendered page dimensions; they are not OCR word boxes or padded
cell-image pixel coordinates. This adds no recognition calls and leaves segmentation choices unchanged.
The installed merged-table control now also checks the six anchor descriptions and the known merged
rule bounds; `<report>.merged-tables.xml` records that check. See [the coordinate contract](pdf-support.md#optional-per-cell-geometry).

`PdfOptions.withTableContinuation(PdfOptions.TableContinuation.REPEATED_HEADER)` can independently
link eligible adjacent OCR ruled tables. It retains each page's full text, repeated header, HTML,
images and coordinates, with backward links to original source Table IDs. No extra OCR recognition
calls, native/OCR cross-links or fuzzy header correction are introduced. The conservative matching
conditions and chunk/provenance policy are in [PDF support](pdf-support.md#optional-adjacent-page-table-links).
`TesseractContinuationInstalledTest` uses existing explicit executable/model/fresh-report properties
and writes `<report>.continuations.xml` for its owned two-page raster control. Default CI executes no
real-engine control. This is not independently reviewed semantic-continuation accuracy.

Opt-in PdfOcrOptions.ReadingOrder.GEOMETRIC now unifies native and OCR output at page flush,
without changing provider order or its line geometry contract. SOURCE_ORDER remains default.
Original cTDaR scan annotations/structural diagnostics and model caveats are recorded in
benchmarks/review/pdf-physical-scans/; they do not supply an OCR transcription accuracy oracle.


## Explicit page segmentation

```java
final var policy = TesseractOptions.defaults()
        .withPageSegmentation(TesseractOptions.PageSegmentation.SPARSE_TEXT)
        .withCellSegmentation(TesseractOptions.CellSegmentation.SINGLE_BLOCK);
final var engine = TesseractOcr.create(policy);
```

`PageSegmentation.AUTO` retains historical PSM3, `SINGLE_BLOCK` selects PSM6 for one uniform text block,
and `SPARSE_TEXT` selects PSM11 for sparse text without a logical reading-order guarantee. These are
trusted enum arguments, never document-controlled CLI options. Both option-copy methods retain the
other purpose's policy and all limits. Direct one-argument recognition uses the page mode. PAGE and
TABLE_CELL calls may run concurrently through one provider's existing admission/deadline/resource
policy without mutable process configuration. Original constructors/defaults and cell assumptions
remain compatible. No mode selection, OCR retry, rotation/model download or layout inference is implicit.

Unreleased addition after beta.6: `PageSegmentation.SINGLE_COLUMN` selects PSM4 for a
caller-known single text column with variable sizes. It keeps the independent TABLE_CELL
policy and all admission/deadline/size limits. AUTO remains the default. This assumption can
misread genuine multicolumn layouts; no reference text or automatic retry selects the mode.
The public enum gains a constant, so exhaustive switches need to handle it when recompiling.

Choose layout explicitly from the application's document knowledge. A uniform-block assumption can
misread columns/figures, and sparse extraction can alter ordering. PDF integration can optionally sort
native/OCR geometry; that still does not establish logical reading order. Changing segmentation does
not infer table structure or calibrate confidence. Corpus profiles record DPI/page/cell assumptions
separately; compare fixed labels before drawing domain-specific conclusions.

## Explicit quarter-turn orientation

`TesseractOrientation` implements the PDF `PdfOrientationEstimator` interface. Install
`osd.traineddata` separately, then pass an explicitly configured estimator to the named
`PdfPartitioner.createOriented` factory:

```java
var estimator = io.docweave.ocr.TesseractOrientation.create(
        io.docweave.ocr.TesseractOrientationOptions.defaults());
var pdf = PdfPartitioner.createOriented(PdfOptions.defaults(),
        PdfOcrOptions.defaults().withTableDetection(PdfOcrOptions.TableDetection.RULED),
        ocr, estimator);
```

The five-argument factory additionally accepts actual-word OCR and an explicit table-layout
provider. Estimation runs once on each selected rendered/native-masked input before PAGE,
layout and CELL recognition. Accepted quarter turns use a single owned upright raster; parent
and cell coordinates return to the original displayed crop frame. Source image payloads stay
in their original orientation. Corrected OCR tables do not infer continuation links.

OSD uses `-l osd --oem 0 --psm 0`, independently of English recognition. Defaults bound the
whole estimate to30seconds, one admitted call,16million pixels,32MiB PNG,16KiB stdout and
64KiB stderr. Admission, encoding, subprocess handling and parsing share that deadline.
The default minimum raw orientation score is15, following the
[upstream API guidance](https://github.com/tesseract-ocr/tesseract/blob/5.5.3/include/tesseract/baseapi.h).
This is an uncalibrated score threshold, not an accuracy percentage. Low scores and the specific
empty-output/too-few-characters exit abstain with zero correction; missing models, malformed
output, timeouts and other engine failures remain explicit typed failures.

Metadata records `pdf_ocr_orientation_correction`, `pdf_ocr_orientation_status`,
`pdf_ocr_orientation_raw_score` and `pdf_ocr_orientation_provenance`. A caller-supplied
estimator owns its resource bounds; the optional managed adapter supplies process limits.
Construction performs no executable/model probing, download or process launch.

Enable the additional installed-engine control with
`-Ddocweave.tesseract.orientation.enabled=true` alongside the existing executable, tessdata
and fresh report properties. It writes the additional `.orientation.xml` report. Authored
serif controls exercise all four rotations through real ruled-table extraction; sans-serif
scores also retain abstentions. These controls do not establish physical-scan accuracy,
script/language inference, arbitrary-angle deskew or calibrated confidence.


Independent orientation diagnostics are also available in the development validation module:
`docweave.pdf.orientation.cache`/`.report` evaluate the pinned official Tesseract rotation
fixtures at default15 and explicit unfiltered0. `docweave.pdf.scans.orientation=true` and
`docweave.pdf.pubtabnet.orientation=true` independently enable OSD in their existing corpus
pipelines. These flags require installed OSD data and do not download it. See
[orientation evidence](../benchmarks/review/pdf-orientation/README.md) for asset provenance,
report limits and retained abstentions. Threshold0 is a diagnostic control, not a recommended
confidence setting.


## Optional cell-edge rule removal

`options.withCellBorders(TesseractOptions.CellBorders.REMOVE_EDGE_RULES)` explicitly
prepares TABLE_CELL inputs before PNG encoding. PAGE inputs and the default `RETAIN`
policy preserve their original pixels. On cells at least64x64pixels, the helper checks
only the first/last24rows and columns. Lines with at least80percent dark pixels are
whitened in an owned same-size RGB copy; other pixels and returned coordinates stay in
the input frame. Dark means alpha at least128 and an RGB component sum below384.
It does not crop, rescale, deskew, infer topology or use recognized text to select rules.

The scan/copy is inside the existing admission and total deadline, after the input pixel
limit check. Scanning checks cancellation every256pixels, uses at most96line indices
and no full-image scratch; changes allocate one additional raster bounded by the same
maxImagePixels. The owned copy is flushed after encoding/recognition, including failures;
borrowed source pixels are never modified or flushed. PNG and TSV budgets still apply.

This heuristic can remove meaningful edge rules or underlines and may miss weak, short,
skewed or more distant borders. It is an opt-in pixel policy, not a guarantee of better
recognition. Source image elements remain unchanged. The installed quarter-turn fixture
checks both historical RULED/RETAIN and explicit PROVIDER_LAYOUT_CELLS/REMOVE_EDGE_RULES
with the same exact six-cell expectation, outside text and original coordinate bounds.
The earlier unmodified generic-cell failure remains historical evidence.

The PubTabNet evaluation accepts `-Ddocweave.pdf.pubtabnet.cellBorders=REMOVE_EDGE_RULES`;
its default is RETAIN and the report records `cell_borders` separately from segmentation.
Use uniform profiles and independent labels when comparing results; authored-grid
improvements do not establish general table OCR accuracy.


For OCR-only correction that preserves actual words, wrap `TesseractOcr.createWithWords`
with `PdfWordOcrProvider.rotated` or `PdfWordOcrProvider.deskewed`, then inject that interface
into word-driven PDF modes. Table-layout inference still borrows its original input raster;
see [word correction contracts](pdf-support.md#word-preserving-ocr-correction-decorators).
The official-fixture opt-in also writes a `.words.json` sidecar for explicit published-angle
word/transcription/source-bounds controls. It uses English recognition without OSD decisions.

## Explicit internal binarization

Choose a fixed Tesseract5 image policy independently of page and cell segmentation:

```java
var options = TesseractOptions.defaults()
        .withPageSegmentation(TesseractOptions.PageSegmentation.AUTO);
var ocr = TesseractOcr.create(options, TesseractThresholding.SAUVOLA);
var words = TesseractOcr.createWithWords(options, TesseractThresholding.ADAPTIVE_OTSU);
```

`ENGINE_DEFAULT` omits the override and preserves existing factory behavior. `OTSU` selects
global thresholding; `ADAPTIVE_OTSU` selects Leptonica tiled Otsu; `SAUVOLA` selects local
adaptive binarization with the engine's window/factor defaults. These map to the fixed
Tesseract5 `thresholding_method` values0/1/2. See the official
[Tesseract image-quality documentation](https://tesseract-ocr.github.io/tessdoc/ImproveQuality.html).
The new overloads keep the existing `TesseractOptions` record shape and constructors intact.

No source image is changed in place and word/line coordinates remain in the borrowed raster
frame. Thresholding occurs inside the admitted engine process for both page and cell calls;
segmentation stays call-local and concurrent calls share the same provider admission limit.
Original pixel, temporary-file, queue/deadline, output and parser budgets still apply.
Construction remains lazy; no engine probe, model/config download or external execution occurs.
Callers supply their own installed models and trusted Tesseract5 executable as before.

Adaptive thresholding can erase strokes or emphasize noise. It is an explicit alternative,
not an accuracy guarantee or automatic per-document choice. Keep segmentation and other
settings fixed when comparing methods, retain failures, and distinguish page text from
printed-text reference coverage. Existing factories continue to use the engine default.

## Explicit raster resolution

When the actual raster resolution is known, supply it with the binarization policy:

```java
var renderOptions = new PdfOcrOptions(300, 16_000_000, 10_000, 1_000_000);
var ocr = TesseractOcr.create(
        TesseractOptions.defaults(), TesseractThresholding.SAUVOLA, 300);
var pdf = PdfPartitioner.create(PdfOptions.defaults(), renderOptions, ocr);
var words = TesseractOcr.createWithWords(
        TesseractOptions.defaults(), TesseractThresholding.SAUVOLA, 300);
```

The third argument passes numeric `--dpi` metadata to Tesseract. It does not resample or
enhance the image. Supported values are70 through2400, matching Tesseract5's
[credible resolution bounds](https://github.com/tesseract-ocr/tesseract/blob/5.5.3/include/tesseract/publictypes.h).
Existing factories omit the override and preserve engine resolution handling.

Use the resolution of the raster actually supplied to the provider, including prior
resampling. A PDF page rendered at300DPI is a300DPI raster even when its embedded scan has
a different original resolution. Native-size cell crops preserve their parent's resolution;
upscaling decorators change it. Use separate providers for different resolutions; the
adapter cannot infer physical resolution from `BufferedImage` or adjust it per call.
Do not guess a historical scan's DPI from its pixel dimensions alone.

Resolution affects engine layout and adaptive threshold windows, so an incorrect value can
worsen recognition. This explicit setting guarantees neither better text nor general OCR
accuracy. Borrowed pixel/word coordinate frames, admission, deadlines, cleanup and output
limits retain their existing contracts.

The [fixed source-resolution replay](../benchmarks/review/pdf-scan-source-resolution/README.md)
retains all24 historical/degraded case/model/threshold outcomes. Correct source DPI improves
some adaptive results and worsens others; it does not justify an automatic default change.

## Explicit page background normalization

For pages with uneven illumination, an unreleased opt-in page policy is available:

```java
final int rasterDpi = 300; // Actual resolution of every image supplied to this provider.
final var ocr = TesseractOcr.create(
        TesseractOptions.defaults(),
        io.docweave.ocr.TesseractThresholding.ENGINE_DEFAULT,
        rasterDpi,
        io.docweave.ocr.TesseractPreprocessing.LOCAL_WHITEPOINT);
final var pdf = PdfPartitioner.create(PdfOptions.defaults(),
        new PdfOcrOptions(rasterDpi, 16_000_000, 10_000, 1_000_000), ocr);
```

`createWithWords` accepts the same four arguments for actual word-box output. Use
separate providers for different raster resolutions; pass the resolution after any
resampling. `NONE` and earlier factories retain historical behavior. The policy
applies to PAGE requests only. TABLE_CELL requests keep existing cell preparation.
No model, image analysis or strategy builder silently chooses normalization.

This policy estimates local95th-percentile luminance whitepoints and interpolates
between fixed physical-size tiles. It composites alpha over white and produces a
same-size grayscale image. Input pixels remain untouched and output OCR boxes stay
in the original raster coordinate frame. Preparation shares existing pixel limits,
process admission and total request timeout; it does not add a separate deadline.
Owned temporary raster copies and PNGs are cleaned up on success and failure.

Results are mixed. On one fixed1778 low-contrast page, associated reference lines
increase17→31/38(German) and18→31/38(Fraktur), with Fraktur exact lines3→5. Extra
predictions also increase. The1780 stain-labelled page loses an association, and
1835 character edits slightly worsen. Legitimate gray fields and color may be erased.
These are reasons to select the option deliberately, not a general quality guarantee.
See [all source outcomes and reproduction](../benchmarks/review/pdf-scan-background-experiment/README.md).

A [two-page transfer check](../benchmarks/review/pdf-scan-background-transfer/README.md)
retains both models and complete references on previously unmeasured pages of the same
works. On1778/0066, associations improve4→5/15 and extra predictions10→8 for both
models; exact-line counts stay unchanged. The1780 page retains26/26 associations,
with mixed XML-sequence errors. This does not establish universal improvement.
