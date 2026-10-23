# P6 freeze (written before any measurement of this transform)

Rejected already (not repeated): white-point/local background, sparse composition (PSM11),
unsharp sharpening, 2x magnification, stored-sample decode.

## Distinct source-image defect

Dark acquisition/scanner background bands at page edges. Independently observed by the earlier
printed-rule study (global Otsu long runs = page borders: 1778 x 2-259, 1780 x 1690-1997,
1835 x 1342-1672). None of the rejected transforms removes them.

## Frozen transform T6 (scanner-border masking)

On the managed-decode luminance raster Y=(299R+587G+114B)/1000:
1. t = page-global Otsu threshold of Y.
2. From each of the four edges, take the maximal run of consecutive columns (rows) whose mean
   Y is below t. That run is a border band.
3. Paint every pixel in border bands white (255). Raster dimensions and coordinates unchanged.
No per-page parameters, no reference input.

## Feasibility check (no OCR execution; replay of retained AUTO word captures only)

For all four scans:
- S (safety): number of reference PAGE TextLine boxes intersecting a masked band. Must be 0.
- G (possible gain): number of retained predicted lines (both models) whose box centre lies in a
  masked band, split by whether the prediction is associated with a reference line
  (any reference line box overlapping it by IoU>0) -> unassociated ones are removable extras.

## Frozen decision

- S > 0 on any scan: T6 rejected (it would delete labelled text).
- S = 0 and removable-extra count = 0 on all scans: T6 rejected (no possible gain; the border
  produces no predictions to remove; any recognition change would be untargeted).
- S = 0 and removable extras > 0: candidate for ONE OCR validation pass (both models, four
  scans, full metrics) — requires explicitly configured OCR execution.
  No runtime/default change in this pass either way.
