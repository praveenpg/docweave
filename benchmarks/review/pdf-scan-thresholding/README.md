# Explicit OCR thresholding

Typed Tesseract5 factory overloads expose engine default, explicit Otsu, adaptive Otsu and
Sauvola without changing TesseractOptions or its constructors. Existing factories retain
engine defaults. The policy selects only fixed thresholding_method arguments; construction
remains lazy and the hidden adapter shares its existing fair admission, deadline, temporary
file, raster, TSV and output limits. Page/cell segmentation stays call-local. Actual-word
requests still use one process and retain actual TSV word geometry. Borrowed pixels and
coordinates are unchanged; binarization happens internally in the admitted engine process.

The frozen experiment applies engine default, adaptive Otsu and Sauvola uniformly to all
four previously selected original scans, both pinned German models, with AUTO segmentation:
24 real calls on each JDK. Explicit global Otsu is argument-tested but not separately scored,
because the pinned engine's probed default is already0. No per-source exclusion, crop,
parameter tuning, dictionary repair, reference geometry or per-page winner routing occurs.
No source-DPI override or threshold window/factor tuning was supplied. Training overlap is
unknown; this is development evidence rather than representative/held-out accuracy.

| Year | Model | Threshold | Associated reference lines | Exact associated text | Missing reference | Extra predictions | Validated whole-page character edits |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1835 | deu | ENGINE_DEFAULT | 21 | 0 | 1 | 0 | 104 |
| 1835 | deu_latf | ENGINE_DEFAULT | 21 | 4 | 1 | 0 | 51 |
| 1835 | deu | ADAPTIVE_OTSU | 0 | 0 | 22 | 18 | 729 |
| 1835 | deu_latf | ADAPTIVE_OTSU | 0 | 0 | 22 | 18 | 696 |
| 1835 | deu | SAUVOLA | 22 | 1 | 0 | 0 | 102 |
| 1835 | deu_latf | SAUVOLA | 22 | 7 | 0 | 0 | 44 |
| 1879 | deu | ENGINE_DEFAULT | 49 | 42 | 1 | 1 | — |
| 1879 | deu_latf | ENGINE_DEFAULT | 49 | 18 | 1 | 1 | — |
| 1879 | deu | ADAPTIVE_OTSU | 49 | 42 | 1 | 1 | — |
| 1879 | deu_latf | ADAPTIVE_OTSU | 49 | 18 | 1 | 1 | — |
| 1879 | deu | SAUVOLA | 49 | 42 | 1 | 1 | — |
| 1879 | deu_latf | SAUVOLA | 49 | 18 | 1 | 1 | — |
| 1778 | deu | ENGINE_DEFAULT | 18 | 0 | 20 | 9 | — |
| 1778 | deu_latf | ENGINE_DEFAULT | 18 | 4 | 20 | 9 | — |
| 1778 | deu | ADAPTIVE_OTSU | 14 | 0 | 24 | 29 | — |
| 1778 | deu_latf | ADAPTIVE_OTSU | 14 | 0 | 24 | 29 | — |
| 1778 | deu | SAUVOLA | 5 | 0 | 33 | 28 | — |
| 1778 | deu_latf | SAUVOLA | 6 | 0 | 32 | 28 | — |
| 1780 | deu | ENGINE_DEFAULT | 9 | 1 | 1 | 9 | — |
| 1780 | deu_latf | ENGINE_DEFAULT | 9 | 3 | 1 | 9 | — |
| 1780 | deu | ADAPTIVE_OTSU | 1 | 0 | 9 | 3 | — |
| 1780 | deu_latf | ADAPTIVE_OTSU | 1 | 0 | 9 | 3 | — |
| 1780 | deu | SAUVOLA | 8 | 1 | 2 | 6 | — |
| 1780 | deu_latf | SAUVOLA | 8 | 3 | 2 | 6 | — |

Reference line counts are22(1835),50(1879),38(1778),10(1780). Only1835 has explicit complete
reference line order; its whole-page edit denominator is1059code points. The other original
PAGE sequences remain unchanged and retain xml_sequence_* diagnostic labels, not validated
logical-page CER/WER. Local reference-line errors include missing lines but do not charge
extra predictions. Unmatched OCR can include genuine manuscript/decoration/acquisition
content outside printed-text labels, so extra predictions are not automatically hallucinations.

Sauvola improves the1835 Fraktur result51 to44 whole-page character edits and4 to7 exact
associated lines; it recovers the missing reference line. It does not improve every source:
1778 associations fall18 to5(German)/6(Fraktur), with28 extra predictions, and1780 falls9
to8. Adaptive Otsu is substantially worse on1835 (zero geometry associations;729/696 edits)
and degrades both noisy sources. Retain every outcome. Engine default therefore stays the
unchanged factory default, and explicit alternatives have no universal quality guarantee.

All24 paired text/geometry/raster records agree on actual Java21/25. All source/render RGB
hashes match before engine processing, and all8 engine-default cases preserve the prior
AUTO baseline metrics/hashes (apart from random PDF IDs and the added explicit policy field).
Full996-test clean builds, strict public Javadocs and changed-source/private harness Javadocs
pass on both JDKs. Two owned subprocess methods verify actual arguments, concurrent page/
word calls, separate layout policies, lazy/private factories, null checks, typed failure/
timeout and cleanup. Original defaults omit the override; no new runtime dependency is added.

Reproduce with PdfHistoricalTextCorpusTest's explicit executable/tessdata/cache/fresh-report
properties and this manifest; keep OMP_THREAD_LIMIT=1 and the same pinned binary/models.
All source identities and complete references remain those of the earlier fixed scan manifests.
The diagnostic logs one content-free completion record per profile so long grids show progress.
Raw source scans, OCR text, models and full logs stay outside Git.

Tesseract's official image-quality documentation describes the adaptive algorithms:
https://tesseract-ocr.github.io/tessdoc/ImproveQuality.html . The pinned5.5.3 executable's
--print-parameters output independently confirms method0/1/2 and the engine defaults; its
hash is recorded in verification.json. Adaptive window sizes depend on image DPI; explicit
resolution propagation remains a separate investigation, not silently tuned for these cases.
