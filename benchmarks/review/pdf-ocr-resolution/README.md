# Explicit OCR resolution development check

This change supplies known raster resolution to the optional Tesseract adapter without
changing borrowed pixels, coordinates, previous factories or the options record.
It does not establish improved recognition on historical/degraded scans.

The new public interface overloads are `TesseractOcr.create(options, thresholding, sourceDpi)`
and `createWithWords(options, thresholding, sourceDpi)`. Values70 through2400 are accepted;
the bounds match Tesseract5.5.3's
[public resolution constants](https://github.com/tesseract-ocr/tesseract/blob/5.5.3/include/tesseract/publictypes.h).
Existing factories omit `--dpi`. No download, probe, resampling or automatic selection is added.

## JVM validation

Full offline `clean verify` passes on actual Java21.0.12.1+12-LTS and25.0.4+7-LTS:
1000 tests each, zero failures/errors/skips, including strict public Javadocs.
Strict private Javadocs pass for the changed production classes and new resolution test
on both JDKs. The pinned Java formatter and `git diff --check` pass.

`TesseractResolutionTest` uses real owned JVM subprocesses through the complete production
PNG/argument/process/TSV path. It covers historical omission plus70/300/2400DPI with all four
threshold policies, concurrent page/word-cell calls, line projection, actual word boxes,
unchanged caller pixels, temporary-file cleanup, lazy hidden factories, invalid/null input,
typed exit/timeout and character-limit failures. The child is a process control, not an OCR engine.

The opt-in `TesseractInstalledTest` also runs actual installed Tesseract5.5.3 with pinned English
traineddata on both JDKs. Every threshold policy at300DPI recognizes the owned two-line/six-word
control exactly in line and word views, preserves raster containment and borrowed pixels.
Existing direct/PDF/magnification/segmentation/missing-language assertions also pass.
The owned raster represents a300DPI scan; this is not a historical-source accuracy experiment.
The two XML reports retain executable/model/raster identities and runtime details.

Reproduce the ordinary checks with `mvn -B -ntp clean verify`. Run actual OCR explicitly with:

```sh
OMP_THREAD_LIMIT=1 mvn -B -ntp -pl docweave-ocr-tesseract -am test \
  -Dtest=TesseractInstalledTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Ddocweave.tesseract.executable=/path/to/tesseract \
  -Ddocweave.tesseract.tessdata=/path/to/tessdata \
  -Ddocweave.tesseract.report=/fresh/path/installed.xml
```

## Follow-up source evidence

JDK ImageIO TIFF metadata inspection of the four already pinned original scans finds equal
X/Y resolution with explicit inch units (TIFF tags282/283/296):

| Original source | X/Y DPI | Unit |
| --- | --- | --- |
| arnimb_goethe03_1835.tif | 300/300 | inch (2) |
| bebel_frau_1879.tif | 600/600 | inch (2) |
| buerger_gedichte_1778_0039.tif | 300/300 | inch (2) |
| nn_besuch_1780_00001.tif | 300/300 | inch (2) |

The same numeric tags are observed on both JDKs. These are source metadata declarations,
not independent measurements of physical scanning resolution. Source byte identities remain
in the previous [fixed manifest](../pdf-scan-segmentation/manifest.json); no source files change.

The earlier scan studies deliberately used one pixel per PDF point and a72DPI render for exact
RGB replay. Passing original TIFF DPI to that differently sized PDF container would mislabel its
render resolution. A future source-resolution study must preserve physical page dimensions,
assert exact rendered RGB equality, and map public PDF coordinates back to reference pixels
before scoring. No historical/degraded result is recomputed or improved by this metadata audit.
Do not retrofit historical metrics, guess DPI from dimensions or tune DPI against recognition.

All new tests use Java/JUnit. Existing Python tools remain unchanged pending completion of PDF
work, under the validation policy. Python execution remains optional for actual Unstructured reference capture.
