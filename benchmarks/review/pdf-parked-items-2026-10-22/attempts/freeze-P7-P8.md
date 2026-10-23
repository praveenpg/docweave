# P7 and P8 attempts freeze (written before running the prototypes)

## P7: two candidate output contracts, prototyped on owned fixtures (evaluation only, no API)

Fixtures (owned PDFBox pages, 200x200, image = opaque red 40x40 unit square placed at x 40-80,
y 40-80 displayed, 72 DPI): (1) plain; (2) rectangular clip to the left half; (3) graphics-state
non-stroking alpha 0.5; (4) later blue rectangle overpainting the image; (5) image inside a form
transparency group drawn with group alpha 0.5; (6) the same XObject drawn twice, target = second
occurrence at x 120-160; (7) blend mode Multiply; (8) graphics-state luminosity soft mask whose
group paints white on the left half and black on the right half.

Hand-derived expected alpha at the image centre-left (x 50) and centre-right (x 70), y 60, and at
the second placement (x 140) for (6):
- Attempt 1, ISOLATED CONTRIBUTION (render only the target image invocation with its full graphics
  state onto a transparent backdrop, by suppressing all other painting operators):
  (1) 255/255; (2) 255/0; (3) ~128/~128; (4) 255/255 (overpaint ignored); (5) ~128/~128;
  (6) 0 at x 50, 255 at x 140; (7) 255/255 red (multiply over transparent = source);
  (8) 255/0 (mask applies).
- Attempt 2, FINAL VISIBILITY (pixels of the full page that change when only the target invocation
  is suppressed; alpha 255 where changed): (1) 255/255; (2) 255/0; (3) 255/255 (changed, not alpha);
  (4) 0/0 (overpainted); (5) 255/255; (6) 0 at x 50, 255 at x 140; (7) 255/255; (8) 255/0.
Pass = every sampled value within 8 levels of the expectation. Each failing fixture is a documented
limitation of that contract/implementation. No runtime API is added in this pass either way.

## P8: two attempts to reproduce a crop-renderer defect

Attempt 1: hand-derived fractional placement sweep. A 1x1-unit black square at x offsets 0, 0.25, 0.5,
0.75 within a pixel, cropped with BOX_4X at 72 DPI on WHITE: expected covered-pixel luminance
255 - 255 x coverage per pixel (coverage from offsets, 4x4 sampling quantized to 1/16). Pass = within
16 levels on every pixel (one sample).
Attempt 2: independent renderer comparison on owned fixtures where Poppler is available locally
(pdftoppm, no download): same owned pages rendered by Poppler at 288 DPI and box-averaged to 72 DPI
vs BOX_4X crop; report max channel difference. A difference > 16 on an analytically simple fixture
would be a defect candidate. If Poppler is unavailable locally, attempt 2 is recorded as blocked.
