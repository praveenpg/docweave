# Standalone image partitioning (PNG, JPEG, TIFF)

`docweave-image` (added in 0.1.0-beta.20) partitions standalone PNG, JPEG/JFIF and TIFF files, including
multi-page TIFF. Each page is decoded, turned upright and recognized with an **explicitly configured OCR provider**,
the same `PdfOcrProvider` contract used for PDF OCR. Output is one `UncategorizedText` element per recognized line.
There is no image captioning, visual question answering, layout or table detection, vision-model call, automatic
download or image editing.

## Java usage

```java
import io.docweave.DocWeave;
import io.docweave.ocr.TesseractOcr;
import io.docweave.ocr.TesseractOptions;
import io.docweave.partition.*;

var ocr = TesseractOcr.create(TesseractOptions.defaults()); // tesseract + eng installed separately
var registry = PartitionerRegistry.builder();               // starts with the installed (service-loaded) formats
ImagePartitioner.createAll(ImageOptions.defaults(), ocr).forEach(registry::register);
var result = DocWeave.create(registry.build()).partition(java.nio.file.Path.of("scan.tiff"));
```

`ImagePartitioner.create(DocumentFormat.PNG, options, ocr)` creates a single format. The same provider instance can
serve a `PdfPartitioner`, so one Tesseract admission limit covers PDF pages and image pages.

**Prerequisites.** `docweave-image` needs only the JDK's ImageIO decoders (PNG, JPEG and TIFF are built into Java
9+) and the `docweave-pdf` module for the shared OCR contract. Text extraction needs an OCR provider such as
`docweave-ocr-tesseract` with a separately installed `tesseract` executable and language data; see
[ocr-tesseract.md](ocr-tesseract.md). Nothing is downloaded or discovered implicitly.

