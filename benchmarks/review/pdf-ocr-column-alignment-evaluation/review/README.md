# Independent review of the OCR column-alignment evaluation (2026-10-22)

**Outcome: the rejection stands, and the next candidate stays parked.** The review replayed the
eight retained word captures offline and found no harness or scoring defect. No acceptance
threshold, model or DPI was changed. No genuinely distinct mechanism has independent support, so
no new candidate was frozen or implemented.

This is validation-only work. It changes no production source, API, default or test, and no
historical result. No OCR engine or model was executed (`engine_executions` is 0 in
`review-java21.json`), and nothing was downloaded, installed or sent to a remote service.

## What was checked

`benchmarks/tools/ReviewOcrColumnAlignment.java` is a separate review tool, written from the
frozen protocol text rather than copied from the evaluation harness. For each of the 8
observations (4 scans × `deu`/`deu_latf`), its checks gave these results:

| Check | Result |
| --- | --- |
| Harness WIDE_GAPS equals production `TesseractTsv.segments`/`line` (invoked reflectively) | 8/8 equal |
| Independently detected peaks (x and support, in rank order) equal the report | 8/8 equal |
| Winner is the same under the conventional median (mean of the two middle values) | 8/8 same |
| Added splits equal the report (11, 11, 18, 18, 13, 12, 6, 6) | 8/8 equal |
| Word-token sequence preserved by regrouping | 8/8 preserved |
| Acceptance recomputed from the reported metrics with the frozen rule equals the report | 8/8 equal, all FAIL |
| Prediction text hashes (raw, WIDE_GAPS, alignment) equal the report | 8/8 equal |
| Decoded TIFF RGB hash = capture `input_rgb_sha256` = historical `source_rgb_sha256` | 4/4 scans |

**Replay.** The committed harness in `replay` mode produced a report that is JSON-identical to
`../evaluation-java21.json`. The private captures were unchanged before and after the replay, the
review and the diagnostic. Two runs of the review tool produced byte-identical reports.

**Hashes.** All 15 hashes in the private verification receipt match: 8 captures, protocol,
manifest, report, harness, scorer test source, libtesseract and libleptonica. One provenance
limit remains. The harness hash recorded at compile time before capture (`337e1a9a…`) differs
from the committed source (`de11b737…`), and the capture-time source was not retained. The claim
that the later edits only added Javadoc tags and removed imports is supported by the identical
replay output, not by a source diff.

## Findings

1. **No harness or scoring defect.** All denominators are kept: every page, missing line, extra
   prediction and joint error is counted. The full-page association, missing/extra counts, joint
   errors, token preservation and XML-order diagnostics all reproduce.
2. **Two regressions not shown in the parent README table.** Both come from `../evaluation-java21.json`:
   - 1778 XML-order reversed pairs rise from WIDE_GAPS to alignment: `deu` 30→63, `deu_latf` 30→74.
     Correct pairs also rise (321→372 and 321→454), and unmatched pairs fall (352→268 and
     352→175).
   - 1778 missing reference lines fall: `deu` 11→8, `deu_latf` 11→5.

   The parent table already shows the higher associated count, which comes with more extras and
   more joint errors. Neither change alters the verdict.
3. **The DPI wording is imprecise; the baseline caution is correct.** The parent README says the
   older 1778 study "used an explicit 300 DPI engine override". In fact the historical integrated
   reports record an explicit `engine_source_dpi` for all four scans (1835 300, 1879 600, 1778 300,
   1780 300). This evaluation passed none (engine default).
   - Each TIFF declares the same resolution the historical runs passed (`tiff-declared-resolution.tsv`).
     The RGB raster given to the engine carries no DPI, however.
   - The WIDE_GAPS prediction-text hashes equal the historical ones for 1835, 1879 and 1780 under
     both models. They differ only for 1778: associated/extra `deu` 26/17 historically versus
     27/13 now, and `deu_latf` 27/12 versus 27/11 (`dpi-comparison.tsv`).
   - The cause of the 1778 difference is not isolated. Isolating it would need a new OCR run,
     which was not authorized and is not needed for this decision.
   - The historical baselines are kept unchanged and must not be compared as if the two
     protocols were the same.
