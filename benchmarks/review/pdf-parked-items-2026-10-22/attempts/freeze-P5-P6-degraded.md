# P5 and P6 attempts freeze (written before any new OCR call or pixel measurement of these)

Common protocol = the frozen ALIGNMENT_COLUMN_V1 capture protocol: all four pinned scans
(1835, 1879, 1778, 1780) x {deu, deu_latf}, pinned executable/models, ImageIO colour-managed RGB
copied to TYPE_INT_RGB, AUTO segmentation, OEM1 default thresholding, NO engine DPI override,
one native thread, TesseractOcr.createWithWords with the same option limits. Baseline = the
retained captures of that protocol (docweave-source-review/pdf-ocr-column-alignment-20261022),
whose hashes are verified. New captures stay private. Scoring = the unchanged historical scorer
used by EvaluateOcrColumnAlignment (association, missing, extra, joint reference-line character
errors, global/xml-sequence character edits, XML-order pairs).

## P5 attempt 1: existing public small-angle deskew before OCR

PdfWordOcrProvider.deskewed(words engine, PdfOcrOptions(limits)) — the existing conservative
estimator/decorator (quarter-degree search, gates unchanged), coordinates mapped back. Regroup
with the existing WIDE_GAPS rule. Pixel skew observed beforehand from retained words only:
1778 about -0.33 deg, 1780 about 1.0 deg, 1835 -0.1, 1879 0.
Acceptance: 1778 strictly more associated lines under BOTH models with no increase in missing,
extra or joint character errors (vs baseline WIDE_GAPS); on 1835/1879/1780 no worsening of
associated, missing, extra, joint character errors or global/xml-sequence character edits.

## P5 attempt 2: ridge-based printed-rule split (no new OCR; retained words + pixels)

Luminance Y. Ridge response R(x,y) = max(0, (Y(x-2,y)+Y(x+2,y))/2 - Y(x,y)). A pixel is rule
evidence when R >= 8 (fixed, absolute). For each x, the longest vertical run of evidence rows,
bridging gaps of at most 2 rows. Candidate x must lie within the central 10%-90% of the page's
recognized-word extent. Accept the single x with the longest run, only if run >= 8 x median
recognized word height (the earlier printed-rule length criterion). For every OCR line whose
vertical span overlaps the run's extent, split at x when words exist on both sides and no word
box straddles x; combined with WIDE_GAPS boundaries; token order/text unchanged.
Distinct from the rejected global/local Otsu ink detectors (threshold-free relative contrast).
Acceptance: as ALIGNMENT_COLUMN_V1 primary: 1778 strictly more associated lines under both models,
no increase in missing/extra/joint errors, at least one added split; controls EXACTLY identical to
WIDE_GAPS output.

## P6 attempt 1: scanner-border masking T6 (as frozen in the earlier pass) run through OCR

Same transform as results/freeze/P6-freeze.md. The earlier pixel/replay check predicted no
targeted gain; this run measures it anyway at the user's request.

## P6 attempt 2: large non-text component suppression

Binarize with page-global Otsu on Y; 8-connected dark components. Let h = median component
height among components with height >= 4 px. Whiten (255) every component whose height > 6h or
width > 30h (decorations, vignettes, borders, rules). Dimensions/coordinates unchanged.

P6 acceptance (both attempts, raw TESSERACT_LINES output): a target gain on 1778 or 1780 (more
associated lines, or fewer extras, or fewer character edits) under both models, with NO metric
worse (associated, missing, extra, joint character errors, global/xml-sequence character edits) on
any scan/model. Otherwise rejected. No parameter changes after results.

## P4 transfer check (added after P4 attempt 1 passed, before running it here)

Variant DUAL_UNION under this same protocol: primary = managed words (baseline captures),
secondary = stored-sample recognition for profile-tagged non-sRGB three-band scans (1835, 1778 share
the scanner profile; 1780 Adobe RGB; 1879 gray -> no secondary). Non-overlapping secondary lines
added at top position (same union rule). Pass = no metric worse on any scan/model (raw lines).

## P5 attempt 2 transfer (frozen after the four-scan pass, before capturing these pages)

Pages buerger_gedichte_1778_0066 and nn_besuch_1780_00002 (labelled GT-PAGE, earlier used only for a
background-normalization transfer, never for column design). Capture baseline words under the same
protocol (4 calls), then apply the unchanged RULE variant. Pass = no metric worse than WIDE_GAPS on
either page/model; report whether the rule is accepted and how many splits occur.
