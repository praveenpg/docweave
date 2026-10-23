# Bounded passes on degraded scans (E) and rendered crops (F), 2026-10-22

Neither pass changes runtime code. No OCR engine, Unstructured run, model execution or download
was performed.

## F: crop-service contract probe — no defect found

The existing 19 crop tests cover a 90° rotation with an offset crop box, and UserUnit without
rotation. This probe covers the untested combinations. It authors a 40×40 four-colour quadrant
image, takes its displayed box from the public partitioner, crops that box through the public
`PdfPageCropRenderer`, and checks:

- every quadrant centre;
- a 2-pixel-inset pixel at each corner. These sampled locations must agree with the expected
  clockwise rotation; the probe does not check every output pixel.

Combinations covered (`ProbeCropFrames.java.txt`): five page-box geometries × rotations
0/90/180/270 × UserUnit 1 and 2 × `RENDERER`/`BOX_4X` × 72 and 150 DPI. The five geometries are:

- MediaBox at the origin;
- an offset CropBox;
- an offset MediaBox without a CropBox;
- negative-origin boxes;
- a CropBox extending beyond the MediaBox.

| Probe | Cases | Correct crops | Other outcomes |
| --- | ---: | ---: | --- |
| Frame geometry | 160 | 152 | 8 typed `IMAGE_LIMIT` refusals. `BOX_4X` at 150 DPI on a 300×300 page with UserUnit 2 needs about 25M render pixels against the default `maxPagePixels`; this is documented 16× cost behaviour, refused before allocation. |
| Non-normalized `/Rotate` (−90, −270, 450, 540, 720; plus invalid 45) | 120 | 100 | 20 typed rejections, all from the invalid 45° rotation ("must be an integer multiple of 90 degrees") |

`crop-frame-probe.txt` has the raw output. There is no geometry, frame, rounding or rotation
defect to fix, so Task F stays parked. This is a PDFBox-to-PDFBox consistency check, not an
independent appearance comparison.

## E: degraded columns — no new mechanism without OCR word geometry

`degraded-1778-reference-gaps.txt` measures the independent PAGE reference geometry of the 1778
scan: 38 lines, median height 31 px, 11 rows with two reference lines.

Inter-column gaps run from **0.52 to 6.97 line heights**. Five of the eleven rows have gaps of
0.52, 0.52, 1.06, 1.13 and 2.90 line heights. Two are in the range of ordinary word spacing,
which makes a simple gap-size threshold ambiguous. This supports keeping the previously rejected
gap approaches parked (wide gaps, page gutter, uncrossed gutter); it does not prove that every
method combining whitespace with other page evidence must fail.

The remaining source-level signals are:

- **The faint printed rule.** Global and local Otsu detection have both failed.
- **Left-edge alignment of the right column.** Nine of the eleven rows start at x 972–996; two
  indented entries start at 1042 and 1088.

Alignment is distinct from the rejected mechanisms, but it has a weak prior. The earlier
uncrossed-gutter diagnostic found a competing interior alignment at x≈1023, names after ditto
dashes inside the right column.

Testing alignment needs OCR word boxes for all four scans under both pinned models, which means
running the pinned local Tesseract. That execution was not authorized for this pass. **Status:
PARKED.** The next bounded action, if authorized, is to freeze an alignment rule with the 1879
single-column and 1780 negatives, then run one validation.
