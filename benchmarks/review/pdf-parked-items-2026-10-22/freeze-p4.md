# P4 freeze (written before measurement)

Item: 1835 page number "19" lost under colour-managed decode; stored samples recover it but
worsen body recognition (German 104->107, Fraktur 51->63). Rejected already: stored samples
as a switch, fixed 2x magnification.

## Distinct source-supported alternative

The scanner profile (OS12000_mG) declares a perceptual rendering intent; the managed decode
follows it. Hypothesis H4: converting the same source profile to sRGB with the ICC
relative-colorimetric intent (a profile-defined, source-supported alternative, not chosen from
recognition scores) keeps page-number ink near its stored darkness while staying close to the
managed decode elsewhere.

## Pixel-only feasibility measurement (no OCR, no model execution)

Probe on arnimb_goethe03_1835.tif (and 1778 for profile sharing), luminance Y = (299R+587G+114B)/1000:
- page-number box 645,320-689,356: minimum and mean Y under managed-perceptual, stored,
  relative-colorimetric;
- whole page: mean |Y - Y_managed| and fraction of pixels whose RGB differs from managed, for
  stored and relative-colorimetric.

## Frozen feasibility criteria

H4 is worth an OCR validation only if BOTH:
1. relative-colorimetric page-number minimum Y <= stored minimum + 5;
2. relative-colorimetric whole-page mean |dY| vs managed < half of stored's mean |dY| vs managed.
If the two intents produce identical pixels, or either criterion fails, H4 is rejected with the
measurements. Even if met, no runtime/default change: validation needs OCR execution
authorization (both pinned models, all four controls) and, for PDF applicability, Q2.
