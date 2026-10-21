# Fixed median scan experiment: no production adoption

This JVM-only experiment applies one fixed3x3 per-channel RGB median with replicated
borders to the same four original scans, two pinned models and three thresholds. Source
selection, references, geometry association, normalization, source300/600DPI and all24
case/profile denominators remain unchanged. There is no kernel search, per-page best
selection, model installation or production API/default change.

All24 OCR calls succeed on actual Java21/25 with the same pinned Tesseract5.5.3 executable.
Every numeric result and filtered raster hash agrees between JVMs; only random PDF container
identity is ignored. Original/rendered RGB hashes remain exactly unchanged. A separate
engine RGB hash explicitly identifies filtered pixels; unchanged dimensions do not imply
unchanged image content. Borrowed source pixels are checked after recognition too.

## Decision and complete tradeoffs

Do not adopt median filtering as a production enhancement. The owned pixel controls prove
that it can erase a legitimate thin stroke as well as isolated noise. Full metric deltas
remain in `verification.json`, including all misses, extra predictions and failures.
The following examples illustrate mixed outcomes; they are not a per-case routing policy:

| Fixed source/profile | Baseline | Median |
| --- | --- | --- |
|1835 Fraktur/default, declared-order character edits |51/1059 |48/1059 |
|1835 Fraktur/adaptive Otsu, declared-order character edits |59/1059 |162/1059 |
|1835 Fraktur/Sauvola, declared-order character edits |58/1059 |137/1059 |
|1879 German/default, exact associated lines |42/50 |41/50 |
|1778 Fraktur/Sauvola, missing reference / extra prediction lines |15 /12 |17 /19 |
|1780 Fraktur/Sauvola, missing reference / extra prediction lines |2 /12 |1 /5 |

1778 Sauvola gains one exact line4->5 while losing associated lines23->21. Its XML-sequence
character edits fall314->303; the1780 example falls123->67. Those three scans lack explicit
line-order labels, so XML-sequence diagnostics are not logical-page character error rates.
The sole declared-order source improves slightly under one setting and regresses sharply
under two. These development results do not establish representative scan quality.

## Reproduction and controls

Run `PdfHistoricalTextCorpusTest` with this manifest, all explicit existing executable/model/
source/fresh-report properties, source-resolution mode and
`-Ddocweave.pdf.historical.median3x3=true`. Defaults leave this experimental branch inactive.
The test-only helper creates an independent bounded RGB image and flushes it after the
engine call; it never mutates or flushes the borrowed raster, rescales coordinates or clears
interruption. Owned tests cover isolated noise, thin-stroke damage, per-channel color,
tiny replicated borders, pixel bounds and interrupt preservation.

Both full verification builds pass1,021 tests with zero failures/errors/skips and strict
public Javadocs. Changed private probe/test/harness/verifier Javadocs pass both JDKs.
The Java21 full build uses the default inactive probe; the Java25 full build includes all24
actual median OCR calls. A separate Java21 run supplies the paired24-case median report.

Run `CompareMedianReports.java` with the unchanged source-resolution baseline, both fresh
median reports and a fresh output path. It requires all24 matching case/profile keys,
identical engine/model/reference/source identities and complete cross-JVM equality while
retaining the intentionally filtered pixel provenance. Raw source assets stay outside Git.
This evidence does not warrant a new Central release or close historical/degraded-scan gates.
