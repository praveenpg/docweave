# Degraded scan text baseline

Selection was frozen before source-image/XML fetch and recognition. In pinned OCR-D
18_frak_complex revision559892f099b32710c83d0dddbd21cde3128293a4, take the first lexical
page of the first lexical work whose first-page METS labels declare low contrast; independently
take the corresponding first work/page declaring stains. The selected pages are1778/0039
and1780/00001. Every source and metadata Git blob matches the pinned inventory. Models are
the same predeclared German and Fraktur profiles used by the historical baseline, each applied
to both pages. Raw scans, transcriptions and models remain outside Git.

The1778 scan visibly includes a two-column subscriber list, faded background printing and
uneven illumination; the1780 scan has a decorative heading, manuscript addition, stains and
an acquisition ruler. The PAGE text labels cover38 and10 lines respectively. Neither file
declares complete line reading order: retain XML sequence unchanged and report its edit
distances only as xml_sequence_* diagnostics. No whole-page logical CER/WER is validated.
Page-level source condition tags establish selection, not a calibrated severity scale.

| Page | Model | Recognized / reference lines | Geometry associations | Exact associated text | Missing reference / extra prediction lines |
| --- | --- | --- | --- | --- | --- |
| 1778 | deu | 27/38 | 18 | 0 | 20/9 |
| 1778 | deu_latf | 27/38 | 18 | 4 | 20/9 |
| 1780 | deu | 18/10 | 9 | 1 | 1/9 |
| 1780 | deu_latf | 18/10 | 9 | 3 | 1/9 |

These poor outcomes are retained. Counts compare whole-page OCR with the declared printed-text
reference, including predictions on unlabelled decoration, handwriting or acquisition material.
An unmatched prediction is not automatically hallucinated text. Geometry uses text-blind IoU
and one-to-one association; merged source lines can fail association despite partial recognition.
Local reference-line errors count missing lines but do not penalize extra predictions, so they
are not a complete page CER. Both models use the unchanged public OCR path, OEM1/PSM3 at72DPI,
one native thread and lossless whole-scan containers. No cropping, segmentation labels,
contrast enhancement, binarization or deskew is supplied to recognition.

The initial1780 wrapper retained TIFF ICC metadata and rendered different RGB values than
ImageIO decoded. Both initial reports are retained. The corrected wrapper copies decoded
opaque getRGB values into a canonical RGB image before lossless embedding, preventing a
second color interpretation. It changes no decoded pixel values or geometry; successful runs
now assert exact source/render RGB identity. See raster-qualification.json. Owned controls
cover ordinary RGB, grayscale and packed RGB. Production extraction behavior is unchanged.

Training overlap is unknown. These two pages are development diagnostics, not representative
historical/degraded accuracy, held-out acceptance or completed PDF scope. Next investigate
text segmentation and source-labelled layout without using reference geometry as OCR input.
Return separately to independent reference line-order review.

Reproduce using the historical harness properties documented in ../pdf-historical-text/README.md,
plus -Ddocweave.pdf.historical.manifest=benchmarks/review/pdf-degraded-text/manifest.json.
The default historical manifest remains unchanged. Reports include full source/model identities,
all failed associations and engine outcomes; no failing page or model is removed.

Full clean verify passes991 tests on actual Java21/25, with four real degraded OCR calls
each. Metrics and all source/render RGB hashes agree across JDKs. The four original
historical OCR cases were replayed on each JDK after the wrapper change: every text,
geometry and raster metric remains unchanged. Strict private Javadocs and formatter pass.