**Without an OCR provider.** `docweave-image` is part of `docweave-all` and is discovered through `ServiceLoader`, so
`DocWeave.create()` detects and routes PNG, JPEG and TIFF inputs. Those service-loaded instances have no OCR provider
and fail with `PartitionException.Code.UNSUPPORTED_OPTION` ("… text extraction requires an explicitly configured OCR
provider"); they never return an empty "successful" result for an image whose text was not read.

## Detection

Content signatures decide the format; file names are a fallback only when the content has no image signature.

| Format | Signature | Extension fallback | MIME (`filetype`) |
| --- | --- | --- | --- |
| PNG | `89 50 4E 47 0D 0A 1A 0A` | `.png` | `image/png` |
| JPEG | `FF D8 FF` | `.jpg`, `.jpeg`, `.jpe` | `image/jpeg` |
| TIFF | `II*\0` or `MM\0*` (BigTIFF `II+\0`/`MM\0+` is detected and refused) | `.tif`, `.tiff` | `image/tiff` |

A signature that disagrees with the extension wins and adds a `FORMAT_MISMATCH` warning (a PNG named `scan.pdf` is a
PNG). An explicit `PartitionOptions.format()` of PNG, JPEG or TIFF is honoured without that warning. The extension
fallback applies only to binary content without another known signature: content routed by extension alone is
validated by the decoder and fails with `MALFORMED_DOCUMENT` if it is not that format, while PDF, ZIP/DOCX and OLE
content named `.png` keeps its own routing, and a text source (`DocumentSource.text`) named `.png` is handled as text
exactly as before. A text source with an explicit image format fails with `UNSUPPORTED_FORMAT`.

## Output

| Metadata | Value |
| --- | --- |
| `page_number` | 1-based page (TIFF directory order; PNG and JPEG have page 1) |
| `coordinates` | `system` `PixelSpace`, `layout_width`/`layout_height` = the upright page in pixels, `points` = the line's corners (top-left, top-right, bottom-right, bottom-left) |
| `image_text_method` | `ocr` |
| `image_orientation` | the EXIF/TIFF orientation (2–8) that was applied; absent when the stored image was already upright |
| `filetype`, `filename` | as for every format |

`PixelSpace` has its origin at the top-left of the **upright** page, x to the right and y downward, in decoded pixels
(no DPI scaling). Lines are emitted in the provider's order (Tesseract: its reading order).

**No text.** When OCR runs but recognizes nothing on a page, no element is emitted for it and the result carries an
`IMAGE_NO_TEXT` warning naming the page.

## Orientation

- **JPEG:** the Orientation tag (274) of the first EXIF APP1 segment before the first scan is applied (all eight
  values, including mirrored ones) before OCR. An absent or invalid tag means upright.
- **TIFF:** each page's own Orientation tag is applied.
- **PNG:** `eXIf` chunks are not read; PNGs are taken as stored.
- Pages with alpha are composited on white (transparent areas read as paper). Otherwise the decoded raster is passed
  to OCR unchanged; only an oriented or transparent page gets one RGB copy.
- There is no automatic rotation detection for images without an orientation tag (the PDF-only optional orientation
  estimator is not applied to images).

## Supported variants

Decoding uses the JDK's ImageIO readers (no native codec).

- **PNG:** the JDK reader's colour types and bit depths (grey, RGB, palette, alpha; 1–16 bits). Tests cover RGB,
  grey and RGBA (composited on white).
- **JPEG:** what the JDK reader decodes: baseline and progressive, greyscale and YCbCr; tests cover baseline YCbCr.
  The JDK reader does not decode CMYK/YCCK (Adobe), 12-bit, lossless or arithmetic-coded JPEG; such inputs fail with
  `UNSUPPORTED_FORMAT` (or `MALFORMED_DOCUMENT` when the reader cannot tell), and are not covered by fixtures.
- **TIFF:** uncompressed, LZW, Deflate/ZLib, PackBits, CCITT RLE/Group 3/Group 4 and JPEG (compression 7) strips or
  tiles; bilevel, grey, palette and RGB. Tests cover LZW, Deflate, PackBits, JPEG and CCITT Group 4. **Not
  supported:** BigTIFF (`UNSUPPORTED_FORMAT`). Old-style JPEG (compression 6) and CMYK/other photometric
  interpretations depend on the JDK reader and are not qualified.
- GIF, BMP, WebP, HEIC, JPEG 2000 and other image formats are not supported.

## Limits and failures

`ImageOptions` limits are checked from image headers **before any pixel is decoded**; pages are then decoded one at
a time and released after recognition.

| Limit | Default | Failure |
| --- | --- | --- |
| `PartitionOptions.maxInputBytes` | 10 MiB | `INPUT_TOO_LARGE` |
| `maxFrames` (pages) | 100 | `IMAGE_LIMIT` |
| `maxFramePixels` (width × height of one page) | 16,000,000 (Tesseract's default input limit) | `IMAGE_LIMIT` |
| `maxTotalPixels` (all pages) | 200,000,000 | `IMAGE_LIMIT` |
| `maxLinesPerFrame` / `maxCharactersPerFrame` (OCR output) | 10,000 / 1,000,000 | `OCR_LIMIT` |
| `PartitionOptions.maxElements` | as for every format | `ELEMENT_LIMIT` |
| OCR time and concurrent OCR processes | the provider's (`TesseractOptions.timeout`, `maxConcurrentProcesses`) | `OCR_TIMEOUT` / `OCR_LIMIT` |

- Truncated or corrupt data, including JPEG data that the decoder reports with a warning such as a premature end,
  fails with `MALFORMED_DOCUMENT`; no partial page is recognized. The one tolerated decoder warning is libjpeg's
  "extraneous bytes before marker" (stray bytes between segments, common in camera and scanner files, after which the
  image decodes completely).
- Each TIFF page's strips or tiles must lie inside the input. The JDK reader already refuses a directory whose data
  segment lies outside the stream, and the partitioner checks the strip/tile tables again before decoding, so a
  declared byte count cannot cause an allocation larger than the input. A directory chain that loops is bounded by
  `maxFrames`.
- Unsupported variants fail with `UNSUPPORTED_FORMAT`; OCR provider failures with `OCR_FAILURE`.
- Cancellation: see [tested and untested behavior](#cancellation-tested-and-untested-behavior).

**Input ownership.** The partitioner reads `PartitionContext.binaryBuffer()` in place through a seekable stream that
neither copies nor caches the input and never changes the buffer's position; the input is not modified.

**Memory.** Peak heap is roughly the input bytes, plus one decoded page (1–8 bytes per pixel depending on the decoded
type), plus one RGB copy for an oriented or transparent page, plus the elements emitted so far. The Tesseract adapter
also encodes each page it is given as PNG through an in-memory image stream (bounded by `TesseractOptions.maxPngBytes`)
before writing it to a temporary file. The OCR process (Tesseract) runs outside the JVM with its own memory.

## Measured memory (Tesseract 5.3.4, Java 21)

These are measurements. Owned synthetic pages (tiles of `text.png`), `ImageOptions.defaults()`, `maxInputBytes`
256 MiB, Tesseract `eng` with one OCR process, one document at a time, Java 21, 2026-11-09 UTC. "Live heap" is the
used heap after a full collection at the start of each page's OCR call, so it shows what is retained while a page is
recognized. Evidence: [benchmarks/review/image-upstream-0.27.28](../benchmarks/review/image-upstream-0.27.28/README.md).

| Input | Pages | Elements | Live heap at each page | Peak heap at -Xmx1g / -Xmx256m | Time |
| --- | ---: | ---: | --- | --- | ---: |
| PNG 4000 × 4000 (16.0 MP, 0.4 MB) | 1 | 72 | 50 MiB | 77 / 72 MiB | 2.2 s |
| TIFF LZW 2480 × 3508 (8.7 MP, 0.9 MB) | 1 | 42 | 30 MiB | 109 / 107 MiB | 1.3 s |
| TIFF LZW 20 × 2480 × 3508 (174 MP, 18 MB) | 20 | 840 | 47–48 MiB on every page | 177 / 198 MiB | 24.8 s |

The live heap is flat across the 20 pages: each decoded page (26 MB as 3-byte BGR) is released before the next, and
the 20-page TIFF completes with a 256 MiB heap, which could not hold its pages together (more than 520 MB decoded).
Peak heap includes uncollected garbage and depends on the collector; it is not a retained-memory figure. The OCR
process's own memory (outside the JVM) was not measured.

## Cancellation: tested and untested behavior

These are behavioral claims, separate from the measurements above.

- **Tested:** an interrupt raised while a page is being recognized stops processing before the next page is decoded
  or recognized; the call ends with `InterruptedIOException` (`ImagePartitionTest`).
- **Not deterministically tested:** aborting a page decode that is already in progress. The partitioner registers an
  ImageIO progress listener that calls `ImageReader.abort()` when the thread is interrupted, and checks the abort flag
  after the read; how soon a decode stops depends on how often the JDK reader reports progress. No test interrupts a
  decode mid-page, and no stop latency was measured.
- OCR time limits and cancellation inside the OCR engine belong to the provider (`TesseractOptions.timeout`); they
  were not re-measured for images.

## Comparison with Unstructured 0.27.28

Pinned Unstructured 0.27.28 (unstructured-inference 1.6.13, unstructured-pytesseract 0.3.15) with the same Tesseract
5.3.4, `partition_image(strategy="ocr_only", languages=["eng"])`, on the same owned fixtures. `hi_res` (the default for
images) needs a downloaded layout model and was not run. This compares observed behavior on five fixtures; it is not
an accuracy evaluation.

| Input | Unstructured 0.27.28 `ocr_only` | DocWeave |
| --- | --- | --- |
| `text.png`, `text.jpg` (3 lines) | 2 elements: `NarrativeText` (first two lines joined), `Title` | 3 `UncategorizedText` lines, same text |
| `text-exif6.jpg` (EXIF orientation 6) | 0 elements (the sideways stored page is read as is) | 3 lines, upright coordinates, `image_orientation` 6 |
| `pages-g4.tif` (3 pages, CCITT G4) | 1 element: page 1 only | 3 lines, `page_number` 1, 2, 3 |
| `blank.png` | 0 elements, no diagnostic | 0 elements and `IMAGE_NO_TEXT` |
| truncated PNG / JPEG | `OSError: image file is truncated` | `MALFORMED_DOCUMENT` |
| non-image bytes named `.png` | `UnidentifiedImageError` | `MALFORMED_DOCUMENT` |
| BigTIFF header | `UnidentifiedImageError` | `UNSUPPORTED_FORMAT` |

**Agreements:** PNG, JPEG and TIFF inputs; `PixelSpace` coordinates with the same page size; the same first-line
top-left corner of the first text (85, 69), also for DocWeave's EXIF-rotated JPEG; Unstructured's box then spans the joined block; no text for a blank page.
**Differences:** Unstructured groups lines into blocks and classifies them (`Title`, `NarrativeText`); DocWeave emits
unclassified lines. Unstructured's `filetype` is the format name (`PNG`), DocWeave's the MIME type. In this
configuration Unstructured neither applies EXIF orientation nor reads TIFF pages after the first.
**Not in DocWeave:** layout detection, element classification and table structure for images (`hi_res`), HEIC/BMP
and other formats Unstructured accepts through Pillow, and language detection.