4. **Clerical issues in the parent README** (no measurement affected). Several numbers run into
   the preceding word: "Exactly8", "requires6", "On1778", "x1024", "x996", "integrated1778",
   "explicit300DPI" and "Java21". These are recorded here rather than rewritten in place.

## Is there a distinct next mechanism?

`peak-diagnostic.txt` (source in `DiagnoseAlignmentPeaks.java.txt`) describes every eligible
peak in all 8 captures. It uses word geometry only: no transcript, reference box or source
identity.

- On every control (1835, 1879, 1780) and every control peak, no supporting boundary gap exceeds
  twice the median of the line's other word gaps. On 1778, the competing x≈996 peak has 5/9 such
  lines (mean ratio 4.46 `deu`, 4.40 `deu_latf`), against 2/14 and 1/13 for the winning x≈1024.
- **However, WIDE_GAPS already splits 4 of those 5 boundaries.** The other 4 lines supporting
  x≈996 have ordinary word spacing. This matches the reference geometry in
  `../../pdf-bounded-passes-e-f/`, where 5 of 11 two-column rows have gaps of 0.52–2.9 line
  heights.
- A gap-gated alignment rule would therefore add at most one split per model beyond WIDE_GAPS on
  the only positive page. Selecting x≈996 by a gap score would effectively choose the second peak
  after seeing the failure. The hypothesis also comes from the same four development scans, so
  any acceptance criteria frozen now would be circular.

**Disposition: PARKED.** This is a mechanism-level conclusion, not a threshold change. No
candidate was written or run. Reopening needs the resources listed in the private return packet:

- a different two-column degraded scan with a line transcription that DocWeave has not used;
- or independent column-extent labels on such scans;
- plus authorization to run the existing pinned local Tesseract on them.

## Files

| File | Content |
| --- | --- |
| `review-java21.json` | Review report: counts, coordinates, hashes and boolean checks; no text |
| `peak-diagnostic.txt` | Every eligible peak with its support, run length, gap ratio, WIDE_GAPS overlap and vertical span |
| `DiagnoseAlignmentPeaks.java.txt` | Diagnostic source (retained as text; not compiled by the build) |
| `dpi-comparison.tsv` | Historical versus evaluation engine DPI and WIDE_GAPS output, per observation |
| `tiff-declared-resolution.tsv` | Resolution declared by each original TIFF |
| `sha256sums.txt` | Hashes of the files above |

## Reproduction (Java 21, offline, no OCR)

```sh
read -r cp < /tmp/docweave-resume-classpath.txt
cp="$cp:$PWD/docweave-ocr-tesseract/target/classes"
javac --release 21 -Xlint:all -Werror -Xdoclint:all/private -cp "$cp" -d REVIEW_CLASSES \
  benchmarks/tools/ReviewOcrColumnAlignment.java
java -Xmx1024m -Djava.awt.headless=true -cp "REVIEW_CLASSES:$cp" \
  dev.docweave.validation.ReviewOcrColumnAlignment \
  benchmarks/review/pdf-ocr-column-alignment-evaluation/manifest.json /tmp/docweave-scan-segmentation \
  ../docweave-source-review/pdf-ocr-column-alignment-20261022 \
  benchmarks/review/pdf-ocr-column-alignment-evaluation/evaluation-java21.json \
  benchmarks/review/pdf-ocr-wide-gap-lines/integrated-degraded-java21.json \
  benchmarks/review/pdf-ocr-wide-gap-lines/integrated-historical-java21.json NEW_REVIEW_REPORT
```

To run the diagnostic, copy `DiagnoseAlignmentPeaks.java.txt` to a `.java` file and compile it
against `REVIEW_CLASSES:$cp`. Then run `dev.docweave.validation.DiagnoseAlignmentPeaks MANIFEST
CAPTURES`.
