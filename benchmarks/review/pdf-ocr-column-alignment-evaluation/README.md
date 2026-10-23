# Frozen OCR column-alignment evaluation — rejected

## Decision

**DO NOT IMPLEMENT ALIGNMENT_COLUMN_V1.** The one frozen geometry-only rule fails primary
acceptance on all eight observations. No production source, API, default or test was changed.
This is evaluation evidence, not delivered extraction improvement. No model or document download,
installation, remote inference or new Unstructured execution occurred.

The user authorized this bounded local execution and a handoff. Protocol and manifest were
written before new word geometry; a private timestamped receipt records their hashes. The
harness was compiled/hashed before capture. Later edits added private Javadoc tags/removed
unused imports only; final-source offline replay equals the original report.

Exactly8 calls used the existing pinned Tesseract executable (9c19bce3…b917), both original
best models, all four full scans, one native thread and bounded adapter options. Actual words
were collected through the public createWithWords factory and retained privately. All calls
succeeded. Raw Tesseract lines, existing WIDE_GAPS and WIDE_GAPS plus alignment use the same
captured words; no recognition-text improvement can be claimed from regrouping.

## Hypothesis and outcome

The frozen rule clusters recurring internal word left edges, requires6 supporting original
lines, ranks by support then x, and adds at most one boundary per original line to the existing
wide-gap boundaries. It uses no reference labels, source names or document-specific x positions.
Full thresholds and tie handling are in frozen-protocol.txt; manifest.json pins sources/models.

On1778 it chooses the known competing interior alignment near x1024, not the apparent right
column start near x996. It also chooses internal prose alignments on the three controls. A
higher associated-line count on the target comes with more extras and joint text/association
errors, and therefore fails. No threshold or alternate winner was tried afterward.

| Source / model | Associated lines | Extra predictions | Joint reference-line character errors | Winning x | Acceptance |
| --- | ---: | ---: | ---: | ---: | --- |
| arnimb_goethe03_1835 / deu | 21→16 | 0→16 | 103→453 | 724.0 | FAIL |
| arnimb_goethe03_1835 / deu_latf | 21→16 | 0→16 | 50→421 | 724.0 | FAIL |
| bebel_frau_1879 / deu | 50→39 | 1→30 | 7→965 | 1536.0 | FAIL |
| bebel_frau_1879 / deu_latf | 50→39 | 1→30 | 53→994 | 1536.0 | FAIL |
| buerger_gedichte_1778_0039 / deu | 27→30 | 13→23 | 364→448 | 1024.0 | FAIL |
| buerger_gedichte_1778_0039 / deu_latf | 27→33 | 11→17 | 319→338 | 1024.0 | FAIL |
| nn_besuch_1780_00001 / deu | 9→5 | 9→19 | 51→201 | 848.0 | FAIL |
| nn_besuch_1780_00001 / deu_latf | 9→6 | 9→18 | 27→170 | 848.0 | FAIL |

These are WIDE_GAPS→WIDE_GAPS+alignment values. The report also retains original raw-line
metrics, missing counts, full reference/recognized character populations, text hashes, sequence
edits and XML-order correct/reversed/unmatched pairs for every observation. XML sequence is
not logical reading-order truth where line order is undeclared. No page/failure/extra is dropped.

All original word text and encounter order are preserved. Source transcripts are read only by
the scorer after geometry selection. The unchanged historical JVM scorer is reused reflectively
in this benchmark tool rather than exposing test internals as a production API. Detection and
regrouping functions receive only actual word geometry/text, never labels or source IDs.

## Protocol limits: preserve historical baselines

Pixels are Java ImageIO colour-managed RGB, copied to TYPE_INT_RGB with no resize/filtering.
Their hashes equal the historical integrated source RGB hashes on all four scans. This frozen
run used **no explicit engine DPI override**, with AUTO/OEM1/default thresholding. The older
integrated1778 degraded study used an explicit300DPI engine override. Consequently its OCR
text/line counts differ here despite the same pixels/models; do not replace or silently compare
those historical baselines as if the engine protocols were identical. Other tested sources'
wide-gap prediction-text hashes equal the retained historical ones. No second DPI run was
made after failure. This result rejects this candidate/protocol; it is not proof that every
alignment method fails under every DPI or scan pipeline.

These are four previously used development scans, not held-out qualification. No additional
true source or transfer evidence was obtained. The failure is particularly clear on controls,
so there is no justification to implement this rule or loosen the criteria.

## Reproduction and verification

Compile benchmarks/tools/EvaluateOcrColumnAlignment.java against the current validation runtime
AND test classpath on Java21. Raw word captures remain outside Git in
../docweave-source-review/pdf-ocr-column-alignment-20261022/ (source-bearing text).

```sh
read -r eval_cp < /tmp/docweave-resume-classpath.txt || true
javac --release 21 -cp "$eval_cp" -d NEW_CLASSES benchmarks/tools/EvaluateOcrColumnAlignment.java
java -Xmx1024m -Djava.awt.headless=true -cp "NEW_CLASSES:$eval_cp"   dev.docweave.validation.EvaluateOcrColumnAlignment replay   benchmarks/review/pdf-ocr-column-alignment-evaluation/manifest.json   /tmp/docweave-tesseract-runtime/usr/bin/tesseract   /tmp/docweave-tesseract-runtime/usr/share/tessdata   /tmp/docweave-scan-segmentation   ../docweave-source-review/pdf-ocr-column-alignment-20261022 NEW_REPORT
```

Replay never launches the engine. Fresh paths are required. A fresh `capture` instead of
`replay` would launch8 engine processes and requires explicit engine execution configuration,
and needs LD_LIBRARY_PATH=/tmp/docweave-tesseract-runtime/usr/lib. Do not rerun it merely to
review this report; retained capture hashes bind the evaluated output.

- Exactly8/8 capture outcomes SUCCESS; primary acceptance false for every observation.
- Final-source offline report replay is JSON-identical to the original capture report.
- Token encounter-text identity assertions pass for every raw/wide/alignment observation.
- Final Java21 compilation and strict private Javadoc checks pass; pinned clang-format23.1.1
  applied to this new tool. Initial doclint missing-tag warnings were corrected, no algorithm
  or threshold changed after capture. git diff --check passes.
- No full1,394-test or Java25 rerun: library runtime/test code is unchanged. Those remain
  historical product validation, not a claimed new test run for this diagnostic.

## Next bounded action

Keep this exact rule parked. The existing simple gap and strongest-alignment evidence both
show ambiguous geometry. A future candidate needs a distinct supported signal (for example,
independently scoped page-region evidence), frozen independently before testing and evaluated
on the same controls. Do not choose the second peak, hardcode x996 or route by transcription.
Priority remains acquiring independent table labels/negative pages, original ICC-bearing scan
PDFs with line transcriptions, and genuinely unused acceptance originals. Existing sidebar
main-stream grouping and structural/row-association contracts remain unchanged.
